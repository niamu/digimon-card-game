use std::cmp::Ordering;
use std::ffi::CStr;
use std::os::raw::c_char;

use opencv::{
    self as cv,
    core::{Mat, Rect},
};

use crate::utils;

pub struct Template {
    pub path: &'static str,
    pub value: i32,
}

pub const BLOCK_ICONS: [Template; 35] = [
    Template {
        path: "resources/images/templates/block-icons/01_a.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/block-icons/01_b.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/block-icons/01_c.png",
        value: 1,
    },
    Template {
        path: "resources/images/templates/block-icons/02_a.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-icons/02_b.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-icons/02_c.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-icons/02_d.png",
        value: 2,
    },
    Template {
        path: "resources/images/templates/block-icons/03_a.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_b.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_c.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_d.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_e.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_f.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_g.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_h.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_i.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/03_j.png",
        value: 3,
    },
    Template {
        path: "resources/images/templates/block-icons/04_a.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_b.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_c.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_d.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_e.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_f.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_g.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_h.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_i.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_j.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/04_k.png",
        value: 4,
    },
    Template {
        path: "resources/images/templates/block-icons/05_a.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_b.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_c.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_d.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_f.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_g.png",
        value: 5,
    },
    Template {
        path: "resources/images/templates/block-icons/05_h.png",
        value: 5,
    },
];

#[no_mangle]
pub extern "C" fn block_icon(image_path: *const c_char) -> i32 {
    let image_path: &str = unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let mut image_mat = cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
    image_mat = utils::resize_card(&image_mat);
    let image_roi = Mat::roi(&image_mat, Rect::new(370, 460, 28, 140)).unwrap();
    let mut result: Vec<(f64, i32)> = Vec::default();
    for block_icon in BLOCK_ICONS {
        let template =
            cv::imgcodecs::imread(block_icon.path, cv::imgcodecs::IMREAD_UNCHANGED).unwrap();
        let match_result = utils::template_match(&template, &image_roi);
        if match_result.accuracy > 0.875 {
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
