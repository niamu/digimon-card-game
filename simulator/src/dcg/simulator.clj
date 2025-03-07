(ns dcg.simulator
  (:require
   [clojure.spec.alpha :as s]
   [dcg.codec.common :as codec-common]
   [dcg.codec.encode]
   [dcg.codec.decode :as codec-decode]
   [dcg.simulator.attack :as-alias attack]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.effect :as-alias effect]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack])
  (:import
   [java.util Random]))

(s/def ::player
  (s/keys :req [::player/id
                ::player/turn-index
                ::player/name
                ::player/deck-code
                ::player/language
                ::player/memory
                ::player/areas
                ::player/timings]))

(s/def ::player/id uuid?)
(s/def ::player/turn-index (s/or :zero zero?
                                 :pos-int pos-int?))
(s/def ::player/name string?)
(s/def ::player/deck-code (fn [s]
                            (try (codec-decode/decode s)
                                 (catch Exception _ false))))
(s/def ::player/memory (s/int-in -10 11))
(s/def ::player/language (into #{} (keys codec-common/language->bits)))
(s/def ::player/timings (s/coll-of keyword?
                                   :distinct true
                                   :kind set?))

(s/def ::effect
  (s/keys :req [::effect/origin
                ::effect/type
                ::effect/timings
                ::effect/preconditions
                ::effect/available-actions]
          :opt [::effect/targets
                ::effect/target-min
                ::effect/target-max]))

(s/def ::effect/origin (s/tuple ::player/id
                                ::card/uuid
                                #{:card/effect
                                  :card/inherited-effect
                                  :card/security-effect}
                                (s/or :zero zero?
                                      :pos-int pos-int?)))
(s/def ::effect/type #{:persistent
                       :trigger
                       :activation
                       :immediate})
(s/def ::effect/timings ::player/timings)
(s/def ::effect/preconditions (s/coll-of keyword?
                                         :distinct true
                                         :kind set?))
(s/def ::effect/targets (s/coll-of any?)) ;; TODO: narrow this spec
(s/def ::effect/target-min (s/or :zero zero?
                                 :pos-int pos-int?))
(s/def ::effect/target-max pos-int?)
(s/def ::game/available-actions (s/coll-of ::action
                                           :distinct true
                                           :kind set?))
(s/def ::effect/available-actions ::game/available-actions)

(s/def ::game
  (s/keys :req [::game/random
                ::game/id
                ::game/instant
                ::game/turn-counter
                ::game/log
                ::game/players
                ::game/pending-effects
                ::game/available-actions
                ::game/in
                ::game/cards-lookup]
          :opt [::game/constraint-code
                ::game/attack]))

(s/def ::game/cards-lookup (s/map-of uuid?
                                     (s/map-of string? map?)))

(s/def ::game/random #(instance? Random %))
(s/def ::game/id uuid?)
(s/def ::game/instant inst?)
(s/def ::game/turn-counter (s/or :zero zero?
                                 :pos-int pos-int?))
(s/def ::game/constraint-code (s/nilable (fn [s]
                                           (try (codec-decode/decode s)
                                                (catch Exception _ false)))))
(s/def ::game/log (s/coll-of ::action))
(s/def ::game/players (s/coll-of ::player
                                 :distinct true
                                 :min-count 2))
(s/def ::game/active-effects (s/map-of ::effect pos-int?))
(s/def ::game/pending-effects #(instance? clojure.lang.PersistentQueue %))

(s/def ::action-ident (s/tuple keyword? uuid?))
(s/def ::action (s/tuple ::game-in/state-id
                         ::action-ident
                         any?))
(s/def ::game/in (s/keys :req [::game-in/turn-index
                               ::game-in/state-id]))

(s/def ::game-in/turn-index (s/or :zero zero?
                                  :pos-int pos-int?))
(s/def ::game-in/state-id keyword?)

(s/def ::game/attack (s/keys :req [::attack/attacker
                                   ::attack/attacking
                                   ::attack/player]))

(s/def ::attack/attacker (s/tuple keyword? uuid?))
(s/def ::attack/attacking (s/tuple keyword? uuid?))
(s/def ::attack/player (s/tuple keyword? uuid?))
(s/def ::attack/state #{:declare
                        :counter
                        :block
                        :security-check
                        :battle
                        :end})

(s/def ::areas
  (s/keys :req [::area/digi-eggs
	        ::area/breeding
	        ::area/deck
	        ::area/trash
	        ::area/battle
	        ::area/security
	        ::area/hand]))

(s/def ::player/areas ::areas)

(s/def ::area/name keyword?)

(s/def ::area/privacy #{:private
                        :owner
                        :public})

(s/def ::area/cards (s/coll-of ::card
                               :distinct true))

(s/def ::area/stacks (s/* ::stack))

(s/def ::area/digi-eggs (s/and (s/keys :req [::area/name
                                             ::area/privacy
                                             ::area/cards])
                               (fn [{privacy ::area/privacy}]
                                 (= privacy :private))))

(s/def ::area/breeding (s/and (s/keys :req [::area/name
                                            ::area/privacy
                                            ::area/stacks])
                              (fn [{privacy ::area/privacy}]
                                (= privacy :public))))

(s/def ::area/deck (s/and (s/keys :req [::area/name
                                        ::area/privacy
                                        ::area/cards])
                          (fn [{privacy ::area/privacy}]
                            (= privacy :private))))

(s/def ::area/trash (s/and (s/keys :req [::area/name
                                         ::area/privacy
                                         ::area/cards])
                           (fn [{privacy ::area/privacy}]
                             (= privacy :public))))

(s/def ::area/battle (s/and (s/keys :req [::area/name
                                          ::area/privacy
                                          ::area/stacks])
                            (fn [{privacy ::area/privacy}]
                              (= privacy :public))))

(s/def ::area/security (s/and (fn [{cards ::area/cards
                                   privacy ::area/privacy}]
                                (= privacy :private))
                              (s/keys :req [::area/name
                                            ::area/privacy
                                            ::area/cards])))

(s/def ::area/hand (s/and (s/keys :req [::area/name
                                        ::area/privacy
                                        ::area/cards])
                          (fn [{privacy ::area/privacy}]
                            (= privacy :owner))))

(s/def ::stack (s/keys :req [::stack/uuid
                             ::stack/cards
                             ::stack/suspended?
                             ::stack/summoned?]))

(s/def ::stack/uuid uuid?)

(s/def ::stack/cards (s/coll-of ::card
                                :distinct true))

(s/def ::stack/suspended? boolean?)
(s/def ::stack/summoned? boolean?)

(s/def ::card
  (s/nilable (s/keys :req [::card/uuid]
                     :opt [::card/privacy])))

(s/def ::card/uuid uuid?)
(s/def ::card/privacy ::area/privacy)

(comment
  ;; Encode pack order into :deck/name
  (let [deck {:deck/name "#b[S0GQ 20210606 190.45]BT01-03 v1.0: Booster Pack"
              :deck/digi-eggs []
              :deck/deck [{:card/number "BT1-019" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-038" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-032" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-113" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-030" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT2-018" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-046" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-004" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-058" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT1-035" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT2-030" :card/parallel-id 0 :card/count 1}
                          {:card/number "BT2-020" :card/parallel-id 0 :card/count 1}]}
        pack-order (->> deck
                        :deck/deck
                        (map-indexed (fn [idx card]
                                       (assoc card :card/index idx)))
                        (sort-by (juxt :card/number :card/parallel-id))
                        (map :card/index)
                        (clojure.string/join " "))]
    (-> (assoc deck
               :deck/name
               (format "#pack[%s]" pack-order))
        dcg.codec.encode/encode
        dcg.codec.decode/decode)))
