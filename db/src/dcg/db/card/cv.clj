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
                    [image_roi_hash [String] long]
                    [block_icon [String] int]
                    [supplemental_rarity [String] int]
                    [digivolution_requirements [String] jnr.ffi.Pointer]
                    [free_string [jnr.ffi.Pointer] void]])]
    (.load (LibraryLoader/create interface) library-name)))

(defn- parse-edn
  [ptr]
  (let [edn-result (edn/read-string (.getString ptr 0))]
    (.free_string native-library ptr)
    edn-result))

(defn image-hash
  [{{:image/keys [path]} :card/image :as card}]
  (when (.exists (io/file (str "resources" path)))
    (-> (.image_hash native-library
                     (str "resources" path))
        Long/toUnsignedString
        (BigInteger.))))

(defn image-roi-hash
  [{{:image/keys [path]} :card/image :as card}]
  (cond-> card
    (.exists (io/file (str "resources" path)))
    (update :card/image
            merge
            (let [image-hash (-> (.image_roi_hash native-library
                                                  (str "resources" path))
                                 Long/toUnsignedString
                                 (BigInteger.))
                  hash-segments (fn [l]
                                  (let [b (BigInteger/valueOf 0xFF)]
                                    (mapv long [(.and (.shiftRight l 56) b)
                                                (.and (.shiftRight l 48) b)
                                                (.and (.shiftRight l 40) b)
                                                (.and (.shiftRight l 32) b)
                                                (.and (.shiftRight l 24) b)
                                                (.and (.shiftRight l 16) b)
                                                (.and (.shiftRight l 8) b)
                                                (.and (.shiftRight l 0) b)])))
                  hash image-hash]
              {:image/hash hash
               :image/hash-segments (hash-segments hash)}))))

(defn block-icon
  [{:card/keys [block-icon] {:image/keys [path]} :card/image
    :as card}]
  (let [v (if (.exists (io/file (str "resources" path)))
            (.block_icon native-library (str "resources" path))
            -1)]
    (cond-> card
      (and (pos? v)
           (nil? block-icon)) (assoc :card/block-icon v)
      (= block-icon 0) (dissoc :card/block-icon))))

(defn supplemental-rarity
  [{:card/keys [id] {:image/keys [path]} :card/image
    :as card}]
  (let [v (if (.exists (io/file (str "resources" path)))
            (.supplemental_rarity native-library (str "resources" path))
            -1)]
    (cond-> card
      (pos? v) (assoc :card/supplemental-rarity
                      (cond-> {:supplemental-rarity/id
                               (string/replace id
                                               "card/"
                                               "supplemental-rarity/")}
                        (= v 99)
                        (assoc :supplemental-rarity/stamp "SP")
                        (not= v 99)
                        (assoc :supplemental-rarity/stars v))))))

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
           (seq requirements-with-colors))
      (assoc :card/digivolution-requirements
             (map-indexed
              (fn [idx {:keys [colors] :as r}]
                (let [category (or (get (nth digivolution-requirements idx
                                             {:digivolve/category
                                              (-> (get r :category)
                                                  string/lower-case
                                                  keyword)})
                                        :digivolve/category)
                                   :digimon)
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
