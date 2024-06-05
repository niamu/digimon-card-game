package codec

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// Version of the codec
const VERSION uint8 = 5

// Deck codes are all prefixed with "DCG"
const PREFIX = "DCG"

const HEADER_SIZE int = 3

type Language uint8

const (
	Japanese Language = iota + 1
	English
	Chinese
	Korean
)

var (
	Language_name = map[uint8]string{
		1: "ja",
		2: "en",
		3: "zh",
		4: "ko",
	}
	Language_value = map[string]uint8{
		"ja": 1,
		"en": 2,
		"zh": 3,
		"ko": 4,
	}

	Base36ToChar = map[uint8]string{
		0:  "0",
		1:  "1",
		2:  "2",
		3:  "3",
		4:  "4",
		5:  "5",
		6:  "6",
		7:  "7",
		8:  "8",
		9:  "9",
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

	CharToBase36 = map[string]uint8{
		"0": 0,
		"1": 1,
		"2": 2,
		"3": 3,
		"4": 4,
		"5": 5,
		"6": 6,
		"7": 7,
		"8": 8,
		"9": 9,
		"A": 10,
		"B": 11,
		"C": 12,
		"D": 13,
		"E": 14,
		"F": 15,
		"G": 16,
		"H": 17,
		"I": 18,
		"J": 19,
		"K": 20,
		"L": 21,
		"M": 22,
		"N": 23,
		"O": 24,
		"P": 25,
		"Q": 26,
		"R": 27,
		"S": 28,
		"T": 29,
		"U": 30,
		"V": 31,
		"W": 32,
		"X": 33,
		"Y": 34,
		"Z": 35,
	}
)

func (l Language) String() string {
	return Language_name[uint8(l)]
}

func ParseLanguage(l string) (Language, error) {
	l = strings.TrimSpace(strings.ToLower(l))
	value, ok := Language_value[l]
	if !ok {
		return Language(0), fmt.Errorf("%q is not a valid language", l)
	}
	return Language(value), nil
}

func (l Language) MarshalJSON() ([]byte, error) {
	return json.Marshal(l.String())
}

func (l *Language) UnmarshalJSON(data []byte) (err error) {
	var language string
	if err := json.Unmarshal(data, &language); err != nil {
		return err
	}
	if *l, err = ParseLanguage(language); err != nil {
		return err
	}
	return nil
}

func ComputeChecksum(total_card_bytes int, buffer []uint8) uint8 {
	b := bytes.NewReader(buffer[:total_card_bytes])
	var sum int = 0
	var idx int = 0
	for b.Len() > 0 {
		x, _ := b.ReadByte()
		sum += int(x)
		idx += 1
	}
	return byte(sum & 0xFF)
}

type Card struct {
	Number     string `json:"number"`
	ParallelId uint8  `json:"parallel-id,omitempty"`
	Count      uint8  `json:"count"`
}

type Deck struct {
	DigiEggs  []Card   `json:"digi-eggs"`
	Deck      []Card   `json:"deck"`
	Sideboard []Card   `json:"sideboard,omitempty"`
	Icon      string   `json:"icon,omitempty"`
	Language  Language `json:"language,omitempty"`
	Name      string   `json:"name"`
}
