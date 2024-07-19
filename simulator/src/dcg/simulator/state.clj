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
                               (fn []
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
        card-lookup-fn (fn [{:card/keys [number parallel-id]}]
                         (get-in db [:card-uuids-by-language
                                     player-language
                                     [number parallel-id]]))
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
                                      (let [card (get-in db k)]
                                        {::card/uuid uuid
                                         ::card/card card})
                                      ::stack/uuid
                                      (let [{::stack/keys [cards]
                                             :as stack} (get-in db k)]
                                        (update stack
                                                ::stack/cards
                                                (fn [cards]
                                                  (mapv (fn [card]
                                                          {::card/uuid uuid
                                                           ::card/card
                                                           (get-in db card)})
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
        update-card (fn [[_ uuid :as lookup]]
                      (when (contains? visible-cards lookup)
                        (let [card (get-in db lookup)
                              actions (get actions-by-ident lookup)]
                          (cond-> {::card/uuid uuid
                                   ::card/card card}
                            actions
                            (assoc ::card/actions actions)))))
        update-cards (fn [cards]
                       (mapv update-card cards))
        update-stack (fn [[_ uuid :as lookup]]
                       (let [{::stack/keys [cards]
                              :as stack} (get-in db lookup)
                             actions (get actions-by-ident lookup)]
                         (cond-> (update stack ::stack/cards update-cards)
                           actions
                           (assoc ::stack/actions actions))))
        update-stacks (fn [stacks]
                        (mapv update-stack stacks))
        players-by-id
        (->> players
             (reduce (fn [accl {::player/keys [id] :as player}]
                       (assoc accl id
                              (update player ::player/areas
                                      (fn [areas]
                                        (reduce-kv
                                         (fn [accl k {::area/keys [cards stacks]
                                                     :as area}]
                                           (assoc accl k
                                                  (cond-> area
                                                    cards
                                                    (update ::area/cards
                                                            update-cards)
                                                    stacks
                                                    (update ::area/stacks
                                                            update-stacks))))
                                         {}
                                         areas)))))
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
        initial-digi-eggs (->> (decoded->deck digi-eggs)
                               (random/shuffle r)
                               (mapv (fn [{::card/keys [number parallel-id]
                                          :as card}]
                                       (assoc card
                                              ::card/uuid (random/uuid r)
                                              ::card/lookup
                                              [:card-uuids-by-language
                                               language
                                               [number parallel-id]]))))
        [initial-hand initial-deck]
        (->> (decoded->deck deck)
             (random/shuffle r)
             (mapv (fn [{::card/keys [number parallel-id]
                        :as card}]
                     (assoc card
                            ::card/uuid (random/uuid r)
                            ::card/lookup [:card-uuids-by-language
                                           language
                                           [number parallel-id]])))
             (split-at 5)
             (map #(into [] %)))
        cards (concat initial-digi-eggs
                      initial-hand
                      initial-deck)]
    (-> player
        (assoc ::player/id (or id (random/uuid r))
               ::player/language language
               ::player/memory 0
               ::player/areas {::area/digi-eggs
                               {::area/privacy :private
                                ::area/cards initial-digi-eggs}
                               ::area/deck
                               {::area/privacy :private
                                ::area/cards initial-deck}
                               ::area/breeding
                               {::area/privacy :public
                                ::area/stacks []}
                               ::area/trash
                               {::area/privacy :public
                                ::area/cards []}
                               ::area/battle
                               {::area/privacy :public
                                ::area/stacks []}
                               ::area/security
                               {::area/privacy :private
                                ::area/cards []}
                               ::area/hand
                               {::area/privacy :owner
                                ::area/cards initial-hand}}
               ::player/timings #{}))))

(defn initialize
  [players constraint-code]
  {:pre [(s/valid? (s/coll-of (s/keys :req [::player/name
                                            ::player/deck-code])
                              :distinct true
                              :min-count 2) players)
         (s/valid? ::game/constraint-code constraint-code)]
   :post [(s/valid? ::simulator/game %)]}
  (let [game-id (random-uuid)
        r (Random. (.getMostSignificantBits game-id))
        instant (Instant/now)
        players (->> players
                     (random/shuffle r)
                     (map-indexed
                      (fn [idx player]
                        (initialize-player r
                                           (assoc player
                                                  ::player/turn-index
                                                  idx))))
                     (into []))
        turn (-> players first ::player/id)
        cards (mapcat (comp (fn [areas]
                              (mapcat (fn [[_ {cards ::area/cards}]]
                                        cards)
                                      areas))
                            ::player/areas)
                      players)
        card-uuids-by-language (helpers/load-cards (map (juxt ::card/number
                                                              ::card/parallel-id)
                                                        cards)
                                                   (->> players
                                                        (map ::player/language)
                                                        (reduce conj #{"en"})))
        update-cards-fn (fn [cards]
                          (mapv (fn [{::card/keys [uuid]}]
                                  [::card/uuid uuid])
                                cards))
        update-stacks-fn (fn [stacks]
                           (mapv (fn [{::stack/keys [uuid] :as stack}]
                                   [::stack/uuid uuid])
                                 stacks))]
    {::game/random r
     ::game/id game-id
     ::game/instant instant
     ::game/turn-counter 0
     ::game/constraint-code constraint-code
     ::game/log [[:phase/pre-game [::game/id game-id] [constraint-code]]]
     ::game/pending-effects clojure.lang.PersistentQueue/EMPTY
     ::game/available-actions #{[:action/re-draw? [::player/id turn] true]
                                [:action/re-draw? [::player/id turn] false]}
     ::game/in {::game-in/turn turn
                ::game-in/state-id :phase/pre-game}
     ::game/players (mapv (fn [{::player/keys [id]}]
                            [::player/id id])
                          players)
     ::game/db (merge {:card-uuids-by-language card-uuids-by-language}
                      (reduce
                       (fn [accl {::player/keys [id areas] :as player}]
                         (-> accl
                             (assoc-in [::player/id id] player)
                             (assoc-in [::player/id id ::player/areas]
                                       (reduce-kv
                                        (fn [accl k {::area/keys [cards stacks]
                                                    :as area}]
                                          (assoc accl k
                                                 (cond-> area
                                                   cards
                                                   (update ::area/cards
                                                           update-cards-fn)
                                                   stacks
                                                   (update ::area/stacks
                                                           update-stacks-fn))))
                                        {}
                                        areas))))
                       {}
                       players)
                      (reduce (fn [accl {{::area/keys [battle breeding]}
                                        ::player/areas
                                        :as player}]
                                (merge accl
                                       (->> (concat breeding
                                                    battle)
                                            (mapv (fn [{::stack/keys [uuid]}]
                                                    (when uuid
                                                      [::stack/uuid uuid])))
                                            (remove nil?)
                                            (into {}))))
                              {}
                              players)
                      (reduce (fn [accl {::card/keys [uuid number parallel-id]}]
                                (assoc-in accl
                                          [::card/uuid uuid]
                                          (get-in card-uuids-by-language
                                                  ["en" [number parallel-id]])))
                              {}
                              cards))}))

(defn initialize-from-queue!
  []
  (when (> (count (get @state ::queue)) 1)
    (let [players (take 2 (get @state ::queue))
          game (initialize players nil)]
      (swap! state
             (fn [state]
               (-> state
                   (update ::queue (fn [q] (nth (iterate pop q) 2)))
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
   ;; Phases
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
        {::game/keys [available-actions in]
         :as game} (-> game
                       (handler action)
                       (as-> #__ game
                         (cond-> game
                           (not= (get-in game [::game/in ::game-in/state-id])
                                 :game/end)
                           (-> (update ::game/available-actions disj action)
                               (update ::game/log conj action)))))]
    (cond-> game
      (and (= (count available-actions) 1)
           (not= (last (first available-actions))
                 :require-input))
      (flow (first available-actions)))))
