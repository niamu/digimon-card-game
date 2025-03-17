use opencv::{
    self as cv,
    core::{Mat, Rect},
    img_hash::PHash,
    prelude::*,
};

use std::ffi::{c_ulonglong, CStr};
use std::os::raw::c_char;

#[no_mangle]
pub extern "C" fn image_hash(image_path: *const c_char) -> c_ulonglong {
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
    let icon = image_mat.roi(Rect::new(96, 64, 300, 215)).unwrap();
    let mut hash_value = Mat::default();
    let mut phash = PHash::create().unwrap();
    phash.compute(&icon, &mut hash_value).unwrap();
    u64::from_be_bytes(hash_value.data_bytes().unwrap().try_into().unwrap())
}
