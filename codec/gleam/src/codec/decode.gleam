import common
import common/card.{type Card, Card}
import common/deck.{type Deck, Deck}
import common/language
import gleam/bit_array
import gleam/int
import gleam/list
import gleam/option.{None, Some}
import gleam/string

fn read_byte(deck_bytes: BitArray) -> #(Int, BitArray) {
  case deck_bytes {
    <<b:8, rest:bits>> -> #(b, rest)
    _ -> panic as "Not a byte"
  }
}

fn is_carry_bit(current_byte: Int, mask_bits: Int) -> Bool {
  let x =
    int.bitwise_shift_left(1, mask_bits)
    |> int.bitwise_and(current_byte)
  x != 0
}

fn read_bits_from_byte(
  current_byte: Int,
  mask_bits: Int,
  shift_bits: Int,
  base_value: Int,
) -> Int {
  current_byte
  |> int.bitwise_and(int.bitwise_shift_left(1, mask_bits) - 1)
  |> int.bitwise_shift_left(shift_bits)
  |> int.bitwise_or(base_value)
}

fn read_encoded_int_with_carry(
  deck_bytes: BitArray,
  base_value: Int,
  current_byte: Int,
  shift_bits: Int,
) -> #(Int, BitArray) {
  let bits_in_a_byte = 8
  let is_carry = is_carry_bit(current_byte, shift_bits)
  case is_carry {
    True -> {
      let #(next_byte, deck_bytes) = read_byte(deck_bytes)
      let base_value =
        read_bits_from_byte(
          next_byte,
          bits_in_a_byte - 1,
          shift_bits - 1,
          base_value,
        )
      read_encoded_int_with_carry(
        deck_bytes,
        base_value,
        next_byte,
        shift_bits + bits_in_a_byte - 1,
      )
    }
    False -> #(base_value, deck_bytes)
  }
}

fn read_encoded_int(
  deck_bytes: BitArray,
  base_value: Int,
  current_byte: Int,
  shift_bits: Int,
) -> #(Int, BitArray) {
  let is_carry = is_carry_bit(current_byte, shift_bits - 1)
  case shift_bits == 0 || is_carry {
    True -> {
      read_encoded_int_with_carry(
        deck_bytes,
        base_value,
        current_byte,
        shift_bits - 1,
      )
    }
    False -> {
      let result = read_bits_from_byte(current_byte, shift_bits - 1, 0, 0)
      #(result, deck_bytes)
    }
  }
}

fn parse_card_set(
  deck_bytes: BitArray,
  version: Int,
  card_set: String,
) -> #(String, BitArray) {
  case version {
    0 -> {
      let assert Ok(card_set_bytes) =
        bit_array.slice(from: deck_bytes, at: 0, take: 4)
      let assert Ok(deck_bytes) =
        bit_array.slice(
          from: deck_bytes,
          at: 4,
          take: bit_array.byte_size(deck_bytes) - 4,
        )
      let assert Ok(card_set) = bit_array.to_string(card_set_bytes)
      #(card_set |> string.trim(), deck_bytes)
    }
    _ -> {
      let #(char_byte, deck_bytes) = read_byte(deck_bytes)
      let assert Ok(char) =
        int.bitwise_and(char_byte, 0x3F) |> int.to_base_string(36)
      case int.bitwise_shift_right(char_byte, 7) != 0 {
        True -> parse_card_set(deck_bytes, version, card_set <> char)
        False -> #(card_set <> char, deck_bytes)
      }
    }
  }
}

fn parse_card_set_padding_and_count(
  deck_bytes: BitArray,
  version: Int,
) -> #(Int, Int, BitArray) {
  let #(padding_and_count, deck_bytes) = read_byte(deck_bytes)
  let padding = int.bitwise_shift_right(padding_and_count, 6) + 1
  case version < 2 {
    True -> {
      let cards_in_group = int.bitwise_and(padding_and_count, 0x3F)
      #(padding, cards_in_group, deck_bytes)
    }
    False -> {
      let #(cards_in_group, deck_bytes) =
        read_encoded_int(deck_bytes, padding_and_count, padding_and_count, 6)
      #(padding, cards_in_group, deck_bytes)
    }
  }
}

fn parse_cards_in_group(
  deck_bytes: BitArray,
  version: Int,
  prev_card_number: Int,
  card_set: String,
  card_set_padding: Int,
  cards_in_group: Int,
  cards: List(Card),
) -> #(List(Card), BitArray) {
  let #(current_byte, deck_bytes) = read_byte(deck_bytes)
  let card_count = case version {
    0 -> int.bitwise_shift_right(current_byte, 6) + 1
    _ -> current_byte + 1
  }
  let #(current_byte, deck_bytes) = case version {
    0 -> #(current_byte, deck_bytes)
    _ -> read_byte(deck_bytes)
  }
  let parallel_id = case version {
    0 -> int.bitwise_shift_right(current_byte, 3) |> int.bitwise_and(0x07)
    _ -> int.bitwise_shift_right(current_byte, 5)
  }

  let delta_shift = case version {
    0 -> 3
    _ -> 5
  }
  let current_card_number =
    read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)
  let #(card_number_offset, deck_bytes) =
    read_encoded_int(deck_bytes, current_card_number, current_byte, delta_shift)
  let prev_card_number = prev_card_number + card_number_offset
  let card_number =
    card_set
    <> "-"
    <> int.to_string(prev_card_number)
    |> string.pad_left(to: card_set_padding, with: "0")
  let card =
    Card(number: card_number, parallel_id: parallel_id, count: card_count)
  let cards = list.append(cards, [card])
  case list.length(cards) < cards_in_group {
    True ->
      parse_cards_in_group(
        deck_bytes,
        version,
        prev_card_number,
        card_set,
        card_set_padding,
        cards_in_group,
        cards,
      )
    False -> #(cards, deck_bytes)
  }
}

fn parse_card_group(
  deck_bytes: BitArray,
  version: Int,
) -> #(List(Card), BitArray) {
  let #(card_set, deck_bytes) = parse_card_set(deck_bytes, version, "")
  let #(card_set_padding, cards_in_group, deck_bytes) =
    parse_card_set_padding_and_count(deck_bytes, version)
  let #(cards, deck_bytes) =
    parse_cards_in_group(
      deck_bytes,
      version,
      0,
      card_set,
      card_set_padding,
      cards_in_group,
      [],
    )
  #(cards, deck_bytes)
}

fn parse_cards(
  deck_bytes: BitArray,
  cards: List(Card),
  version: Int,
) -> List(Card) {
  case bit_array.byte_size(deck_bytes) > 0 {
    True -> {
      let #(grouped_cards, deck_bytes) = parse_card_group(deck_bytes, version)
      let cards = list.append(cards, grouped_cards)
      parse_cards(deck_bytes, cards, version)
    }
    False -> cards
  }
}

fn parse_deck(deck_bytes: BitArray) -> Deck {
  let #(version_and_digi_egg_count, deck_bytes) = read_byte(deck_bytes)
  let version = int.bitwise_shift_right(version_and_digi_egg_count, 4)
  let _ = case version <= common.version {
    True -> Nil
    False ->
      panic as { "Deck version " <> int.to_string(version) <> " not supported" }
  }

  let digi_egg_set_count = case 3 <= version && version <= 4 {
    True -> int.bitwise_and(version_and_digi_egg_count, 0x07)
    False -> int.bitwise_and(version_and_digi_egg_count, 0x0F)
  }
  let #(checksum, deck_bytes) = read_byte(deck_bytes)
  let #(deck_name_length_byte, deck_bytes) = read_byte(deck_bytes)
  let deck_name_length = case version >= 5 {
    True -> int.bitwise_and(deck_name_length_byte, 0x3F)
    False -> deck_name_length_byte
  }
  let total_card_bytes = bit_array.byte_size(deck_bytes) - deck_name_length

  let language_number = case version >= 5 {
    True -> int.bitwise_shift_right(deck_name_length_byte, 6)
    False ->
      int.bitwise_and(
        int.bitwise_shift_right(version_and_digi_egg_count, 3),
        0x01,
      )
  }
  let language = case version >= 3 {
    True -> language.deserialize(language_number)
    False -> None
  }

  let computed_checksum = common.compute_checksum(deck_bytes, total_card_bytes)
  let _ = case computed_checksum == checksum {
    True -> Nil
    False ->
      panic as {
        "Deck checksum failed; "
        <> int.to_string(checksum)
        <> " != "
        <> int.to_string(computed_checksum)
      }
  }

  let #(sideboard_count, deck_bytes) = case version >= 2 {
    True -> read_byte(deck_bytes)
    False -> #(0, deck_bytes)
  }

  let has_icon = version >= 4 && int.bitwise_shift_right(sideboard_count, 7) > 0
  let sideboard_count = case version >= 4 {
    True -> int.bitwise_and(sideboard_count, 0x7F)
    False -> sideboard_count
  }

  let assert Ok(card_bytes) =
    bit_array.slice(
      from: deck_bytes,
      at: 0,
      take: bit_array.byte_size(deck_bytes) - deck_name_length,
    )
  let cards = parse_cards(card_bytes, [], version)
  let #(digi_eggs, cards) = list.split(cards, digi_egg_set_count)
  let #(deck, sideboard) =
    list.split(cards, list.length(cards) - sideboard_count)

  let assert Ok(deck_name_bytes) =
    bit_array.slice(
      from: deck_bytes,
      at: bit_array.byte_size(deck_bytes),
      take: deck_name_length * -1,
    )
  let assert Ok(deck_name) = bit_array.to_string(deck_name_bytes)
  let #(icon, deck_name) = case has_icon {
    True -> {
      let icon = deck_name |> string.slice(0, 8) |> string.trim()
      let deck_name = deck_name |> string.drop_left(8)
      #(Some(icon), deck_name)
    }
    False -> #(None, deck_name)
  }

  Deck(
    name: deck_name,
    digi_eggs: digi_eggs,
    deck: deck,
    sideboard: sideboard,
    language: language,
    icon: icon,
  )
}

pub fn decode(code: String) -> Deck {
  case string.starts_with(code, common.prefix) {
    True -> Nil
    False -> panic as { "Prefix was not '" <> common.prefix <> "'" }
  }
  let deck_code =
    string.slice(
      from: code,
      at_index: string.length(common.prefix),
      length: string.length(code),
    )
  let assert Ok(deck_bytes) = bit_array.base64_url_decode(deck_code)

  parse_deck(deck_bytes)
}
