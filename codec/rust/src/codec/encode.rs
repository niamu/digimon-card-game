//! Encoder

pub use crate::codec::{
    char_to_base36, compute_checksum, Card, Deck, Language, HEADER_SIZE,
    PREFIX, VERSION,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use std::str;

fn bits_with_carry(value: u32, bits: u8) -> u8 {
    let limit_bit = 1 << (bits - 1);
    let mut result = value & (limit_bit - 1);
    if value >= limit_bit {
        result |= limit_bit
    }
    result as u8
}

fn append_rest_to_deck_bytes(
    deck_bytes: &mut Vec<u8>,
    value: u32,
    already_written_bits: u8,
) {
    let mut remaining_value = value >> (already_written_bits - 1);
    while remaining_value > 0 {
        deck_bytes.push(bits_with_carry(remaining_value, u8::BITS as u8));
        remaining_value = remaining_value >> 7;
    }
}

fn group_cards(mut cards: Vec<Card>) -> Vec<(String, usize, Vec<Card>)> {
    cards.sort_unstable_by_key(|card: &Card| {
        (card.number.clone(), card.parallel_id)
    });
    let mut result: Vec<(String, usize, Vec<Card>)> = Vec::new();
    let mut grouped_cards: Vec<Card> = Vec::new();
    let mut prev_card_set: Option<String> = None;
    let mut prev_card_set_number_padding: Option<usize> = None;
    for card in cards {
        let split_result: Vec<&str> = card.number.as_str().split("-").collect();
        let card_set = split_result[0].to_string();
        let card_set_number_padding = split_result[1].len();
        let same_card_set = match prev_card_set {
            Some(ref x) => *x == card_set,
            _ => true,
        };
        let same_card_set_number_padding = match prev_card_set_number_padding {
            Some(x) => x == card_set_number_padding,
            _ => true,
        };
        if same_card_set && same_card_set_number_padding {
            grouped_cards.push(card);
        } else {
            result.push((
                prev_card_set.clone().unwrap(),
                prev_card_set_number_padding.clone().unwrap(),
                grouped_cards.clone(),
            ));
            grouped_cards.clear();
            grouped_cards.push(card);
        }
        prev_card_set = Some(card_set.clone());
        prev_card_set_number_padding = Some(card_set_number_padding);
    }
    if grouped_cards.len() > 0 {
        result.push((
            prev_card_set.clone().unwrap(),
            prev_card_set_number_padding.clone().unwrap(),
            grouped_cards.clone(),
        ));
    }
    result
}

pub fn encode(
    Deck {
        digi_eggs,
        deck,
        sideboard,
        language,
        icon,
        mut name,
    }: Deck,
    version: u8,
) -> String {
    //! Encode public function that takes a Deck struct and encodes to a deck code string
    let mut deck_bytes: Vec<u8> = Vec::<u8>::new();

    let language_number = match language {
        Some(Language::Japanese) => 0,
        Some(Language::English) => 1,
        Some(Language::Chinese) => 2,
        Some(Language::Korean) => 3,
        _ => 1,
    };

    let version_and_digi_egg_count = if 3 <= version && version <= 4 {
        version << 4 | language_number << 3 | digi_eggs.len() as u8 & 0x0F
    } else {
        version << 4 | digi_eggs.len() as u8 & 0x0F
    };

    if version >= 4 && icon.is_some() {
        let mut icon_string = String::from("        ");
        icon_string = icon.clone().unwrap() + &icon_string;
        icon_string = icon_string.split_at(8).0.to_string();
        name = icon_string + &name;
    }
    let mut deck_name_bytes = name.as_bytes().to_vec();
    deck_name_bytes.truncate(0x3F);
    name = str::from_utf8(&deck_name_bytes).unwrap().trim().to_string();
    let mut name_length = deck_name_bytes.len() as u8;
    if version >= 5 {
        name_length = language_number << 6 | name_length;
    }

    deck_bytes.push(version_and_digi_egg_count);
    deck_bytes.push(0); // checksum placeholder
    deck_bytes.push(name_length);

    if version >= 2 {
        let mut sideboard_size = sideboard.len() as u8;
        if version >= 4 && icon.is_some() {
            sideboard_size |= 0x80;
        }
        deck_bytes.push(sideboard_size);
    }

    let mut grouped_decks = Vec::new();
    grouped_decks.append(&mut group_cards(digi_eggs.clone()));
    grouped_decks.append(&mut group_cards(deck.clone()));
    grouped_decks.append(&mut group_cards(sideboard.clone()));

    for (card_set, card_number_padding, grouped_cards) in grouped_decks {
        // Encode card_set
        if version == 0 {
            // Use 4 characters/bytes to store card sets.
            let mut card_set_bytes =
                format!("{: <4}", card_set).as_bytes().to_vec();
            deck_bytes.append(&mut card_set_bytes);
        } else {
            // Encode each character of card-set in Base36.
            // Use 8th bit as continue bit. If 0, reached end.
            let mut chr_iterator = card_set.chars().peekable();
            while let Some(chr) = chr_iterator.next() {
                let mut base36_char = char_to_base36(&chr);
                if chr_iterator.peek().is_some() {
                    base36_char |= 0x80;
                }
                deck_bytes.push(base36_char);
            }
        }
        // 2 bits for card number zero padding (zero padding stored as 0 indexed)
        // 6 bits for initial count offset of cards in a card group
        if version < 2 {
            deck_bytes.push(
                (card_number_padding as u8 - 1) << 6
                    | grouped_cards.len() as u8,
            );
        } else {
            deck_bytes.push(
                (card_number_padding as u8 - 1) << 6
                    | bits_with_carry(grouped_cards.len() as u32, 6),
            );
            append_rest_to_deck_bytes(
                &mut deck_bytes,
                grouped_cards.len() as u32,
                6,
            );
        }
        let mut prev_card_number = 0;
        for card in &grouped_cards {
            let split_result: Vec<&str> =
                card.number.as_str().split("-").collect();
            let card_set_number: u32 = split_result[1].parse().unwrap();
            let card_number_offset = card_set_number - prev_card_number;
            if version == 0 {
                // 2 bits for card count (1-4)
                // 3 bits for parallel id (0-7)
                // 3 bits for start of card number offset
                deck_bytes.push(
                    (card.count - 1) << 6
                        | card.parallel_id << 3
                        | bits_with_carry(card_number_offset as u32, 3),
                );
                // rest of card number offset
                append_rest_to_deck_bytes(
                    &mut deck_bytes,
                    card_number_offset as u32,
                    3,
                );
            } else {
                // TODO: Consider encoding card count with carry bit
                // so it takes less space when count is less than 5 bits?

                // 1 byte for card count (1-50 with BT6-085)
                // 3 bits for parallel id (0-7)
                // 5 bits for start of card number offset
                deck_bytes.push(card.count - 1);
                deck_bytes.push(
                    card.parallel_id << 5
                        | bits_with_carry(card_number_offset as u32, 5),
                );
                // rest of card number offset
                append_rest_to_deck_bytes(
                    &mut deck_bytes,
                    card_number_offset as u32,
                    5,
                );
            }
            prev_card_number = card_set_number;
        }
    }

    // Compute and store cards checksum (second byte in buffer)
    // Only store the first byte of checksum
    let total_card_bytes = deck_bytes.len() - HEADER_SIZE;
    let computed_checksum =
        compute_checksum(total_card_bytes, &deck_bytes.clone()[3..]);
    deck_bytes[1] = (computed_checksum & 0xFF) as u8;

    deck_bytes.append(&mut name.as_bytes().to_vec());

    let deck_b64_encoded = URL_SAFE_NO_PAD.encode(deck_bytes);
    let deck_string = "DCG".to_string() + &deck_b64_encoded;
    deck_string
}
