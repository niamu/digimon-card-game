use std::ffi::CString;
use std::os::raw::c_char;

use opencv::{
    self as cv,
    core::{Mat, Point},
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

pub fn template_match(template: &Mat, image: &Mat) -> MatchResult {
    let mut alpha = Mat::default();
    cv::core::extract_channel(&template, &mut alpha, 3).unwrap();
    let mut mask = Mat::default();
    cv::imgproc::threshold(
        &mut alpha,
        &mut mask,
        0.0,
        255.0,
        cv::imgproc::THRESH_BINARY,
    )
    .unwrap();
    let mut result = Mat::default();
    let mut template_no_alpha = Mat::default();
    cv::imgproc::cvt_color(
        &template,
        &mut template_no_alpha,
        cv::imgproc::COLOR_BGRA2BGR,
        0,
    )
    .unwrap();
    cv::imgproc::match_template(
        &image,
        &template_no_alpha,
        &mut result,
        cv::imgproc::TM_CCORR_NORMED,
        &mask,
    )
    .unwrap();
    drop(mask);
    let mut min_val = Some(0f64);
    let mut max_val = Some(0f64);
    let mut min_loc = Some(Point::default());
    let mut max_loc = Some(Point::default());
    let mut mask = cv::core::no_array();
    cv::core::min_max_loc(
        &result,
        min_val.as_mut(),
        max_val.as_mut(),
        min_loc.as_mut(),
        max_loc.as_mut(),
        &mut mask,
    )
    .unwrap();
    MatchResult {
        accuracy: max_val.unwrap(),
        coords: Coords {
            x: max_loc.unwrap().x,
            y: max_loc.unwrap().y,
        },
    }
}
