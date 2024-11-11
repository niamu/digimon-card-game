(ns dcg.db.card.cv
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [dcg.db.utils :as utils]
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
                   :methods
                   [[db_init [] void]
                    [db_add [String] int]
                    [db_train [] void]
                    [db_query [String] int]
                    [block_icon [String] int]
                    [digivolution_requirements [String] jnr.ffi.Pointer]
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
  (when (.exists (io/file (str "resources" path)))
    (when-not (-> @db
                  set/map-invert
                  (get (:card/id card)))
      (let [image-index (.db_add native-library (str "resources" path))]
        (swap! db assoc image-index (:card/id card))))))

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
        url-bytes (utils/as-bytes url {})
        _ (with-open [in (io/input-stream url-bytes)
                      out (io/output-stream temp-path)]
            (io/copy in out))
        result (query temp-path)]
    (.delete temp-file)
    result))

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
