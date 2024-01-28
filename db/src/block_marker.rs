use std::cmp::Ordering;
use std::ffi::CStr;
use std::os::raw::c_char;

use opencv::{self as cv, core::Mat, prelude::MatTraitConst};

use crate::utils;

struct Template {
    pub path: &'static str,
    pub value: i32,
}

const BLOCK_MARKERS: [Template; 3] = [
    Template {
        path: "resources/images/templates/block-markers/01.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/block-markers/02.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-markers/03.png",
        value: 3,
    },
];

#[no_mangle]
pub extern "C" fn block_marker(image_path: *const c_char) -> i32 {
    let image_path: &str =
        unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let mut image_mat =
        cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
    if image_mat.cols() > 431 || image_mat.rows() > 601 {
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
    let mut result: Vec<(f64, i32)> = Vec::default();
    for block_marker in BLOCK_MARKERS {
        let template = cv::imgcodecs::imread(
            block_marker.path,
            cv::imgcodecs::IMREAD_UNCHANGED,
        )
        .unwrap();
        let match_result = utils::template_match(&template, &image_mat);
        if match_result.accuracy > 0.92
            && match_result.coords.x > 370
            && match_result.coords.x < 395
            && match_result.coords.y > 460
        {
            result.push((match_result.accuracy, block_marker.value));
        }
    }
    result.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(Ordering::Equal));
    let block_marker_value: i32;
    if result.len() > 0 {
        let (_, value) = result.first().unwrap();
        block_marker_value = *value;
    } else {
        block_marker_value = -1;
    }
    block_marker_value
}
