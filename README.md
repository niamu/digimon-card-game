# Digimon Card Game (2020) Deck Codec

This repository will assist in the encoding and decoding of [Digimon Card Game (2020)](https://world.digimoncard.com) deck codes. With a deck codec the community can share decks between platforms and services that implement the codec making decks portable and shareable.

The encoder enforces some restrictions that also match the current game rules of what a deck is allowed to contain:

- 0-5 `:deck/digi-eggs` (after summing the `:card/count` values of each card)
- 50 total `:deck/deck` cards (after summing the `:card/count` values of each card)
- 1-4 `:card/count` for each unique `:card/id`
- 0-4 `:card/alternates` for each unique `:card/id`
- `:card/id`'s are expected to have 3 alphanumeric characters followed by a 2-3 digit number separated by a hypen (if this pattern changes in the future a new deck codec version will be needed)
- 63 character maximum `:deck/name`


## Usage

Using the compiled binary for macOS or Linux:

```
$ dcg --help

dcg encodes and decodes decks for the Digimon Card Game (2020)

Usage: dcg [options] [input]

Options:
      --encode  Encode a deck
      --decode  Decode a deck
  -h, --help
```


### Encode

```
$ echo  "{:deck/digi-eggs [{:card/id \"ST3-01\", :card/count 4}], \
          :deck/deck  [{:card/id \"ST3-02\", :card/count 4} \
                       {:card/id \"ST3-03\", :card/count 4} \
                       {:card/id \"ST3-04\", :card/count 4} \
                       {:card/id \"ST3-05\", :card/count 2} \
                       {:card/id \"ST3-06\", :card/count 4} \
                       {:card/id \"ST3-07\", :card/count 4} \
                       {:card/id \"ST3-08\", :card/count 4} \
                       {:card/id \"ST3-09\", :card/count 4} \
                       {:card/id \"ST3-10\", :card/count 2} \
                       {:card/id \"ST3-11\", :card/count 2} \
                       {:card/id \"ST3-12\", :card/count 4} \
                       {:card/id \"ST3-13\", :card/count 4} \
                       {:card/id \"ST3-14\", :card/count 2} \
                       {:card/id \"ST3-15\", :card/count 4} \
                       {:card/id \"ST3-16\", :card/count 2}], \
          :deck/name \"Starter Deck, Heaven's Yellow [ST-3]\"}" | dcg --encode

DCGAdUkU1QzQcFTVDNPwsHBQcHBwcFBQcHBQcFBU3RhcnRlciBEZWNrLCBIZWF2ZW4ncyBZZWxsb3cgW1NULTNd
```


### Decode

```
$ dcg --decode DCGAdUkU1QzQcFTVDNPwsHBQcHBwcFBQcHBQcFBU3RhcnRlciBEZWNrLCBIZWF2ZW4ncyBZZWxsb3cgW1NULTNd

{:deck/digi-eggs [{:card/id "ST3-01", :card/count 4}],
 :deck/deck  [{:card/id "ST3-02", :card/count 4}
              {:card/id "ST3-03", :card/count 4}
              {:card/id "ST3-04", :card/count 4}
              {:card/id "ST3-05", :card/count 2}
              {:card/id "ST3-06", :card/count 4}
              {:card/id "ST3-07", :card/count 4}
              {:card/id "ST3-08", :card/count 4}
              {:card/id "ST3-09", :card/count 4}
              {:card/id "ST3-10", :card/count 2}
              {:card/id "ST3-11", :card/count 2}
              {:card/id "ST3-12", :card/count 4}
              {:card/id "ST3-13", :card/count 4}
              {:card/id "ST3-14", :card/count 2}
              {:card/id "ST3-15", :card/count 4}
              {:card/id "ST3-16", :card/count 2}],
 :deck/name "Starter Deck, Heaven's Yellow [ST-3]"}
```


## Deck Code Examples

**Starter Deck, Gaia Red [ST-1]**
`DCGAdEdU1QxQcFTVDFPwsHBwcFBwcFBQcHBwUFBU3RhcnRlciBEZWNrLCBHYWlhIFJlZCBbU1QtMV0_`

**Starter Deck, Cocytus Blue [ST-2]**
`DCGAdMhU1QyQcFTVDJPwsHBwUHBwcFBQcHBQcFBU3RhcnRlciBEZWNrLCBDb2N5dHVzIEJsdWUgW1NULTJd`

**Starter Deck, Heaven's Yellow [ST-3]**
`DCGAdUkU1QzQcFTVDNPwsHBQcHBwcFBQcHBQcFBU3RhcnRlciBEZWNrLCBIZWF2ZW4ncyBZZWxsb3cgW1NULTNd`

**Starter Deck, Giga Green [ST-4]**
`DCGAdcfU1Q0QcFTVDRPwsHBwcHBQcHBQUFBwcFBU3RhcnRlciBEZWNrLCBHaWdhIEdyZWVuIFtTVC00XQ__`

**Starter Deck, Machine Black [ST-5]**
`DCGAdkiU1Q1QcFTVDVPwsHBwcHBQcHBQUFBwcFBU3RhcnRlciBEZWNrLCBNYWNoaW5lIEJsYWNrIFtTVC01XQ__`

**Starter Deck, Venomous Violet [ST-6]**
`DCGAdskU1Q2QcFTVDZPwsHBwcHBQcHBQUFBwcFBU3RhcnRlciBEZWNrLCBWZW5vbW91cyBWaW9sZXQgW1NULTZd`


## License

Copyright © 2021 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
