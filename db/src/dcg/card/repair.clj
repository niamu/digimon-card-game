(ns dcg.card.repair
  (:require
   [clojure.string :as string]
   [taoensso.timbre :as logging])
  (:import
   [java.net URI]))

(def text-fixes-by-number-by-language
  "Bandai's official card site has a few issues that cannot be programmatically
  fixed. This lookup has functions that will repair these cards and shouldn't
  cause further issues if the official site is ever updated and fixed.
  "
  {"BT1-033"
   {"en" (fn [card]
           (assoc card :card/name "Dolphmon"))}
   "BT1-054"
   {"ja" (fn [card]
           (update card :card/digivolve-conditions
                   (fn [conditions]
                     (map #(assoc % :digivolve/cost 3) conditions))))}
   "BT1-076"
   {"zh-Hans" (fn [{:card/keys [effect inherited-effect] :as card}]
                (cond-> (dissoc card :card/effect)
                  effect
                  (assoc :card/inherited-effect effect)))}
   "BT1-084"
   {"ko" (fn [card]
           (assoc card :card/digimon-type "성기사형/로얄 나이츠"))}
   "BT1-086"
   {"zh-Hans" (fn [card]
                (dissoc card :card/inherited-effect))}
   "BT1-087"
   {"en" (fn [{:card/keys [inherited-effect security-effect] :as card}]
           (cond-> card
             (and (not security-effect)
                  inherited-effect)
             (-> (assoc :card/security-effect inherited-effect)
                 (dissoc :card/inherited-effect))))}
   "BT1-088"
   {"en" (fn [{:card/keys [inherited-effect security-effect] :as card}]
           (cond-> card
             (and (not security-effect)
                  inherited-effect)
             (-> (assoc :card/security-effect inherited-effect)
                 (dissoc :card/inherited-effect))))}
   "BT1-089"
   {"en" (fn [{:card/keys [inherited-effect security-effect] :as card}]
           (cond-> card
             (and (not security-effect)
                  inherited-effect)
             (-> (assoc :card/security-effect inherited-effect)
                 (dissoc :card/inherited-effect))))
    "ko" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             security-effect
             (update :card/security-effect #(string/replace % "+M7:M11" ""))))}
   "BT1-106"
   {"en" (fn [card]
           (assoc card :card/name "Symphony No. 1 <Polyphony>"))}
   "BT2-010"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (cond-> s
                       (nil? s)
                       (str "[On Deletion] If it's your turn, gain 1 memory.")))))}
   "BT2-020"
   {"zh-Hans" (fn [card]
                (assoc card :card/attribute "病毒种"))}
   "BT2-028"
   {"zh-Hans" (fn [{:card/keys [inherited-effect] :as card}]
                (cond-> card
                  (not inherited-effect)
                  (assoc :card/inherited-effect
                         "【我方的回合】当此数码宝贝在主要阶段中转换为活跃状态时,本回合中,此数码宝贝获得《干扰》（此数码宝贝在与安防数码宝贝的战斗中不会被消灭）效果。")))}
   "BT2-034"
   {"zh-Hans" (fn [{:card/keys [effect inherited-effect] :as card}]
                (cond-> card
                  (and (not effect)
                       inherited-effect)
                  (-> (assoc :card/effect inherited-effect)
                      (dissoc :card/inherited-effect))))}
   "BT2-072"
   {"ko" (fn [card]
           (assoc card
                  :card/form "성숙기"
                  :card/attribute "바이러스종"))}
   "BT2-083"
   {"ko" (fn [card]
           (assoc card
                  :card/digimon-type "합성형"))}
   "BT2-096"
   {"ko" (fn [card]
           (update card :card/security-effect
                   (fn [s]
                     (some-> s
                             (string/replace
                              "【시큐리티】 이 카드를 패에 추가한다."
                              "【시큐리티】 이카드의 【메인】 효과를발휘한다.")))))}
   "BT2-097"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (string/replace s "get-4000" "get -4000"))))}
   "BT3-010"
   {"ko" (fn [{:card/keys [language level parallel-id] :as card}]
           (cond-> card
             (= level 3)
             (-> (assoc :card/id (format "card/%s_%s_P"
                                         language
                                         "BT3-009"
                                         parallel-id)
                        :card/number "BT3-009")
                 (update-in [:card/digivolve-conditions 0 :digivolve/id]
                            (fn [s]
                              (string/replace s "BT3-010" "BT3-009"))))))}
   "BT3-024"
   {"ko" (fn [card]
           (assoc card :card/digimon-type "환수형"))}
   "BT3-027"
   {"en" (fn [card]
           (assoc card :card/name "Paildramon"))}
   "BT3-028"
   {"en" (fn [card]
           (assoc card :card/name "Bastemon"))}
   "BT3-057"
   {"en" (fn [card]
           (update-in card [:card/image :image/source]
                      (fn [source]
                        (if (string/ends-with? source "BT3-057_P3.png")
                          (URI. "https://static.wikia.nocookie.net/digimoncardgame/images/0/01/BT3-057_P2.png")
                          source))))}
   "BT3-068"
   {"ko" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    "【상대의 턴】 이 디지몬의 DP를 +1000 한다.")))}
   "BT3-112"
   {"zh-Hans" (fn [card]
                (assoc card :card/name "奥米加兽 Alter-S"))}
   "BT4-009"
   {"ko" (fn [card]
           (assoc card :card/attribute "배리어블"))}
   "BT4-010"
   {"ko" (fn [card]
           (dissoc card :card/inherited-effect))}
   "BT4-023"
   {"ko" (fn [card]
           (dissoc card :card/inherited-effect))}
   "BT4-026"
   {"ko" (fn [card]
           (assoc card :card/attribute "데이터종"))}
   "BT4-035"
   {"ko" (fn [card]
           (assoc card :card/attribute "데이터종"))}
   "BT4-046"
   {"ko" (fn [card]
           (assoc card :card/inherited-effect
                  "【자신의 턴】 자신의 시큐리티가 3장 이하인 동안, 이 디지몬의 DP를 +1000 한다."))}
   "BT4-047"
   {"ko" (fn [card]
           (assoc card :card/attribute "백신종"))}
   "BT4-049"
   {"ko" (fn [card]
           (assoc card :card/attribute "백신종"))}
   "BT4-105"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】≪リカバリー+1《デッキ》≫（自分のデッキの上からカードを1枚セキュリティの上に置く）")))}
   "BT4-113"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [effect]
                     (string/replace effect #"(\S)(\[Hybrid\])" "$1 $2"))))}
   "BT4-115"
   {"ko" (fn [card]
           (assoc card :card/attribute "백신종"))}
   "BT5-112"
   {"en" (fn [card]
           (dissoc card :card/security-effect))}
   "BT6-022"
   {"ko" (fn [card]
           (assoc card :card/attribute "배리어블"))}
   "BT6-027"
   {"ko" (fn [card]
           (cond-> card
             (nil? (:card/digimon-type card))
             (assoc :card/digimon-type "성룡형/데바")))}
   "BT6-044"
   {"ko" (fn [card]
           (assoc card :card/digimon-type "성기사형/로얄 나이츠"))}
   "BT6-049"
   {"ko" (fn [card]
           (assoc card :card/attribute "배리어블"))}
   "BT6-050"
   {"ja" (fn [card]
           (update card :card/digivolve-conditions
                   (fn [conditions]
                     (map #(assoc % :digivolve/cost 3) conditions))))}
   "BT6-053"
   {"ko" (fn [card]
           (assoc card :card/attribute "데이터종"))}
   "BT6-054"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [effect]
                     (string/replace effect
                                     "with Hybrid "
                                     "with [Hybrid] "))))}
   "BT6-064"
   {"ko" (fn [card]
           (assoc card :card/attribute "데이터종"))}
   "BT6-073"
   {"zh-Hans" (fn [card]
                (update card :card/inherited-effect
                        (fn [s]
                          (if (not (re-find #"^.我方的回合" s))
                            "【我方的回合】[每回合1次] 当因我方的效果丢弃我方的手牌时，内存值+1。"
                            s))))}
   "BT6-079"
   {"ko" (fn [card]
           (-> card
               (assoc :card/form "궁극체")
               (assoc :card/attribute "바이러스종")))}
   "BT6-081"
   {"ko" (fn [card]
           (assoc card :card/digimon-type "신인형"))}
   "BT6-090"
   {"ko" (fn [card]
           (assoc card :card/security-effect
                  "【시큐리티】 이 카드를 코스트를 지불하지 않고 등장시킨다."))}
   "BT6-095"
   {"ko" (fn [card]
           (assoc card :card/security-effect
                  "【시큐리티】 이 카드의 【메인】 효과를 발휘한다."))}
   "BT6-106"
   {"zh-Hans" (fn [card]
                (update card :card/security-effect
                        (fn [s]
                          (string/replace
                           s
                           "【安防】此卡牌加入手牌。"
                           "【安防】发动此卡牌的【主要】效果。"))))}
   "BT7-004"
   {"en" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> (dissoc card :card/effect)
             effect
             (assoc :card/inherited-effect effect)))}
   "BT7-016"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "용전사형")))}
   "BT7-025"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "전사형")))}
   "BT7-041"
   {"zh-Hans" (fn [card]
                (assoc card :card/name "建御雷兽"))}
   "BT7-044"
   {"zh-Hans" (fn [card]
                (assoc card :card/name "ベタモン"))}
   "BT7-070"
   {"zh-Hans" (fn [card]
                (assoc card
                       :card/inherited-effect
                       "【我方的回合】[每回合1次]当我方的驯兽师登场时,《抽1张卡》（从我方的卡组上方抽取1张卡牌）。"))}
   "BT7-085"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "【自分のターン】このデジモンをDP+2000。"
                         "このデジモンのDPが10000 以上の賞、"
                         "このデジモンは"
                         "≪セキュリティアタック+1≫"
                         "(このデジモンがチェックする枚数+1)"
                         "を得る。"))))}
   "BT7-086"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "【アタック時】［ターンに1回］"
                         "次の相手のターン終了まで、"
                         "進化を持たない相手のデジモン1"
                         "体はアタックとブロックができない。"))))}
   "BT7-087"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "【自分のターン】［ターンに1回］"
                         "自分の手が効菜で抑えたとき、メモリー+1。"
                         "その後、このターンの間、"
                         "このデジモンはブロックされない。"))))}
   "BT7-088"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "【相手のターン】自分のセキュリティデジモン全ての"
                         "DPを+3000する。"))))}
   "BT7-089"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "≪貫通≫（このデジモンがアタックしたバトルで相手のデジモンだけを消滅させたとき、このデジモンはセキュリティをチェックする）"))))}
   "BT7-090"
   {"en" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "[Security] Play this card without paying its memory cost.")))
    "zh-Hans" (fn [{:card/keys [security-effect] :as card}]
                (cond-> card
                  (not security-effect)
                  (assoc :card/security-effect
                         "【安防】不支付费用登场此卡牌。")))}
   "BT7-091"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    "【消滅時】メモリーを+1する。")))}
   "BT8-012"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“V仔兽” 起2")))))}
   "BT8-023"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“犰狳兽” 起2")))))}
   "BT8-026"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“麻鹰兽” 起2")))))}
   "BT8-032"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】名称中包含 “龙形态” 起2")))))}
   "BT8-038"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“V仔兽” 起3")))))}
   "BT8-039"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“犰狳兽” 起3")))))}
   "BT8-048"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“麻鹰兽” 起2")))))}
   "BT8-051"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“犰狳兽” 起2")))))}
   "BT8-053"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“V仔兽” 起2")))))}
   "BT8-082"
   {"zh-Hans" (fn [card]
                (update (assoc card :card/name "座天使兽：堕落形态")
                        :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“座天使的” 起2")))))}
   "BT8-111"
   {"zh-Hans" (fn [card]
                (assoc card :card/attribute "病毒种"))}
   "BT8-112"
   {"zh-Hans" (fn [card]
                (assoc card :card/name "帝皇龙甲兽：圣骑士形态"))}
   "BT9-008"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“亚古兽” 起0")))))}
   "BT9-009"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“基尔兽” 起0")))))}
   "BT9-011"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“古拉兽” 起0")))))}
   "BT9-012"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“暴龙兽” 起0")))))}
   "BT9-013"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“奥米加高吼兽” 起0")))))}
   "BT9-014"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“大古拉兽” 起0")))))}
   "BT9-015"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“机械暴龙兽” 起0")))))}
   "BT9-016"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“战斗暴龙兽” 起1")))))}
   "BT9-017"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“公爵兽” 起1")))))}
   "BT9-020"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“加布兽” 起0")))))}
   "BT9-023"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“伽马兽” 起2")))))}
   "BT9-024"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“加鲁鲁兽” 起0")))))}
   "BT9-028"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“狼人加鲁鲁兽” 起0")))))}
   "BT9-031"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“钢铁加鲁鲁兽” 起1")))))}
   "BT9-034"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“小狗兽” 起0")))))}
   "BT9-036"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“迪路兽” 起0")))))}
   "BT9-037"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】名称中包含“迪路兽” 起0")))))}
   "BT9-038"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“巴达兽” 起2")))))}
   "BT9-040"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“天女兽” 起0")))))}
   "BT9-041"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“跃升暴龙兽” 起1")))))}
   "BT9-043"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“圣龙兽” 起1")))))}
   "BT9-044"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“金甲龙兽” 起4")))))}
   "BT9-046"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“小锹形虫兽” 起0")))))}
   "BT9-049"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (or (nil? s)
                                (not (re-find #"^.?进化[】:]" s)))
                            (str "【进化】“古加兽” 起0")))))}
   "BT9-050"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“狮子兽” 起0")))))}
   "BT9-051"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“白狮兽” 起0")))))}
   "BT9-052"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“大古加兽” 起0")))))}
   "BT9-055"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“巨大古加兽” 起1")))))}
   "BT9-056"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“剑狮兽” 起1")))))}
   "BT9-068"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“黑暗战斗暴龙兽” 起2")))))}
   "BT9-070"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“加支兽” 起0")))))}
   "BT9-075"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“多路加兽” 起0")))))}
   "BT9-078"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“多路暴龙兽” 起1")))))}
   "BT9-081"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“多路战龙兽” 起2")))))}
   "BT9-082"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (string/starts-with? s "《合步:"))
                            (str "《合步:紫Lv.6+黄Lv.6起0》"
                                 "将指定的2只数码宝贝重叠,"
                                 "以活跃状态进化")))))}
   "BT9-083"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】名称中包含“奥米加兽” 起3")))))}
   "BT9-104"
   {"en" (fn [card]
           (assoc card :card/digimon-type "X Antibody"))}
   "BT9-106"
   {"en" (fn [card]
           (assoc card :card/digimon-type "X Antibody"))}
   "BT9-109"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "お互いのターン】このデジモンの進化売の"
                         "「X抗体」は効菓て愛菜できない。"
                         "【アタック時】このデジモンを、手の特徴に"
                         "「X抗体」を持つ"
                         "デジモンカード1枚にコストを支払って進化できる。"))))
    "en" (fn [card]
           (-> card
               (assoc :card/digimon-type "X Antibody")
               (assoc :card/inherited-effect
                      "[All Turns] Effects can't trash [X Antibody] in this Digimon's digivolution cards.[When Attacking] This Digimon can digivolve into a Digimon card with [X Antibody] in its traits in your hand for its digivolution cost.")))}
   "BT9-111"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】进化源中存在“王龙兽”的“阿尔法兽”"
                                 " 起3")))))}
   "BT10-007"
   {"zh-Hans" (fn [{:card/keys [effect] :as card}]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not effect)
                            (str "【进化】拥有“Xros Heart”特征的Lv.2 起0")))))}
   "BT10-008"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.2 起0")))))}
   "BT10-009"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “高吼兽”×“弩炮兽”×“多鲁路兽”×“星星兽S”")))))}
   "BT10-011"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】名称中包含“伽马兽”的Lv.4 起3")))))}
   "BT10-012"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “高吼兽X4”×“别西卜兽”")))))}
   "BT10-013"
   {"ja" (fn [card]
           (assoc card :card/color [:red :black]))
    "zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “高吼兽”×“弩炮兽”×“多鲁路兽”×"
                                 "“星星兽S”×“麻雀兽”")))))}
   "BT10-015"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “高吼兽X5”×“别西卜兽”")))))}
   "BT10-016"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“杰斯兽” 起0")))))}
   "BT10-024"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “暴龙兽”×“铠甲鸟龙兽”")))))}
   "BT10-026"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “机械暴龙兽”×“甲板龙兽”")))))}
   "BT10-029"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.2 起0")))))}
   "BT10-031"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“哔哔兽” 起0")))))}
   "BT10-034"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.3 起2")))))}
   "BT10-049"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.3 起2")))))}
   "BT10-050"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“伽马兽” 起2")))))}
   "BT10-060"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.2 起0")))))}
   "BT10-061"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-1≫"
                                 " “骷髅骑士兽”×“致命巨斧兽”")))))}
   "BT10-063"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (or (nil? s)
                                (not (re-find #"≪数码合体" s)))
                            (str "≪数码合体-2≫"
                                 " “显示屏兽”×“显示屏兽”×“显示屏兽”")))))}
   "BT10-067"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】名称中包含“正义兽” 起1")))))}
   "BT10-066"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫"
                                 " “骷髅骑士兽”×“致命巨斧兽”")))))}
   "BT10-068"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“顽固兽” 起1")))))}
   "BT10-069"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“黑暗骑士兽” 起4")))))}
   "BT10-077"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫1张拥有"
                                 " “巴格拉军” 特征的数码宝贝卡牌")))))}
   "BT10-078"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“伽马兽” 起2")))))}
   "BT10-082"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“Xros Heart”特征的Lv.5 起3")))))}
   "BT10-086"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】“奥米加兽” 起3")))))}
   "BT10-104"
   {"ja" (fn [card]
           (assoc card :card/color [:black :purple]))}
   "BT10-111"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond-> s
                            (not (re-find #"≪数码合体" s))
                            (str "\n≪数码合体-2≫1张拥有"
                                 " “Xros Heart” 特征的数码宝贝卡牌")))))}
   "BT10-112"
   {"zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (re-find #"^.?进化[】:]" s))
                            (str "【进化】拥有“皇家骑士”特征的Lv.6 起5")))))}
   "BT11-019"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (let [effect (some-> s
                                          (string/replace "[Sparrowmon]＜Rush＞"
                                                          "[Sparrowmon]\n＜Rush＞"))]
                       (some-> effect
                               (string/replace #"^\[DigiXros.*?\n" "")
                               (str "\n"
                                    "DigiXros -2: "
                                    "[OmniShoutmon] × [ZeigGreymon] × "
                                    "[Ballistamon] × [Dorulumon] × "
                                    "[Starmons] × [Sparrowmon]"))))))}
   "BT12-084"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (some-> s
                             (string/replace #"^\[DigiXros.*?\n" "")
                             (str "\n"
                                  "DigiXros -3: "
                                  "[Mervamon] × [Sparrowmon]")))))}
   "BT12-088"
   {"ja" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "【自分のターン】このデジモンをDP+2000。"
                         "このデジモンのDPが10000 以上の賞、"
                         "このデジモンは"
                         "「【自分のターン】［ターンに1回］このデジモン"
                         "が相手のセキュリティをチェックしたとき、"
                         "メモリー+2。」。"))))
    "en" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    (str "[Your Turn] This Digimon gets +2000 DP."
                         " While this Digimon has 10000 DP or more,"
                         " it gains \"[Your Turn][Once Per Turn]"
                         " When this Digimon checks your opponent's "
                         "security stack, gain 2 memory.\""))))}
   "BT13-110"
   {"zh-Hans" (fn [{:card/keys [digimon-type] :as card}]
                (cond-> card
                  (not digimon-type)
                  (assoc :card/digimon-type "皇家骑士")))}
   "BT17-094"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】自分の手札か/トラッシュから、進化元効果を持つテイマーカード1枚をコストを支払わずに登場できる。その後、このカードを手札に加える。")))}
   "BT17-095"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】自分の手札か/トラッシュから、名称に「八神太一」/「石田ヤマト」を含むカード1枚をコストを支払わずに登場できる。その後、このカードを手札に加える。")))}
   "BT17-096"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】このカードの【メイン】効果を発揮する。")))}
   "BT17-097"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】自分の手札か/トラッシュから、名称に「本宮大輔」/「一乘寺賢」を含むカード1枚をコストを支払わずに登場できる。その後、このカードをバトルエリアに置く。")))}
   "BT17-098"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】自分のデッキの上から3枚オープンする。その中の「パルスモン」の記述があるカード1枚を手札に加える。残りはデッキの下に戻す。その後、このカードをバトルエリアに置く。")))}
   "BT17-099"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】自分の手札か/トラッシュから、名称に「大門大」/「リズム」を含むカード1枚をコストを支払わずに登場できる。その後、このカードをバトルエリアに置く。")))}
   "EX1-029"
   {"ko" (fn [{:card/keys [effect] :as card}]
           (cond-> card
             (not effect)
             (assoc :card/effect
                    (str "【어택 시】 자신의 시큐리티가 3장 이상일 때, "
                         "다음 상대의 턴 종료 시까지 이 디지몬의 DP를 "
                         "+4000 한다."))))}
   "EX2-001"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "렛서형")))}
   "EX2-002"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "렛서형")))}
   "EX2-003"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "렛서형")))}
   "EX2-004"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "렛서형")))}
   "EX2-005"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "유룡형")))}
   "EX2-006"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "렛서형")))}
   "EX2-007"
   {"ko" (fn [{:card/keys [digimon-type] :as card}]
           (cond-> card
             (not digimon-type)
             (assoc :card/digimon-type "능력통합 타입")))}
   "EX2-044"
   {"ja" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> (dissoc card :card/inherited-effect)
             (and (not effect)
                  inherited-effect)
             (assoc :card/effect inherited-effect)))}
   "EX2-071"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】このカードの【メイン】効果を発揮する。")))}
   "EX3-004"
   {"en" (fn [{:card/keys [effect] :as card}]
           (cond-> card
             (not effect)
             (assoc :card/effect
                    (str "[On Play] By trashing 1 card with [Imperialdramon]"
                         " in its name or [Free] in its traits in your hand,"
                         " ＜Draw 2＞. (Draw 2 cards from your deck.)"))))}
   "EX3-005"
   {"en" (fn [{:card/keys [effect] :as card}]
           (cond-> card
             (not effect)
             (assoc :card/effect
                    (str "[Your Turn][Once Per Turn]"
                         " When you play [Hina Kurihara],"
                         " delete 1 of your opponent's Digimon with 3000 DP"
                         " or less."))))}
   "EX3-007"
   {"en" (fn [{:card/keys [effect] :as card}]
           (cond-> card
             (not effect)
             (assoc :card/effect
                    (str "[On Play] Reveal the top 4 cards of your deck. Add 1 Digimon card with [Rock Dragon], [Earth Dragon], [Bird Dragon], [Machine Dragon], or [Sky Dragon] in its traits and 1 [Hina Kurihara] among them to your hand. Place the rest at the bottom of your deck in any order."))))}
   "EX4-012"
   {"ja" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (some-> s
                             (string/replace "【進化】『名称に「メガログラウモン」を含むLv.5』から3"
                                             "")))))}
   "EX5-001"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-002"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-003"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-004"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-005"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-006"
   {"en" (fn [card]
           (dissoc card :card/effect))}
   "EX5-071"
   {"zh-Hans" (fn [card]
                (-> card
                    (assoc :card/security-effect (:card/inherited-effect card))
                    (dissoc :card/inherited-effect)))}
   "EX5-072"
   {"zh-Hans" (fn [card]
                (-> card
                    (assoc :card/security-effect (:card/inherited-effect card))
                    (dissoc :card/inherited-effect)))}
   "EX6-011"
   {"ja" (fn [card]
           (assoc card :card/inherited-effect
                  "≪オーバーフロー《-5》≫（バトルエリアかカードの下から、それ以外の場所に送られる場合、メモリー-5）"))}
   "EX6-035"
   {"ja" (fn [card]
           (assoc card :card/inherited-effect
                  "≪オーバーフロー《-4》≫（バトルエリアかカードの下から、それ以外の場所に送られる場合、メモリー-4）"))}
   "LM-027"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、赤のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "LM-028"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、青のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "LM-029"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、黄のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "LM-030"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、緑のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "LM-031"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、黒のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "LM-032"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (-> (assoc :card/security-effect
                        "【セキュリティ】自分のトラッシュから、紫のDP2000以下のデジモンカード1枚をコストを支払わずに登場できる。 その後、このカードを手札に加える。"))))}
   "RB1-018"
   {"en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (some-> s
                             (string/replace #"(?<!\[On Play\])(\[When Digivolving\])"
                                             "[On Play]$1")))))}
   "RB1-026"
   {"en" (fn [{:card/keys [inherited-effect effect] :as card}]
           (cond-> card
             (and (not inherited-effect)
                  effect)
             (-> (assoc :card/inherited-effect effect)
                 (dissoc :card/effect))))}
   "ST1-12"
   {"en" (fn [card]
           (dissoc card :card/attribute))}
   "ST1-16"
   {"ko" (fn [card]
           (assoc card :card/effect
                  "【메인】 상대의 디지몬 1마리를 소멸시킨다."))}
   "ST2-06"
   {"en" (fn [card]
           (assoc card :card/name "Garurumon"))}
   "ST2-09"
   {"ja" (fn [card]
           (assoc card :card/rarity "U"))}
   "ST3-04"
   {"ko" (fn [card]
           (assoc card :card/digimon-type "포유류형"))}
   "ST3-05"
   {"zh-Hans" (fn [card]
                (assoc card
                       :card/inherited-effect
                       "【攻击时】当我方持有4张或更多的安防卡牌时,内存值+1。"))}
   "ST3-11"
   {"ko" (fn [card]
           (assoc card :card/attribute "백신종"))}
   "ST4-16"
   {"ko" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【시큐리티】 이 카드의 【메인】 효과를 발휘한다.")))}
   "ST5-01"
   {"ko" (fn [{:card/keys [form digimon-type] :as card}]
           (cond-> card
             (not form) (assoc :card/form "유년기")
             (not digimon-type) (assoc :card/digimon-type "렛서형")))}
   "ST6-05"
   {"ko" (fn [{:card/keys [form attribute digimon-type] :as card}]
           (cond-> card
             (not form) (assoc :card/form "성장기")
             (not attribute) (assoc :card/attribute "바이러스종")
             (not digimon-type) (assoc :card/digimon-type "포유류형,포유류형")))}
   "ST7-11"
   {"zh-Hans" (fn [{:card/keys [inherited-effect effect] :as card}]
                (cond-> (dissoc card :card/inherited-effect)
                  inherited-effect
                  (assoc :card/security-effect inherited-effect)))}
   "ST10-01"
   {"en" (fn [{:card/keys [inherited-effect effect] :as card}]
           (cond-> card
             (and (not inherited-effect)
                  effect)
             (-> (assoc :card/inherited-effect effect)
                 (dissoc :card/effect))))}
   "ST12-09"
   {"ja" (fn [card]
           (assoc card :card/color [:red :black]))}
   "ST13-06"
   {"ja" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (cond->> s
                       (not (string/starts-with? s "≪ジョグレス:"))
                       (str "≪ジョグレス:赤Lv.6+黒Lv.6から0≫"
                            "指定のデジモン2体を重ね、"
                            "アクティブで進化する")))))
    "zh-Hans" (fn [card]
                (update card :card/effect
                        (fn [s]
                          (cond->> s
                            (not (string/starts-with? s "《合步:"))
                            (str "《合步:红Lv.6+黑Lv.6起0》"
                                 "将指定的2只数码宝贝重叠,"
                                 "以活跃状态进化")))))}
   "ST13-16"
   {"zh-Hans" (fn [card]
                (assoc card :card/digimon-type "Legend-Arms"))}
   "ST14-12"
   {"ja" (fn [{:card/keys [security-effect] :as card}]
           (cond-> card
             (not security-effect)
             (assoc :card/security-effect
                    "【セキュリティ】最もLv.が驚い箱手のデジモン1体を消滅させる。")))}
   "ST15-08"
   {"en" (fn [card]
           (dissoc card :card/security-effect))}
   "ST16-08"
   {"en" (fn [card]
           (dissoc card :card/security-effect))}
   "PR-004"
   {"ko" (fn [card]
           (assoc card :card/number "P-004"))}
   "P-007"
   {"ko" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> card
             (and effect
                  (not inherited-effect))
             (-> (assoc :card/inherited-effect effect)
                 (dissoc :card/effect))))}
   "P-008"
   {"ko" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> card
             (and effect
                  (not inherited-effect))
             (-> (assoc :card/inherited-effect effect)
                 (assoc :card/effect
                        "【어택 시】 [턴에 1회] 이 디지몬의 진화원에 「가루몬」이 있을 때, 이 디지몬을 액티브로 한다."))))}
   "P-009"
   {"ko" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> card
             (and effect
                  (not inherited-effect))
             (-> (assoc :card/inherited-effect effect)
                 (dissoc :card/effect))))}
   "P-010"
   {"ko" (fn [{:card/keys [effect] :as card}]
           (cond-> card
             (not effect)
             (assoc :card/effect
                    "【자신의 턴】이 디지몬의 진화원에 「아구몬」이 있는 동안, 《시큐리티 어택 +1》(이 디지몬이 시큐리티에 어택했을 때에 체크하는 매수+1)를 얻는다.")))}
   "P-011"
   {"ko" (fn [{:card/keys [effect inherited-effect] :as card}]
           (cond-> card
             (and effect
                  (not inherited-effect))
             (-> (assoc :card/inherited-effect effect)
                 (assoc :card/effect
                        "【어택 시】파랑의 자신의 테이머가 있다면, 자신의 덱 위에서 3장 파기하는 것으로, 이 턴 동안, 이 디지몬을 DP+2000."))))}
   "P-012"
   {"ko" (fn [card]
           (update card :card/security-effect
                   (fn [s]
                     (and s
                          (cond->> s
                            (not (string/starts-with? s "【시큐리티】"))
                            (str "【시큐리티】 "))))))}
   "P-023"
   {"ja" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (string/replace s "高石 タケル" "高石タケル"))))}
   "P-030"
   {"ko" (fn [{:card/keys [inherited-effect] :as card}]
           (cond-> card
             (not inherited-effect)
             (assoc :card/inherited-effect
                    "【자신의턴】 이디지몬이패의 「에이션트가루몬」 으로진화할때, 지불하는진화코스트를-2한다.")))}
   "P-048"
   {"ja" (fn [card]
           (update card :card/digivolve-conditions
                   (fn [conditions]
                     (map #(assoc % :digivolve/cost 4) conditions))))}
   "P-057"
   {"zh-Hans" (fn [{:card/keys [inherited-effect] :as card}]
                (cond-> card
                  (not inherited-effect)
                  (assoc :card/inherited-effect
                         "【我方的回合】 此数码宝贝为LV.6或更高等级的期间, 此 数码宝贝的DP+2000。")))}
   "P-062"
   {"en" (fn [card]
           (assoc card :card/security-effect
                  "[Security] Play this card without paying its memory cost."))}
   "P-063"
   {"en" (fn [card]
           (assoc card :card/security-effect
                  "[Security] Play this card without paying its memory cost."))}
   "P-064"
   {"en" (fn [card]
           (assoc card :card/security-effect
                  "[Security] Play this card without paying its memory cost."))}
   "P-066"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> card
             (and (not effect)
                  security-effect)
             (-> (assoc :card/effect security-effect)
                 (dissoc :card/security-effect))))}
   "P-067"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> (dissoc card :card/security-effect)
             security-effect
             (assoc :card/effect security-effect)))}
   "P-068"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> (dissoc card :card/security-effect)
             security-effect
             (assoc :card/effect security-effect)))}
   "P-069"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> (dissoc card :card/security-effect)
             security-effect
             (assoc :card/effect security-effect)))}
   "P-070"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> card
             (and (not effect)
                  security-effect)
             (-> (assoc :card/effect security-effect)
                 (dissoc :card/security-effect))))}
   "P-071"
   {"en" (fn [{:card/keys [effect security-effect] :as card}]
           (cond-> (dissoc card :card/security-effect)
             security-effect
             (assoc :card/effect security-effect)))}
   "P-072"
   {"ja" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (cond->> s
                       (not (re-find #"^.?進化" s))
                       (str "【進化】「メタルグレイモン」から0")))))
    "en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (and s
                          (cond->> s
                            (not (re-find #"^.?Digivolve" s))
                            (str "[Digivolve] 0 from [MetalGreymon]"))))))}
   "P-073"
   {"ja" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (cond->> s
                       (not (re-find #"^.?進化" s))
                       (str "【進化】「ワーガルルモン」から0")))))
    "en" (fn [card]
           (update card :card/effect
                   (fn [s]
                     (and s
                          (cond->> s
                            (not (re-find #"^.?Digivolve" s))
                            (str "[Digivolve] 0 from [WereGarurumon]"))))))}
   "P-133"
   {"en" (fn [card]
           (dissoc card :card/attribute))}
   "P-136"
   {"en" (fn [card]
           (dissoc card :card/attribute))}})

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
          (string/replace #"(DigiXros\s?[\-\+][0-9]+):" "[$1]")
          (string/replace #"(^ジョグレス.*?ら0)" "≪$1≫")
          (string/replace #"(^DNA Digivolution:.*?Lv\.[0-9].*?Lv\.[0-9])"
                          "＜$1＞")
          (string/replace #"(進化):" "【$1】")
          (string/replace "［(進化)］" "【$1】")
          (string/replace #"(Digivolve):" "[$1]")
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
