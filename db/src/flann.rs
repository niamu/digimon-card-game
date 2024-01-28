use opencv::{
    self as cv,
    core::{Mat, Ptr, Vector},
    features2d::FlannBasedMatcher,
    flann::{IndexParams, SearchParams, FLANN_INDEX_LSH},
    prelude::*,
};

use std::collections::HashMap;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::sync::Mutex;

fn descriptors(image: &Mat) -> Mat {
    let mut orb = cv::features2d::ORB::create(
        500,
        1.2,
        8,
        31,
        0,
        2,
        cv::features2d::ORB_ScoreType::HARRIS_SCORE,
        31,
        20,
    )
    .unwrap();
    let mut image_keypoints = Vector::default();
    let mask = &cv::core::no_array();
    let mut image_descriptors = Mat::default();
    orb.detect_and_compute(
        &image,
        mask,
        &mut image_keypoints,
        &mut image_descriptors,
        false,
    )
    .unwrap();
    image_descriptors
}

static DB: Mutex<Option<FlannBasedMatcher>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn db_init() {
    let mut index_params = Ptr::new(IndexParams::default().unwrap());
    index_params.set_int(&"table_number", 10).unwrap();
    index_params.set_int(&"key_size", 32).unwrap();
    index_params.set_int(&"multi_probe_level", 2).unwrap();
    index_params.set_algorithm(FLANN_INDEX_LSH).unwrap();
    let search_params =
        Ptr::new(SearchParams::new(32, 0.0, true, false).unwrap());
    let db = FlannBasedMatcher::new(&index_params, &search_params).unwrap();
    *DB.lock().unwrap() = Some(db);
}

#[no_mangle]
pub extern "C" fn db_add(image_path: *const c_char) -> i32 {
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
    let image_descriptors = descriptors(&image_mat);
    let mut binding = DB.lock().unwrap();
    let db = binding.as_mut().unwrap();
    let result: i32 = db
        .get_train_descriptors()
        .unwrap()
        .len()
        .try_into()
        .unwrap();
    FlannBasedMatcherTrait::add(db, &image_descriptors).unwrap();
    result
}

#[no_mangle]
pub extern "C" fn db_train() {
    let mut binding = DB.lock().unwrap();
    let db = binding.as_mut().unwrap();
    opencv::prelude::FlannBasedMatcherTrait::train(db).unwrap();
}

#[no_mangle]
pub fn db_query(image_path: *const c_char) -> i32 {
    let mut binding = DB.lock().unwrap();
    let db = binding.as_mut().unwrap();
    let image_path: &str =
        unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let image_mat =
        cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
    let image_descriptors = descriptors(&image_mat);
    let mut matches = Vector::new();
    let masks = &cv::core::no_array();
    db.knn_match(&image_descriptors, &mut matches, 2, masks, false)
        .unwrap();
    let mut match_map: HashMap<i32, i32> = HashMap::new();
    *match_map.entry(-1).or_insert(0) += 0;
    for m in &matches {
        match m.len() {
            2 => {
                if m.get(0).unwrap().distance < 0.7 * m.get(1).unwrap().distance
                {
                    *match_map.entry(m.get(0).unwrap().img_idx).or_insert(0) +=
                        1;
                }
            }
            1 => *match_map.entry(m.get(0).unwrap().img_idx).or_insert(0) += 1,
            _ => *match_map.entry(-1).or_insert(0) += 1,
        }
    }
    *match_map.iter().max_by(|a, b| a.1.cmp(&b.1)).unwrap().0
}
