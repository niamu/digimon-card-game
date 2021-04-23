# Digimon Card Game (2020) Deck Codec

This repository will assist in the encoding and decoding of [Digimon Card Game (2020)](https://world.digimoncard.com) deck codes. With a deck codec the community can share decks between platforms and services that implement the codec making decks portable and shareable.

The encoder enforces some restrictions that also match the current game rules of what a deck is allowed to contain:

- 0-5 `:deck/digi-eggs` (after summing the `:card/count` values of each card)
- 50 total `:deck/deck` cards (after summing the `:card/count` values of each card)
- 1-4 `:card/count` for each unique `:card/number`
- 0-7 `:card/parallel-id` (this id is the canonical parallel number in the image filename on digimoncard.com)
- `:card/number`'s are expected to have a maximum of 4 alphanumeric characters followed by a 2-3 digit number separated by a hypen (if this pattern changes in the future a new deck codec version will be needed)
- 63 bytes maximum `:deck/name`

## Demo

A simple website to demonstrate this codec and the `encode`/`decode` functions is published at [niamu.github.io/digimon-card-game](https://niamu.github.io/digimon-card-game/).

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
$ echo  "{:deck/digi-eggs [{:card/number \"BT2-001\" :card/count 4}
                  {:card/number \"ST1-01\" :card/count 1}],
 :deck/deck  [{:card/number \"BT3-019\" :card/count 4}
              {:card/number \"BT3-016\" :card/count 3}
              {:card/number \"BT3-072\" :card/count 3}
              {:card/number \"BT3-018\" :card/count 2}
              {:card/number \"BT3-013\" :card/count 4}
              {:card/number \"BT2-016\" :card/count 4}
              {:card/number \"BT1-020\" :card/count 2}
              {:card/number \"ST1-07\" :card/count 1}
              {:card/number \"ST1-07\" :card/parallel-id 1 :card/count 3}
              {:card/number \"ST1-06\" :card/count 3}
              {:card/number \"BT1-019\" :card/count 4}
              {:card/number \"BT3-008\" :card/count 4}
              {:card/number \"ST1-03\" :card/count 4}
              {:card/number \"ST1-02\" :card/count 4}
              {:card/number \"BT1-009\" :card/count 1}
              {:card/number \"BT1-085\" :card/parallel-id 1 :card/count 2}
              {:card/number \"ST1-16\" :card/count 2}],
 :deck/name \"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)\"}" | dcg --encode

DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp
```


### Decode

```
$ dcg --decode DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp

{:deck/digi-eggs [{:card/number "BT2-001", :card/count 4}
                  {:card/number "ST1-01", :card/count 1}],
 :deck/deck [{:card/number "BT1-009", :card/count 1}
             {:card/number "BT1-019", :card/count 4}
             {:card/number "BT1-020", :card/count 2}
             {:card/number "BT1-085", :card/count 2, :card/parallel-id 1}
             {:card/number "BT2-016", :card/count 4}
             {:card/number "BT3-008", :card/count 4}
             {:card/number "BT3-013", :card/count 4}
             {:card/number "BT3-016", :card/count 3}
             {:card/number "BT3-018", :card/count 2}
             {:card/number "BT3-019", :card/count 4}
             {:card/number "BT3-072", :card/count 3}
             {:card/number "ST1-02", :card/count 4}
             {:card/number "ST1-03", :card/count 4}
             {:card/number "ST1-06", :card/count 3}
             {:card/number "ST1-07", :card/count 1}
             {:card/number "ST1-07", :card/count 3, :card/parallel-id 1}
             {:card/number "ST1-16", :card/count 2}],
 :deck/name "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"}
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


## Encoded Binary Structure

The encoded binary structure of **Starter Deck, Gaia Red [ST-1]** is used here as an example. Both the `:deck/digi-eggs` and `:deck/deck` collections are sorted by `:card/number` before encoding begins.

The codec produces and consumes Base64URL strings as defined in [rfc4648&#167;5](https://tools.ietf.org/html/rfc4648#section-5) and does not implement padding characters.

```
{:deck/digi-eggs [{:card/number "ST1-01", :card/count 4}],
 :deck/deck [{:card/number "ST1-02", :card/count 4}
             {:card/number "ST1-03", :card/count 4}
             {:card/number "ST1-04", :card/count 4}
             {:card/number "ST1-05", :card/count 4}
             {:card/number "ST1-06", :card/count 4}
             {:card/number "ST1-07", :card/count 2}
             {:card/number "ST1-08", :card/count 4}
             {:card/number "ST1-09", :card/count 4}
             {:card/number "ST1-10", :card/count 2}
             {:card/number "ST1-11", :card/count 2}
             {:card/number "ST1-12", :card/count 4}
             {:card/number "ST1-13", :card/count 4}
             {:card/number "ST1-14", :card/count 4}
             {:card/number "ST1-15", :card/count 2}
             {:card/number "ST1-16", :card/count 2}],
 :deck/name "Starter Deck, Gaia Red [ST-1]"}
 ```

### Header (First 3 bytes)

**1st byte**: `00000001`
- Version (4 bits) and Digi-Egg card set item count (4 bits)
  - Version: `0000`
  - Digi-Egg card set item count: `0001`

**2nd byte**: `00010001`
- Checksum
  - The checksum is truncated as the last byte of the sum of all cards
  - This can't be computed until after the deck contents have been encoded

**3rd byte**: `00011101`
- Deck name string byte length
  - in this case "Starter Deck, Gaia Red [ST-1]" is 29 characters/bytes long
  - **Note**: For UTF-8 characters that take up more than 1 byte (Japanese characters for example), it is important that character count and byte count are not conflated. Always truncate bytes and not characters.

### Decks

#### Card Set Header

**Next 3 bytes (4,5,6)**: `10011100 10011101 00000001`
- The beginning of the deck storage. If the Digi-Egg card set item count is 0 then this starts the main deck storage
- Base36 characters which is the card set name that the cards which follow belong to, in this case `"ST1"` that encodes to [28, 29, 1]
- 8th bit is continue bit. If bit is 0, the end of the string has been reached

**7th byte**: `01000001`
- Card number zero padding/leading zeroes (2 bits) stored as zero-based and the count of the card items in the card set (6 bits)
  - Card zero padding: `01`
  - Card set item count: `000001`

#### Cards Within Set

This is a loop that continues until all the cards within the card set have been written. After which either a new card set is started or the end of the deck has been encoded.

**8th byte**: `00000011`
- Card count (8 bits zero-based counting): `11`

**9th byte**: `00000001`
- Parallel ID (3 bits) for the card: `000`
  - If the card is the original and not an alternate art the value is zero. Otherwise the canonical number is the one used in the image filename. [BT5-086_P3.png](https://digimoncard.com/images/cardlist/card/BT5-086_P3.png) has a parallel ID of 3
- Card number offset (remaining 5 bits of the first byte): `00001`
  - The Digi-Egg card in this example belongs to the "ST1" card set and has the number "01"
  - An offset of the number is stored which is equal to the current card number being stored (1 in this example) subtracted by the previous card number stored in the card set (starting at 0) where this is the first card of the set
  -  If the number cannot be contained in the remaining 5 bits it is continue to the next byte using the first bit as a carry bit. If the carry bit is 0 that means the number is concluded in that byte. If the carry bit is 1 that means the number spans to the next byte using the remaining 7 bits for the number)

### Deck Name

Before the deck string is written, the checksum can now be calculated and the last byte of the checksum stored at the 2nd byte location.

After the deck contents have been stored the deck name (truncated to 63 bytes) is stored as UTF-8 bytes to complete the byte buffer.

Lastly, the entire byte buffer is converted to Base64 and prefixed with `DCG` at the beginning of the string to indicate it is a Digimon Card Game deck code.


## License

Copyright © 2021 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
