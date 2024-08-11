defmodule DcgCodec do
  def decode(deck_code) do
    DcgCodec.Decode.decode(deck_code)
  end

  def encode(deck, version \\ DcgCodec.Common.version()) do
    DcgCodec.Encode.encode(deck, version)
  end
end
