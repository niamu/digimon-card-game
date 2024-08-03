import gleam/option.{type Option, None, Some}

pub type Language {
  Japanese
  English
  Chinese
  Korean
}

pub fn deserialize(l: Int) -> Option(Language) {
  case l {
    0 -> Some(Japanese)
    1 -> Some(English)
    2 -> Some(Chinese)
    3 -> Some(Korean)
    _ -> None
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
