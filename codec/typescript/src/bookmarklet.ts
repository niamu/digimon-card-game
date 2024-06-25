import { encode } from ".";

async function fetch_digi_eggs() {
  const cards_response = await fetch("https://digimoncard.dev/data8675309.php");
  const cards = await cards_response.json();
  return cards
    .filter(({ cardtype }) => cardtype == 1)
    .map(({ cardid }) => cardid);
}

async function digimoncard_dev() {
  if (!location.pathname.startsWith("/deckbuilder/")) {
    throw Error("Not on deck page");
  }

  async function fetch_deck() {
    const pubKey = location.pathname.replace("/deckbuilder/", "");
    let formData = new FormData();
    formData.append("m", 14);
    formData.append("pubKey", pubKey);
    const deck_response = await fetch(
      "https://digimoncard.dev/data8675309.php",
      {
        method: "POST",
        body: formData,
      },
    );
    return await deck_response.json();
  }

  const digi_eggs = await fetch_digi_eggs();
  let raw_deck = await fetch_deck();

  let raw_deck_data = JSON.parse(raw_deck[0].data).deck;
  raw_deck_data = raw_deck_data.reduce((accumulator, number) => {
    accumulator[number] = (accumulator[number] ?? 0) + 1;
    return accumulator;
  }, {});
  raw_deck_data = Object.entries(raw_deck_data).map(([number, count]) => {
    return { number, count };
  });
  let temp_deck = Object.groupBy(raw_deck_data, ({ number }) =>
    digi_eggs.includes(number),
  );
  return {
    "digi-eggs": temp_deck[true],
    deck: temp_deck[false],
    name: raw_deck[0].description + " by " + raw_deck[0].displayName,
  };
}

async function digimoncard_io() {
  if (!document.querySelector("#main_deck")) {
    throw Error("Not on deck page");
  }
  const digi_eggs = Array.from(document.querySelectorAll("#egg_deck a")).map(
    (el) => {
      const number = el.querySelector("img").dataset.card;
      const count = parseInt(
        el.querySelector(".card-count").textContent.replace("x", ""),
      );
      return {
        number,
        count,
      };
    },
  );
  const deck = Array.from(document.querySelectorAll("#main_deck a")).map(
    (el) => {
      const number = el.querySelector("img").dataset.card;
      const count = parseInt(
        el.querySelector(".card-count").textContent.replace("x", ""),
      );
      return {
        number,
        count,
      };
    },
  );
  const sideboard = Array.from(document.querySelectorAll("#side_deck a")).map(
    (el) => {
      const number = el.querySelector("img").dataset.card;
      const count = parseInt(
        el.querySelector(".card-count").textContent.replace("x", ""),
      );
      return {
        number,
        count,
      };
    },
  );
  const deck_name = document.querySelector(
    ".deck-metadata-container h1",
  ).textContent;
  const deck_author = document
    .querySelector(".deck-metadata-container h1 ~ .deck-metadata-info")
    .textContent.trim();
  const icon = document
    .querySelector(".deck-metadata-container")
    .style.cssText.match(/[A-Z0-9]+\-[0-9]+/)[0];

  return {
    "digi-eggs": digi_eggs,
    deck,
    sideboard,
    name: deck_name + " by " + deck_author,
    icon,
  };
}

async function digimoncard_app() {
  async function fetch_deck(data_url) {
    const deck_response = await fetch(data_url);
    return await deck_response.json();
  }

  const found = location.pathname.match(/\/deckbuilder\/(.*)/);
  if (!found) {
    throw Error("Not on deck page");
  }
  const data_url = "https://backend.digimoncard.app/api/decks/" + found[1];
  let raw_deck = await fetch_deck(data_url);
  const digi_eggs = await fetch_digi_eggs();

  let raw_deck_data = JSON.parse(raw_deck.cards).map(({ id, count }) => {
    return {
      number: id,
      count,
    };
  });
  const sideboard = JSON.parse(raw_deck.sideDeck).map(({ id, count }) => {
    return {
      number: id,
      count,
    };
  });
  const icon = raw_deck.imageCardId;
  let temp_deck = Object.groupBy(raw_deck_data, ({ number }) =>
    digi_eggs.includes(number),
  );
  return {
    "digi-eggs": temp_deck[true],
    deck: temp_deck[false],
    sideboard,
    icon,
    name: raw_deck.title + " by " + raw_deck.user,
  };
}

async function digimonmeta_com() {
  if (!document.querySelector("#media-gallery")) {
    throw Error("Not on deck page");
  }
  let tts = Array.from(document.querySelectorAll("#media-gallery div")).filter(
    (el) => {
      return el.textContent
        .trim()
        .startsWith('["Exported from digimonmeta.com",');
    },
  )[0];
  const text = document.querySelector("article .entry-content").textContent;
  const deck_name = text.match(/Deck Name:(.*)Author/)[1].trim();
  const deck_author = text.match(/Author:(.*)Date/)[1].trim();
  const digi_eggs = await fetch_digi_eggs();
  let cards = [];
  tts = JSON.parse(tts.textContent).slice(1);
  tts = tts
    .reduce((accl, number) => {
      accl.set(number, (accl.get(number) ?? 0) + 1);
      return accl;
    }, new Map())
    .forEach((count, number, _) => {
      cards.push({ number, count });
    });
  let temp_deck = Object.groupBy(cards, ({ number }) =>
    digi_eggs.includes(number),
  );
  return {
    "digi-eggs": temp_deck[true],
    deck: temp_deck[false],
    name: deck_name + " by " + deck_author,
  };
}

(async () => {
  switch (location.hostname) {
    case "digimoncard.dev":
      try {
        const deck = await digimoncard_dev();
        const encoded_deck = await encode(deck);
        console.log(encoded_deck);
      } catch (e) {
        console.error("Uh oh... ", e);
      }
      break;
    case "digimoncard.io":
      try {
        const deck = await digimoncard_io();
        const encoded_deck = await encode(deck);
        console.log(encoded_deck);
      } catch (e) {
        console.error("Uh oh... ", e);
      }
      break;
    case "digimoncard.app":
      try {
        const deck = await digimoncard_app();
        const encoded_deck = await encode(deck);
        console.log(encoded_deck);
      } catch (e) {
        console.error("Uh oh... ", e);
      }
      break;
    case "digimonmeta.com":
      try {
        const deck = await digimonmeta_com();
        const encoded_deck = await encode(deck);
        console.log(encoded_deck);
      } catch (e) {
        console.error("Uh oh... ", e);
      }
      break;
    default:
      console.error("Cannot detect deck");
  }
})();
