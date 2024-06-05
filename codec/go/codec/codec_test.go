package codec

import (
	"testing"
)

func roundtrip_with_version(deck_code string, version uint8) (bool, error) {
	decoded_deck, decode_err := Decode(deck_code)
	if decode_err != nil {
		return false, decode_err
	}
	encoded_deck, encode_err := Encode(decoded_deck, version)
	if encode_err != nil {
		return false, encode_err
	}
	return encoded_deck == deck_code, nil
}

func TestCodecRoundTrip(t *testing.T) {
	// v0
	digi_bros_deck_encoded := "DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp"
	// v1
	st1_deck_encoded := "DCGETsdnJ0BQQMBnJ0BTwMCAwEDAQMBAwEBAQMBAwEBAQEBAwEDAQMBAQEBAVN0YXJ0ZXIgRGVjaywgR2FpYSBSZWQgW1NULTFd"
	// v2
	deck_with_sideboard_encoded := "DCGIkA_B4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCV9fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw"
	// v3
	deck_with_sideboard_and_language_encoded := "DCGOkA_B4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCV9fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw"
	// v4
	deck_with_sideboard_and_language_and_icon_encoded := "DCGQsA_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw"
	// v5
	deck_with_sideboard_and_language_zh_and_icon_encoded := "DCGUsC_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw"
	// v5
	deck_with_sideboard_and_language_ko_and_icon_encoded := "DCGUsD_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw"

	var is_match bool
	var err error

	is_match, err = roundtrip_with_version(digi_bros_deck_encoded, 0)
	if !is_match || err != nil {
		t.Fatalf(`digi_bros_deck_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(st1_deck_encoded, 1)
	if !is_match || err != nil {
		t.Fatalf(`st1_deck_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(deck_with_sideboard_encoded, 2)
	if !is_match || err != nil {
		t.Fatalf(`deck_with_sideboard_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(deck_with_sideboard_and_language_encoded, 3)
	if !is_match || err != nil {
		t.Fatalf(`deck_with_sideboard_and_language_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(deck_with_sideboard_and_language_and_icon_encoded, 4)
	if !is_match || err != nil {
		t.Fatalf(`deck_with_sideboard_and_language_and_icon_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(deck_with_sideboard_and_language_zh_and_icon_encoded, 5)
	if !is_match || err != nil {
		t.Fatalf(`deck_with_sideboard_and_language_zh_and_icon_encoded failed roundtrip: %v`, err)
	}

	is_match, err = roundtrip_with_version(deck_with_sideboard_and_language_ko_and_icon_encoded, 5)
	if !is_match || err != nil {
		t.Fatalf(`deck_with_sideboard_and_language_ko_and_icon_encoded failed roundtrip: %v`, err)
	}
}
