import gleam/json.{type Json}
import gleam/list

pub type Card {
  Card(number: String, parallel_id: Int, count: Int)
}

pub fn serialize(card: Card) -> Json {
  let params = [
    #("number", json.string(card.number)),
    #("count", json.int(card.count)),
  ]
  let params = case card.parallel_id != 0 {
    True -> list.append(params, [#("parallel-id", json.int(card.parallel_id))])
    False -> params
  }

  params
  |> json.object
}
