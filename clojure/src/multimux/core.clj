(ns multimux.core
  (:gen-class)
  (:require [multimux.connectors :as connectors]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [javax.swing JFrame JLabel JButton]
           [java.awt.event WindowListener]
           [com.jediterm.terminal ProcessTtyConnector TerminalMode]
           [com.jediterm.terminal.ui JediTermWidget]
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

(defn create-connection [server]
  (try
    (Socket. (:host server) (:port server))
    (catch java.net.ConnectException e
      nil)))

; (defn to-bytes [chars]
;   (let [cb (CharBuffer/wrap chars)
;         bb (.encode (Charset/forName "UTF-8") cb) ]
;     (Arrays/copyOfRange (.array bb) (.position bb) (.limit bb))))

(def messageSchema (.parse (Schema$Parser.) (File. "message.avsc")))

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
    (let [in (.getInputStream socket)
          reader (GenericDatumReader. messageSchema)
          decoder (.directBinaryDecoder (DecoderFactory/get) in nil)]
      (loop [msg (.read reader nil decoder)]
        (>!! msgReadChan msg)
        (recur (.read reader nil decoder)))))
  (async/thread
    (let [out (.getOutputStream socket)
          writer (GenericDatumWriter. messageSchema)
          encoder (.binaryEncoder (EncoderFactory/get) out nil)]
      (loop [msg (<!! msgWriteChan)]
        (.write writer msg encoder)
        (.flush encoder)
        (recur (<!! msgWriteChan))))))

(defn create-input-message [bytes]
  (let [message (GenericData$Record. messageSchema)]
    (.put message "messageType" "input")
    (.put message "data" (ByteBuffer/wrap bytes))
    message))

(defn message-handler [msgReadChan msgWriteChan termReadChan termWriteChan]
  (async/thread
    (let [channels [msgReadChan termWriteChan]]
      (loop [[data chan] (alts!! channels)]
        (condp = chan
          msgReadChan   (>!! termReadChan (.array (.get data "data")))
          termWriteChan (>!! msgWriteChan (create-input-message data)))
        (recur (alts!! channels))))))

(def tr (atom nil))
(def tw (atom nil))

(defn swing []
  (let [frame (JFrame. "Fund manager")
        settings (settings-provider)
        ;settings (DefaultSettingsProvider.)
        term (JediTermWidget. 75 28 settings)
        server {:host "localhost" :port 3333}
        ;connector (connectors/tty-process-connector (connectors/create-process) (Charset/forName "UTF-8"))
        ;connector (tty-socket-connector (create-connection {:host "localhost" :port 3333})
        ;                                (Charset/forName "UTF-8"))
        msgReadChan (chan)
        msgWriteChan (chan)
        termReadChan (chan)
        termWriteChan (chan)
        connector (connectors/tty-channel-connector termReadChan termWriteChan (Charset/forName "UTF-8"))
        session (.createTerminalSession term connector)]
    (reset! tw termReadChan)
    (reset! tr termWriteChan)
    (msg-connection (create-connection server) msgReadChan msgWriteChan)
    (message-handler msgReadChan msgWriteChan termReadChan termWriteChan)
    (.start session)
    ;(.setModeEnabled (.getTerminal term) (TerminalMode/InsertMode) true)
    (.setModeEnabled (.getTerminal term) (TerminalMode/CursorBlinking) false)
    (doto frame
      (.add term)
      ;(.setDefaultCloseOperation JFrame/EXIT_ON_CLOSE)
      (.addWindowListener
        (proxy [WindowListener]  []
          (windowOpened [evt])
          (windowActivated [evt])
          (windowDeactivated [evt])
          (windowClosing [evt])))
      (.setSize 1000 800)
      (.setVisible true))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (BasicConfigurator/configure)
  (.setLevel (Logger/getRootLogger) (Level/INFO))
  (swing)
  ;(println (create-process))
  (println "Hello, World!"))


(def message
  (let [message (GenericData$Record. messageSchema)]
    (.put message "messageType" "input")
    (.put message "data" (ByteBuffer/wrap (.getBytes "date\n" (Charset/forName "UTF-8"))))
    message))

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

