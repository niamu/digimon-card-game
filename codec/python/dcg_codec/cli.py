import argparse
import json
from codec import VERSION
from codec.decode import decode
from codec.encode import encode

parser = argparse.ArgumentParser(
    prog="dcg_codec",
    description="Digimon Card Game (2020) deck codec",
)

group = parser.add_mutually_exclusive_group(required=True)
group.add_argument(
    "--encode", metavar="<deck>", type=json.loads, help="JSON deck string"
)
group.add_argument(
    "--decode",
    metavar="<deck-code>",
    type=str,
    help="deck code string starting with 'DCG'",
)

args = parser.parse_args()

if __name__ == "__main__":
    if args.encode:
        print(encode(args.encode, version=VERSION))
    if args.decode:
        print(json.dumps(decode(args.decode)))
