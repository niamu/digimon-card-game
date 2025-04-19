(ns dcg.db.card.assertion
  (:require
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string])
  (:import
   [java.io PushbackReader]))

(defn- rules
  "Card rules that differ across languages"
  [cards]
  (->> cards
       (filter :card/rules)
       (group-by :card/number)
       (reduce-kv (fn [accl number card-group]
                    (let [result
                          (->> card-group
                               (map (comp #(apply hash-map %)
                                          (juxt :card/id
                                                (comp frequencies
                                                      #(map :rule/type %)
                                                      :card/rules))))
                               (apply merge)
                               (into (sorted-map)))]
                      (if (apply = (vals result))
                        accl
                        (assoc accl number result))))
                  (sorted-map))))

(defn- field-translations
  [field cards]
  (let [tr-map (->> cards
                    (reduce (fn [accl {:card/keys [number language]
                                      :as card}]
                              (assoc-in accl
                                        [number language]
                                        (get card field)))
                            (sorted-map))
                    (reduce-kv
                     (fn [accl _ m]
                       (->> (keys (dissoc m "ja"))
                            (map (fn [l]
                                   (let [text (get m l)]
                                     (when-not (get-in accl [l text])
                                       {l {text (get m "ja")}}))))
                            (apply merge-with merge accl
                                   {"ja" {(get m "ja") (get m "ja")}})))
                     {}))]
    (->> cards
         (reduce (fn [accl {:card/keys [id number language] :as card}]
                   (let [value (get card field)
                         ja-text (get-in tr-map [language value])]
                     (update accl number merge
                             {[id value] ja-text})))
                 {})
         (reduce-kv (fn [m number v]
                      (cond-> m
                        (not (apply = (vals v)))
                        (assoc number
                               (let [most-common (->> (vals v)
                                                      frequencies
                                                      (sort-by val >)
                                                      ffirst)]
                                 {:expected (reduce-kv (fn [accl l m]
                                                         (assoc accl l
                                                                (get (set/map-invert m)
                                                                     most-common)))
                                                       {}
                                                       tr-map)
                                  :errors (->> v
                                               (remove (fn [[_ ja]]
                                                         (= ja most-common)))
                                               (into {}))}))))
                    (sorted-map)))))

(defn- highlight-translations
  [cards]
  (let [filter-fn (fn [{htype :highlight/type}]
                    ;; TODO: Add support for all highlight types
                    (or (= htype :timing)
                        (= htype :precondition)))
        tr-map
        (->> cards
             (reduce (fn [accl {:card/keys [number language highlights]}]
                       (assoc-in accl
                                 [number language]
                                 (->> highlights
                                      (filter filter-fn)
                                      (sort-by (juxt :highlight/field
                                                     :highlight/index))
                                      (map (juxt :highlight/type
                                                 :highlight/text)))))
                     (sorted-map))
             (reduce-kv
              (fn [accl _ m]
                (->> (keys (dissoc m "ja"))
                     (map (fn [l]
                            (let [new-texts (remove (fn [text] (get accl text))
                                                    (get m l))]
                              (->> (interleave
                                    new-texts
                                    (or (->> (get m "ja")
                                             (remove (fn [text]
                                                       (get (set/map-invert accl)
                                                            text)))
                                             seq)
                                        (->> (keep-indexed
                                              (fn [idx text]
                                                (when (contains? (set new-texts)
                                                                 text)
                                                  idx))
                                              (get m l))
                                             (map #(nth (get m "ja") %)))))
                                   (apply hash-map)))))
                     (apply merge accl)))
              {}))]
    (-> (group-by :card/number cards)
        (update-vals (fn [cards]
                       (let [l-map
                             (reduce
                              (fn [accl {:card/keys [language highlights]}]
                                (update accl
                                        language
                                        (fnil (comp set concat) [])
                                        (->> highlights
                                             (filter filter-fn)
                                             (map (juxt :highlight/type
                                                        :highlight/text)))))
                              {}
                              cards)]
                         (reduce-kv (fn [m l texts]
                                      (let [result
                                            (remove
                                             (fn [text]
                                               (contains? (get l-map "ja")
                                                          (get tr-map text)))
                                             texts)]
                                        (cond-> m
                                          (seq result)
                                          (assoc l result))))
                                    {}
                                    (dissoc l-map "ja")))))
        (as-> #__ xs
          (reduce-kv (fn [m number v]
                       (cond-> m
                         (seq v)
                         (assoc number v)))
                     (sorted-map)
                     xs)))))

(defn- digixros-highlights
  "DigiXros highlights should never be the first"
  [cards]
  (let [ignored-card-numbers #{"BT10-063"}]
    (->> cards
         (remove (fn [{:card/keys [number]}]
                   (contains? ignored-card-numbers
                              number)))
         (filter (fn [{:card/keys [highlights]}]
                   (some (fn [{highlight-type :highlight/type
                              :highlight/keys [index]}]
                           (and (= :digixros highlight-type)
                                (zero? index)))
                         highlights)))
         (map (juxt :card/language :card/number))
         set
         (sort-by second))))

(defn- highlights
  "Card highlights that differ across languages"
  [cards]
  (->> cards
       (group-by :card/number)
       (reduce-kv (fn [accl number card-group]
                    (let [result
                          (->> card-group
                               (map (comp #(apply hash-map %)
                                          (juxt :card/id
                                                (comp #(dissoc % :mention)
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
  "Card text fields that differ across languages"
  [cards]
  (->> cards
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
                        (seq diffmap)
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

(defn- card-errata
  [cards]
  (->> cards
       (filter :card/errata)
       (remove (fn [{{:errata/keys [correction]} :card/errata :as card}]
                 (let [corrections (-> correction
                                       string/lower-case
                                       (string/replace "…" "")
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
                               (string/replace "\n" "")
                               (string/replace #"\s+" " ")
                               (string/includes? correction)))
                         corrections))))
       (reduce (fn [accl {:card/keys [language number]}]
                 (update accl language (fnil conj #{}) number))
               {})))

(defn- card-digivolution-requirements
  [cards]
  (->> cards
       (filter :card/digivolution-requirements)
       (remove (fn [{:card/keys [digivolution-requirements form level]}]
                 (cond-> (every? :digivolve/cost digivolution-requirements)
                   (and (string/includes? form "アプモン")
                        (> level 3))
                   (and (some :digivolve/form digivolution-requirements)))))
       (reduce (fn [accl {:card/keys [number]}]
                 (conj accl number))
               #{})
       sort))

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
                              "BT20" 4
                              "BT21" 5
                              "ST20" 5
                              "ST21" 5}]
    (->> cards
         (reduce (fn [accl {:card/keys [id number block-icon]}]
                   (update-in accl
                              [(string/replace number #"\-[0-9]+" "")
                               block-icon]
                              (fnil conj #{})
                              id))
                 {})
         (reduce-kv (fn [accl release kv]
                      (let [result (reduce (fn [m b]
                                             (dissoc m (if (= b 0)
                                                         nil
                                                         b)))
                                           kv
                                           (range (or (get expected-block-icons
                                                           release 0)
                                                      0)
                                                  (->> expected-block-icons
                                                       vals
                                                       (map (fn [b]
                                                              (if (nil? b)
                                                                0
                                                                b)))
                                                       (apply max)
                                                       inc)))]
                        (cond-> accl
                          (and (seq result)
                               (not (contains? #{"P" "LM"} release)))
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
  (assert (empty? (field-translations :card/category cards))
          (format "Card categories differ across languages:\n%s"
                  (field-translations :card/category cards)))
  (assert (empty? (field-translations :card/form cards))
          (format "Card forms differ across languages:\n%s"
                  (field-translations :card/form cards)))
  (assert (= (field-translations :card/attribute cards)
             {"BT6-084" {:expected {"ja" "ウィルス種"
                                    "en" "Virus"
                                    "ko" "바이러스종"
                                    "zh-Hans" "病毒种"},
                         :errors {["card/en_BT6-084_P0" "Data"] "データ種",
                                  ["card/en_BT6-084_P2" "Data"] "データ種",
                                  ["card/en_BT6-084_P1" "Data"] "データ種"}},
              "BT7-083" {:expected
                         {"ja" "ウィルス種"
                          "en" "Virus"
                          "ko" "바이러스종"
                          "zh-Hans" "病毒种"},
                         :errors {["card/en_BT7-083_P0" "Data"] "データ種"}},
              "ST12-13" {:expected {"ja" "ウィルス種"
                                    "en" "Virus"
                                    "ko" "바이러스종"
                                    "zh-Hans" "病毒种"},
                         :errors {["card/en_ST12-13_P0" "Data"] "データ種",
                                  ["card/en_ST12-13_P1" "Data"] "データ種",
                                  ["card/en_ST12-13_P2" "Data"] "データ種"}}})
          (format "Card attributes differ across languages:\n%s"
                  (field-translations :card/attribute cards)))
  (assert (empty? (field-translations :card/rarity cards))
          (format "Card rarities differ across languages:\n%s"
                  (field-translations :card/rarity cards)))
  (assert (empty? (highlight-translations cards))
          (format "Card highlights do not match across languages:\n%s"
                  (highlight-translations cards)))
  (assert (empty? (card-digivolution-requirements cards))
          (format "Card digivolution requirements have issues:\n%s"
                  (card-digivolution-requirements cards)))
  (assert (= (highlights cards)
             {"BT10-099"
              {"card/en_BT10-099_P0" {:timing 3, :keyword-effect 1},
               "card/ja_BT10-099_P0" {:timing 3, :keyword-effect 1},
               "card/ko_BT10-099_P0" {:timing 3, :keyword-effect 1},
               "card/zh-Hans_BT10-099_P0" {:timing 3, :keyword-effect 3}},
              "BT11-054"
              {"card/en_BT11-054_P0"
               {:timing 2, :rule 1, :precondition 1, :keyword-effect 1},
               "card/en_BT11-054_P1"
               {:timing 2, :rule 1, :precondition 1, :keyword-effect 1},
               "card/ja_BT11-054_P0"
               {:timing 2, :rule 1, :precondition 1, :keyword-effect 1},
               "card/ko_BT11-054_P0"
               {:timing 2, :precondition 1, :keyword-effect 1},
               "card/zh-Hans_BT11-054_P0"
               {:timing 2, :rule 1, :precondition 1, :keyword-effect 1}},
              "EX4-057"
              {"card/en_EX4-057_P0"
               {:timing 3, :keyword-effect 2, :precondition 1},
               "card/en_EX4-057_P1"
               {:timing 3, :keyword-effect 1, :precondition 1},
               "card/ja_EX4-057_P0"
               {:timing 3, :keyword-effect 2, :precondition 1},
               "card/ja_EX4-057_P1"
               {:timing 3, :keyword-effect 1, :precondition 1},
               "card/ko_EX4-057_P0"
               {:timing 3, :keyword-effect 2, :precondition 1},
               "card/zh-Hans_EX4-057_P0"
               {:timing 3, :keyword-effect 2, :precondition 1},
               "card/zh-Hans_EX4-057_P1"
               {:timing 3, :keyword-effect 1, :precondition 1}}})
          (format "Card highlights differ across languages:\n%s"
                  (highlights cards)))
  (assert (empty? (digixros-highlights cards))
          (format "Card DigiXros highlights are incorrectly the first match:\n%s"
                  (digixros-highlights cards)))
  (assert (= (rules cards)
             {"BT14-052"
              {"card/en_BT14-052_P0" {:card/name 1},
               "card/ja_BT14-052_P0" {:card/name 1},
               "card/ko_BT14-052_P0" {},
               "card/zh-Hans_BT14-052_P0" {:card/name 1}}})
          (format "Card rules differ across languages:\n%s"
                  (rules cards)))
  (assert (= (card-errata cards)
             {"en" #{"BT3-111"
                     "P-071"
                     "BT21-023"
                     "BT4-041"
                     "EX1-073"},
              "ja" #{"BT6-084"
                     "EX1-001"
                     "BT10-058"
                     "BT7-083"
                     "BT9-073"
                     "BT7-005"
                     "BT10-092"
                     "BT7-055"
                     "BT11-099"
                     "BT10-051"
                     "BT7-049"}
              "zh-Hans" #{"LM-020"}})
          (format "Card errata not accounted for:\n%s"
                  (card-errata cards)))
  (assert (= (card-block-icons dcg.db.core/*cards)
             {"BT12" {1 #{"card/en_BT12-001_P1"}},
              "BT6" {nil #{"card/zh-Hans_BT6-018_P2"}},
              "EX1" {nil #{"card/en_EX1-073_P2"}}})
          (format "Card block icons may not be accurate:\n%s"
                  (card-block-icons cards)))
  cards)

(comment
  (->> dcg.db.core/*cards
       card-assertions)

  ;; Card values analysis
  (map (fn [[k v]]
         (let [issues (->> (partition 2 1
                                      (vals v))
                           (map #(apply data/diff %))
                           (remove (fn [[only-in-a only-in-b _in-both]]
                                     (and (nil? only-in-a)
                                          (nil? only-in-b))))
                           (map #(take 2 %)))
               issue-keys (vec (into #{} (mapcat (fn [x] (mapcat keys x)) issues)))
               incorrect (->> (vals v)
                              (map #(select-keys % issue-keys))
                              frequencies
                              (sort-by val)
                              ffirst)
               incorrect-fn (fn [[_ v]]
                              (= (select-keys v issue-keys)
                                 incorrect))]
           {k {:issue/keys issue-keys
               :issue/incorrect (->> (filter incorrect-fn v)
                                     (map (fn [[k v]]
                                            {k (select-keys v issue-keys)}))
                                     first)
               :issue/correct (->> (remove incorrect-fn v)
                                   (map (fn [[_ v]]
                                          (select-keys v issue-keys)))
                                   first)}}))
       (card-values dcg.db.core/*cards))

  )
