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
              ;;(println (clojure.string/replace (String. (:bytes message)) "\u001b" "^["))
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
        (>!! msg-write-handler (apply ser/create-create-process-message (term/get-size term)))
        (log/warn "No process id associated to message" input-type)))))

(defn message-handler-old [msg-read-chan msg-write-chan term-register]
  (async/thread
    (loop []
      (let [[data chan] (alts!! (conj (keys (:terminals @term-register)) msg-read-chan))]
        (if (= chan msg-read-chan)
          (incoming-message-handler (ser/decode-message data) chan term-register)
          (term-write-handler data chan term-register msg-write-chan)))
      (recur))))

(defn message-handler [msg-read-chan msg-write-chan term-register]
  (async/thread
    (loop []
      (let [[data chan] (alts!! (conj (keys (:terminals @term-register)) msg-read-chan))]
        (if (= chan msg-read-chan)
          ;;(incoming-message-handler (ser/decode-message data) chan term-register)
          (println ">> " data)
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


(def ^:dynamic *term-register* (ref nil))

(defn close-jframe [frame]
  (.dispatchEvent frame (WindowEvent. frame WindowEvent/WINDOW_CLOSING)))

(defn create-terminal-and-process [columns rows key-listener]
  (let [terminal (term/create-term columns rows key-listener)]
    (dosync (alter *term-register* term/add-to-register terminal))
    terminal))

(defn get-component-win-coordinates
  "Returns the 4 coordinates of a component relative to the window"
  ([component]
   (get-component-win-coordinates component (SwingUtilities/getWindowAncestor component)))
  ([component window]
   (let [xy (SwingUtilities/convertPoint component 0 0 window)
         x (.x xy) y (.y xy)]
     [x y (+ x (.getWidth component)) (+ y (.getHeight component))])))

(defn get-term-win-coordinates-with-borders [term]
  (let [[width height] (term/get-font-size)]
    (map (fn [[term [x y xe ye]]]
           [term [x y (+ xe width) (+ ye height)]]))))

(defn get-cursor-win-coordinater [term-widget]
  (let [[width height] (term/get-font-size)
        [c r] (term/get-cursor-position term-widget)
        window (SwingUtilities/getWindowAncestor term-widget)
        xy (SwingUtilities/convertPoint term-widget (* c width) (* r height) window)
        x (.x xy) y (.y xy)]
     [x y]))

(defn get-terminal-coordinates [register]
  (let [[width height] (term/get-font-size)]
    (map (fn [term]
           (let [[x y xe ye] (get-component-win-coordinates (:widget term))]
             ;;[term [x y (+ xe width) (+ ye width)]]
             [term [x y xe ye]]))
         (vals (:terminals register)))))

(defmulti find-terminal
  "Given a current term-widget, the terminal registry and a direction, finds the closest terminal
  in that direction"
  (fn [register term-widget direction] direction))

(defmethod find-terminal :left [register term-widget _]
  (let [[cx cy] (get-cursor-win-coordinater term-widget)
        coords (get-terminal-coordinates register)]
    (first
      (last   ;; The closest one
        (sort-by (fn [[term [x y xe ye]]] x)
               (filter (fn [[term [x y xe ye]]]
                         (and (< xe cx) (<= y cy) (>= ye cy)))
                       coords))))))

(defmethod find-terminal :right [register term-widget _]
  (let [[cx cy] (get-cursor-win-coordinater term-widget)
        coords (get-terminal-coordinates register)]
    (first
      (first  ;; The closest one
        (sort-by (fn [[term [x y xe ye]]] x)
               (filter (fn [[term [x y xe ye]]]
                         (and (> x cx) (<= y cy) (>= ye cy)))
                       coords))))))

(defmethod find-terminal :up [register term-widget _]
  (let [[cx cy] (get-cursor-win-coordinater term-widget)
        coords (get-terminal-coordinates register)]
    (first
      (last   ;; The closest one
        (sort-by (fn [[term [x y xe ye]]] y)
                 (filter (fn [[term [x y xe ye]]]
                           (and (< ye cy) (<= x cx) (>= xe cx)))
                         coords))))))

(defmethod find-terminal :down [register term-widget _]
  (let [[cx cy] (get-cursor-win-coordinater term-widget)
        coords (get-terminal-coordinates register)]
    (first
      (first  ;; The closest one
        (sort-by (fn [[term [x y xe ye]]] y)
               (filter (fn [[term [x y xe ye]]]
                         (and (> y cy) (<= x cx) (>= xe cx)))
                       coords))))))

(defn term-key-listener [term-widget event]
  (let [keyCode (.getKeyCode event)
        close-frame-for-widget #(close-jframe (SwingUtilities/getWindowAncestor term-widget))
        switch-term #(let [term (find-terminal @*term-register* term-widget %)]
                       (when term (.requestFocus (.getTerminalPanel (:widget term))))
                       true)]
    (when (.isAltDown event)
      (condp = keyCode
        KeyEvent/VK_S (term/split term-widget :horizontal (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_V (term/split term-widget :vertical (create-terminal-and-process 80 24 term-key-listener))
        KeyEvent/VK_Q (close-frame-for-widget)
        KeyEvent/VK_D (do (when (not (term/destroy term-widget))
                            (close-frame-for-widget))
                          ;(term/remove-term-from-register register term-widget)
                          (dosync (alter *term-register* term/remove-from-register
                                         (term/widget-to-term @*term-register* term-widget)))
                          true)
        KeyEvent/VK_H (switch-term :left)
        KeyEvent/VK_L (switch-term :right)
        KeyEvent/VK_K (switch-term :up)
        KeyEvent/VK_J (switch-term :down)
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
             connection (conn/open (conn/create-ssh-connection "fede" (System/getenv "TEST_PWD") "localhost" "bash"))]
      ;; TODO: protocol connection check, echo?
      (let [msg-read-chan (chan 100)
            msg-write-chan (chan 100)]
        (dosync (ref-set *term-register* (term/create-term-register)))
        (create-and-show-frame "Multimux" #(when connection (conn/close connection)))
        (log/info "GUI started")
        (ser/message-to-connection-worker connection msg-read-chan msg-write-chan)
        (message-handler msg-read-chan msg-write-chan *term-register*))
      (log/error "Connection not established"))))

(defn create-and-show-frame [title terminal on-close]
  (let [newFrame (JFrame. title)]
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
             connection (conn/open (conn/create-ssh-connection "fede" (System/getenv "TEST_PWD") "localhost" "bash --login"))]
      ;; TODO: protocol connection check, echo?
      (let [terminal (term/create-term 80 25 term-key-listener)]
        (create-and-show-frame "Multimux" terminal #(when connection (conn/close connection)))
        (log/info "GUI started")
        ;(ser/message-to-connection-worker connection msg-read-chan msg-write-chan)
        ;(message-handler msg-read-chan msg-write-chan *term-register*)
        (async/thread
          (loop [data (<!! (:stdout connection))]
            (when (and (not (nil? data))
                       (>!! (:screen terminal) data))
              (recur (<!! (:stdout connection)))))
          (log/info "Terminating message handler out"))
        (async/thread
          (loop [[input-type data] (<!! (:keyboard terminal))]
            (let [result (condp = input-type
                           :input (>!! (:stdin connection) data)
                           :resize (.setPtySize (:jsch-channel connection) (first data) (second data) (nth data 2) (nth data 3))
                           :initialize (log/info "Initialize"))]
              (recur (<!! (:keyboard terminal)))))))
      (log/error "Connection not established"))))

(def kb (:keyboard  (first  (vals  (:terminals @*term-register*)))))
(def screen (:screen  (first  (vals  (:terminals @*term-register*)))))

(defn send-screen [input]
  (>!! screen  (.getBytes  (clojure.string/replace input "^[" "\u001b"))))
