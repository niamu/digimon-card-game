(ns dcg.db.deck
  (:require
   [clojure.string :as string]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging]))

(defn parse-deck
  [dom]
  (let [deck-name (-> (select/select
                       (select/and
                        (select/tag "img")
                        (select/attr :src
                                     (fn [s]
                                       (string/includes? s "/deck_"))))
                       dom)
                      first
                      (get-in [:attrs :alt]))
        cards-within (fn [dom-tree]
                       (->> dom-tree
                            (select/select (select/and (select/tag "td")
                                                       (select/class "tableList-txt")
                                                       (select/class "TxtCenter")))
                            (partition-all 2)
                            (mapv (fn [[number count]]
                                    {:card/number (-> number
                                                      :content
                                                      string/join)
                                     :card/count (-> count
                                                     :content
                                                     string/join
                                                     parse-long)}))))
        card-groups (map cards-within
                         (select/select (select/and (select/tag "table")
                                                    (select/class "tableList"))
                                        dom))
        key-cards (->> dom
                       (select/select (select/and (select/tag "dd")
                                                  (select/class "card")
                                                  (select/class "date")
                                                  (select/class "framecorner"))))
        icon (->> (or (->> key-cards
                           (filter (fn [{content :content}]
                                     (-> (filter string? content)
                                         string/join
                                         (string/includes? deck-name))))
                           first)
                      (first key-cards))
                  (select/select (select/class "num"))
                  first
                  :content
                  string/join)
        additional-cards (->> key-cards
                              (map (comp string/join
                                         :content
                                         first
                                         #(select/select (select/class "num")
                                                         %)))
                              (remove (fn [card-number]
                                        (contains? (->> card-groups
                                                        (apply concat)
                                                        (map :card/number)
                                                        set)
                                                   card-number))))]
    (cond-> {:deck/name deck-name
             :deck/digi-eggs (first card-groups)
             :deck/deck (->> card-groups
                             rest
                             (apply concat)
                             (into []))
             :deck/icon icon}
      (not (empty? additional-cards))
      (assoc :deck/sideboard
             (mapv (fn [card-number]
                     {:card/number card-number
                      :card/count 4})
                   additional-cards)))))

(defn decks-per-release
  [{:release/keys [product-uri name language card-image-language] :as release}]
  (let [decks (when (= language card-image-language)
                (some->> product-uri
                         str
                         utils/http-get
                         hickory/parse
                         hickory/as-hickory
                         (select/select
                          (select/and
                           (select/class "areaTitle")
                           (select/class "accordion")
                           (select/has-descendant
                            (select/and (select/tag "td")
                                        (select/class "tableList-txt")
                                        (select/class "TxtCenter")))))
                         (map (comp (fn [deck]
                                      (-> deck
                                          (assoc :deck/language
                                                 card-image-language)
                                          (update :deck/name
                                                  (fn [s]
                                                    (string/join " "
                                                                 [name s])))))
                                    parse-deck))))]
    (cond-> release
      (not (empty? decks))
      (assoc :release/decks decks))))

(defn decks-per-origin
  [releases-per-origin]
  (->> releases-per-origin
       (mapcat #(map decks-per-release %))
       (filter :release/decks)))
