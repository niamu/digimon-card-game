(ns dcg.codec-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [dcg.codec.encode :as encode]
   [dcg.codec.decode :as decode]
   #?(:clj [clojure.test :as t]
      :cljs [cljs.test :as t :include-macros true])))

(def st1-deck
  {:deck/digi-eggs [{:card/number "ST1-01", :card/count 4}],
   :deck/deck [{:card/number "ST1-02", :card/count 4}
               {:card/number "ST1-03", :card/count 4}
               {:card/number "ST1-04", :card/count 4}
               {:card/number "ST1-05", :card/count 4}
               {:card/number "ST1-06", :card/count 4}
               {:card/number "ST1-07", :card/count 2}
               {:card/number "ST1-08", :card/count 4}
               {:card/number "ST1-09", :card/count 4}
               {:card/number "ST1-10", :card/count 2}
               {:card/number "ST1-11", :card/count 2}
               {:card/number "ST1-12", :card/count 4}
               {:card/number "ST1-13", :card/count 4}
               {:card/number "ST1-14", :card/count 4}
               {:card/number "ST1-15", :card/count 2}
               {:card/number "ST1-16", :card/count 2}],
   :deck/name "Starter Deck, Gaia Red [ST-1]"})

(def st1-deck-encoded
  (str "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVk"
       "IFtTVC0xXQ"))

(def digi-bros-deck
  {:deck/digi-eggs [{:card/number "BT2-001", :card/count 4}
                    {:card/number "ST1-01", :card/count 1}]
   :deck/deck [{:card/number "BT1-009", :card/count 1}
               {:card/number "BT1-019", :card/count 4}
               {:card/number "BT1-020", :card/count 2}
               {:card/number "BT1-085", :card/count 2, :card/parallel-id 1}
               {:card/number "BT2-016", :card/count 4}
               {:card/number "BT3-008", :card/count 4}
               {:card/number "BT3-013", :card/count 4}
               {:card/number "BT3-016", :card/count 3}
               {:card/number "BT3-018", :card/count 2}
               {:card/number "BT3-019", :card/count 4}
               {:card/number "BT3-072", :card/count 3}
               {:card/number "ST1-02", :card/count 4}
               {:card/number "ST1-03", :card/count 4}
               {:card/number "ST1-06", :card/count 3}
               {:card/number "ST1-07", :card/count 1}
               {:card/number "ST1-07", :card/count 3, :card/parallel-id 1}
               {:card/number "ST1-16", :card/count 2}]
   :deck/name "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"})

(def digi-bros-deck-encoded
  (str "DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1Qx"
       "IEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1c"
       "yd3doUjQp"))

(def ja-deck
  (assoc st1-deck :deck/name "予算の赤いデッキ"))

(def invalid-deck
  {:deck/digi-eggs [{:card/number "ST1-01", :card/count 3}
                    {:card/number "ST1-01", :card/count 1}
                    {:card/number "ST2-01", :card/count 1}],
   :deck/deck [{:card/number "ST1-02", :card/count 0}
               {:card/number "ST1-03", :card/count 4}
               {:card/number "ST1-04", :card/count 4}
               {:card/number "ST1-05", :card/count 4}
               {:card/number "ST1-06", :card/count 4}
               {:card/number "ST1-07", :card/count 2}
               {:card/number "ST1-08", :card/count 4}
               {:card/number "ST1-09", :card/count 4}
               {:card/number "ST1-10", :card/count 2}
               {:card/number "ST1-11", :card/count 2}
               {:card/number "ST1-12", :card/count 4}
               {:card/number "ST1-13", :card/count 4}
               {:card/number "ST1-14", :card/count 4}
               {:card/number "XXXXX-15", :card/count 2}
               {:card/number "ST1-99999", :card/count 4}
               {:card/number "ST1-16", :card/count 5}],
   :deck/name (apply str (repeat 64 "_"))})

(t/deftest codec-round-trip
  (t/testing "Deck encode and decode round-trip"
    (t/are [deck] (= deck (-> deck
                              encode/encode
                              decode/decode))
      st1-deck
      digi-bros-deck
      ja-deck)))

(t/deftest stable-decoder
  (t/testing "Deck decoding of old strings is stable"
    (t/is (= digi-bros-deck (decode/decode digi-bros-deck-encoded)))))

(t/deftest stable-encoder
  (t/testing "Deck encoding of old deck is stable"
    (t/is (= (encode/encode digi-bros-deck) digi-bros-deck-encoded))))

;; st1-deck return 58 bytes before Base64 encoding which requires 2 bytes
;; of padding to be appended. This test checks that decoding works when
;; the padding is omitted by the Base64URL process
(t/deftest decode-without-b64-padding
  (t/testing "Deck decodes without Base64 padding on encoded string"
    (t/is (= (decode/decode st1-deck-encoded) st1-deck))))

(t/deftest invalid-deck-throws
  (t/testing "Invalid deck throws an Exception/Error"
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (encode/encode invalid-deck)))))

(t/deftest parallel-id-order
  (t/testing ":card/parallel-id order does not affect encoder equality"
    (t/are [deck1 deck2] (= (encode/encode deck1) (encode/encode deck2))
      {:deck/digi-eggs [{:card/number "ST1-01", :card/count 4}],
       :deck/deck [{:card/number "ST1-02", :card/count 2}
                   {:card/number "ST1-02", :card/count 2 :card/parallel-id 1}
                   {:card/number "ST1-03", :card/count 4}
                   {:card/number "ST1-04", :card/count 4}
                   {:card/number "ST1-05", :card/count 4}
                   {:card/number "ST1-06", :card/count 4}
                   {:card/number "ST1-07", :card/count 2}
                   {:card/number "ST1-08", :card/count 4}
                   {:card/number "ST1-09", :card/count 4}
                   {:card/number "ST1-10", :card/count 2}
                   {:card/number "ST1-11", :card/count 2}
                   {:card/number "ST1-12", :card/count 4}
                   {:card/number "ST1-13", :card/count 4}
                   {:card/number "ST1-14", :card/count 4}
                   {:card/number "ST1-15", :card/count 2}
                   {:card/number "ST1-16", :card/count 2}],
       :deck/name "Starter Deck, Gaia Red [ST-1]"}
      {:deck/digi-eggs [{:card/number "ST1-01", :card/count 4}],
       :deck/deck [{:card/number "ST1-02", :card/count 2 :card/parallel-id 1}
                   {:card/number "ST1-02", :card/count 2}
                   {:card/number "ST1-03", :card/count 4}
                   {:card/number "ST1-04", :card/count 4}
                   {:card/number "ST1-05", :card/count 4}
                   {:card/number "ST1-06", :card/count 4}
                   {:card/number "ST1-07", :card/count 2}
                   {:card/number "ST1-08", :card/count 4}
                   {:card/number "ST1-09", :card/count 4}
                   {:card/number "ST1-10", :card/count 2}
                   {:card/number "ST1-11", :card/count 2}
                   {:card/number "ST1-12", :card/count 4}
                   {:card/number "ST1-13", :card/count 4}
                   {:card/number "ST1-14", :card/count 4}
                   {:card/number "ST1-15", :card/count 2}
                   {:card/number "ST1-16", :card/count 2}],
       :deck/name "Starter Deck, Gaia Red [ST-1]"})))

(defn clean-deck
  [cards]
  ((comp set
         #(map (fn [card]
                 (cond-> card
                   (zero? (:card/parallel-id card 0))
                   (dissoc :card/parallel-id)))
               %))
   cards))

#?(:clj
   (t/deftest generated-decks
     (t/testing "Generated decks succeed round-trip of codec"
       (loop [iteration 0]
         (when (< iteration 100)
           (if-let [deck (try (gen/generate (s/gen :dcg.codec.encode/deck))
                              (catch Exception e nil))]
             (do (t/is (= (-> deck
                              encode/encode
                              decode/decode
                              (update :deck/digi-eggs clean-deck)
                              (update :deck/deck clean-deck))
                          (-> deck
                              (update :deck/digi-eggs clean-deck)
                              (update :deck/deck clean-deck))))
                 (recur (inc iteration)))
             (recur iteration)))))))
