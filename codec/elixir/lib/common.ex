defmodule DcgCodec.Common do
  def version, do: 5
  def prefix, do: "DCG"

  def compute_checksum(deck_bytes, total_card_bytes) do
    bytes = binary_part(deck_bytes, 0, total_card_bytes)

    for(<<b <- bytes>>, do: b)
    |> Enum.sum()
    |> Bitwise.band(0xFF)
  end
end

defmodule DcgCodec.Language do
  @language_map %{"ja" => 0, "en" => 1, "zh" => 2, "ko" => 3}

  def to_int(i) do
    Map.get(@language_map, i, 1)
  end

  def from_int(l) do
    language_map = Map.new(@language_map, fn {key, val} -> {val, key} end)
    Map.get(language_map, l, "en")
  end
end
