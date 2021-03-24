(ns dcg.codec.common)

(def version 0)
(def prefix "DCG")
(def header-size
  "version & digi-eggs deck count, checksum, and deck name length"
  3)
