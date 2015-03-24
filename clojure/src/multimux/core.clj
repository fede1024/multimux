(ns multimux.core
  (:gen-class)
  (:import [javax.swing JFrame JLabel JButton]
           [java.awt.event WindowListener]
           [com.jediterm.terminal ProcessTtyConnector TerminalMode]
           [com.jediterm.terminal.ui JediTermWidget]
           [com.jediterm.terminal.ui.settings DefaultSettingsProvider]
           [com.pty4j PtyProcess WinSize]
           [com.pty4j.util PtyUtil]
           [java.nio.charset Charset]
           [java.nio CharBuffer ByteBuffer]
           [java.io InputStreamReader]
           [java.net Socket]
           [java.util Arrays]
           [org.apache.log4j BasicConfigurator Level Logger]
           ))

(def byte-array?
  (let [check (type (byte-array []))]
    (fn [arg] (instance? check arg))))

(defn create-connection [server]
  (try
    (Socket. (:host server) (:port server))
    (catch java.net.ConnectException e
      nil)))

; (defn to-bytes [chars]
;   (let [cb (CharBuffer/wrap chars)
;         bb (.encode (Charset/forName "UTF-8") cb) ]
;     (Arrays/copyOfRange (.array bb) (.position bb) (.limit bb))))

(defn to-bytes [chars charset]
  (.getBytes (String. chars)))

(defn tty-socket-connector [socket charset]
  (when (not socket)
    (throw (Exception. "Socket is null")))
  (let [inputStream (.getInputStream socket)
        outputStream (.getOutputStream socket)
        inputReader (InputStreamReader. inputStream charset) ]
    (proxy [com.jediterm.terminal.TtyConnector] []
      (init [q]
        (println 'init socket)
        (when socket
          (.isConnected socket))) ; TODO: write real init?
      (isConnected []
        (println 'conn)
        (when socket
          (.isConnected socket))) ; TODO: use heartbeats
      (resize [term-size pixel-size]
        (println "Resize to" (.width term-size) (.height term-size)
                 (.width pixel-size) (.height pixel-size)))
      (read [buf offset length]
        (println 'read buf offset length)
        (let [n (.read inputReader buf offset length)
              a (Arrays/copyOfRange buf offset length) ]
          ;(println n (String. a))
          (println n)
          n
          ))
      (write [buf]
        (println 'write buf (count buf))
        (cond
          (byte-array? buf) (.write outputStream buf)
          (string? buf) (.write outputStream (.getBytes buf charset)))
        (.flush outputStream))
      (getName []
        "socketTty")
      (close []
        (.close socket))
      (waitFor [] 1)))) ; TODO: protocol wait

(defn tty-process-connector [process charset]
  (proxy [com.jediterm.terminal.ProcessTtyConnector] [process charset]
    (isConnected []
      (.isRunning process))
    (resizeImmediately []
      (let [term-size (proxy-super getPendingTermSize)
            pixel-size (proxy-super getPendingPixelSize)]
        (when (and term-size pixel-size)
          (println "Resize to" (.width term-size) (.height term-size))
          (.setWinSize process (WinSize. (.width term-size) (.height term-size)
                                         (.width pixel-size) (.height pixel-size))))))
    (read [buf offset length]
      (println buf offset length)
      (let [n (proxy-super read buf offset length)]
        (println n)
        n))
    (getName []
      "processTty")))

(defn create-process []
  (let [command (into-array String ["/bin/bash"])
        env (into-array String ["TERM=xterm-256color"])
        ;env (java.util.HashMap. (System/getenv))
        ]
    ;(PtyProcess/exec command env "/" false)
    (PtyProcess/exec command env)))

(defn settings-provider []
  (proxy [DefaultSettingsProvider] []
    (maxRefreshRate [] 50)
    (scrollToBottomOnTyping [] true)
    (getBufferMaxLinesCount [] 1000)
    (useAntialiasing [] true)
    (getTerminalFontSize [] 22)))

(defn swing []
  (let [frame (JFrame. "Fund manager")
        settings (settings-provider)
        ;settings (DefaultSettingsProvider.)
        term (JediTermWidget. 75 28 settings)
        ;connector (tty-process-connector (create-process) (Charset/forName "UTF-8"))
        connector (tty-socket-connector (create-connection {:host "localhost" :port 3333})
                                        (Charset/forName "UTF-8"))
        session (.createTerminalSession term connector)]
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
