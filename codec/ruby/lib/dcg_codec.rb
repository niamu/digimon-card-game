# frozen_string_literal: true

require_relative 'dcg_codec/cli'
require_relative 'dcg_codec/decode'
require_relative 'dcg_codec/encode'

# DCGCodec module with encoder and decoder for Digimon Card Game (2020) deck codes
module DCGCodec
end

if $PROGRAM_NAME == __FILE__
  cli = DCGCodec::CLI.new
  cli.parse(ARGV)
end
