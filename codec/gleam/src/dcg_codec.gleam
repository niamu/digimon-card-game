import argv
import codec/decode
import common
import common/deck
import gleam/io

pub fn decode(code: String) -> String {
  let deck = decode.decode(code)
  deck
  |> deck.serialize()
}

pub fn encode(deck, version: Int) -> String {
  case version <= common.version {
    True -> Nil
    False -> panic as "Version not supported"
  }
  deck
}

pub fn main() {
  let result = case argv.load().arguments {
    ["--decode", code] -> decode(code)
    ["--encode", deck] -> encode(deck, 60)
    _ -> "Must use either `--decode` or `--encode`"
  }
  io.println(result)
}
