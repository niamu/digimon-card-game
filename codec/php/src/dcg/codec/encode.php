<?php

require_once "common.php";

class DCGDeckEncoder {
  private static function ExtractNBitsWithCarry($value, $numBits) {
    $unLimitBit = 1 << $numBits;
    $unResult = ($value & ($unLimitBit - 1));
    if ($value >= $unLimitBit) {
      $unResult |= $unLimitBit;
    }

    return $unResult;
  }

  private static function AddByte(&$bytes, $byte) {
    if ($byte > 255)
      throw new Exception("Byte value out of range.");

    array_push($bytes, $byte);
    return true;
  }

  private static function AddRemainingNumberToBuffer(
    $unValue,
    $unAlreadyWrittenBits,
    &$bytes
  ) {
    $unValue >>= $unAlreadyWrittenBits;
    $unNumBytes = 0;
    while ($unValue > 0) {
      $unNextByte = DCGDeckEncoder::ExtractNBitsWithCarry($unValue, 7);
      $unValue >>= 7;
      DCGDeckEncoder::AddByte($bytes, $unNextByte);

      $unNumBytes++;
    }
    return true;
  }

  private static function ComputeChecksum(&$bytes, $unNumBytes) {
    $unChecksum = 0;
    for ($i = HEADER_SIZE; $i < $unNumBytes + HEADER_SIZE; $i++) {
      $byte = $bytes[$i];
      $unChecksum += $byte;
    }
    return $unChecksum;
  }

  private static function EncodeBytes($deckContents) {
    if (
      !isset($deckContents) ||
      !isset($deckContents["digi-eggs"]) ||
      !isset($deckContents["deck"])
    )
      throw new Exception(
        "Invalid deck contents. Requires 'digi-eggs' and 'deck' keys."
      );

    $digiEggs = $deckContents["digi-eggs"];
    $deck = $deckContents["deck"];

    // Confirm Digi-Egg deck sums to 5 or fewer cards
    $sum = 0;
    foreach($digiEggs as $c) {
      $sum += $c["count"];
    }
    if ($sum < 0 || $sum > 5)
      throw new Exception("'digi-eggs' card count must be between 0 and 5.");

    // Confirm main Deck sums to exactly 50
    $sum = 0;
    foreach($deck as $c) {
      $sum += $c["count"];
    }
    if ($sum != 50)
      throw new Exception("'deck' card count must sum to exactly 50.");

    {
      $cardNumber  = array_column($digiEggs, "number");
      $cardParallelId = array_column($digiEggs, "parallel-id");
      array_multisort(
        $cardNumber, SORT_ASC,
        $cardParallelId, SORT_ASC,
        $digiEggs
      );

      $cardNumber  = array_column($deck, "number");
      $cardParallelId = array_column($deck, "parallel-id");
      array_multisort(
        $cardNumber, SORT_ASC,
        $cardParallelId, SORT_ASC,
        $deck
      );
    }

    $countDigiEggs = count($digiEggs);
    
    $groupedDigiEggs = array();
    foreach($digiEggs as $card) {
      if (!preg_match("/^[A-Z|0-9]{1,4}-[0-9]{2,3}$/", $card["number"]))
        throw new Exception("card 'number' does not match a known structure.");
      $cardNumberSplit = preg_split("/-/", $card["number"]);
      $groupedDigiEggs[serialize(
        array(
          "cardSet" => $cardNumberSplit[0],
          "pad" => strlen($cardNumberSplit[1])
        )
      )][] = $card;
    }

    $groupedDeck = array();
    foreach($deck as $card) {
      $cardNumberSplit = preg_split("/-/", $card["number"]);
      $groupedDeck[serialize(
        array(
          "cardSet" => $cardNumberSplit[0],
          "pad" => strlen($cardNumberSplit[1])
        )
      )][] = $card;
    }

    $bytes = array();
    $version = (VERSION << 4) | DCGDeckEncoder::ExtractNBitsWithCarry(
      $countDigiEggs, 3
    );
    DCGDeckEncoder::AddByte($bytes, $version);

    $nChecksumBytePos = 1;
    DCGDeckEncoder::AddByte($bytes, $nChecksumBytePos);

    // TODO: This doesn't support Kanji
    // (character length may not match byte length)
    $nameLen = 0;
    if (isset($deckContents["name"])) {
      $name = $deckContents["name"];
      $trimLen = strlen($name);
      while($trimLen > 63)
      {
        $amountToTrim = floor(($trimLen - 63) / 4);
        $amountToTrim = ($amountToTrim > 1) ? $amountToTrim : 1;
        $name = mb_substr($name, 0, mb_strlen($name) - $amountToTrim);
        $trimLen = strlen($name);
      }

      $nameLen = strlen($name);
    }

    DCGDeckEncoder::AddByte($bytes, $nameLen);

    foreach (array($groupedDigiEggs, $groupedDeck) as $d) {
      foreach ($d as $cardSetAndPad => $cards) {
        $cardSet = strtoupper(unserialize($cardSetAndPad)["cardSet"]);
        $pad = unserialize($cardSetAndPad)["pad"];

        $cardSetLength = strlen($cardSet);
        for ($charIndex = 0; $charIndex < $cardSetLength; $charIndex++) {
          $byte = char_to_base36($cardSet[$charIndex]);
          if ($charIndex != $cardSetLength - 1)
            $byte = $byte | 0x80;
          DCGDeckEncoder::AddByte($bytes, $byte);
        }

        DCGDeckEncoder::AddByte($bytes, ((($pad - 1) << 6)) | count($cards));

        $prevCardBase = 0;
        foreach ($cards as $card) {
          if ($card["count"] == 0 || $card["count"] > 50)
            throw new Exception("card 'count' cannot be 0 or greater than 50.");

          $cardNumber = intval(preg_split("/-/", $card["number"])[1], 10);
          if ($cardNumber <= 0)
            throw new Exception("card 'number' value cannot be 0 or less.");

          DCGDeckEncoder::AddByte($bytes, $card["count"] - 1);

          $cardNumberOffset = ($cardNumber - $prevCardBase);
          $pIdAndOffset = (
            ($card["parallel-id"] << 5) | 
            DCGDeckEncoder::ExtractNBitsWithCarry($cardNumberOffset, 4)
          );
          DCGDeckEncoder::AddByte($bytes, $pIdAndOffset);
          DCGDeckEncoder::AddRemainingNumberToBuffer(
            $cardNumberOffset, 4, $bytes
          );

          $prevCardBase = $cardNumber;
        }
      }
    }

    // Checksum
    $unFullChecksum = DCGDeckEncoder::ComputeChecksum(
      $bytes, count($bytes) - HEADER_SIZE
    );
    $unSmallChecksum = ($unFullChecksum & 0x0FF);
    $bytes[$nChecksumBytePos] = $unSmallChecksum;

    // Deck Name
    {
      $nameBytes = unpack("C*", $name);
      foreach($nameBytes as $nameByte) {
        DCGDeckEncoder::AddByte($bytes, $nameByte);
      }
    }

    return $bytes;
  }

  private static function EncodeBytesToString($bytes) {
    $byteCount = count($bytes);
    if ($byteCount == 0)
      throw new Exception("Cannot encode byte count of 0 to string.");

    $packed = pack("C*", ...$bytes);
    $encoded = base64url_encode($packed);

    return PREFIX . $encoded;
  }

  public static function Encode($deckContents) {
    if (!$deckContents)
      throw new Exception("No deck contents to encode");

    $bytes = DCGDeckEncoder::EncodeBytes($deckContents);
    if (!$bytes)
      throw new Exception("No bytes to encode");
    $deck_code = DCGDeckEncoder::EncodeBytesToString($bytes);
    return $deck_code;
  }
}
