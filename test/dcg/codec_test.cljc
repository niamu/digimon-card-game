(ns dcg.codec-test
  (:require
   [dcg.codec.encode :as encode]
   [dcg.codec.decode :as decode]
   #?(:clj [clojure.test :as t]
      :cljs [cljs.test :as t :include-macros true])))

(def st1-deck
  {:deck/digi-eggs [{:card/id "ST1-01", :card/count 4}],
   :deck/deck [{:card/id "ST1-02", :card/count 4}
               {:card/id "ST1-03", :card/count 4}
               {:card/id "ST1-04", :card/count 4}
               {:card/id "ST1-05", :card/count 4}
               {:card/id "ST1-06", :card/count 4}
               {:card/id "ST1-07", :card/count 2}
               {:card/id "ST1-08", :card/count 4}
               {:card/id "ST1-09", :card/count 4}
               {:card/id "ST1-10", :card/count 2}
               {:card/id "ST1-11", :card/count 2}
               {:card/id "ST1-12", :card/count 4}
               {:card/id "ST1-13", :card/count 4}
               {:card/id "ST1-14", :card/count 4}
               {:card/id "ST1-15", :card/count 2}
               {:card/id "ST1-16", :card/count 2}],
   :deck/name "Starter Deck, Gaia Red [ST-1]"})

(def st1-deck-encoded
  (str "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVk"
       "IFtTVC0xXQ"))

(def digi-bros-deck
  #:deck{:digi-eggs [#:card{:id "BT2-001", :count 4}
                     #:card{:id "ST1-01", :count 1}],
         :deck [#:card{:id "BT1-009", :count 1}
                #:card{:id "BT1-019", :count 4}
                #:card{:id "BT1-020", :count 2}
                #:card{:id "BT1-085", :count 2, :parallel-id 1}
                #:card{:id "BT2-016", :count 4}
                #:card{:id "BT3-008", :count 4}
                #:card{:id "BT3-013", :count 4}
                #:card{:id "BT3-016", :count 3}
                #:card{:id "BT3-018", :count 2}
                #:card{:id "BT3-019", :count 4}
                #:card{:id "BT3-072", :count 3}
                #:card{:id "ST1-02", :count 4}
                #:card{:id "ST1-03", :count 4}
                #:card{:id "ST1-06", :count 3}
                #:card{:id "ST1-07", :count 1}
                #:card{:id "ST1-07", :count 3, :parallel-id 1}
                #:card{:id "ST1-16", :count 2, :parallel-id 1}],
         :name "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"})

(def digi-bros-deck-encoded
  "DCGApwzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiE0CRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp")

(def ja-deck
  #:deck{:digi-eggs [#:card{:id "BT1-001", :count 2}
                     #:card{:id "BT2-001", :count 3}],
         :deck [#:card{:id "BT1-009", :count 4}
                #:card{:id "BT1-019", :count 4}
                #:card{:id "BT1-020", :count 2}
                #:card{:id "BT1-025", :count 3}
                #:card{:id "BT1-085", :count 3}
                #:card{:id "BT2-009", :count 2}
                #:card{:id "BT2-016", :count 4}
                #:card{:id "BT2-018", :count 2}
                #:card{:id "BT3-018", :count 3}
                #:card{:id "P-009", :count 4}
                #:card{:id "ST1-03", :count 4}
                #:card{:id "ST1-06", :count 4}
                #:card{:id "ST1-07", :count 4}
                #:card{:id "ST1-09", :count 3}
                #:card{:id "ST1-16", :count 4}],
         :name "予算の赤いデッキ"})

(def ja-deck-encoded
  (str "DCGAl4YIEJUMYFBIEJUMoGBIEJUMYXFAsYCQYUBhA8gQlQyg0UCxwFCIEJUM4GGBCAgIFCB"
       "xQIgU1QxRcPDwYLHAeS6iOeul-OBrui1pOOBhOODh-ODg-OCrQ"))

(def invalid-deck
  {:deck/digi-eggs [{:card/id "ST1-01", :card/count 3}
                    {:card/id "ST1-01", :card/count 1}
                    {:card/id "ST2-01", :card/count 1}],
   :deck/deck [{:card/id "ST1-02", :card/count 0}
               {:card/id "ST1-03", :card/count 4}
               {:card/id "ST1-04", :card/count 4}
               {:card/id "ST1-05", :card/count 4}
               {:card/id "ST1-06", :card/count 4}
               {:card/id "ST1-07", :card/count 2}
               {:card/id "ST1-08", :card/count 4}
               {:card/id "ST1-09", :card/count 4}
               {:card/id "ST1-10", :card/count 2}
               {:card/id "ST1-11", :card/count 2}
               {:card/id "ST1-12", :card/count 4}
               {:card/id "ST1-13", :card/count 4}
               {:card/id "ST1-14", :card/count 4}
               {:card/id "XXXXX-15", :card/count 2}
               {:card/id "ST1-99999", :card/count 4}
               {:card/id "ST1-16", :card/count 5}],
   :deck/name (apply str (repeat 64 "_"))})

(t/deftest codec-round-trip
  (t/testing "Deck encode and decode round-trip"
    (t/is (= digi-bros-deck
             (-> digi-bros-deck
                 encode/encode
                 decode/decode)))
    (t/is (= ja-deck
             (-> ja-deck
                 encode/encode
                 decode/decode)))))

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
