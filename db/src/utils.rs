use std::ffi::CString;
use std::os::raw::c_char;

use opencv::{
    self as cv,
    boxed_ref::BoxedRef,
    core::{Mat, Point},
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
    let mut result_ccorr = Mat::default();
    let mut result_sqdiff = Mat::default();
    let mut result: f64;
    let mut image_prep = Mat::default();
    cv::imgproc::cvt_color(&image, &mut image_prep, cv::imgproc::COLOR_BGRA2GRAY, 0).unwrap();
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
        )
        .unwrap();
    } else {
        cv::imgproc::cvt_color(
            &template,
            &mut template_prep,
            cv::imgproc::COLOR_BGR2GRAY,
            0,
        )
        .unwrap();
    }
    cv::imgproc::match_template(
        &image_prep,
        &template_prep,
        &mut result_ccorr,
        cv::imgproc::TM_CCORR_NORMED,
        &mask,
    )
    .unwrap();
    cv::imgproc::match_template(
        &image_prep,
        &template_prep,
        &mut result_ccoeff,
        cv::imgproc::TM_CCOEFF_NORMED,
        &mask,
    )
    .unwrap();
    cv::imgproc::match_template(
        &image_prep,
        &template_prep,
        &mut result_sqdiff,
        cv::imgproc::TM_SQDIFF_NORMED,
        &mask,
    )
    .unwrap();
    drop(mask);
    let mut mask = cv::core::no_array();
    let mut min_val = Some(0f64);
    let mut max_val = Some(0f64);
    let mut min_loc = Some(Point::default());
    let mut max_loc = Some(Point::default());
    cv::core::min_max_loc(
        &result_ccorr,
        min_val.as_mut(),
        max_val.as_mut(),
        min_loc.as_mut(),
        max_loc.as_mut(),
        &mut mask,
    )
    .unwrap();
    coords.x = max_loc.unwrap().x;
    coords.y = max_loc.unwrap().y;
    result = max_val.unwrap();
    cv::core::min_max_loc(
        &result_ccoeff,
        min_val.as_mut(),
        max_val.as_mut(),
        min_loc.as_mut(),
        max_loc.as_mut(),
        &mut mask,
    )
    .unwrap();
    coords.x = max_loc.unwrap().x;
    coords.y = max_loc.unwrap().y;
    result = result + max_val.unwrap();
    cv::core::min_max_loc(
        &result_sqdiff,
        min_val.as_mut(),
        max_val.as_mut(),
        min_loc.as_mut(),
        max_loc.as_mut(),
        &mut mask,
    )
    .unwrap();
    result = (result + (1.0 - min_val.unwrap())) / 3.0;
    MatchResult {
        accuracy: result,
        coords: coords,
    }
}
