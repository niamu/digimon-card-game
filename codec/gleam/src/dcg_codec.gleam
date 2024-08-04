import argv
import codec/common
import codec/common/deck

import codec/decode
import codec/encode
import gleam/io

pub fn decode(code: String) -> String {
  let deck = decode.decode(code)
  deck
  |> deck.serialize()
}

pub fn encode(json_string: String, version: Int) -> String {
  let deck = deck.deserialize(json_string)
  case version <= common.version {
    True -> Nil
    False -> panic as "Version not supported"
  }
  encode.encode(deck, common.version)
}

pub fn main() {
  let result = case argv.load().arguments {
    ["--decode", code] -> decode(code)
    ["--encode", deck] -> encode(deck, common.version)
    _ -> "Must use either `--decode` or `--encode`"
  }
  io.println(result)
}
