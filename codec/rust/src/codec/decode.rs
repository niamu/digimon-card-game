//! Decoder

pub use crate::codec::{
    base36_to_char, compute_checksum, Card, Deck, Language, PREFIX, VERSION,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};

fn get_u8(deck_bytes: &mut Vec<u8>) -> u8 {
    *deck_bytes.drain(..1).collect::<Vec<u8>>().first().unwrap()
}

fn get_u32(deck_bytes: &mut Vec<u8>) -> Vec<u8> {
    (*deck_bytes.drain(..4).collect::<Vec<u8>>()).to_vec()
}

fn get_string_from_bytes(card_set_bytes: Vec<u8>) -> String {
    String::from_utf8(card_set_bytes)
        .unwrap()
        .trim()
        .to_string()
}

fn is_carry_bit(current_byte: u8, mask_bits: u8) -> bool {
    0 != current_byte & (1 << mask_bits)
}

fn read_bits_from_byte(
    current_byte: u8,
    mask_bits: u8,
    delta_shift: u8,
    out_bits: u32,
) -> u32 {
    (u32::from(current_byte & ((1 << mask_bits) - 1)) << delta_shift) | out_bits
}

fn read_encoded_u32(
    mut base_value: u32,
    current_byte: u8,
    mut delta_shift: u8,
    mut deck_bytes: &mut Vec<u8>,
) -> u32 {
    if delta_shift - 1 == 0 || is_carry_bit(current_byte, &delta_shift - 1) {
        loop {
            let next_byte = get_u8(&mut deck_bytes);
            base_value = read_bits_from_byte(
                next_byte,
                u8::BITS as u8 - 1,
                &delta_shift - 1,
                base_value,
            );
            if !is_carry_bit(next_byte, u8::BITS as u8 - 1) {
                break;
            }
            delta_shift += u8::BITS as u8 - 1;
        }
    } else {
        return read_bits_from_byte(current_byte, &delta_shift - 1, 0, 0);
    };

    base_value
}

fn deserialize_card(
    version: u8,
    mut deck_bytes: &mut Vec<u8>,
    card_set: &String,
    card_set_padding: usize,
    prev_card_number: &mut u32,
) -> Card {
    let current_byte: u8 = get_u8(&mut deck_bytes);
    let card_count = if version == 0 {
        (current_byte >> 6) + 1
    } else {
        current_byte + 1
    };
    let current_byte: u8 = if version == 0 {
        current_byte
    } else {
        get_u8(&mut deck_bytes)
    };
    let card_parallel_id = if version == 0 {
        current_byte >> 3 & 0x07
    } else {
        current_byte >> 5
    };

    let delta_shift: u8 = if version == 0 { 3 } else { 5 };
    let card_number: u32 =
        read_bits_from_byte(current_byte, delta_shift - 1, 0, 0);
    *prev_card_number += read_encoded_u32(
        card_number,
        current_byte,
        delta_shift,
        &mut deck_bytes,
    );

    Card {
        number: format!(
            "{s}-{:0>p$}",
            prev_card_number,
            s = &card_set,
            p = card_set_padding
        ),
        parallel_id: card_parallel_id,
        count: card_count,
    }
}

fn parse_deck(mut deck_bytes: &mut Vec<u8>) -> Deck {
    let version_and_digi_egg_count = get_u8(&mut deck_bytes);
    let version = version_and_digi_egg_count >> 4;
    assert!(VERSION >= version, "Deck version {} not supported", version);

    let digi_egg_set_count = (version_and_digi_egg_count
        & if 3 <= version && version <= 4 {
            0x07
        } else {
            0x0F
        }) as usize;
    let checksum = get_u8(&mut deck_bytes);
    let deck_name_length_byte = get_u8(&mut deck_bytes) as usize;
    let mut deck_name_length = deck_name_length_byte;
    if version >= 5 {
        deck_name_length &= 0x3F;
    }
    let total_card_bytes = deck_bytes.len() - deck_name_length;

    let language_number = if version >= 5 {
        deck_name_length_byte >> 6
    } else {
        (version_and_digi_egg_count as usize >> 3) & 0x01
    };
    let language = match language_number {
        0 => Some(Language::Japanese),
        1 => Some(Language::English),
        2 => Some(Language::Chinese),
        3 => Some(Language::Korean),
        _ => Some(Language::English),
    };

    let computed_checksum = compute_checksum(total_card_bytes, deck_bytes);
    assert_eq!(checksum, computed_checksum, "Deck checksum failed");

    let mut sideboard_count: usize = if version >= 2 {
        get_u8(&mut deck_bytes).into()
    } else {
        0
    };

    let has_icon = version >= 4 && (sideboard_count >> 7) > 0;
    sideboard_count = if version >= 4 {
        sideboard_count & 0x7F
    } else {
        sideboard_count
    };

    let mut cards: Vec<Card> = Vec::new();

    while deck_bytes.len() > deck_name_length {
        // Card Set Header
        // - Card Set
        let card_set = if version == 0 {
            let card_set_bytes = get_u32(&mut deck_bytes);
            get_string_from_bytes(card_set_bytes)
        } else {
            let mut s: String = Default::default();
            loop {
                let current_byte: u8 = get_u8(&mut deck_bytes);
                s += base36_to_char(current_byte & 0x3F);
                if current_byte >> 7 == 0 {
                    break;
                }
            }
            s.clone()
        };
        // - Card Set Zero Padding and Count
        let padding_and_set_count = &get_u8(&mut deck_bytes);
        let card_set_padding = ((padding_and_set_count >> 6) + 1) as usize;
        let card_set_count: u32 = if version >= 2 {
            read_encoded_u32(
                *padding_and_set_count as u32,
                *padding_and_set_count,
                6,
                &mut deck_bytes,
            )
        } else {
            (padding_and_set_count & 0x3F).into()
        };

        let mut prev_card_number: u32 = 0;

        for _ in 0..card_set_count {
            let card = deserialize_card(
                version,
                &mut deck_bytes,
                &card_set,
                card_set_padding,
                &mut prev_card_number,
            );
            cards.push(card);
        }
    }

    let mut icon = None;
    let mut deck_name = get_string_from_bytes(deck_bytes.to_vec());
    if has_icon {
        let (icon_raw, new_deck_name) = deck_name.split_at(8);
        icon = Some(icon_raw.trim().to_string());
        deck_name = new_deck_name.trim().to_string();
    }

    Deck {
        digi_eggs: (&cards[..digi_egg_set_count]).to_vec(),
        deck: (&cards[digi_egg_set_count..(cards.len() - sideboard_count)])
            .to_vec(),
        sideboard: if version >= 2 && sideboard_count != 0 {
            (&cards[(cards.len() - sideboard_count)..]).to_vec()
        } else {
            Vec::<Card>::new()
        },
        icon: icon,
        language: if version >= 3 { language } else { None },
        name: deck_name.to_string(),
    }
}

pub fn decode(deck_code_str: &str) -> Deck {
    //! Decode public function that takes a deck code and decodes to a Deck struct
    let (prefix, deck_code) = deck_code_str.split_at(3);
    assert_eq!(PREFIX, prefix, "Prefix was not 'DCG'");

    let mut deck_bytes: Vec<u8> = URL_SAFE_NO_PAD.decode(deck_code).unwrap();
    parse_deck(&mut deck_bytes)
}
