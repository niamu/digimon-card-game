import {
  VERSION,
  PREFIX,
  Card,
  Deck,
  Language,
  char_to_base36,
  computeChecksum,
} from "./common";

async function bufferToBase64(buffer: Uint8Array): Promise<string> {
  return await new Promise((resolve, reject) => {
    const reader = Object.assign(new FileReader(), {
      onload: () => {
        const s = reader.result as string;
        resolve(s.substring(s.indexOf(",") + 1));
      },
      onerror: () => reject(reader.error),
    });
    reader.readAsDataURL(
      new File([buffer], "", { type: "application/octet-stream" }),
    );
  });
}

function bits_with_carry(value: number, bits: number): number {
  const limit_bit = 1 << (bits - 1);
  let result = value & (limit_bit - 1);
  if (value >= limit_bit) {
    result |= limit_bit;
  }
  return result;
}

function append_rest_to_deck_bytes(
  deckBytes: Array<number>,
  value: number,
  already_written_bits: number,
) {
  let remaining_value = value >> (already_written_bits - 1);
  while (remaining_value > 0) {
    deckBytes.push(bits_with_carry(remaining_value, 8));
    remaining_value = remaining_value >> 7;
  }
}

function group_cards(d: Array<Card>): Array<[string, number, Array<Card>]> {
  d.sort(
    (a, b) => a.number.localeCompare(b.number) || a.parallel_id - b.parallel_id,
  );
  let result = Object.groupBy(d, ({ number }) => {
    const [card_set, card_number] = number.split("-");
    return [card_set, card_number.length].toString();
  });
  return Object.entries(result).map(([k, v]) => {
    const [card_set, padding] = k.split(",");
    return [card_set, parseInt(padding), v];
  });
}

export async function encode(
  deck: Deck,
  version: number = VERSION,
): Promise<string> {
  let deckBytes: number[] = new Array();
  const textEncoder = new TextEncoder();
  const language_number = Language[Language[deck.language]] ?? 1;

  const version_and_digi_egg_count =
    3 <= version && version <= 4
      ? (version << 4) | (language_number << 3) | (deck.digi_eggs.length & 0x0f)
      : (version << 4) | (deck.digi_eggs.length & 0x0f);

  if (version >= 4 && !!deck.icon) {
    deck.name = deck.icon.padEnd(8) + deck.name;
  }

  let deck_name_bytes = textEncoder.encode(deck.name);
  deck_name_bytes = deck_name_bytes.slice(0, 0x3f);
  deck.name = new TextDecoder().decode(deck_name_bytes);
  let name_length = deck_name_bytes.length;
  if (version >= 5) {
    name_length = (language_number << 6) | name_length;
  }

  deckBytes.push(version_and_digi_egg_count);
  deckBytes.push(0); // checksum placeholder
  deckBytes.push(name_length);
  const HEADER_SIZE = deckBytes.length;

  if (version >= 2) {
    let sideboard_size = deck?.sideboard?.length ?? 0;
    if (version >= 4 && !!deck.icon) {
      sideboard_size |= 0x80;
    }
    deckBytes.push(sideboard_size);
  }

  let grouped_decks = new Array();
  grouped_decks = grouped_decks.concat(group_cards(deck?.digi_eggs ?? []));
  grouped_decks = grouped_decks.concat(group_cards(deck?.deck ?? []));
  grouped_decks = grouped_decks.concat(group_cards(deck?.sideboard ?? []));

  for (const [card_set, card_number_padding, grouped_cards] of grouped_decks) {
    // Encode card_set
    if (version == 0) {
      // Use 4 characters/bytes to store card sets.
      deckBytes = deckBytes.concat(
        Array.from(textEncoder.encode(card_set.padEnd(4, " "))),
      );
    } else {
      // Encode each character of card-set in Base36.
      // Use 8th bit as continue bit. If 0, reached end.
      for (let i = 0; i < card_set.length; i++) {
        const c = card_set[i];
        let base36_char = char_to_base36.get(c);
        if (!!card_set[parseInt(i.toString()) + 1]) {
          base36_char |= 0x80;
        }
        deckBytes.push(base36_char);
      }
    }
    // 2 bits for card number zero padding (zero padding stored as 0 indexed)
    // 6 bits for initial count offset of cards in a card group
    if (version < 2) {
      deckBytes.push(((card_number_padding - 1) << 6) | grouped_cards.length);
    } else {
      deckBytes.push(
        ((card_number_padding - 1) << 6) |
          bits_with_carry(grouped_cards.length, 6),
      );
      append_rest_to_deck_bytes(deckBytes, grouped_cards.length, 6);
    }
    let prev_card_number = 0;
    for (const card of grouped_cards) {
      const card_set_number = card.number.split("-")[1];
      const card_number_offset = card_set_number - prev_card_number;
      if (version == 0) {
        // 2 bits for card count (1-4)
        // 3 bits for parallel id (0-7)
        // 3 bits for start of card number offset
        deckBytes.push(
          ((card.count - 1) << 6) |
            (card.parallel_id << 3) |
            bits_with_carry(card_number_offset, 3),
        );
        // rest of card number offset
        append_rest_to_deck_bytes(deckBytes, card_number_offset, 3);
      } else {
        // 1 byte for card count (1-50 with BT6-085)
        // 3 bits for parallel id (0-7)
        // 5 bits for start of card number offset
        deckBytes.push(card.count - 1);
        deckBytes.push(
          (card.parallel_id << 5) | bits_with_carry(card_number_offset, 5),
        );
        // rest of card number offset
        append_rest_to_deck_bytes(deckBytes, card_number_offset, 5);
      }
      prev_card_number = card_set_number;
    }
  }

  // Compute and store cards checksum (second byte in buffer)
  // Only store the first byte of checksum
  deckBytes[1] = computeChecksum(deckBytes.slice(HEADER_SIZE));

  for (const b of deck_name_bytes) {
    deckBytes.push(b);
  }

  const deckString =
    PREFIX +
    (await bufferToBase64(new Uint8Array(deckBytes)))
      .replace(/\//g, "_")
      .replace(/\+/g, "-")
      .replace(/=/g, "");

  return deckString;
}
