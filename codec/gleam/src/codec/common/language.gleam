import gleam/dynamic
import gleam/option.{type Option, None, Some}
import gleam/result

pub type Language {
  Japanese
  English
  Chinese
  Korean
}

pub fn from_int(l: Int) -> Option(Language) {
  case l {
    0 -> Some(Japanese)
    1 -> Some(English)
    2 -> Some(Chinese)
    3 -> Some(Korean)
    _ -> None
  }
}

pub fn to_int(l: Option(Language)) -> Int {
  case l {
    Some(Japanese) -> 0
    Some(English) -> 1
    Some(Chinese) -> 2
    Some(Korean) -> 3
    _ -> 1
  }
}

pub fn deserialize(
  language: dynamic.Dynamic,
) -> Result(Language, List(dynamic.DecodeError)) {
  use l <- result.try(dynamic.string(language))
  case l {
    "ja" -> Ok(Japanese)
    "en" -> Ok(English)
    "zh" -> Ok(Chinese)
    "ko" -> Ok(Korean)
    _ -> Ok(English)
  }
}

pub fn serialize(l: Option(Language)) -> String {
  case l {
    Some(Japanese) -> "ja"
    Some(English) -> "en"
    Some(Chinese) -> "zh"
    Some(Korean) -> "ko"
    _ -> ""
  }
}
