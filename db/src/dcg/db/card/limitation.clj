(ns dcg.db.card.limitation
  (:require
   [clojure.string :as string]
   [dcg.db.card.cv :as cv]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.text SimpleDateFormat]
   [java.time LocalDate ZoneOffset]
   [java.util Date]))

(defmulti limitations
  (fn [{:origin/keys [language card-image-language] :as params}]
    (and (not card-image-language)
         language)))

(defmethod limitations :default [_] nil)

(defmethod limitations "ja"
  [{:origin/keys [url language] :as params}]
  (let [title? (fn title? [element]
                 (some->> (select/select (select/descendant
                                          (select/and
                                           (select/tag "h4")
                                           (select/class "subTit")))
                                         element)
                          first))
        subtitle? (fn subtitle? [element]
                    (some->> (select/select (select/descendant
                                             (select/and
                                              (select/tag "h5")
                                              (select/class "minTit")))
                                            element)
                             first))
        parse-date
        (fn parse-date [s]
          (let [date-matcher #"(\d+)[年년]\s?(\d+)[月월]\s?(\d+)?[日일]?"
                [year month day] (some->> (re-find date-matcher s)
                                          rest
                                          (map #(parse-long (or % "1"))))]
            (when (and year month day)
              (as-> (LocalDate/of ^int year
                                  ^int month
                                  ^int day) d
                (.atStartOfDay ^LocalDate d)
                (.toInstant d ZoneOffset/UTC)
                (Date/from d)))))
        parse-limitations
        (fn parse-limitations [lifted? date subtitle cards]
          (let [restriction-type (and subtitle
                                      (card-utils/text-content subtitle))
                limitation-type (cond
                                  lifted? :unrestrict

                                  (and restriction-type
                                       (string/starts-with? restriction-type
                                                            "禁止カード"))
                                  :ban

                                  (and restriction-type
                                       (string/starts-with? restriction-type
                                                            "禁止ペア"))
                                  :banned-pair

                                  :else :restrict)
                allowance (some-> (and restriction-type
                                       (re-find #"制限カード（([0-9]+)"
                                                restriction-type))
                                  last
                                  parse-long)
                allowance (cond
                            lifted? 4
                            :else (or allowance 0))]
            (pmap (fn [card]
                    (let [image (first (select/select
                                        (select/tag :img)
                                        card))
                          number
                          (or (->> (get-in image [:attrs :src] "")
                                   (re-find card-utils/card-number-re))
                              (some-> (get-in image [:attrs :src])
                                      (string/replace "../../images"
                                                      "/images")
                                      (as-> path (str url path))
                                      cv/query-url
                                      (as-> #__ card-id
                                        (re-find card-utils/card-number-re
                                                 card-id))))
                          id (format "limitation/%s_%s"
                                     language
                                     number)
                          note (->> (select/select
                                     (select/class "baseTxt")
                                     card)
                                    first
                                    card-utils/text-content)
                          card-pairs (when (= limitation-type :banned-pair)
                                       (some->> (select/select
                                                 (select/class "noticeList")
                                                 card)
                                                second
                                                card-utils/text-content
                                                (re-seq card-utils/card-number-re)
                                                (into [])))]
                      (if-not number
                        (when image
                          (logging/error
                           (format "Unable to detect card from image: %s"
                                   image)))
                        (cond-> {:limitation/id id
                                 :limitation/type limitation-type
                                 :limitation/note note
                                 :limitation/card-number number
                                 :limitation/language language
                                 :limitation/allowance allowance}
                          date (assoc :limitation/date date)
                          card-pairs (assoc :limitation/paired-card-numbers
                                            card-pairs)))))
                  cards)))
        parsed-doc (->> (utils/http-get (str url "/rule/restriction_card/"))
                        hickory/parse
                        hickory/as-hickory)
        applicable-cards (->> parsed-doc
                              (select/select
                               (select/descendant (select/id "application")
                                                  (select/class "num")))
                              (map (comp first :content))
                              set)]
    (->> parsed-doc
         (select/select (select/descendant (select/id "inner")
                                           (select/class "article")
                                           (select/and
                                            (select/tag "section")
                                            (select/not
                                             (select/id "application")))
                                           (select/or
                                            (select/and
                                             (select/tag "h4")
                                             (select/class "subTit"))
                                            (select/and
                                             (select/tag "h5")
                                             (select/class "minTit"))
                                            (select/or
                                             (select/class "restrictionCol")
                                             (select/has-descendant
                                              (select/class "noticeFrame"))))))
         (take-while (fn [element]
                       ;; Contact Information
                       (not= (:content element)
                             ["お問い合わせ先"])))
         (reduce (fn [accl element]
                   (cond-> accl
                     (title? element)
                     (conj [element])
                     (not (title? element))
                     (update-in [(dec (count accl))] conj element)))
                 [])
         (mapcat (fn [[title subtitle & cards]]
                   (let [lifted? (some-> (title? title)
                                         card-utils/text-content
                                         (string/includes? "より解除"))
                         date (or (some-> (title? title)
                                          card-utils/text-content
                                          parse-date)
                                  ;; BT08 release date
                                  nil)
                         cards (if (subtitle? subtitle)
                                 cards
                                 (cons subtitle cards))
                         subtitle (when (subtitle? subtitle)
                                    subtitle)
                         subtitle-and-cards (partition-by subtitle? cards)]
                     (if (= (count subtitle-and-cards) 1)
                       (parse-limitations lifted? date subtitle cards)
                       (let [[cards1 [subtitle2] cards2] subtitle-and-cards]
                         (concat (parse-limitations lifted?
                                                    date
                                                    subtitle
                                                    cards1)
                                 (parse-limitations lifted?
                                                    date
                                                    subtitle2
                                                    cards2)))))))
         ;; BT2-047 & BT3-103 are not identified with a note anymore.
         ;; If these cards are ever unrestricted, this will need to change.
         (concat [(when (contains? applicable-cards "BT2-047")
                    {:limitation/id "limitation/ja_BT2-047"
                     :limitation/type :restrict
                     :limitation/date #inst "2020-03-01T00:00:00.000-00:00"
                     :limitation/note "「BT2-047 アルゴモン」「BT3-103 秘めたる力の発現！！」は、いずれも進化コストをほとんどの場合において0まで引き下げることができる効果を持っており、緑デッキの進化スピードを他色のデッキに比べて想定以上に早くしてしまったため、1枚制限とさせていただきます。"
                     :limitation/card-number "BT2-047"
                     :limitation/language language
                     :limitation/allowance 1})
                  (when (contains? applicable-cards "BT3-103")
                    {:limitation/id "limitation/ja_BT3-103"
                     :limitation/type :restrict
                     :limitation/date #inst "2020-03-01T00:00:00.000-00:00"
                     :limitation/note "「BT2-047 アルゴモン」「BT3-103 秘めたる力の発現！！」は、いずれも進化コストをほとんどの場合において0まで引き下げることができる効果を持っており、緑デッキの進化スピードを他色のデッキに比べて想定以上に早くしてしまったため、1枚制限とさせていただきます。"
                     :limitation/card-number "BT3-103"
                     :limitation/language language
                     :limitation/allowance 1})])
         (reduce (fn [accl {:limitation/keys [language card-number] :as l}]
                   (let [pre (get-in accl [card-number language])]
                     (if (and (= (:limitation/type pre)
                                 :unrestrict)
                              (pos? (compare (Date.)
                                             (:limitation/date pre))))
                       accl
                       (assoc-in accl [card-number language]
                                 (dissoc l
                                         :limitation/card-number
                                         :limitation/language)))))
                 {}))))

(defmethod limitations "en"
  [{:origin/keys [url language] :as params}]
  (letfn [(title? [element]
            (some->> (select/select (select/descendant
                                     (select/and
                                      (select/tag "h4")
                                      (select/class "subTit")))
                                    element)
                     first))
          (subtitle? [element]
            (some->> (select/select (select/descendant
                                     (select/and
                                      (select/tag "h5")
                                      (select/class "minTit")))
                                    element)
                     first))
          (parse-date [s]
            (some->> (re-find #"\w+ \d+, [0-9]{4}" s)
                     (.parse (SimpleDateFormat. "MMM dd, yyyy"))))
          (parse-limitations [lifted? date subtitle cards]
            (let [restriction-type (card-utils/text-content subtitle)
                  limitation-type (cond
                                    lifted? :unrestrict

                                    (and restriction-type
                                         (string/starts-with? restriction-type
                                                              "Banned cards"))
                                    :ban

                                    (and restriction-type
                                         (string/starts-with? restriction-type
                                                              "Banned Pair"))
                                    :banned-pair

                                    :else :restrict)
                  allowance
                  (some-> (and restriction-type
                               (re-find #"Restricted\sCards\s\(([0-9]+)"
                                        restriction-type))
                          last
                          parse-long)
                  allowance (cond
                              lifted? 4
                              :else (or allowance 0))]
              (reduce (fn [accl card]
                        (let [image (first (select/select
                                            (select/tag :img)
                                            card))
                              number
                              (or (->> (get-in image [:attrs :alt] "")
                                       (re-find card-utils/card-number-re))
                                  (->> (get-in image [:attrs :src] "")
                                       (re-find card-utils/card-number-re))
                                  (some-> (get-in image [:attrs :src])
                                          (string/replace "../../images"
                                                          "/images")
                                          (as-> path (str url path))
                                          cv/query-url
                                          (as-> #__ card-id
                                            (re-find card-utils/card-number-re
                                                     card-id))))
                              id (format "limitation/%s_%s"
                                         language
                                         number)
                              note (or (->> (select/select
                                             (select/class "baseTxt")
                                             card)
                                            first
                                            card-utils/text-content)
                                       (get-in accl [(dec (count accl))
                                                     :limitation/note]))
                              card-pairs (when (= limitation-type :banned-pair)
                                           (some->> (select/select
                                                     (select/class "noticeList")
                                                     card)
                                                    second
                                                    card-utils/text-content
                                                    (re-seq card-utils/card-number-re)
                                                    (into [])))]
                          (if-not number
                            (do (when image
                                  (logging/error
                                   (format "Unable to detect card from image: %s"
                                           image)))
                                accl)
                            (conj accl
                                  (cond-> {:limitation/id id
                                           :limitation/type limitation-type
                                           :limitation/note note
                                           :limitation/card-number number
                                           :limitation/language language
                                           :limitation/allowance allowance}
                                    date (assoc :limitation/date date)
                                    card-pairs (assoc :limitation/paired-card-numbers
                                                      card-pairs))))))
                      []
                      (reverse cards))))]
    (->> (utils/http-get (str url "/rule/restriction_card/"))
         hickory/parse
         hickory/as-hickory
         (select/select (select/descendant (select/id "inner")
                                           (select/class "article")
                                           (select/or
                                            (select/and
                                             (select/tag "h4")
                                             (select/class "subTit"))
                                            (select/and
                                             (select/tag "h5")
                                             (select/class "minTit"))
                                            (select/or
                                             (select/class "restrictionCol")
                                             (select/has-descendant
                                              (select/class "noticeFrame"))))))
         (reduce (fn [accl element]
                   (cond-> accl
                     (title? element)
                     (conj [element])
                     (not (title? element))
                     (update-in [(dec (count accl))] conj element)))
                 [])
         (mapcat (fn [[title subtitle & cards]]
                   (let [lifted? (some-> (title? title)
                                         card-utils/text-content
                                         (string/includes? "lifted"))
                         date (or (some-> (title? title)
                                          card-utils/text-content
                                          parse-date)
                                  ;; BT08 release date
                                  #inst "2022-05-13T04:00:00.000-00:00")
                         subtitle-and-cards (partition-by subtitle? cards)]
                     (if (= (count subtitle-and-cards) 1)
                       (->> (parse-limitations lifted? date subtitle cards)
                            reverse)
                       (let [[cards1 [subtitle2] cards2] subtitle-and-cards]
                         (concat (->> (parse-limitations lifted?
                                                         date
                                                         subtitle
                                                         cards1)
                                      reverse)
                                 (->> (parse-limitations lifted?
                                                         date
                                                         subtitle2
                                                         cards2)
                                      reverse))))
                     )))
         (reduce (fn [accl {:limitation/keys [language card-number] :as l}]
                   (let [pre (get-in accl [card-number language])]
                     (if (and (= (:limitation/type pre)
                                 :unrestrict)
                              (pos? (compare (Date.)
                                             (:limitation/date pre))))
                       accl
                       (assoc-in accl [card-number language]
                                 (dissoc l
                                         :limitation/card-number
                                         :limitation/language)))))
                 {}))))
