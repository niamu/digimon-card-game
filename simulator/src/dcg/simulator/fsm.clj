(ns dcg.simulator.fsm
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [dcg.codec.spec]
   [dcg.codec.decode :as decode]
   [dcg.db.db :as db]
   [dcg.simulator.server.render :as render])
  (:import
   [java.util ArrayList Collection Collections Date Random UUID]))

(def player
  {:player/name          "PLACEHOLDER"
   :player/deck-codec    "DCG..."
   :player/deck          {}
   :player/memory        0
   :player/zones         {:zone/trash         []
                          :zone/deck          []
                          :zone/hand          []
                          :zone/digi-eggs     []
                          :zone/raising-area  []
                          :zone/battle-area   []
                          :zone/security      []}})

(defn find-card
  [number parallel-id language]
  (db/q '{:find [(pull ?c [:card/id
                           :card/name
                           :card/number
                           :card/category
                           :card/language
                           :card/parallel-id
                           :card/block-icon
                           :card/play-cost
                           :card/use-cost
                           :card/level
                           :card/dp
                           :card/effect
                           :card/inherited-effect
                           :card/security-effect
                           :card/form
                           :card/attribute
                           :card/type
                           :card/rarity
                           :card/color
                           {:card/digivolution-requirements
                            [:digivolve/id
                             :digivolve/cost
                             :digivolve/color
                             :digivolve/level
                             :digivolve/index]}
                           {:card/image
                            [:image/id
                             :image/path]}
                           {:card/errata
                            [:errata/id
                             :errata/date
                             :errata/error
                             :errata/correction
                             :errata/notes]}
                           {:card/limitation
                            [:limitation/id
                             :limitation/type
                             :limitation/date
                             :limitation/allowance
                             :limitation/note]}
                           {:card/highlights
                            [:highlight/id
                             :highlight/type
                             :highlight/text
                             :highlight/index
                             :highlight/field
                             {:highlight/treat
                              [:treat/id
                               :treat/as
                               :treat/field]}
                             {:highlight/mention
                              [:mention/id
                               :mention/text
                               {:mention/cards
                                [:card/id]}]}]}
                           {:card/mentions
                            [:mention/id
                             :mention/text
                             {:mention/cards
                              [:card/id]}]}
                           {:card/releases
                            [:release/id
                             :release/date
                             :release/name
                             :release/genre
                             :release/product-uri]}]) .]
          :in [$ ?number ?parallel-id ?language]
          :where [[?c :card/language ?language]
                  [?c :card/number ?number]
                  [?c :card/parallel-id ?parallel-id]]}
        number
        parallel-id
        language))

(defn init-player
  [player]
  (let [deck (->> (:player/deck-codec player)
                  decode/decode)
        populate
        (fn [cards]
          (->> cards
               (mapcat (fn [{:card/keys [number parallel-id count]
                             :or {parallel-id 0}}]
                         (->> (find-card number
                                         parallel-id
                                         (get deck :deck/language "en"))
                              (repeat count))))))]
    (if (s/valid? :dcg/deck deck)
      (assoc player
             :player/id (-> (:player/name player)
                            (.getBytes)
                            UUID/nameUUIDFromBytes)
             :player/deck (-> deck
                              (update :deck/digi-eggs populate)
                              (update :deck/deck populate)))
      (throw (Exception. "Invalid deck codec")))))

(defn initialize-player-decks
  [{:game/keys [seed] :as state} player-id]
  (update-in state [:game/players player-id]
             (fn [player]
               (let [{{:deck/keys [digi-eggs deck]} :player/deck} player
                     shuffled-deck (shuffle deck)
                     #_(shuffle-with-seed deck seed)]
                 (-> player
                     (assoc-in [:player/zones :zone/digi-eggs]
                               (shuffle digi-eggs)
                               #_(shuffle-with-seed digi-eggs seed))
                     (assoc-in [:player/zones :zone/deck]
                               (vec (drop 5 shuffled-deck)))
                     (assoc-in [:player/zones :zone/hand]
                               (vec (take 5 shuffled-deck))))))))

(defn game-start
  [state [p1 p2]]
  (let [now (Date.)
        {p1-id :player/id :as p1} (init-player p1)
        {p2-id :player/id :as p2} (init-player p2)
        game-id (pr-str [now p1-id p2-id])
        seed (hash [p1-id p2-id])
        [p1 p2] (shuffle [p1 p2]) #_(shuffle-with-seed [p1 p2] seed)]
    (-> state
        (assoc :game/id (UUID/nameUUIDFromBytes (.getBytes game-id))
               :game/current-turn p1-id
               :game/next-turn p2-id
               :game/seed seed)
        (assoc-in [:game/players p1-id]
                  (merge player p1))
        (assoc-in [:game/players p2-id]
                  (merge player p2))
        (initialize-player-decks p1-id)
        (initialize-player-decks p2-id))))

(defn draw
  [{:game/keys [current-turn] :as state}]
  (update-in state [:game/players current-turn]
             (fn [player]
               (let [{{:zone/keys [deck]} :player/zones} player]
                 (-> player
                     (update-in [:player/zones :zone/deck]
                                (comp vec rest))
                     (update-in [:player/zones :zone/hand]
                                (fnil conj [])
                                (first deck)))))))

(defn hatch-digi-egg
  [state player-id]
  (update-in state [:game/players player-id]
             (fn [player]
               (let [{{:zone/keys [digi-eggs raising-area]}
                      :player/zones} player]
                 (if (and (empty? raising-area)
                          (not (empty? digi-eggs)))
                   (-> player
                       (assoc-in [:player/zones
                                  :zone/digi-eggs]
                                 (rest digi-eggs))
                       (update-in [:player/zones
                                   :zone/raising-area]
                                  (fnil conj [])
                                  {:slot/id (inc (count raising-area))
                                   :slot/suspended? false
                                   :slot/summoning-sickness? false
                                   :slot/cards [(first digi-eggs)]}))
                   player)))))

(defn move-out-of-raising-area
  [state player-id]
  (update-in state [:game/players player-id]
             (fn [player]
               (let [{{:zone/keys [raising-area battle-area]}
                      :player/zones} player
                     card-slot (first raising-area)]
                 (if (some-> card-slot
                             :slot/cards
                             peek
                             :card/dp)
                   (-> player
                       (update-in [:player/zones :zone/battle-area]
                                  (fnil conj [])
                                  (assoc card-slot
                                         :slot/id (inc (count battle-area))))
                       (update-in [:player/zones :zone/raising-area]
                                  empty))
                   player)))))

(defn pass-turn
  [{:game/keys [current-turn next-turn players] :as state}]
  (assoc state
         :game/current-turn next-turn
         :game/next-turn current-turn))

(defn update-memory
  [{:game/keys [current-turn next-turn] :as state} amount]
  (-> state
      (update-in [:game/players current-turn :player/memory]
                 (fnil - 0)
                 amount)
      (update-in [:game/players next-turn :player/memory]
                 (fnil + 0)
                 amount)))

(defn which-digivolution-requirement
  [card {:card/keys [digivolution-requirements]}]
  (when digivolution-requirements
    (->> digivolution-requirements
         (filter (fn [{:digivolve/keys [cost color level]}]
                   (and (not (empty? (set/intersection (set (:card/color card))
                                                       (set color))))
                        (= (:card/level card)
                           level))))
         (sort-by :digivolve/cost)
         first)))

(defn play-from-hand
  [{:game/keys [current-turn next-turn players] :as state} [card-id-in-hand]]
  (let [hand (get-in players [current-turn
                              :player/zones
                              :zone/hand])
        card-in-hand (some->> hand
                              (filter (fn [{:card/keys [id]}]
                                        (= id card-id-in-hand)))
                              first)
        memory (get-in players [current-turn :player/memory])
        available-memory (if (>= memory 0)
                           (+ memory 10)
                           0)]
    (cond-> state
      (when card-in-hand
        (<= (or (:card/play-cost card-in-hand)
                (:card/use-cost card-in-hand))
            available-memory))
      (-> (update-in [:game/players
                      current-turn
                      :player/zones
                      :zone/battle-area]
                     (fnil conj [])
                     {:slot/id (-> (get-in players
                                           [current-turn
                                            :player/zones
                                            :zone/battle-area])
                                   count
                                   inc)
                      :slot/suspended? false
                      :slot/summoning-sickness? true
                      :slot/cards [card-in-hand]})
          (update-in [:game/players
                      current-turn
                      :player/zones
                      :zone/hand]
                     (fn [hand]
                       (loop [hand hand
                              idx 0
                              removed? false]
                         (let [card (nth hand idx nil)
                               match? (= (:card/id card)
                                         card-id-in-hand)]
                           (if (or (nil? card)
                                   removed?)
                             hand
                             (recur (vec (cond->> hand
                                           match?
                                           (keep-indexed #(when-not
                                                              (= (:card/id %2)
                                                                 card-id-in-hand)
                                                            %2))))
                                    (inc idx)
                                    match?))))))
          ;; TODO: Handle "On Play" and "Main" timings
          ;; TODO: Trash Option card after "Main" effect
          (update-memory (or (:card/play-cost card-in-hand)
                             (:card/use-cost card-in-hand)
                             0))))))

(defn player-can-block?
  [{:game/keys [players] :as state} player-uuid]
  (let [battle-area (get-in players [player-uuid
                                     :player/zones
                                     :zone/battle-area])]
    (->> battle-area
         (filter (fn [{:slot/keys [cards suspended?]}]
                   (when (not suspended?)
                     (let [inherited-effects
                           (->> (drop-last cards)
                                (filter
                                 (fn [{:card/keys [highlights]}]
                                   (->> highlights
                                        (filter
                                         (fn [{:highlight/keys [text field]}]
                                           (and (= field
                                                   :card/inherited-effect)
                                                (= (subs text 1
                                                         (dec (count text)))
                                                   "Blocker"))))
                                        empty?
                                        not))))
                           effects
                           (->> (first cards)
                                :card/highlights
                                (filter (fn [{:highlight/keys [text field]}]
                                          (and (= field
                                                  :card/effect)
                                               (= (subs text 1
                                                        (dec (count text)))
                                                  "Blocker")))))]
                       (or (not (empty? inherited-effects))
                           (not (empty? effects)))))))
         empty?
         not)))

(defn battle-area-slot->trash
  [{:game/keys [players] :as state} player-uuid slot-id]
  (let [slot-to-delete (->> (get-in players [player-uuid
                                             :player/zones
                                             :zone/battle-area])
                            (filter (fn [{:slot/keys [id cards suspended?]}]
                                      (= id slot-id)))
                            first)]
    (-> state
        (update-in [:game/players
                    player-uuid
                    :player/zones
                    :zone/battle-area]
                   (fn [battle-area]
                     (->> battle-area
                          ;; Delete slot from battle-area
                          (remove (fn [{:slot/keys [id]}]
                                    (= id slot-id)))
                          ;; Re-order battle-area slot IDs
                          (reduce (fn [accl slot]
                                    (conj accl
                                          (assoc slot
                                                 :slot/id
                                                 (inc (count accl)))))
                                  []))))
        (update-in [:game/players
                    player-uuid
                    :player/zones
                    :zone/trash]
                   (comp vec concat)
                   (:slot/cards slot-to-delete)))))

(defn suspend-battle-area-slot
  [state player-uuid slot-id]
  (update-in state
             [:game/players
              player-uuid
              :player/zones
              :zone/battle-area]
             (fn [battle-area]
               (->> battle-area
                    (reduce (fn [accl slot]
                              (conj accl
                                    (cond-> slot
                                      (= (:slot/id slot) slot-id)
                                      (assoc :slot/suspended? true))))
                            [])))))

(defn security-check
  [{:game/keys [current-turn next-turn players] :as state} battle-area-slot-id]
  (let [attacking-slot (->> (get-in players [current-turn
                                             :player/zones
                                             :zone/battle-area])
                            (filter (fn [{:slot/keys [id cards suspended?]}]
                                      (= id battle-area-slot-id)))
                            first)
        defending-card (->> (get-in players [next-turn
                                             :player/zones
                                             :zone/security])
                            first)]
    ;; TODO: Apply current DP & active effects to attacking slot
    (when (:card/security-effect defending-card)
      ;; TODO: Active security effect of defending card
      (throw (Exception. (format "%s needs security effect handled"
                                 (:card/number defending-card)))))
    (cond-> (-> state
                ;; Ensure both battling cards are suspended
                (suspend-battle-area-slot current-turn battle-area-slot-id)
                ;; Delete security card
                (update-in [:game/players
                            next-turn
                            :player/zones
                            :zone/security]
                           (comp vec rest))
                (update-in [:game/players
                            next-turn
                            :player/zones
                            :zone/trash]
                           conj
                           defending-card))
      (> (-> attacking-slot :slot/cards peek :card/dp)
         (:card/dp defending-card))
      (cond-> #_state
        (pos? (get-in attacking-slot
                      [:slot/modifiers
                       :modifier/security-attack]
                      0))
        (-> (update-in [:game/players
                        current-turn
                        :player/zones
                        :zone/battle-area]
                       (fn [battle-area]
                         (mapv (fn [{:slot/keys [id] :as slot}]
                                 (cond-> slot
                                   (= id battle-area-slot-id)
                                   (update-in [:slot/modifiers
                                               :modifier/security-attack]
                                              dec)))
                               battle-area)))
            (security-check battle-area-slot-id)))
      (< (-> attacking-slot :slot/cards peek :card/dp)
         (:card/dp defending-card))
      (battle-area-slot->trash current-turn battle-area-slot-id)
      (= (-> attacking-slot :slot/cards peek :card/dp)
         (:card/dp defending-card))
      (battle-area-slot->trash current-turn battle-area-slot-id))))

(defn resolve-attack
  [{:game/keys [current-turn next-turn players] :as state}
   [battle-area-slot-id opponent-slot-id]]
  (if (nil? opponent-slot-id)
    (security-check state battle-area-slot-id)
    (let [attacking-slot (->> (get-in players [current-turn
                                               :player/zones
                                               :zone/battle-area])
                              (filter (fn [{:slot/keys [id cards suspended?]}]
                                        (= id battle-area-slot-id)))
                              first)
          defending-slot (->> (get-in players [next-turn
                                               :player/zones
                                               :zone/battle-area])
                              (filter (fn [{:slot/keys [id cards suspended?]}]
                                        (= id opponent-slot-id)))
                              first)]
      ;; TODO: Apply current DP & active effects to slot
      (cond-> (-> state
                  ;; Ensure both battling cards are suspended
                  (suspend-battle-area-slot current-turn battle-area-slot-id)
                  (suspend-battle-area-slot next-turn opponent-slot-id))
        (> (-> attacking-slot :slot/cards peek :card/dp)
           (-> defending-slot :slot/cards peek :card/dp))
        (battle-area-slot->trash next-turn opponent-slot-id)
        (< (-> attacking-slot :slot/cards peek :card/dp)
           (-> defending-slot :slot/cards peek :card/dp))
        (battle-area-slot->trash current-turn battle-area-slot-id)
        (= (-> attacking-slot :slot/cards peek :card/dp)
           (-> defending-slot :slot/cards peek :card/dp))
        (-> (battle-area-slot->trash current-turn battle-area-slot-id)
            (battle-area-slot->trash next-turn opponent-slot-id))))))

(defn target-attack
  [{:game/keys [current-turn next-turn players] :as state}
   [battle-area-slot-id opponent-slot-id]]
  ;; TODO: Attack resolution
  (let [opponent-security-stack (get-in players [next-turn
                                                 :player/zones
                                                 :zone/security])
        battle-area (get-in players [current-turn
                                     :player/zones
                                     :zone/battle-area])
        opponent-battle-area (get-in players [next-turn
                                              :player/zones
                                              :zone/battle-area])
        opponent-battle-area-card
        (some->> opponent-battle-area
                 (filter (fn [{:slot/keys [id cards suspended?]}]
                           (and (= id opponent-slot-id)
                                (:card/dp (peek cards))
                                suspended?)))
                 first
                 :slot/cards
                 peek)
        battle-area-card (->> battle-area
                              (filter (fn [{:slot/keys [id cards suspended?]}]
                                        (and (= id battle-area-slot-id)
                                             (:card/dp (peek cards))
                                             (not suspended?))))
                              first
                              :slot/cards
                              peek)
        opponent-card (cond
                        opponent-battle-area-card opponent-battle-area-card
                        (and (not (empty? opponent-security-stack))
                             (not opponent-slot-id)) nil
                        opponent-slot-id (throw (Exception.
                                                 (format
                                                  (str "Opponent slot ID %s "
                                                       "cannot be attacked.")
                                                  opponent-slot-id)))
                        :else :win)]
    (cond-> (-> state
                (suspend-battle-area-slot current-turn battle-area-slot-id))
      (and (not (player-can-block? state next-turn))
           (not= opponent-card :win))
      (as-> #_state state
        (if opponent-battle-area-card
          (-> state
              (resolve-attack [battle-area-slot-id opponent-slot-id]))
          (-> state
              (security-check battle-area-slot-id))))

      ;; TODO: [When Attacking] and "when a Digimon attacks"
      ;; effects trigger

      ;; TODO: Counter Timing

      ;; TODO: Block Timing
      (player-can-block? state next-turn)
      (-> pass-turn
          (assoc-in [:game/players next-turn :player/blocking?] true))

      (and opponent-card
           (= opponent-card :win))
      (update-in [:game/players
                  current-turn]
                 assoc
                 :player/wins? true))))

(defn main-phase
  [{:game/keys [current-turn players] :as state} _]
  (let [hand (get-in players [current-turn
                              :player/zones
                              :zone/hand])
        raising-area-cards (some->> (get-in players
                                            [current-turn
                                             :player/zones
                                             :zone/raising-area])
                                    first
                                    :slot/cards
                                    peek)
        battle-area (get-in players [current-turn
                                     :player/zones
                                     :zone/battle-area])
        battle-area-cards (map (fn [{:slot/keys [cards]}]
                                 (peek cards))
                               battle-area)
        memory (get-in players [current-turn :player/memory])
        available-memory (if (>= memory 0)
                           (+ memory 10)
                           0)]
    (assoc-in state
              [:game/players
               current-turn
               :player/prompt]
              (cond-> #{:phase/end}
                (some (fn [{:card/keys [play-cost use-cost] :as card}]
                        (and (= (:card/category card)
                                "Digimon")
                             (<= (or play-cost use-cost) available-memory)))
                      hand)
                (conj :phase/main.play-digimon)
                (some (fn [{:card/keys [play-cost use-cost] :as card}]
                        (and (= (:card/category card)
                                "Tamer")
                             (<= (or play-cost use-cost) available-memory)))
                      hand)
                (conj :phase/main.play-tamer)
                (let [colors-in-play (->> (conj battle-area-cards
                                                raising-area-cards)
                                          (mapcat :card/color)
                                          (into #{}))]
                  (some (fn [{:card/keys [color play-cost use-cost]
                              :as card}]
                          (and (= (:card/category card) "Option")
                               (not
                                (empty?
                                 (set/intersection
                                  colors-in-play
                                  (set color))))
                               (<= (or play-cost use-cost) available-memory)))
                        hand))
                (conj :phase/main.play-option)
                (when (>= available-memory 0)
                  (some
                   (fn [{:card/keys [digivolution-requirements]}]
                     (when digivolution-requirements
                       (some
                        (fn [{:digivolve/keys [cost color level]}]
                          (and (<= cost available-memory)
                               (some (fn [card]
                                       (and
                                        (not
                                         (empty?
                                          (set/intersection
                                           (set (:card/color card))
                                           (set color))))
                                        (= (:card/level card)
                                           level)))
                                     (conj battle-area-cards
                                           raising-area-cards))))
                        digivolution-requirements)))
                   hand))
                (conj :phase/main.digivolve)
                (some (fn [{:slot/keys [cards suspended? summoning-sickness?]}]
                        (and (not summoning-sickness?)
                             (not suspended?)
                             (:card/dp (peek cards))))
                      battle-area)
                (conj :phase/main.attack)))))

(defn digivolve
  [{:game/keys [current-turn next-turn players] :as state}
   [card-id-in-hand zone slot-id]]
  (let [hand (get-in players [current-turn
                              :player/zones
                              :zone/hand])
        card-in-hand (some->> hand
                              (filter (fn [{:card/keys [id]}]
                                        (= id card-id-in-hand)))
                              first)
        slots (get-in players [current-turn
                               :player/zones
                               zone])
        digivolution-requirement (which-digivolution-requirement
                                  (some->> slots
                                           (filter (fn [slot]
                                                     (= (:slot/id slot)
                                                        slot-id)))
                                           first
                                           :slot/cards
                                           peek)
                                  card-in-hand)
        memory (get-in players [current-turn :player/memory])
        available-memory (if (>= memory 0)
                           (+ memory 10)
                           0)]
    (cond-> state
      (when digivolution-requirement
        (<= (:digivolve/cost digivolution-requirement) available-memory))
      (-> (update-in [:game/players
                      current-turn
                      :player/zones
                      zone]
                     (fn [area]
                       (reduce (fn [accl slot]
                                 (conj accl
                                       (cond-> slot
                                         (and (= slot-id (:slot/id slot))
                                              digivolution-requirement)
                                         (update-in [:slot/cards]
                                                    conj
                                                    card-in-hand))))
                               []
                               area)))
          (update-in [:game/players
                      current-turn
                      :player/zones
                      :zone/hand]
                     (fn [hand]
                       (loop [hand hand
                              idx 0
                              removed? false]
                         (let [card (nth hand idx nil)
                               match? (= (:card/id card)
                                         card-id-in-hand)]
                           (if (or (nil? card)
                                   removed?)
                             hand
                             (recur (vec (cond->> hand
                                           match?
                                           (keep-indexed
                                            #(when-not
                                                 (= (:card/id %2)
                                                    card-id-in-hand)
                                               %2))))
                                    (inc idx)
                                    match?))))))
          (update-memory (get digivolution-requirement
                              :digivolve/cost 0))
          draw
          ;; TODO: Handle "When Digivolving"
          ))))

(defn setup-security
  [{:game/keys [current-turn players] :as state} _]
  (-> state
      (update-in [:game/players current-turn]
                 (fn [player]
                   (let [{{:zone/keys [deck]} :player/zones} player]
                     (-> player
                         (update-in [:player/zones :zone/deck]
                                    #(drop 5 %))
                         (assoc-in [:player/zones :zone/security]
                                   (reverse (take 5 deck)))))))
      pass-turn))

(defn unsuspend
  [{:game/keys [current-turn players] :as state} _]
  (update-in
   state
   [:game/players current-turn :player/zones :zone/battle-area]
   (fn [battle-area]
     (reduce (fn [accl {:slot/keys [cards] :as slot}]
               (let [inherited-effects
                     (->> (drop-last cards)
                          (mapcat
                           (fn [{:card/keys [highlights]}]
                             (->> highlights
                                  (filter
                                   (fn [{:highlight/keys [text field]}]
                                     (and (= field
                                             :card/inherited-effect)
                                          (string/includes?
                                           text "Security Attack +"))))
                                  (map (comp
                                        parse-long
                                        #(re-find #"[0-9]+" %)
                                        :highlight/text))))))
                     effects
                     (->> (first cards)
                          :card/highlights
                          (filter (fn [{:highlight/keys [text field]}]
                                    (and (= field
                                            :card/effect)
                                         (string/includes?
                                          text "Security Attack +"))))
                          (map (comp
                                parse-long
                                #(re-find #"[0-9]+" %)
                                :highlight/text)))
                     additional-security-attacks
                     (reduce + 0 (concat inherited-effects
                                         effects))]
                 (conj accl
                       (cond-> (-> slot
                                   (dissoc :slot/summoning-sickness?)
                                   (assoc :slot/suspended? false))
                         (pos? additional-security-attacks)
                         (assoc :slot/modifiers
                                {:modifier/security-attack
                                 additional-security-attacks})))))
             []
             battle-area))))

(defn raising
  [{:game/keys [current-turn players] :as state} _]
  (assoc-in state
            [:game/players current-turn :player/prompt]
            (cond-> #{:phase/raising?do-nothing}
              (and (empty? (get-in players [current-turn
                                            :player/zones
                                            :zone/raising-area]))
                   (not (empty? (get-in players [current-turn
                                                 :player/zones
                                                 :zone/digi-eggs]))))
              (conj :phase/raising?hatch)
              (some-> (get-in players [current-turn
                                       :player/zones
                                       :zone/raising-area])
                      first
                      :slot/cards
                      peek
                      :card/dp)
              (conj :phase/raising?move-to-battle-area))))

(def fsm
  "Inspired by https://github.com/yogthos/maestro"
  {:game/start {:handler game-start
                :dispatches [[:phase/mulligan? (constantly true)]]}
   :phase/mulligan? {:handler (fn [{:game/keys [current-turn] :as state} _]
                                (assoc-in state
                                          [:game/players
                                           current-turn
                                           :player/prompt]
                                          #{:phase/mulligan?accept
                                            :phase/mulligan?decline}))}
   :phase/mulligan?accept {:handler (fn [{:game/keys [current-turn] :as state} _]
                                      (-> state
                                          (assoc-in [:game/players
                                                     current-turn
                                                     :player/ready?]
                                                    true)
                                          (initialize-player-decks current-turn)
                                          pass-turn))
                           :dispatches [[:phase/setup-security
                                         (fn [{:game/keys [players] :as state}]
                                           (every? (fn [[_ player]]
                                                     (:player/ready? player))
                                                   players))]
                                        [:phase/mulligan? (constantly true)]]}
   :phase/mulligan?decline {:handler (fn [{:game/keys [current-turn players]
                                           :as state} _]
                                       (-> state
                                           (assoc-in [:game/players
                                                      current-turn
                                                      :player/ready?]
                                                     true)
                                           pass-turn))
                            :dispatches [[:phase/setup-security
                                          (fn [{:game/keys [players] :as state}]
                                            (every? (fn [[_ player]]
                                                      (:player/ready? player))
                                                    players))]
                                         [:phase/mulligan? (constantly true)]]}
   :phase/setup-security {:handler setup-security
                          :dispatches [[:phase/unsuspend
                                        (fn [{:game/keys [players] :as state}]
                                          (every?
                                           (fn [[id player]]
                                             (= (count
                                                 (get-in player
                                                         [:player/zones
                                                          :zone/security]))
                                                5))
                                           players))]
                                       [:phase/setup-security
                                        (constantly true)]]}
   :phase/unsuspend {:handler unsuspend
                     :dispatches [[:phase/draw (constantly true)]]}
   :phase/draw {:handler (fn [state _]
                           (draw state))
                :dispatches [[:phase/raising? (constantly true)]]}
   :phase/raising? {:handler raising
                    :dispatches [[:phase/raising?do-nothing
                                  (fn [{:game/keys [current-turn players]}]
                                    (= (get-in players [current-turn
                                                        :player/prompt])
                                       #{:phase/raising?do-nothing}))]]}
   :phase/raising?hatch {:handler (fn [{:game/keys [current-turn] :as state} _]
                                    (-> state
                                        (hatch-digi-egg current-turn)))
                         :dispatches [[:phase/main (constantly true)]]}
   :phase/raising?move-to-battle-area {:handler (fn [{:game/keys [current-turn]
                                                      :as state} _]
                                                  (-> state
                                                      (move-out-of-raising-area
                                                       current-turn)))
                                       :dispatches [[:phase/main
                                                     (constantly true)]]}
   :phase/raising?do-nothing {:handler (fn [{:game/keys [current-turn]
                                             :as state} _]
                                         state)
                              :dispatches [[:phase/main (constantly true)]]}
   :phase/main {:handler main-phase}
   :phase/main.play-digimon {:handler play-from-hand
                             :dispatches [[:phase/end
                                           (fn [{:game/keys [current-turn players]
                                                 :as state}]
                                             (neg? (get-in players
                                                           [current-turn
                                                            :player/memory])))]
                                          [:phase/main (constantly true)]]}
   :phase/main.play-tamer {:handler play-from-hand
                           :dispatches [[:phase/end
                                         (fn [{:game/keys [current-turn players]}]
                                           (neg? (get-in players
                                                         [current-turn
                                                          :player/memory])))]
                                        [:phase/main (constantly true)]]}
   :phase/main.play-option {:handler play-from-hand
                            :dispatches [[:phase/end
                                          (fn [{:game/keys [current-turn players]}]
                                            (neg? (get-in players
                                                          [current-turn
                                                           :player/memory])))]
                                         [:phase/main (constantly true)]]}
   :phase/main.digivolve {:handler digivolve
                          :dispatches [[:phase/end
                                        (fn [{:game/keys [current-turn players]}]
                                          (neg? (get-in players
                                                        [current-turn
                                                         :player/memory])))]
                                       [:phase/main (constantly true)]]}
   :phase/main.attack {:handler target-attack
                       :dispatches [[:game/end
                                     (fn [{:game/keys [current-turn players]}]
                                       (true? (get-in players
                                                      [current-turn
                                                       :player/wins?])))]
                                    [:phase/main.attack.counter
                                     (constantly false)]
                                    [:phase/main.attack.block?
                                     (fn [{:game/keys [moves current-turn players]}]
                                       (get-in players
                                               [current-turn
                                                :player/blocking?]))]
                                    [:phase/main (constantly true)]]}
   :phase/main.attack.block?
   {:handler (fn [{:game/keys [current-turn players] :as state} _]
               (-> state
                   (update-in [:game/players
                               current-turn]
                              dissoc
                              :player/blocking?)
                   (assoc-in [:game/players
                              current-turn
                              :player/prompt]
                             #{:phase/main.attack.block?accept
                               :phase/main.attack.block?decline})))}
   :phase/main.attack.block?accept
   {:handler (fn [{:game/keys [current-turn moves] :as state}
                  blocking-slot-id]
               (let [[attacking-slot-id _]
                     (->> moves
                          reverse
                          (filter (fn [[_ state-id _ & resources]]
                                    (= state-id :phase/main.attack)))
                          first
                          last)]
                 (-> state
                     pass-turn
                     (resolve-attack [attacking-slot-id blocking-slot-id]))))
    :dispatches [[:phase/main (constantly true)]]}
   :phase/main.attack.block?decline
   {:handler (fn [{:game/keys [current-turn moves] :as state} _]
               (let [[attacking-slot-id defending-slot-id]
                     (->> moves
                          reverse
                          (filter (fn [[_ state-id _ & resources]]
                                    (= state-id :phase/main.attack)))
                          first
                          last)]
                 (-> state
                     pass-turn
                     (resolve-attack [attacking-slot-id defending-slot-id]))))
    :dispatches [[:phase/main (constantly true)]]}
   :phase/end {:handler (fn [{:game/keys [current-turn moves players] :as state} _]
                          (cond-> state
                            (>= (get-in players [current-turn :player/memory]) 0)
                            (update-memory 3)
                            :else pass-turn))
               :dispatches [[:phase/unsuspend (constantly true)]]}
   :game/end {:handler (fn [state _] state)}})

(defn step
  [{:game/keys [current-turn next-turn] :as state} state-id resources]
  (let [handler (get-in fsm [state-id :handler])
        dispatches (get-in fsm [state-id :dispatches])
        prompt (or (get-in state [:game/players current-turn :player/prompt])
                   (get-in state [:game/players next-turn :player/prompt]))
        new-state (cond
                    (and prompt (not (contains? prompt state-id)))
                    state
                    (fn? handler)
                    (handler (cond-> (-> (merge state {})
                                         ;; TODO: Ensure invalid moves
                                         ;; don't show up in :game/moves
                                         (update :game/moves
                                                 (fnil conj [])
                                                 (cond-> [(Date.) state-id]
                                                   current-turn
                                                   (conj current-turn)
                                                   (and current-turn
                                                        resources)
                                                   (conj resources)))
                                         (assoc :game/current-state-id state-id))
                               (and prompt (contains? prompt state-id))
                               (update-in [:game/players current-turn]
                                          dissoc :player/prompt))
                             resources)
                    :else state)
        next-state-id (reduce (fn [next-state-id [state-id dispatch-fn]]
                                (cond
                                  next-state-id                   next-state-id
                                  (true? (dispatch-fn new-state)) state-id
                                  :else                           nil))
                              nil
                              dispatches)]
    (if next-state-id
      (step new-state next-state-id nil)
      new-state)))

(comment
  (db/import-from-file!)

  (-> (step nil
            :game/start
            [{:player/name "niamu"
              :player/deck-codec "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
             {:player/name "AI"
              :player/deck-codec "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}])
      (step :phase/mulligan?accept nil)
      (step :phase/mulligan?decline nil)
      (step :phase/raising?hatch nil)
      (step :phase/main.play-digimon ["card/en_ST2-03_P0"])
      (step :phase/raising?hatch nil)
      (step :phase/main.play-digimon ["card/en_ST1-03_P0"])
      (step :phase/main.digivolve ["card/en_ST1-07_P0" :zone/battle-area 1])
      (step :phase/main.play-tamer ["card/en_ST2-12_P0"])
      (step :phase/main.play-option ["card/en_ST2-13_P0"])
      (step :phase/main.digivolve ["card/en_ST2-07_P0" :zone/battle-area 1])
      (step :phase/main.attack [1 nil])
      #_(render/game-board {:player/name "niamu"}))

  (->> (db/q '{:find [[?t ...]]
               :where [[?c :card/highlights ?h]
                       [?c :card/language "ja"]
                       [?h :highlight/type :keyword-effect]
                       [?h :highlight/text ?t]
                       #_[(first ?t) ?f]
                       #_[(= ?f (first "＜"))]]})
       sort)

  (db/q '{:find [[(pull ?c [:card/number]) ...]]
          :where [[?c :card/highlights ?h]
                  [?c :card/language "ja"]
                  [?h :highlight/type :keyword-effect]
                  [?h :highlight/text "≪進撃≫"]]})

  (->> (db/q '{:find [[?t ...]]
               :where [[?c :card/highlights ?h]
                       [?c :card/image ?i]
                       [?i :image/language "en"]
                       [?h :highlight/type :precondition]
                       [?h :highlight/text ?t]]})
       sort))
