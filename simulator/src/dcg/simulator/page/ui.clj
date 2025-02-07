(ns dcg.simulator.page.ui
  (:require
   [clojure.string :as string]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.helpers :as helpers]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom-server :as dom]
   [ring.middleware.anti-forgery :as anti-forgery]))

(declare Stack ui-stack
         Game)

(defsc Action
  [this {params :action/params
         [action-key _ original-params :as action] :action/action
         :as props}]
  {:query [:action/action
           :action/params]}
  (dom/li
   (dom/form
    {:method "POST"}
    (dom/input {:type "hidden"
                :name "__anti-forgery-token"
                :value anti-forgery/*anti-forgery-token*})
    (dom/input {:type "hidden"
                :name "action"
                :value (str action-key)})
    (dom/input {:type "hidden"
                :name "params"
                :value (pr-str original-params)})
    (case action-key
      :action/play
      (dom/button {:type "submit"}
                  (format "Play for a cost of %d"
                          (get-in params [::card/card :card/play-cost])))
      :action/use
      (dom/button {:type "submit"}
                  (format "Use for a cost of %d"
                          (get-in params [::card/card :card/use-cost])))
      :action/digivolve
      (let [stack-uuid (-> original-params last last)]
        [(dom/style
          (format
           (str "dcg-board:has(button[data-stack=\"%s\"]:hover) "
                "dcg-stack[data-stack=\"%s\"] {"
                " box-shadow: var(--card-glow-small), var(--card-glow-large);"
                "}")
           stack-uuid
           stack-uuid))
         (dom/button
          {:type "submit"
           :data-stack (str stack-uuid)}
          (format "Digivolve onto %s for a cost of %d"
                  (get-in (last params)
                          [::stack/cards
                           0
                           ::card/card
                           :card/name])
                  (get-in (vec params)
                          [0
                           ::card/card
                           :card/digivolution-requirements
                           (second original-params)
                           :digivolve/cost]))
          (ui-stack (last params)))])
      :action/move
      (dom/button {:type "submit"} "Move to Battle Area")
      :action/attack.declare
      (let [attack-stack (-> original-params last last)]
        [(dom/style
          (format
           (str "dcg-board:has(button[data-stack=\"%s\"]:hover) "
                "dcg-stack[data-stack=\"%s\"] {"
                " box-shadow: var(--card-glow-small), var(--card-glow-large);"
                "}")
           attack-stack
           attack-stack))
         (dom/button
          {:type "submit"
           :data-stack attack-stack}
          "Attack"
          (if (= (-> original-params last first)
                 ::player/id)
            (str " " (-> params last ::player/name))
            (ui-stack (last params))))])))))

(def ui-action (comp/factory Action {:keyfn (fn [params] (hash params))}))

(defsc Card
  [this {::card/keys [uuid privacy actions]
         {:card/keys [id parallel-id category number color rarity image language
                      level dp play-cost use-cost form attribute block-icon
                      effect inherited-effect security-effect notes
                      digivolution-requirements]
          :as card} ::card/card}]
  {:ident ::card/uuid
   :query [::card/uuid
           {::card/actions (comp/get-query Action)}
           ::card/privacy
           {::card/card [:card/id
                         :card/name
                         :card/number
                         :card/category
                         :card/parallel-id
                         :card/level
                         :card/dp
                         :card/play-cost
                         :card/use-cost
                         :card/language
                         :card/form
                         :card/attribute
                         :card/type
                         :card/rarity
                         :card/block-icon
                         :card/notes
                         {:card/color [:color/index
                                       :color/color]}
                         {:card/digivolution-requirements [:digivolve/index
                                                           :digivolve/level
                                                           :digivolve/color
                                                           :digivolve/cost]}
                         {:card/releases [:release/name
                                          :release/genre
                                          :release/date]}
                         {:card/image [:image/path]}
                         {:card/highlights [:highlight/index
                                            :highlight/field
                                            :highlight/type
                                            :highlight/text]}
                         :card/effect
                         :card/inherited-effect
                         :card/security-effect]}]}
  #_(dom/pre (with-out-str (clojure.pprint/pprint actions)))
  (dom/element
   (cond-> {:tag :dcg-card}
     id
     (assoc
      :attrs {:lang language}
      :children
      [(dom/div
        {:itemscope "" :itemprop "dcg-card"}
        (dom/div
         {:itemscope "" :itemprop "brand"}
         (dom/data {:itemprop "name" :value "Digimon Card Game"}
                   "Digimon Card Game")
         (dom/data {:itemprop "url" :value "https://digimoncard.com/global"}
                   "https://digimoncard.com/global"))
        (dom/data {:itemprop "id" :value id} id)
        (dom/data {:itemprop "name" :value (:card/name card)} (:card/name card))
        (dom/data {:itemprop "number" :value number} number)
        (when parallel-id
          (dom/data {:itemprop "parallel-id" :value parallel-id} parallel-id))
        (dom/data {:itemprop "category" :value category} category)
        (dom/data {:itemprop "rarity" :value rarity} rarity)
        (let [color (->> color
                         (sort-by :color/index)
                         (map (comp name :color/color))
                         (string/join "/"))]
          (dom/data {:itemprop "color" :value color} color))
        (dom/data {:itemprop "image" :value (:image/path image)}
                  (:image/path image))
        (when level
          (dom/data {:itemprop "level" :value level} level))
        (when dp
          (dom/data {:itemprop "DP" :value dp} dp))
        (when play-cost
          (dom/data {:itemprop "play-cost" :value play-cost} play-cost))
        (when use-cost
          (dom/data {:itemprop "use-cost" :value use-cost} use-cost))
        (when form
          (dom/data {:itemprop "form" :value form} form))
        (when attribute
          (dom/data {:itemprop "attribute" :value attribute} attribute))
        (when-let [type (:card/type card)]
          (dom/data {:itemprop "type" :value type} type))
        (when effect
          (dom/data {:itemprop "effect" :value effect} effect))
        (when inherited-effect
          (dom/data {:itemprop "inherited-effect" :value inherited-effect}
                    inherited-effect))
        (when security-effect
          (dom/data {:itemprop "security-effect" :value security-effect}
                    security-effect))
        (when notes
          (dom/data {:itemprop "notes" :value notes} notes))
        (when block-icon
          (dom/data {:itemprop "block-icon" :value block-icon} block-icon))
        (when digivolution-requirements
          (dom/div
           (map (fn [{:digivolve/keys [level color cost] :as digivolve}]
                  (let [color (->> color
                                   sort
                                   (map name)
                                   (string/join "/"))]
                    (dom/div
                     {:itemscope ""
                      :itemprop "dcg-digivolution-requirment"}
                     (dom/data {:itemprop "level" :value level} level)
                     (dom/data {:itemprop "color" :value color} color)
                     (dom/data {:itemprop "cost" :value cost} cost))))
                digivolution-requirements))))
       (dom/picture
        (dom/source {:srcset (:image/path image)})
        (dom/img {:width 430 :height 600 :draggable "false"
                  :alt (format "%s %s" number (:card/name card))}))
       (when (seq actions)
         [(dom/button {:popovertarget (str uuid "-actions")}
                      "See actions")
          (dom/ul
           {:popover true
            :id (str uuid "-actions")}
           (map ui-action actions))])]))))

(def ui-card (comp/factory Card {:keyfn ::card/uuid}))

(defsc Stack
  [this {::stack/keys [actions cards uuid summoned? suspended?] :as stack}]
  {:ident ::stack/uuid
   :query [::stack/uuid
           ::stack/summoned?
           ::stack/suspended?
           {::stack/actions (comp/get-query Action)}
           {::stack/cards (comp/get-query Card)}]}
  (dom/element
   {:tag :dcg-stack
    :attrs {:data-stack (str uuid)}
    :children [(when (seq actions)
                 [(dom/button {:popovertarget (str uuid "-actions")}
                              "See actions")
                  (dom/ul {:popover true
                           :id (str uuid "-actions")}
                          (map ui-action actions))])
               (map ui-card cards)]}))

(def ui-stack (comp/factory Stack {:keyfn ::stack/uuid}))

(defsc DigiEggs
  [this {::area/keys [privacy of-player cards actions]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           ::area/actions
           {::area/cards (comp/get-query Card)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:digi-eggs ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Digi-Eggs (%d)" (count cards)))
               (if (and (seq actions)
                        (contains? actions
                                   [:action/hatch [::player/id of-player] nil]))
                 (dom/form
                  {:method "POST"}
                  (dom/input {:type "hidden"
                              :name "__anti-forgery-token"
                              :value anti-forgery/*anti-forgery-token*})
                  (dom/input {:type "hidden"
                              :name "action"
                              :value (str :action/hatch)})
                  (dom/element
                   {:tag :dcg-card
                    :children [(dom/button
                                {:type "submit"}
                                "Hatch Digi-Egg")]}))
                 (when (pos? (count cards))
                   (ui-card (first cards))))]}))

(def ui-digi-eggs
  (comp/factory DigiEggs {:keyfn (fn [{::area/keys [of-player]}]
                                   (str "area/digi-eggs_" of-player))}))

(defsc Deck
  [this {::area/keys [privacy cards]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/cards (comp/get-query Card)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:deck ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Deck (%d)" (count cards)))
               (when (pos? (count cards))
                 (->> cards
                      (take 1)
                      (map ui-card)))]}))

(def ui-deck
  (comp/factory Deck {:keyfn (fn [{::area/keys [of-player]}]
                               (str "area/deck_" of-player))}))

(defsc Battle
  [this {::area/keys [privacy stacks]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/stacks (comp/get-query Stack)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:battle ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Battle Area (%d)" (count stacks)))
               (dom/div :.scrollable-container
                        (map ui-stack stacks))]}))

(def ui-battle
  (comp/factory Battle {:keyfn (fn [{::area/keys [of-player]}]
                                 (str "area/battle_" of-player))}))

(defsc TamerAndOption
  [this {::area/keys [privacy stacks]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/stacks (comp/get-query Stack)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:tamer-option ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Tamer/Option Area (%d)"
                                         (count stacks)))
               (dom/div :.scrollable-container
                        (map ui-stack stacks))]}))

(def ui-tamer-and-option
  (comp/factory TamerAndOption
                {:keyfn (fn [{::area/keys [of-player]}]
                          (str "area/tamer-and-option_" of-player))}))

(defsc Breeding
  [this {::area/keys [privacy stacks]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/stacks (comp/get-query Stack)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:breeding ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only "Breeding Area")
               (map ui-stack stacks)]}))

(def ui-breeding
  (comp/factory Breeding {:keyfn (fn [{::area/keys [of-player]}]
                                   (str "area/breeding_" of-player))}))

(defsc Trash
  [this {::area/keys [privacy cards]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/cards (comp/get-query Card)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:trash ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Trash (%d)" (count cards)))
               (->> cards
                    (take 1)
                    (map ui-card))]}))

(def ui-trash
  (comp/factory Trash {:keyfn (fn [{::area/keys [of-player]}]
                                (str "area/trash_" of-player))}))

(defsc Security
  [this {::area/keys [privacy cards]}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           {::area/cards (comp/get-query Card)}]}
  (dom/element
   {:tag :dcg-area
    :attrs {:security ""
            :privacy privacy}
    :children [(dom/h3 :.sr-only (format "Security (%d)" (count cards)))
               (map ui-card cards)]}))

(def ui-security
  (comp/factory Security {:keyfn (fn [{::area/keys [of-player]}]
                                   (str "area/security_" of-player))}))

(defsc Hand
  [this {::area/keys [privacy cards of-player actions]
         :as params}]
  {:query [::area/name
           ::area/privacy
           ::area/of-player
           ::area/actions
           {::area/cards (comp/get-query Card)}]}
  (let [hand (dom/element
              {:tag :dcg-area
               :attrs {:hand ""
                       :privacy privacy}
               :children [(dom/h3 :.sr-only (format "Hand (%d)" (count cards)))
                          (dom/div :.scrollable-container
                                   (map ui-card cards))]})]
    (if-let [re-draw-actions (->> actions
                                  (filter (fn [[action-key _ params]]
                                            (= action-key :action/re-draw?)))
                                  seq)]
      (dom/dialog
       {:open true}
       (dom/form
        {:method "POST"}
        (dom/div (dom/p (dom/strong "Re-draw Hand?")) hand)
        (dom/input {:type "hidden"
                    :name "__anti-forgery-token"
                    :value anti-forgery/*anti-forgery-token*})
        (dom/input {:type "hidden"
                    :name "action"
                    :value (str :action/re-draw?)})
        (->> re-draw-actions
             (sort-by (fn [[_ _ param]] param))
             (map (fn [[_ _ param]]
                    (dom/button (cond-> {:type "submit"
                                         :name "params"
                                         :value (str param)}
                                  (= param false) (assoc :autofocus true))
                                (case param
                                  true "Yes"
                                  false "No"
                                  "")))))))
      hand)))

(def ui-hand
  (comp/factory Hand {:keyfn (fn [{::area/keys [of-player]}]
                               (str "area/hand_" of-player))}))

(defsc Player
  [this {{::area/keys [security
                       battle
                       tamer-and-option
                       deck
                       digi-eggs
                       breeding
                       hand
                       trash]} ::player/areas
         ::player/keys [actions]
         :as player}]
  {:ident ::player/id
   :query [::player/id
           ::player/name
           ::player/deck-code
           ::player/language
           ::player/memory
           ::player/actions
           {::player/areas
            [{::area/digi-eggs (comp/get-query DigiEggs)}
             {::area/deck (comp/get-query Deck)}
             {::area/breeding (comp/get-query Breeding)}
             {::area/tamer-and-option (comp/get-query TamerAndOption)}
             {::area/trash (comp/get-query Trash)}
             {::area/battle (comp/get-query Battle)}
             {::area/security (comp/get-query Security)}
             {::area/hand (comp/get-query Hand)}]}]}
  (let [{::game/keys [players]
         me :current/user} (comp/props (comp/get-parent this))
        spectator? (not (contains? (->> players
                                        (map ::player/id)
                                        (into #{}))
                                   (::player/id me)))]
    (dom/element
     {:tag :dcg-player-perspective
      :attrs {:data-opponent (if spectator?
                               (str (not= (::player/id player)
                                          (get-in players
                                                  [0 ::player/id])))
                               (str (not= (::player/id player)
                                          (::player/id me))))}
      :children [(when (seq actions)
                   (dom/form
                    {:method "POST"}
                    (dom/input {:type "hidden"
                                :name "__anti-forgery-token"
                                :value anti-forgery/*anti-forgery-token*})
                    (map (fn [[action-key _ params]]
                           [(dom/input {:type "hidden"
                                        :name "action"
                                        :value (str action-key)})
                            (when params
                              (dom/input {:type "hidden"
                                          :name "params"
                                          :value (pr-str params)}))
                            (dom/button {:type "submit"}
                                        (case action-key
                                          :phase/main "Move to Main Phase"
                                          :action/pass "Pass Turn"
                                          :action/attack.counter "No Counter"))])
                         actions)))
                 (dom/element
                  {:tag :dcg-playmat
                   :children [(ui-security security)
                              (ui-battle battle)
                              (ui-tamer-and-option tamer-and-option)
                              (ui-deck deck)
                              (ui-digi-eggs digi-eggs)
                              (ui-breeding breeding)
                              (ui-hand hand)
                              (ui-trash trash)]})]})))

(def ui-player (comp/factory Player {:keyfn ::player/id}))

(defsc Game
  [this {{::player/keys [memory] :as me} :current/user
         {::game-in/keys [turn-index]} ::game/in
         ::game/keys [available-actions log players] :as game}]
  {:ident ::game/id
   :query [{:current/user [::player/id
                           ::player/name
                           ::player/memory]}
           ::game/id
           ::game/log
           ::game/turn-counter
           ::game/constraint-code
           ::game/available-actions
           ::game/pending-effects
           {::game/in [::game-in/turn-index
                       ::game-in/state-id]}
           {::game/players (comp/get-query Player)}]}
  (let [spectator? (not (contains? (->> players
                                        (map ::player/id)
                                        (into #{}))
                                   (::player/id me)))
        current-turn (nth players turn-index)
        ;; TODO: This will need adjusting to support more than 2 players
        player-filter (fn [{::player/keys [id]}]
                        (if spectator?
                          (= id (get-in players [0 ::player/id]))
                          (= id (::player/id me))))]
    (dom/div
     (dom/h1 (dom/a {:href "/"} "Heroicc"))
     (dom/h2 (get me ::player/name
                  (format "Spectating. %s's turn"
                          (::player/name current-turn))))
     (dom/p (format "Memory: %d"
                    (if spectator?
                      (::player/memory current-turn)
                      memory)))
     (when (and (not spectator?)
                (empty? available-actions)
                (not= (get-in game [::game/in ::game-in/state-id])
                      :game/end))
       (dom/p "Waiting for opponent..."))
     (dom/element
      {:tag :dcg-board
       :children [(dom/div :.scrollable-container
                           (->> players
                                (remove player-filter)
                                (map ui-player)))
                  (dom/div :.scrollable-container
                           (->> players
                                (filter player-filter)
                                (map ui-player)))]})
     (when-let [[_ _ [_ winner-id]] (and (-> log last first
                                             (= :game/end))
                                         (last log))]
       (dom/dialog
        {:open true}
        (dom/form
         {:method "dialog"}
         (dom/button {:autofocus true} "Close"))
        (if spectator?
          (dom/p (format "%s Wins!" (get-in (helpers/players-by-id players)
                                            [ winner-id
                                             ::player/name])))
          (dom/p (str "You "
                      (if (= winner-id (get me ::player/id))
                        "Win"
                        "Lose"))))
        (dom/ul
         (->> players
              (remove (fn [{::player/keys [id]}]
                        (= id (::player/id me))))
              (map (fn [{::player/keys [deck-code] :as player}]
                     (dom/li (format "%s's deck code: %s"
                                     (::player/name player)
                                     deck-code)))))))))))

(def ui-game (comp/factory Game {:keyfn ::game/id}))
