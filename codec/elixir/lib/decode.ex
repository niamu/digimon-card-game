defmodule DcgCodec.Decode do
  def decode(deck_code) do
    unless String.starts_with?(deck_code, DcgCodec.Common.prefix()) do
      exit("Prefix was not '#{DcgCodec.Common.prefix()}'")
    end

    String.replace_prefix(deck_code, DcgCodec.Common.prefix(), "")
    |> Base.url_decode64!(padding: false)
    |> parse_deck
  end

  defp read_byte(bytes) do
    case bytes do
      <<>> -> {nil, <<>>}
      <<b, bytes::binary>> -> {b, bytes}
    end
  end

  defp carry_bit?(current_byte, mask_bits) do
    Bitwise.bsl(1, mask_bits) |> Bitwise.band(current_byte) != 0
  end

  defp read_bits_from_byte(current_byte, mask_bits, shift_bits, base_value) do
    current_byte
    |> Bitwise.band(Bitwise.bsl(1, mask_bits) - 1)
    |> Bitwise.bsl(shift_bits)
    |> Bitwise.bor(base_value)
  end

  defp read_encoded_int_with_carry(deck_bytes, base_value, current_byte, shift_bits) do
    bits_in_a_byte = 8

    if carry_bit?(current_byte, shift_bits) do
      {next_byte, deck_bytes} = read_byte(deck_bytes)
      base_value = read_bits_from_byte(next_byte, bits_in_a_byte - 1, shift_bits, base_value)

      read_encoded_int_with_carry(
        deck_bytes,
        base_value,
        next_byte,
        shift_bits + bits_in_a_byte - 1
      )
    else
      {base_value, deck_bytes}
    end
  end

  defp read_encoded_int(deck_bytes, base_value, current_byte, shift_bits) do
    if shift_bits - 1 == 0 || carry_bit?(current_byte, shift_bits - 1) do
      read_encoded_int_with_carry(deck_bytes, base_value, current_byte, shift_bits - 1)
    else
      result = read_bits_from_byte(current_byte, shift_bits - 1, 0, 0)
      {result, deck_bytes}
    end
  end

  defp card_set(deck_bytes, version, card_set) do
    case version do
      0 ->
        <<card_set::binary-size(4), deck_bytes::binary>> = deck_bytes
        {card_set |> String.trim(), deck_bytes}

      _ ->
        {card_char, deck_bytes} = read_byte(deck_bytes)

        card_set =
          card_set <>
            (card_char
             |> Bitwise.band(0x3F)
             |> Integer.to_charlist(36)
             |> to_string)

        if Bitwise.bsr(card_char, 7) != 0 do
          card_set(deck_bytes, version, card_set)
        else
          {card_set, deck_bytes}
        end
    end
  end

  defp card_set_padding_and_count(deck_bytes, version) do
    {padding_and_count, deck_bytes} = read_byte(deck_bytes)
    card_set_padding = Bitwise.bsr(padding_and_count, 6) + 1

    if version < 2 do
      card_set_count = Bitwise.band(padding_and_count, 0x3F)
      {card_set_padding, card_set_count, deck_bytes}
    else
      {card_set_count, deck_bytes} =
        read_encoded_int(deck_bytes, padding_and_count, padding_and_count, 6)

      {card_set_padding, card_set_count, deck_bytes}
    end
  end

  defp parse_card_group(
         deck_bytes,
         version,
         prev_card_number,
         card_set,
         card_set_padding,
         card_set_count,
         cards
       ) do
    {current_byte, deck_bytes} = read_byte(deck_bytes)

    card_count =
      case version do
        0 -> Bitwise.bsr(current_byte, 6) + 1
        _ -> current_byte + 1
      end

    {current_byte, deck_bytes} =
      case version do
        0 -> {current_byte, deck_bytes}
        _ -> read_byte(deck_bytes)
      end

    parallel_id =
      case version do
        0 -> Bitwise.bsr(current_byte, 3) |> Bitwise.band(0x07)
        _ -> Bitwise.bsr(current_byte, 5)
      end

    delta_shift =
      case version do
        0 -> 3
        _ -> 5
      end

    current_card_number = read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)

    {card_number_offset, deck_bytes} =
      read_encoded_int(deck_bytes, current_card_number, current_byte, delta_shift)

    prev_card_number = prev_card_number + card_number_offset

    card_number =
      card_set <>
        "-" <> (to_string(prev_card_number) |> String.pad_leading(card_set_padding, "0"))

    card = %{
      "number" => card_number,
      "count" => card_count
    }

    card = if parallel_id != 0, do: Map.put_new(card, "parallel-id", parallel_id), else: card

    cards = Enum.concat(cards, [card])

    if length(cards) < card_set_count do
      parse_card_group(
        deck_bytes,
        version,
        prev_card_number,
        card_set,
        card_set_padding,
        card_set_count,
        cards
      )
    else
      {cards, deck_bytes}
    end
  end

  defp parse_cards(deck_bytes, version, cards) do
    if byte_size(deck_bytes) > 0 do
      {card_set, deck_bytes} = card_set(deck_bytes, version, "")

      {card_set_padding, card_set_count, deck_bytes} =
        card_set_padding_and_count(deck_bytes, version)

      {card_group, deck_bytes} =
        parse_card_group(
          deck_bytes,
          version,
          0,
          card_set,
          card_set_padding,
          card_set_count,
          []
        )

      cards = Enum.concat(cards, card_group)
      parse_cards(deck_bytes, version, cards)
    else
      cards
    end
  end

  defp parse_deck(deck_bytes) do
    {version_and_digi_egg_count, deck_bytes} = read_byte(deck_bytes)
    version = Bitwise.bsr(version_and_digi_egg_count, 4)

    if version > DcgCodec.Common.version() do
      exit("Deck version #{version} not supported")
    end

    digi_egg_set_count =
      if 3 <= version && version <= 4 do
        Bitwise.band(version_and_digi_egg_count, 0x07)
      else
        Bitwise.band(version_and_digi_egg_count, 0x0F)
      end

    {checksum, deck_bytes} = read_byte(deck_bytes)
    {deck_name_length_byte, deck_bytes} = read_byte(deck_bytes)

    deck_name_length =
      if version >= 5 do
        Bitwise.band(deck_name_length_byte, 0x3F)
      else
        deck_name_length_byte
      end

    total_card_bytes = byte_size(deck_bytes) - deck_name_length

    language_number =
      if version >= 5 do
        Bitwise.bsr(deck_name_length_byte, 6)
      else
        Bitwise.band(Bitwise.bsr(version_and_digi_egg_count, 3), 0x01)
      end

    language =
      if version >= 3 do
        DcgCodec.Language.from_int(language_number)
      else
        nil
      end

    computed_checksum = DcgCodec.Common.compute_checksum(deck_bytes, total_card_bytes)

    unless computed_checksum == checksum do
      exit("Deck checksum failed; #{checksum} != #{computed_checksum}")
    end

    {sideboard_count, deck_bytes} =
      if version >= 2 do
        read_byte(deck_bytes)
      else
        {0, deck_bytes}
      end

    icon? = version >= 4 && Bitwise.bsr(sideboard_count, 7) > 0

    sideboard_count =
      if version >= 4 do
        Bitwise.band(sideboard_count, 0x7F)
      else
        sideboard_count
      end

    card_bytes = binary_part(deck_bytes, 0, byte_size(deck_bytes) - deck_name_length)
    cards = parse_cards(card_bytes, version, [])
    {digi_eggs, cards} = Enum.split(cards, digi_egg_set_count)
    {deck, sideboard} = Enum.split(cards, length(cards) - sideboard_count)

    deck_name =
      binary_part(deck_bytes, byte_size(deck_bytes), deck_name_length * -1) |> to_string

    {icon, deck_name} =
      if icon? do
        String.split_at(deck_name, 8)
      else
        {nil, deck_name}
      end

    deck = %{
      "name" => deck_name,
      "digi-eggs" => digi_eggs,
      "deck" => deck
    }

    deck = if icon, do: Map.put_new(deck, "icon", icon |> String.trim()), else: deck
    deck = if language, do: Map.put_new(deck, "language", language), else: deck
    deck = if sideboard_count > 0, do: Map.put_new(deck, "sideboard", sideboard), else: deck

    deck
  end
end
