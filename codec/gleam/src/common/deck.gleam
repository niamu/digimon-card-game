import common/card.{type Card}
import common/language.{type Language}
import gleam/json
import gleam/list
import gleam/option.{type Option}

pub type Deck {
  Deck(
    digi_eggs: List(Card),
    deck: List(Card),
    sideboard: List(Card),
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
  let params = case list.length(deck.sideboard) > 0 {
    True ->
      list.append(params, [
        #(
          "sideboard",
          json.preprocessed_array(list.map(deck.sideboard, card.serialize)),
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
