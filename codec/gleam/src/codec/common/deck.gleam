import codec/common/card.{type Card}
import codec/common/language.{type Language}
import gleam/dynamic
import gleam/json
import gleam/list
import gleam/option.{type Option}

pub type Deck {
  Deck(
    digi_eggs: List(Card),
    deck: List(Card),
    sideboard: Option(List(Card)),
    icon: Option(String),
    language: Option(Language),
    name: String,
  )
}

pub fn serialize(deck: Deck) -> String {
  let params = [
    #("name", json.string(deck.name)),
    #(
      "digi-eggs",
      json.preprocessed_array(list.map(deck.digi_eggs, card.serialize)),
    ),
    #("deck", json.preprocessed_array(list.map(deck.deck, card.serialize))),
  ]
  let sideboard = option.unwrap(deck.sideboard, [])
  let params = case list.length(sideboard) > 0 {
    True ->
      list.append(params, [
        #(
          "sideboard",
          json.preprocessed_array(list.map(sideboard, card.serialize)),
        ),
      ])
    False -> params
  }
  let params = case option.is_some(deck.icon) {
    True ->
      list.append(params, [#("icon", json.string(option.unwrap(deck.icon, "")))])
    False -> params
  }
  let params = case option.is_some(deck.language) {
    True ->
      list.append(params, [
        #("language", json.string(language.serialize(deck.language))),
      ])
    False -> params
  }

  params
  |> json.object
  |> json.to_string
}

pub fn deserialize(json_string: String) -> Deck {
  let deck_decoder =
    dynamic.decode6(
      Deck,
      dynamic.field("digi-eggs", of: dynamic.list(of: card.deserialize)),
      dynamic.field("deck", of: dynamic.list(of: card.deserialize)),
      dynamic.optional_field(
        "sideboard",
        of: dynamic.list(of: card.deserialize),
      ),
      dynamic.optional_field("icon", of: dynamic.string),
      dynamic.optional_field("language", of: language.deserialize),
      dynamic.field("name", of: dynamic.string),
    )

  let assert Ok(deck) = json.decode(from: json_string, using: deck_decoder)
  deck
}
