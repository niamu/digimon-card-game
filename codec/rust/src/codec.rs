//! Codec constants and structs
pub mod decode;
pub mod encode;

pub use crate::codec::decode::decode;
pub use crate::codec::encode::encode;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Version of the codec
pub const VERSION: u8 = 5;

/// Deck codes are all prefixed with "DCG"
pub const PREFIX: &str = "DCG";

/// version, checksum, and deck name byte count
pub const HEADER_SIZE: usize = 3;

fn is_zero(n: &u8) -> bool {
    return *n == 0;
}

/// A deck's digi-eggs and main deck is made of Card structs
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Card {
    /// card number identifier (i.e. "ST1-001")
    pub number: String,
    /// parallel-id values greater than 0 are alternate arts of the card
    #[serde(
        default,
        skip_serializing_if = "is_zero",
        alias = "parallel-id",
        rename(serialize = "parallel-id")
    )]
    pub parallel_id: u8,
    /// count of how many cards are in this card group
    pub count: u8,
}

/// Deck language
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub enum Language {
    /// Japanese
    #[serde(alias = "ja", rename(serialize = "ja"))]
    Japanese,
    /// English
    #[serde(alias = "en", rename(serialize = "en"))]
    English,
    /// Chinese
    #[serde(alias = "zh-Hans", rename(serialize = "zh-Hans"))]
    Chinese,
    /// Korean
    #[serde(alias = "ko", rename(serialize = "ko"))]
    Korean,
}

/// A deck has digi-egg cards (0-5 Cards), a main deck of cards (50 Cards), and a name (0-63 bytes)
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Deck {
    #[serde(alias = "digi-eggs", rename(serialize = "digi-eggs"))]
    /// cards in digi-egg deck
    pub digi_eggs: Vec<Card>,
    /// cards in main deck
    pub deck: Vec<Card>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    /// cards in sideboard
    pub sideboard: Vec<Card>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    /// deck icon
    pub icon: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    /// deck language
    pub language: Option<Language>,
    /// deck name
    pub name: String,
}

/// Compute checksum of deck that excludes header and deck name
pub fn compute_checksum(total_card_bytes: usize, deck_bytes: &[u8]) -> u8 {
    let checksum = deck_bytes[..total_card_bytes]
        .iter()
        .map(|&b| b as u32)
        .sum::<u32>();
    (checksum & 0xFF) as u8
}

/// Convert a u8 to a base36 character
pub fn base36_to_char(base36: u8) -> &'static str {
    let lookup: HashMap<u8, &str> = HashMap::from([
        (0, "0"),
        (1, "1"),
        (2, "2"),
        (3, "3"),
        (4, "4"),
        (5, "5"),
        (6, "6"),
        (7, "7"),
        (8, "8"),
        (9, "9"),
        (10, "A"),
        (11, "B"),
        (12, "C"),
        (13, "D"),
        (14, "E"),
        (15, "F"),
        (16, "G"),
        (17, "H"),
        (18, "I"),
        (19, "J"),
        (20, "K"),
        (21, "L"),
        (22, "M"),
        (23, "N"),
        (24, "O"),
        (25, "P"),
        (26, "Q"),
        (27, "R"),
        (28, "S"),
        (29, "T"),
        (30, "U"),
        (31, "V"),
        (32, "W"),
        (33, "X"),
        (34, "Y"),
        (35, "Z"),
    ]);
    lookup.get(&base36).unwrap_or(&"")
}

/// Convert a base36 character to a u8
pub fn char_to_base36(chr: &char) -> u8 {
    let chr_string = chr.to_string().to_uppercase();
    let chr = chr_string.as_str();
    let lookup: HashMap<&str, u8> = HashMap::from([
        ("0", 0),
        ("1", 1),
        ("2", 2),
        ("3", 3),
        ("4", 4),
        ("5", 5),
        ("6", 6),
        ("7", 7),
        ("8", 8),
        ("9", 9),
        ("A", 10),
        ("B", 11),
        ("C", 12),
        ("D", 13),
        ("E", 14),
        ("F", 15),
        ("G", 16),
        ("H", 17),
        ("I", 18),
        ("J", 19),
        ("K", 20),
        ("L", 21),
        ("M", 22),
        ("N", 23),
        ("O", 24),
        ("P", 25),
        ("Q", 26),
        ("R", 27),
        ("S", 28),
        ("T", 29),
        ("U", 30),
        ("V", 31),
        ("W", 32),
        ("X", 33),
        ("Y", 34),
        ("Z", 35),
    ]);
    *lookup.get(&chr).unwrap_or(&0)
}
