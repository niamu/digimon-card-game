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

(defn private-state-for-player-id
  [{::game/keys [available-actions players db]
    {::game-in/keys [turn]} ::game/in
    :as game} player-id]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::player/id player-id)]}
  (let [players (map #(get-in db %) players)
        player-language (get-in (helpers/players-by-id players)
                                [player-id ::player/language]
                                "en")
        available-actions (reduce (fn [accl [_ [_ id] _ :as action]]
                                    (cond-> accl
                                      (= id player-id)
                                      (conj action)))
                                  #{}
                                  available-actions)
        visible-cards
        (reduce (fn [accl {::player/keys [id areas]}]
                  (->> areas
                       (mapcat (fn [[_ {::area/keys [privacy cards stacks]}]]
                                 (->> (or cards
                                          (mapcat (fn [stack]
                                                    (-> (get-in db stack)
                                                        ::stack/cards))
                                                  stacks))
                                      (filter (fn [card]
                                                (let [p (get card
                                                             ::card/privacy
                                                             privacy)]
                                                  (or (= p :public)
                                                      (and (= id player-id)
                                                           (= p :owner)))))))))
                       (concat accl)
                       (into #{})))
                #{}
                players)
        actions-by-ident
        (reduce (fn [accl [_ _ params :as action]]
                  (let [expanded-params
                        (when (vector? params)
                          (mapv (fn [k]
                                  (if-let [[t uuid] (and (vector? k) k)]
                                    (case t
                                      ::card/uuid
                                      (get-in db k)
                                      ::stack/uuid
                                      (let [{::stack/keys [cards]
                                             :as stack} (get-in db k)]
                                        (update stack
                                                ::stack/cards
                                                (fn [cards]
                                                  (mapv (fn [card]
                                                          (get-in db card))
                                                        cards))))
                                      ::player/id
                                      (get-in db k))
                                    k))
                                params))]
                    (cond-> accl
                      (vector? params)
                      (update (first params) conj
                              {:action/action action
                               :action/params expanded-params}))))
                {}
                available-actions)
        players-by-id
        (->> players
             (reduce (fn [accl {::player/keys [id] :as player}]
                       (assoc accl id player))
                     {}))]
    (-> game
        (dissoc ::game/random
                ::game/instant
                ::game/db)
        (update ::game/players (fn [players]
                                 (mapv (fn [[_ id]]
                                         (players-by-id id))
                                       players)))
        (assoc ::game/available-actions available-actions))))

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
        player-id (or id (random/uuid r))]
    (-> player
        (assoc ::player/id player-id
               ::player/turn-index 0
               ::player/language language
               ::player/memory 0
               ::player/areas {::area/digi-eggs
                               {::area/name ::area/digi-eggs
                                ::area/of-player player-id
                                ::area/privacy :private
                                ::area/cards initial-digi-eggs}
                               ::area/deck
                               {::area/name ::area/deck
                                ::area/of-player player-id
                                ::area/privacy :private
                                ::area/cards initial-deck}
                               ::area/breeding
                               {::area/name ::area/breeding
                                ::area/of-player player-id
                                ::area/privacy :public
                                ::area/stacks []}
                               ::area/trash
                               {::area/name ::area/trash
                                ::area/of-player player-id
                                ::area/privacy :public
                                ::area/cards []}
                               ::area/battle
                               {::area/name ::area/battle
                                ::area/of-player player-id
                                ::area/privacy :public
                                ::area/stacks []}
                               ::area/security
                               {::area/name ::area/security
                                ::area/of-player player-id
                                ::area/privacy :private
                                ::area/cards []}
                               ::area/hand
                               {::area/name ::area/hand
                                ::area/of-player player-id
                                ::area/privacy :owner
                                ::area/cards initial-hand}}
               ::player/timings #{}
               ::player/cards-by-uuid cards-by-uuid))))

(defn initialize-game
  [players constraint-code]
  {:pre [(s/valid? (s/coll-of (s/keys :req [::player/name
                                            ::player/deck-code])
                              :distinct true
                              :min-count 2) players)
         (s/valid? ::game/constraint-code constraint-code)]
   :post [(s/valid? ::simulator/game %)]}
  (let [game-id (random-uuid)
        r (Random. (.getMostSignificantBits game-id))
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
                               set)]
    {::game/random r
     ::game/id game-id
     ::game/instant (Instant/now)
     ::game/turn-counter 0
     ::game/constraint-code constraint-code
     ::game/log [[:phase/pre-game [::game/id game-id] [constraint-code]]]
     ::game/pending-effects clojure.lang.PersistentQueue/EMPTY
     ::game/available-actions available-actions
     ::game/in {::game-in/turn-index turn-index
                ::game-in/state-id :phase/pre-game}
     ::game/players (mapv (fn [player]
                            (dissoc player ::player/cards-by-uuid))
                          players)
     ::game/cards-lookup cards-lookup}))

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
   :action/use #'action/play
   :action/play #'action/play
   :action/digivolve #'action/digivolve
   :action/attack.declare #'action/declare-attack
   :action/attack.counter #'action/counter-attack
   :action/attack.block #'action/block-attack
   :action/attack.digimon #'action/digimon-attack
   :action/attack.security #'action/security-attack
   :action/pass #'action/pass
   :action/update-memory #'action/update-memory
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
  (let [game-db (get-in @state [::games-by-id
                                #uuid "19ac6500-1740-4406-960b-6b36fe1ed459"
                                ::game/db])
        stack-paths (get-in game-db [::player/id
                                     #uuid "8427e599-51d6-4d7e-883c-d625cad0962a"
                                     ::player/areas
                                     ::area/battle
                                     ::area/stacks])
        stacks (->> stack-paths
                    (map (fn [path]
                           (-> (get-in game-db path)
                               (update ::stack/cards
                                       (fn [cards]
                                         (->> cards
                                              (map #(get-in game-db %)))))))))]
    stacks)

  (initialize-game [{::player/id (random-uuid)
                     ::player/name "niamu"
                     ::player/deck-code "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                    {::player/id (random-uuid)
                     ::player/name "AI"
                     ::player/deck-code "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}]
                   nil)

  (get-in @state
          [::games-by-id #uuid "4970cb51-99a5-47d1-b9ce-15942b6efcc5"])

  )
