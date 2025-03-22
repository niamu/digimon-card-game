use std::ffi::CStr;
use std::os::raw::c_char;

use opencv::{
    self as cv,
    core::{Mat, Rect},
    prelude::MatTraitConst,
};

use crate::block_icon;
use crate::utils;

struct Template {
    pub path: &'static str,
    pub value: i32,
}

const RARITY_STAMPS: [Template; 11] = [
    Template {
        path: "resources/images/templates/rarity/sp_a.png",
        value: 99,
    },
    Template {
        path: "resources/images/templates/rarity/sp_b.png",
        value: 99,
    },
    Template {
        path: "resources/images/templates/rarity/sp_c.png",
        value: 99,
    },
    Template {
        path: "resources/images/templates/rarity/sp_d.png",
        value: 99,
    },
    Template {
        path: "resources/images/templates/rarity/sp_e.png",
        value: 99,
    },
    Template {
        path: "resources/images/templates/rarity/star_a.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/rarity/star_b.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/rarity/star_c.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/rarity/star2_a.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/rarity/star2_b.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/rarity/star3_a.png",
        value: 3,
    },
];

#[no_mangle]
pub extern "C" fn supplemental_rarity(image_path: *const c_char) -> i32 {
    let image_path: &str = unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let mut image_mat = cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
    if image_mat.cols() != 430 || image_mat.rows() != 600 {
        let image_size = cv::core::Size::new(430, 600);
        let mut reduced_image_mat = Mat::default();
        cv::imgproc::resize(
            &image_mat,
            &mut reduced_image_mat,
            image_size,
            0.0,
            0.0,
            cv::imgproc::INTER_LINEAR,
        )
        .unwrap();
        drop(image_mat);
        image_mat = reduced_image_mat.clone();
        drop(reduced_image_mat);
    }

    let mut image_roi = Mat::roi(&image_mat, Rect::new(370, 460, 28, 140)).unwrap();
    let mut block_icon_coords: Option<utils::Coords> = Option::None;
    for block_icon in block_icon::BLOCK_ICONS {
        let template =
            cv::imgcodecs::imread(block_icon.path, cv::imgcodecs::IMREAD_UNCHANGED).unwrap();
        let match_result = utils::template_match(&template, &image_roi);
        if match_result.accuracy > 0.875 {
            block_icon_coords = Some(match_result.coords);
        }
    }
    drop(image_roi);

    let mut result: i32 = -1;
    match block_icon_coords {
        Some(coords) => {
            image_roi = Mat::roi(&image_mat, Rect::new(335, 460 + coords.y - 5, 38, 20)).unwrap();
            for sp_stamp in RARITY_STAMPS {
                let template =
                    cv::imgcodecs::imread(sp_stamp.path, cv::imgcodecs::IMREAD_UNCHANGED).unwrap();
                let match_result = utils::template_match(&template, &image_roi);
                if match_result.accuracy >= 0.905 && sp_stamp.value > result {
                    result = sp_stamp.value;
                }
            }
        }
        None => {}
    }
    result
}
