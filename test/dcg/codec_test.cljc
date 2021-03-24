(ns dcg.codec-test
  (:require
   [dcg.codec.encode :as encode]
   [dcg.codec.decode :as decode]
   #?(:clj [clojure.test :as t]
      :cljs [cljs.test :as t :include-macros true])))

(t/deftest codec
  (t/testing "Deck encode and decode round-trip"
    (let [deck {:deck/digi-eggs [{:card/id "ST3-01"
                                  :card/count 4}]
                :deck/deck [{:card/id "ST3-02"
                             :card/count 4}
                            {:card/id "ST3-03"
                             :card/count 4}
                            {:card/id "ST3-04"
                             :card/count 4}
                            {:card/id "ST3-05"
                             :card/count 2}
                            {:card/id "ST3-06"
                             :card/count 4}
                            {:card/id "ST3-07"
                             :card/count 4}
                            {:card/id "ST3-08"
                             :card/count 4}
                            {:card/id "ST3-09"
                             :card/count 4}
                            {:card/id "ST3-10"
                             :card/count 2}
                            {:card/id "ST3-11"
                             :card/count 2}
                            {:card/id "ST3-12"
                             :card/count 4}
                            {:card/id "ST3-13"
                             :card/count 4}
                            {:card/id "ST3-14"
                             :card/count 2}
                            {:card/id "ST3-15"
                             :card/count 4}
                            {:card/id "ST3-16"
                             :card/count 2}]
                :deck/name "Starter Deck, Heaven's Yellow [ST-3]"}]
      (t/is (= deck
               (-> deck
                   encode/encode
                   decode/decode))))))
