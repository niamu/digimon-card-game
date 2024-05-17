mod utils;

use std::ffi::CString;
use crate::digivolution_requirements::digivolution_requirements;

mod digivolution_requirements;

fn main() {
	let path = CString::new("/Users/niamu/Documents/Projects/Projects/niamu/digimon-card-game/db/resources/images/cards/ja/BT10-072.png");
	let raw = digivolution_requirements(path.expect("REASON").into_raw());
	unsafe {
		let x = CString::from_raw(raw);
		println!("{:?}", x);
	}

	let path = CString::new("/Users/niamu/Documents/Projects/Projects/niamu/digimon-card-game/db/resources/images/cards/ja/BT10-040.png");
	let raw = digivolution_requirements(path.expect("REASON").into_raw());
	unsafe {
		let x = CString::from_raw(raw);
		println!("{:?}", x);
	}

	let path = CString::new("/Users/niamu/Documents/Projects/Projects/niamu/digimon-card-game/db/resources/images/cards/ja/BT8-084.png");
	let raw = digivolution_requirements(path.expect("REASON").into_raw());
	unsafe {
		let x = CString::from_raw(raw);
		println!("{:?}", x);
	}
}
