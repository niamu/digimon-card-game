<?php

require_once "common.php";

class DCGDeckDecoder {
  private static function ReadBitsChunk(
    $nChunk,
    $nNumBits,
    $nCurrShift,
    &$nOutBits
  ) {
    $nContinueBit = (1 << $nNumBits);
    $nNewBits = $nChunk & ($nContinueBit - 1);
    $nOutBits |= ($nNewBits << $nCurrShift);

    return ($nChunk & $nContinueBit) != 0;
  }

  private static function ReadVarEncodedUint32(
    $nBaseValue,
    $nBaseBits,
    $data,
    &$indexStart,
    $indexEnd,
    &$outValue
  ) {
    $outValue = 0;

    $nDeltaShift = 0;
    if (($nBaseBits == 0) || DCGDeckDecoder::ReadBitsChunk(
      $nBaseValue,
      $nBaseBits,
      $nDeltaShift,
      $outValue
    )) {
      $nDeltaShift += $nBaseBits;

      while (true) {
        if ($indexStart > $indexEnd) {
          throw new Exception("Attempted to read invalid byte index.");
        }

        $nNextByte = $data[$indexStart++];
        if (!DCGDeckDecoder::ReadBitsChunk(
          $nNextByte,
          7,
          $nDeltaShift,
          $outValue
        )) {
          break;
        }

        $nDeltaShift += 7;
      }
    }
    return true;
  }

  private static function ReadSerializedCardv0(
    $data,
    &$indexStart,
    $indexEnd,
    &$nPrevCardBase,
    &$nOutCount,
    &$nOutParallelID,
    &$nOutCardNumber
  ) {
    if($indexStart > $indexEnd) {
      throw new Exception("Attempted to read invalid byte index.");
    }

    $nHeader = $data[$indexStart++];

    $nCardDelta = 0;
    DCGDeckDecoder::ReadVarEncodedUint32(
      $nHeader,
      2,
      $data,
      $indexStart,
      $indexEnd,
      $nCardDelta
    );

    $nOutCardNumber = $nPrevCardBase + $nCardDelta;

    $nOutCount = ($nHeader >> 6) + 1;
    $nOutParallelID = ($nHeader >> 3) & 0x07;

    $nPrevCardBase = $nOutCardNumber;
    return true;
  }

  private static function ReadSerializedCardv1(
    $data,
    &$indexStart,
    $indexEnd,
    &$nPrevCardBase,
    &$nOutCount,
    &$nOutParallelID,
    &$nOutCardNumber
  ) {
    if($indexStart > $indexEnd) {
      throw new Exception("Attempted to read invalid byte index.");
    }

    $nOutCount = $data[$indexStart++] + 1;
    $nHeader = $data[$indexStart++];

    $nCardDelta = 0;
    DCGDeckDecoder::ReadVarEncodedUint32(
      $nHeader,
      4,
      $data,
      $indexStart,
      $indexEnd,
      $nCardDelta
    );

    $nOutCardNumber = $nPrevCardBase + $nCardDelta;
    $nOutParallelID = ($nHeader >> 5);
    $nPrevCardBase = $nOutCardNumber;
    return true;
  }

  private static function ParseDeck($deckCodeStr, $deckBytes) {
    $nCurrentByteIndex = 1;
    $nTotalBytes = count($deckBytes);

    $nVersionAndDigiEggCount = $deckBytes[$nCurrentByteIndex++];
    $version = $nVersionAndDigiEggCount >> 4;

    if($version > VERSION) {
      throw new Exception("Decoding unsupported version: {$version}");
    }

    $nChecksum = $deckBytes[$nCurrentByteIndex++];

    $nStringLength = $deckBytes[$nCurrentByteIndex++];
    $nTotalCardBytes = $nTotalBytes - $nStringLength;

    {
      $nComputedChecksum = 0;
      for($i = $nCurrentByteIndex; $i <= $nTotalCardBytes; $i++)
        $nComputedChecksum += $deckBytes[$i];

      $masked = ($nComputedChecksum & 0xFF);
      if($nChecksum != $masked) {
        throw new Exception("Invalid Checksum.");
      }
    }

    $digiEggCount = 0;
    DCGDeckDecoder::ReadVarEncodedUint32(
      $nVersionAndDigiEggCount,
      3,
      $deckBytes,
      $nCurrentByteIndex,
      $nTotalCardBytes,
      $digiEggCount
    );

    $cards = array();
    while($nCurrentByteIndex <= $nTotalCardBytes) {
      $cardSet = "";
      $cardSetBytes = array();
      switch ($version) {
        case 0:
          for ($charIdx = 0; $charIdx < 4; $charIdx++) {
            array_push($cardSetBytes, $deckBytes[$nCurrentByteIndex++]);
          }
          $cardSet = trim(implode(array_map("chr", $cardSetBytes)));
          break;
        case 1:
          while (true) {
            $byte = $deckBytes[$nCurrentByteIndex++];
            $cardSet .= base36_to_char($byte & 0x3F);
            if (($byte >> 7) == 0) {
              break;
            }
          }
          break;
      }
      $cardSetPadAndCount = $deckBytes[$nCurrentByteIndex++];
      $pad = ($cardSetPadAndCount >> 6) + 1;
      $cardSetCount = $cardSetPadAndCount & 0x3F;

      $nPrevCardBase = 0;
      for ($cardSetIndex = 0; $cardSetIndex < $cardSetCount; $cardSetIndex++) {
        $nCardCount = 0;
        $nParallelID = 0;
        $nCardNumber = 0;
        switch ($version) {
          case 0:
            DCGDeckDecoder::ReadSerializedCardv0(
              $deckBytes,
              $nCurrentByteIndex,
              $nTotalBytes,
              $nPrevCardBase,
              $nCardCount,
              $nParallelID,
              $nCardNumber
            );
            break;
          case 1:
            DCGDeckDecoder::ReadSerializedCardv1(
              $deckBytes,
              $nCurrentByteIndex,
              $nTotalBytes,
              $nPrevCardBase,
              $nCardCount,
              $nParallelID,
              $nCardNumber
            );
            break;
        }

        array_push(
          $cards,
          array(
            "number" => sprintf("{$cardSet}-%0{$pad}d", $nCardNumber),
            "parallel-id" => $nParallelID,
            "count" => $nCardCount
          )
        );
      }
    }

    $name = "";
    if($nCurrentByteIndex <= $nTotalBytes) {
      $name = implode(array_map(
        "chr",
        array_slice($deckBytes, -1 * $nStringLength)
      ));
    }

    return array(
      "digi-eggs" => array_slice($cards, 0, $digiEggCount),
      "deck" => array_slice($cards, $digiEggCount),
      "name" => $name
    );
  }

  private static function DecodeDeckString($deckCodeStr) {
    if(substr($deckCodeStr, 0, strlen(PREFIX)) != PREFIX) {
      throw new Exception("Deck codes must begin with 'DCG'.");
    }

    $strNoPrefix = substr($deckCodeStr, strlen(PREFIX));
    $decoded = base64url_decode($strNoPrefix);
    return unpack("C*", $decoded);
  }

  public static function Decode($deckCodeStr) {
    $deckBytes = DCGDeckDecoder::DecodeDeckString($deckCodeStr);
    if(!$deckBytes) {
      throw new Exception("Decoding string resulted in no bytes.");
    }

    $deck = DCGDeckDecoder::ParseDeck($deckCodeStr, $deckBytes);
    return $deck;
  }
}
