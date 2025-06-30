(ns dcg.db.card.repair
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.io PushbackReader]))

(def text-fixes-by-number-by-language
  "Bandai's official card site has a few issues that cannot be programmatically
  fixed. This lookup has functions that will repair these cards and shouldn't
  cause further issues if the official site is ever updated and fixed.
  "
  (->> (edn/read (PushbackReader.
                  (io/reader
                   (io/resource "card-repairs.edn"))))
       (reduce-kv (fn [accl number kv]
                    (assoc accl number
                           (reduce-kv (fn [m language f]
                                        (assoc m language (eval f)))
                                      {}
                                      kv)))
                  {})))

(defn html-encoding-errors
  [html-string]
  (-> html-string
      (string/replace #"<(Draw.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Blocker.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Blast Digivolve.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Security Attack.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Overflow.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Digi-Burst.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Rush.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Vortex.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Delay)[>＞]" "&lt;$1&gt;")))

(defn text-fixes
  [s]
  (some-> s
          (string/replace "＜br＞" "")
          (string/replace "\uFEFF" "")
          (string/replace "【진화시】" "【진화 시】")
          (string/replace #"(?i)once per turn" "Once Per Turn")
          (string/replace "Opponent's Turns" "Opponent's Turn")
          (string/replace #"(デジクロス\s?[\-\+][0-9]+):" "【$1】")
          (string/replace #"(DigiXros\s?[\-\+][0-9]+)\s?:" "<$1>")
          (string/replace #"(数码合体\s?[\-\+][0-9]+)\s?[:：]" "≪$1≫")
          (string/replace #"(디지크로스\s?[\-\+][0-9]+)\s?:" "≪$1≫")
          (string/replace #"^≪?ジョグレス:?(.*?ら0)≫?"
                          "【ジョグレス】$1")
          (string/replace "アクティブで〔進化〕する"
                          "アクティブで進化する")
          (string/replace "〔ジョグレス〕" "【ジョグレス】")
          (string/replace #"^＜?DNA Digivolution:\s*(.*?Lv\.[0-9].*?Lv\.[0-9])＞?"
                          "[DNA Digivolution] $1")
          (string/replace #"^《?合步:(.*?Lv\.[0-9].*?Lv\.[0-9]起[0-9])》?"
                          "【合步】$1")
          (string/replace #"^《?조그레스:\s*(.*?Lv\.[0-9].*?Lv\.[0-9]에서\s*[0-9])》?"
                          "【조그레스】$1")
          (string/replace "〔조그레스〕" "【조그레스】")
          (string/replace "〔버스트 진화〕" "【버스트 진화】")
          (string/replace #"《진화:?(.*[0-9]+)》" "【진화】 $1")
          (string/replace #"(爆裂进化):" "【$1】")
          (string/replace #"(進化|进化|진화)\s?:" "【$1】")
          (string/replace #"[〔［\[](進化|进化|진화)[］〕\]]" "【$1】")
          (string/replace #"(Burst Digivolve):" "[$1]")
          (string/replace #"(Digivolve):" "[$1]")
          (string/replace #"《(진화):(.*?)》" "【$1】$2")
          (string/replace "〔턴에 1회〕" "［턴에 1회］")
          (string/replace "〔패〕" "［패］")
          (string/replace "[패]" "［패］")
          (string/replace "[육성]" "［육성］")
          (string/replace "【턴에 1회】" "［턴에 1회］")
          (string/replace "[턴에 1회]" "［턴에 1회］")
          (string/replace "[턴에 2회]" "［턴에 2회］")
          (string/replace "[트래시]" "［트래시］")
          (string/replace "{セキュリティ}" "［セキュリティ］")
          (string/replace "[セキュリティ]" "［セキュリティ］")
          (string/replace "[ターンに1回]" "［ターンに1回］")
          (string/replace "[ターンに2回]" "［ターンに2回］")
          (string/replace "[トラッシュ]" "［トラッシュ］")
          (string/replace "[手札]" "［手札］")
          (string/replace "{手札}" "［手札］")
          (string/replace "[育成]" "［育成］")
          (string/replace "{Security}" "[Security]")
          (string/replace #"(アセンブリ\-[0-9]):" "【$1】")
          (string/replace #"【(组装\-[0-9]):" "【$1】")
          (string/replace ",并减少登场费用。】" ",并减少登场费用。")
          (string/replace #"【(DP\+[0-9]+)】" "$1")
          (string/replace #"\)ー$" ")")
          ;; https://world.digimoncard.com/rule/card_text/
          (string/replace "X-Antibody" "X Antibody")))

(defn ace-names
  [cards]
  (->> cards
       (sort-by :card/number)
       (partition-by :card/number)
       (mapcat (fn [cards]
                 (cond->> cards
                   (some (fn [{:card/keys [name] :as card}]
                           (string/ends-with? name "ACE"))
                         cards)
                   (map (fn [{:card/keys [language] :as card}]
                          (update card
                                  :card/name
                                  (fn [s]
                                    (cond-> s
                                      (not (string/ends-with? s "ACE"))
                                      (str (cond->> "ACE"
                                             (= language "en")
                                             (str " ")))))))))))))
