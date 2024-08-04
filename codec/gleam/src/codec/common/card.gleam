import gleam/dynamic
import gleam/json.{type Json}
import gleam/list
import gleam/option.{type Option}

pub type Card {
  Card(number: String, parallel_id: Option(Int), count: Int)
}

pub fn serialize(card: Card) -> Json {
  let params = [
    #("number", json.string(card.number)),
    #("count", json.int(card.count)),
  ]
  let parallel_id = option.unwrap(card.parallel_id, 0)
  let params = case parallel_id != 0 {
    True -> list.append(params, [#("parallel-id", json.int(parallel_id))])
    False -> params
  }

  params
  |> json.object
}

pub fn deserialize(
  card: dynamic.Dynamic,
) -> Result(Card, List(dynamic.DecodeError)) {
  card
  |> dynamic.decode3(
    Card,
    dynamic.field("number", of: dynamic.string),
    dynamic.optional_field("parallel-id", of: dynamic.int),
    dynamic.field("count", of: dynamic.int),
  )
}
