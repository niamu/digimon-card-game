# Tools for the Digimon Card Game (2020)

[![Heroicc](https://assets.heroi.cc/images/metatag.png)](https://heroi.cc)

This repository hosts tools for the [Digimon Card Game (2020)](https://world.digimoncard.com) and is the foundations for [Heroicc](https://heroi.cc).

## Codec

The [codec](/codec) directory hosts multiple library implementations for encoding and decoding of [Digimon Card Game (2020)](https://world.digimoncard.com) decks. With a deck codec the community can share decks between platforms and services that implement the codec making decks portable and shareable.

## Database

[db](/db) contains code that scrapes official Bandai sources for the Digimon Card Game (2020) and produces a normalized and enriched [Datomic](https://www.datomic.com) database of card data in all officially supported languages where cards are released.

## API

[api](/api) is a [JSON:API](https://jsonapi.org) exposing data from the [database](/db).

## Card Scanner

[card-scanner](/card-scanner) is an in-browser tool that uses the webcam to match cards using a perceptual hash.

---

This work is licensed under a
[Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License][cc-by-nc-sa] unless otherwise stated.

[![CC BY-NC-SA 4.0][cc-by-nc-sa-image]][cc-by-nc-sa]

[cc-by-nc-sa]: http://creativecommons.org/licenses/by-nc-sa/4.0/
[cc-by-nc-sa-image]: https://licensebuttons.net/l/by-nc-sa/4.0/88x31.png
