(ns dcg.simulator.server.render
  (:require
   [clojure.string :as string]
   [hiccup.page :as page]))

(defn playing-field
  [{:zone/keys [battle-area digi-eggs deck hand raising-area security trash]}
   current?]
  [:playing-field (when current? {:current true})
   [:section.security
    [:h4 (format "Security (%d)" (count security))]
    (when (pos? (count security))
      (list
       [:security-stack
        (repeat (count security)
                [:dcg-card {:deck-back true}])]
       (count security)))]
   [:section.battle-area
    [:h4 "Battle Area"]
    (->> battle-area
         (remove (fn [{:slot/keys [cards]}]
                   (or (every? (fn [{:card/keys [type]}]
                                 (= type "Option"))
                               cards)
                       (every? (fn [{:card/keys [type]}]
                                 (= type "Tamer"))
                               cards))))
         (map (fn [{:slot/keys [id suspended? summoning-sickness? cards]}]
                [:dcg-slot {:data-slot-id id}
                 (when suspended?
                   [:small "Suspended"])
                 (when summoning-sickness?
                   [:small "Summoning sickness"])
                 [:dcg-card-stack
                  (map (fn [{:card/keys [id type name number parallel-id image]}]
                         [:dcg-card
                          {(if (= (string/lower-case type)
                                  "digi-egg")
                             :digi-eggs-back
                             :deck-back) true}
                          [:img
                           {:src (-> image
                                     :image/path
                                     (string/replace #"^resources" ""))
                            :width 430
                            :height 600
                            :alt (string/join " " [number name])}]])
                       cards)]
                 [:small (format "Slot #%d" id)]])))]
   [:section.deck
    [:h4 "Deck"]
    [:dcg-card {:deck-back true}]
    (count deck)]
   [:section.raising-area
    [:h4 "Raising Area"]
    [:dcg-card {:digi-eggs-back true}]
    (let [cards (some-> raising-area
                        peek
                        :slot/cards)]
      (when (not (empty? cards))
        [:dcg-card-stack
         (map (fn [{:card/keys [id type name number parallel-id image]}]
                [:dcg-card
                 {(if (= (string/lower-case type)
                         "digi-egg")
                    :digi-eggs-back
                    :deck-back) true}
                 [:img
                  {:src (-> image
                            :image/path
                            (string/replace #"^resources" ""))
                   :width 430
                   :height 600
                   :alt (string/join " " [number name])}]])
              cards)]))]
   [:section.tamer-option-area
    [:h4 "Tamer/Option"]
    (->> battle-area
         (filter (fn [{:slot/keys [cards]}]
                   (or (every? (fn [{:card/keys [type]}]
                                 (= type "Option"))
                               cards)
                       (every? (fn [{:card/keys [type]}]
                                 (= type "Tamer"))
                               cards))))
         (map (fn [{:slot/keys [id suspended? summoning-sickness? cards]}]
                [:dcg-slot {:data-slot-id id}
                 (when suspended?
                   [:small "Suspended"])
                 (when summoning-sickness?
                   [:small "Summoning sickness"])
                 [:dcg-card-stack
                  (map (fn [{:card/keys [id type name number parallel-id image]}]
                         [:dcg-card
                          {(if (= (string/lower-case type)
                                  "digi-egg")
                             :digi-eggs-back
                             :deck-back) true}
                          [:img
                           {:src (-> image
                                     :image/path
                                     (string/replace #"^resources" ""))
                            :width 430
                            :height 600
                            :alt (string/join " " [number name])}]])
                       cards)]
                 [:small (format "Slot #%d" id)]])))]
   [:section.trash
    [:h4 "Trash"]
    (let [{:card/keys [type number name image] :as last-trashed-card} (last trash)]
      (when last-trashed-card
        (list
         [:dcg-card
          {(if (= (string/lower-case type)
                  "digi-egg")
             :digi-eggs-back
             :deck-back) true}
          [:img
           {:src (-> image
                     :image/path
                     (string/replace #"^resources" ""))
            :width 430
            :height 600
            :alt (string/join " " [number name])}]]
         (count trash))))]
   #_[:section.hand
      [:h4 (format "Hand (%d)" (count hand))]
      (let [cards hand]
        (when (not (empty? cards))
          (map (fn [{:card/keys [id type name number parallel-id image]}]
                 [:dcg-card
                  {:deck-back true}
                  (when current?
                    [:img
                     {:src (-> image
                               :image/path
                               (string/replace #"^resources" ""))
                      :width 430
                      :height 600
                      :alt (string/join " " [number name])}])])
               cards)))]])

(defn player
  [{:player/keys [id name deck-codec deck zones ready? prompt] :as p} current?]
  [:section.player
   (when current?
     {:class "current"})
   [:h2 name]
   [:h3 "Deck Name"]
   [:p (:deck/name deck)]
   [:h3 "Prompt"]
   [:p (pr-str prompt)]
   (playing-field zones current?)])

(defn memory-guage
  [memory]
  [:section.memory
   [:h2 "Memory"]
   [:memory-guage
    [:ul
     [:ul
      (map (fn [n]
             [:li
              n
              (when (= memory n)
                [:memory-counter
                 {:aria-label (format "%d available memory" memory)}])])
           (range 10 0 -1))]
     [:li 0
      (when (zero? memory)
        [:memory-counter
         {:aria-label (format "%d available memory" memory)}])]
     [:ul
      (map (fn [n]
             [:li
              [:span.sr-only "-"]
              (* n -1)
              (when (= memory n)
                [:memory-counter
                 {:aria-label (format "%d available memory" memory)}])])
           (range -1 -11 -1))]]]])

(defn game-board
  [{:game/keys [moves players]} active-player-id]
  (page/html5
   {:mode :html}
   [:head
    [:title "Digimon Card Game"]
    (page/include-css "/css/style.css")]
   [:body
    [:ul#moves
     (for [[inst state player-uuid resources] moves]
       [:li (pr-str [#_(str inst)
                     (get-in players [player-uuid :player/name])
                     state
                     resources])])]
    (let [[active-player opponent-player]
          (->> players
               (sort-by (fn [[uuid player]]
                          (if (= active-player-id
                                 (:player/id player))
                            0
                            1)))
               vals)]
      [:div#board
       (memory-guage (:player/memory active-player))
       (player active-player (= active-player-id
                                (:player/id active-player)))
       (player opponent-player (= active-player-id
                                  (:player/id opponent-player)))])]))
