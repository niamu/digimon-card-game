# Rust Digimon Card Game (2020) Deck Codec

This is a [Rust](https://www.rust-lang.org) implementation of the [Digimon Card Game (2020)](https://world.digimoncard.com/) deck codec. The original [reference implementation is in Clojure](/codec/clojure).

## Usage

Using the compiled binary:

```
$ dcg-codec --help
dcg-codec 0.5.0
Digimon Card Game 2020 deck codec

USAGE:
    dcg-codec [OPTIONS]

FLAGS:
    -h, --help       Prints help information
    -V, --version    Prints version information

OPTIONS:
        --encode <deck>
        --decode <deck-code-str>
```

### Decode

```
$ dcg-codec --decode DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp

{"digi-eggs":[{"number":"BT2-001","count":4},{"number":"ST1-01","count":1}],"deck":[{"number":"BT1-009","count":1},{"number":"BT1-019","count":4},{"number":"BT1-020","count":2},{"number":"BT1-085","parallel-id":1,"count":2},{"number":"BT2-016","count":4},{"number":"BT3-008","count":4},{"number":"BT3-013","count":4},{"number":"BT3-016","count":3},{"number":"BT3-018","count":2},{"number":"BT3-019","count":4},{"number":"BT3-072","count":3},{"number":"ST1-02","count":4},{"number":"ST1-03","count":4},{"number":"ST1-06","count":3},{"number":"ST1-07","count":1},{"number":"ST1-07","parallel-id":1,"count":3},{"number":"ST1-16","count":2}],"name":"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"}
```

### Encode

```
$ dcg-codec --encode '{"digi-eggs":[{"number":"BT2-001","count":4},{"number":"ST1-01","count":1}],"deck":[{"number":"BT1-009","count":1},{"number":"BT1-019","count":4},{"number":"BT1-020","count":2},{"number":"BT1-085","parallel-id":1,"count":2},{"number":"BT2-016","count":4},{"number":"BT3-008","count":4},{"number":"BT3-013","count":4},{"number":"BT3-016","count":3},{"number":"BT3-018","count":2},{"number":"BT3-019","count":4},{"number":"BT3-072","count":3},{"number":"ST1-02","count":4},{"number":"ST1-03","count":4},{"number":"ST1-06","count":3},{"number":"ST1-07","count":1},{"number":"ST1-07","parallel-id":1,"count":3},{"number":"ST1-16","count":2}],"name":"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"}'

"DCGUopzAIudAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhgMIAwUCAwECAwECFQOcnQFGAwIDAQIDAAECIAEJRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
```

## License

Copyright © 2021 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
