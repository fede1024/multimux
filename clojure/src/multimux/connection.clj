(ns multimux.connection
  (:require [taoensso.timbre :as log])
  (:import [java.net Socket]
           [java.io FileInputStream InputStreamReader FileOutputStream BufferedReader]
           [java.lang Process ProcessBuilder ProcessBuilder$Redirect]))

(defprotocol ConnectionProtocol
  (open [c] "Open the connection")
  (close [c] "Close the connection")
  (input-stream [c] "Get the input stream")
  (output-stream [c] "Get the output stream"))

;; Connection over a tcp socket
(defrecord SocketConnection [^String server ^int port ^Socket socket])

(defn create-socket-connection [server port]
  (->SocketConnection server port nil))

(defn open-socket-connection [conn]
  (try
    (assoc conn :socket (Socket. (:host conn) (:port conn)))
    (catch java.net.ConnectException e
      (log/warn "Connection failure" e)
      nil)))

(defn close-socket-connection [conn]
  (when (:socket conn)
    (.close (:socket conn))
    conn))

(defn get-socket-connection-input-stream [conn]
  (.getInputStream (:socket conn)))

(defn get-socket-connection-output-stream [conn]
  (.getOutputStream (:socket conn)))

(extend SocketConnection
  ConnectionProtocol
  {:open open-socket-connection
   :close close-socket-connection
   :input-stream get-socket-connection-input-stream
   :output-stream get-socket-connection-output-stream})

;; Connection over ssh to a unix socket
(defrecord SSHUnixConnection [^String username ^String host ^String unix-path ^Process proc])

(defn ssh-command [username host unix-path]
  ["ssh" "-f" (str username "@" host) "socat" "STDIO" (str "UNIX-CONNECT:" unix-path)]
  ["ssh" "fede@localhost" "echo ciaoooiojoij"]
  )

(defn create-ssh-unix-connection [username host unix-path]
  (let [pb (ProcessBuilder. (ssh-command username host unix-path))]
    (.redirectOutput pb ProcessBuilder$Redirect/PIPE)
    (.redirectInput pb ProcessBuilder$Redirect/PIPE)
    (.redirectError pb ProcessBuilder$Redirect/PIPE)
    ;(.redirectErrorStream pb true)
    (try
      (.start pb)
      (catch java.io.IOException e
        (log/warn "Connection failure" e)))))
