(ns dcg.codec.decode
  (:require
   [dcg.codec.common :as codec]
   [clojure.string :as string]
   #?(:cljs [goog.crypt.base64 :as b64]))
  #?(:clj (:import
           [java.util Base64])))

(defn- carry-bit?
  [current-byte bits]
  (not (zero? (bit-and current-byte (bit-shift-left 1 bits)))))

(defn- read-bits-from-byte
  [current-byte bits delta-shift out-bits]
  (bit-or (bit-shift-left (bit-and current-byte
                                   (dec (bit-shift-left 1 bits)))
                          delta-shift)
          out-bits))

(defn- read-var-encoded-uint32
  [current-byte bits data current-byte-index index-end]
  (let [bits-in-a-byte 8]
    (if (or (zero? (dec bits))
            (carry-bit? current-byte (dec bits)))
      (loop [out-bits (if (carry-bit? current-byte (dec bits))
                        (read-bits-from-byte current-byte (dec bits) 0 0)
                        0)
             delta-shift (dec bits)]
        (when (> @current-byte-index index-end)
          (throw (#?(:clj Exception. :cljs js/Error.)
                  (str "End of block."
                       " "
                       @current-byte-index " greater than " index-end))))
        (swap! current-byte-index inc)
        (cond-> (read-bits-from-byte (nth data (dec @current-byte-index))
                                     (dec bits-in-a-byte)
                                     delta-shift
                                     out-bits)
          (carry-bit? (nth data (dec @current-byte-index)) (dec bits-in-a-byte))
          (recur (+ delta-shift (dec bits-in-a-byte)))))
      (read-bits-from-byte current-byte (dec bits) 0 0))))

(defn- serialized-card-v0
  [deck-bytes byte-index index-end prev-card-number]
  (when (> @byte-index index-end)
    (throw (#?(:clj Exception. :cljs js/Error.)
            (str "End of block."
                 " "
                 @byte-index " greater than " index-end))))
  (let [header (nth deck-bytes @byte-index)]
    (swap! byte-index inc)
    [;; 2 bits for card count (1-4)
     (inc (bit-shift-right header 6))
     ;; 3 bits for card parallel-id (0-7)
     (-> header
         (bit-shift-right 3)
         (bit-and 0x07))
     ;; card number offset + previous card number
     (+ prev-card-number
        (read-var-encoded-uint32 header
                                 3 ; bits
                                 deck-bytes
                                 byte-index
                                 index-end))]))

(defn- serialized-card-v1
  [deck-bytes byte-index index-end prev-card-number]
  (when (> @byte-index index-end)
    (throw (#?(:clj Exception. :cljs js/Error.)
            (str "End of block."
                 " "
                 @byte-index " greater than " index-end))))
  (let [card-count (nth deck-bytes @byte-index)
        _ (swap! byte-index inc)
        header (nth deck-bytes @byte-index)
        _ (swap! byte-index inc)]
    [;; 1 byte for card count
     (inc card-count)
     ;; 3 bits for card parallel-id (0-7)
     (bit-shift-right header 5)
     ;; card number offset + previous card number
     (+ prev-card-number
        (read-var-encoded-uint32 header
                                 5 ; bits
                                 deck-bytes
                                 byte-index
                                 index-end))]))

(defn- parse-deck
  [deck-bytes]
  (let [byte-index (atom 0)
        version-and-digi-egg-count (nth deck-bytes @byte-index)
        version (bit-shift-right version-and-digi-egg-count 4)
        digi-egg-set-count (bit-and version-and-digi-egg-count 0x0F)
        _ (swap! byte-index inc)
        checksum (nth deck-bytes @byte-index)
        _ (swap! byte-index inc)
        string-length (nth deck-bytes @byte-index)
        _ (swap! byte-index inc)
        sideboard-count (if (>= version 2)
                          (nth deck-bytes @byte-index)
                          0)
        _ (when (>= version 2)
            (swap! byte-index inc))
        total-card-bytes (- (count deck-bytes) string-length)
        computed-checksum (codec/checksum total-card-bytes deck-bytes)
        _ (when-not (>= codec/version version)
            (throw (#?(:clj Exception. :cljs js/Error.)
                    (str "Invalid version: "
                         codec/version " !>= "
                         (bit-shift-right version 4)))))
        _ (when-not (== checksum (bit-and computed-checksum 0xFF))
            (throw (#?(:clj Exception. :cljs js/Error.)
                    (str "Invalid checksum: "
                         checksum " != " (bit-and computed-checksum 0xFF)))))
        deck
        (loop [deck []]
          (if (< @byte-index total-card-bytes)
            (let [;; card set is 4 characters/bytes
                  card-set (condp = version
                             0 (let [result (-> (drop @byte-index deck-bytes)
                                                (as-> deck-bytes
                                                    (take 4 deck-bytes))
                                                codec/bytes->string
                                                string/trim)]
                                 (swap! byte-index #(+ % 4))
                                 result)
                             (loop [cs ""]
                               (let [cb (nth deck-bytes @byte-index)]
                                 (if-not (zero? (bit-shift-right cb 7))
                                   (do (swap! byte-index inc)
                                       (recur (->> (get codec/base36->char
                                                        (bit-and 0x3F cb))
                                                   (str cs))))
                                   (let [result (str cs
                                                     (get codec/base36->char
                                                          (bit-and 0x3F cb)))]
                                     (swap! byte-index inc)
                                     result)))))
                  ;; card set zero padding and count is 1 byte long
                  card-set-pad-and-count (nth deck-bytes @byte-index)
                  _ (swap! byte-index inc)
                  pad (-> card-set-pad-and-count
                          (bit-shift-right 6)
                          inc)
                  card-set-count
                  (condp = version
                    2 (read-var-encoded-uint32 card-set-pad-and-count
                                               6
                                               deck-bytes
                                               byte-index
                                               total-card-bytes)
                    (bit-and card-set-pad-and-count 0x3F))]
              (recur (->> (loop [cards []
                                 card-index 0
                                 prev-card-number 0]
                            (if (< card-index card-set-count)
                              (let [[card-count parallel-id number]
                                    (condp = version
                                      0 (serialized-card-v0 deck-bytes
                                                            byte-index
                                                            total-card-bytes
                                                            prev-card-number)
                                      (serialized-card-v1 deck-bytes
                                                          byte-index
                                                          total-card-bytes
                                                          prev-card-number))
                                    card (cond-> {:card/number
                                                  (str card-set "-"
                                                       (loop [n (str number)]
                                                         (if (< (count n) pad)
                                                           (recur (str "0" n))
                                                           n)))
                                                  :card/count card-count}
                                           (not (zero? parallel-id))
                                           (assoc :card/parallel-id
                                                  parallel-id))]
                                (recur (conj cards card)
                                       (inc card-index)
                                       number))
                              cards))
                          (concat deck)
                          (into []))))
            deck))
        sideboard (into [] (take-last sideboard-count deck))]
    (cond-> {:deck/digi-eggs (into [] (take digi-egg-set-count deck))
             :deck/deck (->> deck
                             (drop digi-egg-set-count)
                             (drop-last sideboard-count)
                             (into []))
             :deck/name (if (< @byte-index (count deck-bytes))
                          (-> (->> (drop (- (count deck-bytes) string-length)
                                         deck-bytes)
                                   (take string-length))
                              codec/bytes->string)
                          "")}
      (and (>= version 2)
           (not (empty? sideboard)))
      (assoc :deck/sideboard sideboard))))

(defn- decode-deck-string
  [deck-code-str]
  (if (string/starts-with? deck-code-str codec/prefix)
    (as-> (codec/base64url (subs deck-code-str (count codec/prefix))) x
      #?(:clj (.decode (Base64/getDecoder) ^String x)
         :cljs (b64/decodeStringToByteArray x))
      (map #(bit-and % 0xFF) x))
    (throw (#?(:clj Exception. :cljs js/Error.)
            "Deck codes must begin with \"DCG\""))))

(defn ^:export decode
  [deck-code-str]
  (-> (decode-deck-string deck-code-str)
      parse-deck))

#?(:clj
   (defn -main
     [& [deck-code-str]]
     (-> (or deck-code-str (string/trim (slurp *in*)))
         decode
         pr-str
         println)))
