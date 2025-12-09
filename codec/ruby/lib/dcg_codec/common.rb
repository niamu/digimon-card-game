# frozen_string_literal: true

module DCGCodec
  # Constants and functions used in the encoder and decoder
  module Common
    # Version of the codec
    VERSION = 5

    # Deck codes are all prefixed with 'DCG'
    PREFIX = 'DCG'

    LANGUAGE = { 0 => 'ja', 1 => 'en', 2 => 'zh-Hans', 3 => 'ko' }.freeze

    def self.compute_checksum(buffer)
      buffer.sum & 0xFF
    end

    BASE_36_LOOKUP = {
      0 => '0',
      1 => '1',
      2 => '2',
      3 => '3',
      4 => '4',
      5 => '5',
      6 => '6',
      7 => '7',
      8 => '8',
      9 => '9',
      10 => 'A',
      11 => 'B',
      12 => 'C',
      13 => 'D',
      14 => 'E',
      15 => 'F',
      16 => 'G',
      17 => 'H',
      18 => 'I',
      19 => 'J',
      20 => 'K',
      21 => 'L',
      22 => 'M',
      23 => 'N',
      24 => 'O',
      25 => 'P',
      26 => 'Q',
      27 => 'R',
      28 => 'S',
      29 => 'T',
      30 => 'U',
      31 => 'V',
      32 => 'W',
      33 => 'X',
      34 => 'Y',
      35 => 'Z'
    }.freeze

    def self.base36_to_char(base36)
      BASE_36_LOOKUP.fetch(base36, '')
    end

    def self.char_to_base36(char)
      BASE_36_LOOKUP.invert.fetch(char, 0)
    end
  end
end
