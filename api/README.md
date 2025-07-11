# Digimon Card Game (2020) API

[JSON:API](https://jsonapi.org) exposing data from the [database](/db).

## Usage

**NOTE**: Ensure you've already exported "cards.transit.json" (using `clojure -X:export` from the [database](/db)) and placed it in the resources directory.

### Bulk Data Export

The API supports bulk data file endpoints to download from, but these files must be generated before starting the web server.

```
$ API_NAME="Digimon Card Game (2020)" \
  SITE_ORIGIN="https://example.com" \
  API_ORIGIN="https://api.example.com" \
  IMAGES_ORIGIN="https://images.example.com" \
  ASSETS_ORIGIN="https://assets.example.com" \
    clojure -X:bulk-data-export!
Bulk data export complete
```

### Start the Server

```
$ API_NAME="Digimon Card Game (2020)" \
  SITE_ORIGIN="https://example.com" \
  API_ORIGIN="https://api.example.com" \
  IMAGES_ORIGIN="https://images.example.com" \
  ASSETS_ORIGIN="https://assets.example.com" \
    clojure -X:serve
API started on port 3000
```

## Build

```
$ clojure -T:build uber
```

The resulting uberjar is saved to the "target" directory.
