(ns dcg.db.card.cv
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [taoensso.timbre :as logging])
  (:import
   [jnr.ffi LibraryLoader]))

(def ^:private native-library
  (let [library-name "dcgcv"
        _ (System/setProperty "jnr.ffi.library.path"
                              (-> (System/mapLibraryName library-name)
                                  io/resource
                                  io/as-file
                                  (.getParent)))
        interface (gen-interface
                   :name "dcg.card.cv.INativeLibrary"
                   :methods
                   [[image_hash [String] long]
                    [block_icon [String] int]
                    [digivolution_requirements [String] jnr.ffi.Pointer]
                    [free_string [jnr.ffi.Pointer] void]])]
    (.load (LibraryLoader/create interface) library-name)))

(defn- parse-edn
  [ptr]
  (let [edn-result (edn/read-string (.getString ptr 0))]
    (.free_string native-library ptr)
    edn-result))

(defn image-hash
  [{:card/keys [number] {:image/keys [path]} :card/image :as card}]
  (cond-> card
    (.exists (io/file (str "resources" path)))
    (update :card/image
            merge
            (let [hash-segments (fn [l]
                                  [(bit-and (bit-shift-right l 56) 0xFF)
                                   (bit-and (bit-shift-right l 48) 0xFF)
                                   (bit-and (bit-shift-right l 40) 0xFF)
                                   (bit-and (bit-shift-right l 32) 0xFF)
                                   (bit-and (bit-shift-right l 24) 0xFF)
                                   (bit-and (bit-shift-right l 16) 0xFF)
                                   (bit-and (bit-shift-right l 8) 0xFF)
                                   (bit-and (bit-shift-right l 0) 0xFF)])
                  hash (bit-and Long/MAX_VALUE
                                (.image_hash native-library
                                             (str "resources" path)))]
              {:image/hash hash
               :image/hash-segments (hash-segments hash)}))))

(defn block-icon
  [{:card/keys [block-icon number]
    {:image/keys [path]} :card/image
    :as card}]
  (let [v (if (.exists (io/file (str "resources" path)))
            (.block_icon native-library (str "resources" path))
            -1)]
    (cond-> card
      (and (pos? v)
           (nil? block-icon)) (assoc :card/block-icon v)
      (= block-icon 0) (dissoc :card/block-icon))))

(defn digivolution-requirements
  [{:card/keys [digivolution-requirements number]
    {:image/keys [path]} :card/image
    :as card}]
  (let [requirements-with-colors
        (if (.exists (io/file (str "resources" path)))
          (-> (.digivolution_requirements native-library (str "resources" path))
              parse-edn)
          [])]
    (cond-> (dissoc card :card/digivolution-requirements)
      (and digivolution-requirements
           (not (empty? requirements-with-colors)))
      (assoc :card/digivolution-requirements
             (map-indexed
              (fn [idx {:keys [colors category]}]
                (let [category (-> category
                                   string/lower-case
                                   keyword)
                      prev-requirement
                      (cond-> (or (nth digivolution-requirements idx nil)
                                  (first digivolution-requirements))
                        (= category :tamer)
                        (dissoc :digivolve/level))]
                  (when-not (get prev-requirement :digivolve/cost)
                    (logging/error (format (str "Digivolution requirement"
                                                " missing for %s on index %d")
                                           number idx)))
                  (merge prev-requirement
                         {:digivolve/id (format "digivolve/%s_index%d"
                                                number
                                                idx)
                          :digivolve/color (set (map keyword colors))
                          :digivolve/category category
                          :digivolve/index idx})))
              requirements-with-colors)))))
