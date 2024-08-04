# Gleam Digimon Card Game (2020) Deck Codec

This is a [Gleam](https://gleam.run) implementation of the [Digimon Card Game (2020)](https://world.digimoncard.com/) deck codec. The original [reference implementation is in Clojure](/codec/clojure).

## Usage

```sh
gleam add dcg_codec@1
```

### Decode

```gleam
import dcg_codec/codec

codec.decode("DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp")

// {"name":"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)","digi-eggs":[{"number":"BT2-001","count":4},{"number":"ST1-01","count":1}],"deck":[{"number":"BT1-005","count":1},{"number":"BT1-011","count":4},{"number":"BT1-012","count":2},{"number":"BT1-045","count":2,"parallel-id":1},{"number":"BT2-008","count":4},{"number":"BT3-004","count":4},{"number":"BT3-007","count":4},{"number":"BT3-010","count":3},{"number":"BT3-012","count":2},{"number":"BT3-013","count":4},{"number":"BT3-040","count":3},{"number":"ST1-02","count":4},{"number":"ST1-03","count":4},{"number":"ST1-06","count":3},{"number":"ST1-07","count":1},{"number":"ST1-07","count":3,"parallel-id":1},{"number":"ST1-12","count":2}]}
```

### Encode

```gleam
import dcg_codec/codec/common
import dcg_codec/codec/common/card.{Card}
import dcg_codec/codec/common/deck.{Deck}
import dcg_codec/codec/common/language
import dcg_codec/codec/encode
import gleam/option

encode.encode(
  Deck(
    digi_eggs: [
      Card(
        number: "BT2-001",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "ST1-01",
        parallel_id: option.None,
        count: 1,
      ),
    ],
    deck: [
      Card(
        number: "BT1-009",
        parallel_id: option.None,
        count: 1,
      ),
      Card(
        number: "BT1-019",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "BT1-020",
        parallel_id: option.None,
        count: 2,
      ),
      Card(
        number: "BT1-085",
        parallel_id: option.Some(1),
        count: 2,
      ),
      Card(
        number: "BT2-016",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "BT3-008",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "BT3-013",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "BT3-016",
        parallel_id: option.None,
        count: 3,
      ),
      Card(
        number: "BT3-018",
        parallel_id: option.None,
        count: 2,
      ),
      Card(
        number: "BT3-019",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "BT3-072",
        parallel_id: option.None,
        count: 3,
      ),
      Card(
        number: "ST1-02",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "ST1-03",
        parallel_id: option.None,
        count: 4,
      ),
      Card(
        number: "ST1-06",
        parallel_id: option.None,
        count: 3,
      ),
      Card(
        number: "ST1-07",
        parallel_id: option.None,
        count: 1,
      ),
      Card(
        number: "ST1-07",
        parallel_id: option.Some(1),
        count: 3,
      ),
      Card(
        number: "ST1-16",
        parallel_id: option.None,
        count: 2,
      ),
    ],
    sideboard: option.None,
    icon: option.None,
    language: option.Some(language.English),
    name: "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)",
  ), common.version
)

// "DCGUopzAIudAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhgMIAwUCAwECAwECFQOcnQFGAwIDAQIDAAECIAEJRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
```

## License

Copyright © 2024 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
