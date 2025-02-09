(ns dcg.simulator.state
  (:require
   [clojure.spec.alpha :as s]
   [dcg.codec.decode :as codec-decode]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.action :as action]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.phase :as phase]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.random :as random])
  (:import
   [java.time Instant]
   [java.util Random]))

(defonce state
  (atom {::queue clojure.lang.PersistentQueue/EMPTY
         ::games-by-id {}
         ::game-by-player-id {}}))

(defn player-in-queue?
  [{::player/keys [id] :as player}]
  (contains? (->> @state
                  ::queue
                  (map ::player/id)
                  (into #{}))
             id))

(defn player-in-game?
  [{::player/keys [id] :as player}]
  (when-let [game-id (get-in @state [::game-by-player-id id])]
    (let [game-state (get-in @state [::games-by-id
                                     game-id
                                     ::game/in
                                     ::game-in/state-id])]
      (when (not= :game/end game-state)
        game-id))))

(defn- decoded->deck
  [cards]
  (reduce (fn [accl {:card/keys [number parallel-id count]
                    :or {parallel-id 0}}]
            (apply conj accl
                   (repeatedly count
                               (constantly
                                {::card/number number
                                 ::card/parallel-id parallel-id}))))
          []
          cards))

(defn initialize-player
  [^Random r {::player/keys [deck-code id] :as player}]
  (let [{:deck/keys [digi-eggs deck language]
         :or {language "en"}} (codec-decode/decode deck-code)
        assign-uuid-to-card (fn [card] (assoc card ::card/uuid (random/uuid r)))
        digi-eggs (->> (decoded->deck digi-eggs)
                       (map assign-uuid-to-card))
        deck (->> (decoded->deck deck)
                  (map assign-uuid-to-card))
        cards-by-uuid (reduce (fn [accl {::card/keys [uuid number parallel-id]}]
                                (assoc accl
                                       uuid
                                       [number parallel-id]))
                              {}
                              (concat digi-eggs deck))
        update-card (fn [card] (select-keys card [::card/uuid]))
        initial-digi-eggs (->> digi-eggs
                               (map update-card)
                               (random/shuffle r))
        [initial-hand initial-deck] (->> deck
                                         (map update-card)
                                         (random/shuffle r)
                                         (split-at 5))
        player-id (if id
                    ;; Generate UUID and discard to keep replays in sync for
                    ;; player-ids that are provided ahead of time
                    (do (random/uuid r)
                        id)
                    (random/uuid r))]
    (-> player
        (assoc ::player/id player-id
               ::player/turn-index 0
               ::player/language language
               ::player/memory 0
               ::player/areas {::area/digi-eggs
                               {::area/name ::area/digi-eggs
                                ::area/privacy :private
                                ::area/cards initial-digi-eggs}
                               ::area/deck
                               {::area/name ::area/deck
                                ::area/privacy :private
                                ::area/cards initial-deck}
                               ::area/breeding
                               {::area/name ::area/breeding
                                ::area/privacy :public
                                ::area/stacks []}
                               ::area/trash
                               {::area/name ::area/trash
                                ::area/privacy :public
                                ::area/cards []}
                               ::area/battle
                               {::area/name ::area/battle
                                ::area/privacy :public
                                ::area/stacks []}
                               ::area/security
                               {::area/name ::area/security
                                ::area/privacy :private
                                ::area/cards []}
                               ::area/hand
                               {::area/name ::area/hand
                                ::area/privacy :owner
                                ::area/cards initial-hand}}
               ::player/timings #{}
               ::player/cards-by-uuid cards-by-uuid))))

(defn initialize-game
  ([players constraint-code]
   (initialize-game players constraint-code (random-uuid)))
  ([players constraint-code game-id]
   {:pre [(s/valid? (s/coll-of (s/keys :req [::player/name
                                             ::player/deck-code])
                               :distinct true
                               :min-count 2) players)
          (s/valid? ::game/constraint-code constraint-code)]
    :post [(s/valid? ::simulator/game %)]}
   (let [r (Random. (.getMostSignificantBits game-id))
         players (->> players
                      (random/shuffle r)
                      (mapv (fn [player]
                              (initialize-player r player))))
         turn-index 0
         player-id (::player/id (nth players turn-index))
         cards-by-uuid (->> players
                            (map ::player/cards-by-uuid)
                            (apply merge))
         card-languages (conj (->> players
                                   (map ::player/language)
                                   (into #{}))
                              "en")
         cards-lookup (helpers/load-cards cards-by-uuid
                                          card-languages)
         available-actions (->> players
                                (mapcat (fn [{::player/keys [id]}]
                                          [[:action/re-draw? [::player/id id] true]
                                           [:action/re-draw? [::player/id id] false]]))
                                set)
         instant (Instant/now)]
     {::game/random r
      ::game/id game-id
      ::game/instant instant
      ::game/turn-counter 0
      ::game/constraint-code constraint-code
      ::game/log [[:phase/pre-game [::game/id game-id]
                   (-> (mapv (fn [{::player/keys [id]}]
                               [::player/id id])
                             players)
                       (conj instant constraint-code))]]
      ::game/pending-effects clojure.lang.PersistentQueue/EMPTY
      ::game/available-actions available-actions
      ::game/in {::game-in/turn-index turn-index
                 ::game-in/state-id :phase/pre-game}
      ::game/players (mapv (fn [player]
                             (dissoc player ::player/cards-by-uuid))
                           players)
      ::game/cards-lookup cards-lookup})))

(defn initialize-from-queue!
  []
  (swap! state
         (fn [{queue ::queue :as state}]
           (if (< (count queue) 2)
             state
             (let [players (take 2 queue)
                   game (initialize-game players nil)]
               (-> state
                   (update ::queue (fn [q] (-> (iterate pop q) (nth 2))))
                   (assoc-in [::games-by-id (::game/id game)] game)
                   (update ::game-by-player-id merge
                           (reduce (fn [accl {::player/keys [id]}]
                                     (assoc accl id (::game/id game)))
                                   {}
                                   players))))))))

(def fsm
  {;; Actions
   :action/re-draw? #'action/re-draw?
   :action/hatch #'action/hatch
   :action/move #'action/move
   :action/use #'action/use
   :action/play #'action/play
   :action/digivolve #'action/digivolve
   :action/attack.declare #'action/declare-attack
   :action/attack.counter #'action/counter-attack
   :action/attack.block #'action/block-attack
   :action/attack.digimon #'action/digimon-attack
   :action/attack.security #'action/security-attack
   :action/pass #'action/pass
   :action/update-memory #'action/update-memory
   :action/effect #'action/effect
   ;; Phases
   :phase/start-turn #'phase/start-of-turn
   :phase/unsuspend #'phase/unsuspend
   :phase/draw #'phase/draw
   :phase/breeding #'phase/breeding
   :phase/main #'phase/main
   :phase/end-turn #'phase/end-of-turn})

(defn flow
  [{::game/keys [available-actions log players]
    :as game} [action-state-id _ _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (when-not (contains? available-actions action)
    (throw (Exception. (str "Invalid action: " action))))
  (let [handler (get-in fsm [action-state-id])
        {::game/keys [available-actions]
         :as game} (as-> (handler game action) game
                     (cond-> game
                       (not= (get-in game [::game/in ::game-in/state-id])
                             :game/end)
                       (-> (update ::game/available-actions disj action)
                           (update ::game/log conj action))))]
    (cond-> game
      (and (= (count available-actions) 1)
           (not= (last (first available-actions))
                 :require-input))
      (flow (first available-actions)))))

(comment
  (let [game (-> (initialize-game [{::player/id #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"
                                    ::player/name "niamu"
                                    ::player/deck-code "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                                   {::player/id #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"
                                    ::player/name "AI"
                                    ::player/deck-code "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}]
                                  nil
                                  #uuid "e2ae2331-7922-40ea-8a19-bcb3d5c1d960")
                 (flow [:action/re-draw?
                        [:dcg.simulator.player/id
                         #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"]
                        false])
                 (flow [:action/re-draw?
                        [:dcg.simulator.player/id
                         #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                        true])
                 (flow [:action/hatch
                        [:dcg.simulator.player/id
                         #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                        nil])
                 (flow [:action/digivolve
                        [:dcg.simulator.player/id
                         #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                        [[:dcg.simulator.card/uuid
                          #uuid "4db63699-447c-1570-3327-cdb7231a89ce"]
                         0
                         [:dcg.simulator.stack/uuid
                          #uuid "35cbb895-de70-99f0-565b-de0403e3c87e"]]])
                 (flow [:action/digivolve
                        [:dcg.simulator.player/id
                         #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                        [[:dcg.simulator.card/uuid
                          #uuid "d4eed8c9-03a1-cc06-d9c9-d22b6e9bc792"]
                         0
                         [:dcg.simulator.stack/uuid
                          #uuid "35cbb895-de70-99f0-565b-de0403e3c87e"]]])
                 (flow [:action/hatch
                        [:dcg.simulator.player/id
                         #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"]
                        nil])
                 (flow [:action/digivolve
                        [:dcg.simulator.player/id
                         #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"]
                        [[:dcg.simulator.card/uuid
                          #uuid "b990360c-2de6-aeba-cc8a-7b3b42ec3de6"]
                         0
                         [:dcg.simulator.stack/uuid
                          #uuid "e5ba51aa-f54d-907b-9862-2bfc6a80eea5"]]])
                 (flow [:action/digivolve
                        [:dcg.simulator.player/id
                         #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"]
                        [[:dcg.simulator.card/uuid
                          #uuid "ffc76a31-581d-966f-5abc-28005d4b0109"]
                         0
                         [:dcg.simulator.stack/uuid
                          #uuid "e5ba51aa-f54d-907b-9862-2bfc6a80eea5"]]])
                 (flow [:action/play
                        [:dcg.simulator.player/id
                         #uuid "d7838557-f640-4380-9746-b8090c6ecfb2"]
                        [:dcg.simulator.card/uuid
                         #uuid "eea93772-71d2-96ca-4874-2b5175b85ace"]])
                 (flow [:action/move
                        [:dcg.simulator.player/id
                         #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                        [:dcg.simulator.stack/uuid
                         #uuid "35cbb895-de70-99f0-565b-de0403e3c87e"]])
                 #_(flow [:action/use
                          [:dcg.simulator.player/id
                           #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                          [:dcg.simulator.card/uuid
                           #uuid "d317abf8-d015-aff0-406a-28661daab9a0"]])
                 #_(flow [:action/use
                          [:dcg.simulator.player/id
                           #uuid "a3347b2b-8016-4c36-b170-4e77cc7b33f3"]
                          [:dcg.simulator.card/uuid
                           #uuid "30a28c4f-b2a0-5f10-bc9d-3714a7939037"]]))]

    #_(swap! state assoc-in
             [::games-by-id #uuid "e2ae2331-7922-40ea-8a19-bcb3d5c1d960"]
             game)

    (-> game
        ::game/log
        last)

    (get-in game [::game/players 0 ::player/memory]))

  (->> (get-in @state
               [::games-by-id #uuid "e2ae2331-7922-40ea-8a19-bcb3d5c1d960"
                ::game/log])
       last)

  )
