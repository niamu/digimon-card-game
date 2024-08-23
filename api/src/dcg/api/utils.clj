(ns dcg.api.utils
  (:require
   [clojure.data.json :as json]
   [liberator.representation :as representation])
  (:import
   [java.security MessageDigest]))

(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (str date) out)))

(extend-type java.net.URI
  json/JSONWriter
  (-write [uri out]
    (json/-write (str uri) out)))

(defn base-url
  [{{:keys [scheme headers uri]} :request :as context}]
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
