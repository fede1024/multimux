(ns multimux.core
  (:gen-class)
  (:require [multimux.connectors :as connectors]
            [clojure.core.async :refer [>!! <!! chan] :as async])
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
           [org.apache.avro.io BinaryEncoder EncoderFactory]
           [org.apache.avro.file DataFileWriter DataFileReader]))

(defn create-connection [server]
  (try
    (Socket. (:host server) (:port server))
    (catch java.net.ConnectException e
      nil)))

; (defn to-bytes [chars]
;   (let [cb (CharBuffer/wrap chars)
;         bb (.encode (Charset/forName "UTF-8") cb) ]
;     (Arrays/copyOfRange (.array bb) (.position bb) (.limit bb))))

(defn settings-provider []
  (proxy [DefaultSettingsProvider] []
    (maxRefreshRate [] 50)
    (scrollToBottomOnTyping [] true)
    (getBufferMaxLinesCount [] 1000)
    (useAntialiasing [] true)
    (getTerminalFontSize [] 22)))

(def termReadChan (atom nil))
(def termWriteChan (atom nil))

(defn swing []
  (let [frame (JFrame. "Fund manager")
        settings (settings-provider)
        ;settings (DefaultSettingsProvider.)
        term (JediTermWidget. 75 28 settings)
        ;connector (tty-process-connector (create-process) (Charset/forName "UTF-8"))
        ;connector (tty-socket-connector (create-connection {:host "localhost" :port 3333})
        ;                                (Charset/forName "UTF-8"))
        readChan (chan)
        writeChan (chan)
        connector (connectors/tty-channel-connector readChan writeChan (Charset/forName "UTF-8"))
        session (.createTerminalSession term connector)]
    (reset! termReadChan readChan)
    (reset! termWriteChan writeChan)
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

(def schema (.parse (Schema$Parser.) (File. "message.avsc")))

(def message
  (let [message (GenericData$Record. schema)]
    (.put message "messageType" "input")
    ;(.put message "data" (.getBytes "lol" (Charset/forName "UTF-8")))
    (.put message "data" (ByteBuffer/wrap  (.getBytes "lol"  (Charset/forName "UTF-8"))))
    message))

(let [out (ByteArrayOutputStream.)
      writer (GenericDatumWriter. schema)
      encoder (.binaryEncoder (EncoderFactory/get) out nil)
      file (FileOutputStream. "message.avro")]
  (.write writer message encoder)
  (.put message "messageType" "output")
  (.write writer message encoder)
  (.flush encoder)
  (.write file (.toByteArray out)))

; (let [writer (DataFileWriter. (GenericDatumWriter. schema))
;       file (File. "users.avro")]
;   (.create writer schema file)
;   (.append writer user)
;   (.put user "ciao" "carletto")
;   (.append writer user)
;   (.close writer))
;
; (let [file (File. "users.avro")
;       reader (DataFileReader. file (GenericDatumReader. schema))]
;   (for [user reader]
;     [(.get user "ciao") (.get user "favorite_number")]))

