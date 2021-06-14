(ns dcg.codec.spec
  #?(:clj (:gen-class))
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as string]))

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

(s/def :dcg/card-number-limit
  (fn [cards]
    (->> cards
         (group-by (fn [{:card/keys [number]}] number))
         (reduce (fn [accl [number cards]]
                   (assoc-in accl
                             [number]
                             (reduce (fn [accl {:card/keys [count]}]
                                       (+ accl count))
                                     0
                                     cards)))
                 {})
         (every? (fn [[_ card-count]]
                   (<= card-count 255))))))

(s/def :deck/digi-eggs
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?
         :dcg/card-number-limit
         (fn [cards] (<= (count cards) 15))))

(s/def :deck/deck
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?
         :dcg/card-number-limit))

(s/def :deck/name
  (s/and string? (fn [n] (<= 0 (count n) 63))))

(s/def :deck/sideboard
  (s/and (s/coll-of :dcg/card)
         :dcg/deck-is-unique?
         :dcg/card-number-limit))

(s/def :dcg/deck
  (s/keys :req [:deck/digi-eggs
                :deck/deck
                :deck/name]
          :opt [:deck/sideboard]))
