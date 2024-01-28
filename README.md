# Digimon Card Game (2020)

This repository hosts multiple tools for the [Digimon Card Game (2020)](https://world.digimoncard.com).

## Codec

The [codec](/codec) directory hosts multiple library implementations for encoding and decoding of [Digimon Card Game (2020)](https://world.digimoncard.com) decks. With a deck codec the community can share decks between platforms and services that implement the codec making decks portable and shareable.

## Database

[db](/db) contains code that scrapes official Bandai sources for the Digimon Card Game (2020) and produces a normalized and enriched [Datomic](https://www.datomic.com) database of card data in all officially supported languages where cards are released.
