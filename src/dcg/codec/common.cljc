(ns dcg.codec.common
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   #?(:cljs [goog.crypt :as crypt])))

(def version 2)
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

(def char->base36
  {\0 0
   \1 1
   \2 2
   \3 3
   \4 4
   \5 5
   \6 6
   \7 7
   \8 8
   \9 9
   \A 10
   \B 11
   \C 12
   \D 13
   \E 14
   \F 15
   \G 16
   \H 17
   \I 18
   \J 19
   \K 20
   \L 21
   \M 22
   \N 23
   \O 24
   \P 25
   \Q 26
   \R 27
   \S 28
   \T 29
   \U 30
   \V 31
   \W 32
   \X 33
   \Y 34
   \Z 35})

(def base36->char
  (set/map-invert char->base36))
