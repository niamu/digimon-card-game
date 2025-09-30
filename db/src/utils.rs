use std::ffi::CString;
use std::os::raw::c_char;

use opencv::{
    self as cv,
    boxed_ref::BoxedRef,
    core::{AlgorithmHint, Mat, Point},
    prelude::MatTraitConst,
};

#[no_mangle]
pub extern "C" fn free_string(ptr: *mut c_char) {
    unsafe {
        if !ptr.is_null() {
            // retake pointer to free memory
            let _ = CString::from_raw(ptr);
        }
    }
}

#[derive(Debug, Copy, Clone)]
pub struct MatchResult {
    pub accuracy: f64,
    pub coords: Coords,
}

#[derive(Debug, Copy, Clone)]
pub struct Coords {
    pub x: i32,
    pub y: i32,
}

pub fn template_match(template: &Mat, image: &BoxedRef<'_, Mat>) -> MatchResult {
    let mut alpha = Mat::default();
    let mut mask = Mat::default();
    let mut template_prep = template.clone();
    let mut result_ccoeff = Mat::default();
    let result: f64;
    let mut image_prep = Mat::default();
    cv::imgproc::cvt_color(
        &image,
        &mut image_prep,
        cv::imgproc::COLOR_BGRA2GRAY,
        0,
        AlgorithmHint::ALGO_HINT_DEFAULT,
    )
    .unwrap();
    let mut coords = Coords { x: 0, y: 0 };
    if template.channels() > 3 {
        cv::core::extract_channel(&template, &mut alpha, 3).unwrap();
        cv::imgproc::threshold(
            &mut alpha,
            &mut mask,
            0.0,
            255.0,
            cv::imgproc::THRESH_BINARY,
        )
        .unwrap();
        cv::imgproc::cvt_color(
            &template,
            &mut template_prep,
            cv::imgproc::COLOR_BGRA2GRAY,
            0,
            AlgorithmHint::ALGO_HINT_DEFAULT,
        )
        .unwrap();
    } else {
        cv::imgproc::cvt_color(
            &template,
            &mut template_prep,
            cv::imgproc::COLOR_BGR2GRAY,
            0,
            AlgorithmHint::ALGO_HINT_DEFAULT,
        )
        .unwrap();
    }
    cv::imgproc::match_template(
        &image_prep,
        &template_prep,
        &mut result_ccoeff,
        cv::imgproc::TM_CCOEFF_NORMED,
        &mask,
    )
    .unwrap();
    let mut new_mask = cv::core::no_array();
    let mut min_val = Some(0f64);
    let mut max_val = Some(0f64);
    let mut min_loc = Some(Point::default());
    let mut max_loc = Some(Point::default());
    cv::core::min_max_loc(
        &result_ccoeff,
        min_val.as_mut(),
        max_val.as_mut(),
        min_loc.as_mut(),
        max_loc.as_mut(),
        &mut new_mask,
    )
    .unwrap();
    if max_val.unwrap().is_nan() {
        cv::imgproc::match_template(
            &image_prep,
            &template_prep,
            &mut result_ccoeff,
            cv::imgproc::TM_CCORR_NORMED,
            &mask,
        )
        .unwrap();
        drop(mask);
        let mut new_mask = cv::core::no_array();
        let mut min_val = Some(0f64);
        let mut max_val = Some(0f64);
        let mut min_loc = Some(Point::default());
        let mut max_loc = Some(Point::default());
        cv::core::min_max_loc(
            &result_ccoeff,
            min_val.as_mut(),
            max_val.as_mut(),
            min_loc.as_mut(),
            max_loc.as_mut(),
            &mut new_mask,
        )
        .unwrap();
        coords.x = max_loc.unwrap().x;
        coords.y = max_loc.unwrap().y;
        result = max_val.unwrap();
    } else {
        coords.x = max_loc.unwrap().x;
        coords.y = max_loc.unwrap().y;
        result = max_val.unwrap();
    }
    MatchResult {
        accuracy: result,
        coords,
    }
}

pub fn resize_card(image: &Mat) -> Mat {
    let mut image = image.clone();
    if image.cols() != 430 || image.rows() != 600 {
        let image_size = cv::core::Size::new(430, 600);
        let mut reduced_image = Mat::default();
        cv::imgproc::resize(
            &image,
            &mut reduced_image,
            image_size,
            0.0,
            0.0,
            cv::imgproc::INTER_LINEAR,
        )
        .unwrap();
        drop(image);
        image = reduced_image.clone();
        drop(reduced_image);
    }
    return image;
}
