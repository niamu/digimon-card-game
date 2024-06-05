package main

import (
	"github.com/niamu/digimon-card-game/codec/go/codec"

	"encoding/json"
	"flag"
	"fmt"
	"os"
)

func main() {
	decode := flag.String("decode", "", "deck code string")
	encode := flag.String("encode", "", "deck JSON string to encode into deck string")

	flag.Parse()

	switch os.Args[1] {
	case "-encode":
		deck := codec.Deck{}
		json.Unmarshal([]byte(*encode), &deck)
		deck_code, err := codec.Encode(deck, codec.VERSION)
		if err != nil {
			fmt.Printf("%v", err)
		} else {
			fmt.Println(deck_code)
		}
	case "-decode":
		deck, err := codec.Decode(*decode)
		if err != nil {
			fmt.Printf("%v", err)
		} else {
			deck_json, _ := json.Marshal(deck)
			fmt.Println(string(deck_json))
		}
	}
}
