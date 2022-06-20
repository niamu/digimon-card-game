# PHP Digimon Card Game (2020) Deck Codec

This is a PHP implementation of the [Digimon Card Game (2020)](https://world.digimoncard.com/) deck codec. The original [reference implementation is in Clojure](https://github.com/niamu/digimon-card-game/tree/master/codec/clojure).

## Usage

### Encode

```PHP
<?php

require "src/dcg/codec/encode.php";

DCGDeckEncoder::Encode(
    array
    (
        "digi-eggs" => array
            (
                array
                    (
                        "number" => "ST8-01",
                        "parallel-id" => 0,
                        "count" => 4
                    )

            ),
        "deck" => array
            (
                array
                    (
                        "number" => "BT1-028",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "BT1-037",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "BT1-038",
                        "parallel-id" => 0,
                        "count" => 2
                    ),
                array
                    (
                        "number" => "ST2-13",
                        "parallel-id" => 0,
                        "count" => 2
                    ),
                array
                    (
                        "number" => "ST8-02",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-03",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-04",
                        "parallel-id" => 0,
                        "count" => 2
                    ),
                array
                    (
                        "number" => "ST8-05",
                        "parallel-id" => 0,
                        "count" => 2
                    ),
                array
                    (
                        "number" => "ST8-06",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-07",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-08",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-09",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-10",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-11",
                        "parallel-id" => 0,
                        "count" => 4
                    ),
                array
                    (
                        "number" => "ST8-12",
                        "parallel-id" => 0,
                        "count" => 4
                    )

            ),
        "name" => "Starter Deck, UlforceVeedramon [ST-8]"
    )
);

?>
```

**Returns**

```
DCGAdYlU1Q4IEHBQlQxIIPEB8UCQVNUMiBBRQNTVDggS8LBQUHBwcHBQcHBU3RhcnRlciBEZWNrLCBVbGZvcmNlVmVlZHJhbW9uIFtTVC04XQ
```


### Decode

```PHP
<?php

require "src/dcg/codec/decode.php";

DCGDeckDecoder::Decode("DCGAdYlU1Q4IEHBQlQxIIPEB8UCQVNUMiBBRQNTVDggS8LBQUHBwcHBQcHBU3RhcnRlciBEZWNrLCBVbGZvcmNlVmVlZHJhbW9uIFtTVC04XQ");

?>
```

**Returns**

```
Array
(
    [digi-eggs] => Array
        (
            [0] => Array
                (
                    [number] => ST8-01
                    [parallel-id] => 0
                    [count] => 4
                )

        )

    [deck] => Array
        (
            [0] => Array
                (
                    [number] => BT1-028
                    [parallel-id] => 0
                    [count] => 4
                )

            [1] => Array
                (
                    [number] => BT1-037
                    [parallel-id] => 0
                    [count] => 4
                )

            [2] => Array
                (
                    [number] => BT1-038
                    [parallel-id] => 0
                    [count] => 2
                )

            [3] => Array
                (
                    [number] => ST2-13
                    [parallel-id] => 0
                    [count] => 2
                )

            [4] => Array
                (
                    [number] => ST8-02
                    [parallel-id] => 0
                    [count] => 4
                )

            [5] => Array
                (
                    [number] => ST8-03
                    [parallel-id] => 0
                    [count] => 4
                )

            [6] => Array
                (
                    [number] => ST8-04
                    [parallel-id] => 0
                    [count] => 2
                )

            [7] => Array
                (
                    [number] => ST8-05
                    [parallel-id] => 0
                    [count] => 2
                )

            [8] => Array
                (
                    [number] => ST8-06
                    [parallel-id] => 0
                    [count] => 4
                )

            [9] => Array
                (
                    [number] => ST8-07
                    [parallel-id] => 0
                    [count] => 4
                )

            [10] => Array
                (
                    [number] => ST8-08
                    [parallel-id] => 0
                    [count] => 4
                )

            [11] => Array
                (
                    [number] => ST8-09
                    [parallel-id] => 0
                    [count] => 4
                )

            [12] => Array
                (
                    [number] => ST8-10
                    [parallel-id] => 0
                    [count] => 2
                )

            [13] => Array
                (
                    [number] => ST8-11
                    [parallel-id] => 0
                    [count] => 4
                )

            [14] => Array
                (
                    [number] => ST8-12
                    [parallel-id] => 0
                    [count] => 4
                )

        )

    [name] => Starter Deck, UlforceVeedramon [ST-8]
)
```

## License

Copyright © 2021 Brendon Walsh.

Licensed under the BSD 3-Clause License (see the file LICENSE).
