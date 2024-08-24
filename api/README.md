# Digimon Card Game (2020) API

[JSON:API](https://jsonapi.org) exposing data from the [database](/db).

## Usage

The API depends on the Database, so when building the container, both projects need to be copied into the container.

Build the container image from the root of this project using the following command:

**NOTE**: Ensure you've already generated a db.edn file in an accessible resources directory on the classpath first.

```
$ docker build -t dcg-api -f api/Dockerfile .
```

Then run the container:

```
$ docker run --rm -it -p 3000:3000 dcg-api
```

Query the API:

```
$ curl http://127.0.0.1:3000
```

```json
{
  "data": [
    {
      "id": "en",
      "type": "language",
      "attributes": {
        "tag": "en",
        "name": "English"
      },
      "meta": {
        "latest-release": "Sun Aug 18 00:00:00 EDT 2024"
      },
      "links": {
        "related": "http://127.0.0.1:3000/en"
      }
    },
    {
      "id": "ja",
      "type": "language",
      "attributes": {
        "tag": "ja",
        "name": "Japanese"
      },
      "meta": {
        "latest-release": "Sat Aug 10 20:00:00 EDT 2024"
      },
      "links": {
        "related": "http://127.0.0.1:3000/ja"
      }
    },
    {
      "id": "zh-Hans",
      "type": "language",
      "attributes": {
        "tag": "zh-Hans",
        "name": "Chinese"
      },
      "meta": {
        "latest-release": "Sun Aug 04 00:00:00 EDT 2024"
      },
      "links": {
        "related": "http://127.0.0.1:3000/zh-Hans"
      }
    },
    {
      "id": "ko",
      "type": "language",
      "attributes": {
        "tag": "ko",
        "name": "Korean"
      },
      "meta": {
        "latest-release": "Thu Jul 18 20:00:00 EDT 2024"
      },
      "links": {
        "related": "http://127.0.0.1:3000/ko"
      }
    }
  ],
  "links": {
    "self": "http://127.0.0.1:3000/"
  }
}
```

