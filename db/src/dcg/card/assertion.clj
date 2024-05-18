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
       (group-by :card/number)
       (reduce-kv
        (fn [accl number card-group]
          (let [result
                (->> card-group
                     (map (comp #(apply hash-map %)
                                (juxt :card/id
                                      #(->> [:card/color
                                             :card/rarity
                                             :card/level
                                             :card/dp
                                             :card/play-cost
                                             :card/use-cost
                                             :card/digivolution-requirements]
                                            (select-keys %)))))
                     (apply merge)
                     (into (sorted-map)))]
            (if (apply = (vals result))
              accl
              (assoc accl number result))))
        (sorted-map))))

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
  (assert (= (card-categories cards)
             {"en" #{"Digi-Egg" "Option" "Digimon" "Tamer"},
              "ja" #{"オプション" "テイマー" "デジモン" "デジタマ"},
              "ko" #{"디지타마" "디지몬" "옵션" "테이머"},
              "zh-Hans" #{"数码宝贝" "驯兽师" "数码蛋" "选项"}})
          "Card category fields across languages do not amount to 4")
  (assert (every? (fn [[_ rarities]]
                    (= rarities #{"C" "U" "R" "SR" "SEC" "P"}))
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
