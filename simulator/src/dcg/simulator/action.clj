(ns dcg.simulator.action
  (:require
   [clojure.spec.alpha :as s]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(defn set-memory
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in :as game} memory]
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))]
    (-> game
        (assoc-in [::game/players turn-idx ::player/memory]
                  memory)
        (assoc-in [::game/players next-turn-idx ::player/memory]
                  (* memory -1)))))

(defn update-memory
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in :as game} op value]
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))
        memory (-> (get-in game [::game/players turn-idx ::player/memory])
                   (op value))]
    (-> game
        (assoc-in [::game/players turn-idx ::player/memory]
                  memory)
        (assoc-in [::game/players next-turn-idx ::player/memory]
                  (* memory -1)))))

(defn re-draw?
  [{::game/keys [seed log players]
    {::game-in/keys [turn]} ::game/in
    :as game}
   [_ player-id re-draw? :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))
        next-player-id (get-in game [::game/players next-turn-idx ::player/id])
        {::game/keys [log players]
         {::game-in/keys [turn]} ::game/in
         :as game}
        (-> game
            (update ::game/players
                    (fn [players]
                      (mapv (fn [{::player/keys [id] :as player}]
                              (cond-> player
                                (= id player-id)
                                (update ::player/areas
                                        (fn [{::area/keys [deck hand]
                                             :as areas}]
                                          (let [[new-hand new-deck]
                                                (->> (concat
                                                      (::area/cards deck)
                                                      (::area/cards hand))
                                                     (helpers/shuffle-with-seed seed)
                                                     (split-at 5)
                                                     (map #(into [] %)))
                                                [security new-deck]
                                                (->> (if re-draw?
                                                       new-deck
                                                       (::area/cards deck))
                                                     (split-at 5)
                                                     (map #(into [] %)))
                                                security (->> security
                                                              reverse)]
                                            (cond-> (-> areas
                                                        (assoc-in
                                                         [::area/deck
                                                          ::area/cards]
                                                         new-deck)
                                                        (update-in
                                                         [::area/security
                                                          ::area/cards]
                                                         (fn [q]
                                                           (->> security
                                                                (reduce conj
                                                                        q)))))
                                              re-draw?
                                              (assoc-in [::area/hand
                                                         ::area/cards]
                                                        new-hand)))))))
                            players)))
            (assoc ::game/available-actions
                   (let [already-drawn (->> log
                                            (filter (fn [[state-id _ _]]
                                                      (= :action/re-draw?
                                                         state-id)))
                                            (map second)
                                            (into #{}))]
                     (cond-> #{}
                       (not (contains? already-drawn next-player-id))
                       (conj [:action/re-draw? next-player-id true]
                             [:action/re-draw? next-player-id false])))))]
    (cond-> game
      (= (conj (->> log
                    (filter (fn [[state-id _ _]]
                              (= state-id :action/re-draw?)))
                    (map second)
                    (into #{}))
               player-id)
         (->> players
              (map ::player/id)
              (into #{})))
      (-> (assoc ::game/available-actions
                 #{[:phase/unsuspend turn nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/unsuspend)))))

(defn hatch
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [state-id player-id hatch-or-move :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))]
    (-> game
        (update-in [::game/players turn-idx ::player/areas]
                   (fn [areas]
                     (-> areas
                         (update-in [::area/digi-eggs
                                     ::area/cards]
                                    (comp #(into [] %)
                                          rest))
                         (update-in [::area/breeding
                                     ::area/stacks]
                                    conj
                                    {::stack/uuid (random-uuid)
                                     ::stack/cards
                                     (->> (get-in areas
                                                  [::area/digi-eggs
                                                   ::area/cards])
                                          (take 1)
                                          (into []))}))))
        (assoc ::game/available-actions
               #{[:phase/main turn nil]})
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn move
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
    (-> game
        (update-in [::game/players turn-idx ::player/areas]
                   (fn [{::area/keys [breeding] :as areas}]
                     (-> areas
                         (update-in [::area/battle
                                     ::area/stacks]
                                    (comp #(into [] %) concat)
                                    (get-in breeding
                                            [::area/stacks]))
                         (assoc-in [::area/breeding
                                    ::area/stacks]
                                   []))))
        (assoc ::game/available-actions
               #{[:phase/main turn nil]})
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn play
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [card-uuid & [override]] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))]
    (let [card (->> (get-in game
                            [::game/players turn-idx ::player/areas
                             ::area/hand ::area/cards])
                    (filter (fn [{::card/keys [uuid]}]
                              (= uuid card-uuid)))
                    first)
          {:card/keys [play-cost use-cost]} (get-in game (::card/lookup card))]
      (-> game
          (update-memory - (or (:cost override)
                               play-cost use-cost))
          (update-in [::game/players turn-idx ::player/areas]
                     (fn [{::area/keys [hand battle] :as areas}]
                       (-> areas
                           (update-in [::area/hand ::area/cards]
                                      (fn [cards]
                                        (->> cards
                                             (remove (fn [{::card/keys [uuid]}]
                                                       (= uuid card-uuid)))
                                             (into []))))
                           (update-in [::area/battle ::area/stacks]
                                      conj
                                      {::stack/uuid (random-uuid)
                                       ::stack/cards [card]}))))
          (assoc ::game/available-actions
                 #{[:phase/main turn nil]})))))

(defn digivolve
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [card-uuid digivolve into-card-uuid] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        deck-has-cards? (pos? (count (get-in players
                                             [turn-idx
                                              ::player/areas
                                              ::area/deck
                                              ::area/cards])))
        card (->> (get-in game
                          [::game/players turn-idx ::player/areas
                           ::area/hand ::area/cards])
                  (filter (fn [{::card/keys [uuid]}]
                            (= uuid card-uuid)))
                  first)
        {:card/keys [digivolution-requirements]} (get-in game
                                                         (::card/lookup card))]
    (-> game
        (update-memory - (get-in digivolution-requirements
                                 [digivolve :digivolve/cost]))
        (update-in [::game/players turn-idx ::player/areas]
                   (fn [areas]
                     (-> areas
                         (update-in [::area/hand ::area/cards]
                                    (fn [cards]
                                      (->> cards
                                           (remove (fn [{::card/keys [uuid]}]
                                                     (= uuid card-uuid)))
                                           (into []))))
                         (update-in
                          [::area/battle ::area/stacks]
                          (fn [stacks]
                            (mapv (fn [{::stack/keys [cards]
                                       :as stack}]
                                    (cond-> stack
                                      (contains? (->> cards
                                                      (map ::card/uuid)
                                                      (into #{}))
                                                 into-card-uuid)
                                      (-> (update ::stack/cards
                                                  (fn [cards]
                                                    (->> (concat [card]
                                                                 cards)
                                                         (into []))))
                                          (assoc ::stack/uuid (random-uuid)))))
                                  stacks)))
                         (update-in
                          [::area/breeding ::area/stacks]
                          (fn [stacks]
                            (mapv (fn [{::stack/keys [cards]
                                       :as stack}]
                                    (cond-> stack
                                      (contains? (->> cards
                                                      (map ::card/uuid)
                                                      (into #{}))
                                                 into-card-uuid)
                                      (-> (update ::stack/cards
                                                  (fn [cards]
                                                    (->> (concat [card]
                                                                 cards)
                                                         (into []))))
                                          (assoc ::stack/uuid (random-uuid)))))
                                  stacks))))))
        (cond-> #__
          deck-has-cards?
          (update-in [::game/players turn-idx ::player/areas]
                     (fn [areas]
                       (-> areas
                           (update-in [::area/hand ::area/cards]
                                      conj
                                      (get-in areas [::area/deck
                                                     ::area/cards
                                                     0]))
                           (update-in [::area/deck ::area/cards]
                                      (comp #(into [] %) rest))))))
        (assoc ::game/available-actions
               #{[:phase/main turn nil]}))))

(defn pass
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))
        next-player-id (get-in game [::game/players next-turn-idx ::player/id])]
    (-> game
        (set-memory -3)
        (assoc ::game/available-actions
               #{[:phase/unsuspend turn nil]})
        (assoc-in [::game/in ::game-in/turn] next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/unsuspend))))
