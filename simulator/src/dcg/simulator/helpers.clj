(ns dcg.simulator.helpers
  (:require
   [clojure.spec.alpha :as s]
   [dcg.db :as db]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack])
  (:import
   [java.util ArrayList Collection Collections Random]))

(defn shuffle-with-seed
  [seed ^Collection coll]
  (let [al (ArrayList. coll)
        rng (Random. (hash seed))]
    (Collections/shuffle al rng)
    (clojure.lang.RT/vector (.toArray al))))

(defn players-by-id
  [players]
  (reduce (fn [accl {::player/keys [id] :as player}]
            (assoc accl id player))
          {}
          players))

(defn load-cards
  [card-ids languages]
  (->> (db/q '{:find [[(pull ?c
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
                              {:card/digivolution-requirements [:digivolve/index
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
               :where [[?c :card/image ?i]
                       [?c :card/parallel-id ?p]
                       [?c :card/number ?n]
                       [?c :card/language ?l]
                       [?i :image/language ?l]]}
             card-ids
             languages)
       (reduce (fn [accl {:card/keys [language number parallel-id] :as card}]
                 (assoc-in accl
                           [language [number parallel-id]]
                           card))
               {})))
