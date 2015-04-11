(ns multimux.terminal
  (:require [clojure.core.async :refer [>!! <!! chan] :as async])
  (:import [java.nio.charset Charset]
           [java.io InputStreamReader StringReader]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.awt Font]
           [com.jediterm.terminal TerminalMode TerminalColor TextStyle]
           [com.jediterm.terminal.ui JediTermWidget TerminalPanel$TerminalKeyHandler]
           [com.jediterm.terminal.ui.settings DefaultSettingsProvider]))

(def byte-array?
  (let [check (type (byte-array []))]
    (fn [arg] (instance? check arg))))

(defn message-to-stream
  "Read a message from a channel of byte arrays and return a string reader of it,
  based on the specified charset."
  [channel charset]
  (StringReader. (String. (<!! channel) charset)))

(defn tty-terminal-connector [terminal charset]
  (when (or (not (:keyboard terminal)) (not (:screen terminal)))
    (throw (Exception. "Channel is null")))
  (let [input-stream (atom (StringReader. ""))]
    (reify com.jediterm.terminal.TtyConnector
      (init [this q]
        (>!! (:keyboard terminal) [:initialize nil])
        (when chan true))
      (isConnected [this]
        (when chan true))
      (resize [this term-size pixel-size]
        (>!! (:keyboard terminal) [:resize [(.width term-size) (.height term-size)
                                            (.width pixel-size) (.height pixel-size)]]))
      (read [this buf offset length]
        (let [n (.read @input-stream buf offset length)]
          (if (= n -1)
            (let [stream (message-to-stream (:screen terminal) charset)]
              (reset! input-stream stream)
              (.read stream buf offset length))
            n)))
      (^void write [this ^bytes buf]
        (>!! (:keyboard terminal) [:input buf]))
      (^void write [this ^String buf]
        (>!! (:keyboard terminal) [:input (.getBytes buf charset)]))
      (getName [this] "channelTty")
      (close [this] nil)    ; TODO: fix
      (waitFor [this] 1)))) ; TODO: protocol wait?

(defrecord Terminal [^JediTermWidget widget ^ManyToManyChannel keyboard ^ManyToManyChannel screen
                    ^int process-id])

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

(defn get-scrollbar [term-widget]
  (first (filter #(= (type %) javax.swing.JScrollBar)
                 (.getComponents term-widget))))

(defn create-term [columns rows key-listener]
  (let [term-widget (JediTermWidget. columns rows (settings-provider))
        screen (chan 100)
        keyboard (chan 100)
        terminal (->Terminal term-widget keyboard screen -1)
        connector (tty-terminal-connector terminal (Charset/forName "UTF-8"))
        listener (proxy [TerminalPanel$TerminalKeyHandler] [(.getTerminalPanel term-widget)]
                   (keyPressed [event]
                     (when (not (key-listener term-widget event))
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
    terminal))

(defn get-term-size [terminal]
  (let [panel (.getTerminalPanel (:widget terminal))]
    [(.getColumnCount panel) (.getRowCount panel) (.getPixelWidth panel) (.getPixelHeight panel)]))

;; Directly connects a tty to a socket, not used
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
          (.isConnected socket)))
      (isConnected []
        (println 'conn)
        (when socket
          (.isConnected socket)))
      (resize [term-size pixel-size]
        (println "Resize to" (.width term-size) (.height term-size)
                 (.width pixel-size) (.height pixel-size)))
      (read [buf offset length]
        (println 'read buf offset length)
        (.read inputReader buf offset length))
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
      (waitFor [] 1))))

;; (defn create-process []
;;   (let [command (into-array String ["/bin/bash"])
;;         env (into-array String ["TERM=xterm-256color"])
;;         ;env (java.util.HashMap. (System/getenv))
;;         ]
;;     ;(PtyProcess/exec command env "/" false)
;;     (PtyProcess/exec command env)))

;; (defn tty-process-connector [process charset]
;;   (proxy [com.jediterm.terminal.ProcessTtyConnector] [process charset]
;;     (isConnected []
;;       (.isRunning process))
;;     (resizeImmediately []
;;       (let [term-size (proxy-super getPendingTermSize)
;;             pixel-size (proxy-super getPendingPixelSize)]
;;         (when (and term-size pixel-size)
;;           (println "Resize to" (.width term-size) (.height term-size))
;;           (.setWinSize process (WinSize. (.width term-size) (.height term-size)
;;                                          (.width pixel-size) (.height pixel-size))))))
;;     ;(read [buf offset length]
;;     ;  (println buf offset length)
;;     ;  (let [n (proxy-super read buf offset length)]
;;     ;    (println n)
;;     ;    n))
;;     (getName []
;;       "processTty")))
