(ns dcg.api.utils
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [liberator.representation :as representation])
  (:import
   [java.security MessageDigest]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

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

(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (str date) out)))

(extend-type java.net.URI
  json/JSONWriter
  (-write [uri out]
    (json/-write (str uri) out)))

(defn inst->iso8601
  [i]
  (let [formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd")
        zoned-date-time (-> i
                            inst-ms
                            Instant/ofEpochMilli
                            (.atZone (ZoneId/of "UTC")))]
    (.format ^DateTimeFormatter formatter zoned-date-time)))

(defn short-uuid
  [id]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (->> (.getBytes id)
         (.digest digest)
         (take 3)
         (map (fn [c]
                (format "%02x" (bit-and c 0xFF))))
         (apply str))))

(defn base-url
  [{{:keys [scheme headers]} :request :as context}]
  (when scheme
    (str (name scheme)
         "://"
         (get headers "host"))))

(defn sha
  [text]
  (->> (cond-> text
         (not (string? text))
         str)
       .getBytes
       (.digest (MessageDigest/getInstance "SHA"))
       (map #(format "%02x" %))
       (apply str)))

(defmethod representation/render-map-generic "application/vnd.api+json"
  [data {{:keys [uri]} :request :as context}]
  (let [self-url (str (base-url context) uri)]
    (json/write-str (cond-> data
                      (:data data)
                      (assoc :links {:self self-url})))))

(defmethod representation/render-seq-generic "application/vnd.api+json"
  [data {{:keys [uri]} :request :as context}]
  (let [self-url (str (base-url context) uri)]
    (json/write-str (cond-> {:data data}
                      (nil? (:errors data))
                      (assoc :links {:self self-url})))))
