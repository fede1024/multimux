(ns multimux.connectors
  (:gen-class)
  (:require [clojure.core.async :refer [>!! <!! chan] :as async])
  (:import [java.nio.charset Charset]
           [java.nio CharBuffer ByteBuffer]
           [java.io File InputStreamReader ByteArrayOutputStream StringReader FileOutputStream]
           [java.net Socket]
           [java.util Arrays]
           [com.pty4j PtyProcess WinSize]
           [com.pty4j.util PtyUtil]
           [org.apache.log4j BasicConfigurator Level Logger]
           [org.apache.avro.generic GenericData$Record GenericDatumWriter GenericDatumReader]
           [org.apache.avro Schema$Parser]
           [org.apache.avro.io BinaryEncoder EncoderFactory]
           [org.apache.avro.file DataFileWriter DataFileReader]))

(def byte-array?
  (let [check (type (byte-array []))]
    (fn [arg] (instance? check arg))))

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
      (waitFor [] 1)))) ; TODO: protocol wait

(defn create-process []
  (let [command (into-array String ["/bin/bash"])
        env (into-array String ["TERM=xterm-256color"])
        ;env (java.util.HashMap. (System/getenv))
        ]
    ;(PtyProcess/exec command env "/" false)
    (PtyProcess/exec command env)))

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

(defn message-to-stream [channel charset]
  (StringReader. (String. (<!! channel) charset)))

(defn tty-channel-connector [readChan writeChan charset]
  (when (or (not readChan) (not writeChan))
    (throw (Exception. "Channel is null")))
  (let [input-stream (atom (StringReader. ""))]
    (proxy [com.jediterm.terminal.TtyConnector] []
      (init [q]
        (println 'init chan)
        (when chan true))
      (isConnected []
        (println 'conn)
        (when chan true))
      (resize [term-size pixel-size]
        (println "Resize to" (.width term-size) (.height term-size)
                 (.width pixel-size) (.height pixel-size)))
      (read [buf offset length]
        (println 'read buf offset length)
        (let [n (.read @input-stream buf offset length)]
          (if (= n -1)
            (do
              (reset! input-stream (message-to-stream readChan charset))
              (.read @input-stream buf offset length))
            n)))
      (write [buf]
        (println 'write buf (count buf))
        (>!! writeChan buf))
      (getName []
        "socketTty")
      (close []
        nil)  ; TODO: fix
      (waitFor [] 1)))) ; TODO: protocol wait?
