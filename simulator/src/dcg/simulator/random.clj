(ns dcg.simulator.random
  (:refer-clojure :exclude [shuffle])
  (:import
   [java.util ArrayList Collection Collections Random UUID]))

(defn uuid
  "Generate a random UUID from a Random number generator"
  [^Random r]
  (UUID. (.nextLong r) (.nextLong r)))

(defn shuffle
  "Shuffle a collection with a Random number generator"
  [^Random r ^Collection coll]
  (let [al (ArrayList. coll)]
    (Collections/shuffle al r)
    (clojure.lang.RT/vector (.toArray al))))
