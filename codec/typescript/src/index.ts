import { VERSION, Deck } from "./codec/common";
import { encode as internal_encode } from "./codec/encode";
import { decode as internal_decode } from "./codec/decode";

export async function encode(deck: Object, version: number = VERSION): string {
  return await internal_encode(Deck.fromJSON(deck), version);
}

export function decode(deckCode: string) {
  return internal_decode(deckCode).toJSON();
}
