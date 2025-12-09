export const VERSION: number = 5;
export const PREFIX: string = "DCG";

export enum Language {
  ja,
  en,
  "zh-Hans",
  ko,
}

export function computeChecksum(b: number[]): number {
  return (
    b.reduce(function (accl, x) {
      return accl + x;
    }) & 0xff
  );
}

export const base36ToChar: Map<number, string> = new Map<number, string>(
  Array.from({ length: 36 }, (_, i) => {
    let idx = i;
    if (i > 9) idx = i + 7;
    return [i, String.fromCharCode(48 + idx)];
  }),
);

export const charToBase36 = new Map<string, number>(
  Array.from(base36ToChar, (entry) => [entry[1], entry[0]]),
);

interface ICard {
  number: string;
  parallel_id?: number;
  count: number;
}

export class Card implements ICard {
  number: string;
  parallel_id?: number;
  count: number;

  constructor(number: string, parallel_id: number, count: number) {
    this.number = number;
    this.parallel_id = parallel_id;
    this.count = count;
  }

  toJSON() {
    let card = {
      number: this.number,
      "parallel-id": this.parallel_id,
      count: this.count,
    };
    if (this.parallel_id == 0) delete card["parallel-id"];
    return card;
  }

  static fromJSON(json: string | Object) {
    if (json instanceof String) json = JSON.parse(json as string);
    return new Card(json["number"], json["parallel-id"] ?? 0, json["count"]);
  }
}

interface IDeck {
  digi_eggs: Array<Card>;
  deck: Array<Card>;
  sideboard?: Array<Card>;
  icon?: string | null;
  language?: Language | null;
  name: string;
}

export class Deck implements IDeck {
  digi_eggs: Array<Card>;
  deck: Array<Card>;
  sideboard?: Array<Card>;
  icon?: string | null;
  language?: Language | null;
  name: string;

  constructor(
    digi_eggs: Array<Card>,
    deck: Array<Card>,
    sideboard: Array<Card>,
    icon: string | null,
    language: Language | null,
    name: string,
  ) {
    this.digi_eggs = digi_eggs;
    this.deck = deck;
    this.sideboard = sideboard;
    this.icon = icon;
    this.language = language;
    this.name = name;
  }

  toJSON() {
    let deck = {
      "digi-eggs": this.digi_eggs.map((c) => {
        if (c instanceof Card) {
          return c.toJSON();
        }
        return c;
      }),
      deck: this.deck.map((c) => {
        if (c instanceof Card) {
          return c.toJSON();
        }
        return c;
      }),
      sideboard:
        this?.sideboard?.map((c) => {
          if (c instanceof Card) {
            return c.toJSON();
          }
          return c;
        }) || [],
      icon: this.icon,
      language: Language[this.language] || Language[1],
      name: this.name,
    };
    if (deck?.sideboard?.length == 0) delete deck.sideboard;
    if (!deck.icon) delete deck.icon;
    if (!deck.language) delete deck.language;
    return deck;
  }

  static fromJSON(json: string | Object) {
    if (json instanceof String) {
      json = JSON.parse(json as string);
    }
    let digi_eggs = json["digi-eggs"] ?? [];
    let deck = json["deck"] ?? [];
    let sideboard = json["sideboard"] ?? [];
    let icon = json["icon"] ?? null;
    let language = json["language"] ?? Language[1];
    return new Deck(
      digi_eggs.map((c) => Card.fromJSON(c)),
      deck.map((c) => Card.fromJSON(c)),
      sideboard.map((c) => Card.fromJSON(c)),
      icon,
      language || Language[1],
      json["name"] ?? "",
    );
  }
}
