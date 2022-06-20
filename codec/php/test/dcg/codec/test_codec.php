<?php

require "src/dcg/codec/decode.php";
require "src/dcg/codec/encode.php";

$testV1EncodedDeck = "DCGEoozi50CgQMBnJ0BQQABi50BhAAJAwoBAQExBIudAoEDEAGLnQOGAwgDBQIDAQIDAQIVA5ydAUYDAgMBAgMAAQIgAQlEaWdpIEJyb3M6IFJhZ25hbG9hcmRtb24gUmVkICh5b3V0dS5iZS9vMEtvVzJ3d2hSNCk";

$decodedDeck = DCGDeckDecoder::Decode($testV1EncodedDeck);
$encodedDeck = DCGDeckEncoder::Encode($decodedDeck);

assert($testV1EncodedDeck == $encodedDeck);
