(ns dcg.simulator.action
  (:refer-clojure :exclude [use])
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
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.effect :as effect]
   [dcg.simulator.random :as random]))

(defn set-memory
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} memory]
  (let [memory (min memory 10)
        next-player-index (mod (inc turn-index) (count players))]
    (-> game
        (assoc-in [::game/players turn-index ::player/memory]
                  memory)
        (assoc-in [::game/players next-player-index ::player/memory]
                  (* memory -1)))))

(defn update-memory
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} [_ player-id [op value]]]
  (let [player-index (.indexOf (map ::player/id players) player-id)
        memory (-> (get-in game [::game/players player-index ::player/memory])
                   (op value))]
    (set-memory game memory)))

(defn re-draw?
  [{::game/keys [random available-actions log players]
    {::game-in/keys [turn-index]} ::game/in
    :as game} [_ [_ player-id] re-draw? :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)
        player-index (.indexOf (map ::player/id players) player-id)
        already-drawn (conj (->> log
                                 (filter (fn [[state-id _ _]]
                                           (= :action/re-draw? state-id)))
                                 (map (fn [[_ [_ player-id]]] player-id))
                                 set)
                            player-id)
        {::game/keys [players] :as game}
        (-> game
            (update-in [::game/players
                        player-index
                        ::player/areas]
                       (fn [{{deck ::area/cards} ::area/deck
                            {hand ::area/cards} ::area/hand
                            :as areas}]
                         (let [[hand deck] (->> (cond->> (concat hand deck)
                                                  re-draw?
                                                  (random/shuffle random))
                                                (split-at 5))
                               [security deck] (->> deck
                                                    (split-at 5))]
                           (-> areas
                               (assoc-in [::area/deck ::area/cards] deck)
                               (assoc-in [::area/hand ::area/cards] hand)
                               (assoc-in [::area/security ::area/cards]
                                         (reverse security))))))
            (assoc ::game/available-actions
                   (->> available-actions
                        (remove (fn [[action-key [_ id] _]]
                                  (and (= action-key :action/re-draw?)
                                       (= id player-id))))
                        set)))]
    (cond-> game
      (= already-drawn (set (map ::player/id players)))
      (-> (assoc ::game/available-actions
                 #{[:phase/unsuspend [::player/id current-player-id] nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/unsuspend)))))

(defn hatch
  [{::game/keys [random players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)]
    (-> game
        (update-in [::game/players turn-index ::player/areas]
                   (fn [{{digi-eggs ::area/cards} ::area/digi-eggs :as areas}]
                     (-> areas
                         (update-in [::area/digi-eggs ::area/cards] rest)
                         (update-in [::area/breeding ::area/stacks] conj
                                    {::stack/uuid (random/uuid random)
                                     ::stack/cards (->> (take 1 digi-eggs)
                                                        (into []))
                                     ::stack/summoned? false
                                     ::stack/suspended? false}))))
        (assoc ::game/available-actions
               #{[:phase/main [::player/id current-player-id] nil]})
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn move
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ [_ stack-uuid] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)]
    (cond-> game
      (= (some-> (get-in players [turn-index
                                  ::player/areas
                                  ::area/breeding
                                  ::area/stacks])
                 first
                 ::stack/uuid)
         stack-uuid)
      (-> (update-in [::game/players turn-index ::player/areas]
                     (fn [{{breeding ::area/stacks} ::area/breeding :as areas}]
                       (-> areas
                           (update-in [::area/battle ::area/stacks]
                                      (comp #(into [] %) concat) breeding)
                           (assoc-in [::area/breeding ::area/stacks] []))))
          (assoc ::game/available-actions
                 #{[:phase/main [::player/id current-player-id] nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/main)))))

(defn play
  [{::game/keys [random players cards-lookup]
    {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ [_ card-uuid & [override]] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [card {::card/uuid card-uuid}
        {:card/keys [play-cost]
         :as card-info} (get-in cards-lookup [card-uuid "en"])
        {current-player-id ::player/id} (nth players turn-index)
        stack-uuid (random/uuid random)]
    (-> game
        (update-memory [:action/update-memory
                        current-player-id
                        [- (or (:cost override)
                               play-cost)]])
        (update-in [::game/players turn-index ::player/areas]
                   (fn [{::area/keys [hand] :as areas}]
                     (-> areas
                         (update-in [::area/hand ::area/cards]
                                    (fn [cards]
                                      (->> cards
                                           (remove (partial = card))
                                           (into []))))
                         (update-in [::area/battle ::area/stacks] conj
                                    {::stack/uuid stack-uuid
                                     ::stack/summoned? true
                                     ::stack/suspended? false
                                     ::stack/cards [card]}))))
        (assoc ::game/available-actions
               #{[:phase/main [::player/id current-player-id] nil]}))))

(defn use
  [{::game/keys [random players cards-lookup]
    {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ [_ card-uuid & [override]] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [card {::card/uuid card-uuid}
        {:card/keys [use-cost]
         :as card-info} (get-in cards-lookup [card-uuid "en"])
        {current-player-id ::player/id} (nth players turn-index)
        stack-uuid (random/uuid random)]
    (-> game
        (update-memory [:action/update-memory
                        current-player-id
                        [- (or (:cost override)
                               use-cost)]])
        (update-in [::game/players turn-index ::player/areas]
                   (fn [{::area/keys [hand] :as areas}]
                     (-> areas
                         (update-in [::area/hand ::area/cards]
                                    (fn [cards]
                                      (->> cards
                                           (remove (partial = card))
                                           (into []))))
                         (update-in [::area/trash ::area/cards]
                                    (fn [cards]
                                      (->> (concat [card]
                                                   cards)
                                           (into [])))))))
        (assoc ::game/available-actions
               (if-let [[field index] (effect/effect-path card-info
                                                          :timing/main)]
                 #{[:action/effect [::player/id current-player-id]
                    [[::card/uuid card-uuid] field index]]}
                 #{[:phase/main [::player/id current-player-id] nil]})))))

(defn digivolve
  [{::game/keys [players cards-lookup] {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ [[_ card-uuid] digivolve-idx [_ stack-uuid]] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [card {::card/uuid card-uuid}
        {{{cards-in-hand ::area/cards} ::area/hand
          {deck-cards ::area/cards} ::area/deck} ::player/areas
         :as current-player} (nth players turn-index)
        deck-has-cards? (pos? (count deck-cards))
        {:card/keys [digivolution-requirements]} (get-in cards-lookup
                                                         [card-uuid "en"])
        {current-player-id ::player/id} (nth players turn-index)]
    (-> game
        (update-memory [:action/update-memory
                        current-player-id
                        [- (get-in digivolution-requirements
                                   [digivolve-idx :digivolve/cost])]])
        (update-in [::game/players
                    turn-index
                    ::player/areas
                    ::area/hand
                    ::area/cards]
                   (fn [cards]
                     (->> cards
                          (remove (partial = card))
                          (into []))))
        (update-in [::game/players
                    turn-index
                    ::player/areas]
                   (fn [areas]
                     (reduce-kv (fn [accl k {::area/keys [stacks] :as area}]
                                  (assoc accl k
                                         (cond-> area
                                           (seq stacks)
                                           (update ::area/stacks
                                                   (fn [stacks]
                                                     (map (fn [{::stack/keys [uuid] :as stack}]
                                                            (cond-> stack
                                                              (= uuid stack-uuid)
                                                              (update ::stack/cards
                                                                      (fn [cards]
                                                                        (->> cards
                                                                             (concat [card])
                                                                             (into []))))))
                                                          stacks))))))
                                {}
                                areas)))
        ;; Draw on digivolve
        (cond-> #__
          deck-has-cards?
          (-> (update-in [::game/players turn-index ::player/areas
                          ::area/hand ::area/cards]
                         conj
                         (first deck-cards))
              (update-in [::game/players turn-index ::player/areas
                          ::area/deck ::area/cards]
                         (comp #(into [] %) rest))))
        (assoc ::game/available-actions
               #{[:phase/main [::player/id current-player-id] nil]}))))

(defn declare-attack
  [{::game/keys [players]
    {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ [attacker target] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [players-by-index (->> players
                              (map-indexed (fn [idx {::player/keys [id]}]
                                             [id idx]))
                              (into {}))
        player-by-stack
        (->> players
             (reduce (fn [accl {{{breeding ::area/stacks} ::area/breeding
                                {battle ::area/stacks} ::area/battle}
                               ::player/areas
                               ::player/keys [id]}]
                       (merge accl
                              (->> (concat breeding battle)
                                   (map (fn [{stack-uuid ::stack/uuid}]
                                          [[::stack/uuid stack-uuid]
                                           [::player/id id]]))
                                   (into {}))))
                     {}))
        [_ opponent-id] (case (first target)
                          ::player/id target
                          (get player-by-stack target))]
    (-> game
        (update-in [::game/players
                    (->> (get player-by-stack attacker)
                         second
                         (get players-by-index))
                    ::player/areas
                    ::area/battle
                    ::area/stacks]
                   (fn [stacks]
                     (map (fn [{::stack/keys [uuid] :as stack}]
                            (cond-> stack
                              (= uuid (second attacker))
                              (assoc ::stack/suspended? true)))
                          stacks)))
        (assoc ::game/attack
               {::attack/attacker attacker
                ::attack/attacking target
                ::attack/player [::player/id opponent-id]}
               ::game/available-actions
               #{[:action/attack.counter
                  [::player/id opponent-id]
                  :require-input]})))
  ;; TODO: [When Attacking] and "when a Digimon attacks" effects trigger
  )

(defn counter-attack
  [{::game/keys [players]
    {::attack/keys [attacking player]} ::game/attack
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (assoc ::game/available-actions
             (cond-> #{(if (= (first attacking)
                              ::player/id)
                         [:action/attack.security player nil]
                         [:action/attack.digimon player nil])}
               ;; TODO: enumerate all possible blocks
               #_(conj [:action/attack.block player stack-uuid])
               )))
  ;; TODO: Opponent's "When an opponent's Digimon attacks" effects activate.
  )

(defn block-attack
  [{::game/keys [players]
    {::game-in/keys [turn-index]} ::game/in
    :as game} [_ _ target :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (assoc-in [::game/attack ::attack/attacking] target)
      ;; TODO: Action should be action/attack.battle
      #_(assoc ::game/available-actions
               ;; TODO: Action should be action/attack.battle
               #{[:action/attack.digimon [::player/id turn] nil]})))

(defn digimon-attack
  [{::game/keys [cards-lookup players]
    {::attack/keys [attacker attacking player]} ::game/attack
    {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)
        player-by-stack
        (->> players
             (reduce (fn [accl {{{breeding ::area/stacks} ::area/breeding
                                {battle ::area/stacks} ::area/battle}
                               ::player/areas
                               ::player/keys [id]}]
                       (merge accl
                              (->> (concat breeding battle)
                                   (map (fn [{stack-uuid ::stack/uuid}]
                                          [[::stack/uuid stack-uuid]
                                           id]))
                                   (into {}))))
                     {}))
        stacks-by-ident (helpers/stacks-by-ident players)
        attacker-stack (-> (get stacks-by-ident attacker)
                           (update ::stack/cards
                                   (partial helpers/stack-cards-lookup
                                            cards-lookup)))
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> attacker-stack
                            ::stack/cards
                            first
                            (get-in [::card/card :card/dp] 0)))
        attacking-stack (-> (get stacks-by-ident attacking)
                            (update ::stack/cards
                                    (partial helpers/stack-cards-lookup
                                             cards-lookup)))
        attacking-dp (or (-> attacking-stack ::stack/dp)
                         (-> attacking-stack
                             ::stack/cards
                             first
                             (get-in [::card/card :card/dp] 0)))]
    (cond-> game
      (<= attacker-dp attacking-dp)
      ;; TODO: On deletion effect
      (update-in
       [::game/players]
       (fn [players]
         (mapv (fn [player]
                 (cond-> player
                   (= (::player/id player)
                      (get player-by-stack attacker))
                   (update ::player/areas
                           (fn [areas]
                             (-> areas
                                 (update-in [::area/battle
                                             ::area/stacks]
                                            (fn [stacks]
                                              (->> stacks
                                                   (remove
                                                    (fn [{::stack/keys [uuid]}]
                                                      (= (get attacker-stack
                                                              ::stack/uuid)
                                                         uuid))))))
                                 (update-in [::area/trash
                                             ::area/cards]
                                            (fn [cards]
                                              (->> (concat (get attacker-stack
                                                                ::stack/cards)
                                                           cards)
                                                   (into [])))))))))
               players)))
      (>= attacker-dp attacking-dp)
      ;; TODO: On deletion effect
      (update-in
       [::game/players]
       (fn [players]
         (mapv (fn [player]
                 (cond-> player
                   (= (::player/id player)
                      (get player-by-stack attacking))
                   (update ::player/areas
                           (fn [areas]
                             (-> areas
                                 (update-in [::area/battle
                                             ::area/stacks]
                                            (fn [stacks]
                                              (->> stacks
                                                   (remove
                                                    (fn [{::stack/keys [uuid]}]
                                                      (= (get attacking-stack
                                                              ::stack/uuid)
                                                         uuid))))))
                                 (update-in [::area/trash
                                             ::area/cards]
                                            (fn [cards]
                                              (->> (concat (get attacking-stack
                                                                ::stack/cards)
                                                           cards)
                                                   (into [])))))))))
               players)))
      true
      (assoc ::game/available-actions
             (if false ;; TODO: if attacker has piercing and survived
               #{[:action/attack.security [::player/id current-player-id] nil]}
               #{[:phase/main [::player/id current-player-id] nil]})))))

(defn security-attack
  [{::game/keys [cards-lookup players]
    {::attack/keys [attacker attacking player]} ::game/attack
    {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)
        player-by-stack
        (->> players
             (reduce (fn [accl {{{breeding ::area/stacks} ::area/breeding
                                {battle ::area/stacks} ::area/battle}
                               ::player/areas
                               ::player/keys [id]}]
                       (merge accl
                              (->> (concat breeding battle)
                                   (map (fn [{stack-uuid ::stack/uuid}]
                                          [[::stack/uuid stack-uuid]
                                           id]))
                                   (into {}))))
                     {}))
        stacks-by-ident (helpers/stacks-by-ident players)
        attacker-stack (-> (get stacks-by-ident attacker)
                           (update ::stack/cards
                                   (partial helpers/stack-cards-lookup
                                            cards-lookup)))
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> attacker-stack
                            ::stack/cards
                            first
                            (get-in [::card/card :card/dp] 0)))
        {{{opponent-security ::area/cards} ::area/security} ::player/areas
         opponent-id ::player/id} (get (helpers/players-by-id players) (second player))
        attacking-card (first opponent-security)
        attacking-dp (get (helpers/card-lookup cards-lookup attacking-card)
                          :card/dp 0)]
    (if (zero? (count opponent-security))
      (-> game
          (assoc ::game/available-actions #{})
          (assoc-in [::game/in ::game-in/state-id] :game/end)
          (update-in [::game/log] conj
                     [:game/end
                      [::player/id current-player-id]
                      [::player/id current-player-id]]))
      (cond-> game
        (<= attacker-dp attacking-dp)
        ;; TODO: On deletion effect
        (update-in
         [::game/players]
         (fn [players]
           (mapv (fn [player]
                   (cond-> player
                     (= (::player/id player)
                        (get player-by-stack attacker))
                     (update ::player/areas
                             (fn [areas]
                               (-> areas
                                   (update-in [::area/battle
                                               ::area/stacks]
                                              (fn [stacks]
                                                (->> stacks
                                                     (remove
                                                      (fn [{::stack/keys [uuid]}]
                                                        (= (get attacker-stack
                                                                ::stack/uuid)
                                                           uuid))))))
                                   (update-in [::area/trash
                                               ::area/cards]
                                              (fn [cards]
                                                (->> (concat (get attacker-stack
                                                                  ::stack/cards)
                                                             cards)
                                                     (into [])))))))))
                 players)))
        true
        (update-in
         [::game/players]
         (fn [players]
           (mapv (fn [player]
                   (cond-> player
                     (= (::player/id player)
                        opponent-id)
                     (update ::player/areas
                             (fn [areas]
                               (-> areas
                                   (update-in [::area/security
                                               ::area/cards]
                                              (comp #(into [] %) rest))
                                   ;; TODO: security effect may move to
                                   ;; battle area instead of trash
                                   (update-in [::area/trash
                                               ::area/cards]
                                              (fn [cards]
                                                (->> (concat [attacking-card]
                                                             cards)
                                                     (into [])))))))))
                 players)))
        true
        (assoc ::game/available-actions
               (if false ;; TODO: if attacker has security attack +N
                 #{[:action/attack.security [::player/id current-player-id] nil]}
                 #{[:phase/main [::player/id current-player-id] nil]}))))))

(defn pass
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (->> (mod (inc turn-index) (count players))
                                          (nth players))]
    (-> game
        (set-memory -3)
        (assoc ::game/available-actions
               #{[:phase/end-turn [::player/id next-player-id] nil]})
        (assoc-in [::game/in ::game-in/turn] next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/end-turn))))

(defn unsuspend-stack
  [stack]
  (assoc stack
         ::stack/suspended? false
         ::stack/summoned? false))

(defn effect
  [{::game/keys [random players cards-lookup]
    {::game-in/keys [turn-index state-id]} ::game/in
    :as game} [_ _ [[_ card-uuid] field index] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)]
    (-> game
        (effect/transform action)
        (assoc ::game/available-actions
               #{[state-id [::player/id current-player-id] nil]}))))
