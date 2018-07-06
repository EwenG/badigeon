(ns badigeon.compile
  (:refer-clojure :exclude [compile])
  (:import [java.nio.file Paths Files StandardOpenOption OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.io FileInputStream DataInputStream ByteArrayInputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels FileChannel]))

(comment
  (def OPEN_OPTIONS (doto (make-array OpenOption 1)
                      (aset 0 StandardOpenOption/READ)))

  (def ^:const MAGIC 0xCAFEBABE)

  (def ^:const CONSTANT_POOL_UTF_8 1)
  (def ^:const CONSTANT_POOL_CLASS 7)
  (def ^:const CONSTANT_POOL_NAME_AND_TYPE 12)
  (def ^:const CONSTANT_POOL_FIELD 9)
  (def ^:const CONSTANT_POOL_METHOD 10)
  (def ^:const CONSTANT_POOL_INTERFACE_METHOD 11)
  (def ^:const CONSTANT_POOL_STRING 8)

  (defn get-ubyte [buff]
    (bit-and (.get buff) 0xFF))

  (defn get-ushort [buff]
    (bit-and (.getShort buff) 0xFFFF))

  (defn get-uint [buff]
    (bit-and (.getInt buff) 0xFFFFFFFF))

  (defn read-contant-pool-entry-dispatch [tag buff] tag)

  (defmulti read-contant-pool-entry #'read-contant-pool-entry-dispatch)

  (defmethod read-contant-pool-entry CONSTANT_POOL_UTF_8
    [tag buff]
    (let [length (get-ushort buff)
          old-limit (.limit buff)
          sb (StringBuilder. (+ length (bit-shift-right length 1) 16))]
      (.limit buff (+ (.position buff) length))
      (while (.hasRemaining buff)
        (let [b (.get buff)]
          (if (> b 0)
            (.append sb (char b))
            (let [b2 (.get buff)]
              (if (not= (bit-and b 0xF0) 0xe0)
                (.append sb (char (bit-or (bit-and b 0x1F) (bit-and b2 0x3F))))
                (let [b3 (.get buff)]
                  (.append sb (char (bit-or (bit-shift-left (bit-and b 0x0F) 12)
                                            (bit-or (bit-shift-left (bit-and b2 0x3F) 6)
                                                    (bit-and b3 0x3F)))))))))))
      (.limit buff old-limit)
      (str sb)))

  (defmethod read-contant-pool-entry CONSTANT_POOL_CLASS
    [tag buff]
    (get-ushort buff))

  (defmethod read-contant-pool-entry CONSTANT_POOL_NAME_AND_TYPE
    [tag buff]
    {:name-index (get-ushort buff)
     :descriptor-index (get-ushort buff)})

  (defmethod read-contant-pool-entry CONSTANT_POOL_FIELD
    [tag buff]
    {:class-index (get-ushort buff)
     :name-and-type-index (get-ushort buff)})

  (defmethod read-contant-pool-entry CONSTANT_POOL_METHOD
    [tag buff]
    {:class-index (get-ushort buff)
     :name-and-type-index (get-ushort buff)})

  (defmethod read-contant-pool-entry CONSTANT_POOL_INTERFACE_METHOD
    [tag buff]
    {:class-index (get-ushort buff)
     :name-and-type-index (get-ushort buff)})

  (defmethod read-contant-pool-entry CONSTANT_POOL_STRING
    [tag buff]
    (get-ushort buff))

  (defn read-constant-entry [buff]
    (let [tag (get-ubyte buff)]
      (prn tag)
      (read-contant-pool-entry tag buff)))

  (defn read-constant-pool [buff constant-pool-count]
    (->> (partial read-constant-entry buff)
         (repeatedly (dec constant-pool-count))
         (into [])))

  (defn read-class [path]
    (let [file-channel (FileChannel/open path OPEN_OPTIONS)]
      (try
        (let [buff (.map file-channel java.nio.channels.FileChannel$MapMode/READ_ONLY
                         0 (.size file-channel))
              buff (.order buff ByteOrder/BIG_ENDIAN)
              magic (get-uint buff)]
          (when (not= magic MAGIC)
            (throw (RuntimeException. (format "Invalid class file: %s - Magic number: %s"
                                              (pr-str (str path))
                                              magic))))
          (let [minor (get-ushort buff)
                major (get-ushort buff)
                constant-pool-count (get-ushort buff)]
            {:magic magic
             :minor minor
             :major major
             :constant-pool-count constant-pool-count
             :constant-pool (read-constant-pool buff constant-pool-count)}))
        (finally (.close file-channel)))))
  )

(defn compile
  ([namespaces compile-path]
   (compile namespaces compile-path nil))
  ([namespaces compile-path opts]
   (Files/createDirectories
    (Paths/get compile-path (make-array String 0))
    (make-array FileAttribute 0))
   (if (coll? namespaces)
     (binding [*compile-path* compile-path
               *compiler-options* opts]
       (doseq [namespace namespaces]
         (clojure.core/compile namespace)))
     (binding [*compile-path* compile-path
               *compiler-options* opts]
       (clojure.core/compile namespaces)))))


;; Cleaning non project classes: https://dev.clojure.org/jira/browse/CLJ-322

;; Cleaning non project classes is not supported by badigeon because:
;; Most of the time, libraries should be shipped without AOT. In the rare case when a library must be shipped AOT (let's say we don't want to ship the sources), directories can be removed programmatically, between build tasks. Shipping an application with AOT is a more common use case. In this case, AOT compiling dependencies is not an issue.
