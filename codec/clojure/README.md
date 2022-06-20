# Clojure Digimon Card Game (2020) Deck Codec

The encoder enforces some restrictions:

- 0-7 `:deck/digi-eggs` card groups
- 0-127 `:deck/sideboard` card groups
- 1-256 `:card/count` for each unique `:card/number`
- 0-7 `:card/parallel-id` (this id is the canonical parallel number in the image filename on digimoncard.com)
- `:card/number`'s are expected to have a maximum of 4 alphanumeric characters followed by a 2-3 digits separated by a hypen (if this pattern changes in the future a new deck codec version will be needed)
- 63 bytes maximum `:deck/name`
- `:deck/language` must be one of `"ja"`, `"en"`, `"zh"`, or `"ko"` (defaults to `"en"` if not provided)

## Usage

Using the compiled binary for macOS or Linux:

```clojure
user> (require '[dcg.codec.decode :refer [decode]])
nil
user> (require '[dcg.codec.encode :refer [encode]])
nil
user> (decode "DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp")
#:deck{:digi-eggs
       [#:card{:number "BT2-001", :count 4}
        #:card{:number "ST1-01", :count 1}],
       :deck
       [#:card{:number "BT1-009", :count 1}
        #:card{:number "BT1-019", :count 4}
        #:card{:number "BT1-020", :count 2}
        #:card{:number "BT1-085", :count 2, :parallel-id 1}
        #:card{:number "BT2-016", :count 4}
        #:card{:number "BT3-008", :count 4}
        #:card{:number "BT3-013", :count 4}
        #:card{:number "BT3-016", :count 3}
        #:card{:number "BT3-018", :count 2}
        #:card{:number "BT3-019", :count 4}
        #:card{:number "BT3-072", :count 3}
        #:card{:number "ST1-02", :count 4}
        #:card{:number "ST1-03", :count 4}
        #:card{:number "ST1-06", :count 3}
        #:card{:number "ST1-07", :count 1}
        #:card{:number "ST1-07", :count 3, :parallel-id 1}
        #:card{:number "ST1-16", :count 2}],
       :name "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"}
user> (encode *1)
"DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
```

## Deck Code Examples

**Starter Deck, Gaia Red [ST-1]**

`DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ`

**Starter Deck, Cocytus Blue [ST-2]**

`DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0`

**Starter Deck, Heaven's Yellow [ST-3]**

`DCGARUkU1QzIEHBU1QzIE_CwcFBwcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIEhlYXZlbidzIFllbGxvdyBbU1QtM10`

**Starter Deck, Giga Green [ST-4]**

`DCGARcfU1Q0IEHBU1Q0IE_CwcHBwcFBwcFBQUHBwUFTdGFydGVyIERlY2ssIEdpZ2EgR3JlZW4gW1NULTRd`

**Starter Deck, Machine Black [ST-5]**

`DCGARkiU1Q1IEHBU1Q1IE_CwcHBwcFBwcFBQUHBwUFTdGFydGVyIERlY2ssIE1hY2hpbmUgQmxhY2sgW1NULTVd`

**Starter Deck, Venomous Violet [ST-6]**

`DCGARskU1Q2IEHBU1Q2IE_CwcHBwcFBwcFBQUHBwUFTdGFydGVyIERlY2ssIFZlbm9tb3VzIFZpb2xldCBbU1QtNl0`


## License

Copyright © 2021 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
