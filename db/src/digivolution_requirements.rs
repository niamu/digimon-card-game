use edn_derive::Serialize;
use edn_rs;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use opencv::{
    self as cv,
    boxed_ref::BoxedRef,
    core::{AlgorithmHint, Mat, Rect, Size, Vec3b, Vector},
    prelude::MatTraitConst,
};

use crate::utils::{self, Coords, MatchResult};

#[derive(Debug)]
struct Color {
    name: String,
    coords: Coords,
    rgb: RGB,
}

#[derive(Debug, Copy, Clone)]
struct RGB {
    r: u8,
    g: u8,
    b: u8,
}

#[derive(Debug, Clone, Serialize)]
struct DigivolveRequirement {
    colors: Vec<String>,
    category: String,
}

#[derive(Debug, Copy, Clone)]
struct Template {
    path: &'static str,
    version: i32,
    requirements_count: i32,
}

const DIGIVOLVE_TEMPLATES: [Template; 6] = [
    Template {
        path: "resources/images/templates/digivolution-requirements/v1_1.png",
        version: 1,
        requirements_count: 1,
    },
    Template {
        path: "resources/images/templates/digivolution-requirements/v1_2.png",
        version: 1,
        requirements_count: 2,
    },
    Template {
        path: "resources/images/templates/digivolution-requirements/v1_3.png",
        version: 1,
        requirements_count: 3,
    },
    Template {
        path: "resources/images/templates/digivolution-requirements/v1_4.png",
        version: 1,
        requirements_count: 4,
    },
    Template {
        path: "resources/images/templates/digivolution-requirements/v2_1.png",
        version: 2,
        requirements_count: 1,
    },
    Template {
        path: "resources/images/templates/digivolution-requirements/v2_2.png",
        version: 2,
        requirements_count: 2,
    },
];

fn color_difference(rgb1: RGB, rgb2: RGB) -> f64 {
    let max_distance: f64 = 442.0;
    let r_diff: f64 = (rgb1.r as f64 - rgb2.r as f64).into();
    let g_diff: f64 = (rgb1.g as f64 - rgb2.g as f64).into();
    let b_diff: f64 = (rgb1.b as f64 - rgb2.b as f64).into();
    let result: f64 = (r_diff.powf(2.0) + g_diff.powf(2.0) + b_diff.powf(2.0)).into();
    (result.sqrt() / max_distance) * 100.0
}

fn color_at_coordinate(image: &BoxedRef<'_, Mat>, coords: Coords) -> RGB {
    let mut blurred_image = Mat::default();
    cv::imgproc::gaussian_blur(
        &image,
        &mut blurred_image,
        Size::new(3, 3),
        0.0,
        0.0,
        cv::core::BORDER_DEFAULT,
        AlgorithmHint::ALGO_HINT_DEFAULT,
    )
    .unwrap();
    let bgr = blurred_image.at_2d::<Vec3b>(coords.y, coords.x).unwrap();
    RGB {
        r: bgr[2],
        g: bgr[1],
        b: bgr[0],
    }
}

fn colors_within_image(image: &BoxedRef<'_, Mat>) -> Vec<String> {
    let mut result: Vec<String> = Vec::default();
    let mut blurred_image = Mat::default();
    cv::imgproc::gaussian_blur(
        &image,
        &mut blurred_image,
        Size::new(3, 3),
        0.0,
        0.0,
        cv::core::BORDER_DEFAULT,
        AlgorithmHint::ALGO_HINT_DEFAULT,
    )
    .unwrap();
    let mut images: Vector<Mat> = Vector::new();
    images.push(blurred_image.clone());
    let channels = Vector::from_slice(&[0, 1, 2]);
    let mask_roi = cv::core::no_array();
    let hsize = Vector::from_slice(&[2, 2, 2]);
    let ranges = Vector::from_slice(&[0_f32, 255_f32, 0_f32, 255_f32, 0_f32, 255_f32]);
    let mut hist = Mat::default();
    cv::imgproc::calc_hist(
        &images, &channels, &mask_roi, &mut hist, &hsize, &ranges, false,
    )
    .unwrap();
    let mut detected_map: HashMap<&str, f32> = HashMap::new();
    for i in 0..2 {
        for j in 0..2 {
            for k in 0..2 {
                let color = match (i, j, k) {
                    (0, 0, 1) => "red",
                    (1, 1, 0) => "blue",
                    (0, 1, 1) => "yellow",
                    (0, 1, 0) => "green",
                    (0, 0, 0) => "black",
                    (1, 0, 1) => "purple",
                    (1, 0, 0) => "purple",
                    (1, 1, 1) => "white",
                    _ => "",
                };
                let color_count: f32 = *hist.at_3d::<f32>(i, j, k).unwrap();
                let percent = (color_count
                    / (blurred_image.rows() as f32 * blurred_image.cols() as f32))
                    * 100.0;
                if color != "" && percent >= 11.75 {
                    if detected_map.contains_key(color) {
                        detected_map.insert(color, detected_map.get(color).unwrap() + color_count);
                    } else {
                        detected_map.insert(color, color_count);
                    }
                }
            }
        }
    }

    let mut non_text_color_counts = detected_map.clone();
    non_text_color_counts.retain(|c, _| *c != "white" && *c != "black");
    let non_text_color_counts = non_text_color_counts.len();
    let mut detected = Vec::from_iter(detected_map.iter());
    let detected_colors = detected
        .iter()
        .map(|(color, _)| color.to_string())
        .collect::<Vec<_>>();
    if non_text_color_counts >= 3 {
        result = vec![
            "red".to_string(),
            "blue".to_string(),
            "yellow".to_string(),
            "green".to_string(),
            "black".to_string(),
            "purple".to_string(),
            "white".to_string(),
        ];
    } else if detected.len() == 2
        && (detected_colors.contains(&"white".to_string())
            || detected_colors.contains(&"black".to_string()))
    {
        let non_text_colors: Vec<_> = detected_colors
            .iter()
            .filter(|color| **color != "white".to_string() && **color != "black".to_string())
            .collect();
        if non_text_colors.len() != 0 {
            result.push(non_text_colors.first().unwrap().to_owned().clone())
        } else {
            detected.sort_by(|(_, a), (_, b)| b.partial_cmp(a).unwrap());
            result.push(detected.first().unwrap().0.to_string());
        }
    } else {
        detected.sort_by(|(_, a), (_, b)| b.partial_cmp(a).unwrap());
        result.push(detected.first().unwrap().0.to_string());
    }
    result
}

fn requirements_v1(
    image: &BoxedRef<'_, Mat>,
    base_coords: Coords,
    digivolve_template: Template,
) -> Vec<DigivolveRequirement> {
    let mut result: Vec<DigivolveRequirement> = Vec::new();
    let roi_size: i32 = 28;
    let roi_y_between: i32 = 15;
    let mut rois: Vec<Rect> = Vec::new();
    rois.push(Rect::new(
        base_coords.x + 22,
        base_coords.y + 22,
        roi_size,
        roi_size,
    ));
    let mut i = 1;
    while i < digivolve_template.requirements_count {
        i = i + 1;
        let prev_roi = rois.last().unwrap();
        rois.push(Rect::new(
            prev_roi.x,
            prev_roi.y + roi_size + roi_y_between,
            roi_size,
            roi_size,
        ));
    }
    for roi in rois {
        result.push(DigivolveRequirement {
            colors: colors_within_image(&Mat::roi(image, roi).unwrap()),
            category: "Digimon".to_string(),
        });
    }
    result
}

fn requirements_v2(image: &BoxedRef<'_, Mat>, base_coords: Coords, digivolve_template: Template) -> Vec<DigivolveRequirement> {
    let mut result: Vec<DigivolveRequirement> = Vec::new();
    let mut colors: Vec<Color> = vec![
        Color {
            name: "red".to_string(),
            coords: Coords { x: 50, y: 20 },
            rgb: RGB {
                r: 200,
                g: 20,
                b: 53,
            },
        },
        Color {
            name: "blue".to_string(),
            coords: Coords { x: 54, y: 32 },
            rgb: RGB {
                r: 0,
                g: 150,
                b: 223,
            },
        },
        Color {
            name: "yellow".to_string(),
            coords: Coords { x: 43, y: 52 },
            rgb: RGB {
                r: 225,
                g: 225,
                b: 0,
            },
        },
        Color {
            name: "green".to_string(),
            coords: Coords { x: 20, y: 51 },
            rgb: RGB {
                r: 0,
                g: 156,
                b: 108,
            },
        },
        Color {
            name: "black".to_string(),
            coords: Coords { x: 10, y: 32 },
            rgb: RGB {
                r: 35,
                g: 24,
                b: 17,
            },
        },
        Color {
            name: "purple".to_string(),
            coords: Coords { x: 14, y: 20 },
            rgb: RGB {
                r: 100,
                g: 85,
                b: 162,
            },
        },
        Color {
            name: "white".to_string(),
            coords: Coords { x: 30, y: 10 },
            rgb: RGB {
                r: 255,
                g: 255,
                b: 255,
            },
        },
    ];
    let mut confirmed_colors: Vec<String> = Vec::new();
    for color in colors {
        let coords = Coords {
            x: color.coords.x + base_coords.x,
            y: color.coords.y + base_coords.y,
        };
        let color_in_image = color_at_coordinate(&image, coords);
        let diff = color_difference(color_in_image, color.rgb.clone());
        if diff < 15.0 {
            confirmed_colors.push(color.name.to_string())
        }
    }
    result.push(DigivolveRequirement {
        colors: confirmed_colors,
        category: "Digimon".to_string(),
    });
    if digivolve_template.requirements_count == 2 {
        colors = vec![
            Color {
                name: "red".to_string(),
                coords: Coords { x: 50, y: 20 + 50 },
                rgb: RGB {
                    r: 200,
                    g: 20,
                    b: 53,
                },
            },
            Color {
                name: "blue".to_string(),
                coords: Coords { x: 54, y: 32 + 50 },
                rgb: RGB {
                    r: 0,
                    g: 150,
                    b: 223,
                },
            },
            Color {
                name: "yellow".to_string(),
                coords: Coords { x: 43, y: 52 + 50 },
                rgb: RGB {
                    r: 225,
                    g: 225,
                    b: 0,
                },
            },
            Color {
                name: "green".to_string(),
                coords: Coords { x: 20, y: 51 + 50 },
                rgb: RGB {
                    r: 0,
                    g: 156,
                    b: 108,
                },
            },
            Color {
                name: "black".to_string(),
                coords: Coords { x: 10, y: 32 + 50 },
                rgb: RGB {
                    r: 35,
                    g: 24,
                    b: 17,
                },
            },
            Color {
                name: "purple".to_string(),
                coords: Coords { x: 14, y: 20 + 50 },
                rgb: RGB {
                    r: 100,
                    g: 85,
                    b: 162,
                },
            },
            Color {
                name: "white".to_string(),
                coords: Coords { x: 30, y: 10 + 50 },
                rgb: RGB {
                    r: 255,
                    g: 255,
                    b: 255,
                },
            },
        ];
        let mut confirmed_colors: Vec<String> = Vec::new();
        for color in colors {
            let coords = Coords {
                x: color.coords.x + base_coords.x,
                y: color.coords.y + base_coords.y,
            };
            let color_in_image = color_at_coordinate(&image, coords);
            let diff = color_difference(color_in_image, color.rgb.clone());
            if diff < 15.0 {
                confirmed_colors.push(color.name.to_string())
            }
        }
        result.push(DigivolveRequirement {
            colors: confirmed_colors,
            category: "Tamer".to_string(),
        });
    }
    result
}

#[no_mangle]
pub extern "C" fn digivolution_requirements(image_path: *const c_char) -> *mut c_char {
    let image_path: &str = unsafe { CStr::from_ptr(image_path).to_str().unwrap() };
    let mut image_mat = cv::imgcodecs::imread(image_path, cv::imgcodecs::IMREAD_COLOR).unwrap();
    if image_mat.cols() != 430 || image_mat.rows() != 600 {
        let image_size = Size::new(430, 600);
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
    let image_mat_roi = image_mat.roi(Rect::new(0, 90, 90, 210)).unwrap();
    let mut result_vec: Vec<(MatchResult, Template)> = Vec::default();
    for digivolve_template in DIGIVOLVE_TEMPLATES {
        let template =
            cv::imgcodecs::imread(digivolve_template.path, cv::imgcodecs::IMREAD_UNCHANGED)
                .unwrap();
        let match_result = utils::template_match(&template, &image_mat_roi);
        if match_result.accuracy > 0.85 && match_result.coords.y < 25 {
            result_vec.push((match_result, digivolve_template));
        }
    }
    result_vec.sort_by(|a, b| {
        b.0.accuracy
            .partial_cmp(&a.0.accuracy)
            .unwrap_or(Ordering::Equal)
    });
    let result: Option<(MatchResult, Template)>;
    if result_vec.len() > 0 {
        let (m, t) = result_vec.first().unwrap();
        result = Some((*m, *t));
    } else {
        result = None
    }
    let edn = match result {
        Some((m, t)) => match t.version {
            2 => Some(requirements_v2(&image_mat_roi, m.coords, t)),
            _ => Some(requirements_v1(&image_mat_roi, m.coords, t)),
        },
        None => None,
    };
    let edn_string = edn_rs::to_string(&edn);
    let c_str = CString::new(edn_string).unwrap();
    c_str.into_raw()
}
