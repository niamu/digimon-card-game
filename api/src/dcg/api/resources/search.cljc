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

(defn- regex?
  [v]
  (instance? #?(:clj java.util.regex.Pattern
                :cljs js/RegExp) v))

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
   (let [valid-value? (when (string? v)
                        (allowable-strings attribute))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         valid? (or (and valid-value?
                         valid-operator?)
                    (regex? v))]
     (if valid?
       (with-meta (cond-> [['?card attribute ?value]]
                    (regex? v)
                    (conj [(list 're-find v ?value)])
                    (string? v)
                    (conj [(list 'String/.toLowerCase ?value)
                           (symbol (str (name ?value) "-lower"))]
                          (cond->> [(list (if (= operator '=)
                                            'String/.equals
                                            'String/.contains)
                                          (symbol (str (name ?value) "-lower"))
                                          (string/lower-case v))]
                            (boolean not?)
                            (list 'not))))
         (assoc (meta value)
                :note (if (regex? v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s the pattern /%s/"
                         (name attribute)
                         (if (boolean not?)
                           "does not match"
                           "matches")
                         v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s \"%s\""
                         (name attribute)
                         (if (boolean not?)
                           (str "does not "
                                (if (= operator '=)
                                  "equal"
                                  "include"))
                           (if (= operator '=)
                             "equals"
                             "includes"))
                         v))))
       (with-meta {:errors (cond-> []
                             (not valid-value?)
                             (conj (#?(:clj format
                                       :cljs gstring/format)
                                    "\"%s\" is not a valid attribute to query."
                                    (name attribute)))
                             (not valid-operator?)
                             (conj (#?(:clj format
                                       :cljs gstring/format)
                                    "\"%s\" is not a valid operator for %s."
                                    (name (or operator ":"))
                                    (name attribute))))}
         {:error? true})))))

(defmethod datom :cost
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid? (or (and (string? v)
                         (boolean (parse-long v)))
                    (regex? v))
         v (or (when (string? v)
                 (parse-long v))
               v)
         ?value (gensym "?cost")
         ?s (gensym "?cost-string")
         ?dual (gensym "?dual")]
     (if valid?
       (with-meta (cond-> [(list 'or-join
                                 ['?card ?value]
                                 ['?card :card/play-cost ?value]
                                 ['?card :card/use-cost ?value]
                                 (list 'and
                                       ['?card :card/dual ?dual]
                                       [?dual :card/play-cost ?value])
                                 (list 'and
                                       ['?card :card/dual ?dual]
                                       [?dual :card/use-cost ?value]))]
                    (regex? v)
                    (conj [(list 'str ?value) ?s]
                          [(list 're-find
                                 (if (boolean not?)
                                   (re-pattern (str "[^" v "]"))
                                   v)
                                 ?s)])
                    (not (regex? v))
                    (conj (cond->> [(list (or operator '=) ?value v)]
                            (boolean not?)
                            (list 'not))))
         (assoc (meta value)
                :note (if (regex? v)
                        (#?(:clj format
                            :cljs gstring/format) "the play/use cost %s the pattern %s"
                         (if (boolean not?)
                           "does not match"
                           "matches")
                         (str "/" v "/"))
                        (#?(:clj format
                            :cljs gstring/format) "the play/use cost %s %s %s"
                         (cond-> "is"
                           (boolean not?) (str " not"))
                         (case (and operator (name operator))
                           "<" "less than"
                           "<=" "less than or equal to"
                           ">" "greater than"
                           ">=" "greater than or equal to"
                           "equal to")
                         (if (string? v)
                           (str "\"" v "\"")
                           (str "#" v "#"))))))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid %s."
                             v
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
         ?dual (gensym "?dual")
         ?rule (gensym "?rule")
         ?rule-type (symbol (str (name ?rule) "-type"))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         op (if (= operator '=)
              'String/.equals
              'String/.contains)]
     (if valid-operator?
       (with-meta (if (boolean not?)
                    [(cond-> (list 'not-join ['?card]
                                   (list 'or-join
                                         ['?card ?value]
                                         ['?card :card/name ?value]
                                         (list 'and
                                               ['?card :card/dual ?dual]
                                               [?dual :card/name ?value])
                                         (list 'and
                                               ['?card :card/rules ?rule]
                                               [?rule :rule/type ?rule-type]
                                               [?rule :rule/value ?value]
                                               [(list 'contains?
                                                      #{:card/name} ?rule-type)])))
                       (string? v)
                       (concat (list [(list 'String/.toLowerCase ?value) ?value-lower]
                                     [(list op ?value-lower
                                            (string/lower-case v))]))
                       (regex? v)
                       (concat (list [(list 're-find v ?value)])))]
                    (cond-> [(list 'or-join
                                   ['?card ?value]
                                   ['?card :card/name ?value]
                                   (list 'and
                                         ['?card :card/dual ?dual]
                                         [?dual :card/name ?value])
                                   (list 'and
                                         ['?card :card/rules ?rule]
                                         [?rule :rule/type ?rule-type]
                                         [?rule :rule/value ?value]
                                         [(list 'contains?
                                                #{:card/name} ?rule-type)]))]
                      (string? v)
                      (conj [(list 'String/.toLowerCase ?value) ?value-lower]
                            [(list op ?value-lower
                                   (string/lower-case v))])
                      (regex? v)
                      (conj [(list 're-find v ?value)])))
         (assoc (meta value)
                :note (if (string? v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s \"%s\""
                         (name attribute)
                         (if (boolean not?)
                           (str "does not "
                                (if (= operator '=)
                                  "is exactly"
                                  "include"))
                           (if (= operator '=)
                             "is exactly"
                             "includes"))
                         v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s the pattern %s"
                         (name attribute)
                         (if (boolean not?)
                           "does not match"
                           "matches")
                         (str "/" v "/")))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for name."
                            (name (or operator ":")))))}
         {:error? true})))))

(defmethod datom :card/number
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-lower (symbol (str (name ?value) "-lower"))
         ?dual (gensym "?dual")
         ?rule (gensym "?rule")
         ?rule-type (symbol (str (name ?rule) "-type"))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         op (if (= operator '=)
              'String/.equals
              'String/.contains)]
     (if valid-operator?
       (with-meta (if (boolean not?)
                    [(cond-> (list 'not-join ['?card]
                                   (list 'or-join
                                         ['?card ?value]
                                         ['?card :card/number ?value]
                                         (list 'and
                                               ['?card :card/dual ?dual]
                                               [?dual :card/number ?value])
                                         (list 'and
                                               ['?card :card/rules ?rule]
                                               [?rule :rule/type ?rule-type]
                                               [?rule :rule/value ?value]
                                               [(list 'contains?
                                                      #{:card/number} ?rule-type)])))
                       (string? v)
                       (concat (list [(list 'String/.toLowerCase ?value) ?value-lower]
                                     [(list op ?value-lower
                                            (string/lower-case v))]))
                       (regex? v)
                       (concat (list [(list 're-find v ?value)])))]
                    (cond-> [(list 'or-join
                                   ['?card ?value]
                                   ['?card :card/number ?value]
                                   (list 'and
                                         ['?card :card/dual ?dual]
                                         [?dual :card/number ?value])
                                   (list 'and
                                         ['?card :card/rules ?rule]
                                         [?rule :rule/type ?rule-type]
                                         [?rule :rule/value ?value]
                                         [(list 'contains?
                                                #{:card/number} ?rule-type)]))]
                      (string? v)
                      (conj [(list 'String/.toLowerCase ?value) ?value-lower]
                            [(list op ?value-lower
                                   (string/lower-case v))])
                      (regex? v)
                      (conj [(list 're-find v ?value)])))
         (assoc (meta value)
                :note (if (string? v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s \"%s\""
                         (name attribute)
                         (if (boolean not?)
                           (str "does not "
                                (if (= operator '=)
                                  "equal"
                                  "include"))
                           (if (= operator '=)
                             "equals"
                             "includes"))
                         v)
                        (#?(:clj format
                            :cljs gstring/format) "the %s %s the pattern %s"
                         (name attribute)
                         (if (boolean not?)
                           "does not match"
                           "matches")
                         (str "/" v "/")))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for number."
                            (name (or operator ":")))))}
         {:error? true})))))

(defmethod datom :supplemental-rarity/stamp
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [valid-stamps (->> ["SP"]
                           (into #{}))
         valid-value? (if (string? v)
                        (valid-stamps (string/upper-case v))
                        (regex? v))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (and valid-value?
                     valid-operator?)
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?stamp-value (gensym "?stamp-value")]
     (if valid?
       (with-meta (cond-> [['?card :card/supplemental-rarity ?value]]
                    (regex? v)
                    (conj [?value attribute ?stamp-value]
                          [(list 're-find v ?stamp-value)])
                    (string? v)
                    (conj [?value attribute (string/upper-case v)])
                    (boolean not?)
                    (as-> #__ datoms
                      [(reduce (fn [accl datom]
                                 (concat accl [datom]))
                               (list 'not-join
                                     ['?card])
                               datoms)]))
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "the rarity stamp %s %s"
                       (if (regex? v)
                         (str (if (boolean not?)
                                "does not match"
                                "matches")
                              " the pattern")
                         (cond-> "is"
                           (boolean not?)
                           (str " not")))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\"" v "\"")))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-operator?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "\"%s\" is not a valid operator for number."
                            (name (or operator ":")))))}
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
         valid-value? (when (string? v)
                        (valid-categories (string/lower-case v)))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (or (and valid-value?
                         valid-operator?)
                    (regex? v))
         v (cond-> v
             (and valid?
                  (and (string? v))) (-> string/lower-case
                                         keyword))
         digivolve? (= (namespace attribute)
                       "digivolve")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?category-value (gensym "?category")
         ?category-name (gensym "?category-name")
         ?dual (gensym "?dual")]
     (if valid?
       (with-meta (if digivolve?
                    (cond-> [['?card :card/digivolution-requirements ?value]]
                      (regex? v)
                      (conj [?value attribute ?category-value]
                            [(list 'name ?category-value) ?category-name]
                            [(list 're-find v ?category-name)])
                      (not (regex? v))
                      (conj [?value attribute v])
                      (boolean not?)
                      (as-> #__ datoms
                        [(reduce (fn [accl datom]
                                   (concat accl [datom]))
                                 (list 'not-join
                                       ['?card])
                                 datoms)]))
                    [(cond->> (list 'or-join
                                    ['?card]
                                    (if (regex? v)
                                      (list 'and
                                            ['?card attribute ?value]
                                            [(list 'name ?value) ?category-name]
                                            [(list 're-find v ?category-name)])
                                      ['?card attribute v])
                                    (if (regex? v)
                                      (list 'and
                                            ['?card :card/dual ?dual]
                                            [?dual attribute ?value]
                                            [(list 'name ?value) ?category-name]
                                            [(list 're-find v ?category-name)])
                                      (list 'and
                                            ['?card :card/dual ?dual]
                                            [?dual attribute v])))
                       (boolean not?)
                       (list 'not-join
                             ['?card]))])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s %s"
                       (str (if digivolve?
                              (if (boolean not?)
                                "it does not digivolve from"
                                "it digivolves from")
                              (str "the "
                                   (string/replace (name attribute)
                                                   "-" " ")
                                   (cond-> " is"
                                     (boolean not?) (str " not"))))
                            (when (regex? v)
                              " a pattern match of"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\""
                              (case v
                                :digi-egg "Digi-Egg"
                                (-> v name string/capitalize))
                              "\"")))))
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
         valid-value? (when (string? v)
                        (valid-colors (string/lower-case v)))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (or (and valid-value?
                         valid-operator?)
                    (regex? v))
         v (cond-> v
             (and valid?
                  (and (string? v))) (-> string/lower-case
                                         keyword))
         digivolve? (= (namespace attribute)
                       "digivolve")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?color-value (gensym "?color-value")
         ?color-name (gensym "?color-name")
         ?dual (gensym "?dual")
         ?dual-color (gensym "?dual-color")]
     (if valid?
       (with-meta (if digivolve?
                    (cond-> [['?card :card/digivolution-requirements ?value]]
                      (regex? v)
                      (conj [?value attribute ?color-value]
                            [(list 'name ?color-value) ?color-name]
                            [(list 're-find v ?color-name)])
                      (not (regex? v))
                      (conj [?value attribute v])
                      (boolean not?)
                      (as-> #__ datoms
                        [(reduce (fn [accl datom]
                                   (concat accl [datom]))
                                 (list 'not-join
                                       ['?card])
                                 datoms)]))
                    [(cond->> (list 'or-join
                                    ['?card]
                                    (if (regex? v)
                                      (list 'and
                                            ['?card attribute ?value]
                                            [?value :color/color ?color-value]
                                            [(list 'name ?color-value) ?color-name]
                                            [(list 're-find v ?color-name)])
                                      (list 'and
                                            ['?card attribute ?value]
                                            [?value :color/color v]))
                                    (if (regex? v)
                                      (list 'and
                                            ['?card :card/dual ?dual]
                                            [?dual attribute ?dual-color]
                                            [?dual-color :color/color ?color-value]
                                            [(list 'name ?color-value) ?color-name]
                                            [(list 're-find v ?color-name)])
                                      (list 'and
                                            ['?card :card/dual ?dual]
                                            [?dual attribute ?dual-color]
                                            [?dual-color :color/color v])))
                       (boolean not?)
                       (list 'not-join
                             ['?card]))])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s %s"
                       (str (if digivolve?
                              (if (boolean not?)
                                "it does not digivolve from the colour"
                                "it digivolves from the colour")
                              (str "the colour"
                                   (cond-> " is"
                                     (boolean not?) (str " not"))))
                            (when (regex? v)
                              " pattern"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\"" (name v) "\"")))))
       (with-meta {:errors
                   (cond-> []
                     (not valid-value?)
                     (conj (#?(:clj format
                               :cljs gstring/format)
                            "%s is not a valid %s."
                            (if (regex? v)
                              (str "/" v "/")
                              (str "\"" v "\""))
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
   (let [valid? (or (when (string? v)
                      (boolean (parse-long v)))
                    (regex? v))
         v (or (when (string? v)
                 (parse-long v))
               v)
         digivolve? (= (namespace attribute)
                       "digivolve")
         supplemental-rarity? (= (namespace attribute)
                                 "supplemental-rarity")
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?value-value (symbol (str (name ?value) "-value"))
         ?s (gensym "?value-string")
         ?dual (gensym "?dual")
         ?dual-value (gensym "?dual-value")]
     (if valid?
       (with-meta (cond-> (if (and (not digivolve?)
                                   (not supplemental-rarity?))
                            (if (boolean not?)
                              [(cond-> (list 'not-join
                                             ['?card]
                                             ['?card attribute ?value])
                                 (regex? v)
                                 (concat (list [(list 'str ?value) ?s]
                                               [(list 're-find v ?s)]))
                                 (number? v)
                                 (concat (list [(list (or operator '=) ?value v)])))
                               (cond-> (list 'not-join
                                             ['?card]
                                             ['?card :card/dual ?dual]
                                             [?dual attribute ?value])
                                 (regex? v)
                                 (concat (list [(list 'str ?value) ?s]
                                               [(list 're-find v ?s)]))
                                 (number? v)
                                 (concat (list [(list (or operator '=) ?value v)])))]
                              (cond-> [(list 'or-join
                                             ['?card ?value]
                                             ['?card attribute ?value]
                                             (list 'and
                                                   ['?card :card/dual ?dual]
                                                   [?dual attribute ?value]))]
                                (regex? v)
                                (conj [(list 'str ?value) ?s]
                                      [(list 're-find v ?s)])
                                (number? v)
                                (conj [(list (or operator '=) ?value v)])))
                            [])
                    digivolve?
                    (conj (when-not (boolean not?)
                            ['?card :card/digivolution-requirements ?value])
                          (when-not (boolean not?)
                            [?value attribute ?value-value])
                          (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card :card/digivolution-requirements ?value]
                                  [?value attribute ?value-value]
                                  [(list (or operator '=) ?value-value v)])
                            [(list (or operator '=) ?value-value v)]))
                    supplemental-rarity?
                    (conj (when-not (boolean not?)
                            ['?card :card/supplemental-rarity ?value])
                          (when-not (boolean not?)
                            [?value attribute ?value-value])
                          (if (boolean not?)
                            (list 'not-join
                                  ['?card]
                                  ['?card :card/supplemental-rarity ?value]
                                  [?value attribute ?value-value]
                                  [(list (or operator '=) ?value-value v)])
                            [(list (or operator '=) ?value-value v)])))
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "%s %s %s"
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
                         (if (regex? v)
                           "a match for the pattern"
                           "equal to"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "#" v "#")))))
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
         valid-value? (when (string? v)
                        (valid-languages (string/lower-case v)))
         valid-operator? (or (nil? operator)
                             (= operator '=))
         valid? (or (and valid-value?
                         valid-operator?)
                    (regex? v))
         ?language (gensym "?language")]
     (if valid?
       (with-meta (cond-> (if (regex? v)
                            [['?card attribute ?language]
                             [(list 're-find v ?language)]]
                            [['?card attribute (->> (string/lower-case v)
                                                    (language-map))]])
                    (boolean not?)
                    (as-> #__ datoms
                      [(reduce (fn [accl datom]
                                 (concat accl [datom]))
                               (list 'not-join
                                     ['?card])
                               datoms)]))
         (assoc (meta value)
                :language? true
                :note (#?(:clj format
                          :cljs gstring/format) "the language %s %s"
                       (str (cond-> "is"
                              (boolean not?)
                              (str " not"))
                            (when (regex? v)
                              " a pattern match for"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\""
                              (-> (string/lower-case v)
                                  (language-map)
                                  name
                                  (string/replace #"^_$" "any"))
                              "\"")))))
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
   (let [valid-rarities (->> ["C" "U" "R" "SR" "UR" "SEC" "P"]
                             (into #{}))
         ?value (gensym "?rarity")
         ?rarity-name (gensym "?rarity-name")
         ?rarity-rank (gensym "?rarity-rank")
         ?rarity-rank-tr (gensym "?rarity-rank-tr")
         valid? (or (when (string? v)
                      (valid-rarities (string/upper-case v)))
                    (regex? v))]
     (if valid?
       (with-meta (if (regex? v)
                    (cond-> [['?card attribute ?value]
                             [(list 'name ?value) ?rarity-name]
                             [(list 're-find v ?rarity-name)]]
                      (boolean not?)
                      (as-> #__ datoms
                        [(reduce (fn [accl datom]
                                   (concat accl [datom]))
                                 (list 'not-join
                                       ['?card])
                                 datoms)]))
                    [['?card attribute ?value]
                     (list 'rarity-rank ?value
                           ?rarity-rank)
                     (list 'rarity-rank (string/upper-case v)
                           ?rarity-rank-tr)
                     (cond->> [(list (or operator '=)
                                     ?rarity-rank
                                     ?rarity-rank-tr)]
                       (boolean not?)
                       (list 'not))])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "the rarity %s %s %s"
                       (cond-> "is"
                         (boolean not?)
                         (str " not"))
                       (if (regex? v)
                         "a pattern match for"
                         (case (some-> operator name)
                           "<" "less than"
                           "<=" "less than or equal to"
                           ">" "greater than"
                           ">=" "greater than or equal to"
                           "equal to"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\"" (string/upper-case v) "\"")))))
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
         valid? (or (when (string? v)
                      (valid-forms (string/lower-case v)))
                    (regex? v))
         v (cond-> v
             (and valid?
                  (string? v))
             (-> string/lower-case
                 keyword))
         ?value (gensym (str "?"
                             (namespace attribute)
                             "-"
                             (name attribute)))
         ?form-name (gensym "?form-name")
         ?form-rank (gensym "?form-rank")
         ?form-rank-tr (gensym "?form-rank-tr")
         ?value-value (symbol (str (name ?value) "-value"))]
     (if valid?
       (with-meta (if (regex? v)
                    (cond-> [['?card :card/digivolution-requirements ?value]
                             [?value attribute ?value-value]
                             [(list 'name ?value-value) ?form-name]
                             [(list 're-find v ?form-name)]]
                      (boolean not?)
                      (as-> #__ datoms
                        [(reduce (fn [accl datom]
                                   (concat accl [datom]))
                                 (list 'not-join
                                       ['?card])
                                 datoms)]))
                    [['?card :card/digivolution-requirements ?value]
                     [?value attribute ?value-value]
                     (list 'digivolve-form-rank ?value-value ?form-rank)
                     (list 'digivolve-form-rank v ?form-rank-tr)
                     (if (boolean not?)
                       (list 'not-join
                             ['?card ?form-rank-tr]
                             ['?card :card/digivolution-requirements ?value]
                             [?value attribute ?value-value]
                             (list 'digivolve-form-rank ?value-value ?form-rank)
                             [(list (or operator '=) ?form-rank ?form-rank-tr)])
                       [(list (or operator '=) ?form-rank ?form-rank-tr)])])
         (assoc (meta value)
                :note (#?(:clj format
                          :cljs gstring/format) "it %s from a form %s %s"
                       (if (boolean not?)
                         "does not digivolve"
                         "digivolves")
                       (if (regex? v)
                         "matching the pattern of"
                         (case (some-> operator name)
                           "<" "less than"
                           "<=" "less than or equal to"
                           ">" "greater than"
                           ">=" "greater than or equal to"
                           "equal to"))
                       (if (regex? v)
                         (str "/" v "/")
                         (str "\""
                              (string/capitalize (name v))
                              "\"")))))
       (with-meta {:errors [(#?(:clj format
                                :cljs gstring/format)
                             "\"%s\" is not a valid %s."
                             v
                             (str "digivolve-" (name attribute)))]}
         {:error? true})))))

(defmethod datom :release
  ([attribute operator value]
   (datom nil attribute operator value))
  ([not? attribute operator [_ v :as value]]
   (let [?release (gensym "?releases")
         ?release-name (gensym "?release-name")
         ?value-lower (symbol (str (name ?release) "-lower"))]
     (with-meta (cond-> [['?card :card/releases ?release]
                         [?release :release/name ?release-name]]
                  (regex? v)
                  (conj [(list 're-find v ?release-name)])
                  (string? v)
                  (conj [(list 'String/.toLowerCase ?release-name) ?value-lower]
                        [(list 'String/.contains
                               ?value-lower
                               (string/lower-case v))])
                  (boolean not?)
                  (as-> #__ datoms
                    [(reduce (fn [accl datom]
                               (concat accl [datom]))
                             (list 'not-join
                                   ['?card])
                             datoms)]))
       (assoc (meta value)
              :note (#?(:clj format
                        :cljs gstring/format) "the card release %s %s"
                     (if (regex? v)
                       (if (boolean not?)
                         "does not match the pattern"
                         "matches the pattern")
                       (if (boolean not?)
                         "does not include"
                         "includes"))
                     (if (regex? v)
                       (str "/" v "/")
                       (str "\"" v "\""))))))))

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
                       "traits"
                       "cost"
                       "release"} a) (keyword a)
                    :else (keyword "card" a))))
   :operator (fn
               ([])
               ([operator]
                (symbol operator)))
   :quoted (fn [s]
             (subs s 1 (dec (count s))))
   :regex (fn [s]
            (re-pattern (subs s 1 (dec (count s)))))
   :datom datom
   :not-string (fn [s]
                 (let [?value (gensym "?card-name-or-number")
                       ?dual (gensym "?dual")
                       ?rule (gensym "?rule")
                       ?rule-type (symbol (str (name ?rule) "-type"))]
                   (with-meta [(list 'or-join
                                     ['?card ?value]
                                     ['?card :card/name ?value]
                                     ['?card :card/number ?value]
                                     (list 'and
                                           ['?card :card/dual ?dual]
                                           [?dual :card/name ?value])
                                     (list 'and
                                           ['?card :card/dual ?dual]
                                           [?dual :card/number ?value])
                                     (list 'and
                                           ['?card :card/rules ?rule]
                                           [?rule :rule/type ?rule-type]
                                           [?rule :rule/value ?value]
                                           [(list 'contains?
                                                  #{:card/name
                                                    :card/number} ?rule-type)]))
                               [(list 'String/.toLowerCase ?value)
                                (symbol (str (name ?value) "-lower"))]
                               (list 'not
                                     [(list 'String/.contains
                                            (symbol (str (name ?value) "-lower"))
                                            (string/lower-case s))])]
                     {:note (#?(:clj format
                                :cljs gstring/format) "the name or number does not include \"%s\"" s)
                      :instaparse.gll/start-index nil
                      :instaparse.gll/end-index nil})))
   :exact-string (fn exact-string
                   ([s]
                    (exact-string nil s))
                   ([not? s]
                    (let [?value (gensym "?card-name-or-number")
                          ?dual (gensym "?dual")
                          ?rule (gensym "?rule")
                          ?rule-type (symbol (str (name ?rule) "-type"))
                          ?value-lower (symbol (str (name ?value) "-lower"))]
                      (with-meta [(list 'or-join
                                        ['?card ?value]
                                        ['?card :card/name ?value]
                                        ['?card :card/number ?value]
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/name ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/number ?value])
                                        (list 'and
                                              ['?card :card/rules ?rule]
                                              [?rule :rule/type ?rule-type]
                                              [?rule :rule/value ?value]
                                              [(list 'contains?
                                                     #{:card/name
                                                       :card/number} ?rule-type)]))
                                  [(list 'String/.toLowerCase ?value) ?value-lower]
                                  (cond->> [(list '=
                                                  ?value-lower
                                                  (string/lower-case s))]
                                    (boolean not?)
                                    (list 'not))]
                        {:note (#?(:clj format
                                   :cljs gstring/format) "the name or number is %s \"%s\""
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
                                      :card/restriction
                                      :card/dual}
                 attribute (keyword "card" s)
                 valid? (boolean (allowed-attributes attribute))
                 ?dual (gensym "?dual")]
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
                            :card/dual
                            [(if (boolean not?)
                               (list 'not ['?card :card/dual])
                               ['?card :card/dual])]
                            [(if (boolean not?)
                               (list 'and
                                     (list 'not
                                           ['?card attribute])
                                     (list 'not-join
                                           ['?card]
                                           ['?card :card/dual ?dual]
                                           [?dual attribute]))
                               (list 'or-join
                                     ['?card]
                                     ['?card attribute]
                                     (list 'and
                                           ['?card :card/dual ?dual]
                                           [?dual attribute])))])
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
   :traits (fn traits
             ([value]
              (traits nil value))
             ([not? [_ v :as value]]
              (let [?value (gensym "?traits")
                    ?value-lower (gensym "?value-lower")
                    ?rule (gensym "?rule")
                    ?rule-type (symbol (str (name ?rule) "-type"))]
                (with-meta (cond-> [(list 'or-join
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
                                                         :card/type} ?rule-type)]))]
                             (regex? v)
                             (conj [(list 're-find v ?value)])
                             (string? v)
                             (conj [(list 'String/.toLowerCase ?value) ?value-lower]
                                   [(list 'String/.contains
                                          ?value-lower
                                          (string/lower-case v))])
                             (boolean not?)
                             (as-> #__ datoms
                               [(reduce (fn [accl datom]
                                          (concat accl [datom]))
                                        (list 'not-join
                                              ['?card])
                                        datoms)]))
                  (assoc (meta value)
                         :note (#?(:clj format
                                   :cljs gstring/format) "the card traits %s %s"
                                (if (regex? v)
                                  (if (boolean not?)
                                    "does not match the pattern"
                                    "matches the pattern")
                                  (if (boolean not?)
                                    "does not include"
                                    "includes"))
                                (if (regex? v)
                                  (str "/" v "/")
                                  (str "\"" v "\""))))))))
   :text (fn text
           ([value]
            (text nil value))
           ([not? [_ v :as value]]
            (let [?value (gensym "?text")
                  ?value-lower (gensym "?text-lower")
                  ?dual (gensym "?dual")
                  ?rule (gensym "?rule")
                  ?rule-type (symbol (str (name ?rule) "-type"))]
              (with-meta (cond-> [(list 'or-join ['?card ?value]
                                        ['?card :card/attribute ?value]
                                        ['?card :card/form ?value]
                                        ['?card :card/type ?value]
                                        ['?card :card/number ?value]
                                        ['?card :card/name ?value]
                                        ['?card :card/effect ?value]
                                        ['?card :card/inherited-effect ?value]
                                        ['?card :card/security-effect ?value]
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/attribute ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/form ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/type ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/number ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/name ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/effect ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/inherited-effect ?value])
                                        (list 'and
                                              ['?card :card/dual ?dual]
                                              [?dual :card/security-effect ?value])
                                        (list 'and
                                              ['?card :card/rules ?rule]
                                              [?rule :rule/type ?rule-type]
                                              [?rule :rule/value ?value]
                                              [(list 'contains?
                                                     #{:card/name
                                                       :card/number
                                                       :card/attribute
                                                       :card/form
                                                       :card/type} ?rule-type)]))]
                           (regex? v)
                           (conj [(list 're-find v ?value)])
                           (string? v)
                           (conj [(list 'String/.toLowerCase ?value) ?value-lower]
                                 [(list 'String/.contains
                                        ?value-lower
                                        (string/lower-case v))])
                           (boolean not?)
                           (as-> #__ datoms
                             [(reduce (fn [accl datom]
                                        (concat accl [datom]))
                                      (list 'not-join
                                            ['?card])
                                      datoms)]))
                (assoc (meta value)
                       :note (#?(:clj format
                                 :cljs gstring/format) "the card text %s %s"
                              (if (regex? v)
                                (if (boolean not?)
                                  "does not match the pattern"
                                  "matches the pattern")
                                (if (boolean not?)
                                  "does not include"
                                  "includes"))
                              (if (regex? v)
                                (str "/" v "/")
                                (str "\"" v "\""))))))))})

(defn transform
  [transform-map parse-tree]
  (->> (insta/transform transform-map parse-tree)
       (map (fn [s]
              (if (or (string? s)
                      (regex? s))
                (with-meta (let [?value (gensym "?card-name-or-number")
                                 ?value-lower (gensym "?value-lower")
                                 ?dual (gensym "?dual")
                                 ?rule (gensym "?rule")
                                 ?rule-type (symbol (str (name ?rule) "-type"))]
                             (cond-> [(list 'or-join
                                            ['?card ?value]
                                            ['?card :card/name ?value]
                                            ['?card :card/number ?value]
                                            (list 'and
                                                  ['?card :card/dual ?dual]
                                                  [?dual :card/name ?value])
                                            (list 'and
                                                  ['?card :card/dual ?dual]
                                                  [?dual :card/number ?value])
                                            (list 'and
                                                  ['?card :card/rules ?rule]
                                                  [?rule :rule/type ?rule-type]
                                                  [?rule :rule/value ?value]
                                                  [(list 'contains?
                                                         #{:card/name
                                                           :card/number} ?rule-type)]))]
                               (regex? s)
                               (conj [(list 're-find s ?value)])
                               (string? s)
                               (conj [(list 'String/.toLowerCase ?value) ?value-lower]
                                     [(list 'String/.contains
                                            ?value-lower
                                            (string/lower-case s))])))
                  {:note (str "the name or number "
                              (if (regex? s)
                                "matches the pattern "
                                "includes ")
                              (if (regex? s)
                                (str "/" s "/")
                                (str "\"" s "\"")))
                   :instaparse.gll/start-index nil
                   :instaparse.gll/end-index nil})
                s)))))

(def ranking-rules
  '[[(rarity-rank ?r ?rank)
     [(ground [["P"  -1]
               ["C"   0]
               ["U"   1]
               ["R"   2]
               ["SR"  3]
               ["UR"  4]
               ["SEC" 5]])
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
                (into ['[?card :card/id _]
                       '(not [_ :card/dual ?card])]))]
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
                                   Long/MAX_VALUE))
                             :card/parallel-id)))))))

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
     (let [q (or q "")
           parse-tree (->> (parser q)
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
           total (count db-results)
           summary (if (<= total max-per-page)
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
           fragments (case (count fragments)
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
                                  (next remaining)))))
           cards (nth (partition-all max-per-page db-results)
                      (dec page)
                      [])]
       {:query/pagination
        {:pagination/page page
         :pagination/total total
         :pagination/cards cards
         :pagination/summary summary
         :pagination/errors errors
         :pagination/fragments fragments
         :pagination/max-per-page max-per-page
         :pagination/pages (max (-> (/ total max-per-page)
                                    math/ceil
                                    math/round)
                                1)
         :pagination/prev (when (> page 1)
                            true)
         :pagination/next (when (< page (/ total max-per-page))
                            true)}})))

(defn query-highlight
  [s]
  (try (->> (parser s)
            (transform transform-map)
            (keep (fn [xs]
                    (when (insta/span xs)
                      (meta xs))))
            (reduce (fn [accl {:instaparse.gll/keys [start-index end-index]
                               :keys [error?]}]
                      (let [processed-index
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
            (apply str))
       (catch #?(:clj Exception
                 :cljs js/Error) e
         #?(:cljs (js/console.error "Search Highlight Error" e))
         s)))

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
                      :dcg.api.core/keys [default-language]
                      :or {default-language "en"}} :request}]
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
      :existed? (fn [{{{{:keys [q]} :query} :parameters} :request}]
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
                       {{:pagination/keys [cards errors summary fragments total
                                           pages prev next]} :query/pagination}
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
      :as-response (fn [data context]
                     (-> data
                         (representation/as-response
                          (assoc-in context
                                    [:representation :media-type]
                                    "application/vnd.api+json"))))}))
