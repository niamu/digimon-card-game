import gleam/bit_array
import gleam/int

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
