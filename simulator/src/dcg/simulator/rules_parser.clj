(ns dcg.simulator.rules-parser
  (:require
   [clojure.java.io :as io]
   [instaparse.core :as insta]))

(def parser (insta/parser (io/resource "parser.bnf")))

(parser "[On Play] By placing this Digimon under 1 of your other Digimon that's black or has [Legend-Arms] in its traits as its bottom digivolution card, delete 1 of your opponent's Digimon with 5000 DP or less.")

(parser "[When Digivolving] Trash the top 3 cards of your deck.[When Digivolving] This Digimon gains \"[On Deletion] If there are 10 or more cards in your trash, you may play 1 [Beelzemon] from your trash without paying the cost\" until the end of your opponent's turn.")

(parser "[Opponent's Turn] While you have a Digimon that's red or has [Legend-Arms] in its traits in play, this Digimon gains ＜Blocker＞. (When an opponent's Digimon attacks, you may suspend this Digimon to force the opponent to attack it instead.)")

(parser "[Opponent's Turn] While you have a red Digimon or [Legend-Arms] in its traits in play, this Digimon gains ＜Blocker＞. (When an opponent's Digimon attacks, you may suspend this Digimon to force the opponent to attack it instead.)")

(parser "[When Digivolving] ＜Blitz＞ (This Digimon can attack when your opponent has 1 or more memory.)[When Attacking] You may play 1 Digimon card with [Sistermon] in its name from your hand without paying its memory cost.[Your Turn][Once Per Turn] When you play another Digimon by an effect, this Digimon gets +3000 DP and gains ＜Security Attack +1＞ for the turn. (This Digimon checks 1 additional security card.)")

(parser "[Main] 1 of your Digimon gets +2000 DP for the turn. Then, if you have a Digimon with [Huckmon] in its name or [Royal Knight] in its traits in play, gain 1 memory, and 1 of your Digimon gains ＜Piercing＞ for the turn. (When this Digimon attacks and deletes an opponent's Digimon and survives the battle, it performs any security checks it normally would.)")

(comment
  (require '[datomic.api :as d]
           '[dcg.db :as db])
  (db/import-from-file!)
  (let [m (->> (d/q '{:find [[(pull ?c [:card/name
                                        :card/number
                                        :card/effect
                                        :card/inherited-effect
                                        :card/security-effect
                                        {:card/highlights
                                         [:highlight/id
                                          :highlight/type
                                          :highlight/field
                                          :highlight/index
                                          :highlight/text]}]) ...]]
                      :in [$]
                      :where [[?c :card/image ?i]
                              [?c :card/parallel-id 0]
                              (or [?c :card/effect _]
                                  [?c :card/inherited-effect _]
                                  [?c :card/security-effect _])
                              [?c :card/number ?n]
                              #_(or [(clojure.string/starts-with? ?n "ST1-")]
                                    [(clojure.string/starts-with? ?n "ST2-")]
                                    [(clojure.string/starts-with? ?n "ST3-")]
                                    [(clojure.string/starts-with? ?n "ST4-")]
                                    [(clojure.string/starts-with? ?n "ST5-")]
                                    [(clojure.string/starts-with? ?n "ST6-")]
                                    [(clojure.string/starts-with? ?n "ST7-")]
                                    [(clojure.string/starts-with? ?n "ST8-")]
                                    [(clojure.string/starts-with? ?n "ST9-")]
                                    [(clojure.string/starts-with? ?n "ST10-")])
                              #_[(clojure.string/starts-with? ?n "ST12-")]
                              #_[(clojure.string/starts-with? ?n "ST13-")]
                              #_[(clojure.string/starts-with? ?n "ST14-")]
                              [(clojure.string/starts-with? ?n "BT1-")]
                              [?i :image/language "en"]]}
                    (d/db db/conn))
               (mapcat (juxt :card/effect
                             :card/inherited-effect
                             :card/security-effect))
               (remove nil?)
               (map dcg.simulator.rules-parser/parser)
               (group-by instaparse.core/failure?))
        result (update-vals m count)]
    {:percentage (when (get result false)
                   (* (float (/ (get result false)
                                (+ (get result false)
                                   (get result true 0))))
                      100))
     :success (get result false 0)
     :total (+ (get result false 0)
               (get result true 0))}
    (->> (get m true)
         (map :text)
         sort))

  )
