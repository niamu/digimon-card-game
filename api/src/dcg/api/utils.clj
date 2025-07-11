(ns dcg.api.utils
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [reitit.core :as r])
  (:import
   [java.security MessageDigest]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(defn update-api-path
  [{:keys [scheme server-name]} path]
  (str (or (System/getenv "API_ORIGIN")
           (when scheme
             (format "%s://%s"
                     (name scheme)
                     server-name)))
       path))

(defn update-asset-path
  [{:keys [scheme server-name]} path]
  (when path
    (str (or (System/getenv "ASSETS_ORIGIN")
             (format "%s://%s"
                     (name scheme)
                     server-name))
         path)))

(defn update-image-path
  [{:keys [scheme server-name]} path]
  (when path
    (str (or (System/getenv "IMAGES_ORIGIN")
             (format "%s://%s"
                     (name scheme)
                     server-name))
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

(defn short-uuid
  [id]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (->> (.getBytes id)
         (.digest digest)
         (take 3)
         (map (fn [c]
                (format "%02x" (bit-and c 0xFF))))
         (apply str))))

(defn sha
  [text]
  (->> (cond-> text
         (not (string? text))
         str)
       .getBytes
       (.digest (MessageDigest/getInstance "SHA"))
       (map #(format "%02x" %))
       (apply str)))

(defn route-by-name
  [{router ::r/router} name & [path-params]]
  (-> router
      (r/match-by-name name path-params)
      r/match->path))
