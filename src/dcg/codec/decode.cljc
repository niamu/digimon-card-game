(ns dcg.codec.decode
  #?(:clj (:gen-class))
  (:require
   [dcg.codec.common :as codec]
   #?(:clj [clojure.pprint :as pprint])
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
          (throw (#?(:clj Exception. :cljs js/Error.) "End of block.")))
        (swap! current-byte-index inc)
        (cond-> (read-bits-from-byte (nth data (dec @current-byte-index))
                                     (dec bits-in-a-byte)
                                     delta-shift
                                     out-bits)
          (carry-bit? (nth data (dec @current-byte-index)) (dec bits-in-a-byte))
          (recur (+ delta-shift (dec bits-in-a-byte)))))
      (read-bits-from-byte current-byte (dec bits) 0 0))))

(defn- read-serialized-card
  [deck-bytes byte-index index-end prev-card-number]
  (when (> @byte-index index-end)
    (throw (#?(:clj Exception. :cljs js/Error.) "End of block.")))
  (let [header (nth deck-bytes @byte-index)]
    (swap! byte-index inc)
    [ ;; 2 bits for card count (1-4)
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

(defn- parse-deck
  [deck-bytes]
  (let [version (nth deck-bytes 0)
        checksum (nth deck-bytes 1)
        string-length (nth deck-bytes 2)
        total-card-bytes (- (count deck-bytes) string-length)
        computed-checksum (codec/checksum total-card-bytes deck-bytes)
        _ (when-not (== codec/version (bit-shift-right version 4))
            (throw (#?(:clj Exception. :cljs js/Error.)
                    (str "Invalid version: "
                         codec/version " != "
                         (bit-shift-right version 4)))))
        _ (when-not (== checksum (bit-and computed-checksum 0xFF))
            (throw (#?(:clj Exception. :cljs js/Error.) "Invalid checksum.")))
        byte-index (atom 0)
        _ (reset! byte-index 3)
        digi-egg-set-count (read-var-encoded-uint32 version
                                                    4 ; bits
                                                    deck-bytes
                                                    byte-index
                                                    total-card-bytes)
        [digi-eggs main-deck]
        (reduce
         (fn [accl deck-type]
           (conj accl
                 (loop [deck []
                        card-set-index 0]
                   (if (condp = deck-type
                         :digi-egg (< card-set-index digi-egg-set-count)
                         (< @byte-index total-card-bytes))
                     (let [;; card set is 4 characters/bytes
                           card-set (-> (drop @byte-index deck-bytes)
                                        (as-> deck-bytes
                                            (take 4 deck-bytes))
                                        codec/bytes->string
                                        string/trim)
                           _ (swap! byte-index #(+ % 4))
                           ;; card set zero padding and count is 1 byte long
                           card-set-pad-and-count (nth deck-bytes @byte-index)
                           pad (-> card-set-pad-and-count
                                   (bit-shift-right 6)
                                   inc)
                           card-set-count (-> card-set-pad-and-count
                                              (bit-and 0x3F))
                           _ (swap! byte-index inc)]
                       (recur (->> (loop [cards []
                                          card-index 0
                                          prev-card-number 0]
                                     (if (< card-index card-set-count)
                                       (let [[card-count parallel-id number]
                                             (apply read-serialized-card
                                                    [deck-bytes
                                                     byte-index
                                                     total-card-bytes
                                                     prev-card-number])
                                             fstr (str "~A-~" pad ",'0d")
                                             card
                                             (cond->
                                                 {:card/number
                                                  (str card-set "-"
                                                       (loop [n (str number)]
                                                         (if (< (count n)
                                                                pad)
                                                           (recur (str "0"
                                                                       n))
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
                                   (into []))
                              (inc card-set-index)))
                     deck))))
         []
         [:digi-egg :deck])]
    {:deck/digi-eggs digi-eggs
     :deck/deck main-deck
     :deck/name (if (< @byte-index (count deck-bytes))
                  (-> (->> (drop (- (count deck-bytes) string-length)
                                 deck-bytes)
                           (take string-length))
                      codec/bytes->string)
                  "")}))

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
  (-> deck-code-str
      decode-deck-string
      parse-deck))

#?(:clj
   (defn -main
     [& [deck-code-str]]
     (-> (or deck-code-str (string/trim (slurp *in*)))
         decode
         pprint/pprint)))
