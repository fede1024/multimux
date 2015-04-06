(ns multimux.core
  (:gen-class)
  (:require [multimux.connectors :as connectors]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [javax.swing JFrame JLabel JButton JTabbedPane JPanel JSplitPane]
           [java.awt.event WindowListener]
           [java.awt Dimension]
           [com.jediterm.terminal ProcessTtyConnector TerminalMode RequestOrigin]
           [com.jediterm.terminal.ui JediTermWidget]
           [com.jediterm.terminal.ui.settings DefaultSettingsProvider]
           [com.jediterm.terminal.model JediTerminal$ResizeHandler]
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
    (getTerminalFontSize [] 22)))

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

(defn msg-connection [socket msgReadChan msgWriteChan]
  (when (not socket)
    (throw (Exception. "Socket is nil")))
  (async/thread
    (try
      (let [in (.getInputStream socket)
            reader (GenericDatumReader. messageSchema)
            decoder (.directBinaryDecoder (DecoderFactory/get) in nil)]
        (loop [msg (.read reader nil decoder)]
          (>!! msgReadChan msg)
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
        (loop [msg (<!! msgWriteChan)]
          (.write writer msg encoder)
          (.flush encoder)
          (recur (<!! msgWriteChan))))
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

(defn register-to-process-message [processId]
  (let [registerToProcess (GenericData$Record. registerToProcessSchema)]
    (.put registerToProcess "process" (int processId))
    (create-message "registerToProcess" registerToProcess)))

(defn handle-term-write [[type payload]]
  (condp = type
    :input (create-stdin-message 1234 payload)
    :resize (create-resize-message 1234 payload)))

(defn decode-term-data [data]
  (let [inputOutput (.get data "data")]
    (.array (.get inputOutput "bytes"))))

(defn message-handler [msgReadChan msgWriteChan termReadChan termWriteChan]
  (async/thread
    (let [channels [msgReadChan termWriteChan]]
      (loop [[data chan] (alts!! channels)]
        (condp = chan
          msgReadChan   (>!! termReadChan (decode-term-data data))
          termWriteChan (>!! msgWriteChan (handle-term-write data)))
        (recur (alts!! channels))))))

(def panes (atom {}))

(def frame (atom nil))

(def connection (atom nil))

(defn create-term [columns rows]
  (let [term (JediTermWidget. columns rows (settings-provider))
        termReadChan (chan)
        termWriteChan (chan)
        connector (connectors/tty-channel-connector termReadChan termWriteChan (Charset/forName "UTF-8"))
        session (.createTerminalSession term connector)]
    (.start session)
    (.setModeEnabled (.getTerminal term) (TerminalMode/CursorBlinking) false)
    (.setModeEnabled (.getTerminal term) (TerminalMode/AutoWrap) true)
    [term termReadChan termWriteChan]))

(def last-focus (atom nil))

(defn get-focused-term-panel [frame]
  (let [component (.getMostRecentFocusOwner frame)]
    (if (= (type component) com.jediterm.terminal.ui.TerminalPanel)
      component
      (log/warn "Focused object is not a terminal"))))

(defn get-term-container [term]
  (condp = (type term)
    com.jediterm.terminal.ui.TerminalPanel (.getParent (.getParent term))
    com.jediterm.terminal.ui.JediTermWidget (.getParent term)
    (log/warn "get-term-container of" (type term))))

(defn resize-handler []
  (proxy [JediTerminal$ResizeHandler] []
    (sizeUpdated [width height cursorY]
      (log/info "Terminal resized" width height cursorY))))

(defn split-panel [direction panel old-term new-term]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        split (JSplitPane. orientation true old-term new-term)]
    (.remove panel old-term)
    (.add panel split)
    (.revalidate panel)
    (.setResizeWeight split 0.5)))

(defn split-splitpane [direction splitpane old-term new-term]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        new-split (JSplitPane. orientation true old-term new-term)]
    (.remove splitpane old-term)
    (if (= (.getOrientation splitpane) JSplitPane/HORIZONTAL_SPLIT)
      (if (.getLeftComponent splitpane)
        (.setRightComponent splitpane new-split)
        (.setLeftComponent splitpane new-split))
      (if (.getTopComponent splitpane)
        (.setBottomComponent splitpane new-split)
        (.setTopComponent splitpane new-split)))
    (.revalidate splitpane)
    (.revalidate new-split)
    (.setResizeWeight new-split 0.5)))

(defn split [direction]
  {:pre [(direction #{:vertical :horizontal})]}
  (if (not @frame)
    (log/warn "No frame, nothing to split")
    (let [term-panel (get-focused-term-panel @frame)
          term-widget (.getParent term-panel)
          container (.getParent term-widget)
          [new-term term-read-chan term-write-chan] (create-term 80 24)]
      (condp = (type container)
        javax.swing.JPanel (split-panel direction container term-widget new-term)
        javax.swing.JSplitPane (split-splitpane direction container term-widget new-term))
      (.requestFocus (.getTerminalPanel new-term)))))

(defn swing []
  (let [newFrame (JFrame. "Fund manager")
        ;settings (DefaultSettingsProvider.)
        [term termReadChan termWriteChan] (create-term 75 28)
        ;splitPane (JSplitPane. (JSplitPane/HORIZONTAL_SPLIT) true term empty-panel)
        ;connector (connectors/tty-process-connector (connectors/create-process) (Charset/forName "UTF-8"))
        ;connector (tty-socket-connector (create-connection {:host "localhost" :port 3333})
        ;                                (Charset/forName "UTF-8"))
        msgReadChan (chan)
        msgWriteChan (chan)]
    (reset! connection (create-connection {:host "localhost" :port 3333}))
    (reset! frame newFrame)
    (msg-connection @connection msgReadChan msgWriteChan)
    (message-handler msgReadChan msgWriteChan termReadChan termWriteChan)
    (doto newFrame
      (.add term)
      ;(.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
      (.addWindowListener
        (proxy [WindowListener]  []
          (windowOpened [evt])
          (windowActivated [evt])
          (windowDeactivated [evt])
          (windowClosing [evt]
            (when @connection
              (.close @connection))
            (log/info "GUI closed"))))
      (.setSize 1000 800)
      (.pack)
      (.setVisible true))))

(defn -main
  [& args]
  (BasicConfigurator/configure)
  (.setLevel (Logger/getRootLogger) (Level/INFO))
  (configure-logger!)
  (swing)
  (log/info "GUI started"))

(defn demo []
  (-main)
  (Thread/sleep 1000)
  (split :vertical)
  (Thread/sleep 1000)
  (split :horizontal)
  (Thread/sleep 1000)
  (split :vertical)
  (Thread/sleep 1000)
  (split :horizontal))

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

