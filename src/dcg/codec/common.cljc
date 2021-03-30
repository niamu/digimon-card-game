(ns dcg.codec.common
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   #?(:cljs [goog.crypt :as crypt])))

(def version 0)
(def prefix "DCG")
(def header-size
  "version & digi-eggs deck count, checksum, and deck name length"
  3)

(defn bytes->string
  [b]
  #?(:clj (-> b byte-array (String. "UTF8"))
     :cljs (-> b clj->js crypt/utf8ByteArrayToString)))

(defn checksum
  [total-card-bytes deck-bytes]
  (reduce + (->> (take total-card-bytes deck-bytes)
                 (drop header-size))))

(defn base64url
  "https://tools.ietf.org/html/rfc4648#section-5"
  [^String s] ; Base64 string
  (let [replace-map {"/" "_" "+" "-" "=" ""}
        replace-map (merge replace-map (set/map-invert replace-map))]
    (string/replace s #"/|\+|=|_|-" replace-map)))
