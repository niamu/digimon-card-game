//! Digimon Card Game 2020 deck codec CLI interface

#![deny(missing_docs)]

mod codec;

pub use crate::codec::{decode, encode, Deck, VERSION};
use serde_json;
use structopt::StructOpt;

/// Digimon Card Game 2020 deck codec
#[derive(StructOpt)]
struct Cli {
    #[structopt(long = "decode", conflicts_with = "deck")]
    deck_code_str: Option<String>,

    #[structopt(long = "encode", conflicts_with = "deck-code-str")]
    deck: Option<String>,
}

fn main() {
    let args = Cli::from_args();

    if let Some(deck_code_str) = &args.deck_code_str {
        println!(
            "{}",
            serde_json::to_string(&codec::decode(deck_code_str)).unwrap()
        );
    }

    if let Some(deck_str) = &args.deck {
        let deck: Deck = serde_json::from_str(deck_str).unwrap();
        println!("{:?}", codec::encode(deck, VERSION));
    }
}
