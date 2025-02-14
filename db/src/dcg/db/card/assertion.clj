(ns dcg.db.card.assertion
  (:require
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string])
  (:import
   [java.io PushbackReader]))

(def category-keyword
  {"Digi-Egg"    :digi-egg
   "デジタマ"    :digi-egg
   "디지타마"    :digi-egg
   "数码蛋"      :digi-egg
   "Digimon"     :digimon
   "デジモン"    :digimon
   "디지몬"      :digimon
   "数码宝贝"    :digimon
   "Tamer"       :tamer
   "テイマー"    :tamer
   "테이머"      :tamer
   "驯兽师"      :tamer
   "Option"      :option
   "オプション"  :option
   "옵션"        :option
   "选项"        :option})

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
                  (sorted-map))))

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
       (group-by :card/number)
       (reduce-kv
        (fn [accl number card-group]
          (let [result
                (->> card-group
                     (map (comp #(apply hash-map %)
                                (juxt :card/id
                                      (comp category-keyword :card/category))))
                     (apply merge)
                     (into (sorted-map)))]
            (if (apply = (vals result))
              accl
              (assoc accl number result))))
        (sorted-map))))

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

(defn- card-block-icons
  [cards]
  (let [expected-block-icons {"ST1" nil
                              "ST2" nil
                              "ST3" nil
                              "BT1" nil
                              "BT2" nil
                              "BT3" nil
                              "ST4" nil
                              "ST5" nil
                              "ST6" nil
                              "BT4" nil
                              "BT5" nil
                              "ST7" 1
                              "ST8" 1
                              "BT6" 1
                              "EX1" 1
                              "BT7" 1
                              "ST9" 1
                              "ST10" 1
                              "BT8" 1
                              "EX2" 1
                              "BT9" 1
                              "ST12" 2
                              "ST13" 2
                              "BT10" 2
                              "EX3" 2
                              "BT11" 2
                              "BT12" 2
                              "ST14" 2
                              "EX4" 2
                              "RB1" 2
                              "BT13" 2
                              "ST15" 3
                              "ST16" 3
                              "BT14" 3
                              "LM" 3
                              "EX5" 3
                              "BT15" 3
                              "ST17" 3
                              "BT16" 3
                              "EX6" 3
                              "BT17" 3
                              "ST18" 4
                              "ST19" 4
                              "BT18" 4
                              "EX7" 4
                              "BT19" 4
                              "EX8" 4
                              "BT20" 4}]
    (->> cards
         (filter (fn [{:card/keys [language image]}]
                   (= language (:image/language image))))
         (reduce (fn [accl {:card/keys [id number block-icon]
                           :as card}]
                   (update-in accl [(string/replace number #"\-[0-9]+" "")
                                    block-icon]
                              (fnil conj #{})
                              id))
                 {})
         (reduce-kv (fn [accl release kv]
                      (let [result (dissoc kv
                                           (get expected-block-icons
                                                release :not-found))]
                        (cond-> accl
                          (seq result)
                          (assoc release result))))
                    (sorted-map)))))

(defn card-assertions
  [cards]
  (assert (empty? (card-values cards))
          (format "JA card values differ across languages:\n%s"
                  (card-values cards)))
  (assert (empty? (text-fields cards))
          (format "Card text fields differ across languages:\n%s"
                  (text-fields cards)))
  (assert (empty? (card-categories cards))
          (format "Card categories differ across languages:\n%s"
                  (card-categories cards)))
  (assert (= (highlights cards)
             {"BT10-099"
              {"card/en_BT10-099_P0" {:timing 3 :keyword-effect 1}
               "card/ja_BT10-099_P0" {:timing 3 :keyword-effect 1}
               "card/zh-Hans_BT10-099_P0" {:timing 3 :keyword-effect 3}
               "card/ko_BT10-099_P0" {:timing 3 :keyword-effect 1}}})
          (format "Card highlights differ across languages:\n%s"
                  (highlights cards)))
  (let [missing-block-icons
        (first
         (data/diff (card-block-icons cards)
                    (edn/read (PushbackReader.
                               (io/reader
                                (io/resource "block-icons.edn"))))))]
    (assert (empty? missing-block-icons)
            (format "Card block icons do not match expected output:\n%s"
                    missing-block-icons)))
  (assert (every? (fn [[_ rarities]]
                    (= rarities #{"C" "U" "R" "SR" "SEC" "P"}))
                  (card-rarities cards))
          (format "Card rarity fields across languages do not amount to 6:\n%s"
                  (card-rarities cards)))
  (assert (= (card-errata cards)
             {"en" #{"BT3-111"
                     "BT4-041"
                     "P-071"
                     "EX1-073"}
              "ja" #{"BT10-051"
                     "BT10-092"
                     "EX1-001"
                     "BT11-099"
                     "BT6-084"
                     "BT7-005"
                     "BT7-049"
                     "BT7-055"
                     "BT7-083"
                     "BT9-073"}})
          (format "Card errata not accounted for:\n%s"
                  (card-errata cards)))
  cards)

(comment
  (highlights dcg.db.core/*cards)

  (->> dcg.db.core/*cards
       (filter (fn [{:card/keys [number]}]
                 (= number "BT20-090")))
       (map (juxt :card/effect)))
  ;; Card values analysis
  (map (fn [[k v]]
         (let [issues (->> (partition 2 1
                                      (vals v))
                           (map #(apply clojure.data/diff %))
                           (remove (fn [[only-in-a only-in-b in-both]]
                                     (and (nil? only-in-a)
                                          (nil? only-in-b))))
                           (map #(take 2 %)))
               issue-keys (vec (into #{} (mapcat (fn [x] (mapcat keys x)) issues)))
               incorrect (->> (vals v)
                              (map #(select-keys % issue-keys))
                              frequencies
                              (sort-by val)
                              ffirst)]
           {k {:issue/keys issue-keys
               :issue/incorrect (->> (filter (fn [[k v]]
                                               (= (select-keys v issue-keys)
                                                  incorrect))
                                             v)
                                     (map (fn [[k v]]
                                            {k (select-keys v issue-keys)}))
                                     first)
               :issue/correct (->> (filter (fn [[k v]]
                                             (not= (select-keys v issue-keys)
                                                   incorrect))
                                           v)
                                   (map (fn [[k v]]
                                          (select-keys v issue-keys)))
                                   first)}}))
       (card-values dcg.db.core/*cards))
  ;; Update block-icons.edn resource
  (let [block-icons (edn/read (PushbackReader.
                               (io/reader
                                (io/resource "block-icons.edn"))))]
    (spit (io/resource "block-icons.edn")
          (merge-with (partial merge-with set/union)
                      block-icons
                      (into (sorted-map)
                            (first
                             (data/diff (card-block-icons dcg.db.core/*cards)
                                        block-icons)))))))
