(ns dcg.codec.encode
  (:require
   [dcg.codec.spec :as spec]
   [dcg.codec.common :as codec]
   [#?(:clj clojure.edn :cljs cljs.reader) :as edn]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   #?(:cljs [goog.crypt.base64 :as b64]))
  #?(:clj (:import
           [java.util Base64])))

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
  [{:deck/keys [language digi-eggs deck sideboard icon name] :as d}]
  (let [byte-buffer (atom [])
        version (if (<= 3 codec/version 4)
                  (bit-or (bit-shift-left codec/version 4)
                          (bit-shift-left (if (= language "ja") 0 1) 3)
                          (bit-and (count digi-eggs) 0x07))
                  (bit-or (bit-shift-left codec/version 4)
                          (bit-and (count digi-eggs) 0x0F)))
        name (cond->> name
               (and (>= codec/version 4) icon)
               (str (string/replace (string/join (repeat 8 " "))
                                    (re-pattern
                                     (str "^\\s{" (count icon) "}"))
                                    icon)))
        checksum-byte-index (atom 0)
        group-deck (fn [deck]
                     (->> deck
                          (sort-by (juxt :card/number :card/parallel-id))
                          (group-by (fn [{:keys [card/number]}]
                                      (let [[card-set card-number-int]
                                            (string/split number #"-")]
                                        [;; Card Set
                                         card-set
                                         ;; Zero padding for number
                                         (count card-number-int)])))
                          (into [])))
        name (string/trim name) ; trim the deck name just in case
        deck-name (->> (spec/get-bytes name) ; byte # for UTF-8 compatibility
                       (take 0x3F) ; sextet byte count max
                       codec/bytes->string
                       string/trim)]
    (append-to-buffer! byte-buffer version) ; version & digi-egg card group #
    (append-to-buffer! byte-buffer 0) ; Placeholder checksum byte
    (reset! checksum-byte-index (dec (count @byte-buffer)))
    (append-to-buffer! byte-buffer (cond->> (count (spec/get-bytes deck-name))
                                     (>= codec/version 5)
                                     (bit-or
                                      (bit-shift-left (get codec/language->bits
                                                           (or language "en"))
                                                      6))))
    (when (>= codec/version 2)
      ;; sideboard count
      (append-to-buffer! byte-buffer
                         (cond->> (count sideboard)
                           (and (>= codec/version 4) icon) (bit-or 0x80))))
    ;; Store decks
    (doseq [d [(group-deck digi-eggs)
               (group-deck deck)
               (group-deck sideboard)]]
      (loop [card-set-index 0]
        (when (< card-set-index (count d))
          (let [[[card-set pad] cards] (nth d card-set-index)]
            (doseq [c (if (zero? codec/version)
                        ;; Use 4 characters/bytes to store card sets.
                        ;; At time of writing, 3 characters is max in released
                        ;; cards, but we may see ST10 in the future
                        (->> (loop [card-set card-set]
                               (if (< (count card-set) 4)
                                 (recur (str card-set " "))
                                 card-set))
                             spec/get-bytes
                             (map byte))
                        ;; Encode each character of card-set in Base36.
                        ;; Use 8th bit as continue bit. If 0, reached end.
                        (reduce (fn [accl c]
                                  (conj accl
                                        (cond-> (-> (string/upper-case c)
                                                    first
                                                    codec/char->base36)
                                          (not= (count accl)
                                                (dec (count card-set)))
                                          (bit-or 0x80))))
                                []
                                card-set))]
              (append-to-buffer! byte-buffer c))
            ;; 2 bits for card number zero padding
            ;; (zero padding stored as 0 indexed)
            ;; 6 bits for initial count offset of cards in a card group
            (if (>= codec/version 2)
              (do (append-to-buffer! byte-buffer
                                     (bit-or (bit-shift-left (dec pad) 6)
                                             (bits-with-carry (count cards) 6)))
                  (append-rest-to-buffer! byte-buffer (count cards) 6))
              (append-to-buffer! byte-buffer
                                 (bit-or (bit-shift-left (dec pad) 6)
                                         (count cards))))
            (loop [prev-card-number 0
                   card-index 0]
              (when (< card-index (count cards))
                (let [{:card/keys [number parallel-id count]
                       :or {parallel-id 0}} (nth cards card-index)
                      number (-> number
                                 (string/split #"-")
                                 second
                                 #?(:clj (Integer/parseInt 10)
                                    :cljs (js/parseInt 10)))
                      number-offset (- number prev-card-number)]
                  (if (zero? codec/version)
                    ;; 2 bits for card count (1-4)
                    ;; 3 bits for parallel id (0-7)
                    ;; 3 bits for start of card number offset
                    (do (append-to-buffer!
                         byte-buffer
                         (bit-or (bit-shift-left (dec count) 6)
                                 (bit-shift-left parallel-id 3)
                                 (bits-with-carry number-offset 3)))
                        ;; rest of card number offset
                        (append-rest-to-buffer! byte-buffer number-offset 3))
                    ;; 1 byte for card count (1-50 with BT6-085)
                    ;; 3 bits for parallel id (0-7)
                    ;; 5 bits for start of card number offset
                    (do (append-to-buffer! byte-buffer (dec count))
                        (append-to-buffer!
                         byte-buffer
                         (bit-or (bit-shift-left parallel-id 5)
                                 (bits-with-carry number-offset 5)))
                        ;; rest of card number offset
                        (append-rest-to-buffer! byte-buffer number-offset 5)))
                  (recur number (inc card-index))))))
          (recur (inc card-set-index)))))
    ;; Compute and store cards checksum (second byte in buffer)
    ;; Only store the first byte of checksum
    (swap! byte-buffer assoc-in [@checksum-byte-index]
           (bit-and (codec/checksum (count @byte-buffer) @byte-buffer)
                    0xFF))
    ;; Store deck name
    (doseq [character-byte (spec/get-bytes deck-name)]
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
  (let [deck (update deck :deck/name str)]
    (if (s/valid? :dcg/deck deck)
      (-> deck
          encode-bytes
          encode-bytes->string)
      (throw (#?(:clj Exception. :cljs js/Error.)
              (->> ["Deck provided for encoding is invalid!"
                    ""
                    (s/explain-str :dcg/deck deck)]
                   (string/join \newline)))))))

#?(:clj
   (defn -main
     [& [deck]]
     (-> (or deck (edn/read-string (slurp *in*)))
         encode
         println)))
