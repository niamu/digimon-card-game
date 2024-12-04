# Ruby Digimon Card Game (2020) Deck Codec

This is a [Ruby](https://ruby-lang.org/) implementation of the [Digimon Card Game (2020)](https://world.digimoncard.com/) deck codec. The original [reference implementation is in Clojure](/codec/clojure).

## Usage

Using `lib/dcg_codec.rb`:

```
$ ruby lib/dcg_codec.rb --help
Usage: dcg_codec.rb [options]

        --encode DECK
        --decode DECK_CODE

    -h, --help                       Show this message
    -v, --version
```

### Decode

```
irb> require 'dcg_codec'
irb> DCGCodec::Decoder.new("DCGUopzAIudAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhgMIAwUCAwECAwECFQOcnQFGAwIDAQIDAAECIAEJRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp").decode
=>
{"digi-eggs"=>[{"number"=>"BT2-001", "count"=>4}, {"number"=>"ST1-01", "count"=>1}],
 "deck"=>
  [{"number"=>"BT1-009", "count"=>1},
   {"number"=>"BT1-019", "count"=>4},
   {"number"=>"BT1-020", "count"=>2},
   {"number"=>"BT1-085", "count"=>2, "parallel-id"=>1},
   {"number"=>"BT2-016", "count"=>4},
   {"number"=>"BT3-008", "count"=>4},
   {"number"=>"BT3-013", "count"=>4},
   {"number"=>"BT3-016", "count"=>3},
   {"number"=>"BT3-018", "count"=>2},
   {"number"=>"BT3-019", "count"=>4},
   {"number"=>"BT3-072", "count"=>3},
   {"number"=>"ST1-02", "count"=>4},
   {"number"=>"ST1-03", "count"=>4},
   {"number"=>"ST1-06", "count"=>3},
   {"number"=>"ST1-07", "count"=>1},
   {"number"=>"ST1-07", "count"=>3, "parallel-id"=>1},
   {"number"=>"ST1-16", "count"=>2}],
 "name"=>"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)",
 "language"=>"en"}
```

### Encode

```
irb> require 'dcg_codec'
irb> DCGCodec::Encoder.new(
       {"digi-eggs"=>[{"number"=>"BT2-001", "count"=>4}, {"number"=>"ST1-01", "count"=>1}],
        "deck"=>
         [{"number"=>"BT1-009", "count"=>1},
          {"number"=>"BT1-019", "count"=>4},
          {"number"=>"BT1-020", "count"=>2},
          {"number"=>"BT1-085", "count"=>2, "parallel-id"=>1},
          {"number"=>"BT2-016", "count"=>4},
          {"number"=>"BT3-008", "count"=>4},
          {"number"=>"BT3-013", "count"=>4},
          {"number"=>"BT3-016", "count"=>3},
          {"number"=>"BT3-018", "count"=>2},
          {"number"=>"BT3-019", "count"=>4},
          {"number"=>"BT3-072", "count"=>3},
          {"number"=>"ST1-02", "count"=>4},
          {"number"=>"ST1-03", "count"=>4},
          {"number"=>"ST1-06", "count"=>3},
          {"number"=>"ST1-07", "count"=>1},
          {"number"=>"ST1-07", "count"=>3, "parallel-id"=>1},
          {"number"=>"ST1-16", "count"=>2}],
        "name"=>"Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)",
        "language"=>"en"}
     ).encode
=> "DCGUopzAIudAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhgMIAwUCAwECAwECFQOcnQFGAwIDAQIDAAECIAEJRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
```

## License

Copyright © 2024 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
