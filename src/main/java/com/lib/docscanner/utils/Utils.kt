package org.tensorflow.lite.examples.ocr.utils

import android.annotation.SuppressLint
import android.graphics.Bitmap
import com.googlecode.leptonica.android.*
import org.tensorflow.lite.examples.ocr.utils.SpUtil
import java.lang.StringBuilder

object Utils {
    private const val DEFAULT_LANGUAGE = "eng"
    @SuppressLint("DefaultLocale")
    fun getSize(size: Int): String {
        var s = ""
        val kb = (size / 1024).toDouble()
        val mb = kb / 1024
        if (size < 1024) {
            s = "\$size Bytes"
        } else if (size < 1024 * 1024) {
            s = String.format("%.2f", kb) + " KB"
        } else if (size < 1024 * 1024 * 1024) {
            s = String.format("%.2f", mb) + " MB"
        }
        return s
    }

    fun preProcessBitmap(bitmap: Bitmap): Bitmap {
        var bitmap = bitmap
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        var pix = ReadFile.readBitmap(bitmap)
        pix = Convert.convertTo8(pix)
        if (SpUtil.instance!!.getBoolean(Constants.KEY_CONTRAST, true)) {
            // pix=AdaptiveMap.backgroundNormMorph(pix);
            pix = AdaptiveMap.pixContrastNorm(pix)
        }
        if (SpUtil.instance!!.getBoolean(Constants.KEY_UN_SHARP_MASKING, true)) pix =
            Enhance.unsharpMasking(pix)
        if (SpUtil.instance!!.getBoolean(Constants.KEY_OTSU_THRESHOLD, true)) pix =
            Binarize.otsuAdaptiveThreshold(pix)
        if (SpUtil.instance!!.getBoolean(Constants.KEY_FIND_SKEW_AND_DESKEW, true)) {
            val f = Skew.findSkew(pix)
            pix = Rotate.rotate(pix, f)
        }
        return WriteFile.writeBitmap(pix)
    }

    val isPreProcessImage: Boolean
        get() = SpUtil.instance!!.getBoolean(Constants.KEY_GRAYSCALE_IMAGE_OCR, true)
    val isPersistData: Boolean
        get() = SpUtil.instance!!.getBoolean(Constants.KEY_PERSIST_DATA, true)

    fun getTesseractStringForMultipleLanguages(langs: Set<String?>?): String {
        if (langs == null) return DEFAULT_LANGUAGE
        val rLanguage = StringBuilder()
        for (lang in langs) {
            rLanguage.append(lang)
            rLanguage.append("+")
        }
        return rLanguage.subSequence(0, rLanguage.toString().lastIndexOf('+')).toString()
    }

    val trainingDataType: String?
        get() = SpUtil.instance!!.getString(Constants.KEY_TESS_TRAINING_DATA_SOURCE, "best")
    val trainingDataLanguage: String?
        get() = if (SpUtil.instance!!.getBoolean(Constants.KEY_ENABLE_MULTI_LANG)
        ) {
            getTesseractStringForMultipleLanguages(
                SpUtil.instance!!.getStringSet(
                    Constants.KEY_LANGUAGE_FOR_TESSERACT_MULTI,
                    null
                )
            )
        } else {
            SpUtil.instance!!.getString(
                Constants.KEY_LANGUAGE_FOR_TESSERACT,
                DEFAULT_LANGUAGE
            )
        }

    fun setTrainingDataLanguage(language: String?): String? {
        return if (SpUtil.instance!!.getBoolean(Constants.KEY_ENABLE_MULTI_LANG)
        ) {
            getTesseractStringForMultipleLanguages(
                SpUtil.instance!!.getStringSet(
                    Constants.KEY_LANGUAGE_FOR_TESSERACT_MULTI,
                    null
                )
            )
        } else {
            SpUtil.instance!!.getString(
                Constants.KEY_LANGUAGE_FOR_TESSERACT,
                DEFAULT_LANGUAGE
            )
        }
    }

    val pageSegMode: Int
        get() = SpUtil.instance!!.getString(Constants.KEY_PAGE_SEG_MODE, "1")!!.toInt()

    fun putLastUsedText(text: String?) {
        SpUtil.instance!!.putString(Constants.KEY_LAST_USE_IMAGE_TEXT, text)
    }

    val lastUsedText: String?
        get() = SpUtil.instance!!.getString(Constants.KEY_LAST_USE_IMAGE_TEXT, "")
    val last3UsedLanguage: Array<String?>
        get() = arrayOf(
            SpUtil.instance!!.getString(Constants.KEY_LAST_USED_LANGUAGE_1, "eng"),
            SpUtil.instance!!.getString(Constants.KEY_LAST_USED_LANGUAGE_2, "hin"),
            SpUtil.instance!!.getString(Constants.KEY_LAST_USED_LANGUAGE_3, "deu")
        )

    fun setLastUsedLanguage(lastUsedLanguage: String) {
        val l1 = SpUtil.instance!!.getString(Constants.KEY_LAST_USED_LANGUAGE_1, "eng")
        if (lastUsedLanguage.contentEquals(l1)) {
            return
        }
        val l2 = SpUtil.instance!!.getString(Constants.KEY_LAST_USED_LANGUAGE_2, "hin")
        if (l2.contentEquals(lastUsedLanguage)) {
            SpUtil.instance!!.putString(Constants.KEY_LAST_USED_LANGUAGE_2, l1)
            SpUtil.instance!!.putString(Constants.KEY_LAST_USED_LANGUAGE_1, lastUsedLanguage)
        } else {
            SpUtil.instance!!.putString(Constants.KEY_LAST_USED_LANGUAGE_3, l2)
            SpUtil.instance!!.putString(Constants.KEY_LAST_USED_LANGUAGE_2, l1)
            SpUtil.instance!!.putString(Constants.KEY_LAST_USED_LANGUAGE_1, lastUsedLanguage)
        }
    }

    fun putLastUsedImageLocation(imageURI: String?) {
        SpUtil.instance!!.putString(Constants.KEY_LAST_USE_IMAGE_LOCATION, imageURI)
    }
}