import io
from codec import PREFIX, VERSION, LANGUAGE, compute_checksum, char_to_base36
from base64 import urlsafe_b64encode
from itertools import groupby


def _bits_with_carry(value, bits):
    limit_bit = 1 << (bits - 1)
    result = value & (limit_bit - 1)
    if value >= limit_bit:
        result |= limit_bit
    return result


def _append_rest_to_deck_bytes(deck_bytes, value, already_written_bits):
    remaining_value = value >> (already_written_bits - 1)
    while remaining_value > 0:
        deck_bytes.write(bytes([_bits_with_carry(remaining_value, 8)]))
        remaining_value = remaining_value >> 7


def _group_cards(cards):
    result = []

    def set_and_padding(card):
        card_set, n = card["number"].split("-")
        return [card_set, len(n)]

    cards = sorted(cards, key=lambda c: (c["number"], c.get("parallel-id", 0)))
    cards = groupby(cards, set_and_padding)
    for card_set_and_padding, x in cards:
        card_set_and_padding.append(list(x))
        result.append(card_set_and_padding)
    return result


def encode(d, version=VERSION):
    """
    Encode public function that takes a Deck dict and encodes to a deck code string
    """
    deck_bytes = io.BytesIO()

    language = d.get("language", "en")
    digi_eggs = d.get("digi-eggs", [])
    deck = d.get("deck", [])
    sideboard = d.get("sideboard", [])
    icon = d.get("icon", None)
    name = d.get("name", "")

    language_number_by_language = {v: k for k, v in LANGUAGE.items()}
    language_number = language_number_by_language.get(language, 1)

    version_and_digi_egg_count = (
        (version << 4 | language_number << 3 | len(digi_eggs) & 0x0F)
        if 3 <= version and version <= 4
        else (version << 4 | len(digi_eggs) & 0x0F)
    )
    if version >= 4 and icon:
        name = icon.ljust(8, " ") + name
    deck_name_bytes = name.encode("UTF-8")[:0x3F]
    deck_name_length = len(deck_name_bytes.decode("UTF-8").strip())
    if version >= 5:
        deck_name_length = language_number << 6 | deck_name_length

    deck_bytes.write(bytes([version_and_digi_egg_count]))
    deck_bytes.write(bytes([0]))  # checksum placeholder
    deck_bytes.write(bytes([deck_name_length]))
    header_size = deck_bytes.tell()

    if version >= 2:
        sideboard_size = len(sideboard)
        if version >= 4 and icon:
            sideboard_size |= 0x80
        deck_bytes.write(bytes([sideboard_size]))

    grouped_decks = []
    grouped_decks += _group_cards(digi_eggs)
    grouped_decks += _group_cards(deck)
    grouped_decks += _group_cards(sideboard)
    for x in grouped_decks:
        # Encode card_set
        card_set, card_number_padding, grouped_cards = x[0], x[1], x[2]
        if version == 0:
            # Use 4 characters/bytes to store card sets.
            deck_bytes.write(card_set.ljust(4, " ").encode("UTF-8"))
        else:
            # Encode each character of card-set in Base36.
            # Use 8th bit as continue bit. If 0, reached end.
            for idx, char in enumerate(card_set):
                base36_char = char_to_base36(char)
                if idx + 1 < len(card_set):
                    base36_char |= 0x80
                deck_bytes.write(bytes([base36_char]))
        # 2 bits for card number zero padding (zero padding stored as 0 indexed)
        # 6 bits for initial count offset of cards in a card group
        if version < 2:
            deck_bytes.write(
                bytes([(card_number_padding - 1) << 6 | len(grouped_cards)])
            )
        else:
            deck_bytes.write(
                bytes(
                    [
                        (card_number_padding - 1) << 6
                        | _bits_with_carry(len(grouped_cards), 6)
                    ]
                )
            )
            _append_rest_to_deck_bytes(deck_bytes, len(grouped_cards), 6)
        prev_card_number = 0
        for card in grouped_cards:
            _, n = card["number"].split("-")
            card_set_number = int(n)
            card_number_offset = card_set_number - prev_card_number
            if version == 0:
                # 2 bits for card count (1-4)
                # 3 bits for parallel id (0-7)
                # 3 bits for start of card number offset
                deck_bytes.write(
                    bytes(
                        [
                            (card["count"] - 1) << 6
                            | card.get("parallel-id", 0) << 3
                            | _bits_with_carry(card_number_offset, 3)
                        ]
                    )
                )
                _append_rest_to_deck_bytes(deck_bytes, card_number_offset, 3)
            else:
                # 1 byte for card count (1-50 with BT6-085)
                # 3 bits for parallel id (0-7)
                # 5 bits for start of card number offset
                deck_bytes.write(bytes([(card["count"] - 1)]))
                deck_bytes.write(
                    bytes(
                        [
                            card.get("parallel-id", 0) << 5
                            | _bits_with_carry(card_number_offset, 5)
                        ]
                    )
                )
                _append_rest_to_deck_bytes(deck_bytes, card_number_offset, 5)
            prev_card_number = card_set_number

    # Compute and store cards checksum (second byte in buffer)
    # Only store the first byte of checksum
    total_card_bytes = len(deck_bytes.getbuffer()) - header_size
    computed_checksum = compute_checksum(
        total_card_bytes,
        deck_bytes.getbuffer()[header_size : total_card_bytes + header_size],
    )

    deck_bytes.write(name.encode("UTF-8"))
    buffer = deck_bytes.getbuffer()
    buffer[1] = computed_checksum & 0xFF

    deck_b64_encoded = urlsafe_b64encode(buffer).strip(b"=").decode("UTF-8")
    deck_string = PREFIX + deck_b64_encoded
    return deck_string
