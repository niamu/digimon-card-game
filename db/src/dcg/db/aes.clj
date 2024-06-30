(ns dcg.db.aes
  (:import
   [javax.crypto Cipher]
   [javax.crypto.spec IvParameterSpec SecretKeySpec]
   [java.util HexFormat]))

(defn decrypt
  [in key iv]
  (let [cipher (Cipher/getInstance "AES/CBC/NoPadding")]
    (do (.init cipher
               Cipher/DECRYPT_MODE
               (new SecretKeySpec (.parseHex (HexFormat/of) key) "AES")
               (new IvParameterSpec (.parseHex (HexFormat/of) iv)))
        (.formatHex (HexFormat/of)
                    (.doFinal cipher (.parseHex (HexFormat/of) in))))))
