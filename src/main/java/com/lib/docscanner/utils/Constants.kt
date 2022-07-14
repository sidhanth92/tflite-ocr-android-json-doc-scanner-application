package org.tensorflow.lite.examples.ocr.utils

/**
 * Various constant: Self explanatory
 */
object Constants {
    /***
     * TRAINING DATA URL TEMPLATES for downloading
     */
    const val TESSERACT_DATA_DOWNLOAD_URL_BEST =
        "https://github.com/tesseract-ocr/tessdata_best/raw/4.0.0/%s.traineddata"
    const val TESSERACT_DATA_DOWNLOAD_URL_STANDARD =
        "https://github.com/tesseract-ocr/tessdata/raw/4.0.0/%s.traineddata"
    const val TESSERACT_DATA_DOWNLOAD_URL_FAST =
        "https://github.com/tesseract-ocr/tessdata_fast/raw/4.0.0/%s.traineddata"
    const val LANGUAGE_CODE = "%s.traineddata"
    const val KEY_LANGUAGE_FOR_TESSERACT = "language_for_tesseract"
    const val KEY_ENABLE_MULTI_LANG = "key_enable_multiple_lang"
    const val KEY_TESS_TRAINING_DATA_SOURCE = "tess_training_data_source"
    const val KEY_LANGUAGE_FOR_TESSERACT_MULTI = "multi_languages"
    const val KEY_GRAYSCALE_IMAGE_OCR = "grayscale_image_ocr"
    const val KEY_LAST_USE_IMAGE_LOCATION = "last_use_image_location"
    const val KEY_LAST_USE_IMAGE_TEXT = "last_use_image_text"
    const val KEY_PERSIST_DATA = "persist_data"
    const val KEY_LAST_USED_LANGUAGE_1 = "key_language_1"
    const val KEY_LAST_USED_LANGUAGE_2 = "key_language_2"
    const val KEY_LAST_USED_LANGUAGE_3 = "key_language_3"
    const val KEY_CONTRAST = "process_contrast"
    const val KEY_UN_SHARP_MASKING = "un_sharp_mask"
    const val KEY_OTSU_THRESHOLD = "otsu_threshold"
    const val KEY_FIND_SKEW_AND_DESKEW = "deskew_img"
    const val KEY_PAGE_SEG_MODE = "key_ocr_psm_mode"
}