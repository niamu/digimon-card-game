(ns dcg.api.resources.card
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.pprint :as pprint]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom.html-entities :as ent]
   [com.fulcrologic.fulcro.dom-server :as dom]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.simulator.card :as-alias card]
   [dcg.api.db :as db]
   [taoensso.tempura :as tempura])
  (:import
   [java.io PushbackReader]
   [java.text SimpleDateFormat]
   [java.util UUID]
   [name.fraser.neil.plaintext diff_match_patch diff_match_patch$Operation]))

(def ^:private tr
  (partial tempura/tr
           {:dict (-> (io/resource "tr.edn")
                      io/reader
                      (PushbackReader.)
                      edn/read)}))

(defn- diff
  [old new]
  (let [dmp (diff_match_patch.)
        diff (.diff_main dmp old new)]
    (.diff_cleanupSemantic dmp diff)
    (for [d diff
          :let [operation (.-operation d)
                text (.-text d)]]
      (condp = operation
        diff_match_patch$Operation/DELETE (dom/del text)
        diff_match_patch$Operation/INSERT (dom/ins text)
        text))))

(defn- paragraphs
  [s]
  (if (string? s)
    (->> (string/split s #"\n+\s*" -1)
         (map (fn [para]
                (dom/p para))))
    (->> s
         (map (fn [el]
                (if (string? el)
                  (string/split el #"\n+\s*" -1)
                  el)))
         (reduce (fn [accl elements]
                   (if (and (every? string? elements)
                            (> (count elements) 1))
                     (apply conj accl (interpose \n elements))
                     (conj accl elements)))
                 [])
         (partition-by (fn [el] (= el \n)))
         (remove (fn [coll] (every? (fn [el] (= el \n)) coll)))
         (map (fn [elements]
                (dom/p elements))))))

(defn- highlight-card-text
  [card highlights card-field]
  (->> highlights
       (filter (fn [{:highlight/keys [field]}]
                 (= field card-field)))
       (sort-by :highlight/index)
       (reduce (fn [accl {:highlight/keys [text type]}]
                 (let [remaining (last accl)
                       start-index (string/index-of remaining text)
                       before (subs remaining 0 start-index)
                       after (subs remaining (+ start-index
                                                (count text)))]
                   (-> accl
                       drop-last
                       (concat [(when-not (string/blank? before)
                                  before)
                                (dom/span
                                 {:class (format "highlight %s"
                                                 (name type))}
                                 (cond-> text
                                   (not= type :mention)
                                   (-> (subs 0 (dec (count text)))
                                       (subs 1))))
                                after]))))
               [(get card card-field)])
       paragraphs))

(defn- render-to-str-without-react
  [x]
  (with-redefs [dom/assign-react-checksum identity
                dom/react-text-node dom/text-node
                dom/render-element!
                (fn [{:keys [tag attrs children]} react-id ^StringBuilder sb]
                  (binding [dom/*css-mode* (= "style" tag)]
                    (dom/append! sb "<" tag)
                    (dom/render-attr-map! sb tag attrs)
                    (if (dom/container-tag? tag (seq children))
                      (do
                        (dom/append! sb ">")
                        (if-let [html-map (:dangerouslySetInnerHTML attrs)]
                          (dom/render-unescaped-html! sb html-map)
                          (run! #(.renderToString % react-id sb) children))
                        (dom/append! sb "</" tag ">"))
                      (dom/append! sb "/>"))))]
    (dom/render-to-str x)))

(defsc ^:private CardDetails
  [this {:card/keys [id language parallel-id category number color rarity
                     level type dp play-cost use-cost form attribute block-icon
                     image effect inherited-effect security-effect notes
                     digivolution-requirements supplemental-rarity errata faqs
                     highlights releases limitations]
         {rarity-stars :supplemental-rarity/stars
          rarity-stamp :supplemental-rarity/stamp} :card/supplemental-rarity
         :as card}]
  {:ident :card/id
   :query [:card/id
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
           {:card/supplemental-rarity
            [:supplemental-rarity/stars
             :supplemental-rarity/stamp]}
           :card/block-icon
           :card/notes
           {:card/color [:color/index
                         :color/color]}
           {:card/digivolution-requirements [:digivolve/index
                                             :digivolve/level
                                             :digivolve/category
                                             :digivolve/form
                                             :digivolve/color
                                             :digivolve/cost]}
           {:card/releases [:release/id
                            :release/name
                            :release/genre
                            :release/date
                            :release/product-uri
                            {:release/thumbnail [:image/path]}
                            {:release/image [:image/path]}]}
           {:card/image [:image/path]}
           {:card/highlights [:highlight/index
                              :highlight/field
                              :highlight/type
                              :highlight/text]}
           :card/effect
           :card/inherited-effect
           :card/security-effect
           {:card/errata [:errata/date
                          :errata/error
                          :errata/correction
                          :errata/notes]}
           {:card/limitations [:limitation/date
                               :limitation/type
                               :limitation/allowance
                               :limitation/paired-card-numbers
                               :limitation/note]}
           {:card/faqs [:faq/id
                        :faq/date
                        :faq/question
                        :faq/answer]}]}
  (let [digimon? (or (= category :digi-egg)
                     (= category :digimon))]
    (dom/div
     {:class "card-details"}
     (dom/section
      (dom/div
       (dom/dl
        {:class "card-details_header"}
        (dom/dt {:class "sr-only"} (tr [language] [:card-number]))
        (dom/dd number)
        (dom/dt {:class "sr-only"} (tr [language] [:rarity]))
        (dom/dd
         (dom/data
          {:itemprop "rarity"
           :value rarity}
          rarity))
        (dom/dt {:class "sr-only"} (tr [language] [:category]))
        (dom/dd (dom/data {:itemprop "category"
                           :value (name category)}
                          (tr [language] [category])))
        (when digimon?
          [(dom/dt {:class "sr-only"} (tr [language] [:level]))
           (dom/dd
            (dom/data
             {:itemprop "level"
              :value (or level "\u2012")}
             (or level "\u2012")))]))
       (dom/dl
        {:class "card-details_name"}
        (dom/dt {:class "sr-only"} (tr [language] [:name]))
        (dom/dd
         (dom/span
          {:class (when (re-find #"ACE$" (:card/name card))
                    "ace")}
          (-> (:card/name card)
              (string/replace #"ACE$" "")))))
       (when (or block-icon
                 (or (pos? parallel-id)
                     supplemental-rarity))
         (dom/dl
          {:class "card-details_subheader"}
          (when block-icon
            [(dom/dt {:class "sr-only"} (tr [language] [:block-icon]))
             (dom/dd
              (dom/data
               {:itemprop "block-icon"
                :value block-icon}
               (format "%02d" block-icon)))])
          (when (or (pos? parallel-id)
                    supplemental-rarity)
            [(dom/dt {:class "sr-only"} (tr [language] [:supplemental-rarity]))
             (when supplemental-rarity
               [(when rarity-stars
                  (dom/dd
                   {:aria-label (pprint/cl-format nil "~D star~:P" rarity-stars)}
                   (->> (repeat rarity-stars \u2605)
                        (string/join " "))))
                (when rarity-stamp
                  (dom/dd
                   {:aria-label (format "%s stamp" rarity-stamp)}
                   rarity-stamp))])
             (when (pos? parallel-id)
               (dom/dd
                (dom/data
                 {:itemprop "parallel-id"
                  :value parallel-id}
                 (tr [language] [:alternative-art]))))]))))
      (dom/div
       {:class "card-details_fields"}
       (dom/dl
        {:class "card-details_fields_table"}
        (dom/dt (tr [language] [:color]))
        (dom/dd (->> color
                     (sort-by :color/index)
                     (map (comp (fn [color]
                                  (dom/span
                                   {:class (format "color %s" (name color))}
                                   (tr [language] [color])))
                                :color/color))
                     (interpose " ")))
        (when play-cost
          [(dom/dt (tr [language] [:play-cost]))
           (dom/dd play-cost)])
        (when use-cost
          [(dom/dt (tr [language] [:use-cost]))
           (dom/dd use-cost)])
        (when dp
          [(dom/dt "DP")
           (dom/dd dp)])
        (when form
          [(dom/dt (tr [language] [:form]))
           (dom/dd form)])
        (when attribute
          [(dom/dt (tr [language] [:attribute]))
           (dom/dd attribute)])
        (when type
          [(dom/dt (tr [language] [:type]))
           (dom/dd type)]))
       (when digivolution-requirements
         (dom/dl
          {:class "card-details_digivolution-requirements"}
          (dom/dt (tr [language] [:digivolution-requirements]))
          (dom/dd
           (->> digivolution-requirements
                (sort-by :digivolve/index)
                (partition-by (juxt :digivolve/cost
                                    :digivolve/level
                                    :digivolve/form
                                    :digivolve/category))
                (reduce (fn [accl requirements]
                          (conj accl
                                (assoc (first requirements)
                                       :digivolve/color
                                       (mapcat :digivolve/color requirements))))
                        [])
                (map (fn [{:digivolve/keys [cost color level form category]}]
                       (dom/dl
                        (dom/dt (tr [language] [:digivolve-cost]))
                        (dom/dd cost)
                        (dom/dt (tr [language] [:color]))
                        (dom/dd (->> color
                                     (sort-by {:red 0
                                               :blue 1
                                               :yellow 2
                                               :green 3
                                               :black 4
                                               :purple 5
                                               :white 6})
                                     (map (fn [color]
                                            (dom/span
                                             {:class (format "color %s"
                                                             (name color))}
                                             (tr [language] [color]))))
                                     (interpose " ")))
                        (when level
                          [(dom/dt (tr [language] [:level]))
                           (dom/dd level)])
                        (when category
                          [(dom/dt (tr [language] [:category]))
                           (dom/dd (tr [language] [category]))])
                        (when form
                          [(dom/dt (tr [language] [:form]))
                           (dom/dd (tr [language] [form ""]))]))))))))
       (when effect
         (dom/dl
          (dom/dt (tr [language] [:effect]))
          (dom/dd (highlight-card-text card highlights :card/effect))))
       (when inherited-effect
         (dom/dl
          (dom/dt (tr [language] [:inherited-effect]))
          (dom/dd (highlight-card-text card highlights :card/inherited-effect))))
       (when security-effect
         (dom/dl
          (dom/dt (tr [language] [:security-effect]))
          (dom/dd (highlight-card-text card highlights :card/security-effect)))))
      (when errata
        (dom/dl
         (dom/dt (tr [language] [:errata]))
         (dom/dd
          (dom/dl
           {:class "list"}
           (when (inst? (:errata/date errata))
             [(dom/dt {:class "sr-only"} (tr [language] [:date]))
              (dom/dd
               (dom/time
                {:datetime (.format (SimpleDateFormat. "yyyy-MM-dd")
                                    (:errata/date errata))}
                (.format (SimpleDateFormat. "yyyy-MM-dd")
                         (:errata/date errata))))])
           (dom/dt (tr [language] [:correction]))
           (dom/dd (paragraphs (diff (:errata/error errata)
                                     (:errata/correction errata))))
           (when (:errata/notes errata)
             [(dom/dt (tr [language] [:notes]))
              (dom/dd (paragraphs (:errata/notes errata)))])))))
      (when limitations
        (dom/dl
         (dom/dt (tr [language] [:restrictions]))
         (dom/dd
          (->> limitations
               (sort-by :limitation/date)
               (map (fn [{:limitation/keys [date type allowance note
                                            paired-card-numbers]
                          :as limitation}]
                      (dom/dl
                       {:class "list"}
                       (when (inst? date)
                         [(dom/dt {:class "sr-only"} (tr [language] [:date]))
                          (dom/dd
                           (dom/time
                            {:datetime (.format (SimpleDateFormat. "yyyy-MM-dd")
                                                date)}
                            (.format (SimpleDateFormat. "yyyy-MM-dd")
                                     date)))])
                       (dom/dt {:class "sr-only"} (tr [language] [:limitation-type]))
                       (dom/dd {:data-allowance (when allowance
                                                  allowance)}
                               (-> type
                                   name
                                   (string/split #"\-")
                                   (as-> #__ parts
                                     (->> parts
                                          (map string/capitalize)
                                          (string/join " ")))))
                       (when allowance
                         [(dom/dt {:class "sr-only"} (tr [language] [:card-allowance]))
                          (dom/dd {:class "sr-only"} allowance)])
                       (when paired-card-numbers
                         [(dom/dt (tr [language] [:paired-cards]))
                          (->> paired-card-numbers
                               (map (fn [number]
                                      (dom/dd number))))])
                       (when note
                         [(dom/dt (tr [language] [:note]))
                          (dom/dd (paragraphs note))]))))))))
      (when faqs
        (dom/dl
         (dom/dt "Q&A")
         (dom/dd
          (->> faqs
               (sort-by :faq/id)
               (map (fn [{:faq/keys [date question answer]}]
                      (dom/dl
                       {:class "list"}
                       (when (inst? date)
                         [(dom/dt {:class "sr-only"} (tr [language] [:date]))
                          (dom/dd
                           (dom/time
                            {:datetime (.format (SimpleDateFormat. "yyyy-MM-dd")
                                                date)}
                            (.format (SimpleDateFormat. "yyyy-MM-dd")
                                     date)))])
                       (dom/dt "Question")
                       (dom/dd (paragraphs question))
                       (dom/dt "Answer")
                       (dom/dd (paragraphs answer)))))))))
      (when releases
        (dom/dl
         (dom/dt (tr [language] [:releases]))
         (->> releases
              (sort-by :release/id)
              (map (fn [{:release/keys [id name date genre product-uri
                                        thumbnail image]
                         :as release}]
                     (dom/dd
                      [(dom/img {:src (or (:image/path thumbnail)
                                          (:image/path image))
                                 :width 360
                                 :height 240
                                 :alt ""})
                       (dom/dl
                        {:class "list"}
                        (dom/dt {:class "sr-only"} (tr [language] [:genre]))
                        (dom/dd genre)
                        (dom/dt {:class "sr-only"} (tr [language] [:release-name]))
                        (dom/dd (dom/a (when product-uri
                                         {:href (str product-uri)
                                          :target "_blank"})
                                       name))
                        (when (inst? date)
                          [(dom/dt {:class "sr-only"} (tr [language] [:date]))
                           (dom/dd
                            (dom/time
                             {:datetime (.format (SimpleDateFormat. "yyyy-MM-dd")
                                                 date)}
                             (.format (SimpleDateFormat. "yyyy-MM-dd")
                                      date)))]))]))))))
      (when notes
        (dom/dl
         (dom/dt (tr [language] [:notes]))
         (dom/dd notes))))
     (dom/element
      {:tag :dcg-card
       :attrs {:lang language
               :data-card-id (cond-> number
                               (not (zero? parallel-id))
                               (str "_P" parallel-id))
               :data-showcase true}
       :children [(dom/picture
                   (dom/source {:srcset (:image/path image)})
                   (dom/img {:width 430
                             :height 600
                             :draggable false
                             :alt (format "%s %s" number (:card/name card))}))]}))))

(def ^:private ui-card-details (comp/factory CardDetails {:keyfn ::card/id}))

(defsc ^:private Card
  [this {::card/keys [uuid]
         {:card/keys [id parallel-id category number color rarity image language
                      level dp play-cost use-cost form attribute block-icon
                      effect inherited-effect security-effect notes
                      digivolution-requirements supplemental-rarity]
          :as card} ::card/card}
   {:keys [showcase?]}]
  {:ident ::card/uuid
   :query [::card/uuid
           {::card/card (comp/get-query CardDetails)}]}
  (dom/element
   {:tag :dcg-card
    :attrs {:lang language
            :data-card-id (cond-> number
                            (not (zero? parallel-id))
                            (str "_P" parallel-id))
            :data-showcase showcase?}
    :children
    [(dom/button
      {:aria-label (str (format "%s %s details"
                                number
                                (:card/name card)))
       :popovertarget (str uuid "-dialog")}
      (dom/picture
       (dom/source {:srcset (:image/path image)})
       (dom/img {:width 430 :height 600 :draggable "false"
                 :alt (format "%s %s" number (:card/name card))})))
     (dom/element
      {:tag "dcg-dialog"
       :children
       [(dom/dialog
         {:id (str uuid "-dialog")
          :popover "auto"}
         (dom/div
          {:class "container"}
          (dom/button
           {:autofocus true
            :popovertarget (str uuid "-dialog")
            :popovertargetaction "hide"
            :aria-label "Close"})
          (ui-card-details card)))]})]}))

(def ^:private ui-card (comp/factory Card {:keyfn ::card/uuid}))

(liberator/defresource card-resource
  [{{:keys [language card-id]} :path-params
    :as request}]
  (let [[number parallel-id] (string/split card-id #"_")
        parallel-id (or (some-> parallel-id
                                (subs 1)
                                parse-long)
                        0)
        db-card (db/q '{:find [(pull ?c [:card/name
                                         :card/number
                                         :card/language
                                         :card/parallel-id
                                         :card/level
                                         :card/effect
                                         :card/inherited-effect
                                         :card/security-effect
                                         :card/dp
                                         :card/rarity
                                         {:card/supplemental-rarity
                                          [:supplemental-rarity/stamp
                                           :supplemental-rarity/stars]}
                                         {:card/releases
                                          [:release/id
                                           :release/name
                                           :release/date
                                           :release/genre
                                           {:release/image [:image/path]}
                                           {:release/thumbnail [:image/path]}]}
                                         :card/block-icon
                                         :card/category
                                         :card/attribute
                                         :card/form
                                         :card/type
                                         {:card/color [:color/color]}
                                         {:card/digivolution-requirements
                                          [:digivolve/category
                                           :digivolve/form
                                           :digivolve/color
                                           :digivolve/level
                                           :digivolve/cost]}
                                         {:card/image [:image/path]}
                                         {:card/errata
                                          [:errata/date
                                           :errata/error
                                           :errata/correction
                                           :errata/notes]}
                                         {:card/limitations
                                          [:limitation/date
                                           :limitation/type
                                           :limitation/allowance
                                           :limitation/paired-card-numbers
                                           :limitation/note]}
                                         {:card/faqs
                                          [:faq/date
                                           :faq/question
                                           :faq/answer]}
                                         :card/notes]) .]
                        :in [$ ?language ?number ?parallel-id]
                        :where [[?c :card/language ?language]
                                [?c :card/image ?i]
                                [?c :card/number ?number]
                                [?c :card/parallel-id ?parallel-id]
                                [?i :image/language ?language]]}
                      language
                      number
                      parallel-id)
        db-card (cond-> db-card
                  (:card/image db-card)
                  (update-in [:card/image :image/path]
                             (partial utils/update-image-path
                                      request))
                  (:card/releases db-card)
                  (update-in [:card/releases]
                             (fn [releases]
                               (->> releases
                                    (map (fn [release]
                                           (-> release
                                               (update-in [:release/thumbnail
                                                           :image/path]
                                                          (partial utils/update-image-path
                                                                   request))
                                               (update-in [:release/image
                                                           :image/path]
                                                          (partial utils/update-image-path
                                                                   request)))))))))
        card (some-> db-card
                     (dissoc :card/language)
                     (update :card/color
                             #(map :color/color %))
                     (update :card/image
                             (fn [{path :image/path}]
                               path)))
        alt-arts (db/q '{:find [[(pull ?c [:card/notes
                                           :card/number
                                           :card/parallel-id]) ...]]
                         :in [$ ?language ?number ?parallel-id]
                         :where [[?c :card/language ?language]
                                 [?c :card/image ?i]
                                 [?c :card/number ?number]
                                 (not [?c :card/parallel-id ?parallel-id])
                                 [?i :image/language ?language]]}
                       language
                       number
                       parallel-id)]
    {:allowed-methods [:head :get]
     :available-media-types ["application/vnd.api+json"
                             "text/html"]
     :etag (fn [{{media-type :media-type} :representation}]
             (str (utils/sha card)
                  "--"
                  media-type))
     :exists? (fn [_] (boolean card))
     :handle-ok
     (fn [{{{:keys [_ _]} :path-params} :request
          {media-type :media-type} :representation
          :as context}]
       (case media-type
         "text/html" (->> (ui-card {::card/uuid (-> (get-in context
                                                            [:request
                                                             :reitit.core/match
                                                             :path])
                                                    (string/replace "/" "_"))
                                    ::card/card db-card})
                          render-to-str-without-react)
         {:data
          (cond-> {:id (str "/" language "/cards/"
                            number
                            (when-not (zero? parallel-id)
                              (str "_P" parallel-id)))
                   :type "cards"
                   :attributes (cond-> (dissoc card :card/releases)
                                 (:card/errata card)
                                 (update :card/errata
                                         (fn [errata]
                                           (cond-> errata
                                             (:errata/date errata)
                                             (update :errata/date
                                                     utils/inst->iso8601))))
                                 (:card/faqs card)
                                 (update :card/faqs
                                         (fn [faqs]
                                           (map (fn [faq]
                                                  (cond-> faq
                                                    (:faq/date faq)
                                                    (update :faq/date
                                                            utils/inst->iso8601)))
                                                faqs)))
                                 (:card/limitations card)
                                 (update :card/limitations
                                         (fn [limitations]
                                           (map (fn [l]
                                                  (cond-> l
                                                    (:limitation/date l)
                                                    (update :limitation/date
                                                            utils/inst->iso8601)))
                                                limitations))))}
            (seq alt-arts)
            (assoc-in [:relationships :alternate-arts]
                      {:data
                       (->> alt-arts
                            (map (fn [{:card/keys [notes number parallel-id]}]
                                   {:type "cards"
                                    :id (str "/" language "/cards/"
                                             number
                                             (when-not (zero? parallel-id)
                                               (str "_P" parallel-id)))
                                    :links
                                    {:related
                                     (cond-> {:href
                                              (format
                                               "%s/%s"
                                               (utils/base-url context)
                                               (str language
                                                    "/"
                                                    "cards"
                                                    "/"
                                                    number
                                                    (when-not (zero? parallel-id)
                                                      (str "_P" parallel-id))))}
                                       notes
                                       (assoc :meta
                                              {:notes notes}))}})))})
            (seq (:card/releases card))
            (assoc-in [:relationships :releases]
                      {:data
                       (->> (:card/releases card)
                            (map (fn [{:release/keys [name]
                                      :as release}]
                                   (update release
                                           :release/id utils/short-uuid)))
                            (sort-by (juxt :release/date
                                           :release/id))
                            (mapv (fn [r]
                                    (let [m (reduce-kv (fn [m k v]
                                                         (if (nil? v)
                                                           m
                                                           (assoc m k v)))
                                                       {}
                                                       r)]
                                      (cond-> m
                                        (:release/date m)
                                        (update :release/date
                                                utils/inst->iso8601)))))
                            (map (fn [r]
                                   {:id (str "/" language "/releases/"
                                             (:release/id r))
                                    :type "releases"
                                    :meta {:name (:release/name r)
                                           :date (:release/date r)}
                                    :links
                                    {:related (format "%s/%s"
                                                      (utils/base-url context)
                                                      (str language
                                                           "/releases/"
                                                           (:release/id r)))}})))}))}))
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :handle-not-found errors/error404-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))
