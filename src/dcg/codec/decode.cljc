(ns dcg.codec.decode
  #?(:clj (:gen-class))
  (:require
   [dcg.codec.common :as codec]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   #?@(:cljs [[goog.crypt :as crypt]
              [goog.crypt.base64 :as b64]]))
  #?(:clj (:import
           [java.util Base64])))

(defn byte-buffer->string
  [b]
  #?(:clj (-> b byte-array (String. "UTF8"))
     :cljs (-> b clj->js crypt/utf8ByteArrayToString)))

(defn- continue?
  [chunk num-bits]
  (not= 0 (bit-and chunk (bit-shift-left 1 num-bits))))

(defn- read-bits-chunk
  [chunk num-bits curr-shift out-bits]
  (bit-or (bit-shift-left (bit-and chunk (dec (bit-shift-left 1 num-bits)))
                          curr-shift)
          out-bits))

(defn- read-var-encoded-uint32
  [base-value base-bits data current-byte-idx idx-end]
  (if (or (= 0 base-bits)
          (continue? base-value base-bits))
    (loop [out-value (if (continue? base-value base-bits)
                       (read-bits-chunk base-value base-bits 0 0)
                       0)
           delta-shift base-bits]
      (when (> @current-byte-idx idx-end)
        (throw (#?(:clj Exception. :cljs js/Error.) "End of block.")))
      (swap! current-byte-idx inc)
      (cond-> (read-bits-chunk (nth data (dec @current-byte-idx))
                               7
                               delta-shift
                               out-value)
        (continue? (nth data (dec @current-byte-idx)) 7)
        (recur (+ delta-shift 7))))
    (read-bits-chunk base-value base-bits 0 0)))

(defn- read-serialized-card
  [deck-bytes byte-idx idx-end prev-card-base]
  (when (> @byte-idx idx-end)
    (throw (#?(:clj Exception. :cljs js/Error.) "End of block.")))
  (let [header (nth deck-bytes @byte-idx)]
    (swap! byte-idx inc)
    [ ;; 2 bits for card count (1-4)
     (inc (bit-shift-right header 6))
     ;; 3 bits for card parallel-id (0-7)
     (-> header
         (bit-shift-right 3)
         (bit-and 0x07))
     ;; card id offset
     (+ prev-card-base
        (read-var-encoded-uint32 header 2 deck-bytes byte-idx idx-end))]))

(defn- parse-deck
  [deck-bytes]
  (let [version (nth deck-bytes 0)
        checksum (nth deck-bytes 1)
        string-length (nth deck-bytes 2)
        total-card-bytes (- (count deck-bytes) string-length)
        computed-checksum (reduce + (->> (take total-card-bytes deck-bytes)
                                         (drop codec/header-size)))
        _ (when-not (= codec/version (bit-shift-right version 4))
            (throw (#?(:clj Exception. :cljs js/Error.)
                    (str "Invalid version: "
                         codec/version " != "
                         (bit-shift-right version 4)))))
        _ (when-not (= checksum (bit-and computed-checksum 0xFF))
            (throw (#?(:clj Exception. :cljs js/Error.) "Invalid checksum.")))
        byte-idx (atom 0)
        _ (reset! byte-idx 3)
        digi-egg-set-count (read-var-encoded-uint32 version
                                                    3
                                                    deck-bytes
                                                    byte-idx
                                                    total-card-bytes)
        [digi-eggs main-deck]
        (reduce
         (fn [accl deck-type]
           (conj accl
                 (loop [deck []
                        card-set-idx 0]
                   (if (condp = deck-type
                         :digi-egg (< card-set-idx digi-egg-set-count)
                         (< @byte-idx total-card-bytes))
                     (let [;; card set is 4 characters/bytes
                           card-set (-> (drop @byte-idx deck-bytes)
                                        (as-> deck-bytes
                                            (take 4 deck-bytes))
                                        byte-buffer->string
                                        string/trim)
                           _ (swap! byte-idx #(+ % 4))
                           ;; card set zero padding and count is 1 byte long
                           card-set-pad-and-count (nth deck-bytes @byte-idx)
                           pad (-> card-set-pad-and-count
                                   (bit-shift-right 6)
                                   inc)
                           card-set-count (-> card-set-pad-and-count
                                              (bit-and 0x3F))
                           _ (swap! byte-idx inc)]
                       (recur (->> (loop [cards []
                                          card-idx 0
                                          prev-card-base 0]
                                     (if (< card-idx card-set-count)
                                       (let [[card-count parallel-id id]
                                             (apply read-serialized-card
                                                    [deck-bytes
                                                     byte-idx
                                                     total-card-bytes
                                                     prev-card-base])
                                             fstr (str "~A-~" pad ",'0d")
                                             card
                                             (cond-> {:card/id
                                                      (pprint/cl-format nil
                                                                        fstr
                                                                        card-set
                                                                        id)
                                                      :card/count card-count}
                                               (not (zero? parallel-id))
                                               (assoc :card/parallel-id
                                                      parallel-id))]
                                         (recur (conj cards card)
                                                (inc card-idx)
                                                id))
                                       cards))
                                   (concat deck)
                                   (into []))
                              (inc card-set-idx)))
                     deck))))
         []
         [:digi-egg :deck])]
    {:deck/digi-eggs digi-eggs
     :deck/deck main-deck
     :deck/name (if (< @byte-idx (count deck-bytes))
                  (-> (->> (drop (- (count deck-bytes) string-length)
                                 deck-bytes)
                           (take string-length))
                      byte-buffer->string)
                  "")}))

(defn- decode-deck-string
  [deck-code-str]
  (when (string/starts-with? deck-code-str codec/prefix)
    (->> (string/replace (subs deck-code-str (count codec/prefix))
                         #"-|_" {"-" "/" "_" "="})
         #?(:clj (.decode (Base64/getDecoder))
            :cljs (b64/decodeStringToByteArray))
         (map #(bit-and % 0xFF)))))

(defn decode
  [deck-code-str]
  (-> deck-code-str
      decode-deck-string
      parse-deck))

(defn -main
  [& [deck-code-str]]
  (-> (or deck-code-str #?(:clj (string/trim (slurp *in*))))
      decode
      pprint/pprint))
