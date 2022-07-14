package com.lib.docscanner.utils

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.ProgressNotifier

class ImageTextReader {
    val TAG = "ImageTextReader"
    var success = false

    /**
     * TessBaseAPI instance
     */
    @Volatile
    private var api: TessBaseAPI? = null
    //  private static volatile TesseractImageTextReader INSTANCE;

    //  private static volatile TesseractImageTextReader INSTANCE;
    /**
     * initialize and train the tesseract engine
     *
     * @param path     a path to training data
     * @param language language code i.e. selected by user
     * @return the instance of this class for later use
     */
    fun geInstance(
        path: String?,
        language: String?,
        pageSegMode: Int,
        progressNotifier: TessBaseAPI.ProgressNotifier?
    ): ImageTextReader? {
        return try {
            val imageTextReader = ImageTextReader()
            api = TessBaseAPI(progressNotifier)
            imageTextReader.success = api!!.init(path, language)
            api!!.pageSegMode = pageSegMode
            imageTextReader
        } catch (e: Exception) {
            null
        }
    }

    /**
     * get the text from bitmap
     *
     * @param bitmap a image
     * @return text on image
     */
    //Anurag
    fun getTextFromBitmap(bitmap: Bitmap?): String? {
        api!!.setImage(bitmap)
        val textOnImage: String
        textOnImage = try {
            api!!.utF8Text
            //textOnImage = api.getHOCRText(1);
        } catch (e: Exception) {
            return "Scan Failed: WTF: Must be reported to developer!"
        }
        return if (textOnImage.isEmpty()) {
            "Scan Failed: Couldn't read the image\nProblem may be related to Tesseract or no Text on Image!"
        } else textOnImage
    }


}