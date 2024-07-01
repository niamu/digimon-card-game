# Digimon Card Game (2020) Database

The database ingests data via web scraping the official Bandai sites for the [Digimon Card Game (2020)](https://world.digimoncard.com) in the following languages:

- [Japanese](https://digimoncard.com)
- [English](https://world.digimoncard.com)
- [Simplified Chinese](https://digimoncard.cn)
- [Korean](https://digimoncard.co.kr)

All card images are saved in `resources/images/cards/`.

## Sources of Error

There are numerous transcription errors in the official data and the ingestion process makes every attempt to identify them even if it can't automatically correct them. Any unresolved errors that remain prior to attempting to store data in the database will throw an error.

### Digivolution Requirements

At the time of writing there are 2 different card designs for digivolution requirements. The transcription of the colours of the requirements is often incomplete or missing so OpenCV is used to detect which colours are associated with each requirement. THis is done once for every Japanese card that is not an alternate art and that information is applied to the rest of the cards across languages with the assumption that every card number will first be made available in Japan and the Japanese site is generally more accurate than the rest.

Templates for OpenCV matching of digivolution conditions are stored in `resources/images/templates/digivolution-requirements/`.

### Block Icons

The block icons on each card are not transcribed anywhere on the site so OpenCV is also used to detect these on each card. This effort has proven to be especially tricky to automate with 100% accuracy so an assertion is made against a known good in `resources/block-icons.edn` which a human should verify when new alternate arts have been made.

Templates for OpenCV matching of block icons are stored in `resources/images/templates/block-icons/`. In an effort to increase accuracy sometimes multiple templates per block icon are made with an alphabetical suffix.

### Card Text

By far the most challenging errors to identify are transcription errors. Sometimes cards have slightly different textual explanations across alternate arts which this project tries to maintain. The detection of missing fields or partially missing text is done by comparing all transcriptions for each card number across every language against one another. Any discrepencies in numerical values, missing keywords/timings, etc. are flagged as an error which must be resolved.

Repair functions to apply to cards are stored in `resources/card-repairs.edn`.

## Usage

For convenience a Dockerfile has been provided to build and run the database ingestion. Ensure you have at least 12GB of RAM your container can access otherwise the OpenCV FLANN index for image search will throw an error and ingestion will fail.

First build the image:

```
docker build -t dcg-db .
```

Then run:

```
docker run \
	--rm -it \
	-w /db \
	-v $PWD/resources/db.edn:/db/resources/db.edn \
	-v $PWD/resources/images:/db/resources/images \
	dcg-db
```

This process will take a long time to download all of the card images on first run. Once an image is downloaded, it is not attempted to be downloaded again so subsequent runs are faster.

**Note**: The volume mount of the `db.edn` file so the saved database is available in your host directory afterwards along with all of the card `images`.
