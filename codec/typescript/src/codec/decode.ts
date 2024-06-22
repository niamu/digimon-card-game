import {
  VERSION,
  PREFIX,
  Card,
  Deck,
  Language,
  computeChecksum,
  base36_to_char,
} from "./common";

let byteOffset: number = 0;

function is_carry_bit(current_byte: number, mask_bits: number): boolean {
  return 0 != (current_byte & (1 << mask_bits));
}

function read_bits_from_byte(
  current_byte: number,
  mask_bits: number,
  delta_shift: number,
  out_bits: number,
): number {
  return ((current_byte & ((1 << mask_bits) - 1)) << delta_shift) | out_bits;
}

function read_encoded_u32(
  base_value: number,
  current_byte: number,
  delta_shift: number,
  deckBytes: Uint8Array,
): number {
  if (delta_shift - 1 == 0 || is_carry_bit(current_byte, delta_shift - 1)) {
    while (true) {
      let next_byte = deckBytes[byteOffset];
      byteOffset++;
      base_value = read_bits_from_byte(
        next_byte,
        8 - 1,
        delta_shift - 1,
        base_value,
      );
      if (!is_carry_bit(next_byte, 8 - 1)) {
        break;
      }
      delta_shift += 8 - 1;
    }
  } else {
    return read_bits_from_byte(current_byte, delta_shift - 1, 0, 0);
  }

  return base_value;
}

function deserialize_card(
  version: number,
  deckBytes: Uint8Array,
  card_set: string,
  card_set_padding: number,
  prev_card_number: number,
): [number, Card] {
  let current_byte: number = deckBytes[byteOffset];
  byteOffset++;
  let card_count = version == 0 ? (current_byte >> 6) + 1 : current_byte + 1;

  if (version != 0) {
    current_byte = deckBytes[byteOffset];
    byteOffset++;
  }
  const card_parallel_id =
    version == 0 ? (current_byte >> 3) & 0x07 : current_byte >> 5;

  const delta_shift: number = version == 0 ? 3 : 5;
  let card_number: number = read_bits_from_byte(
    current_byte,
    delta_shift - 1,
    0,
    0,
  );
  prev_card_number += read_encoded_u32(
    card_number,
    current_byte,
    delta_shift,
    deckBytes,
  );

  return [
    prev_card_number,
    new Card(
      card_set +
        "-" +
        prev_card_number.toString().padStart(card_set_padding, "0"),
      card_parallel_id,
      card_count,
    ),
  ];
}

function parseDeck(deckBytes: Uint8Array) {
  const textDecoder = new TextDecoder("utf8");
  byteOffset = 0;
  const version_and_digi_egg_count: number = deckBytes[byteOffset];
  byteOffset++;
  const version: number = version_and_digi_egg_count >> 4;
  if (!(VERSION >= version)) {
    throw Error("Deck version " + version + " not supported");
  }

  const digi_egg_count: number =
    3 <= version && version <= 4
      ? version_and_digi_egg_count & 0x07
      : version_and_digi_egg_count & 0x0f;
  const checksum: number = deckBytes[byteOffset];
  byteOffset++;
  const deck_name_length_byte: number = deckBytes[byteOffset];
  byteOffset++;
  let deck_name_length: number = deck_name_length_byte;
  if (version >= 5) {
    deck_name_length &= 0x3f;
  }
  const total_card_bytes = deckBytes.slice(byteOffset, -deck_name_length);
  const language_number: number =
    version >= 5
      ? deck_name_length_byte >> 6
      : (version_and_digi_egg_count >> 3) & 0x01;

  const computed_checksum: number = computeChecksum(total_card_bytes);
  if (checksum != computed_checksum) {
    throw Error("Deck checksum failed");
  }

  let sideboard_count: number = 0;
  if (version >= 2) {
    sideboard_count = deckBytes[byteOffset];
    byteOffset++;
  }

  const has_icon = version >= 4 && sideboard_count >> 7 > 0;
  if (version >= 4) {
    sideboard_count &= 0x7f;
  }

  let cards: Array<Card> = [];

  while (byteOffset < deckBytes.length - deck_name_length) {
    // Card Set Header
    // - Card Set
    let card_set: string = "";
    if (version == 0) {
      const card_set_bytes = deckBytes.slice(byteOffset, byteOffset + 4);
      byteOffset += 4;
      card_set = textDecoder.decode(card_set_bytes).trim();
    } else {
      while (true) {
        let current_byte: number = deckBytes[byteOffset];
        byteOffset++;
        card_set += base36_to_char.get(current_byte & 0x3f);
        if (current_byte >> 7 == 0) {
          break;
        }
      }
    }
    // - Card Set Zero Padding and Count
    const padding_and_set_count: number = deckBytes[byteOffset];
    byteOffset++;
    const card_set_padding: number = (padding_and_set_count >> 6) + 1;
    let card_set_count: number = padding_and_set_count & 0x3f;
    if (version >= 2) {
      card_set_count = read_encoded_u32(
        padding_and_set_count,
        padding_and_set_count,
        6,
        deckBytes,
      );
    }

    let prev_card_number: number = 0;

    for (let _ = 0; _ < card_set_count; _++) {
      const [prev_card_number_result, card] = deserialize_card(
        version,
        deckBytes,
        card_set,
        card_set_padding,
        prev_card_number,
      );
      prev_card_number = prev_card_number_result;
      cards.push(card);
    }
  }

  let icon: string | null = null;
  let deck_name = textDecoder.decode(deckBytes.slice(byteOffset)).trim();
  if (has_icon) {
    const [icon_raw, new_deck_name] = [
      deck_name.slice(0, 8),
      deck_name.slice(8),
    ];
    icon = icon_raw.trim();
    deck_name = new_deck_name.trim();
  }

  const deck = new Deck(
    cards.slice(0, digi_egg_count),
    cards.slice(digi_egg_count, cards.length - sideboard_count),
    cards.slice(cards.length - sideboard_count),
    icon,
    version >= 3 ? Language[language_number] : null,
    deck_name,
  );
  if (version < 2 || deck?.sideboard?.length == 0) delete deck.sideboard;
  if (!deck.icon) delete deck.icon;
  if (version < 3 || !deck.language) delete deck.language;
  return deck;
}

function decodeDeckString(deckCode: string): Uint8Array {
  if (!deckCode.startsWith(PREFIX)) {
    throw Error('Deck codes must begin with "DCG"');
  }
  return Uint8Array.from(
    atob(deckCode.substr(PREFIX.length).replace(/_/g, "/").replace(/-/g, "+")),
    (c) => c.charCodeAt(0),
  );
}

export function decode(deckCode: string): Deck {
  return parseDeck(decodeDeckString(deckCode));
}
