defmodule DcgCodec.Encode do
  def encode(d, version \\ DcgCodec.Common.version()) do
    language_number = Map.get(d, "language") |> DcgCodec.Language.to_int()

    digi_eggs = Map.get(d, "digi-eggs", [])

    version_and_digi_egg_count =
      if 3 <= version && version <= 4 do
        Bitwise.bsl(version, 4)
        |> Bitwise.bor(Bitwise.bsl(language_number, 3))
        |> Bitwise.bor(Bitwise.band(length(digi_eggs), 0x0F))
      else
        Bitwise.bsl(version, 4)
        |> Bitwise.bor(Bitwise.band(length(digi_eggs), 0x0F))
      end

    icon = Map.get(d, "icon")
    deck_name = Map.get(d, "name", "")

    deck_name =
      if version >= 4 && icon do
        (icon
         |> String.slice(0, 8)
         |> String.pad_trailing(8, " ")) <> deck_name
      else
        deck_name
      end
      |> String.trim()

    deck_name_bytes = deck_name |> binary_part(0, min(0x3F, byte_size(deck_name)))

    deck_name_length_byte =
      if version >= 5 do
        Bitwise.bsl(language_number, 6)
        |> Bitwise.bor(byte_size(deck_name_bytes))
      else
        byte_size(deck_name_bytes)
      end

    deck_bytes = <<>> <> <<deck_name_length_byte>>

    sideboard = Map.get(d, "sideboard", [])

    deck_bytes =
      if version >= 2 do
        sideboard_count = length(sideboard)

        sideboard_count =
          if version >= 4 && icon do
            Bitwise.bor(sideboard_count, 0x80)
          else
            sideboard_count
          end

        deck_bytes <> <<sideboard_count>>
      else
        deck_bytes
      end

    deck = Map.get(d, "deck", [])

    deck_bytes =
      []
      |> Enum.concat(group_cards(digi_eggs))
      |> Enum.concat(group_cards(deck))
      |> Enum.concat(group_cards(sideboard))
      |> List.foldl(deck_bytes, fn {{card_set, card_number_padding}, cards}, acc ->
        deck_bytes =
          case version do
            0 ->
              acc <> String.pad_trailing(card_set, 4, " ")

            _ ->
              card_set_chars = String.graphemes(card_set)

              card_set_bytes =
                List.foldl(card_set_chars, <<>>, fn char, acc2 ->
                  {base_36_char, _} = Integer.parse(char, 36)

                  result =
                    if byte_size(acc2) + 1 < length(card_set_chars) do
                      Bitwise.bor(base_36_char, 0x80)
                    else
                      base_36_char
                    end

                  acc2 <> <<result>>
                end)

              acc <> card_set_bytes
          end

        deck_bytes =
          if version < 2 do
            x =
              Bitwise.bsl(card_number_padding - 1, 6)
              |> Bitwise.bor(length(cards))

            deck_bytes <> <<x>>
          else
            x =
              Bitwise.bsl(card_number_padding - 1, 6)
              |> Bitwise.bor(bits_with_carry(length(cards), 6))

            (deck_bytes <> <<x>>)
            |> append_rest_to_deck_bytes(length(cards), 6)
          end

        prev_card_number = 0

        {deck_bytes, _} =
          List.foldl(cards, {deck_bytes, prev_card_number}, fn card, acc ->
            card_count = Map.get(card, "count")
            card_number = Map.get(card, "number")
            {deck_bytes, prev_card_number} = acc
            [_, card_set_number] = String.split(card_number, "-")
            {card_set_number, _} = Integer.parse(card_set_number, 10)
            card_number_offset = card_set_number - prev_card_number
            prev_card_number = card_set_number
            parallel_id = Map.get(card, "parallel-id", 0)

            deck_bytes =
              if version == 0 do
                x =
                  Bitwise.bsl(card_count - 1, 6)
                  |> Bitwise.bor(Bitwise.bsl(parallel_id, 3))
                  |> Bitwise.bor(bits_with_carry(card_number_offset, 3))

                (deck_bytes <> <<x>>)
                |> append_rest_to_deck_bytes(card_number_offset, 3)
              else
                card_number_offset_start =
                  Bitwise.bor(Bitwise.bsl(parallel_id, 5), bits_with_carry(card_number_offset, 5))

                (deck_bytes <> <<card_count - 1>> <> <<card_number_offset_start>>)
                |> append_rest_to_deck_bytes(card_number_offset, 5)
              end

            {deck_bytes, prev_card_number}
          end)

        deck_bytes
      end)

    bytes_to_checksum = binary_part(deck_bytes, 1, byte_size(deck_bytes) - 1)

    computed_checksum =
      DcgCodec.Common.compute_checksum(bytes_to_checksum, byte_size(bytes_to_checksum))

    checksum = <<computed_checksum>>
    deck_bytes = <<version_and_digi_egg_count>> <> checksum <> deck_bytes <> deck_name_bytes

    DcgCodec.Common.prefix() <> Base.url_encode64(deck_bytes, padding: false)
  end

  defp bits_with_carry(value, bits) do
    limit_bit = Bitwise.bsl(1, bits - 1)
    result = Bitwise.band(value, limit_bit - 1)

    if value >= limit_bit do
      Bitwise.bor(result, limit_bit)
    else
      result
    end
  end

  defp append_rest_to_deck_bytes(deck_bytes, value, already_written_bits) do
    remaining_value = Bitwise.bsr(value, already_written_bits - 1)

    if remaining_value > 0 do
      x = bits_with_carry(remaining_value, 8)
      append_rest_to_deck_bytes(deck_bytes <> <<x>>, remaining_value, 8)
    else
      deck_bytes
    end
  end

  defp group_cards(cards) do
    Enum.group_by(cards, fn card ->
      card_number = Map.get(card, "number", "")
      [card_set, card_set_number] = String.split(card_number, "-")
      {card_set, String.length(card_set_number)}
    end)
    |> Enum.to_list()
    |> List.foldl([], fn group, acc ->
      {k, v} = group

      v =
        Enum.sort_by(v, fn card ->
          [Map.get(card, "number"), Map.get(card, "parallel-id", 0)]
        end)

      Enum.concat(acc, [{k, v}])
    end)
    |> Enum.sort_by(fn {{card_set, _}, _} ->
      card_set
    end)
  end
end
