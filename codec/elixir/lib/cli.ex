defmodule DcgCodec.CLI do
  def main(args) do
    args |> parse_args |> process_args
  end

  def parse_args(args) do
    {params, _, _} = OptionParser.parse(args, switches: [help: :boolean])
    params
  end

  def process_args(help: true) do
    print_help_message()
  end

  def process_args(encode: deck) do
    :json.decode(deck)
    |> DcgCodec.Encode.encode()
    |> IO.puts()
  end

  def process_args(decode: deck_code) do
    deck = DcgCodec.Decode.decode(deck_code)
    json_deck = :json.encode(deck) |> to_string()
    IO.puts(json_deck)
  end

  def process_args(_) do
    print_help_message()
  end

  @options %{
    "--help" => "\t\t\tPrints this help information",
    "--decode" => "<deck-code>\t\tDecode a deck code into JSON",
    "--encode" => "<deck>\t\tEncode a JSON deck into a deck code"
  }

  defp print_help_message do
    app = :dcg_codec
    version = Application.spec(app, :vsn)
    IO.puts("#{app} #{version}")
    IO.puts("Digimon Card Game 2020 deck codec\n")
    IO.puts("USAGE:")
    IO.puts("  #{app} [OPTIONS]\n")
    IO.puts("OPTIONS:")

    @options
    |> Enum.map(fn {command, description} -> IO.puts("  #{command} #{description}") end)

    IO.puts("")
  end
end
