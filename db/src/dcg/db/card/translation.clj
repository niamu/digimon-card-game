(ns dcg.db.card.translation
  (:require
   [clojure.string :as string]))

(defn card-name-replacement-map
  [cards-per-origin]
  (->> cards-per-origin
       (group-by (comp :card/language first))
       (filter (fn [[_ group-count]]
                 (> (count group-count) 1)))
       vals
       (mapcat #(apply concat %))
       (filter (fn [{:card/keys [parallel-id]}]
                 (zero? parallel-id)))
       (reduce (fn [accl {:card/keys [number language name]}]
                 (update accl [language number] conj name))
               {})
       (filter (fn [[_ names]]
                 (and (> (count names) 1)
                      (apply (complement =)
                             (map string/lower-case names)))))
       (reduce (fn [accl [[language number] names]]
                 (if (apply = (map string/lower-case names))
                   accl
                   (merge accl
                          (apply hash-map names))))
               {})))
