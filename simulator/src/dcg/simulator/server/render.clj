(ns dcg.simulator.server.render
  (:require
   [clojure.string :as string]
   [dcg.simulator.state :as state]
   [hiccup.page :as page]
   [ring.middleware.anti-forgery :as anti-forgery]))

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
                             (if (< (or block-icon 0) 4)
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
                :font-size 22
                :font-weight 600
                :x 13
                :y 35}
         [:tspan "L"]
         [:tspan {:font-size 18} "v"]
         [:tspan "."]
         [:tspan {:font-size 32} (or level "-")]])
      (if (or (and (= "en" language)
                   (< (count name) 55))
              (and (not= "en" language)
                   (< (count name) 28)))
        [:text {:lang language
                :font-family "Kommon Grotesk"
                :fill (if (and (or (= (-> color first :color/color) :yellow)
                                   (= (-> color first :color/color) :white))
                               (< (count color) 3))
                        (get card-color :black)
                        (get card-color :white))
                :style {:paint-order "stroke"}
                :stroke (when (and (> (count color) 1)
                                   (or (contains? (set (map :color/color color))
                                                  :yellow)
                                       (contains? (set (map :color/color color))
                                                  :white)))
                          (if (and (= (-> color first :color/color) :yellow)
                                   (< (count color) 3))
                            "#FFF"
                            "#000"))
                :stroke-width (when (> (count color) 1) 3)
                :font-size (cond-> 24
                             (= "en" language)
                             (+ 6))
                :lengthAdjust "spacingAndGlyphs"
                :textLength (cond
                              (and (> (count name) 12)
                                   digimon?) 205
                              (and (> (count name) 20)
                                   (not digimon?)) 220
                              :else nil)
                :font-weight 900
                :x (if digimon?
                     (if (and (or (= "ja" language)
                                  (= "ko" language))
                              (< (or block-icon 0) 3))
                       85
                       200)
                     195)
                :y (cond-> 30
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
                   :font-family "Kommon Grotesk"
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
                   :font-weight 900
                   :x (if digimon?
                        (if (and (or (= "ja" language)
                                     (= "ko" language))
                                 (< (or block-icon 0) 3))
                          85
                          200)
                        195)
                   :y (cond-> 20
                        (some string? [form attribute type])
                        (- 3))
                   :text-anchor (if (and digimon?
                                         (or (= "ja" language)
                                             (= "ko" language))
                                         (< (or block-icon 0) 3))
                                  "start"
                                  "middle")} line-1]
           [:text {:lang language
                   :font-family "Kommon Grotesk"
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
                   :font-weight 900
                   :x (if digimon?
                        (if (and (or (= "ja" language)
                                     (= "ko" language))
                                 (< (or block-icon 0) 3))
                          85
                          200)
                        195)
                   :y (cond-> 33
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
                :font-size 7.5
                :font-weight 900
                :kerning 1
                :x 370
                :y 42
                :fill (if (= (-> color first :color/color) :black)
                        (get card-color :black)
                        (get card-color :white))
                :text-anchor "end"}
         (->> [form attribute type]
              (remove nil?)
              (string/join "&nbsp;&nbsp;|&nbsp;&nbsp;"))])]]))

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
     [:h1 [:a {:href "/"} "Heroicc"]]
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
     [:h1 [:a {:href "/"} "Heroicc"]]
     [:div
      [:h2 "Card names"]
      [:ul
       (->> cards
            (map (juxt :card/name :card/language))
            set
            (sort-by second)
            (map (fn [[n language]]
                   [:li (format "%s (%s)"
                                n language)])))]]
     [:div
      [:h2 "Compact cards"]
      (map (fn [c]
             [:div
              [:div
               (card-component {::card/card c})]
              (compact-card-component c)
              [:p (:card/category c)]])
           cards)]]))

(defn game-wrapper
  [app]
  (page/html5 {:mode :html
               :lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:title "Heroicc"]
     [:meta {:content "width=device-width,initial-scale=1" :name "viewport"}]
     (page/include-css "/css/style.css")
     [:script {:type "text/javascript" :defer "defer" :src "/js/dcg-card.js"}]]
    [:body app]))
