(ns multimux.core
  (:gen-class)
  (:require [multimux.terminal :as term]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [javax.swing JFrame JScrollBar JPanel JSplitPane SwingUtilities]
           [java.awt.event WindowListener KeyEvent KeyListener WindowEvent]
           [java.awt GridLayout]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [com.jediterm.terminal.ui JediTermWidget TerminalPanel$TerminalKeyHandler]
           [java.nio.charset Charset]
           [java.nio CharBuffer ByteBuffer]
           [java.io File InputStreamReader ByteArrayOutputStream FileOutputStream]
           [java.net Socket]
           [java.util Arrays]
           [org.apache.log4j BasicConfigurator Level Logger]
           [org.apache.avro.generic GenericData$Record GenericData$EnumSymbol GenericDatumWriter GenericDatumReader]
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
    (.put inputOutput "processId" (int processId))
    (.put inputOutput "bytes" (ByteBuffer/wrap bytes))
    (create-message "stdin" inputOutput)))

(defn create-resize-message [processId size]
  (let [[rows cols width height] size
        resize (GenericData$Record. resizeSchema)]
    (.put resize "rows" rows)
    (.put resize "cols" cols)
    (.put resize "xpixel" width)
    (.put resize "ypixel" height)
    (.put resize "processId" (int processId))
    (create-message "resize" resize)))

(defn create-register-to-process-message [processId]
  (let [registerToProcess (GenericData$Record. registerToProcessSchema)]
    (.put registerToProcess "processId" (int processId))
    (create-message "registerToProcess" registerToProcess)))

(defn decode-message [^GenericData$Record message]
  (let [message-type (keyword (str (.get message "messageType")))
        ^GenericData$Record payload (.get message "data")]
    (merge {:message-type message-type}
           (condp = message-type
             :stdout {:process-id (int (.get payload "processId")) :bytes (.array (.get payload "bytes"))}))))

; (defn message-handler [msg-read-chan msg-write-chan stdin stdout]
;   (async/thread
;     (let [channels [msg-read-chan stdout]]
;       (loop [[data chan] (alts!! channels)]
;         (condp = chan
;           msg-read-chan (>!! stdin (decode-term-data data))
;           stdout        (>!! msg-write-chan (handle-term-write data)))
;         (recur (alts!! channels))))))

(defn incoming-message-handler [message chan term-register]
  (condp = (:message-type message)
    :stdout (doseq [term (get-in term-register [:followers (:process-id message)])]
              (>!! (:screen term) (:bytes message)))
    (log/error "Unknown message type" (:message-type message))))

(defn term-write-handler [[input-type payload] keyboard-chan term-register msg-write-handler]
  (if-let [process-id (:process-id (get (:terminals term-register) keyboard-chan))]
    (let [message (condp = input-type
                    :input (create-stdin-message process-id payload)
                    :resize (create-resize-message process-id payload)
                    :initialize (log/error "Terminal is already initialized"))]
      (when message (>!! msg-write-handler message)))
    (if (= input-type :initialize)
      (>!! msg-write-handler (create-register-to-process-message -1))
      (log/warn "No process id associated to message" input-type))))

(defn message-handler [msg-read-chan msg-write-chan term-register]
  (async/thread
    (loop []
      (let [[data chan] (alts!! (conj (keys (:terminals @term-register)) msg-read-chan))]
        (if (= chan msg-read-chan)
          (incoming-message-handler (decode-message data) chan @term-register)
          (term-write-handler data chan @term-register msg-write-chan)))
      (recur))))

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

(def ^:dynamic *term-register* (ref nil))

(defrecord TermRegister [terminals followers])

(defn create-term-register []
  (->TermRegister {} {}))

(defn add-term-to-register [register terminal]
  (assoc-in register [:terminals (:keyboard terminal)] terminal))

(defn register-follow-process [register terminal process-id]
  (let [term (assoc terminal :process-id process-id)]
   (-> register
      (update-in [:followers process-id] #(if % (conj % term) #{term}))
      (assoc-in [:terminals (:keyboard terminal)] term))))

(defn create-terminal-and-process [columns rows key-listener]
  (let [terminal (term/create-term columns rows key-listener)]
    (dosync (alter *term-register* add-term-to-register terminal))
    terminal))

(defn term-key-listener [term-widget event]
  (let [keyCode (.getKeyCode event)]
    (when (.isAltDown event)
      (condp = keyCode
        KeyEvent/VK_H (split-term term-widget :horizontal (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_V (split-term term-widget :vertical (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_Q (close-jframe (SwingUtilities/getWindowAncestor term-widget))
        nil))))

(defn create-and-show-frame [title on-close]
  (let [newFrame (JFrame. title)
        terminal (create-terminal-and-process 75 28 term-key-listener)]
    (doto newFrame
      (.add (:widget terminal))
      ;(.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
      (.addWindowListener
        (proxy [WindowListener]  []
          (windowOpened [evt])
          (windowActivated [evt])
          (windowDeactivated [evt])
          (windowClosing [evt]
            (on-close)
            (log/info "GUI closed"))))
      (.setSize 1000 800)
      (.pack)
      (.setVisible true))))

(defn -main [& args]
  (BasicConfigurator/configure)
  (.setLevel (Logger/getRootLogger) (Level/INFO))
  (configure-logger!)
  (dosync (ref-set *term-register* (create-term-register)))
  (if-let [connection (create-connection {:host "localhost" :port 3333})]
    (let [msg-read-chan (chan 100)
          msg-write-chan (chan 100)]
      (create-and-show-frame "Multimux" #(when connection (.close connection)))
      (dosync
        (alter *term-register* register-follow-process (first (vals (:terminals @*term-register*))) 0))
      (msg-connection connection msg-read-chan msg-write-chan)
      ;(message-handler msg-read-chan msg-write-chan (:stdout (get @*term-registry* 0)) (:stdin (get @*term-registry* 0))))
      (message-handler msg-read-chan msg-write-chan *term-register*))
    (log/error "Connection not established"))
  (log/info "GUI started"))

;(dosync
;  (alter *term-register* register-follow-process (first (vals (:terminals @*term-register*))) 1))

(def message
  (let [inputOutput (GenericData$Record. inputOutputSchema)]
    (.put inputOutput "bytes" (ByteBuffer/wrap (.getBytes "date\n" (Charset/forName "UTF-8"))))
    (.put inputOutput "processId" (int 1234))
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

