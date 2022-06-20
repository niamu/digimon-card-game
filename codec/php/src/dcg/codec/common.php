<?php
define("VERSION", 1);
define("PREFIX", "DCG");
define("HEADER_SIZE", 3);
define("BASE36", array(
  "0" => 0,
  "1" => 1,
  "2" => 2,
  "3" => 3,
  "4" => 4,
  "5" => 5,
  "6" => 6,
  "7" => 7,
  "8" => 8,
  "9" => 9,
  "A" => 10,
  "B" => 11,
  "C" => 12,
  "D" => 13,
  "E" => 14,
  "F" => 15,
  "G" => 16,
  "H" => 17,
  "I" => 18,
  "J" => 19,
  "K" => 20,
  "L" => 21,
  "M" => 22,
  "N" => 23,
  "O" => 24,
  "P" => 25,
  "Q" => 26,
  "R" => 27,
  "S" => 28,
  "T" => 29,
  "U" => 30,
  "V" => 31,
  "W" => 32,
  "X" => 33,
  "Y" => 34,
  "Z" => 35
));

function base64url_encode($data) {
  $b64 = base64_encode($data);
  if ($b64 === false)
    throw new Exception("Base64 encode error.");
  $url = strtr($b64, '+/', '-_');
  return rtrim($url, '=');
}

function base64url_decode($data) {
  $b64 = strtr($data, '-_', '+/');
  return base64_decode($b64, false);
}

function char_to_base36($char) {
  return BASE36[$char];
}

function base36_to_char($base36) {
  return array_flip(BASE36)[$base36];
}
