(ns dcg.card.assertion
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [dcg.card.utils :as utils]))

(defn- highlights
  "Card highlights that differ across languages"
  [cards]
  (->> (mapcat :card/highlights cards)
       (partition-by (fn [{:highlight/keys [id]}]
                       (re-find utils/card-number-re id)))
       (map (fn [card-group]
              [(re-find utils/card-number-re
                        (get (first card-group) :highlight/id))
               (->> card-group
                    (partition-by (fn [{:highlight/keys [id]}]
                                    (re-find #"highlight/(.*?)_" id)))
                    (mapcat (juxt (comp (fn [{:highlight/keys [id]}]
                                          (re-find #"highlight/(.*?)_" id))
                                        first)
                                  (fn [highlights]
                                    (frequencies
                                     (map :highlight/type highlights)))))
                    (apply hash-map))
               (->> card-group
                    (partition-by (fn [{:highlight/keys [id]}]
                                    (re-find #"highlight/(.*?)_" id))))]))
       (reduce (fn [accl [number counts-by-lang card-group]]
                 (if (or (empty? counts-by-lang)
                         (apply = (vals counts-by-lang)))
                   accl
                   (conj accl [number counts-by-lang card-group])))
               [])))

(defn- mentions
  "Mentions in cards that differ across languages with no matches"
  [cards]
  (->> cards
       (filter (fn [{:card/keys [image language]}]
                 (= language
                    (:image/language image))))
       (mapcat :card/mentions)
       (group-by (fn [{:mention/keys [id]}]
                   (re-find utils/card-number-re id)))
       (reduce-kv (fn [m k v]
                    (let [result
                          (->> v
                               (partition-by
                                (fn [{:mention/keys [id]}]
                                  (re-find #"mention/(.*?)_" id)))
                               (mapcat (fn [group]
                                         [(->> (get (first group) :mention/id)
                                               (re-find #"mention/(.*?)_")
                                               second)
                                          (->> (map :mention/cards group)
                                               (apply concat)
                                               set
                                               count)]))
                               (apply hash-map))]
                      (assoc m
                             k
                             result)))
                  {})
       (remove (fn [[number result]]
                 (or (= (count (vals result)) 1)
                     (not (some zero? (vals result)))
                     (apply = (vals result)))))
       (sort-by first)))

(defn- text-fields
  [cards]
  "Card text fields that differ across languages"
  (->> cards
       (group-by :card/number)
       (reduce-kv (fn [accl number card-group]
                    (let [expected-keys
                          (->> card-group
                               (map (fn [card]
                                      (->> [:card/name
                                            :card/form
                                            :card/attribute
                                            :card/digimon-type
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
                                                 :card/play-cost]))
                   accl))
               {})
       (reduce (fn [accl [number counts-by-lang]]
                 (if (apply = counts-by-lang)
                   accl
                   (conj accl [number counts-by-lang])))
               [])))

(defn card-assertions
  [cards]
  (assert (empty? (highlights cards))
          "Card highlights differ across languages")
  (assert (empty? (mentions cards))
          "Mentions in cards differ across languages with no matches")
  (assert (empty? (text-fields cards))
          "Card text fields differ across languages")
  (assert (empty? (card-values cards))
          "JA card values differ across languages")
  cards)
