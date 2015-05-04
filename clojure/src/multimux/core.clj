(ns multimux.core
  (:gen-class)
  (:require [multimux.terminal :as term]
            [multimux.serialize :as ser]
            [multimux.connection :as conn]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [javax.swing UIManager BorderFactory JFrame JScrollBar SwingUtilities]
           [java.awt.event WindowListener KeyEvent KeyListener WindowEvent]
           [java.awt Color]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [com.jediterm.terminal.ui JediTermWidget TerminalPanel$TerminalKeyHandler]
           [java.nio.charset Charset]
           [java.io File InputStreamReader ByteArrayOutputStream FileOutputStream]
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

(defn incoming-message-handler [message chan term-register]
  (condp = (:message-type message)
    :stdout (doseq [term (get-in @term-register [:followers (:process-id message)])]
              (>!! (:screen term) (:bytes message)))
    :createProcess (let [term (first (filter #(= (:process-id %) -1) (vals (:terminals @term-register))))]
                     (if term
                       (dosync (alter term-register term/register-follow-process term (:process-id message)))
                       (log/error "Received process creation feedback, but no unattached terminal is present")))
    (log/error "Unknown message type" (:message-type message))))

(defn term-write-handler [[input-type payload] keyboard-chan term-register msg-write-handler]
  (let [term (get (:terminals @term-register) keyboard-chan)]
    (if (>= (:process-id term) 0)
      (let [message (condp = input-type
                      :input (ser/create-stdin-message (:process-id term) payload)
                      :resize (ser/create-resize-message (:process-id term) payload)
                      :initialize (log/error "Terminal is already initialized"))]
        (when message (>!! msg-write-handler message)))
      (if (= input-type :initialize)
        (>!! msg-write-handler (apply ser/create-create-process-message (term/get-term-size term)))
        (log/warn "No process id associated to message" input-type)))))

(defn message-handler [msg-read-chan msg-write-chan term-register]
  (async/thread
    (loop []
      (let [[data chan] (alts!! (conj (keys (:terminals @term-register)) msg-read-chan))]
        (if (= chan msg-read-chan)
          (incoming-message-handler (ser/decode-message data) chan term-register)
          (term-write-handler data chan term-register msg-write-chan)))
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


(def ^:dynamic *term-register* (ref (term/create-term-register)))

(defn close-jframe [frame]
  (.dispatchEvent frame (WindowEvent. frame WindowEvent/WINDOW_CLOSING)))

(defn create-terminal-and-process [columns rows key-listener]
  (let [terminal (term/create-term columns rows key-listener)]
    (dosync (alter *term-register* term/add-term-to-register terminal))
    terminal))

(defn term-key-listener [term-widget event]
  (let [keyCode (.getKeyCode event)
        close-frame-for-widget #(close-jframe (SwingUtilities/getWindowAncestor term-widget))]
    (when (.isAltDown event)
      (condp = keyCode
        KeyEvent/VK_H (term/split term-widget :horizontal (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_V (term/split term-widget :vertical (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_Q (close-frame-for-widget)
        KeyEvent/VK_D (do (when (not (term/destroy term-widget))
                            (close-frame-for-widget))
                          true)
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
  (.put (UIManager/getDefaults) "SplitPane.border", (BorderFactory/createEmptyBorder))
  (UIManager/put "SplitDivider.background", (Color. 6 26 39))
  (UIManager/put "SplitDivider.foreground", (Color. 96 109 117))
  ;(UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName))
  (if (not (System/getenv "TEST_PWD"))
    (log/error "Missing password")
    (if-let [;connection (conn/open (conn/create-socket-connection "localhost" 3333))
             connection (conn/open (conn/create-ssh-unix-connection "fede" (System/getenv "TEST_PWD") "localhost"))]
      ;; TODO: protocol connection check, echo?
      (let [msg-read-chan (chan 100)
            msg-write-chan (chan 100)]
        (create-and-show-frame "Multimux" #(when connection (conn/close connection)))
        (log/info "GUI started")
        (ser/message-to-connection-worker connection msg-read-chan msg-write-chan)
        (message-handler msg-read-chan msg-write-chan *term-register*))
      (log/error "Connection not established"))))
