import io
from codec import PREFIX, VERSION, LANGUAGE, compute_checksum, base36_to_char
from base64 import urlsafe_b64decode


def _get_u8(deck_bytes):
    return deck_bytes.read1(1)[0]


def _get_u32(deck_bytes):
    return deck_bytes.read1(4)


def _read_bits_from_byte(current_byte, mask_bits, delta_shift, out_bits):
    return ((current_byte & ((1 << mask_bits) - 1)) << delta_shift) | out_bits


def _is_carry_bit(current_byte, mask_bits):
    return 0 != current_byte & (1 << mask_bits)


def _read_encoded_u32(base_value, current_byte, delta_shift, deck_bytes):
    if delta_shift - 1 == 0 or _is_carry_bit(current_byte, delta_shift - 1):
        while True:
            next_byte = _get_u8(deck_bytes)
            base_value = _read_bits_from_byte(
                next_byte, 8 - 1, delta_shift - 1, base_value
            )
            if not _is_carry_bit(next_byte, 8 - 1):
                break
            delta_shift += 8 - 1
    else:
        return _read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)
    return base_value


def _deserialize_card(
    version, deck_bytes, card_set, card_set_padding, prev_card_number
):
    current_byte = _get_u8(deck_bytes)
    card_count = (current_byte >> 6) + 1 if version == 0 else current_byte + 1
    current_byte = current_byte if version == 0 else _get_u8(deck_bytes)
    card_parallel_id = current_byte >> 3 & 0x07 if version == 0 else current_byte >> 5
    delta_shift = 3 if version == 0 else 5
    card_number = _read_bits_from_byte(current_byte, delta_shift - 1, 0, 0)
    prev_card_number += _read_encoded_u32(
        card_number, current_byte, delta_shift, deck_bytes
    )
    card = {
        "number": f"{card_set}-{str(prev_card_number).zfill(card_set_padding)}",
        "count": card_count,
    }
    if card_parallel_id != 0:
        card |= {"parallel-id": card_parallel_id}
    return (prev_card_number, card)


def _parse_deck(deck_bytes):
    version_and_digi_egg_count = _get_u8(deck_bytes)
    version = version_and_digi_egg_count >> 4
    assert VERSION >= version, f"Deck version {version} not supported"
    digi_egg_set_count = version_and_digi_egg_count & (
        0x07 if (3 <= version and version <= 4) else 0x0F
    )
    checksum = _get_u8(deck_bytes)
    deck_name_length = _get_u8(deck_bytes)
    language_number = (
        deck_name_length >> 6
        if version >= 5
        else version_and_digi_egg_count >> 3 & 0x01
    )
    if version >= 5:
        deck_name_length &= 0x3F
    total_card_bytes = (
        len(deck_bytes.getbuffer()[deck_bytes.tell() :]) - deck_name_length
    )

    language = LANGUAGE.get(language_number, "en")

    header_size = deck_bytes.tell()

    computed_checksum = compute_checksum(
        total_card_bytes,
        deck_bytes.getbuffer()[header_size : total_card_bytes + header_size],
    )
    assert (
        checksum == computed_checksum
    ), f"Deck checksum failed. {checksum} != {computed_checksum}"

    sideboard_byte = _get_u8(deck_bytes) if version >= 2 else 0
    has_icon = version >= 4 and (sideboard_byte >> 7) > 0
    sideboard_count = sideboard_byte & 0x7F if version >= 4 else sideboard_byte

    cards = []

    while len(deck_bytes.getbuffer()[deck_bytes.tell() :]) > deck_name_length:
        # Card Set Header
        # - Card Set
        if version == 0:
            card_set = str(_get_u32(deck_bytes).decode("UTF-8")).strip()
        else:
            card_set = ""
            current_byte = _get_u8(deck_bytes)
            card_set += base36_to_char(current_byte & 0x3F)
            while current_byte >> 7 != 0:
                current_byte = _get_u8(deck_bytes)
                card_set += base36_to_char(current_byte & 0x3F)
        # - Card Set Zero Padding and Count
        padding_and_set_count = _get_u8(deck_bytes)
        card_set_padding = (padding_and_set_count >> 6) + 1
        card_set_count = (
            _read_encoded_u32(
                padding_and_set_count, padding_and_set_count, 6, deck_bytes
            )
            if version >= 2
            else padding_and_set_count & 0x3F
        )

        prev_card_number = 0
        for _ in range(card_set_count):
            prev_card_number, card = _deserialize_card(
                version, deck_bytes, card_set, card_set_padding, prev_card_number
            )
            cards.append(card)

    icon = None
    deck_name = (
        bytes(deck_bytes.getbuffer()[deck_bytes.tell() :]).decode("UTF-8").strip()
    )
    deck = {
        "digi-eggs": cards[:digi_egg_set_count],
        "deck": cards[digi_egg_set_count : (len(cards) - sideboard_count)],
        "name": deck_name,
    }
    if version >= 3:
        deck |= {"language": language}
    if has_icon:
        icon, deck_name = deck_name[:8], deck_name[8:].strip()
        deck |= {"icon": icon, "name": deck_name}
    has_sideboard = version >= 2 and sideboard_count != 0
    if has_sideboard:
        deck |= {"sideboard": cards[(len(cards) - sideboard_count) :]}
    return deck


def decode(s):
    """
    Decode public function that takes a deck code and decodes to a Deck dict
    """
    prefix, deck_code = s[: len(PREFIX)], s[len(PREFIX) :]
    assert prefix == PREFIX, "Prefix was not 'DCG'"
    deck_code_bytes = deck_code.encode("UTF-8")
    deck_bytes = urlsafe_b64decode(
        (deck_code_bytes + (b"=" * (4 - (len(deck_code_bytes) % 4))))
    )
    return _parse_deck(io.BytesIO(deck_bytes))
