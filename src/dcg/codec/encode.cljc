(ns dcg.codec.encode
  #?(:clj (:gen-class))
  (:require
   [dcg.codec.common :as codec]
   [#?(:clj clojure.edn :cljs cljs.reader) :as edn]
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as string]
   #?@(:cljs [[goog.crypt :as crypt]
              [goog.crypt.base64 :as b64]]))
  #?(:clj (:import
           [java.util Base64])))

(def ^:private card-id-regex
  "1-4 alphanumeric characters followed by 2-3 digits separated by a hyphen"
  #"[A-Z|0-9]{1,4}\-[0-9]{2,3}")

(s/def :card/number
  (s/int-in 1 1000))

(s/def :card/set
  (s/with-gen string?
    #(gen/fmap (fn [s]
                 (string/upper-case (apply str s)))
               (gen/bind (s/gen (s/int-in 1 5))
                         (fn [size]
                           (gen/vector (gen/char-alpha) size))))))

(s/def :card/id
  (s/with-gen #(re-matches card-id-regex %)
    #(gen/fmap (fn [[s n]]
                 (str s "-" (cond->> n
                              (< (count (str n)) 2) (str "0"))))
               (s/gen (s/cat :s :card/set :n :card/number)))))

(s/def :card/count
  (s/int-in 1 5))

(s/def :card/parallel-id
  (s/int-in 1 8))

(s/def ::card
  (s/keys :req [:card/id :card/count]
          :opt [:card/parallel-id]))

(s/def ::deck-is-unique?
  (fn [cards]
    (let [card-ids (map (juxt :card/id :card/parallel-id) cards)]
      (== (count card-ids)
          (count (set card-ids))))))

(defn- card-count
  [cards]
  (reduce (fn [accl {:keys [card/count]}]
            (+ accl count))
          0
          cards))

(defn- card-id-count-limit
  [cards]
  (->> cards
       (group-by (fn [{:card/keys [id]}] id))
       (reduce (fn [accl [id cards]]
                 (conj accl [id (reduce (fn [accl {:card/keys [count]}]
                                          (+ accl count))
                                        0
                                        cards)]))
               {})
       (every? (fn [[id card-count]]
                 (<= card-count 4)))))

(s/def :deck/digi-eggs
  (s/and (s/coll-of ::card)
         ::deck-is-unique?
         card-id-count-limit
         (fn [cards] (<= 0 (card-count cards) 5))))

(s/def :deck/deck
  (s/and (s/coll-of ::card)
         ::deck-is-unique?
         card-id-count-limit
         (fn [cards] (== (card-count cards) 50))))

(s/def :deck/name
  (s/and string? (fn [n] (<= 0 (count n) 63))))

(s/def ::deck
  (s/keys :req [:deck/digi-eggs :deck/deck :deck/name]))

(defn- get-bytes
  [^String s]
  #?(:clj (.getBytes s "UTF8")
     :cljs (crypt/stringToUtf8ByteArray s)))

(defn- bits-with-carry
  "`bits` from `value` is actually 1 less than requested
  to account for the carry bit at the beginning"
  [value bits]
  (let [limit-bit (bit-shift-left 1 (dec bits))
        result (bit-and value (dec limit-bit))]
    (if (>= value limit-bit)
      (bit-or result limit-bit)
      result)))

(defn- append-to-buffer!
  [byte-buffer b]
  (if (> b 255)
    (throw (#?(:clj Exception. :cljs js/Error.) "Byte out of range."))
    (swap! byte-buffer conj b)))

(defn- append-rest-to-buffer!
  [byte-buffer value already-written-bits]
  (loop [v (bit-shift-right value (dec already-written-bits))]
    (when (> v 0)
      (append-to-buffer! byte-buffer (bits-with-carry v 8))
      (recur (bit-shift-right v 7)))))

(defn- encode-bytes
  [{:deck/keys [digi-eggs deck name]}]
  (let [byte-buffer (atom [])
        version (bit-or (bit-shift-left codec/version 4)
                        (bits-with-carry (-> (map :card/id digi-eggs)
                                             set
                                             count)
                                         4))
        group-deck (fn [deck]
                     (->> deck
                          (sort-by (juxt :card/id :card/parallel-id))
                          (group-by (fn [{:keys [card/id]}]
                                      (let [id-split (string/split id #"-")]
                                        [;; Card Set
                                         (first id-split)
                                         ;; Zero padding for number
                                         (count (second id-split))])))
                          (into [])))
        name (string/trim name) ; trim the deck name just in case
        deck-name-bytes (get-bytes name) ; bytes of name for UTF-8 compatibility
        deck-name (->> deck-name-bytes
                       (take 63) ; sextet byte count max
                       codec/bytes->string
                       string/trim)]
    (append-to-buffer! byte-buffer version) ; version & digi-egg card group #
    (append-to-buffer! byte-buffer 0) ; Placeholder checksum byte
    (append-to-buffer! byte-buffer (count deck-name-bytes)) ; Deck name length
    ;; Store decks
    (doseq [d [(group-deck digi-eggs)
               (group-deck deck)]]
      (loop [card-set-index 0]
        (when (< card-set-index (count d))
          (let [[[card-set pad] cards] (nth d card-set-index)]
            ;; Use 4 characters/bytes to store card sets.
            ;; At time of writing, 3 characters is max in released cards, but
            ;; we may see ST10 in the future
            (doseq [c (->> (loop [card-set card-set]
                             (if (< (count card-set) 4)
                               (recur (str card-set " "))
                               card-set))
                           get-bytes
                           (map byte))]
              (append-to-buffer! byte-buffer c))
            ;; 2 bits for card number zero padding
            ;; (zero padding stored as 0 indexed)
            ;; 6 bits for count of cards in a card set
            (append-to-buffer! byte-buffer (bit-or (bit-shift-left (dec pad) 6)
                                                   (count cards)))
            (loop [prev-card-id 0
                   card-index 0]
              (when (< card-index (count cards))
                (let [{:card/keys [id parallel-id count]
                       :or {parallel-id 0}} (nth cards card-index)
                      id (-> id
                             (string/split #"-")
                             second
                             #?(:clj (Integer/parseInt)
                                :cljs (js/parseInt)))
                      id-offset (- id prev-card-id)]
                  ;; 2 bits for card count (1-4)
                  ;; 3 bits for parallel id (0-7) - BT5-086 has 4 at the moment
                  ;; 3 bits for start of card id offset
                  (append-to-buffer! byte-buffer
                                     (bit-or (bit-shift-left (dec count) 6)
                                             (bit-shift-left parallel-id 3)
                                             (bits-with-carry id-offset 3)))
                  ;; rest of card id offset
                  (append-rest-to-buffer! byte-buffer id-offset 3)
                  (recur id (inc card-index))))))
          (recur (inc card-set-index)))))
    ;; Compute and store cards checksum (second byte in buffer)
    ;; Only store the first byte of checksum
    (swap! byte-buffer assoc-in [1]
           (bit-and (codec/checksum (count @byte-buffer) @byte-buffer)
                    0xFF))
    ;; Store deck name
    (doseq [character-byte (get-bytes deck-name)]
      (append-to-buffer! byte-buffer character-byte))
    @byte-buffer))

(defn- encode-bytes->string
  [byte-buffer]
  (if (empty? byte-buffer)
    (throw (#?(:clj Exception. :cljs js/Error.) "Empty byte buffer."))
    (->> #?(:clj (.encodeToString (Base64/getEncoder) (byte-array byte-buffer))
            :cljs (b64/encodeByteArray (js/Uint8Array. byte-buffer)))
         codec/base64url
         (str codec/prefix))))

(defn ^:export encode
  [deck]
  (if (s/valid? ::deck deck)
    (-> deck
        encode-bytes
        encode-bytes->string)
    (throw (#?(:clj Exception. :cljs js/Error.)
            (->> ["Deck provided for encoding is invalid!"
                  ""
                  (s/explain-str ::deck deck)]
                 (string/join \newline))))))

#?(:clj
   (defn -main
     [& [deck]]
     (-> (or deck (edn/read-string (slurp *in*)))
         encode
         println)))
