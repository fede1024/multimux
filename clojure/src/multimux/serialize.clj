(ns multimux.serialize
  (:require [taoensso.timbre :as log]
            [clojure.core.async :refer [>!! <!! chan alts!!] :as async])
  (:import [java.io File InputStreamReader]
           [java.nio ByteBuffer]
           [org.apache.avro Schema$Parser]
           [org.apache.avro.generic GenericData$Record GenericDatumWriter GenericDatumReader]
           [org.apache.avro.io EncoderFactory DecoderFactory]))

(def parser (Schema$Parser.))

(def inputOutputSchema (.parse parser (File. "../avro/InputOutput.avsc")))
(def resizeSchema (.parse parser (File. "../avro/Resize.avsc")))
(def createProcessSchema (.parse parser (File. "../avro/CreateProcess.avsc")))
(def attachToProcessSchema (.parse parser (File. "../avro/AttachToProcess.avsc")))
(def messageSchema (.parse parser (File. "../avro/Message.avsc")))

(defn message-to-socket-worker [socket msg-read-chan msg-write-chan]
  (when (not socket)
    (throw (Exception. "Socket is nil")))
  (async/thread
    (try
      (let [in (.getInputStream socket)
            reader (GenericDatumReader. messageSchema)
            decoder (.directBinaryDecoder (DecoderFactory/get) in nil)]
        (loop [msg (.read reader nil decoder)]
          (>!! msg-read-chan msg)
          (recur (.read reader nil decoder))))
      (catch java.io.EOFException e
        (log/warn "Socket exception" e))
      (catch java.net.SocketException e
        (log/warn "Socket exception" e))))
  (async/thread
    (try
      (let [out (.getOutputStream socket)
            writer (GenericDatumWriter. messageSchema)
            encoder (.binaryEncoder (EncoderFactory/get) out nil)]
        (loop [msg (<!! msg-write-chan)]
          (.write writer msg encoder)
          (.flush encoder)
          (recur (<!! msg-write-chan))))
      (catch java.io.EOFException e
        (log/warn "Interrupted stream" e))
      (catch java.net.SocketException e
        (log/warn "Socket exception" e)))))

(defn create-message [messageType data]
  (let [message (GenericData$Record. messageSchema)]
    (.put message "messageType" messageType)
    (.put message "data" data)
    message))

(defn create-stdin-message [processId bytes]
  (let [inputOutput (GenericData$Record. inputOutputSchema)]
    (.put inputOutput "processId" (int processId))
    (.put inputOutput "bytes" (ByteBuffer/wrap bytes))
    (create-message "stdin" inputOutput)))

(defn create-resize-message [processId size]
  (let [[cols rows width height] size
        resize (GenericData$Record. resizeSchema)]
    (.put resize "rows" rows)
    (.put resize "cols" cols)
    (.put resize "xpixel" width)
    (.put resize "ypixel" height)
    (.put resize "processId" (int processId))
    (create-message "resize" resize)))

(defn create-attach-to-process-message [processId]
  (let [attachToProcess (GenericData$Record. attachToProcessSchema)]
    (.put attachToProcess "processId" (int processId))
    (create-message "attachToProcess" attachToProcess)))

(defn create-create-process-message [cols rows width height]
  (let [createProcess (GenericData$Record. createProcessSchema)]
    (.put createProcess "rows" rows)
    (.put createProcess "cols" cols)
    (.put createProcess "xpixel" width)
    (.put createProcess "ypixel" height)
    (.put createProcess "processId" -1)
    (.put createProcess "path" "/bin/bash")
    (create-message "createProcess" createProcess)))

(defn decode-message [^GenericData$Record message]
  (let [message-type (keyword (str (.get message "messageType")))
        ^GenericData$Record payload (.get message "data")]
    (merge {:message-type message-type}
           (condp = message-type
             :stdout {:process-id (int (.get payload "processId")) :bytes (.array (.get payload "bytes"))}
             :createProcess {:process-id (int (.get payload "processId"))}
             (log/error "Unknown message type" message-type)))))
