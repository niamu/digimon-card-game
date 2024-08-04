import codec/common
import codec/common/card.{type Card, Card}
import codec/common/deck.{type Deck}
import codec/common/language
import gleam/bit_array
import gleam/dict
import gleam/int
import gleam/list
import gleam/option
import gleam/order
import gleam/pair
import gleam/string

fn bits_with_carry(value: Int, bits: Int) -> Int {
  let limit_bit = int.bitwise_shift_left(1, bits - 1)
  let result = int.bitwise_and(value, limit_bit - 1)
  let result = case value >= limit_bit {
    True -> int.bitwise_or(result, limit_bit)
    False -> result
  }
  result
}

fn append_rest_to_deck_bytes(
  deck_bytes: BitArray,
  value: Int,
  already_written_bits: Int,
) -> BitArray {
  let remaining_value = int.bitwise_shift_right(value, already_written_bits - 1)
  case remaining_value > 0 {
    True -> {
      let x = bits_with_carry(remaining_value, 8)
      let deck_bytes = bit_array.append(deck_bytes, <<x>>)
      append_rest_to_deck_bytes(deck_bytes, remaining_value, 8)
    }
    False -> deck_bytes
  }
}

fn group_cards(cards: List(Card)) -> List(#(#(String, Int), List(Card))) {
  list.group(cards, fn(card: Card) {
    let assert Ok(#(card_set, card_set_number)) =
      string.split_once(card.number, "-")
    #(card_set, string.length(card_set_number))
  })
  |> dict.map_values(fn(_, v) {
    list.sort(v, fn(a, b) {
      case string.compare(a.number, b.number) {
        order.Eq ->
          int.compare(
            option.unwrap(a.parallel_id, 0),
            option.unwrap(b.parallel_id, 0),
          )
        x -> x
      }
    })
  })
  |> dict.to_list
  |> list.sort(fn(a, b) {
    string.compare(a |> pair.first |> pair.first, b |> pair.first |> pair.first)
  })
}

pub fn encode(deck: Deck, version: Int) -> String {
  let language_number = language.to_int(deck.language)

  let version_and_digi_egg_count = case 3 <= version && version <= 4 {
    True ->
      int.bitwise_shift_left(version, 4)
      |> int.bitwise_or(int.bitwise_shift_left(language_number, 3))
      |> int.bitwise_or(int.bitwise_and(list.length(deck.digi_eggs), 0x0F))
    False ->
      int.bitwise_shift_left(version, 4)
      |> int.bitwise_or(int.bitwise_and(list.length(deck.digi_eggs), 0x0F))
  }

  let deck_name = case version >= 4 && option.is_some(deck.icon) {
    True ->
      option.unwrap(deck.icon, "")
      |> string.slice(0, 8)
      |> string.pad_right(to: 8, with: " ")
      <> deck.name
    False -> deck.name
  }
  let deck_name_bytes = bit_array.from_string(deck_name |> string.trim())
  let assert Ok(deck_name_bytes) =
    deck_name_bytes
    |> bit_array.slice(0, int.min(0x3F, bit_array.byte_size(deck_name_bytes)))
  let deck_name_length =
    int.bitwise_shift_left(language_number, 6)
    |> int.bitwise_or(bit_array.byte_size(deck_name_bytes))

  let deck_bytes = bit_array.append(<<>>, <<deck_name_length>>)

  let deck_bytes = case version >= 2 {
    True -> {
      let sideboard_count = list.length(option.unwrap(deck.sideboard, []))
      let sideboard_count = case version >= 4 && option.is_some(deck.icon) {
        True -> int.bitwise_or(sideboard_count, 0x80)
        False -> sideboard_count
      }
      bit_array.append(deck_bytes, <<sideboard_count>>)
    }
    False -> deck_bytes
  }

  let card_groups = []
  let card_groups = list.append(card_groups, group_cards(deck.digi_eggs))
  let card_groups = list.append(card_groups, group_cards(deck.deck))
  let card_groups =
    list.append(card_groups, group_cards(option.unwrap(deck.sideboard, [])))

  let deck_bytes =
    list.fold(card_groups, deck_bytes, fn(accl, card_group) {
      // Encode card set
      let card_set = card_group |> pair.first |> pair.first
      let card_number_padding = card_group |> pair.first |> pair.second
      let cards = card_group |> pair.second
      let deck_bytes = case version {
        0 ->
          bit_array.append(
            accl,
            card_set |> string.pad_right(4, " ") |> bit_array.from_string,
          )
        _ -> {
          let card_set_chars = string.to_graphemes(card_set)
          let card_set_bytes =
            card_set_chars
            |> list.fold(<<>>, fn(accl2, char) {
              let base_36_char = common.from_base36(char)
              let result = case
                { bit_array.byte_size(accl2) + 1 } < list.length(card_set_chars)
              {
                True -> int.bitwise_or(base_36_char, 0x80)
                False -> base_36_char
              }
              bit_array.append(accl2, <<result>>)
            })
          bit_array.append(accl, card_set_bytes)
        }
      }

      let deck_bytes = case version < 2 {
        True -> {
          let x =
            int.bitwise_shift_left(card_number_padding - 1, 6)
            |> int.bitwise_or(list.length(cards))
          bit_array.append(deck_bytes, <<x>>)
        }
        False -> {
          let x =
            int.bitwise_shift_left(card_number_padding - 1, 6)
            |> int.bitwise_or(bits_with_carry(list.length(cards), 6))
          bit_array.append(deck_bytes, <<x>>)
          |> append_rest_to_deck_bytes(list.length(cards), 6)
        }
      }

      let prev_card_number = 0
      let #(deck_bytes, _) =
        list.fold(cards, #(deck_bytes, prev_card_number), fn(accl, card) {
          let deck_bytes = pair.first(accl)
          let prev_card_number = pair.second(accl)
          let assert Ok(#(_, card_set_number)) =
            string.split_once(card.number, "-")
          let assert Ok(card_set_number) = int.parse(card_set_number)
          let card_number_offset = card_set_number - prev_card_number
          let prev_card_number = card_set_number
          let parallel_id = option.unwrap(card.parallel_id, 0)
          let deck_bytes = case version == 0 {
            True -> {
              // 2 bits for card count (1-4)
              // 3 bits for parallel id (0-7)
              // 3 bits for start of card number offset
              let x =
                int.bitwise_shift_left(card.count - 1, 6)
                |> int.bitwise_or(int.bitwise_shift_left(parallel_id, 3))
                |> int.bitwise_or(bits_with_carry(card_number_offset, 3))
              bit_array.append(deck_bytes, <<x>>)
              |> append_rest_to_deck_bytes(card_number_offset, 3)
            }
            False -> {
              let card_count = card.count - 1
              let card_number_offset_start =
                int.bitwise_or(
                  int.bitwise_shift_left(parallel_id, 5),
                  bits_with_carry(card_number_offset, 5),
                )
              bit_array.append(deck_bytes, <<card_count>>)
              |> bit_array.append(<<card_number_offset_start>>)
              |> append_rest_to_deck_bytes(card_number_offset, 5)
            }
          }
          #(deck_bytes, prev_card_number)
        })

      deck_bytes
    })

  let deck_bytes = {
    let assert Ok(bytes_to_checksum) =
      bit_array.slice(deck_bytes, 1, bit_array.byte_size(deck_bytes) - 1)
    let computed_checksum =
      common.compute_checksum(
        bytes_to_checksum,
        bit_array.byte_size(bytes_to_checksum),
      )
    let checksum = <<computed_checksum>>
    bit_array.append(<<version_and_digi_egg_count>>, checksum)
    |> bit_array.append(deck_bytes)
    |> bit_array.append(deck_name_bytes)
  }

  common.prefix <> bit_array.base64_url_encode(deck_bytes, False)
}
