(ns dcg.db.card.utils
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.awt Transparency]
   [java.awt.image BufferedImage]
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [javax.imageio ImageIO]))

(def card-number-re #"[A-Z|0-9]{1,4}\-[0-9]{2,3}")

(def text-punctuation
  {:spaces           [" "
                      "\u3000"]
   :periods          ["."
                      "\u3002"
                      "\uFF0E"
                      "\uFF61"]
   :bullet-points    ["\u00B7"
                      "\u2022"
                      "\u2023"
                      "\u2043"
                      "\u2219"
                      "\u25E6"
                      "\u30FB"]
   :square-brackets  [["\u300C" "\u300D"]
                      ["\u3010" "\u3011"]
                      ["[" "]"]
                      ["\uFF3B" "\uFF3D"]
                      ["\u201C" "\u201D"]]})

(def re-escape-map
  {\[ "\\["
   \] "\\]"
   \( "\\("
   \) "\\)"})

(defn within-brackets-re
  [brackets]
  (->> brackets
       (map (fn [[open close]]
              (format "%s([^%s]+)%s"
                      (string/escape open re-escape-map)
                      (string/escape close re-escape-map)
                      (string/escape close re-escape-map))))
       (string/join "|")
       re-pattern))

(defn normalize-string
  [s]
  (condp = s
    "  " nil
    "、" nil
    "ー" nil
    "－" nil
    "―" nil
    "-" nil
    (-> s
        (string/replace "１" "1")
        (string/replace "Ｘ" "X")
        (string/replace "＋" "+")
        (string/replace "：" ":")
        (string/replace "＆" "&")
        (string/replace #"\h+" " ")
        (string/replace "\uFF01" "!")
        (string/replace "’" "'")
        (string/replace "\uFF0C" ",")
        (string/replace #"\s*\(Rule\)" " ⟨Rule⟩")
        (string/replace "<规则>" "\u3008规则\u3009")
        (string/replace #"(\s?)A(?i)ce$" "$1ACE")
        (string/replace (->> (:spaces text-punctuation)
                             (string/join "|" )
                             re-pattern) " ")
        (as-> #_s string
          (let [eastern-parens? (or (string/includes? string "（")
                                    (string/includes? string "）"))
                eastern-brackets? (or (string/includes? string "［")
                                      (string/includes? string "］"))]
            (cond-> string
              eastern-parens?
              (-> (string/replace #"\(" "（")
                  (string/replace #"\)" "）"))
              eastern-brackets?
              (-> (string/replace #"\[" "［")
                  (string/replace #"\]" "］")))))
        (as-> #_s string
          (let [ending-brackets (->> (:square-brackets text-punctuation)
                                     (map second))]
            (reduce (fn [accl ending-bracket]
                      (string/replace accl
                                      (re-pattern (str "\\s+" ending-bracket))
                                      ending-bracket))
                    string
                    ending-brackets)))
        (string/replace #"(デジクロス\s?\-[0-9])" "\u226A$1\u226B")
        (string/replace #"(DigiXros\s?\-[0-9])\s?:" "<$1>")
        (string/replace #"\s+\." "."))))

(defn text-content
  [element]
  (when (and (map? element)
             (:content element))
    (-> (map (fn [e]
               (cond
                 (string? e) e
                 (and (map? e) (= (get e :tag) :br)) "\n"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/jogress.png")) "ジョグレス"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/evolution.png")) "【進化】"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/burst_evolution.png")) "【バースト進化】"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/digicross-1.png")) "デジクロス-1"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/digicross-2.png")) "デジクロス-2"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/digicross-3.png")) "デジクロス-3"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/overflow-1.png"))
                 "≪オーバーフロー\u300A-1\u300B≫"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/overflow-2.png"))
                 "≪オーバーフロー\u300A-2\u300B≫"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/overflow-3.png"))
                 "≪オーバーフロー\u300A-3\u300B≫"
                 (and (map? e)
                      (= (get e :tag) :img)
                      (string/ends-with? (get-in e [:attrs :src])
                                         "/overflow-4.png"))
                 "≪オーバーフロー\u300A-4\u300B≫"
                 :else (text-content e)))
             (:content element))
        string/join
        (string/replace "ofthis" "of this")
        (string/replace "\u3000" " ")
        (string/replace #"\h+" " ")
        (string/replace "\r" "")
        (string/replace #"\n+" "\n")
        string/trim)))

(defn- bytes->buffered-image
  [bs]
  (with-open [is (io/input-stream bs)]
    (ImageIO/read is)))

(defn- buffered-image->bytes
  [^BufferedImage image]
  (with-open [baos (ByteArrayOutputStream.)]
    (ImageIO/write image "png" baos)
    (.toByteArray baos)))

(defn trim-transparency!
  [bs]
  (let [i ^BufferedImage (bytes->buffered-image bs)]
    (if (= (.getTransparency i) Transparency/TRANSLUCENT)
      (let [width (.getWidth i)
            height (.getHeight i)
            mid-x (quot width 2)
            mid-y (quot height 2)
            top (loop [y 0]
                  (if (= (bit-shift-right (.getRGB i mid-x y) 24) 0x00)
                    (recur (inc y))
                    y))
            bottom (loop [y (dec height)]
                     (if (= (bit-shift-right (.getRGB i mid-x y) 24) 0x00)
                       (recur (dec y))
                       y))
            left (loop [x 0]
                   (if (= (bit-shift-right (.getRGB i x mid-y) 24) 0x00)
                     (recur (inc x))
                     x))
            right (loop [x (dec width)]
                    (if (= (bit-shift-right (.getRGB i x mid-y) 24) 0x00)
                      (recur (dec x))
                      x))
            cropped-image (.getSubimage i
                                        left
                                        top
                                        (inc (- right left))
                                        (inc (- bottom top)))]
        (buffered-image->bytes cropped-image))
      (buffered-image->bytes i))))
