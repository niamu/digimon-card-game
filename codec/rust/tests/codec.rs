use dcg_codec::codec::{decode, encode};

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip_with_version(deck_code: &str, version: u8) {
        let decoded_deck = decode(deck_code);
        let encoded_deck = encode(decoded_deck, version);
        assert_eq!(deck_code, encoded_deck);
    }

    #[test]
    fn codec_roundtrip() {
        // v0
        let digi_bros_deck_encoded = "DCGApQzQlQyIIHBU1QxIEEBQlQxIIQFAsYCQU0QQlQyIIHEBEJUMyCGxALFAYNCwYUNU1QxIEbCwYMBiEUCRGlnaSBCcm9zOiBSYWduYWxvYXJkbW9uIFJlZCAoeW91dHUuYmUvbzBLb1cyd3doUjQp";
        // v1
        let st1_deck_encoded = "DCGETsdnJ0BQQMBnJ0BTwMCAwEDAQMBAwEBAQMBAwEBAQEBAwEDAQMBAQEBAVN0YXJ0ZXIgRGVjaywgR2FpYSBSZWQgW1NULTFd";
        // v2
        let deck_with_sideboard_encoded = "DCGIkA_B4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCV9fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw";
        // v3
        let deck_with_sideboard_and_language_encoded = "DCGOkA_B4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCV9fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw";
        // v4
        let deck_with_sideboard_and_language_and_icon_encoded = "DCGQsA_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw";
        // v5
        let deck_with_sideboard_and_language_zh_and_icon_encoded = "DCGUsC_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw";
        // v5
        let deck_with_sideboard_and_language_ko_and_icon_encoded = "DCGUsD_h4udAoEDAZydAUEAAYudAYQACQMKAQEBMQSLnQKBAxABi50DhQMIAwUCAwECAwGLnQOBAhgEnJ0BRgMCAwECAwABAiABCUJBQ0stMDAxX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fX19fXw";

        roundtrip_with_version(digi_bros_deck_encoded, 0);
        roundtrip_with_version(st1_deck_encoded, 1);
        roundtrip_with_version(deck_with_sideboard_encoded, 2);
        roundtrip_with_version(deck_with_sideboard_and_language_encoded, 3);
        roundtrip_with_version(
            deck_with_sideboard_and_language_and_icon_encoded,
            4,
        );
        roundtrip_with_version(
            deck_with_sideboard_and_language_zh_and_icon_encoded,
            5,
        );
        roundtrip_with_version(
            deck_with_sideboard_and_language_ko_and_icon_encoded,
            5,
        );
    }
}
