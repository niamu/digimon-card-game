# frozen_string_literal: true

require 'base64'
require_relative 'common'

module DCGCodec
  # DCG Codec Decoder to handle decoding deck code strings
  class Decoder
    def initialize(deck_code)
      @deck_code = deck_code
      @offset = 0
    end

    def decode
      unless @deck_code.start_with?(DCGCodec::Common::PREFIX)
        raise "DECK_CODE must start with #{DCGCodec::Common::PREFIX}"
      end

      @deck_code.slice!(0, DCGCodec::Common::PREFIX.length)
      @deck_bytes = IO::Buffer.for(Base64.urlsafe_decode64(@deck_code))

      parse_deck
    end

    private

    def read_u8
      v = @deck_bytes.get_value(:U8, @offset)
      @offset += 1
      v
    end

    def read_4_u8
      v = @deck_bytes.get_values(%i[U8 U8 U8 U8], @offset)
      @offset += 4
      v
    end

    def read_bits_from_byte(current_byte, mask_bits, delta_shift, out_bits)
      ((current_byte & ((1 << mask_bits) - 1)) << delta_shift) | out_bits
    end

    def carry_bit?(current_byte, mask_bits)
      !(current_byte & (1 << mask_bits)).zero?
    end

    def read_encoded_u32(base_value, current_byte, delta_shift)
      if (delta_shift - 1).zero? || carry_bit?(current_byte, delta_shift - 1)
        loop do
          next_byte = read_u8
          base_value = read_bits_from_byte(
            next_byte, 8 - 1, delta_shift - 1, base_value
          )
          break unless carry_bit?(next_byte, 8 - 1)

          delta_shift += 8 - 1
        end
      end
      unless (delta_shift - 1).zero? || carry_bit?(current_byte, delta_shift - 1)
        return read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)
      end

      base_value
    end

    def deserialize_card(version, card_set, card_set_padding, prev_card_number)
      current_byte = read_u8
      card_count = version.zero? ? (current_byte >> 6) + 1 : current_byte + 1
      current_byte = version.zero? ? current_byte : read_u8
      card_parallel_id = version.zero? ? current_byte >> 3 & 0x07 : current_byte >> 5
      delta_shift = version.zero? ? 3 : 5
      card_number = read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)
      prev_card_number += read_encoded_u32(card_number, current_byte, delta_shift)
      card = {
        'number' => "#{card_set}-#{prev_card_number.to_s.rjust(card_set_padding, '0')}",
        'count' => card_count
      }
      card.store('parallel-id', card_parallel_id) unless card_parallel_id.zero?
      [prev_card_number, card]
    end

    def parse_deck
      version_and_digi_egg_count = read_u8
      version = version_and_digi_egg_count >> 4
      raise "Deck version #{version} not supported" unless version <= DCGCodec::Common::VERSION

      digi_egg_set_count = version_and_digi_egg_count & (version >= 3 && version <= 4 ? 0x07 : 0x0F)
      checksum = read_u8
      deck_name_length = read_u8
      language_number = version >= 5 ? deck_name_length >> 6 : version_and_digi_egg_count >> 3 & 0x01
      deck_name_length &= 0x3F if version >= 5
      total_card_bytes = @deck_bytes.size - @offset - deck_name_length
      language = DCGCodec::Common::LANGUAGE.fetch(language_number, 'en')
      header_size = @offset

      computed_checksum = DCGCodec::Common.compute_checksum(
        @deck_bytes.values(:U8, header_size, total_card_bytes)
      )
      raise "Deck checksum failed. #{checksum} != #{computed_checksum}" unless checksum == computed_checksum

      sideboard_byte = version >= 2 ? read_u8 : 0
      has_icon = version >= 4 && (sideboard_byte >> 7).positive?
      sideboard_count = version >= 4 ? sideboard_byte & 0x7F : sideboard_byte

      cards = []

      loop do
        # Card Set Header
        # - Card Set
        card_set = ''
        if version.zero?
          card_set = read_4_u8.pack('C*')
        else
          card_set = card_set.dup
          loop do
            current_byte = read_u8
            card_set << DCGCodec::Common.base36_to_char(current_byte & 0x3F)
            break if (current_byte >> 7).zero?
          end
        end
        # - Card Set Zero Padding and Count
        padding_and_set_count = read_u8
        card_set_padding = (padding_and_set_count >> 6) + 1
        card_set_count = if version >= 2
                           read_encoded_u32(padding_and_set_count, padding_and_set_count, 6)
                         else
                           padding_and_set_count & 0x3F
                         end

        prev_card_number = 0
        (1..card_set_count).each do |_|
          prev_card_number, card = deserialize_card(version, card_set, card_set_padding, prev_card_number)
          cards.push(card)
        end

        break if @offset >= @deck_bytes.size - deck_name_length
      end

      deck_name = @deck_bytes.get_string(@offset, deck_name_length)
      deck = {
        'digi-eggs' => cards.take(digi_egg_set_count),
        'deck' => cards[digi_egg_set_count, cards.length - digi_egg_set_count - sideboard_count],
        'name' => deck_name
      }
      deck.store('language', language) if version >= 3
      if has_icon
        icon = deck_name[0, 8].strip
        deck_name = deck_name[8, deck_name.length - 8].strip
        deck.merge({ 'icon' => icon, 'name' => deck_name })
      end
      has_sideboard = version >= 2 && !sideboard_count.zero?
      deck.store('sideboard', cards[-sideboard_count, sideboard_count]) if has_sideboard
      deck
    end
  end
end
