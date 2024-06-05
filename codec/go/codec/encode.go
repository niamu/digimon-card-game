package codec

import (
	"cmp"
	"encoding/base64"
	"fmt"
	"slices"
	"strconv"
	"strings"
)

type CardGroup struct {
	card_set string
	padding  uint8
	cards    []Card
}

func bits_with_carry(value uint32, bits uint8) uint8 {
	limit_bit := uint32(1 << (bits - 1))
	result := value & (limit_bit - 1)
	if value >= limit_bit {
		result |= limit_bit
	}
	return uint8(result)
}

func append_rest_to_deck_bytes(
	buffer *[]uint8,
	value uint32,
	already_written_bits uint8,
) {
	remaining_value := value >> (already_written_bits - 1)
	for remaining_value > 0 {
		*buffer = append(*buffer, bits_with_carry(remaining_value, 0x0F))
		remaining_value = remaining_value >> 7
	}
}

func group_cards(cards []Card) []CardGroup {
	slices.SortFunc(cards, func(a, b Card) int {
		return cmp.Or(
			cmp.Compare(a.Number, b.Number),
			cmp.Compare(a.ParallelId, b.ParallelId),
		)
	})
	var result []CardGroup
	var grouped_cards []Card
	var prev_card_set string = ""
	var prev_card_set_number_padding uint8 = 0
	for _, card := range cards {
		split_result := strings.Split(card.Number, "-")
		card_set := split_result[0]
		card_set_number_padding := uint8(len(split_result[1]))
		if prev_card_set == "" {
			prev_card_set = card_set
			prev_card_set_number_padding = card_set_number_padding
		}
		if prev_card_set == card_set && prev_card_set_number_padding == card_set_number_padding {
			grouped_cards = append(grouped_cards, card)
		} else {
			result = append(result, CardGroup{
				prev_card_set,
				prev_card_set_number_padding,
				grouped_cards,
			})
			grouped_cards = nil
			grouped_cards = append(grouped_cards, card)
		}
		prev_card_set = card_set
		prev_card_set_number_padding = card_set_number_padding
	}
	if len(grouped_cards) > 0 {
		result = append(result, CardGroup{
			prev_card_set,
			prev_card_set_number_padding,
			grouped_cards,
		})
	}
	return result
}

func Encode(deck Deck, version uint8) (string, error) {
	var buffer []uint8
	language_number := uint8(deck.Language)
	if language_number == 0 {
		language_number = uint8(English)
	}
	language_number -= 1
	var version_and_digi_egg_count uint8
	if 3 <= version && version <= 4 {
		version_and_digi_egg_count = version<<4 | language_number<<3 | uint8(len(deck.DigiEggs))&0x0F
	} else {
		version_and_digi_egg_count = version<<4 | uint8(len(deck.DigiEggs))
	}

	deck_name_bytes := []byte(deck.Name)
	if version >= 4 && deck.Icon != "" {
		deck_name_bytes = []byte(fmt.Sprintf("%-8s%s", deck.Icon, deck.Name))
	}
	if len(deck_name_bytes) > 0x3F {
		deck_name_bytes = deck_name_bytes[:0x3F]
	}
	name_length := uint8(len(deck_name_bytes))
	if version >= 5 {
		name_length = language_number<<6 | name_length
	}

	buffer = append(buffer, version_and_digi_egg_count)
	buffer = append(buffer, 0) // checksum placeholder
	buffer = append(buffer, name_length)

	if version >= 2 {
		sideboard_size := uint8(len(deck.Sideboard))
		if version >= 4 && deck.Icon != "" {
			sideboard_size |= 0x80
		}
		buffer = append(buffer, sideboard_size)
	}

	var grouped_decks []CardGroup
	grouped_decks = slices.Concat(grouped_decks, group_cards(deck.DigiEggs))
	grouped_decks = slices.Concat(grouped_decks, group_cards(deck.Deck))
	grouped_decks = slices.Concat(grouped_decks, group_cards(deck.Sideboard))

	for _, grouped_deck := range grouped_decks {
		// Encode card_set
		if version == 0 {
			// Use 4 characters/bytes to store card sets.
			card_set_bytes := []uint8(fmt.Sprintf("%-4s", grouped_deck.card_set))
			buffer = slices.Concat(buffer, card_set_bytes)
		} else {
			// Encode each character of card-set in Base36.
			// Use 8th bit as continue bit. If 0, reached end.
			for idx, chr := range grouped_deck.card_set {
				// fmt.Printf("%s", string(chr))
				base36_char := CharToBase36[string(chr)]
				if idx+1 < len(grouped_deck.card_set) {
					base36_char |= 0x80
				}
				buffer = append(buffer, base36_char)
			}
		}
		// 2 bits for card number zero padding (zero padding stored as 0 indexed)
		// 6 bits for initial count offset of cards in a card group
		if version < 2 {
			buffer = append(buffer, (grouped_deck.padding-1)<<6|uint8(len(grouped_deck.cards)))
		} else {
			buffer = append(buffer, (grouped_deck.padding-1)<<6|bits_with_carry(uint32(len(grouped_deck.cards)), 6))
			append_rest_to_deck_bytes(
				&buffer,
				uint32(len(grouped_deck.cards)),
				6,
			)
		}
		prev_card_number := uint32(0)
		for _, card := range grouped_deck.cards {
			split_result := strings.Split(card.Number, "-")
			card_set_number, _ := strconv.Atoi(split_result[1])
			card_number_offset := uint32(card_set_number) - prev_card_number
			if version == 0 {
				// 2 bits for card count (1-4)
				// 3 bits for parallel id (0-7)
				// 3 bits for start of card number offset
				buffer = append(buffer, (card.Count-1)<<6|card.ParallelId<<3|bits_with_carry(card_number_offset, 3))
				// rest of card number offset
				append_rest_to_deck_bytes(
					&buffer,
					card_number_offset,
					3,
				)
			} else {
				// TODO: Consider encoding card count with carry bit
				// so it takes less space when count is less than 5 bits?

				// 1 byte for card count (1-50 with BT6-085)
				// 3 bits for parallel id (0-7)
				// 5 bits for start of card number offset
				buffer = append(buffer, (card.Count - 1))
				buffer = append(buffer, card.ParallelId<<5|bits_with_carry(card_number_offset, 5))
				// rest of card number offset
				append_rest_to_deck_bytes(
					&buffer,
					card_number_offset,
					5,
				)
			}
			prev_card_number = uint32(card_set_number)
		}
	}
	// Compute and store cards checksum (second byte in buffer)
	// Only store the first byte of checksum
	total_card_bytes := len(buffer) - HEADER_SIZE
	computed_checksum := ComputeChecksum(total_card_bytes, buffer[HEADER_SIZE:])
	buffer[1] = (computed_checksum & 0xFF)

	buffer = slices.Concat(buffer, deck_name_bytes)

	deck_b64_encoded := base64.RawURLEncoding.EncodeToString(buffer)
	deck_string := PREFIX + deck_b64_encoded

	return deck_string, nil
}
