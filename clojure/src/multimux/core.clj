(ns multimux.core
  (:gen-class)
  (:require [multimux.connectors :as connectors]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [javax.swing JFrame JScrollBar JPanel JSplitPane SwingUtilities]
           [java.awt.event WindowListener KeyEvent KeyListener WindowEvent]
           [java.awt Dimension GridLayout Font]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [com.jediterm.terminal ProcessTtyConnector TerminalMode RequestOrigin TerminalColor TextStyle]
           [com.jediterm.terminal.ui JediTermWidget TerminalPanel$TerminalKeyHandler]
           [com.jediterm.terminal.ui.settings DefaultSettingsProvider]
           [java.nio.charset Charset]
           [java.nio CharBuffer ByteBuffer]
           [java.io File InputStreamReader ByteArrayOutputStream FileOutputStream]
           [java.net Socket]
           [java.util Arrays]
           [org.apache.log4j BasicConfigurator Level Logger]
           [org.apache.avro.generic GenericData$Record GenericDatumWriter GenericDatumReader]
           [org.apache.avro Schema$Parser]
           [org.apache.avro.io BinaryEncoder BinaryDecoder EncoderFactory DecoderFactory]
           [org.apache.avro.file DataFileWriter DataFileReader DataFileStream]))

(defn configure-logger! []
  (log/merge-config!
    {:timestamp-pattern "yyyy-MMM-dd HH:mm:ss"
     :fmt-output-fn (fn [{:keys [level throwable message timestamp hostname ns]}
                         & [{:keys [nofonts?] :as appender-fmt-output-opts}]]
                      ;; <timestamp> <LEVEL> [<ns>] - <message> <throwable>
                      (format "%s %s [%s] - %s%s"
                              timestamp (-> level name str/upper-case) ns (or message "")
                              (or (log/stacktrace throwable "\n" (when nofonts? {})) "")))}))

(defn create-connection [server]
  (try
    (Socket. (:host server) (:port server))
    (catch java.net.ConnectException e
      (log/warn "Connection failure" e)
      nil)))

(def parser (Schema$Parser.))

(def inputOutputSchema (.parse parser (File. "../avro/InputOutput.avsc")))
(def resizeSchema (.parse parser (File. "../avro/Resize.avsc")))
(def registerToProcessSchema (.parse parser (File. "../avro/RegisterToProcess.avsc")))
(def messageSchema (.parse parser (File. "../avro/Message.avsc")))

(defn settings-provider []
  (proxy [DefaultSettingsProvider] []
    (maxRefreshRate [] 50)
    (scrollToBottomOnTyping [] true)
    (getBufferMaxLinesCount [] 1000)
    (useAntialiasing [] true)
    (getTerminalFontSize [] 22)
    (getDefaultStyle []
      (TextStyle. (TerminalColor. 255 255 255) (TerminalColor. 6 26 39)))
    (getTerminalFont []
      (.deriveFont (Font/decode "DejaVu Sans Mono for Powerline") 22.0))))

(defn socket-to-chan [server readChan writeChan]
  (let [socket (create-connection server)
        inputStream (.getInputStream socket)
        outputStream (.getOutputStream socket)]
    (async/thread    ; Reading thread
      (let [buf (make-array Byte/TYPE 1024)]
        (loop [bytesIn (.read inputStream buf)]
          (when (not (= bytesIn -1)) ; TODO: check closed channel?
            (let [data (make-array Byte/TYPE bytesIn)]
              (System/arraycopy buf 0 data 0 bytesIn)
              (>!! readChan data))
            (recur (.read inputStream buf))))))
    (async/thread    ; Writing thread
      (loop [data (<!! writeChan)]
        (when data
          (.write outputStream data)
          (recur (<!! writeChan)))))))

(defn msg-connection [socket msg-read-chan msg-write-chan]
  (when (not socket)
    (throw (Exception. "Socket is nil")))
  (async/thread
    (try
      (let [in (.getInputStream socket)
            reader (GenericDatumReader. messageSchema)
            decoder (.directBinaryDecoder (DecoderFactory/get) in nil)]
        (loop [msg (.read reader nil decoder)]
          (>!! msg-read-chan msg)
          (recur (.read reader nil decoder))))
      (catch java.io.EOFException e
        (log/warn "Socket exception" e))
      (catch java.net.SocketException e
        (log/warn "Socket exception" e))))
  (async/thread
    (try
      (let [out (.getOutputStream socket)
            writer (GenericDatumWriter. messageSchema)
            encoder (.binaryEncoder (EncoderFactory/get) out nil)]
        (loop [msg (<!! msg-write-chan)]
          (.write writer msg encoder)
          (.flush encoder)
          (recur (<!! msg-write-chan))))
      (catch java.io.EOFException e
        (log/warn "Interrupted stream" e))
      (catch java.net.SocketException e
        (log/warn "Socket exception" e)))))

(defn create-message [messageType data]
  (let [message (GenericData$Record. messageSchema)]
    (.put message "messageType" messageType)
    (.put message "data" data)
    message))

(defn create-stdin-message [processId bytes]
  (let [inputOutput (GenericData$Record. inputOutputSchema)]
    (.put inputOutput "process" (int processId))
    (.put inputOutput "bytes" (ByteBuffer/wrap bytes))
    (create-message "stdin" inputOutput)))

(defn create-resize-message [processId size]
  (let [[rows cols width height] size
        resize (GenericData$Record. resizeSchema)]
    (.put resize "rows" rows)
    (.put resize "cols" cols)
    (.put resize "xpixel" width)
    (.put resize "ypixel" height)
    (.put resize "process" (int processId))
    (create-message "resize" resize)))

(defn create-register-to-process-message [processId]
  (let [registerToProcess (GenericData$Record. registerToProcessSchema)]
    (.put registerToProcess "process" (int processId))
    (create-message "registerToProcess" registerToProcess)))

(defn handle-term-write [[type payload]]
  (condp = type
    :input (create-stdin-message 1234 payload)
    :resize (create-resize-message 1234 payload)))

(defprotocol TerminalHandler
  (read-stdout [this] "Handles an stdout read request")
  (write-stdin [this data] "Handles an stdin write request")
  (resize [this rows cols xpixel ypixel] "Handles a resize request")
  (key-pressed [this event] "Handles a key press. It returns true if the key event should not be sent to
                             the terminal (it's recognized shortcut)."))

(defn decode-term-data [data]
  (let [inputOutput (.get data "data")]
    (.array (.get inputOutput "bytes"))))

(defn message-handler [msg-read-chan msg-write-chan stdin stdout]
  (async/thread
    (let [channels [msg-read-chan stdout]]
      (loop [[data chan] (alts!! channels)]
        (condp = chan
          msg-read-chan (>!! stdin (decode-term-data data))
          stdout        (>!! msg-write-chan (handle-term-write data)))
        (recur (alts!! channels))))))

;(defrecord Terminal [^int id ^JediTermWidget widget ^ManyToManyChannel stdout ^ManyToManyChannel stdin])

(defrecord MultimuxTerminal [id widget])

(extend MultimuxTerminal
  connectors/TerminalHandler
  {:read-stdout (fn [this] (Thread/sleep 10000) (println 'read) (.getBytes "lol" (Charset/forName "UTF-8")))
   :write-stdin (fn [this data] (println 'wriiite data))
   :resize (fn [this rows cols xpixel ypixel] (println 'resssisss))
   :key-pressed (fn [this event] (println event))})

(defn get-scrollbar [term-widget]
  (first (filter #(= (type %) javax.swing.JScrollBar)
                 (.getComponents term-widget))))

(defn create-widget [columns rows terminal-handler]
  (let [term-widget (JediTermWidget. columns rows (settings-provider))
        ;connector (connectors/tty-channel-connector term-read-chan term-write-chan (Charset/forName "UTF-8"))
        connector (connectors/tty-terminal-connector (->Terminal term-widget) (Charset/forName "UTF-8"))
        listener (proxy [TerminalPanel$TerminalKeyHandler] [(.getTerminalPanel term-widget)]
                   (keyPressed [event]
                     (when (not (key-pressed terminal-handler event))
                       (proxy-super keyPressed event))))]
    (.setTtyConnector term-widget connector)
    (.setModeEnabled (.getTerminal term-widget) (TerminalMode/CursorBlinking) false)
    (.setModeEnabled (.getTerminal term-widget) (TerminalMode/AutoWrap) true)
    (.setVisible (get-scrollbar term-widget) false)
    ;(.start term-widget)
    ;; substitutes JediTermWidget.start() to use setKeyListener
    (async/thread
      (.setName (Thread/currentThread) (str "Connector-" (.getName connector)))
      (when (.init connector nil)
        (.setKeyListener (.getTerminalPanel term-widget) listener)
        (.start (.getTerminalStarter term-widget))))
    term-widget))

; (defn get-focused-term-panel [frame]
;   (let [component (.getMostRecentFocusOwner frame)]
;     (if (= (type component) com.jediterm.terminal.ui.TerminalPanel)
;       component
;       (log/warn "Focused object is not a terminal"))))

; (defn get-term-container [term]
;   (condp = (type term)
;     com.jediterm.terminal.ui.TerminalPanel (.getParent (.getParent term))
;     com.jediterm.terminal.ui.JediTermWidget (.getParent term)
;     (log/warn "get-term-container of" (type term))))

; (defn resize-handler []
;   (proxy [JediTerminal$ResizeHandler] []
;     (sizeUpdated [width height cursorY]
;       (log/info "Terminal resized" width height cursorY))))

(defn split-panel [direction panel old-term-widget new-term-widget]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        split (JSplitPane. orientation true old-term-widget new-term-widget)]
    (.remove panel old-term-widget)
    (.add panel split)
    (.setResizeWeight split 0.5)
    (.revalidate panel)))

(defn split-splitpane [direction splitpane old-term-widget new-term-widget]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        new-split (JSplitPane. orientation true old-term-widget new-term-widget)]
    (.remove splitpane old-term-widget)
    (if (= (.getOrientation splitpane) JSplitPane/HORIZONTAL_SPLIT)
      (if (.getLeftComponent splitpane)
        (.setRightComponent splitpane new-split)
        (.setLeftComponent splitpane new-split))
      (if (.getTopComponent splitpane)
        (.setBottomComponent splitpane new-split)
        (.setTopComponent splitpane new-split)))
    (.setResizeWeight new-split 0.5)))

; (defn split [direction new-term]
;   {:pre [(direction #{:vertical :horizontal})]}
;   (if (not @frame)
;     (log/warn "No frame, nothing to split")
;     (let [term-panel (get-focused-term-panel @frame)
;           term-widget (.getParent term-panel)
;           container (.getParent term-widget)]
;       (condp = (type container)
;         javax.swing.JPanel (split-panel direction container term-widget new-term)
;         javax.swing.JSplitPane (split-splitpane direction container term-widget new-term))
;       (.requestFocus (.getTerminalPanel new-term)))))

(defn split-term [term-widget direction new-terminal]
  {:pre [(direction #{:vertical :horizontal})]}
  (let [term-panel (.getTerminalPanel term-widget)
        container (.getParent term-widget)]
    (condp = (type container)
      javax.swing.JPanel (split-panel direction container term-widget (:widget new-terminal))
      javax.swing.JSplitPane (split-splitpane direction container term-widget (:widget new-terminal)))
    (.requestFocus (.getTerminalPanel (:widget new-terminal))))
  new-terminal)

(defn close-jframe [frame]
  (.dispatchEvent frame (WindowEvent. frame WindowEvent/WINDOW_CLOSING)))

(def ^:dynamic *term-registry* (ref {}))

(defn create-and-register-terminal [columns rows key-listener]
  (let [terminal (create-term 75 28 key-listener)]
    (dosync
      (let [new-id (count @*term-registry*)
            term (assoc terminal :id new-id)]
        (alter *term-registry* assoc new-id term)
        term))))

(defn term-key-listener [term-widget event]
  (let [keyCode (.getKeyCode event)]
    (when (.isAltDown event)
      (condp = keyCode
        KeyEvent/VK_H (split-term term-widget :horizontal (create-and-register-terminal 80 24 term-key-listener))
        KeyEvent/VK_V (split-term term-widget :vertical (create-and-register-terminal 80 24 term-key-listener))
        KeyEvent/VK_Q (close-jframe (SwingUtilities/getWindowAncestor term-widget))
        nil))))

(defn swing []
  (let [newFrame (JFrame. "Multimux")
        ;settings (DefaultSettingsProvider.)
        terminal (create-and-register-terminal 75 28 term-key-listener)
        ;splitPane (JSplitPane. (JSplitPane/HORIZONTAL_SPLIT) true term empty-panel)
        ;connector (connectors/tty-process-connector (connectors/create-process) (Charset/forName "UTF-8"))
        ;connector (tty-socket-connector (create-connection {:host "localhost" :port 3333})
        ;                                (Charset/forName "UTF-8"))
        msg-read-chan (chan)
        msg-write-chan (chan)
        connection (create-connection {:host "localhost" :port 3333})]
    (msg-connection connection msg-read-chan msg-write-chan)
    ;(message-handler msg-read-chan msg-write-chan (:stdout terminal) (:stdin terminal))
    (doto newFrame
      (.add (:widget terminal))
      ;(.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
      (.addWindowListener
        (proxy [WindowListener]  []
          (windowOpened [evt])
          (windowActivated [evt])
          (windowDeactivated [evt])
          (windowClosing [evt]
            (when connection
              (.close connection))
            (log/info "GUI closed"))))
      (.setSize 1000 800)
      (.pack)
      (.setVisible true))))

(defn -main
  [& args]
  (BasicConfigurator/configure)
  (.setLevel (Logger/getRootLogger) (Level/INFO))
  (configure-logger!)
  (dosync (ref-set *term-registry* {}))
  (swing)
  (log/info "GUI started"))

(def message
  (let [inputOutput (GenericData$Record. inputOutputSchema)]
    (.put inputOutput "bytes" (ByteBuffer/wrap (.getBytes "date\n" (Charset/forName "UTF-8"))))
    (.put inputOutput "process" (int 1234))
    (create-message "stdin" inputOutput)))

(let [out (ByteArrayOutputStream.)
      writer (GenericDatumWriter. messageSchema)
      encoder (.binaryEncoder (EncoderFactory/get) out nil)
      file (FileOutputStream. "message.avro")]
  (.write writer message encoder)
  ;(.put message "messageType" "output")
  ;(.write writer message encoder)
  (.flush encoder)
  (.write file (.toByteArray out)))

(def tsocket (atom nil))

(defn test-read []
  (let [socket (create-connection {:host "localhost" :port 3333})
        in (.getInputStream socket)
        reader (GenericDatumReader. messageSchema)
        decoder (.directBinaryDecoder (DecoderFactory/get) in nil)]
    (reset! tsocket socket)
    (.read reader nil decoder)
    (println 'lettooo)
    (.read reader nil decoder)
    (println 'lettooo)
    (.read reader nil decoder)
    (println 'lettooo)))

; (let [socket (create-connection {:host "localhost" :port 3333})
;       in (.getInputStream socket)
;       stream (DataFileStream. (.getInputStream socket) (GenericDatumReader. messageSchema))]
;   messageSchema
;   )

(defn test-write []
  (let [out (.getOutputStream @tsocket)
        writer (GenericDatumWriter. messageSchema)
        encoder (.binaryEncoder (EncoderFactory/get) out nil)]
    (.write writer message encoder)
    (.flush encoder)))

; (test-write)

; (let [writer (DataFileWriter. (GenericDatumWriter. messageSchema))
;       file (File. "users.avro")]
;   (.create writer messageSchema file)
;   (.append writer user)
;   (.put user "ciao" "carletto")
;   (.append writer user)
;   (.close writer))
;
; (let [file (File. "users.avro")
;       reader (DataFileReader. file (GenericDatumReader. messageSchema))]
;   (for [user reader]
;     [(.get user "ciao") (.get user "favorite_number")]))

