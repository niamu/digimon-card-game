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
                   (update-in accl [number] (fnil conj #{})
                              (select-keys card [:card/color
                                                 :card/rarity
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

(defn- card-digivolution-requirements
  [cards]
  (->> cards
       (filter :card/digivolution-requirements)
       (reduce (fn [accl {:card/keys [number digivolution-requirements] :as card}]
                 (let [broken (remove (fn [{:digivolve/keys [color
                                                            cost
                                                            level]}]
                                        (every? (complement nil?)
                                                [color
                                                 cost
                                                 level]))
                                      digivolution-requirements)]
                   (cond-> accl
                     (seq broken)
                     (update-in [number] (fnil conj #{})
                                broken))))
               {})))

(defn- card-categories
  [cards]
  (->> cards
       (reduce (fn [accl {:card/keys [language category]}]
                 (update-in accl [language] (fnil conj #{}) category))
               {})))

(defn- card-rarities
  [cards]
  (->> cards
       (reduce (fn [accl {:card/keys [language rarity]}]
                 (update-in accl [language] (fnil conj #{}) rarity))
               {})))

(defn- card-errata
  [cards]
  (->> cards
       (filter (fn [{:card/keys [language image errata] :as card}]
                 (and errata
                      (= language (:image/language image)))))
       (remove (fn [{{:errata/keys [correction]} :card/errata :as card}]
                 (let [corrections (-> correction
                                       string/lower-case
                                       (string/replace "’" "'")
                                       (string/replace #"\]([A-Z])" "] $1")
                                       (string/replace #"(?i)\s*\[?((Inherited|Security)\s)?Effect\]?\s+"
                                                       "\n")
                                       string/trim
                                       string/split-lines
                                       (as-> #__ coll
                                         (map string/trim coll)))
                       s (->> [(:card/effect card)
                               (:card/inherited-effect card)
                               (:card/security-effect card)
                               (:card/form card)
                               (:card/attribute card)
                               (:card/type card)
                               (:card/name card)]
                              (remove nil?)
                              string/join)]
                   (some (fn [correction]
                           (-> s
                               string/lower-case
                               (string/replace "\n" " ")
                               (string/replace #"\s+" " ")
                               (string/includes? correction)))
                         corrections))))
       (reduce (fn [accl {:card/keys [language number]}]
                 (update accl language (fnil conj #{}) number))
               {})))

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
  (assert (empty? (card-digivolution-requirements cards))
          "Card digivolution requirements do not have all expected values")
  (assert (every? (fn [[_ categories]]
                    (= (count categories) 4))
                  (card-categories cards))
          "Card category fields across languages do not amount to 4")
  (assert (every? (fn [[_ rarities]]
                    (= (count rarities) 6))
                  (card-rarities cards))
          "Card rarity fields across languages do not amount to 6")
  (assert (= (card-errata cards)
             {"en" #{"BT3-111"
                     "BT4-041"
                     "P-071"}
              "ja" #{"BT10-051"
                     "EX1-001"
                     "BT11-099"
                     "BT6-084"
                     "BT7-005"
                     "BT7-049"
                     "BT7-055"
                     "BT7-083"
                     "BT9-073"}})
          "Card errata not accounted for")
  cards)
