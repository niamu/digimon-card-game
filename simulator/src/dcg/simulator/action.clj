(ns dcg.simulator.action
  (:require
   [clojure.spec.alpha :as s]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.attack :as-alias attack]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(defn set-memory
  [{::game/keys [players] :as game} memory]
  (let [memory (min memory 10)
        {turn-idx ::player/turn-index} (helpers/current-player game)
        {next-turn-idx ::player/turn-index} (helpers/next-player game)]
    (-> game
        (assoc-in [::game/players turn-idx ::player/memory]
                  memory)
        (assoc-in [::game/players next-turn-idx ::player/memory]
                  (* memory -1)))))

(defn update-memory
  [{::game/keys [players] :as game} op value]
  (let [{turn-idx ::player/turn-index} (helpers/current-player game)
        memory (-> (get-in game [::game/players turn-idx ::player/memory])
                   (op value))]
    (set-memory game memory)))

(defn re-draw?
  [{::game/keys [seed log players] :as game} [_ player-id re-draw? :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (helpers/next-player game)
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
  (let [{turn-idx ::player/turn-index} (helpers/current-player game)]
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
                                          (into []))
                                     ::stack/summoned? false
                                     ::stack/suspended? false}))))
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
  (let [{turn-idx ::player/turn-index} (helpers/current-player game)]
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
  (let [{turn-idx ::player/turn-index} (helpers/current-player game)
        card (->> (get-in game
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
                                     ::stack/summoned? true
                                     ::stack/suspended? false
                                     ::stack/cards [card]}))))
        (assoc ::game/available-actions
               #{[:phase/main turn nil]}))))

(defn digivolve
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [card-uuid digivolve-idx into-card-uuid] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{cards-in-hand ::area/cards} ::area/hand
          {deck-cards ::area/cards} ::area/deck} ::player/areas
         turn-idx ::player/turn-index
         :as current-player} (helpers/current-player game)
        deck-has-cards? (pos? (count deck-cards))
        card (-> (reduce (fn [accl {::card/keys [uuid] :as card}]
                           (assoc accl uuid card))
                         {}
                         cards-in-hand)
                 (get card-uuid))
        {:card/keys [digivolution-requirements]} (get-in game
                                                         (::card/lookup card))]
    (-> game
        (update-memory - (get-in digivolution-requirements
                                 [digivolve-idx :digivolve/cost]))
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

(defn declare-attack
  [{::game/keys [players] :as game} [_ _ [attacker target] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{turn-idx ::player/turn-index
         :as current-player} (helpers/current-player game)
        {opponent-id ::player/id
         :as opponent-turn} (or (get (helpers/players-by-id players)
                                     target)
                                (->> players
                                     (filter (fn [{{{battle-stacks ::area/stacks} ::area/battle} ::player/areas}]
                                               (contains? (->> battle-stacks
                                                               (map ::stack/uuid)
                                                               (into #{}))
                                                          target)))
                                     first))]
    (-> game
        (update-in [::game/players turn-idx ::player/areas]
                   (fn [areas]
                     (update-in areas
                                [::area/battle ::area/stacks]
                                (fn [stacks]
                                  (mapv (fn [{::stack/keys [uuid] :as stack}]
                                          (cond-> stack
                                            (= uuid attacker)
                                            (assoc ::stack/suspended? true)))
                                        stacks)))))
        (assoc ::game/attack
               {::attack/attacker attacker
                ::attack/attacking target}
               ::game/available-actions
               #{[:action/attack.counter opponent-id :require-input]})))
  ;; TODO: [When Attacking] and "when a Digimon attacks" effects trigger
  )

(defn counter-attack
  [{::game/keys [players]
    {::attack/keys [attacking]} ::game/attack
    :as game} [_ _ card-uuid :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{opponent-id ::player/id
         :as opponent-turn} (or (get (helpers/players-by-id players)
                                     attacking)
                                (->> players
                                     (filter (fn [{{{battle-stacks ::area/stacks} ::area/battle} ::player/areas}]
                                               (contains? (->> battle-stacks
                                                               (map ::stack/uuid)
                                                               (into #{}))
                                                          attacking)))
                                     first))]
    (-> game
        (assoc ::game/available-actions
               (cond-> #{(if (get (helpers/players-by-id players)
                                  attacking)
                           [:action/attack.security opponent-id nil]
                           [:action/attack.digimon opponent-id nil])}
                 ;; TODO: enumerate all possible blocks
                 #_(conj [:action/attack.block opponent-id stack-uuid])
                 ))))
  ;; TODO: Opponent's "When an opponent's Digimon attacks" effects activate.
  )

(defn block-attack
  [{::game/keys [players]
    {::game-in/keys [turn]} ::game/in
    :as game} [_ _ target :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (assoc-in [::game/attack ::attack/attacking] target)
      (assoc ::game/available-actions
             #{[:action/attack.digimon turn nil]})))

(defn digimon-attack
  [{::game/keys [players]
    {::attack/keys [attacker attacking]} ::game/attack
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{battle-stacks ::area/stacks} ::area/battle}
         ::player/areas} (helpers/current-player game)
        attacker-stack (loop [i 0]
                         (let [{::stack/keys [uuid]
                                :as stack} (nth battle-stacks i nil)]
                           (cond
                             (= uuid attacker) stack
                             (nil? stack) 0
                             :else (recur (inc i)))))
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> (get-in game
                                    (-> attacker-stack
                                        ::stack/cards
                                        first
                                        ::card/lookup))
                            (get :card/dp 0)))
        {{{opponent-battle-stacks ::area/stacks} ::area/battle}
         ::player/areas
         opponent-id ::player/id} (or (get (helpers/players-by-id players)
                                           attacking)
                                      (->> players
                                           (filter (fn [{{{battle-stacks ::area/stacks} ::area/battle} ::player/areas}]
                                                     (contains? (->> battle-stacks
                                                                     (map ::stack/uuid)
                                                                     (into #{}))
                                                                attacking)))
                                           first))
        attacking-stack (loop [i 0]
                          (let [{::stack/keys [uuid]
                                 :as stack} (nth opponent-battle-stacks i nil)]
                            (cond
                              (= uuid attacking) stack
                              (nil? stack) 0
                              :else (recur (inc i)))))
        attacking-dp (or (-> attacking-stack ::stack/dp)
                         (-> (get-in game
                                     (-> attacking-stack
                                         ::stack/cards
                                         first
                                         ::card/lookup))
                             (get :card/dp 0)))]
    (-> game
        (update ::game/players
                (fn [players]
                  (mapv (fn [{::player/keys [id] :as player}]
                          (cond-> player
                            (and (= id turn)
                                 (<= attacker-dp attacking-dp))
                            (update ::player/areas
                                    (fn [{::area/keys [deck hand]
                                         :as areas}]
                                      (cond-> (update-in areas
                                                         [::area/battle
                                                          ::area/stacks]
                                                         (fn [stacks]
                                                           (->> stacks
                                                                (remove (fn [{::stack/keys [uuid]}]
                                                                          (= uuid attacker)))
                                                                (into []))))
                                        ;; TODO: On deletion effect
                                        true
                                        (update-in
                                         [::area/trash
                                          ::area/cards]
                                         (fn [cards]
                                           (->> (concat (get attacker-stack
                                                             ::stack/cards)
                                                        cards)
                                                (into [])))))))
                            (and (= id opponent-id)
                                 (>= attacker-dp attacking-dp))
                            (update ::player/areas
                                    (fn [{::area/keys [deck hand]
                                         :as areas}]
                                      (cond-> (update-in areas
                                                         [::area/battle
                                                          ::area/stacks]
                                                         (fn [stacks]
                                                           (->> stacks
                                                                (remove (fn [{::stack/keys [uuid]}]
                                                                          (= uuid attacking)))
                                                                (into []))))
                                        ;; TODO: On deletion effect
                                        true
                                        (update-in
                                         [::area/trash
                                          ::area/cards]
                                         (fn [cards]
                                           (->> (concat (get attacking-stack
                                                             ::stack/cards)
                                                        cards)
                                                (into [])))))))))
                        players)))
        (assoc ::game/available-actions
               (if false ;; TODO: if attacker has piercing
                 #{[:action/attack.security turn nil]}
                 #{[:phase/main turn nil]})))))

(defn security-attack
  [{::game/keys [players]
    {::attack/keys [attacker attacking]} ::game/attack
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{battle-stacks ::area/stacks} ::area/battle}
         ::player/areas} (helpers/current-player game)
        {{{opponent-security ::area/cards} ::area/security} ::player/areas
         opponent-id ::player/id} (get (helpers/players-by-id players)
                                       attacking)
        attacker-stack (loop [i 0]
                         (let [{::stack/keys [uuid]
                                :as stack} (nth battle-stacks i nil)]
                           (cond
                             (= uuid attacker) stack
                             (nil? stack) 0
                             :else (recur (inc i)))))
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> (get-in game
                                    (-> attacker-stack
                                        ::stack/cards
                                        first
                                        ::card/lookup))
                            (get :card/dp 0)))
        attacking-dp (-> (get-in game
                                 (-> opponent-security
                                     first
                                     ::card/lookup))
                         (get :card/dp 0))]
    (if (zero? (count opponent-security))
      (-> game
          (assoc ::game/available-actions
                 #{})
          (assoc-in [::game/in ::game-in/state-id]
                    :game/end)
          (update-in [::game/log]
                     conj
                     [:game/end turn turn]))
      (-> game
          (update ::game/players
                  (fn [players]
                    (mapv (fn [{::player/keys [id] :as player}]
                            (cond-> player
                              (and (= id turn)
                                   (<= attacker-dp attacking-dp))
                              (update ::player/areas
                                      (fn [{::area/keys [deck hand]
                                           :as areas}]
                                        (cond-> (update-in areas
                                                           [::area/battle
                                                            ::area/stacks]
                                                           (fn [stacks]
                                                             (->> stacks
                                                                  (remove (fn [{::stack/keys [uuid]}]
                                                                            (= uuid attacker)))
                                                                  (into []))))
                                          ;; TODO: On deletion effect
                                          true
                                          (update-in
                                           [::area/trash
                                            ::area/cards]
                                           (fn [cards]
                                             (->> (concat (get attacker-stack
                                                               ::stack/cards)
                                                          cards)
                                                  (into [])))))))
                              (= id opponent-id)
                              (update ::player/areas
                                      (fn [{::area/keys [deck hand]
                                           :as areas}]
                                        (cond-> (update-in areas
                                                           [::area/security
                                                            ::area/cards]
                                                           (comp #(into [] %)
                                                                 rest))
                                          ;; TODO: effect move to battle area
                                          ;; instead of trash
                                          true
                                          (update-in
                                           [::area/trash
                                            ::area/cards]
                                           (fn [cards]
                                             (->> (concat [(-> opponent-security
                                                               first)]
                                                          cards)
                                                  (into [])))))))))
                          players)))
          (assoc ::game/available-actions
                 (if false ;; TODO: if attacker has security attack +N
                   #{[:action/attack.security turn nil]}
                   #{[:phase/main turn nil]}))))))

(defn pass
  [{{::game-in/keys [turn]} ::game/in :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (helpers/next-player game)]
    (-> game
        (set-memory -3)
        (assoc ::game/available-actions
               #{[:phase/unsuspend turn nil]})
        (assoc-in [::game/in ::game-in/turn] next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/unsuspend))))
