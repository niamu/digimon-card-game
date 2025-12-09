# Version of the codec
VERSION = 5

# Deck codes are all prefixed with "DCG"
PREFIX = "DCG"

LANGUAGE = {0: "ja", 1: "en", 2: "zh-Hans", 3: "ko"}


def compute_checksum(total_card_bytes, buffer):
    return sum(buffer) & 0xFF


BASE_36_LOOKUP = {
    0: "0",
    1: "1",
    2: "2",
    3: "3",
    4: "4",
    5: "5",
    6: "6",
    7: "7",
    8: "8",
    9: "9",
    10: "A",
    11: "B",
    12: "C",
    13: "D",
    14: "E",
    15: "F",
    16: "G",
    17: "H",
    18: "I",
    19: "J",
    20: "K",
    21: "L",
    22: "M",
    23: "N",
    24: "O",
    25: "P",
    26: "Q",
    27: "R",
    28: "S",
    29: "T",
    30: "U",
    31: "V",
    32: "W",
    33: "X",
    34: "Y",
    35: "Z",
}


def base36_to_char(base_36):
    return BASE_36_LOOKUP.get(base_36, "")


def char_to_base36(char):
    lookup = {v: k for k, v in BASE_36_LOOKUP.items()}
    return lookup.get(char, 0)
