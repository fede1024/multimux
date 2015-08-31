(ns multimux.connection
  (:require [taoensso.timbre :as log]
            [clojure.core.async :refer [>!! <!! chan] :as async])
  (:import [com.jcraft.jsch JSch JSchException Session Channel]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.net Socket]
           [java.io OutputStream InputStream BufferedReader InputStreamReader]
           [java.util Arrays]
           [java.lang Process ProcessBuilder ProcessBuilder$Redirect]))

(defprotocol ConnectionProtocol
  (open [c] "Open the connection")
  (close [c] "Close the connection"))

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
   :close close-socket-connection})


;; Connection over ssh to a unix socket
(defrecord SSHConnection [^String username ^String password ^String host ^String command ^Session session
                          ^Channel channel ^ManyToManyChannel stdout ^ManyToManyChannel stdin])

(defn create-ssh-connection [username password host command]
  (map->SSHConnection {:username username :password password :host host :command command}))

(def jsch (JSch.))

(defn create-ssh-session [username password host & {:keys [host-key user-info]}]
  (let [session (.getSession jsch username host)
        known-host-repository (.getHostKeyRepository session)]
    (when host-key
      (log/warn "Adding ssh host key for" (.getHost session))
      (.add known-host-repository host-key user-info))
    (.setPassword session password)
    (try
      (.connect session 20000)
      session
      (catch JSchException e
        (if (and (.startsWith (.getMessage e) "UnknownHostKey") (not host-key) (not user-info))
          (create-ssh-session username password host :host-key (.getHostKey session)
                              :user-info (.getUserInfo session))
          (log/error e))))))

(defn ssh-exec-channel [session command]
  (let [channel (.openChannel session "shell")
        ;error-stream (.getErrStream channel)
        ]
    ;(async/thread
    ;  (let [reader (BufferedReader. (InputStreamReader. error-stream))]
    ;    (loop []
    ;      (when-let [line (.readLine reader)]
    ;        (log/warn "SSH stderr:" line)
    ;        (recur))))
    ;  (log/info "SSH stderr worker terminated"))
    ;(.setCommand channel command)
    (.setPty channel true)
    (.setPtyType channel "xterm")
    (.connect channel)
    channel))

(defn start-stdout-worker [input-stream]
  (let [out-chan (chan)]
    (async/thread
      (let [buf (byte-array 1024)]
        (loop [len (.read input-stream buf)]
          (when (and (> len 0)
                     (async/>!! out-chan (Arrays/copyOf buf len)))
            (recur (.read input-stream buf)))))
      (log/info "Terminating stdout worker"))
    out-chan))

(defn start-stdin-worker [output-stream]
  (let [in-chan (chan)]
    (async/thread
      (loop [data (async/<!! in-chan)]
        (when (not (nil? data))
          (.write output-stream data)
          (.flush output-stream)
          (recur (async/<!! in-chan))))
      (log/info "Terminating stdin worker"))
    in-chan))

(defn open-ssh-connection [conn]
  (if-let [session (create-ssh-session (:username conn) (:password conn) (:host conn))]
    (let [channel (ssh-exec-channel session (:command conn))
          out-chan (start-stdout-worker (.getInputStream channel))
          in-chan (start-stdin-worker (.getOutputStream channel))]
      ;; TODO: add protocol message to check for connection
      (assoc conn :session session :jsch-channel channel :stdout out-chan :stdin in-chan))))

(defn close-ssh-connection [conn]
  (.disconnect (:jsch-channel conn))
  (.disconnect (:session conn))
  (dissoc conn :session :jsch-channel :stdout :stdin))

(extend SSHConnection
  ConnectionProtocol
  {:open open-ssh-connection
   :close close-ssh-connection})


;; Connection over ssh to a unix socket

(def unix-socket-path "/tmp/mm.sock")
(def socat-command (str "socat STDIO UNIX-CONNECT:" unix-socket-path))

(defn create-ssh-unix-connection [username password host]
  (->SSHConnection username password host socat-command nil nil nil nil nil))

;; (def conn (open (create-ssh-connection "fede" (System/getenv "TEST_PWD") "localhost" "screen")))
