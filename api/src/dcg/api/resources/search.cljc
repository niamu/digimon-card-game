(ns dcg.api.resources.search
  (:require
   #?@(:cljs
       [[goog.string :as gstring]
        [goog.string.format]])
   [clojure.string :as string]
   [clojure.math :as math]
   #?@(:clj
       [[clojure.java.io :as io]
        [clojure.pprint :as pprint]
        [dcg.api.resources.card :as card]
        [dcg.api.resources.errors :as errors]
        [dcg.api.router :as router]
        [liberator.core :as liberator]
        [liberator.representation :as representation]
        [dcg.api.utils :as utils]])
   [dcg.api.db :as db]
   [dcg.api.routes :as-alias routes]
   [instaparse.core :as insta])
  #?(:cljs
     (:require-macros
      [dcg.api.resources.search :refer [load-grammar]])))

(def max-per-page 60)

#?(:clj
   (defmacro load-grammar
     []
     (slurp (io/resource "search-syntax.bnf"))))

(def parser
  (insta/parser (load-grammar)))

(def ^:private allowable-strings
  (reduce (fn [accl {:db/keys [ident valueType]}]
            (cond-> accl
              (and (not= (name ident) "id")
                   (= :db.type/string
                      valueType))
              (conj ident)))
          #{}
          db/schema))

(defmulti datom
  (fn [& args]
    (case (count args)
      3 (first args)
      (second args))))

(defmethod datom :default
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid? (allowable-strings attribute)
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))]
     (if valid?
       (with-meta [['?card attribute ?value]
                   [(list 'String/.toLowerCase ?value)
                    (symbol (str (name ?value) "-lower"))]
                   (cond->> [(list 'String/.contains
                                   (symbol (str (name ?value) "-lower"))
                                   (string/lower-case v))]
                     (boolean not?)
                     (list 'not))]
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "the %s %s \"%s\""
                       (name attribute)
                       (if (boolean not?)
                         "does not include"
                         "includes")
                       v)))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid attribute to query."
                             (name attribute))]}
         {:error? true})))))

(defmethod datom :card/name
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-lower (symbol (str (name ?value) "-lower"))
         ?rule (gensym "?rule")
         ?rule-type (symbol (str (name ?rule) "-type"))]
     (with-meta (if (boolean not?)
                  [(list 'not-join ['?card]
                         (list 'or-join
                               ['?card ?value]
                               ['?card :card/name ?value]
                               (list 'and
                                     ['?card :card/rules ?rule]
                                     [?rule :rule/type ?rule-type]
                                     [?rule :rule/value ?value]
                                     [(list 'contains?
                                            #{:card/name} ?rule-type)]))
                         [(list 'String/.toLowerCase ?value) ?value-lower]
                         [(list 'String/.contains ?value-lower
                                (string/lower-case v))])]
                  [(list 'or-join
                         ['?card ?value]
                         ['?card :card/name ?value]
                         (list 'and
                               ['?card :card/rules ?rule]
                               [?rule :rule/type ?rule-type]
                               [?rule :rule/value ?value]
                               [(list 'contains?
                                      #{:card/name} ?rule-type)]))
                   [(list 'String/.toLowerCase ?value) ?value-lower]
                   [(list 'String/.contains ?value-lower
                          (string/lower-case v))]])
       (assoc (meta value)
              :note (#?(:clj format
                        :cljs gstring/format) "the %s %s \"%s\""
                     (name attribute)
                     (if (boolean not?)
                       "does not include"
                       "includes")
                     v))))))

(defmethod datom :card/number
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-lower (symbol (str (name ?value) "-lower"))
         ?rule (gensym "?rule")
         ?rule-type (symbol (str (name ?rule) "-type"))]
     (with-meta (if (boolean not?)
                  [(list 'not-join ['?card]
                         (list 'or-join
                               ['?card ?value]
                               ['?card :card/number ?value]
                               (list 'and
                                     ['?card :card/rules ?rule]
                                     [?rule :rule/type ?rule-type]
                                     [?rule :rule/value ?value]
                                     [(list 'contains?
                                            #{:card/number} ?rule-type)]))
                         [(list 'String/.toLowerCase ?value) ?value-lower]
                         [(list 'String/.contains ?value-lower
                                (string/lower-case v))])]
                  [(list 'or-join
                         ['?card ?value]
                         ['?card :card/number ?value]
                         (list 'and
                               ['?card :card/rules ?rule]
                               [?rule :rule/type ?rule-type]
                               [?rule :rule/value ?value]
                               [(list 'contains?
                                      #{:card/number} ?rule-type)]))
                   [(list 'String/.toLowerCase ?value) ?value-lower]
                   [(list 'String/.contains ?value-lower
                          (string/lower-case v))]])
       (assoc (meta value)
              :note (#?(:clj format
                        :cljs gstring/format) "the %s %s \"%s\""
                     (name attribute)
                     (if (boolean not?)
                       "does not include"
                       "includes")
                     v))))))

(defmethod datom :supplemental-rarity/stamp
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid? (allowable-strings attribute)
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta (if (boolean not?)
                    [(list 'not-join
                           ['?card]
                           ['?card :card/supplemental-rarity
                            (symbol (str (name ?value) "-not"))]
                           [(symbol (str (name ?value) "-not"))
                            attribute (string/upper-case v)])]
                    [['?card :card/supplemental-rarity ?value]
                     [?value attribute (string/upper-case v)]])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "the rarity stamp %s \"%s\""
                       (cond-> "is"
                         (boolean not?)
                         (str " not"))
                       v)))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid attribute to query."
                             (name attribute))]}
         {:error? true})))))

(derive :card/category ::category)
(derive :digivolve/category ::category)

(defmethod datom ::category
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid-categories (->> [:tamer :option :digi-egg :digimon]
                               (map name)
                               (into #{}))
         valid-value? (valid-categories (string/lower-case v))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (and valid-value?
                     valid-operator?)
         v (cond-> v
             valid?
             (-> string/lower-case
                 keyword))
         digivolve? (= (namespace attribute)
                       "digivolve")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta (if digivolve?
                    [['?card :card/digivolution-requirements ?value]
                     (if (boolean not?)
                       (list 'not-join
                             ['?card]
                             ['?card :card/digivolution-requirements
                              (symbol (str (name ?value) "-not"))]
                             [(symbol (str (name ?value) "-not"))
                              attribute v])
                       [?value attribute v])]
                    [(cond->> ['?card attribute v]
                       (boolean not?)
                       (list 'not))])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s \"%s\""
                       (if digivolve?
                         (if (boolean not?)
                           "it does not digivolve from the category"
                           "it digivolves from the category")
                         (str "the "
                              (string/replace (name attribute)
                                              "-" " ")
                              (cond-> " is"
                                (boolean not?) (str " not"))))
                       (case v
                         :digi-egg "Digi-Egg"
                         (-> v name string/capitalize)))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-value?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid %s."
                            v
                            (cond->> (name attribute)
                              digivolve? (str "digivolve-"))))
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for %s."
                            (name (or operator ":"))
                            (cond->> (name attribute)
                              digivolve? (str "digivolve-")))))}
         {:error? true})))))

(derive :card/color ::color)
(derive :digivolve/color ::color)

(defmethod datom ::color
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid-colors (->> [:yellow :black :blue :green :white :purple :red]
                           (map name)
                           (into #{}))
         valid-value? (valid-colors (string/lower-case v))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (and valid-value?
                     valid-operator?)
         v (cond-> v
             valid? (-> string/lower-case
                        keyword))
         digivolve? (= (namespace attribute)
                       "digivolve")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta (cond-> [['?card (if digivolve?
                                     :card/digivolution-requirements
                                     attribute) ?value]]
                    digivolve?
                    (conj (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card :card/digivolution-requirements
                                   (symbol (str (name ?value) "-not"))]
                                  [(symbol (str (name ?value) "-not"))
                                   attribute
                                   v])
                            [?value attribute v]))
                    (not digivolve?)
                    (conj (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card attribute
                                   (symbol (str (name ?value) "-not"))]
                                  [(symbol (str (name ?value) "-not"))
                                   :color/color
                                   v])
                            [?value :color/color v])))
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s \"%s\""
                       (if digivolve?
                         (if (boolean not?)
                           "it does not digivolve from the colour"
                           "it digivolves from the colour")
                         (str "the colour"
                              (cond-> " is"
                                (boolean not?) (str " not"))))
                       (-> v name string/capitalize))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-value?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid %s."
                            v
                            (cond->> (name attribute)
                              digivolve? (str "digivolve-"))))
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for %s."
                            (name (or operator ":"))
                            (cond->> (name attribute)
                              digivolve? (str "digivolve-")))))}
         {:error? true})))))

(derive :card/dp ::number)
(derive :card/parallel-id ::number)
(derive :card/block-icon ::number)
(derive :card/play-cost ::number)
(derive :card/use-cost ::number)
(derive :card/level ::number)
(derive :digivolve/cost ::number)
(derive :digivolve/level ::number)
(derive :supplemental-rarity/stars ::number)

(defmethod datom ::number
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid? (boolean (parse-long v))
         v (or (parse-long v) v)
         digivolve? (= (namespace attribute)
                       "digivolve")
         supplemental-rarity? (= (namespace attribute)
                                 "supplemental-rarity")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta (cond-> (if (and (not digivolve?)
                                   (not supplemental-rarity?))
                            [['?card attribute ?value]]
                            [])
                    digivolve?
                    (conj (when-not (boolean not?)
                            ['?card :card/digivolution-requirements ?value])
                          (when-not (boolean not?)
                            [?value attribute ?value-value])
                          (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card :card/digivolution-requirements
                                   (symbol (str (name ?value) "-not"))]
                                  [(symbol (str (name ?value) "-not"))
                                   attribute
                                   (symbol (str (name ?value-value) "-not"))]
                                  [(list (or operator '=)
                                         (symbol (str (name ?value-value) "-not"))
                                         v)])
                            [(list (or operator '=) ?value-value v)]))
                    supplemental-rarity?
                    (conj (when-not (boolean not?)
                            ['?card :card/supplemental-rarity ?value])
                          (when-not (boolean not?)
                            [?value attribute ?value-value])
                          (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card :card/supplemental-rarity
                                   (symbol (str (name ?value) "-not"))]
                                  [(symbol (str (name ?value) "-not"))
                                   attribute
                                   (symbol (str (name ?value-value) "-not"))]
                                  [(list (or operator '=)
                                         (symbol (str (name ?value-value) "-not"))
                                         v)])
                            [(list (or operator '=) ?value-value v)]))
                    (and (not digivolve?)
                         (not supplemental-rarity?))
                    (conj (cond->> [(list (or operator '=) ?value v)]
                            (boolean not?)
                            (list 'not))))
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s %s {%d}"
                       (if digivolve?
                         (str (if (boolean not?)
                                "it does not digivolve from a "
                                "it digivolves from a ")
                              (name attribute))
                         (str "the "
                              (string/replace (name attribute)
                                              "-" " ")
                              (cond-> " is"
                                (boolean not?) (str " not"))))
                       (case (and operator (name operator))
                         "<" "less than"
                         "<=" "less than or equal to"
                         ">" "greater than"
                         ">=" "greater than or equal to"
                         "equal to")
                       v)))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid %s."
                             v
                             (cond->> (name attribute)
                               digivolve? (str "digivolve-")))]}
         {:error? true})))))

(defmethod datom :card/language
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [language-map {"any"     '_
                       "ja"      "ja"
                       "en"      "en"
                       "zh-hans" "zh-Hans"
                       "ko"      "ko"}
         valid-languages (->> ["any" "ja" "en" "zh-Hans" "ko"]
                              (map string/lower-case)
                              (into #{}))
         valid-value? (valid-languages (string/lower-case v))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (and valid-value?
                     valid-operator?)]
     (if valid?
       (with-meta [(cond->> ['?card attribute (->> (string/lower-case v)
                                                   (language-map))]
                     (boolean not?)
                     (list 'not))]
         (assoc (meta value)
                :language? true
                :note (#?(:clj format
                          :cljs gstring/format) "the language %s \"%s\""
                       (cond-> "is"
                         (boolean not?)
                         (str " not"))
                       (-> (string/lower-case v)
                           (language-map)
                           name
                           (string/replace #"^_$" "any")))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-value?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid language." v))
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for language."
                            (name (or operator ":")))))}
         {:error? true})))))

(defmethod datom :card/rarity
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid-rarities (->> ["C" "U" "R" "SR" "SEC" "P"]
                             (into #{}))
         ?value (gensym "?rarity")
         valid? (valid-rarities (string/upper-case v))]
     (if valid?
       (with-meta [['?card attribute ?value]
                   (list 'rarity-rank ?value
                         (symbol (str (name ?value) "-rank")))
                   (list 'rarity-rank (string/upper-case v)
                         (symbol (str (name ?value) "-rank-tr")))
                   (cond->> [(list (or operator '=)
                                   (symbol (str (name ?value) "-rank"))
                                   (symbol (str (name ?value) "-rank-tr")))]
                     (boolean not?)
                     (list 'not))]
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "the rarity %s %s \"%s\""
                       (cond-> "is"
                         (boolean not?)
                         (str " not"))
                       (case (name operator)
                         "<" "less than"
                         "<=" "less than or equal to"
                         ">" "greater than"
                         ">=" "greater than or equal to"
                         "equal to")
                       (string/upper-case v))))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid rarity." v)]}
         {:error? true})))))

(defmethod datom :digivolve/form
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid-forms (->> [:standard :super :ultimate :god]
                          (map name)
                          (into #{}))
         valid? (valid-forms (string/lower-case v))
         v (cond-> v
             valid?
             (-> string/lower-case
                 keyword))
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta [['?card :card/digivolution-requirements ?value]
                   [?value attribute ?value-value]
                   (list 'digivolve-form-rank
                         ?value-value
                         (symbol (str (name ?value) "-rank")))
                   (list 'digivolve-form-rank
                         v
                         (symbol (str (name ?value) "-rank-tr")))
                   (if (boolean not?)
                     (list 'not-join
                           ['?card (symbol (str (name ?value) "-rank-tr"))]
                           ['?card :card/digivolution-requirements
                            (symbol (str (name ?value) "-not"))]
                           [(symbol (str (name ?value) "-not"))
                            attribute
                            (symbol (str (name ?value-value) "-not"))]
                           (list 'digivolve-form-rank
                                 (symbol (str (name ?value-value) "-not"))
                                 (symbol (str (name ?value-value) "-rank")))
                           [(list (or operator '=)
                                  (symbol (str (name ?value-value) "-rank"))
                                  (symbol (str (name ?value) "-rank-tr")))])
                     [(list (or operator '=)
                            (symbol (str (name ?value) "-rank"))
                            (symbol (str (name ?value) "-rank-tr")))])]
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "it %s from a form %s \"%s\""
                       (if (boolean not?)
                         "does not digivolve"
                         "digivolves")
                       (case (name operator)
                         "<" "less than"
                         "<=" "less than or equal to"
                         ">" "greater than"
                         ">=" "greater than or equal to"
                         "equal to")
                       (string/capitalize (name v)))))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid %s."
                             v
                             (str "digivolve-" (name attribute)))]}
         {:error? true})))))

(def transform-map
  {:attribute (fn [a]
                (let [a (string/lower-case a)]
                  (cond
                    (#{"digivolve-cost"
                       "digivolve-level"
                       "digivolve-form"
                       "digivolve-category"
                       "digivolve-color"} a) (-> (string/replace a
                                                                 "-" "/")
                                                 keyword)
                    (#{"rarity-stars"
                       "rarity-stamp"} a) (keyword "supplemental-rarity"
                                                   (string/replace a
                                                                   "rarity-"
                                                                   ""))
                    (#{"text"
                       "traits"} a) (keyword a)
                    :else (keyword "card" a))))
   :operator (fn
               ([])
               ([operator]
                (symbol operator)))
   :quoted (fn [s]
             (subs s 1 (dec (count s))))
   :datom datom
   :not-string (fn [s]
                 (let [attribute :card/name
                       ?value (gensym (str "?"
                                           (namespace attribute)
                                           "-"
                                           (name attribute)))]
                   (with-meta [['?card attribute ?value]
                               [(list 'String/.toLowerCase ?value)
                                (symbol (str (name ?value) "-lower"))]
                               (list 'not
                                     [(list 'String/.contains
                                            (symbol (str (name ?value) "-lower"))
                                            (string/lower-case s))])]
                     {:note (#?(:clj format
                                :cljs gstring/format) "the name does not include \"%s\"" s)
                      :instaparse.gll/start-index nil
                      :instaparse.gll/end-index nil})))
   :exact-string (fn exact-string
                   ([s]
                    (exact-string nil s))
                   ([not? s]
                    (let [attribute :card/name
                          ?value (gensym (str "?"
                                              (namespace attribute)
                                              "-"
                                              (name attribute)))]
                      (with-meta [['?card attribute ?value]
                                  [(list 'String/.toLowerCase ?value)
                                   (symbol (str (name ?value) "-lower"))]
                                  (cond->> [(list '=
                                                  (symbol (str (name ?value) "-lower"))
                                                  (string/lower-case s))]
                                    (boolean not?)
                                    (list 'not))]
                        {:note (#?(:clj format
                                   :cljs gstring/format) "the name is %s \"%s\""
                                (cond->> "exactly"
                                  (boolean not?)
                                  (str "not "))
                                s)
                         :instaparse.gll/start-index nil
                         :instaparse.gll/end-index nil}))))
   :has (fn has
          ([s]
           (has nil s))
          ([not? [_ s :as value]]
           (let [allowed-attributes #{:card/block-icon
                                      :card/dp
                                      :card/level
                                      :card/form
                                      :card/attribute
                                      :card/type
                                      :card/play-cost
                                      :card/use-cost
                                      :card/effect
                                      :card/inherited-effect
                                      :card/security-effect
                                      :card/errata
                                      :card/panorama
                                      :card/parallel
                                      :card/ban
                                      :card/restriction}
                 attribute (keyword "card" s)
                 valid? (boolean (allowed-attributes attribute))]
             (if valid?
               (with-meta (case attribute
                            :card/parallel
                            (let [?p (gensym "?parallel")]
                              [['?card :card/parallel-id ?p]
                               (cond->> [(list '> ?p 0)]
                                 not? (list 'not))])
                            :card/ban
                            (let [?l (gensym "?limitations")]
                              (if not?
                                [(list 'not-join
                                       ['?card]
                                       ['?card :card/limitations ?l]
                                       (list 'or-join
                                             [?l]
                                             [?l :limitation/type :ban]
                                             [?l :limitation/type :banned-pair]))]
                                [['?card :card/limitations ?l]
                                 (list 'or-join
                                       [?l]
                                       [?l :limitation/type :ban]
                                       [?l :limitation/type :banned-pair])]))
                            :card/restriction
                            (let [?l (gensym "?limitations")
                                  ?l2 (gensym "?limitations")]
                              (if not?
                                [(list 'not-join
                                       ['?card]
                                       ['?card :card/limitations ?l]
                                       [?l :limitation/type :restrict]
                                       (list 'not-join
                                             ['?card]
                                             ['?card :card/limitations ?l2]
                                             [?l2 :limitation/type :unrestrict]))]
                                [['?card :card/limitations ?l]
                                 [?l :limitation/type :restrict]
                                 (list 'not-join
                                       ['?card]
                                       ['?card :card/limitations ?l2]
                                       [?l2 :limitation/type :unrestrict])]))
                            [(cond->> ['?card attribute]
                               not? (list 'not))])
                 (assoc (meta value)
                        :note (#?(:clj format
                                  :cljs gstring/format) "the card %s %s \"%s\""
                               (if (boolean not?)
                                 "does not have"
                                 "has")
                               (cond
                                 (#{:card/attribute
                                    :card/effect
                                    :card/inherited-effect} attribute) "an"
                                 (= :card/errata attribute) "any"
                                 :else "a")
                               (string/replace s "-" " "))))
               (with-meta {:errors [(#?(:clj format
                                        :cljs gstring/format)
                                     "Checking if cards have \"%s\" is not supported."
                                     s)]}
                 {:error? true})))))
   :trait (fn trait
            ([value]
             (trait nil value))
            ([not? [_ v :as value]]
             (let [?value (gensym "?trait")
                   ?value-lower (symbol (str (name ?value) "-lower"))
                   ?rule (gensym "?rule")
                   ?rule-type (symbol (str (name ?rule) "-type"))]
               (with-meta (if (boolean not?)
                            [(list 'not-join ['?card]
                                   (list 'or-join
                                         ['?card ?value]
                                         ['?card :card/attribute ?value]
                                         ['?card :card/form ?value]
                                         ['?card :card/type ?value]
                                         (list 'and
                                               ['?card :card/rules ?rule]
                                               [?rule :rule/type ?rule-type]
                                               [?rule :rule/value ?value]
                                               [(list 'contains?
                                                      #{:card/attribute
                                                        :card/form
                                                        :card/type} ?rule-type)]))
                                   [(list 'String/.toLowerCase ?value) ?value-lower]
                                   [(list 'String/.contains ?value-lower
                                          (string/lower-case v))])]
                            [(list 'or-join
                                   ['?card ?value]
                                   ['?card :card/attribute ?value]
                                   ['?card :card/form ?value]
                                   ['?card :card/type ?value]
                                   (list 'and ['?card :card/rules ?rule]
                                         [?rule :rule/type ?rule-type]
                                         [?rule :rule/value ?value]
                                         [(list 'contains?
                                                #{:card/attribute
                                                  :card/form
                                                  :card/type} ?rule-type)]))
                             [(list 'String/.toLowerCase ?value) ?value-lower]
                             [(list 'String/.contains ?value-lower
                                    (string/lower-case v))]])
                 (assoc (meta value)
                        :note (#?(:clj format
                                  :cljs gstring/format) "the card traits %s \"%s\""
                               (if (boolean not?)
                                 "do not include"
                                 "includes")
                               v))))))
   :text (fn text
           ([value]
            (text nil value))
           ([not? [_ v :as value]]
            (let [?value (gensym "?text")
                  ?value-lower (symbol (str (name ?value) "-lower"))
                  ?rule (gensym "?rule")
                  ?rule-type (symbol (str (name ?rule) "-type"))]
              (with-meta (if (boolean not?)
                           [(list 'not-join ['?card]
                                  (list 'or-join ['?card ?value]
                                        ['?card :card/attribute ?value]
                                        ['?card :card/form ?value]
                                        ['?card :card/type ?value]
                                        ['?card :card/number ?value]
                                        ['?card :card/name ?value]
                                        ['?card :card/effect ?value]
                                        ['?card :card/inherited-effect ?value]
                                        ['?card :card/security-effect ?value]
                                        (list 'and
                                              ['?card :card/rules ?rule]
                                              [?rule :rule/type ?rule-type]
                                              [?rule :rule/value ?value]
                                              [(list 'contains?
                                                     #{:card/name
                                                       :card/number
                                                       :card/attribute
                                                       :card/form
                                                       :card/type} ?rule-type)]))
                                  [(list 'String/.toLowerCase ?value) ?value-lower]
                                  [(list 'String/.contains ?value-lower
                                         (string/lower-case v))])]
                           [(list 'or-join ['?card ?value]
                                  ['?card :card/attribute ?value]
                                  ['?card :card/form ?value]
                                  ['?card :card/type ?value]
                                  ['?card :card/number ?value]
                                  ['?card :card/name ?value]
                                  ['?card :card/effect ?value]
                                  ['?card :card/inherited-effect ?value]
                                  ['?card :card/security-effect ?value]
                                  (list 'and
                                        ['?card :card/rules ?rule]
                                        [?rule :rule/type ?rule-type]
                                        [?rule :rule/value ?value]
                                        [(list 'contains?
                                               #{:card/name
                                                 :card/number
                                                 :card/attribute
                                                 :card/form
                                                 :card/type} ?rule-type)]))
                            [(list 'String/.toLowerCase ?value) ?value-lower]
                            [(list 'String/.contains ?value-lower
                                   (string/lower-case v))]])
                (assoc (meta value)
                       :note (#?(:clj format
                                 :cljs gstring/format) "the card text %s \"%s\""
                              (if (boolean not?)
                                "does not include"
                                "includes")
                              v))))))})

(defn transform
  [transform-map parse-tree]
  (->> (insta/transform transform-map parse-tree)
       (map (fn [s]
              (if (string? s)
                (with-meta (let [attribute :card/name
                                 ?value (gensym (str "?"
                                                     (namespace attribute)
                                                     "-"
                                                     (name attribute)))]
                             [['?card attribute ?value]
                              [(list 'String/.toLowerCase ?value)
                               (symbol (str (name ?value) "-lower"))]
                              [(list 'String/.contains
                                     (symbol (str (name ?value) "-lower"))
                                     (string/lower-case s))]])
                  {:note (#?(:clj format
                             :cljs gstring/format) "the name includes \"%s\"" s)
                   :instaparse.gll/start-index nil
                   :instaparse.gll/end-index nil})
                s)))))

(def ranking-rules
  '[[(rarity-rank ?r ?rank)
     [(ground [["C"   0]
               ["U"   1]
               ["R"   2]
               ["SR"  3]
               ["SEC" 4]])
      [[?r ?rank]]]]
    [(digivolve-form-rank ?r ?rank)
     [(ground [[:standard 0]
               [:super    1]
               [:ultimate 2]
               [:god      3]])
      [[?r ?rank]]]]])

#?(:clj
   (defn query-db
     [parse-tree query]
     (let [where-clause
           (->> parse-tree
                (keep (fn [datom]
                        (when (vector? datom)
                          datom)))
                (apply concat)
                (remove nil?)
                distinct
                (into []))]
       (when where-clause
         (->> (db/q {:find [[(list 'pull '?card query) '...]]
                     :in '[$ %]
                     :where `~where-clause}
                    ranking-rules)
              (sort-by (juxt (fn [{:card/keys [language]}]
                               (case language
                                 "ja" 0
                                 "en" 1
                                 "zh-Hans" 2
                                 "ko" 3))
                             (fn [{:card/keys [number]}]
                               (or (some->> number
                                            (re-find #"^(.*)[0-9]+\-")
                                            last)
                                   "XXX"))
                             (fn [{:card/keys [number]}]
                               (or (some->> number
                                            (re-find #"([0-9]+)\-")
                                            last
                                            parse-long)
                                   Long/MAX_VALUE))
                             (fn [{:card/keys [number]}]
                               (or (some->> number
                                            (re-find #"\-([0-9]+)$")
                                            last
                                            parse-long)
                                   Long/MAX_VALUE)))))))))

(defn- escape-html
  [s]
  #?(:clj
     (let [sb (StringBuilder.)]
       (doseq [ch (str s)]
         (case ch
           \< (.append sb "&lt;")
           \> (.append sb "&gt;")
           \& (.append sb "&amp;")
           \" (.append sb "&quot;")
           \' (.append sb "&#39;")
           (.append sb ch)))
       (.toString sb))
     :cljs
     (gstring/htmlEscape s)))

#?(:clj
   (defn query
     [q query & [{:keys [max-per-page page default-language]
                  :or {max-per-page 60
                       page 1
                       default-language "en"}}]]
     (let [parse-tree (->> (parser q)
                           (transform transform-map))
           parse-tree (cond-> parse-tree
                        (not (seq (filter (comp :language? meta) parse-tree)))
                        (conj (with-meta [['?card :card/language default-language]]
                                {:note (#?(:clj format
                                           :cljs gstring/format)
                                        "the language is \"%s\""
                                        default-language)})))
           errors (->> parse-tree
                       (filter :errors)
                       distinct
                       (map (fn [m]
                              (assoc m :original
                                     (let [[s e] (insta/span m)]
                                       (subs q s e))))))
           fragments (->> parse-tree
                          (map (comp :note meta))
                          (remove nil?))
           db-results (when (seq parse-tree)
                        (query-db parse-tree query))
           total (count db-results)]
       {:query/total total
        :query/cards (nth (partition-all max-per-page db-results)
                          (dec page)
                          [])
        :query/summary (if (<= total max-per-page)
                         (pprint/cl-format nil
                                           "~:D card~:P"
                                           total)
                         (pprint/cl-format nil
                                           "~:D - ~:D of ~:D card~:P"
                                           (inc (- (* page max-per-page)
                                                   max-per-page))
                                           (if (> (* page max-per-page)
                                                  total)
                                             (+ (- (* page max-per-page)
                                                   max-per-page)
                                                (mod total max-per-page))
                                             (* page max-per-page))
                                           total))
        :query/pagination
        {:pagination/pages (max (-> (/ total max-per-page)
                                    math/ceil
                                    math/round)
                                1)
         :pagination/prev (when (> page 1)
                            true)
         :pagination/next (when (< page (/ total max-per-page))
                            true)}
        :query/errors errors
        :query/fragments (case (count fragments)
                           0 ""
                           (1 2) (str " where " (string/join " and " fragments))
                           (loop [accl " where "
                                  remaining fragments]
                             (case (count remaining)
                               0 accl
                               1 (recur (str accl ", and " (first remaining))
                                        (next remaining))
                               (recur (str accl
                                           (when-not (= remaining fragments)
                                             ", ")
                                           (first remaining))
                                      (next remaining)))))})))

(defn query-highlight
  [s]
  (->> (parser s)
       (transform transform-map)
       (keep (fn [xs]
               (when (insta/span xs)
                 (meta xs))))
       (reduce (fn [accl {:instaparse.gll/keys [start-index end-index]
                         :keys [error?]}]
                 (let [remaining (last accl)
                       processed-index
                       (or (some->> (seq accl)
                                    drop-last
                                    (map (fn [s]
                                           (if (string? s)
                                             (count s)
                                             (count (get s :highlight)))))
                                    (apply +))
                           0)
                       before (subs s processed-index start-index)
                       after (subs s end-index)
                       text (subs s start-index end-index)]
                   (-> accl
                       drop-last
                       (concat [(when-not (empty? before)
                                  before)
                                {:highlight text
                                 :error? error?}
                                after]))))
               [s])
       (map (fn [{:keys [highlight error?] :as s}]
              (if highlight
                (#?(:clj format
                    :cljs gstring/format)
                 "<span class=\"%s\">%s</span>"
                 (cond-> "highlight"
                   error? (str " error"))
                 (escape-html highlight))
                (escape-html s))))
       (apply str)))

#?(:cljs
   (js/goog.exportSymbol "queryHighlight" query-highlight))

#?(:clj
   (liberator/defresource search-resource
     {:allowed-methods [:head :get]
      :available-media-types ["application/vnd.api+json"]
      :malformed? (fn [{{{{:keys [q]} :query} :parameters} :request}]
                    (string/blank? q))
      :exists? (fn [{{{{:keys [q page]
                       :or {page 1}} :query} :parameters
                     :dcg.api.core/keys [default-language]} :request}]
                 (let [{{:pagination/keys [pages]
                         :or {pages 1}} :query/pagination
                        :as query}
                       (when q
                         (query q
                                card/query
                                {:default-language default-language
                                 :max-per-page max-per-page
                                 :page page}))]
                   (if (or (and q (string/blank? q))
                           (> page pages))
                     false
                     {::query query})))
      :existed? (fn [{{{{:keys [q page]} :query} :parameters} :request
                     query ::query}]
                  (and q (string/blank? q)))
      :moved-temporarily? true
      :location (fn [_]
                  (router/by-name ::routes/search))
      :etag (fn [{{media-type :media-type} :representation
                 ::keys [card]}]
              (str (utils/sha card)
                   "--"
                   media-type))
      :handle-ok (fn [{{{{:keys [q page]
                         :or {page 1}} :query} :parameters} :request
                      {:query/keys [cards errors summary fragments total]
                       {:pagination/keys [pages prev next]} :query/pagination}
                      ::query}]
                   (let [q (string/trim q)
                         cards (->> cards
                                    (map (fn [card]
                                           (card/process-card card nil nil))))]
                     {:data (map :data cards)
                      :included (->> cards
                                     (mapcat :included)
                                     distinct)
                      :links
                      (cond-> {:first (router/by-name ::routes/search
                                                      {:query {:q q
                                                               :page 1}})
                               :last (router/by-name ::routes/search
                                                     {:query {:q q
                                                              :page pages}})}
                        prev
                        (assoc :prev
                               (router/by-name ::routes/search
                                               {:query {:q q
                                                        :page (dec page)}}))
                        next
                        (assoc :next
                               (router/by-name ::routes/search
                                               {:query {:q q
                                                        :page (inc page)}})))
                      :meta (cond-> {:total-cards total
                                     :summary (str summary fragments)}
                              (seq errors)
                              (assoc :errors (->> errors
                                                  (mapcat :errors)
                                                  distinct)))}))
      :handle-malformed (assoc-in errors/error400-body
                                  [:errors 0 :detail]
                                  "No `q` query parameter provided to search for anything.")
      :handle-method-not-allowed errors/error405-body
      :handle-not-acceptable errors/error406-body
      :handle-not-found errors/error404-body
      :as-response (fn [data {representation :representation :as context}]
                     (-> data
                         (representation/as-response
                          (assoc-in context
                                    [:representation :media-type]
                                    "application/vnd.api+json"))))}))
