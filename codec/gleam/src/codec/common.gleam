import gleam/bit_array
import gleam/dict
import gleam/int
import gleam/list

// Version of the codec
pub const version: Int = 5

// Deck codes are all prefixed with "DCG"
pub const prefix: String = "DCG"

fn bit_array_sum(bits: BitArray, output: Int) -> Int {
  case bits {
    <<>> -> int.bitwise_and(output, 0xFF)
    <<b:8, rest:bits>> -> bit_array_sum(rest, b + output)
    _ -> panic as "Not a byte"
  }
}

pub fn compute_checksum(deck_bytes: BitArray, total_card_bytes: Int) -> Int {
  let assert Ok(bits) =
    bit_array.slice(from: deck_bytes, at: 0, take: total_card_bytes)
  bit_array_sum(bits, 0)
}

pub fn from_base36(char: String) -> Int {
  let assert Ok(i) =
    list.range(0, 35)
    |> list.fold([], fn(accl, i) { list.append(accl, [#(int.to_base36(i), i)]) })
    |> dict.from_list
    |> dict.get(char)
  i
}
