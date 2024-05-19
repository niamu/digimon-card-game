use std::cmp::Ordering;
use std::ffi::CStr;
use std::os::raw::c_char;

use opencv::{self as cv, core::{Mat, Rect}, prelude::MatTraitConst};

use crate::utils;

struct Template {
    pub path: &'static str,
    pub value: i32,
}

const BLOCK_ICONS: [Template; 4] = [
    Template {
        path: "resources/images/templates/block-icons/01.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/block-icons/02.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-icons/03.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/04.png",
        value: 4,
    },
];

#[no_mangle]
pub extern "C" fn block_icon(image_path: *const c_char) -> i32 {
    let image_path: &str =
        unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let mut image_mat =
        cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
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
    let image_roi = Mat::roi(&image_mat, Rect::new(385, 460, 20, 110)).unwrap();
    let mut result: Vec<(f64, i32)> = Vec::default();
    for block_icon in BLOCK_ICONS {
        let template = cv::imgcodecs::imread(
            block_icon.path,
            cv::imgcodecs::IMREAD_UNCHANGED,
        )
        .unwrap();
        let match_result = utils::template_match(&template, &image_roi);
        if match_result.accuracy > 0.948
        {
            result.push((match_result.accuracy, block_icon.value));
        }
    }
    result.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(Ordering::Equal));
    let block_icon_value: i32;
    if result.len() > 0 {
        let (_, value) = result.first().unwrap();
        block_icon_value = *value;
    } else {
        block_icon_value = -1;
    }
    block_icon_value
}
