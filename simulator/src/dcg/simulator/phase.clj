(ns dcg.simulator.phase
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(defn unsuspend
  [{::game/keys [log players] {::game-in/keys [turn]} ::game/in
    :as game} [state-id player-id _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      ;; Only update the turn counter if we are on player 1
      (cond-> #_game
        (= turn (get (first players) ::player/id))
        (update ::game/turn-counter (fnil inc 0)))
      (update ::game/players
              (fn [players]
                (mapv (fn [{::player/keys [id] :as player}]
                        (if (= id turn)
                          (-> player
                              (update-in [::player/areas
                                          ::area/battle
                                          ::area/stacks]
                                         (fn [stacks]
                                           (mapv (fn [stack]
                                                   (assoc stack
                                                          ::stack/suspended?
                                                          false))
                                                 stacks))))
                          player))
                      players)))
      (update ::game/available-actions
              conj
              [:phase/draw turn nil])
      (assoc-in [::game/in ::game-in/state-id]
                :phase/draw)))

(defn draw
  [{::game/keys [turn-counter players] {::game-in/keys [turn]} ::game/in
    :as game} [state-id player-id _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game game)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))
        next-player-id (get-in players [next-turn-idx ::player/id])
        deck-has-cards? (pos? (count (get-in players
                                             [turn-idx
                                              ::player/areas
                                              ::area/deck
                                              ::area/cards])))]
    (cond-> game
      (and (not (and (= turn-counter 1)
                     (= turn (-> players first ::player/id))))
           deck-has-cards?)
      (update ::game/players
              (fn [players]
                (mapv (fn [{::player/keys [id] :as player}]
                        (cond-> player
                          (= id turn)
                          (update-in [::player/areas]
                                     (fn [{::area/keys [hand deck] :as areas}]
                                       (-> areas
                                           (update-in [::area/deck
                                                       ::area/cards]
                                                      (comp #(into [] %)
                                                            rest))
                                           (update-in [::area/hand
                                                       ::area/cards]
                                                      conj
                                                      (->> deck
                                                           ::area/cards
                                                           first)))))))
                      players)))
      deck-has-cards?
      (-> (assoc ::game/available-actions
                 #{[:phase/breeding turn nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/breeding))
      (not deck-has-cards?)
      (-> (assoc ::game/available-actions
                 #{})
          (assoc-in [::game/in ::game-in/state-id]
                    :game/end)
          (update-in [::game/log]
                     conj
                     [:game/end turn next-player-id])))))

(defn breeding
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [state-id player-id _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))]
    (assoc game
           ::game/available-actions
           (cond-> #{[:phase/main turn nil]}
             (and (empty? (get-in (helpers/players-by-id players)
                                  [turn
                                   ::player/areas
                                   ::area/breeding
                                   ::area/stacks]))
                  (seq (get-in (helpers/players-by-id players)
                               [turn
                                ::player/areas
                                ::area/digi-eggs
                                ::area/cards])))
             (conj [:action/hatch turn nil])
             (when-let [stacks (get-in (helpers/players-by-id players)
                                       [turn
                                        ::player/areas
                                        ::area/breeding
                                        ::area/stacks])]
               (some->> stacks first ::stack/cards first ::card/lookup
                        (get-in game)
                        :card/dp))
             (conj [:action/move turn nil])))))

(defn main
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        {::player/keys [memory]
         {{cards-in-hand ::area/cards} ::area/hand
          {breeding ::area/stacks} ::area/breeding
          {battle ::area/stacks} ::area/battle} ::player/areas
         :as player} (get-in game [::game/players turn-idx])
        available-memory (+ memory 10)
        playable-cards
        (->> cards-in-hand
             (filter (fn [{::card/keys [lookup uuid]}]
                       (let [{:card/keys [play-cost]} (get-in game lookup)]
                         (when play-cost
                           (<= play-cost available-memory)))))
             (map (fn [{::card/keys [uuid]}]
                    [:action/play turn [uuid]])))
        usable-cards
        (->> cards-in-hand
             (filter (fn [{::card/keys [lookup uuid]}]
                       (let [{:card/keys [use-cost]} (get-in game lookup)]
                         (when use-cost
                           (<= use-cost available-memory)))))
             (map (fn [{::card/keys [uuid]}]
                    [:action/use turn [uuid]])))
        digivolve-actions
        (let [the-area (->> (concat breeding battle)
                            (map (fn [{::stack/keys [cards]}]
                                   (-> (first cards)
                                       (update ::card/lookup
                                               (fn [lookup]
                                                 (get-in game lookup)))))))]
          (->> cards-in-hand
               (reduce (fn [accl {::card/keys [lookup uuid]}]
                         (let [{:card/keys [digivolution-requirements]}
                               (get-in game lookup)
                               digivolvable
                               (mapcat (fn [req]
                                         (->> the-area
                                              (filter
                                               (fn [{{:card/keys [color level]}
                                                    ::card/lookup
                                                    :as area-card}]
                                                 (and (<= (get req
                                                               :digivolve/cost)
                                                          available-memory)
                                                      (= level
                                                         (get req
                                                              :digivolve/level))
                                                      (seq (set/intersection
                                                            (->> color
                                                                 (map :color/color)
                                                                 (into #{}))
                                                            (->> (get req
                                                                      :digivolve/color)
                                                                 (into #{})))))))
                                              (map (fn [card]
                                                     [:action/digivolve turn
                                                      [uuid
                                                       (get req :digivolve/index)
                                                       (get card ::card/uuid)]]))))
                                       digivolution-requirements)]
                           (if (seq digivolvable)
                             (apply conj accl digivolvable)
                             accl)))
                       [])))]
    (-> game
        (assoc ::game/available-actions
               (if (< memory 0)
                 #{[:phase/end-turn turn nil]}
                 (cond-> #{[:action/pass turn nil]}
                   (seq playable-cards)
                   (as-> #__ available-actions
                     (apply conj available-actions playable-cards))
                   (seq usable-cards)
                   (as-> #__ available-actions
                     (apply conj available-actions usable-cards))
                   (seq digivolve-actions)
                   (as-> #__ available-actions
                     (apply conj available-actions digivolve-actions))
                   ;; TODO
                   ;; - Attack
                   ;; - Activate a [Main] timing effect
                   )))
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn end-of-turn
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [next-turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                    (assoc accl id (count accl)))
                                  {}
                                  players)
                          (get turn)
                          inc
                          (mod (count players)))
        next-player (get-in game [::game/players next-turn-idx])
        next-player-id (::player/id next-player)]
    (-> game
        (assoc ::game/available-actions
               #{[:phase/unsuspend next-player-id nil]})
        (assoc-in [::game/in ::game-in/turn]
                  next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/end-turn))))
