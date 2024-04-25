(ns dcg.card.assertion
  (:require
   [clojure.set :as set]
   [clojure.string :as string]))

(defn- highlights
  "Card highlights that differ across languages"
  [cards]
  (->> cards
       (filter (fn [{:card/keys [image language]}]
                 (= language
                    (:image/language image))))
       (group-by :card/number)
       (reduce-kv (fn [accl number card-group]
                    (let [result
                          (->> card-group
                               (map (comp #(apply hash-map %)
                                          (juxt :card/id
                                                (comp #(dissoc %
                                                               :treat
                                                               :mention)
                                                      frequencies
                                                      #(map :highlight/type %)
                                                      :card/highlights))))
                               (apply merge)
                               (into (sorted-map)))]
                      (if (apply = (vals result))
                        accl
                        (assoc accl number result))))
                  (sorted-map))))

(defn- text-fields
  [cards]
  "Card text fields that differ across languages"
  (->> cards
       (filter (fn [{:card/keys [image language]}]
                 (= language
                    (:image/language image))))
       (group-by :card/number)
       (reduce-kv (fn [accl number card-group]
                    (let [expected-keys
                          (->> card-group
                               (map (fn [card]
                                      (->> [:card/name
                                            :card/form
                                            :card/attribute
                                            :card/type
                                            :card/effect
                                            :card/inherited-effect
                                            :card/security-effect]
                                           (select-keys card)
                                           (reduce-kv
                                            (fn [m k v]
                                              (cond-> m
                                                (and (string? v)
                                                     (not (string/starts-with?
                                                           v "※")))
                                                (assoc k v)))
                                            {})
                                           keys)))
                               (sort-by count >)
                               first)
                          diffmap
                          (reduce (fn [accl2 {:card/keys [id] :as card}]
                                    (let [ks (-> card
                                                 (select-keys expected-keys)
                                                 keys)]
                                      (cond-> accl2
                                        (not= ks expected-keys)
                                        (assoc id
                                               (set/difference
                                                (set expected-keys)
                                                (set ks))))))
                                  {}
                                  card-group)]
                      (cond-> accl
                        (not (empty? diffmap))
                        (assoc number diffmap))))
                  {})))

(defn- card-values
  "JA card values that differ which are used as common values across languages"
  [cards]
  (->> cards
       (reduce (fn [accl {:card/keys [number language] :as card}]
                 (if (= language "ja")
                   (update-in accl [number] (comp set conj)
                              (select-keys card [:card/rarity
                                                 :card/level
                                                 :card/dp
                                                 :card/play-cost
                                                 :card/use-cost]))
                   accl))
               {})
       (reduce (fn [accl [number counts-by-lang]]
                 (if (apply = counts-by-lang)
                   accl
                   (conj accl [number counts-by-lang])))
               [])))

(defn card-assertions
  [cards]
  (assert (empty? (card-values cards))
          "JA card values differ across languages")
  (assert (= (highlights cards)
             {"BT10-099"
              {"card/en_BT10-099_P0" {:timing 3 :keyword-effect 1}
               "card/ja_BT10-099_P0" {:timing 3 :keyword-effect 1}
               "card/zh-Hans_BT10-099_P0" {:timing 3 :keyword-effect 3}}})
          "Card highlights differ across languages")
  (assert (empty? (text-fields cards))
          "Card text fields differ across languages")
  cards)
