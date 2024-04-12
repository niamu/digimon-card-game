(ns dcg.card.cv
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [dcg.card.utils :as utils]
   [taoensso.timbre :as logging])
  (:import
   [java.io File]
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
                   :methods [[db_init [] void]
                             [db_add [String] int]
                             [db_train [] void]
                             [db_query [String] int]
                             [block_marker [String] int]
                             [digivolve_conditions [String] jnr.ffi.Pointer]
                             [free_string [jnr.ffi.Pointer] void]])]
    (.load (LibraryLoader/create interface) library-name)))

(defn- parse-edn
  [ptr]
  (let [edn-result (edn/read-string (.getString ptr 0))]
    (.free_string native-library ptr)
    edn-result))

(defonce db
  (do (.db_init native-library)
      (atom {})))

(defn add!
  [{{:image/keys [path]} :card/image :as card}]
  (when-not (-> @db
                set/map-invert
                (get (:card/id card)))
    (let [image-index (.db_add native-library path)]
      (swap! db assoc image-index (:card/id card)))))

(defn train!
  []
  (.db_train native-library))

(defn query
  [image-path]
  (get @db (.db_query native-library image-path)))

(defn query-url
  [url]
  (logging/info (format "CV Query for URL: %s" url))
  (let [temp-file (File/createTempFile "temp" "")
        temp-path (.getPath temp-file)
        url-bytes (utils/as-bytes url)
        _ (with-open [in (io/input-stream url-bytes)
                      out (io/output-stream temp-path)]
            (io/copy in out))
        result (query temp-path)]
    (.delete temp-file)
    result))

(defn block-marker
  [{{:image/keys [path]} :card/image :as card}]
  (let [v (.block_marker native-library path)]
    (cond-> card
      (pos? v) (assoc :card/block-marker v))))

(defn digivolve-conditions
  [{:card/keys [digivolve-conditions number]
    {:image/keys [path]} :card/image
    :as card}]
  (let [conditions-with-colors (-> (.digivolve_conditions native-library path)
                                   parse-edn)]
    (cond-> (dissoc card :card/digivolve-conditions)
      (and digivolve-conditions
           (not (empty? conditions-with-colors)))
      (assoc :card/digivolve-conditions
             (map-indexed
              (fn [idx {colors :colors}]
                (let [prev-condition (or (nth digivolve-conditions idx nil)
                                         (first digivolve-conditions))]
                  (when-not (get prev-condition :digivolve/cost)
                    (logging/error (format (str "Digivolve condition missing for"
                                                " %s on index %d")
                                           number idx)))
                  (merge prev-condition
                         {:digivolve/id (format "digivolve/%s_index%d"
                                                number
                                                idx)
                          :digivolve/color (set (map keyword colors))
                          :digivolve/index idx})))
              conditions-with-colors)))))
