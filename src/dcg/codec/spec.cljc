(ns dcg.codec.spec
  #?(:clj (:gen-class))
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as string]
   #?(:cljs [goog.crypt :as crypt])))

(defn get-bytes
  [^String s]
  #?(:clj (.getBytes s "UTF8")
     :cljs (crypt/stringToUtf8ByteArray s)))

(def ^:private card-number-regex
  "1-4 alphanumeric characters followed by 2-3 digits separated by a hyphen"
  #"[A-Z|0-9]{1,4}\-[0-9]{2,3}")

(s/def :card/number-int
  (s/int-in 1 1000))

(s/def :card/set
  (s/with-gen string?
    #(gen/fmap (fn [s]
                 (string/upper-case (apply str s)))
               (gen/bind (s/gen (s/int-in 1 5))
                         (fn [size]
                           (gen/vector (gen/char-alpha) size))))))

(s/def :card/number
  (s/with-gen #(re-matches card-number-regex %)
    #(gen/fmap (fn [[s n]]
                 (str s "-" (cond->> n
                              (< (count (str n)) 2) (str "0"))))
               (s/gen (s/cat :s :card/set :n :card/number-int)))))

(s/def :card/count
  (s/int-in 1 256))

(s/def :card/parallel-id
  (s/int-in 0 8))

(s/def :dcg/card
  (s/keys :req [:card/number :card/count]
          :opt [:card/parallel-id]))

(s/def :dcg/deck-is-unique?
  (fn [cards]
    (let [card-ids (map (juxt :card/number :card/parallel-id) cards)]
      (== (count card-ids)
          (count (set card-ids))))))

(s/def :deck/language
  #{:ja :en})

(s/def :deck/digi-eggs
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?
         (fn [cards] (<= (count cards) 0x07))))

(s/def :deck/deck
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?))

(s/def :deck/name
  (s/and string?
         (fn [s] (<= (count (get-bytes s)) 0x3F))))

(s/def :deck/icon
  :card/number)

(s/def :deck/sideboard
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?
         (fn [cards] (<= (count cards) 0x7F))))

(s/def :dcg/deck
  (s/keys :req [:deck/digi-eggs
                :deck/deck
                :deck/name]
          :opt [:deck/language
                :deck/icon
                :deck/sideboard]))
