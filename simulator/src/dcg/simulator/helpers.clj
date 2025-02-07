(ns dcg.simulator.helpers
  (:require
   [clojure.spec.alpha :as s]
   [dcg.db.db :as db]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(defn players-by-id
  [players]
  (reduce (fn [accl {::player/keys [id] :as player}]
            (assoc accl id player))
          {}
          players))

(defn card-lookup
  [cards-lookup {card-uuid ::card/uuid}]
  (get-in cards-lookup [card-uuid "en"]))

(defn stack-cards-lookup
  [cards-lookup cards]
  (map (fn [card]
         (assoc card
                ::card/card
                (card-lookup cards-lookup card)))
       cards))

(defn stacks-by-ident
  [players]
  (->> players
       (reduce (fn [accl {{{battle ::area/stacks} ::area/battle} ::player/areas}]
                 (merge accl
                        (->> battle
                             (map (fn [{stack-uuid ::stack/uuid :as stack}]
                                    [[::stack/uuid stack-uuid] stack]))
                             (into {}))))
               {})))

(defn load-cards
  [cards-by-uuid languages]
  (let [cards (db/q '{:find [[(pull ?c
                                    [:card/id
                                     :card/name
                                     :card/number
                                     :card/category
                                     :card/parallel-id
                                     :card/level
                                     :card/dp
                                     :card/play-cost
                                     :card/use-cost
                                     :card/language
                                     :card/form
                                     :card/attribute
                                     :card/type
                                     :card/rarity
                                     :card/block-icon
                                     :card/notes
                                     {:card/color [:color/index
                                                   :color/color]}
                                     {:card/digivolution-requirements
                                      [:digivolve/index
                                       :digivolve/level
                                       :digivolve/color
                                       :digivolve/cost]}
                                     {:card/releases [:release/name
                                                      :release/genre
                                                      :release/date]}
                                     {:card/image [:image/path]}
                                     {:card/highlights [:highlight/index
                                                        :highlight/field
                                                        :highlight/type
                                                        :highlight/text]}
                                     :card/effect
                                     :card/inherited-effect
                                     :card/security-effect]) ...]]
                      :in [$ [[?n ?p] ...] [?l ...]]
                      :where [[?c :card/number ?n]
                              [?c :card/parallel-id ?p]
                              [?c :card/language ?l]
                              [?c :card/image ?i]
                              [?i :image/language ?l]]}
                    (vals cards-by-uuid)
                    languages)
        cards-lookup
        (reduce (fn [accl {:card/keys [language number parallel-id] :as card}]
                  (assoc-in accl
                            [[number parallel-id] language]
                            card))
                {}
                cards)]
    cards
    (reduce-kv (fn [accl uuid [number parallel-id]]
                 (assoc accl uuid
                        (get cards-lookup [number parallel-id])))
               {}
               cards-by-uuid)))
