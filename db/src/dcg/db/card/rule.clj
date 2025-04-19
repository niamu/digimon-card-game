(ns dcg.db.card.rule
  "Card rule revisions"
  (:require
   [clojure.string :as string]
   [dcg.db.card.highlight :as highlight]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]))

(def ^:private repair-text
  {"BT6-084"
   {"ja" (fn [s]
           (string/replace s
                           "※カード名に「シスタモン シエル」特徴:「データ種」としても扱う。"
                           "※カード名に「シスタモン シエル」を持つカードとしても扱う\n※属性に「データ種」を持つカードとしても扱う"))}
   "BT10-061"
   {"en" (fn [s]
           (string/replace s
                           "This card/Digimon is also treated as if its name is [SkullKnightmon] and [DeadlyAxemon]."
                           "The name of this card/Digimon is also treated as [SkullKnightmon] and [DeadlyAxemon]."))}
   "BT10-111"
   {"en" (fn [s]
           (string/replace s
                           "The name of this card/Digimon is also treated as [Shoutmon]."
                           "This card/Digimon is also treated as if its name is [Shoutmon]."))}
   "P-072"
   {"en" (fn [s]
           (string/replace s
                           "The name of this card/Digimon is also treated as [MetalGreymon]."
                           "Treat this card/Digimon as if its name is also [MetalGreymon]."))}
   "P-073"
   {"en" (fn [s]
           (string/replace s
                           "The name of this card/Digimon is also treated as [WereGarurumon]."
                           "Treat this card/Digimon as if its name is also [WereGarurumon]."))}
   "RB1-032"
   {"ja" (fn [s]
           (string/replace s
                           "メモリー+1し、≪1ドロー≫"
                           "メモリー+1、≪1ドロー≫し"))}})

(defmulti rules
  (fn [{:origin/keys [language card-image-language]}]
    (and (not card-image-language)
         language)))

(defmethod rules :default [_] nil)

(defmethod rules "ja"
  [{:origin/keys [url language]}]
  (let [rule-title-re #"^〈ルール〉に変更されたカード$"
        draw-memory-title-re #"「≪1ドロー≫し、メモリー\+1」に変更されるカード"]
    (->> (utils/http-get (str url "/rule/revised/"))
         hickory/parse
         hickory/as-hickory
         (select/select
          (select/descendant
           (select/or
            (select/and
             (select/class "accordionTitle")
             (select/has-child
              (select/find-in-text rule-title-re)))
            (select/descendant (select/follow-adjacent
                                (select/and
                                 (select/class "accordionTitle")
                                 (select/has-child
                                  (select/find-in-text rule-title-re)))
                                select/element)
                               (select/class "changelist"))
            (select/and
             (select/class "accordionTitle")
             (select/has-child
              (select/find-in-text draw-memory-title-re)))
            (select/descendant (select/follow-adjacent
                                (select/and
                                 (select/class "accordionTitle")
                                 (select/has-child
                                  (select/find-in-text draw-memory-title-re)))
                                select/element)
                               (select/class "changelist")))))
         (partition-all 2)
         (map (fn [[section-heading changelist]]
                (->> changelist
                     :content
                     (filter map?)
                     (map (fn [el]
                            (select/select
                             (select/or (select/tag :th)
                                        (select/follow-adjacent
                                         (select/and (select/tag :td)
                                                     (select/class "changeBefore"))
                                         (select/tag :td))
                                        (select/follow-adjacent
                                         (select/and (select/tag :td)
                                                     (select/class "changeAfter"))
                                         (select/tag :td)))
                             el)))
                     (reduce (fn [accl [heading before after]]
                               (let [number (re-find card-utils/card-number-re
                                                     (card-utils/text-content heading))
                                     repair-fn (get-in repair-text [number language])
                                     before (if (= (card-utils/text-content section-heading)
                                                   "「≪1ドロー≫し、メモリー+1」に変更されるカード")
                                              (-> (select/select
                                                   (select/descendant
                                                    (select/and (select/tag :span)
                                                                (select/class "textR")
                                                                (select/class "txtBold")))
                                                   before)
                                                  first
                                                  card-utils/text-content
                                                  (string/replace "。" "")
                                                  (cond-> #__
                                                    repair-fn repair-fn))
                                              (cond-> (card-utils/text-content before)
                                                repair-fn repair-fn))
                                     after (if (= (card-utils/text-content section-heading)
                                                  "「≪1ドロー≫し、メモリー+1」に変更されるカード")
                                             (->> (string/split before #"、")
                                                  reverse
                                                  (string/join "、"))
                                             (card-utils/text-content after))]
                                 (assoc-in accl [number language]
                                           {:before before
                                            :after after})))
                             {}))))
         (apply merge))))

(defmethod rules "en"
  [{:origin/keys [url language]}]
  (let [rule-title-re #"Cards with changes to ⟨Rule⟩"]
    (->> (utils/http-get (str url "/rule/revised/"))
         hickory/parse
         hickory/as-hickory
         (select/select
          (select/descendant
           (select/or
            (select/and
             (select/class "accordionTitle")
             (select/has-child
              (select/find-in-text rule-title-re)))
            (select/descendant (select/follow-adjacent
                                (select/and
                                 (select/class "accordionTitle")
                                 (select/has-child
                                  (select/find-in-text rule-title-re)))
                                select/element)
                               (select/class "changelist")))))
         (partition-all 2)
         (map (fn [[section-heading changelist]]
                (->> changelist
                     :content
                     (filter map?)
                     (map (fn [el]
                            (select/select
                             (select/or (select/tag :th)
                                        (select/follow-adjacent
                                         (select/and (select/tag :td)
                                                     (select/class "changeBefore"))
                                         (select/tag :td))
                                        (select/follow-adjacent
                                         (select/and (select/tag :td)
                                                     (select/class "changeAfter"))
                                         (select/tag :td)))
                             el)))
                     (reduce (fn [accl [heading before after]]
                               (let [number (re-find card-utils/card-number-re
                                                     (card-utils/text-content heading))
                                     repair-fn (get-in repair-text [number language])
                                     before (cond-> (card-utils/text-content before)
                                              repair-fn repair-fn)
                                     after (-> (card-utils/text-content after)
                                               (string/replace "⟨Rule⟩ Name: Also treated as [Sistermon Ciel (Awakened)], and Trait: Has [Data] attribute."
                                                               "⟨Rule⟩ Name: Also treated as [Sistermon Noir (Awakened)], and Trait: Has [Virus] attribute."))]
                                 (assoc-in accl [number language]
                                           {:before before
                                            :after after})))
                             {}))))
         (apply merge
                {"BT6-087"
                 {"en" {:before "gain 1 memory and trigger ＜Draw 1＞. (Draw 1 card from your deck.)"
                        :after "trigger ＜Draw 1＞ (Draw 1 card from your deck) and gain 1 memory"}}
                 "BT6-088"
                 {"en" {:before "gain 1 memory and trigger ＜Draw 1＞. (Draw 1 card from your deck.)"
                        :after "trigger ＜Draw 1＞ (Draw 1 card from your deck) and gain 1 memory"}}
                 "BT8-028"
                 {"en" {:before "gain 1 memory and trigger ＜Draw 1＞. (Draw 1 card from your deck.)"
                        :after "trigger ＜Draw 1＞ (Draw 1 card from your deck) and gain 1 memory"}}
                 "BT8-092"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "BT9-092"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "BT11-092"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "BT11-095"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "BT13-102"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "BT17-052"
                 {"en" {:before "gain 1 memory and ＜Draw 1＞"
                        :after "＜Draw 1＞ and gain 1 memory"}}
                 "EX1-069"
                 {"en" {:before "gain 2 memory and ＜Draw 1＞ (Draw 1 card from your deck)"
                        :after "＜Draw 1＞ (Draw 1 card from your deck) and gain 2 memory"}}
                 "EX2-017"
                 {"en" {:before "Gain 2 memory and ＜Draw 1＞ (Draw 1 card from your deck)"
                        :after "＜Draw 1＞ (Draw 1 card from your deck) and gain 2 memory"}}
                 "EX2-045"
                 {"en" {:before "gain 1 memory, ＜Draw 1＞ (Draw 1 card from your deck)"
                        :after "＜Draw 1＞ (Draw 1 card from your deck), gain 1 memory"}}
                 "RB1-032"
                 {"en" {:before "gain 1 memory and <Draw 1>"
                        :after "<Draw 1> and gain 1 memory"}}}))))

(defmethod rules "zh-Hans"
  [{:origin/keys [language]}]
  ;; Ideally we'd parse this image: https://digimoncard.cn/ruleinfo?id=55
  ;; But since OCR isn't 100% reliable, here's a static map
  {"BT8-061"
   {language {:before "此卡牌/数码宝贝的名称也视为“豆豆兽”。"
              :after "\u3008规则\u3009名称:也视为“豆豆兽”。"}}
   "BT8-062"
   {language {:before "此卡牌/数码宝贝的名称也视为“骷髅骑士兽”和“致命巨斧兽”。"
              :after "\u3008规则\u3009名称:也视为“骷髅骑士兽”/“致命巨斧兽”。"}}
   "BT10-061"
   {language {:before "此卡牌/数码宝贝的名称也视为“骷髅骑士兽”和“致命巨斧兽”。"
              :after "\u3008规则\u3009名称:也视为“骷髅骑士兽”/“致命巨斧兽”。"}}
   "BT10-111"
   {language {:before "此卡牌/数码宝贝的名称也视为“高吼兽”。"
              :after "\u3008规则\u3009名称:也视为“高吼兽”。"}}
   "BT11-009"
   {language {:before "此卡牌/数码宝贝的名称也视为“高吼兽”和“星星兽S”。"
              :after "\u3008规则\u3009名称:也视为“高吼兽”/“星星兽S”。"}}
   "BT11-018"
   {language {:before "此卡牌/数码宝贝的名称也视为“奥米加高吼兽”和“吉克暴龙兽”。"
              :after "\u3008规则\u3009名称:也视为“奥米加高吼兽”/“吉克暴龙兽”。"}}
   "BT11-030"
   {language {:before "此卡牌/数码宝贝的名称也视为“机械暴龙兽”和“电子龙兽”。"
              :after "\u3008规则\u3009名称:也视为“机械暴龙兽”/“电子龙兽”。"}}
   "BT11-063"
   {language {:before "此卡牌/数码宝贝的名称也视为“鼻涕兽”。"
              :after "\u3008规则\u3009名称:也视为“鼻涕兽”。"}}
   "BT11-071"
   {language {:before "此卡牌/数码宝贝也视为“黑暗骑士兽”和“强者兽”。"
              :after "\u3008规则\u3009名称:也视为“黑暗骑士兽”/“强者兽”。"}}
   "EX2-012"
   {language {:before "此卡牌/数码宝贝的名称也视为“混沌公爵兽”。"
              :after "\u3008规则\u3009名称:也视为“混沌公爵兽”。"}}
   "EX3-064"
   {language {:before "此卡牌/数码宝贝也视为“混沌公爵兽”。"
              :after "\u3008规则\u3009名称:也视为“混沌公爵兽”。"}}
   "EX4-026"
   {language {:before "此卡牌/数码宝贝的名称也视为“九尾兽”。"
              :after "\u3008规则\u3009名称:也视为“九尾兽”。"}}
   "EX4-028"
   {language {:before "此卡牌/数码宝贝的名称也视为“道士兽”。"
              :after "\u3008规则\u3009名称:也视为“道士兽”。"}}
   "EX4-033"
   {language {:before "此卡牌/数码宝贝也视为“梗犬兽”。"
              :after "\u3008规则\u3009名称:也视为“梗犬兽”。"}}
   "P-072"
   {language {:before "此卡牌/数码宝贝的名称也视为“机械暴龙兽”。"
              :after "\u3008规则\u3009名称:也视为“机械暴龙兽”。"}}
   "P-073"
   {language {:before "此卡牌/数码宝贝的名称也视为“狼人加鲁鲁兽”。"
              :after "\u3008规则\u3009名称:也视为“狼人加鲁鲁兽”。"}}
   "EX4-062"
   {language {:before "此卡牌/驯兽师的名称也视为“苍沼切羽”/“天野音音”。"
              :after "\u3008规则\u3009名称:也视为“苍沼切羽”/“天野音音”。"}}
   "BT6-084"
   {language {:before "也视为卡牌名称“修女兽天蓝”特征“数据种”。"
              :after "\u3008规则\u3009名称:也视为“修女兽天蓝”,特征:“数据种”。"}}
   "BT7-083"
   {language {:before "※此卡也视为卡牌名称拥有“修女兽天蓝（觉醒）”的卡牌\n※此卡也视为拥有“数据种”特征的卡牌"
              :after "\u3008规则\u3009名称:也视为“修女兽天蓝（觉醒）”,特征:“数据种”。"}}
   "P-101"
   {language {:before "此卡牌/数码宝贝也视为拥有“改造型”特征。"
              :after "\u3008规则\u3009特征:拥有类型“改造型”。"}}
   "RB1-004"
   {language {:before "※卡牌编号:也视为“P-009”,与“P-009”合计最多能在卡组中放入4张。"
              :after "\u3008规则\u3009卡牌编号:也视为“P-009”,与“P-009”合计最多能在卡组中放入4张。"}}
   "RB1-006"
   {language {:before "※卡牌编号:也视为“P-058”,与“P-058”合计最多能在卡组中放入4张。"
              :after "\u3008规则\u3009卡牌编号:也视为“P-058”,与“P-058”合计最多能在卡组中放入4张。"}}
   "RB1-007"
   {language {:before "※卡牌编号:也视为“P-010”,与“P-010”合计最多能在卡组中放入4张。"
              :after "\u3008规则\u3009卡牌编号:也视为“P-010”,与“P-010”合计最多能在卡组中放入4张。"}}
   "BT9-051"
   {language {:before "此卡牌/数码宝贝也视为名称中包含“狮子兽”。"
              :after "\u3008规则\u3009名称:也视为包含“狮子兽”。"}}
   "BT9-068"
   {language {:before "此卡牌/数码宝贝也视为名称中包含“暴龙兽”。"
              :after "\u3008规则\u3009名称:也视为包含“暴龙兽”。"}}
   "BT11-054"
   {language {:before "此卡牌/数码宝贝也视为名称中包含“狮子兽”。"
              :after "\u3008规则\u3009名称:也视为包含“狮子兽”。"}}
   "EX4-030"
   {language {:before "此卡牌/数码宝贝也视为名称中包含“咲耶兽”。"
              :after "\u3008规则\u3009名称:也视为包含“咲耶兽”。"}}
   "EX4-048"
   {language {:before "此卡牌/数码宝贝也视为名称中包含“暴龙兽”。"
              :after "\u3008规则\u3009名称:也视为包含“暴龙兽”。"}}
   "EX4-072"
   {language {:before "此卡牌也视为名称中包含“插件”。"
              :after "\u3008规则\u3009名称:也视为包含“插件”。"}}
   "BT6-085"
   {language {:before "卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"
              :after "\u3008规则\u3009卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"}}
   "EX2-046"
   {language {:before "卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"
              :after "\u3008规则\u3009卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"}}
   "BT11-061"
   {language {:before "卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"
              :after "\u3008规则\u3009卡组中可以放入最多50张卡牌编号与此卡牌相同的卡牌。"}}})

(defmethod rules "ko"
  [{:origin/keys [language]}]
  ;; No official document for this language exists yet, but this static map
  ;; resolves all of the same issues consistently.
  {"BT10-061"
   {language {:before "이 카드/디지몬의 명칭은 「스컬나이트몬」과 「데들리액스몬」으로도 취급한다다."
              :after "〈룰〉명칭: 「스컬나이트몬」/「데들리 악스몬」으로도 취급한다."}}
   "BT10-111"
   {language {:before "이 카드/디지몬의 명칭은 「샤우트몬」으로도 취급한다."
              :after "〈룰〉명칭: 「샤우트몬」으로도 취급한다."}}
   "BT11-009"
   {language {:before "〈룰〉명칭: 「샤우트몬」/「스타몬즈」로도 취급한다."
              :after "〈룰〉명칭: 「샤우트몬」/「스타몬즈」로도 취급한다."}}
   "BT11-018"
   {language {:before "〈룰〉명칭: 「오메가샤우트몬」/「지크그레이몬」으로도 취급한다."
              :after "〈룰〉명칭: 「오메가샤우트몬」/「지크그레이몬」으로도 취급한다."}}
   "BT11-030"
   {language {:before "〈룰〉명칭: 「메탈그레이몬」/「사이버드라몬」으로도 취급한다."
              :after "〈룰〉명칭: 「메탈그레이몬」/「사이버드라몬」으로도 취급한다."}}
   "BT11-061"
   {language {:before "〈룰〉이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."
              :after "〈룰〉이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."}}
   "BT11-063"
   {language {:before "〈룰〉명칭: 「워매몬」으로도 취급한다."
              :after "〈룰〉명칭: 「워매몬」으로도 취급한다."}}
   "BT11-071"
   {language {:before "〈룰〉명칭: 「다크나이트몬」/「츠와몬」으로도 취급한다."
              :after "〈룰〉명칭: 「다크나이트몬」/「츠와몬」으로도 취급한다."}}
   "BT6-084"
   {language {:before "※ 명칭 : 「시스터몬 시엘」, 특징 「데이터종」으로도 취급한다"
              :after "〈룰〉명칭: 「시스터몬 시엘」로도 취급하며, 특징: 속성 「데이터종」을 가진다."}}
   "BT6-085"
   {language {:before "이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."
              :after "〈룰〉이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."}}
   "BT7-083"
   {language {:before "〈규칙〉명칭: 「시스터몬 시엘(각성)」, 속성: 「데이터종」 으로도 취급한다."
              :after "〈규칙〉명칭: 「시스터몬 시엘(각성)」, 속성: 「데이터종」 으로도 취급한다."}}
   "BT8-061"
   {language {:before "이 카드/디지몬의 명칭은 「콩알몬」으로도 취급한다."
              :after "〈룰〉명칭: 「콩알몬」으로도 취급한다."}}
   "BT8-062"
   {language {:before "이 카드/디지몬의 명칭은 「스컬나이트몬」과 「데들리액스몬」으로도 취급한다."
              :after "〈룰〉명칭: 「스컬나이트몬」/「데들리 악스몬」으로도 취급한다."}}
   "BT9-051"
   {language {:before "이 카드/디지몬은 명칭에 「레오몬」을 포함하는 것으로도 취급한다."
              :after "〈룰〉명칭: 「레오몬」을 포함하는 것으로도 취급한다."}}
   "BT9-068"
   {language {:before "이 카드/디지몬은 명칭에 「그레이몬」을 포함하는 것으로도 취급한다."
              :after "〈룰〉명칭: 「그레이몬」을 포함하는 것으로도 취급한다."}}
   "EX2-012"
   {language {:before "이 카드/디지몬의 명칭은 「카오스듀크몬」으로도 취급한다."
              :after "〈룰〉명칭: 「카오스듀크몬」으로도 취급한다."}}
   "EX2-046"
   {language {:before "이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."
              :after "〈룰〉이 카드와 동일한 카드 넘버의 카드는 덱에 50장까지 넣을 수 있다."}}
   "EX3-064"
   {language {:before "〈룰〉 명칭: 「카오스듀크몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「카오스듀크몬」으로도 취급한다."}}
   "EX4-026"
   {language {:before "〈룰〉 명칭: 「구미호몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「구미호몬」으로도 취급한다."}}
   "EX4-028"
   {language {:before "〈룰〉 명칭: 「도사몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「도사몬」으로도 취급한다."}}
   "EX4-030"
   {language {:before "〈룰〉 명칭: 「샤크라몬」을 포함하는 것으로도 취급한다."
              :after "〈룰〉 명칭: 「샤크라몬」을 포함하는 것으로도 취급한다."}}
   "EX4-033"
   {language {:before "〈룰〉 명칭: 「테리어몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「테리어몬」으로도 취급한다."}}
   "EX4-048"
   {language {:before "〈룰〉 명칭: 「그레이몬」을 포함하는 것으로도 취급한다."
              :after "〈룰〉 명칭: 「그레이몬」을 포함하는 것으로도 취급한다."}}
   "EX4-062"
   {language {:before "〈룰〉 명칭: 「차도혁」/「노유라」로도 취급한다."
              :after "〈룰〉 명칭: 「차도혁」/「노유라」로도 취급한다."}}
   "EX4-072"
   {language {:before "〈룰〉 명칭: 「플러그인」을 포함하는 것으로도 취급한다."
              :after "〈룰〉 명칭: 「플러그인」을 포함하는 것으로도 취급한다."}}
   "P-072"
   {language {:before "이 카드/디지몬의 명칭은 「메탈그레이몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「메탈그레이몬」으로도 취급한다."}}
   "P-073"
   {language {:before "이 카드/디지몬의 명칭은 「워가루몬」으로도 취급한다."
              :after "〈룰〉 명칭: 「워가루몬」으로도 취급한다."}}
   "P-101"
   {language {:before "〈룰〉 특징: 유형 「사이보그형」을 가진다."
              :after "〈룰〉 특징: 유형 「사이보그형」을 가진다."}}
   "RB1-004"
   {language {:before "〈룰〉카드 넘버: 「P-009」로도 취급하며, 덱에 「P-009」와 합계 4장까지 넣을 수 있다."
              :after "〈룰〉카드 넘버: 「P-009」로도 취급하며, 덱에 「P-009」와 합계 4장까지 넣을 수 있다."}}
   "RB1-006"
   {language {:before "〈룰〉카드 넘버: 「P-058」로도 취급하며, 덱에 「P-058」과 합계 4장까지 넣을 수 있다."
              :after "〈룰〉카드 넘버: 「P-058」로도 취급하며, 덱에 「P-058」과 합계 4장까지 넣을 수 있다."}}
   "RB1-007"
   {language {:before "〈룰〉카드 넘버: 「P-010」으로도 취급하며, 덱에 「P-010」과 합계 4장까지 넣을 수 있다."
              :after "〈룰〉카드 넘버: 「P-010」으로도 취급하며, 덱에 「P-010」과 합계 4장까지 넣을 수 있다."}}

   ;; Draw/Memory order
   "BT11-092"
   {"zh-Hans" {:before "并使内存值+1,且《抽1张卡》", :after "且《抽1张卡》,并使内存值+1"}},
   "BT11-095"
   {"zh-Hans" {:before "并使内存值+1,且《抽1张卡》", :after "且《抽1张卡》,并使内存值+1"}},
   "BT13-102"
   {"zh-Hans" {:before "则我方内存值+1,并《抽1张卡》", :after "并《抽1张卡》,则我方内存值+1"}},
   "BT17-052"
   {"zh-Hans" {:before "内存值+1,并《抽1张卡》", :after "并《抽1张卡》,内存值+1"}},
   "BT6-087"
   {"zh-Hans"
    {:before "内存值+1,《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "《抽1张卡》（从我方的卡组上方抽取1张卡牌）,内存值+1"}},
   "BT6-088"
   {"zh-Hans"
    {:before "内存值+1,《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "《抽1张卡》（从我方的卡组上方抽取1张卡牌）,内存值+1"}},
   "BT8-028"
   {"zh-Hans"
    {:before "内存值+1,并《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "并《抽1张卡》（从我方的卡组上方抽取1张卡牌）,内存值+1"}},
   "BT8-092"
   {"zh-Hans" {:before "内存值+1,《抽1张卡》", :after "《抽1张卡》,内存值+1"}},
   "BT9-092"
   {"zh-Hans" {:before "并使内存值+1,且《抽1张卡》", :after "且《抽1张卡》,并使内存值+1"}},
   "EX1-069"
   {"zh-Hans"
    {:before "并使内存值+2,且《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "且《抽1张卡》（从我方的卡组上方抽取1张卡牌）,并使内存值+2"}},
   "EX2-017"
   {"zh-Hans"
    {:before "内存值+2,并《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "并《抽1张卡》（从我方的卡组上方抽取1张卡牌）,内存值+2"}},
   "EX2-045"
   {"zh-Hans"
    {:before "并使内存值+1,《抽1张卡》（从我方的卡组上方抽取1张卡牌）",
     :after "《抽1张卡》（从我方的卡组上方抽取1张卡牌）,并使内存值+1"}},
   "RB1-032"
   {"zh-Hans" {:before "并使内存值+1,且《抽1张卡》", :after "且《抽1张卡》,并使内存值+1"}}})

(defn process-rules
  [cards]
  (let [forms (->> cards
                   (filter :card/form)
                   (map :card/form)
                   set)
        attributes (->> cards
                        (filter :card/attribute)
                        (map :card/attribute)
                        set)
        types (->> cards
                   (filter :card/type)
                   (mapcat (comp #(string/split % #"[/,]") :card/type))
                   set)]
    (->> cards
         (map (fn [{:card/keys [number language effect] :as card}]
                (let [rule (when effect
                             (case language
                               "ja" (re-find #"〈ルール〉.*" effect)
                               "en" (re-find #"⟨Rule⟩.*" effect)
                               "zh-Hans" (re-find #"\u3008规则\u3009.*" effect)
                               "ko" (or (re-find #"〈룰〉.*" effect)
                                        (re-find #"〈규칙〉.*" effect))))]
                  (cond-> card
                    (and effect rule)
                    (assoc :card/rules
                           (loop [rule rule
                                  accl []
                                  remaining [;; traits
                                             "特徴:"
                                             "Trait:"
                                             "속성:"
                                             "특징:"
                                             "特征:"
                                             ;; names
                                             "名称:"
                                             "Name:"
                                             "명칭:"
                                             ;; card numbers
                                             "カードナンバー:"
                                             "Card Number:"
                                             "카드 넘버:"
                                             "卡牌编号:"
                                             ;; allowance
                                             "枚まで入れられる"
                                             "You can include up to"
                                             "넘버의 카드는 덱에"
                                             "卡组中可以放入最多"]]
                             (if (seq remaining)
                               (let [rule-type (first remaining)
                                     end-index (string/index-of rule rule-type)
                                     highlights
                                     (when end-index
                                       (-> (subs rule end-index)
                                           (highlight/highlights-in-text language)
                                           (as-> #__ xs
                                             (map (fn [s]
                                                    (subs s 1 (dec (count s))))
                                                  xs))
                                           set))]
                                 (recur (cond-> rule
                                          end-index
                                          (subs 0 end-index))
                                        (cond->> accl
                                          end-index
                                          (apply conj
                                                 (condp contains? rule-type
                                                   #{"特徴:"
                                                     "Trait:"
                                                     "속성:"
                                                     "특징:"
                                                     "特征:"}
                                                   (map (fn [highlight]
                                                          (let [rule-type
                                                                (cond
                                                                  (contains? forms
                                                                             highlight)
                                                                  :card/form
                                                                  (contains? attributes
                                                                             highlight)
                                                                  :card/attribute
                                                                  (contains? types
                                                                             highlight)
                                                                  :card/type
                                                                  :else :card/type)]
                                                            {:rule/type rule-type
                                                             :rule/value highlight}))
                                                        highlights)
                                                   #{"名称:"
                                                     "Name:"
                                                     "명칭:"}
                                                   (map (fn [highlight]
                                                          {:rule/type :card/name
                                                           :rule/value highlight})
                                                        highlights)
                                                   #{"カードナンバー:"
                                                     "Card Number:"
                                                     "카드 넘버:"
                                                     "卡牌编号:"}
                                                   (map (fn [highlight]
                                                          {:rule/type :card/number
                                                           :rule/value highlight})
                                                        highlights)
                                                   #{"枚まで入れられる"
                                                     "You can include up to"
                                                     "넘버의 카드는 덱에"
                                                     "卡组中可以放入最多"}
                                                   [{:rule/type :card/limitations
                                                     :rule/limitation
                                                     {:limitation/id
                                                      (format "limitation/rule_%s_%s"
                                                              language
                                                              number)
                                                      :limitation/type :rule
                                                      :limitation/allowance
                                                      (->> rule
                                                           (re-find #"[0-9]+")
                                                           parse-long)}}])))
                                        (rest remaining)))
                               (map-indexed (fn [idx rule]
                                              (assoc rule :rule/id
                                                     (format "rule/%s_%s_%d"
                                                             language
                                                             number
                                                             idx)))
                                            accl)))))))))))
