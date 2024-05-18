(ns dcg.simulator.server.render
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.state :as state]
   [hiccup.page :as page]
   [ring.middleware.anti-forgery :as anti-forgery]))

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

(defn card-component
  [uuid {:card/keys [id category number color rarity image language] :as card}]
  [:dcg-card {:lang language}
   [:div
    {:itemscope ""
     :itemprop "dcg-card"}
    [:div {:itemscope ""
           :itemprop "brand"}
     [:data {:itemprop "name" :value "Digimon Card Game"} "Digimon Card Game"]
     [:data {:itemprop "url" :value "https://digimoncard.com/global"}
      "https://digimoncard.com/global"]]
    [:data {:itemprop "id" :value id} id]
    [:data {:itemprop "uuid" :value uuid} uuid]
    [:data {:itemprop "name" :value (:card/name card)} (:card/name card)]
    [:data {:itemprop "number" :value number} number]
    (when-let [parallel-id (:card/parallel-id card)]
      [:data {:itemprop "parallel-id" :value parallel-id} parallel-id])
    [:data {:itemprop "category" :value category} category]
    [:data {:itemprop "rarity" :value rarity} rarity]
    (let [color (->> color
                     (sort-by :color/index)
                     (map (comp name :color/color))
                     (string/join "/"))]
      [:data {:itemprop "color" :value color} color])
    [:data {:itemprop "image" :value (:image/path image)} (:image/path image)]
    (when-let [level (:card/level card)]
      [:data {:itemprop "level" :value level} level])
    (when-let [dp (:card/dp card)]
      [:data {:itemprop "DP" :value dp} dp])
    (when-let [play-cost (:card/play-cost card)]
      [:data {:itemprop "play-cost" :value play-cost} play-cost])
    (when-let [use-cost (:card/use-cost card)]
      [:data {:itemprop "use-cost" :value use-cost} use-cost])
    (when-let [form (:card/form card)]
      [:data {:itemprop "form" :value form} form])
    (when-let [attribute (:card/attribute card)]
      [:data {:itemprop "attribute" :value attribute} attribute])
    (when-let [type (:card/type card)]
      [:data {:itemprop "type" :value type} type])
    (when-let [effect (:card/effect card)]
      [:data {:itemprop "effect" :value effect} effect])
    (when-let [inherited-effect (:card/inherited-effect card)]
      [:data {:itemprop "inherited-effect" :value inherited-effect}
       inherited-effect])
    (when-let [security-effect (:card/security-effect card)]
      [:data {:itemprop "security-effect" :value security-effect}
       security-effect])
    (when-let [notes (:card/notes card)]
      [:data {:itemprop "notes" :value notes} notes])
    (when-let [block-icon (:card/block-icon card)]
      [:data {:itemprop "block-icon" :value block-icon} block-icon])
    (when-let [digivolution-requirements (:card/digivolution-requirements card)]
      [:div
       (map (fn [{:digivolve/keys [level color cost] :as digivolve}]
              (let [color (->> color
                               sort
                               (map name)
                               (string/join "/"))]
                [:div {:itemscope ""
                       :itemprop "dcg-digivolution-requirment"}
                 [:data {:itemprop "level" :value level} level]
                 [:data {:itemprop "color" :value color} color]
                 [:data {:itemprop "cost" :value cost} cost]]))
            digivolution-requirements)])]
   [:picture
    [:source {:srcset (:image/path image)}]
    [:img {:width 430 :height 600 :draggable "false"
           :alt (format "%s %s" number (:card/name card))}]]])

(def card-color
  {:red     "#E90022"
   :blue    "#189fed"
   :yellow  "#FFDE00"
   :green   "#009B6B"
   :white   "#FFF"
   :black   "#111"
   :purple  "#6353A5"})

(def rarities
  {"C"   "Common"
   "U"   "Uncommon"
   "R"   "Rare"
   "SR"  "Super Rare"
   "SEC" "Secret Rare"
   "P"   "Promo"})

(defn rarity-icon
  [rarity color & [{:keys [height x y bg]
                    :or {height 11.5
                         x 0
                         y 0}}]]
  (let [width (if (= rarity "SEC")
                (* height (/ 18 11.5))
                height)
        height height
        fg (get card-color color)
        bg (or bg (if (or (= :yellow color)
                          (= :white color))
                    (get card-color :black)
                    (get card-color :white)))]
    [:svg {:viewBox (str "0 0 " width " " height)
           :x x
           :y y
           :width width
           :height height
           :role "img"
           :aria-label (get rarities rarity)}
     [:rect {:x 0
             :y 0
             :rx (float (/ height 2))
             :ry (float (/ height 2))
             :width width
             :height height
             :fill bg}]
     [:text {:font-family "Roboto Condensed"
             :font-size (* height (/ 9 11.5))
             :font-weight 700
             :x (float (/ width 2))
             :y (float (+ (/ height 1.4) (* (/ 0.5 11.5) height)))
             :fill fg
             :text-anchor "middle"} rarity]]))

(defn compact-card-component
  [{:card/keys [id name level block-icon rarity language category number
                image color type attribute form] :as card}]
  (let [digimon? (or (= category "デジモン")
                     (= category "Digimon")
                     (= category "数码宝贝")
                     (= category "디지몬")
                     (= category "デジタマ")
                     (= category "Digi-Egg")
                     (= category "数码蛋")
                     (= category "디지타마"))]
    [:svg.condensed-card
     {:viewBox "0 0 383 46"
      :role "img"
      :aria-label (str number " " name)
      :width 383
      :height 46}
     [:defs
      [:mask {:id (str "mask-" id)}
       [:polygon {:points "7,0 375,0 382,7 382,38 375,45 7,45 0,38 0,7"
                  :fill "#FFF"}]]
      (let [colors (->> color
                        (sort-by :color/index)
                        (mapv :color/color))]
        (->> (if (> (count colors) 1)
               (partition 2 1 colors)
               [(repeat 2 (first colors))])
             (reduce (fn [accl [c1 c2]]
                       (let [idx (inc (count accl))]
                         (conj accl
                               [[:stop
                                 {:offset (float (* (/ 1 (count colors)) idx))
                                  :stop-color (get card-color c1)}]
                                [:stop
                                 {:offset (float (* (/ 1 (count colors)) idx))
                                  :stop-color (get card-color c2)}]])))
                     [])
             (apply concat)
             (apply conj [:linearGradient
                          {:id (str "gradient-" id) :x2 1 :y2 0}])))]
     [:polygon {:points "7,0 376,0 383,7 383,39 376,46 7,46 0,39 0,7"
                :fill "rgba(0,0,0,0.1)"}]
     [:g {:mask (str "url(#mask-" id ")")
          :transform "translate(0.5,0.5)"}
      [:polygon {:points "7,0 375,0 382,7 382,38 375,45 7,45 0,38 0,7"
                 :fill (str "url(#gradient-" id ")")}]
      (when (some string? [form attribute type])
        [:polygon {:points (if digimon?
                             (if (< (or block-icon 0) 3)
                               "0,4 58,4 88,34 382,34 382,45 0,45"
                               "0,4 54,4 60,11 60,34 382,34 382,45 0,45")
                             (if (< (or block-icon 0) 3)
                               "0,34, 382,34 382,45 0,45"
                               "302,45, 312,34, 382,34, 382,45 302,45"))
                   :fill (if (= (-> color first :color/color) :black)
                           (get card-color :white)
                           (get card-color :black))}])
      (when digimon?
        [:text {:font-family "Prohibition"
                :fill (if (not= (-> color first :color/color) :black)
                        (get card-color :white)
                        (get card-color :black))
                :font-size 24
                :font-weight 700
                :x 11
                :y 36}
         [:tspan "L"]
         [:tspan {:font-size 18} "v"]
         [:tspan "."]
         [:tspan {:font-size 32} (or level "-")]])
      (if (or (and (= "en" language)
                   (< (count name) 55))
              (and (not= "en" language)
                   (< (count name) 28)))
        [:text {:lang language
                :font-family "Peter-Black"
                :fill (if (and (or (= (-> color first :color/color) :yellow)
                                   (= (-> color first :color/color) :white))
                               (< (count color) 3))
                        (get card-color :black)
                        (get card-color :white))
                :style {:paint-order "stroke"}
                :stroke (when (and (> (count color) 1)
                                   (contains? (set (map :color/color color))
                                              :yellow))
                          (if (and (= (-> color first :color/color) :yellow)
                                   (< (count color) 3))
                            "#FFF"
                            "#000"))
                :stroke-width (when (> (count color) 1) 3)
                :font-size (cond-> 22
                             (and (not= "en" language)
                                  (>= (count name) 9))
                             (- (/ (count name) 2.5)))
                :lengthAdjust "spacingAndGlyphs"
                :textLength (cond
                              (and (> (count name) 13)
                                   digimon?) 205
                              (and (> (count name) 20)
                                   (not digimon?)) 220
                              :else nil)
                :font-weight 800
                :x (if digimon?
                     (if (and (or (= "ja" language)
                                  (= "ko" language))
                              (< (or block-icon 0) 3))
                       85
                       200)
                     195)
                :y (cond-> 29
                     (some string? [form attribute type])
                     (- 3))
                :text-anchor (if (and digimon?
                                      (or (= "ja" language)
                                          (= "ko" language))
                                      (< (or block-icon 0) 3))
                               "start"
                               "middle")} name]
        (let [[line-1 line-2] (if (= "en" language)
                                (-> (string/split name #",")
                                    (as-> #__ splits
                                      (->> splits
                                           (split-at (quot (count splits) 2))
                                           (mapv (fn [part]
                                                   (->> part
                                                        (map #(str % ","))
                                                        string/join)))
                                           (#(update-in % [1]
                                                        (fn [s]
                                                          (string/replace s
                                                                          #",$"
                                                                          "")))))))
                                (->> (split-at (quot (count name) 2) name)
                                     (map #(apply str %))))]
          [:g
           [:text {:lang language
                   :font-family "Peter-Black"
                   :fill (if (and (or (= (-> color first :color/color) :yellow)
                                      (= (-> color first :color/color) :white))
                                  (< (count color) 3))
                           (get card-color :black)
                           (get card-color :white))
                   :style {:paint-order "stroke"}
                   :stroke (when (and (> (count color) 1)
                                      (contains? (set (map :color/color color))
                                                 :yellow))
                             (if (and (= (-> color first :color/color) :yellow)
                                      (< (count color) 3))
                               "#FFF"
                               "#000"))
                   :stroke-width (when (> (count color) 1) 3)
                   :font-size 12
                   :lengthAdjust "spacingAndGlyphs"
                   :textLength (cond
                                 (and (> (count line-1) 13)
                                      digimon?) 205
                                 (and (> (count line-1) 20)
                                      (not digimon?)) 220
                                 :else nil)
                   :font-weight 800
                   :x (if digimon?
                        (if (and (or (= "ja" language)
                                     (= "ko" language))
                                 (< (or block-icon 0) 3))
                          85
                          200)
                        195)
                   :y (cond-> 22
                        (some string? [form attribute type])
                        (- 3))
                   :text-anchor (if (and digimon?
                                         (or (= "ja" language)
                                             (= "ko" language))
                                         (< (or block-icon 0) 3))
                                  "start"
                                  "middle")} line-1]
           [:text {:lang language
                   :font-family "Peter-Black"
                   :fill (if (and (or (= (-> color first :color/color) :yellow)
                                      (= (-> color first :color/color) :white))
                                  (< (count color) 3))
                           (get card-color :black)
                           (get card-color :white))
                   :style {:paint-order "stroke"}
                   :stroke (when (and (> (count color) 1)
                                      (contains? (set (map :color/color color))
                                                 :yellow))
                             (if (and (= (-> color first :color/color) :yellow)
                                      (< (count color) 3))
                               "#FFF"
                               "#000"))
                   :stroke-width (when (> (count color) 1) 3)
                   :font-size 12
                   :lengthAdjust "spacingAndGlyphs"
                   :textLength (cond
                                 (and (> (count line-2) 13)
                                      digimon?) 205
                                 (and (> (count line-2) 20)
                                      (not digimon?)) 220
                                 :else nil)
                   :font-weight 800
                   :x (if digimon?
                        (if (and (or (= "ja" language)
                                     (= "ko" language))
                                 (< (or block-icon 0) 3))
                          85
                          200)
                        195)
                   :y (cond-> 35
                        (some string? [form attribute type])
                        (- 3))
                   :text-anchor (if (and digimon?
                                         (or (= "ja" language)
                                             (= "ko" language))
                                         (< (or block-icon 0) 3))
                                  "start"
                                  "middle")} line-2]]))
      [:text {:font-family "Roboto Condensed"
              :fill (if (or (= (-> color last :color/color) :yellow)
                            (= (-> color last :color/color) :white))
                      (get card-color :black)
                      (get card-color :white))
              :font-size 13.5
              :font-weight 700
              :x (cond-> (cond-> 359
                           (and (= rarity "SEC")
                                (< (or block-icon 0) 3)) (- 6)
                           (>= (or block-icon 0) 3) (+ 15)))
              :y 14
              :text-anchor "end"} number]
      (rarity-icon rarity
                   (-> color last :color/color)
                   (if (>= (or block-icon 0) 3)
                     {:x (cond-> 336
                           (= rarity "SEC")
                           (- 7))
                      :y 19}
                     {:x (cond-> 360
                           (= rarity "SEC")
                           (- 6))
                      :y 3.5}))
      (when block-icon
        [:g
         [:polygon
          (cond-> {:points "349,24.5 353,18 370,18 374,24.5 370,31 353,31"
                   :fill "#FFF"}
            (= :white (-> color last :color/color))
            (assoc :stroke-width 0.5
                   :stroke "#111"))]
         [:text {:font-family "Roboto Condensed"
                 :fill "#111"
                 :font-size 13.5
                 :font-weight 700
                 :x 362
                 :y 29.25
                 :text-anchor "middle"}
          (format "%02d" block-icon)]])
      (when (some string? [form attribute type])
        [:text {:lang language
                :font-family "Roboto"
                :font-size 8
                :font-weight 900
                :kerning 1
                :x 372
                :y 42
                :fill (if (= (-> color first :color/color) :black)
                        (get card-color :black)
                        (get card-color :white))
                :text-anchor "end"}
         (->> [form attribute type]
              (remove nil?)
              (string/join " | "))])]]))

(defn playmat
  [{::game/keys [available-actions players] :as game} player]
  (let [{::player/keys [areas] :as me} (get (state/players-by-id players)
                                            (::player/id player))]
    [:dcg-playmat
     ;; Security
     (let [{::area/keys [privacy cards]} (get areas ::area/security)]
       [:dcg-area
        {::area/security ""
         :privacy privacy}
        [:h3.sr-only (format "Security (%d)" cards)]
        (list (repeat cards [:dcg-card]))])
     ;; Battle
     (let [{::area/keys [privacy stacks]} (get areas ::area/battle)]
       [:dcg-area
        {::area/battle ""
         :privacy privacy}
        [:h3.sr-only (format "Battle (%d)" (count stacks))]])
     ;; Deck
     (let [{::area/keys [privacy cards]} (get areas ::area/deck)]
       [:dcg-area
        {::area/deck ""
         :privacy privacy}
        [:h3.sr-only (format "Deck (%d)" cards)]
        [:dcg-card]])
     ;; Digi-Eggs
     (let [{::area/keys [privacy cards]} (get areas ::area/digi-eggs)]
       [:dcg-area
        {::area/digi-eggs ""
         :privacy privacy}
        [:h3.sr-only (format "Digi-Eggs (%d)" cards)]
        (if (contains? available-actions
                       [:action/hatch
                        (::player/id player)
                        nil])
          [:form {:method "POST"
                  :action "#"}
           [:input
            {:type "hidden"
             :name "__anti-forgery-token"
             :value anti-forgery/*anti-forgery-token*}]
           [:input
            {:type "hidden"
             :name "action"
             :value ":action/hatch"}]
           [:dcg-card
            [:button
             {:type "submit"}
             "Hatch Digi-Egg"]]]
          [:dcg-card])])
     ;; Breeding Area
     (let [{::area/keys [privacy stacks]} (get areas ::area/breeding)]
       [:dcg-area
        {::area/breeding ""
         :privacy privacy}
        [:h3.sr-only "Breeding Area"]
        (list (->> stacks
                   (map (fn [{::stack/keys [cards uuid]
                             :as stack}]
                          (->> cards
                               (map (juxt ::card/uuid
                                          (comp #(get-in game %)
                                                ::card/lookup)))
                               (sort-by (comp (juxt :card/category
                                                    :card/level)
                                              second))
                               (map (fn [[card-uuid card]]
                                      (card-component card-uuid card)))
                               (into [:dcg-stack]))))))])
     ;; Tamer/Option
     (let [{::area/keys [privacy stacks]} (get areas ::area/battle)]
       [:dcg-area
        {"tamer-option" ""
         :privacy privacy}
        [:h3.sr-only (format "Battle (%d)" (count stacks))]])
     ;; Trash
     (let [{::area/keys [privacy cards]} (get areas ::area/trash)]
       [:dcg-area
        {::area/trash ""
         :privacy privacy}
        [:h3.sr-only "Trash"]])]))

(defn card
  [cards]
  (page/html5
      {:mode :html
       :lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:title "Heroicc"]
     [:meta {:content "width=device-width,initial-scale=1" :name "viewport"}]
     (page/include-css "/css/style.css")
     [:script {:type "text/javascript" :defer "defer" :src "/js/dcg-card.js"}]]
    [:body
     [:h1 "Heroicc"]
     #_[:pre
        [:code
         (->> cards
              pprint/pprint
              with-out-str)]]
     [:div
      [:h2 "Card names"]
      [:ul
       (map (comp (fn [n]
                    [:li n " - " (count n)])
                  :card/name) cards)]]
     [:div
      [:h2 "Compact cards"]
      (map (fn [c]
             [:div
              [:div
               (card-component (random-uuid) c)]
              (compact-card-component c)
              [:p (:card/category c)]])
           cards)]]))

(defn game
  [{::game/keys [available-actions players db] :as game} player]
  (let [{::player/keys [memory areas] :as me} (get (state/players-by-id players)
                                                   (::player/id player))]
    (page/html5 {:mode :html
                 :lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:title "Heroicc"]
       [:meta {:content "width=device-width,initial-scale=1" :name "viewport"}]
       (page/include-css "/css/style.css")
       [:script {:type "text/javascript" :defer "defer" :src "/js/dcg-card.js"}]]
      [:body
       [:h1 "Heroicc"]
       [:h2 (::player/name me)]
       [:pre
        [:code
         (->> available-actions
              (sort-by first)
              pprint/pprint
              with-out-str)]]
       (when (empty? available-actions)
         [:p "Waiting for opponent..."])
       (let [dialog-actions (filter (fn [[action-state-id _ _]]
                                      (string/ends-with? (str action-state-id)
                                                         "?"))
                                    available-actions)
             action-state-id (ffirst dialog-actions)]
         (when (seq dialog-actions)
           [:dialog {:open true}
            [:form {:method "POST"}
             (case action-state-id
               :action/re-draw?
               [:div
                [:p [:strong "Re-draw Hand?"]]
                (list (->> (get-in areas [::area/hand ::area/cards])
                           (map (comp #(get-in game %) ::card/lookup))
                           (sort-by (juxt :card/category :card/level))
                           (map card-component)))])
             [:input
              {:type "hidden"
               :name "__anti-forgery-token"
               :value anti-forgery/*anti-forgery-token*}]
             [:input
              {:type "hidden"
               :name "action"
               :value (str action-state-id)}]
             (->> dialog-actions
                  (sort-by (fn [[_ _ param]] param))
                  (map (fn [[_ _ param]]
                         [:button
                          {:type "submit"
                           :name "params"
                           :value (str param)}
                          (case param
                            true "Yes"
                            false "No")]))
                  list)]]))
       (playmat game player)])))

(defn index
  [player]
  (page/html5 {:mode :html
               :lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:title "Heroicc"]
     [:meta {:content "width=device-width,initial-scale=1" :name "viewport"}]
     (page/include-css "/css/style.css")
     [:script {:type "text/javascript" :defer "defer" :src "/js/dcg-card.js"}]]
    [:body
     [:h1 "Heroicc"]
     [:p (pr-str player)]
     (if (state/player-in-queue? player)
       [:p "Waiting for another player..."]
       [:form {:method "POST" :action "/queue"}
        [:input
         {:type "hidden"
          :name "__anti-forgery-token"
          :value anti-forgery/*anti-forgery-token*}]
        [:label "Username"
         [:input
          {:type "text"
           :name "username"}]]
        [:label "Deck Code"
         [:input
          {:type "text"
           :name "deck-code"}]]
        [:input
         {:type "submit"
          :value "Queue"}]])]))
