(ns multimux.terminal
  (:require [clojure.core.async :refer [>!! <!! chan] :as async]
            [taoensso.timbre :as log])
  (:import JMultimuxSplit
           [java.nio.charset Charset]
           [java.io InputStreamReader StringReader]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [java.awt Font]
           [java.awt.image BufferedImage]
           [javax.swing JPanel JSplitPane]
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
        (>!! (:keyboard terminal) [:initialize nil]) (when chan true))
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

(defn get-font-size []
  (let [settings (settings-provider)
        font (.getTerminalFont settings)
        linespace (.getLineSpace settings)
        graphics (.createGraphics (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB))
        metrics (.getFontMetrics graphics font)]
    [(.charWidth metrics \W) (+ (.getHeight metrics) (* linespace 2))]))

(defn split-panel [direction panel old-term-widget new-term-widget]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        [w h] (get-font-size)
        split (JMultimuxSplit. orientation old-term-widget new-term-widget w h)]
    (.remove panel old-term-widget)
    (.add panel split)
    (.setResizeWeight split 0.5)
    (.revalidate panel)))

(defn split-splitpane [direction splitpane old-term-widget new-term-widget]
  (let [orientation (if (= direction :vertical)
                      JSplitPane/HORIZONTAL_SPLIT
                      JSplitPane/VERTICAL_SPLIT)
        [w h] (get-font-size)
        new-split (JMultimuxSplit. orientation old-term-widget new-term-widget w h)]
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

(defn split [term-widget direction new-terminal]
  {:pre [(direction #{:vertical :horizontal})]}
  (let [term-panel (.getTerminalPanel term-widget)
        container (.getParent term-widget)]
    (condp = (type container)
      javax.swing.JPanel (split-panel direction container term-widget (:widget new-terminal))
      ;javax.swing.JSplitPane (split-splitpane direction container term-widget (:widget new-terminal))
      JMultimuxSplit (split-splitpane direction container term-widget (:widget new-terminal)))
    (.requestFocus (.getTerminalPanel (:widget new-terminal))))
  new-terminal)

(defn give-focus-to-term [component]
  (condp = (type component)
    JediTermWidget (.requestFocus (.getTerminalPanel component))
    JMultimuxSplit (give-focus-to-term (or (.getLeftComponent component) (.getTopComponent component)))
    (log/warn "I don't know how to give focus to a" (type component))))

(defn destroy-in-split [split term-widget]
  (.remove split term-widget)
  (let [split-container (.getParent split)
        other-term (or (.getLeftComponent split) (.getRightComponent split)
                       (.getTopComponent split) (.getBottomComponent split))]
    (.remove split-container split)
    (condp = (type split-container)
      javax.swing.JPanel (do (.remove split-container split)
                             (.add split-container other-term))
      JMultimuxSplit (if (= (.getOrientation split-container) JSplitPane/HORIZONTAL_SPLIT)
                       (if (.getLeftComponent split-container)
                         (.setRightComponent split-container other-term)
                         (.setLeftComponent split-container other-term))
                       (if (.getTopComponent split-container)
                         (.setBottomComponent split-container other-term)
                         (.setTopComponent split-container other-term))))
    (.revalidate split-container)
    (give-focus-to-term other-term)))

(defn destroy [term-widget]
  (let [container (.getParent term-widget)]
    (condp = (type container)
      javax.swing.JPanel false
      JMultimuxSplit (do (destroy-in-split container term-widget) true))))

;; Term register
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
