(ns dcg.card.repair
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.io PushbackReader]
   [java.net URI]))

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
      (string/replace #"<(Security Attack.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Digi-Burst.*?)[>＞]" "&lt;$1&gt;")
      (string/replace #"<(Rush.*?)[>＞]" "&lt;$1&gt;")))

(defn text-fixes
  [s]
  (some-> s
          (string/replace "＜br＞" "")
          (string/replace "진화시" "진화 시")
          (string/replace #"(?i)once per turn" "Once Per Turn")
          (string/replace "Opponent's Turns" "Opponent's Turn")
          (string/replace ".On Play]" ".[On Play]")
          (string/replace "End of Turn" "End of Your Turn")
          (string/replace "with Justimon in its name"
                          "with [Justimon] in its name")
          (string/replace #"^\[Inherited Effect\]" "")
          (string/replace "from Koromon" "from [Koromon]")
          (string/replace #"(デジクロス\s?[\-\+][0-9]+):" "【$1】")
          (string/replace #"(DigiXros\s?[\-\+][0-9]+)\s?:" "<$1>")
          (string/replace #"(数码合体\s?[\-\+][0-9]+)\s?:" "≪$1≫")
          (string/replace #"^≪?ジョグレス(.*?ら0)≫?"
                          "〔ジョグレス〕$1")
          (string/replace #"^＜?DNA Digivolution:\s*(.*?Lv\.[0-9].*?Lv\.[0-9])＞?"
                          "[DNA Digivolution] $1")
          (string/replace #"^《?合步:(.*?Lv\.[0-9].*?Lv\.[0-9]起[0-9])》?"
                          "〔合步〕$1")
          (string/replace #"^《?조그레스:\s*(.*?Lv\.[0-9].*?Lv\.[0-9]에서\s*[0-9])》?"
                          "〔조그레스〕$1")
          (string/replace #"《진화:?(.*[0-9]+)》" "【진화】 $1")
          (string/replace #"(進化|进化|진화)\s?:" "【$1】")
          (string/replace #"［(進化|进化|진화)］" "【$1】")
          (string/replace #"(Burst Digivolve):" "[$1]")
          (string/replace #"(Digivolve):" "[$1]")
          (string/replace #"(爆裂进化):" "【$1】")
          (string/replace #"《(진화):(.*?)》" "【$1】$2")
          ;; https://world.digimoncard.com/rule/card_text/
          (string/replace "X-Antibody" "X Antibody")
          (as-> s
              (let [re (re-pattern (str "("
                                        "[\\[【]"
                                        "(デジクロス|DigiXros\\s?)\\-[0-9]"
                                        "[\\]】]"
                                        "(((.|\n)*"
                                        "(reduces the play cost|"
                                        "reduces the cost|"
                                        "play cost per card|"
                                        "in traits|"
                                        "in text|"
                                        "& different card numbers)"
                                        "\\s*\\.?\n?)"
                                        "|(.|\n)*different card numbers|"
                                        #_"|.*?[<＜]|"
                                        ".*?\n)"
                                        ")"))
                    digixros (some-> (re-find re s)
                                     first)]
                (cond-> s
                  (and digixros
                       (not (= s digixros)))
                  (-> (string/replace digixros "")
                      (str "\n" digixros)))))))
