(ns dcg.api.utils
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string])
  (:import
   [com.github.slugify Slugify]
   [java.security MessageDigest]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(defn update-api-path
  [path]
  (str (System/getenv "API_ORIGIN")
       path))

(defn update-asset-path
  [path]
  (when path
    (str (System/getenv "ASSETS_ORIGIN")
         path)))

(defn update-image-path
  [path]
  (when path
    (str (System/getenv "IMAGES_ORIGIN")
         (cond-> (string/replace path #"\.png$" ".webp")
           (System/getenv "IMAGES_ORIGIN")
           (string/replace #"^/images" "")))))

(defn inst->iso8601
  [i]
  (let [formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd")
        zoned-date-time (-> i
                            inst-ms
                            Instant/ofEpochMilli
                            (.atZone (ZoneId/of "UTC")))]
    (.format ^DateTimeFormatter formatter zoned-date-time)))

(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (inst->iso8601 date) out)))

(extend-type java.net.URI
  json/JSONWriter
  (-write [uri out]
    (json/-write (str uri) out)))

(defn slugify
  [s]
  (let [slg (.. (Slugify/builder)
                (transliterator true)
                (build))
        v (->> (string/replace s "Ver." "v")
               (re-find #"v\d\.\d"))]
    (.slugify slg
              (cond-> (or (some-> (re-find #"【(.*?)】" s)
                                  second)
                          (some-> (re-find #"\[(.*?)\]" s)
                                  second)
                          s)
                v
                (str "-" v)))))

(defn sha
  [text]
  (->> (cond-> text
         (not (string? text))
         str)
       .getBytes
       (.digest (MessageDigest/getInstance "SHA"))
       (map #(format "%02x" %))
       (apply str)))

(defn default-language
  [request]
  (or (some-> (get-in request
                      [:headers
                       "accept-language"])
              (string/split #"[;\,]")
              (as-> #__ languages
                (filter (fn [l]
                          (contains? #{"ja"
                                       "en"
                                       "zh-Hans"
                                       "ko"}
                                     l))
                        languages))
              first)
      "en"))
