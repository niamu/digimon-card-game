# frozen_string_literal: true

require 'base64'
require 'json'
require 'optparse'
require_relative 'common'
require_relative 'encode'
require_relative 'decode'

module DCGCodec
  # DCGCodec class to handle CLI arguments
  class CLI
    VERSION = '1.0.0'

    def encode_option(parser)
      parser.on('--encode DECK', String) do |deck|
        deck = JSON.parse(deck)
        encoder = DCGCodec::Encoder.new(deck, DCGCodec::Common::VERSION)
        deck_code = encoder.encode
        puts deck_code
        exit
      end
    end

    def decode_option(parser)
      parser.on('--decode DECK_CODE', String) do |deck_code|
        decoder = DCGCodec::Decoder.new(deck_code)
        deck = decoder.decode
        puts JSON.generate(deck)
        exit
      end
    end

    def help_option(parser)
      parser.on_tail('-h', '--help', 'Show this message') do
        puts parser
        exit
      end
    end

    def version_option(parser)
      parser.on_tail('-v', '--version') do
        puts VERSION
        exit
      end
    end

    def parse(args) # rubocop:disable Metrics/MethodLength
      OptionParser.new do |parser|
        parser.banner = 'Usage: dcg_codec.rb [options]'
        parser.separator ''

        encode_option(parser)
        decode_option(parser)

        parser.separator ''

        help_option(parser)
        version_option(parser)

        begin
          parser.parse!(args)
        rescue OptionParser::InvalidOption => e
          puts e
          puts
          puts parser
          exit(1)
        end
      end
    end
  end
end
