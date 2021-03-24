(ns dcg.cli
  #?(:clj (:gen-class))
  (:require
   [clojure.string :as string]
   [clojure.tools.cli :as cli]
   [dcg.codec.decode :as decode]
   [dcg.codec.encode :as encode]))

(set! *warn-on-reflection* true)

(def cli-options
  [[nil "--encode" "Encode a deck"]
   [nil "--decode" "Decode a deck"]
   ["-h" "--help"]])

(defn usage
  [options-summary]
  (->> ["dcg encodes and decodes decks for the Digimon Card Game (2020)"
        ""
        "Usage: dcg [options] [input]"
        ""
        "Options:"
        options-summary
        ""]
       (string/join \newline)))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options)
        [arguments] arguments]
    (cond
      (:help options) (println (usage summary))
      (:decode options) (decode/-main arguments)
      (:encode options) (encode/-main arguments)
      :else (println (usage summary)))))
