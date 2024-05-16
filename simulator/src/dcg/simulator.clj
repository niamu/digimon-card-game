(ns dcg.simulator
  (:require
   [clojure.spec.alpha :as s]
   [dcg.codec.common :as codec-common]
   [dcg.codec.decode :as codec-decode]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(s/def ::player
  (s/keys :req [::player/id
                ::player/name
                ::player/deck-code
                ::player/language
                ::player/memory
                ::player/areas]))

(s/def ::player/id uuid?)
(s/def ::player/name string?)
(s/def ::player/deck-code (fn [s]
                            (try (codec-decode/decode s)
                                 (catch Exception _ false))))
(s/def ::player/memory (s/int-in -10 11))
(s/def ::player/language (into #{} (keys codec-common/language->bits)))

(s/def ::game
  (s/keys :req [::game/id
                ::game/turn-counter
                ::game/log
                ::game/players
                ::game/effect-queue
                ::game/available-actions
                ::game/in]
          :opt [::game/constraint-code]))

(s/def ::game/id uuid?)
(s/def ::game/turn-counter (s/nilable pos-int?))
(s/def ::game/constraint-code (s/nilable (fn [s]
                                           (try (codec-decode/decode s)
                                                (catch Exception _ false)))))
(s/def ::game/log (s/coll-of ::action))
(s/def ::game/players (s/coll-of :dcg.simulator/player
                                 :distinct true
                                 :min-count 2))
(s/def ::game/effect-queue #(instance? clojure.lang.PersistentQueue %))
(s/def ::game/available-actions (s/coll-of ::action
                                           :distinct true
                                           :kind set?))
(s/def ::action (s/tuple ::game-in/state-id ::player/id any?))
(s/def ::game/in (s/keys :req [::game-in/turn
                               ::game-in/state-id]))

(s/def ::game-in/turn ::player/id)
(s/def ::game-in/state-id keyword?)

(s/def ::areas
  (s/keys :req [::area/digi-eggs
		::area/breeding
		::area/deck
		::area/trash
		::area/battle
		::area/security
		::area/hand]))

(s/def ::player/areas ::areas)

(s/def ::area/privacy #{:private
                        :owner
                        :public})

(s/def ::area/cards (s/or :card-coll (s/* ::card)
                          :card-count int?))

(s/def ::area/stacks (s/* ::stack))

(s/def ::area/digi-eggs (s/and (s/keys :req [::area/privacy
                                             ::area/cards])
                               (fn [{privacy ::area/privacy}]
                                 (= privacy :private))))

(s/def ::area/breeding (s/and (s/keys :req [::area/privacy
                                            ::area/stacks])
                              (fn [{privacy ::area/privacy}]
                                (= privacy :public))))

(s/def ::area/deck (s/and (s/keys :req [::area/privacy
                                        ::area/cards])
                          (fn [{privacy ::area/privacy}]
                            (= privacy :private))))

(s/def ::area/trash (s/and (s/keys :req [::area/privacy
                                         ::area/cards])
                           (fn [{privacy ::area/privacy}]
                             (= privacy :public))))

(s/def ::area/battle (s/and (s/keys :req [::area/privacy
                                          ::area/stacks])
                            (fn [{privacy ::area/privacy}]
                              (= privacy :public))))

(s/def ::area/security (s/and (fn [{cards ::area/cards
                                   privacy ::area/privacy}]
                                (= privacy :private))
                              (s/keys :req [::area/privacy
                                            ::area/cards])))

(s/def ::area/hand (s/and (s/keys :req [::area/privacy
                                        ::area/cards])
                          (fn [{privacy ::area/privacy}]
                            (= privacy :owner))))

(s/def ::stack (s/keys :req [::stack/uuid
                             ::stack/cards]
                       :opt [::stack/suspended?]))

(s/def ::stack/uuid uuid?)

(s/def ::stack/cards (s/* ::card))

(s/def ::stack/suspended? boolean?)

(s/def ::card (s/keys :req [::card/uuid
                            ::card/number
                            ::card/parallel-id
                            ::card/lookup]))
