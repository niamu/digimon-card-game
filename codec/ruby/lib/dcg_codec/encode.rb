# frozen_string_literal: true

require 'base64'
require 'stringio'
require_relative 'common'

module DCGCodec
  # DCG Codec Encoder to handle encoding decks
  class Encoder
    def initialize(deck, version = DCGCodec::Common::VERSION)
      @digi_eggs = deck['digi-eggs']
      @deck = deck['deck']
      @sideboard = deck.fetch('sideboard', [])
      @language = deck['language']
      @icon = deck['icon']
      @name = deck['name']
      @version = version

      @deck_bytes = []
    end

    def encode
      language_number = DCGCodec::Common::LANGUAGE.invert.fetch(@language, 1)
      version_and_digi_egg_count = if @version >= 3 && @version <= 4
                                     @version << 4 | language_number << 3 | @digi_eggs.length & 0x0F
                                   else
                                     @version << 4 | @digi_eggs.length & 0x0F
                                   end

      @name = @icon.to_s.to_s.ljust(8, ' ') + @name if @version >= 4 && @icon

      @name = StringIO.new(@name, 'a+')
      @name.truncate(0x3F)
      @name = @name.string.strip

      name_length = StringIO.new(@name).size
      name_length = language_number << 6 | name_length if @version >= 5

      @deck_bytes.push(version_and_digi_egg_count)
      @deck_bytes.push(0) # checksum placeholder
      @deck_bytes.push(name_length)

      header_size = @deck_bytes.length

      if @version >= 2
        sideboard_size = (@sideboard || []).length
        sideboard_size |= 0x80 if @version >= 4 && @icon
        @deck_bytes.push(sideboard_size)
      end

      grouped_decks = []
      grouped_decks.concat(group_cards(@digi_eggs))
      grouped_decks.concat(group_cards(@deck))
      grouped_decks.concat(group_cards(@sideboard))

      grouped_decks.each do |x|
        # Encode card_set
        card_set = x[0]
        card_number_padding = x[1]
        grouped_cards = x[2]
        if @version.zero?
          # Use 4 characters/bytes to store card sets.
          @deck_bytes.concat(card_set.ljust(4, ' ').bytes)
        else
          # Encode each character of card-set in Base36.
          # Use 8th bit as continue bit. If 0, reached end.

          card_set.chars.each_with_index do |char, idx|
            base36_char = DCGCodec::Common.char_to_base36(char)
            base36_char |= 0x80 if idx + 1 < card_set.length
            @deck_bytes.push(base36_char)
          end
        end

        # 2 bits for card number zero padding (zero padding stored as 0 indexed)
        # 6 bits for initial count offset of cards in a card group
        if @version < 2
          @deck_bytes.push((card_number_padding - 1) << 6 | grouped_cards.length)
        else
          @deck_bytes.push((card_number_padding - 1) << 6 | _bits_with_carry(grouped_cards.length, 6))
          _append_rest_to_deck_bytes(grouped_cards.length, 6)
        end

        prev_card_number = 0
        grouped_cards.each do |card|
          _, n = card['number'].split('-')
          card_set_number = n.to_i
          card_number_offset = card_set_number - prev_card_number
          if @version.zero?
            # 2 bits for card count (1-4)
            # 3 bits for parallel id (0-7)
            # 3 bits for start of card number offset
            @deck_bytes.push((card['count'] - 1) << 6 | card.fetch('parallel-id',
                                                                   0) << 3 | _bits_with_carry(card_number_offset,
                                                                                              3))
            _append_rest_to_deck_bytes(card_number_offset, 3)
          else
            # 1 byte for card count (1-50 with BT6-085)
            # 3 bits for parallel id (0-7)
            # 5 bits for start of card number offset
            @deck_bytes.push(card['count'] - 1)
            @deck_bytes.push(card.fetch('parallel-id', 0) << 5 | _bits_with_carry(card_number_offset, 5))
            _append_rest_to_deck_bytes(card_number_offset, 5)
          end
          prev_card_number = card_set_number
        end
      end

      # Compute and store cards checksum (second byte in buffer)
      # Only store the first byte of checksum
      buffer = @deck_bytes.drop(header_size)
      computed_checksum = DCGCodec::Common.compute_checksum(buffer)
      @deck_bytes[1] = computed_checksum & 0xFF

      @deck_bytes.concat(@name.bytes)

      deck_b64_encoded = Base64.urlsafe_encode64(@deck_bytes.pack('C*'), padding: false)
      DCGCodec::Common::PREFIX + deck_b64_encoded
    end

    private

    def group_cards(cards)
      result = []

      cards.sort_by! { |card| [card['number'], card.fetch('parallel-id', 0)] }
      cards = cards.group_by do |card|
        card_set, n = card['number'].split('-')
        [card_set, n.length]
      end

      cards.each do |card_set_and_padding, x|
        card_set_and_padding.push(x)
        result.push(card_set_and_padding)
      end

      result
    end

    def _bits_with_carry(value, bits)
      limit_bit = 1 << (bits - 1)
      result = value & (limit_bit - 1)
      result |= limit_bit if value >= limit_bit
      result
    end

    def _append_rest_to_deck_bytes(value, already_written_bits)
      remaining_value = value >> (already_written_bits - 1)
      while remaining_value.positive?
        @deck_bytes.push(_bits_with_carry(remaining_value, 8))
        remaining_value >>= 7
      end
    end
  end
end
