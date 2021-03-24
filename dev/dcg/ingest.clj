(ns dcg.ingest
  (:gen-class)
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [clj-http.client :as client]
   [hickory.core :as hickory]
   [hickory.select :as select])
  (:import
   [java.net URL]))

(set! *warn-on-reflection* true)

(defn http-get*
  [url]
  (let [response (client/get url)]
    (condp = (:status response)
      200 response
      (throw (Exception. ^String (format "Request to %s failed with status %s"
                                         url
                                         (:status response)))))))

(defonce http-get (memoize http-get*))

(defn base-url
  [& [{:keys [lang]}]]
  (let [subdomain (get {"ja" nil
                        "en" "world"}
                       lang)]
    (str "https://" (cond->> "digimoncard.com"
                      subdomain (str subdomain ".")))))

(def colors
  {:red     "#d50000"
   :blue    "#189fed"
   :yellow  "#f0ac09"
   :green   "#00bd97"
   :white   "#999999"
   :black   "#000"
   :purple  "#752ae6"})

(def rarities
  {"C"   "Common"
   "U"   "Uncommon"
   "R"   "Rare"
   "SR"  "Super Rare"
   "SEC" "Secret Rare"
   "P"   "Promo"})

(def timings
  ["On Play"
   "When Digivolving"
   "When Attacking"
   "End of Attack"
   "On Deletion"
   "Your Turn"
   "All Turns"
   "Opponent's Turn"
   "Start of Your Turn"
   "End of Your Turn"
   "Security"
   "Main"])

(def areas
  ["Battle Area"
   "Breeding Area"
   "Deck Zone"
   "Digi-Egg Deck Zone"
   "Trash (Recycle Bin)"
   "Memory Gauge"
   "Security Stack"])

(def states
  ["Suspend"
   "Suspended"
   "Unsuspend"
   "Unsuspended"
   "Digivolution Card"
   "Deleted"
   "Security Digimon"])

(def phases
  ["Unsuspend"
   "Draw"
   "Breeding"
   "Main"])

(def actions
  ["Attack"
   "Block"
   "Battle"
   "Playing"
   "Hatching"
   "Digivolution"
   "Trash"
   "Pass"
   "Check"
   "Move"])

(def effects
  ["Blocker"
   "Security Attack"
   "Recovery"
   "Piercing"
   "Draw"
   "Jamming"
   "Digisorption"
   "Reboot"
   "De-Digivolve"
   "Retaliation"])

(def frequency
  ["Once Per Turn"
   "Twice Per Turn"])

(defn card
  [c]
  (let [header (->> (select/select (select/descendant
                                    (select/and (select/tag "ul")
                                                (select/class "cardinfo_head"))
                                    (select/tag "li"))
                                   c)
                    (map (comp first :content)))
        dl (fn [class-name]
             (select/select (select/descendant
                             (select/class class-name)
                             (select/or
                              (select/tag "dt")
                              (select/tag "dd")))
                            c))
        info-top (->> (dl "cardinfo_top_body")
                      (map (comp string/trim first :content))
                      (apply hash-map))
        info-bottom (->> (dl "cardinfo_bottom")
                         (drop-last 2)
                         (map #(let [content (first (:content %))]
                                 (cond->> content
                                   (string? content)
                                   string/trim
                                   (map? content)
                                   ((constantly :error)))))
                         (apply hash-map))
        color (-> (get-in c [:attrs :class])
                  (string/split #"card_detail_")
                  second
                  keyword)
        play-cost (some->> (or (get info-top "Play Cost")
                               (get info-top "登場コスト"))
                           (re-find #"[0-9]+")
                           Integer/parseInt)
        digivolve-cost (->> [(or (get info-top "Digivolve 1 Cost")
                                 (get info-top "進化コスト1"))
                             (or (get info-top "Digivolve 2 Cost")
                                 (get info-top "進化コスト2"))]
                            (remove (fn [s] (= s "-")))
                            (remove nil?)
                            (mapv (fn [c] {:cost c})))]
    {:card/id (nth header 0)
     :card/rarity (nth header 1)
     :card/type (nth header 2)
     :card/alternative? (or (contains? (set header) "Alternative Art")
                            (contains? (set header) "パラレル"))
     :card/level (some->> (nth header 3 nil)
                          (re-find #"[0-9]+")
                          Integer/parseInt)
     :card/play-cost (cond
                       (and (nil? play-cost)
                            (= (nth header 2)
                               (or "Option"
                                   "オプション"))) 0
                       :else play-cost)
     :card/digivolve-cost digivolve-cost
     :card/dp (some->> (get info-top "DP")
                       (re-find #"[0-9]+")
                       Integer/parseInt)
     :card/form (or (get info-top "Form")
                    (get info-top "形態"))
     :card/attribute (or (get info-top "Attribute")
                         (get info-top "属性"))
     :card/digimon-type (or (get info-top "Type")
                            (get info-top "タイプ"))
     :card/effect (or (get info-bottom "Effect")
                      (get info-bottom "効果"))
     :card/inherited-effect (or (get info-bottom "Digivoluve effect")
                                (get info-bottom "進化元効果"))
     :card/security-effect (or (get info-bottom "Security effect")
                               (get info-bottom "セキュリティ効果"))
     :card/color color
     :card/name (->> (select/select
                      (select/descendant
                       (select/and (select/tag "div")
                                   (select/class "card_name")))
                      c)
                     first
                     ((comp first :content)))
     :card/image (->> (select/select (select/descendant (select/tag "img"))
                                     c)
                      first
                      (#(-> (get-in % [:attrs :src])
                            (string/replace #"^.." ""))))}))

(defn cardlist
  [url]
  (let [response (http-get url)
        article (->> (:body response)
                     hickory/parse
                     hickory/as-hickory
                     (select/select
                      (select/descendant (select/id "article")))
                     first)
        title (->> (select/select (select/descendant
                                   (select/id "maintitle")
                                   (select/tag "h3"))
                                  article)
                   first
                   :content
                   first
                   string/trim)
        cards (select/select (select/descendant
                              (select/id "article")
                              (select/class "image_lists")
                              (select/class "popup")
                              (select/class "card_detail"))
                             article)]
    (reduce (fn [accl c]
              (let [new-card (card c)
                    broken-keys (reduce (fn [broken-keys [k v]]
                                          (cond-> broken-keys
                                            (= v :error) (conj k)))
                                        new-card)
                    cards-by-id (group-by :card/id accl)
                    new-card (->> (select-keys (get-in cards-by-id
                                                       [(:card/id new-card)
                                                        0])
                                               broken-keys)
                                  (merge new-card)
                                  (reduce (fn [accl2 [k v]]
                                            (assoc accl2
                                                   k
                                                   (condp = v
                                                     "-" nil
                                                     v)))
                                          {}))]
                (conj accl
                      (-> new-card
                          (assoc :card/release title)
                          (update :card/image #(str (string/replace url
                                                                    #"(\.com).*"
                                                                    "$1")
                                                    %))))))
            []
            cards)))

(defn cardlists
  [origin]
  (let [response (http-get (str origin "/cardlist/"))]
    (->> (:body response)
         hickory/parse
         hickory/as-hickory
         (select/select
          (select/descendant (select/id "snaviListCol")
                             (select/tag "li")
                             (select/tag "a")))
         (map #(str origin "/cardlist/" (get-in % [:attrs :href])))
         (mapcat cardlist))))

(defn save-file
  [uri]
  (let [url (URL. uri)
        fullpath (str (.getHost url) (.getPath url))
        path (subs fullpath 0 (inc (string/last-index-of fullpath "/")))]
    (when-not (.exists (io/file (str "resources/" path)))
      (.mkdirs (io/file (str "resources/" path))))
    (when-not (.exists (io/file (str "resources/" fullpath)))
      (with-open [in (io/input-stream uri)
                  out (io/output-stream (str "resources/" fullpath))]
        (println (format "Downloading image: %s" uri))
        (io/copy in out)))))

(defn -main
  []
  (doseq [lang ["ja" "en"]]
    (->> (cardlists (base-url {:lang lang}))
         (group-by (fn [{:keys [card/id]}]
                     (-> id
                         (string/split #"-")
                         first)))
         (map (fn [[card-set cards]]
                (when-not (.exists (io/file (str "resources/" lang)))
                  (.mkdirs (io/file (str "resources/" lang))))
                (spit (str "resources/" lang "/" card-set ".edn") "")
                (doseq [card cards]
                  (spit (str "resources/" lang "/" card-set ".edn")
                        (with-out-str (pprint/pprint card))
                        :append true)
                  (save-file (:card/image card)))))
         doall)))
