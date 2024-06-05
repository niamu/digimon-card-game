package codec

import (
	"bytes"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

func is_carry_bit(current_byte uint8, mask_bits uint8) bool {
	return 0 != current_byte&(1<<mask_bits)
}

func read_bits_from_byte(
	current_byte uint8,
	mask_bits uint8,
	delta_shift uint8,
	out_bits uint32,
) uint32 {
	return (uint32(current_byte&((1<<mask_bits)-1)) << delta_shift) | out_bits
}

func read_encoded_u32(
	base_value uint32,
	current_byte uint8,
	delta_shift uint8,
	b *bytes.Buffer,
) uint32 {
	if (delta_shift-1 == 0) || is_carry_bit(current_byte, delta_shift-1) {
		for {
			next_byte, _ := b.ReadByte()
			base_value = read_bits_from_byte(
				next_byte,
				uint8(0x0F)-1,
				delta_shift-1,
				base_value,
			)
			if !is_carry_bit(next_byte, uint8(0x0F)-1) {
				break
			}
			delta_shift += uint8(0x0F) - 1
		}
	} else {
		return read_bits_from_byte(current_byte, delta_shift-1, 0, 0)
	}

	return base_value
}

func deserialize_card(
	version uint8,
	b *bytes.Buffer,
	card_set string,
	card_set_padding uint8,
	prev_card_number *uint32,
) Card {
	current_byte, _ := b.ReadByte()
	var card_count uint8
	if version == 0 {
		card_count = (current_byte >> 6) + 1
	} else {
		card_count = current_byte + 1
	}
	if version != 0 {
		current_byte, _ = b.ReadByte()
	}
	var card_parallel_id uint8
	if version == 0 {
		card_parallel_id = current_byte >> 3 & 0x07
	} else {
		card_parallel_id = current_byte >> 5
	}

	var delta_shift uint8
	if version == 0 {
		delta_shift = 3
	} else {
		delta_shift = 5
	}
	card_number := read_bits_from_byte(current_byte, delta_shift-1, 0, 0)
	*prev_card_number += read_encoded_u32(
		card_number,
		current_byte,
		delta_shift,
		b,
	)

	return Card{
		Number: fmt.Sprintf(
			"%s-%0*d",
			card_set,
			card_set_padding,
			*prev_card_number,
		),
		ParallelId: card_parallel_id,
		Count:      card_count,
	}
}

func parseDeck(buffer []uint8) (Deck, error) {
	b := bytes.NewBuffer(buffer)
	version_and_digi_egg_count, _ := b.ReadByte()
	version := version_and_digi_egg_count >> 4
	if VERSION >= version {
		fmt.Sprintf("Deck version %d not supported", version)
	}
	digi_egg_set_count := version_and_digi_egg_count & 0x0F
	if 3 <= version && version <= 4 {
		digi_egg_set_count = version_and_digi_egg_count & 0x07
	}
	checksum, _ := b.ReadByte()
	deck_name_length, _ := b.ReadByte()
	var language_number uint8
	language_number = (version_and_digi_egg_count >> 3) & 0x01
	if version >= 5 {
		language_number = deck_name_length >> 6
	}
	language := Language(language_number + 1)
	if version < 3 {
		language = Language(0)
	}
	if version >= 5 {
		deck_name_length &= 0x3F
	}
	total_card_bytes := b.Len() - int(deck_name_length)

	computed_checksum := ComputeChecksum(total_card_bytes, b.Bytes())
	if checksum != computed_checksum {
		return Deck{}, errors.New(
			fmt.Sprintf(
				"Deck checksum failed: %d != %d",
				checksum,
				computed_checksum,
			),
		)
	}

	sideboard_count := byte(0)
	if version >= 2 {
		sideboard_count, _ = b.ReadByte()
	}
	has_icon := false
	if version >= 4 && (sideboard_count>>7) > 0 {
		has_icon = true
	}
	if version >= 4 {
		sideboard_count &= 0x7F
	}

	var cards []Card
	for b.Len() > int(deck_name_length) {
		// Card Set Header
		// - Card Set
		var card_set string
		if version == 0 {
			buf := make([]byte, 4)
			b.Read(buf)
			card_set = strings.TrimSpace(string(buf))
		} else {
			for {
				current_byte, _ := b.ReadByte()
				card_set += Base36ToChar[current_byte&0x3F]
				if current_byte>>7 == 0 {
					break
				}
			}
		}
		// - Card Set Zero Padding and Count
		padding_and_set_count, _ := b.ReadByte()
		card_set_padding := (padding_and_set_count >> 6) + 1
		card_set_count := padding_and_set_count & 0x3F
		if version >= 2 {
			read_encoded_u32(
				uint32(padding_and_set_count),
				padding_and_set_count,
				6,
				b,
			)
		}

		var prev_card_number uint32 = 0

		for range card_set_count {
			cards = append(
				cards,
				deserialize_card(
					version,
					b,
					card_set,
					card_set_padding,
					&prev_card_number,
				),
			)
		}
	}

	var icon string
	deck_name := string(b.Bytes())

	if has_icon {
		icon, deck_name = deck_name[:8], deck_name[8:]
		icon = strings.TrimSpace(icon)
		deck_name = strings.TrimSpace(deck_name)
	}

	return Deck{
		DigiEggs:  cards[:digi_egg_set_count],
		Deck:      cards[digi_egg_set_count : len(cards)-int(sideboard_count)],
		Sideboard: cards[len(cards)-int(sideboard_count):],
		Icon:      icon,
		Language:  language,
		Name:      deck_name,
	}, nil
}

func Decode(s string) (Deck, error) {
	prefix, s := s[:len(PREFIX)], s[len(PREFIX):]

	if prefix != PREFIX {
		return Deck{}, errors.New("Prefix was not 'DCG'")
	}

	buffer, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return Deck{}, err
	}

	return parseDeck(buffer)
}
