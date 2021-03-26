(ns dcg.json
  (:require
   [cljs.pprint :as pprint]
   [dcg.codec.encode :as encode]
   [dcg.codec.decode :as decode]))

(defn ^:export encode
  [^String deck]
  (-> (.parse js/JSON deck)
      (js->clj :keywordize-keys true)
      encode/encode))

(defn ^:export decode
  [^String deck-code-str]
  (-> (.trim deck-code-str)
      decode/decode
      (clj->js :keyword-fn (comp #(subs % 1) str))
      (as-> x (.stringify js/JSON x nil 2))))
