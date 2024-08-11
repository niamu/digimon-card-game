# Elixir Digimon Card Game (2020) Deck Codec

This is a [Elixir](https://elixir-lang.org) implementation of the [Digimon Card Game (2020)](https://world.digimoncard.com/) deck codec. The original [reference implementation is in Clojure](/codec/clojure).

## Usage

If [available in Hex](https://hex.pm/docs/publish), the package can be installed
by adding `dcg_codec` to your list of dependencies in `mix.exs`:

```elixir
def deps do
  [
    {:dcg_codec, "~> 0.1.0"}
  ]
end
```

### Decode

```elixir
iex(1)> DcgCodec.decode("DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp")

%{
  "deck" => [
    %{"count" => 1, "number" => "BT1-009"},
    %{"count" => 4, "number" => "BT1-019"},
    %{"count" => 2, "number" => "BT1-020"},
    %{"count" => 2, "number" => "BT1-085", "parallel-id" => 1},
    %{"count" => 4, "number" => "BT2-016"},
    %{"count" => 4, "number" => "BT3-008"},
    %{"count" => 4, "number" => "BT3-013"},
    %{"count" => 3, "number" => "BT3-016"},
    %{"count" => 2, "number" => "BT3-018"},
    %{"count" => 4, "number" => "BT3-019"},
    %{"count" => 3, "number" => "BT3-072"},
    %{"count" => 4, "number" => "ST1-02"},
    %{"count" => 4, "number" => "ST1-03"},
    %{"count" => 3, "number" => "ST1-06"},
    %{"count" => 1, "number" => "ST1-07"},
    %{"count" => 3, "number" => "ST1-07", "parallel-id" => 1},
    %{"count" => 2, "number" => "ST1-16"}
  ],
  "digi-eggs" => [
    %{"count" => 4, "number" => "BT2-001"},
    %{"count" => 1, "number" => "ST1-01"}
  ],
  "name" => "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"
}
```

### Encode

```elixir
iex(3)> DcgCodec.encode(
  %{
    "deck" => [
      %{"count" => 1, "number" => "BT1-009"},
      %{"count" => 4, "number" => "BT1-019"},
      %{"count" => 2, "number" => "BT1-020"},
      %{"count" => 2, "number" => "BT1-085", "parallel-id" => 1},
      %{"count" => 4, "number" => "BT2-016"},
      %{"count" => 4, "number" => "BT3-008"},
      %{"count" => 4, "number" => "BT3-013"},
      %{"count" => 3, "number" => "BT3-016"},
      %{"count" => 2, "number" => "BT3-018"},
      %{"count" => 4, "number" => "BT3-019"},
      %{"count" => 3, "number" => "BT3-072"},
      %{"count" => 4, "number" => "ST1-02"},
      %{"count" => 4, "number" => "ST1-03"},
      %{"count" => 3, "number" => "ST1-06"},
      %{"count" => 1, "number" => "ST1-07"},
      %{"count" => 3, "number" => "ST1-07", "parallel-id" => 1},
      %{"count" => 2, "number" => "ST1-16"}
    ],
    "digi-eggs" => [
      %{"count" => 4, "number" => "BT2-001"},
      %{"count" => 1, "number" => "ST1-01"}
    ],
    "name" => "Digi Bros: Ragnaloardmon Red (youtu.be/o0KoW2wwhR4)"
  }
)

"DCGUopzAIudAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhgMIAwUCAwECAwECFQOcnQFGAwIDAQIDAAECIAEJRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
```

## License

Copyright © 2024 Brendon Walsh.

Licensed under the EPL (see the file LICENSE).
