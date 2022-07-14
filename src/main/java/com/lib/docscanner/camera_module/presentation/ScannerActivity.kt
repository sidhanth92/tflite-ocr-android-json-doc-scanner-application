package com.lib.docscanner.camera_module.presentation

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.lib.docscanner.MainActivity
import com.lib.docscanner.R
import com.lib.docscanner.camera_module.exceptions.NullCorners
import java.io.*


class ScannerActivity : BaseScannerActivity() {

    private lateinit var images: MutableList<String>
    private var idCardName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        images = ArrayList()
        /* val outDir =
             getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath + "/tesseract/tessdata/eng.traineddata"
         val outFile = File(outDir)
         if (!outFile.exists()) {
             copyAssets()
         }*/
        idCardName = intent.getStringExtra("idCardName")?:""
    }

    override fun onError(throwable: Throwable) {
        when (throwable) {
            is NullCorners -> Toast.makeText(
                this,
                R.string.null_corners, Toast.LENGTH_LONG
            )
                .show()
            else -> Toast.makeText(this, throwable.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDocumentAccepted(bitmap: Bitmap) {
        images.add(writeBitmapToFile(bitmap))
        if (images.size >= 1) {
            binding.btnProceed.visibility = View.VISIBLE
        }
    }

    override fun onClose() {
        finish()
    }

    override fun onProceed() {
        val in1 = Intent(this, MainActivity::class.java)
        for (img: String in images) {
            in1.putExtra("image" + (images.indexOf(img) + 1), img)
            in1.putExtra("idCardName" , idCardName)
        }

        Log.d("msg","onProceed---------------------")
        //startActivity(in1)
        someActivityResultLauncher.launch(in1)
    }

    private fun writeBitmapToFile(bitmap: Bitmap): String {
        Log.d("msg","writeBitmapToFile---------------------")
        val file_path = getFilesDir().absolutePath +
                "/DocScan"
        val dir = File(file_path)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "doc_" + System.currentTimeMillis().toString() + ".png")
        val fOut = FileOutputStream(file)

        bitmap.compress(Bitmap.CompressFormat.PNG, 85, fOut)
        fOut.flush()
        fOut.close()

        return file.absolutePath
    }

    /* private fun copyAssets() {
         val assetManager = assets

         var `in`: InputStream? = null
         var out: OutputStream? = null
         try {
             `in` = assetManager.open("eng.traineddata")
             var outDir =
                 getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath + "/tesseract"
             val dir = File(outDir)
             try {
                 if (dir.mkdir()) {
                     println("Directory created")
                 } else {
                     println("Directory is not created")
                 }
                 outDir += "/tessdata"
                 val dir1 = File(outDir)
                 if (dir1.mkdir()) {
                     println("Directory created")
                 } else {
                     println("Directory is not created")
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
             }


             val outFile = File(outDir, "eng.traineddata")
             out = FileOutputStream(outFile)
             copyFile(`in`, out)
             `in`.close()
             `in` = null
             out.flush()
             out.close()
             out = null
         } catch (e: IOException) {
             Log.e("tag", "Failed to copy asset file: eng.traineddata", e)
         }

     }*/

    /*@Throws(IOException::class)
    private fun copyFile(`in`: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
    }*/

    private var someActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result?.resultCode == RESULT_OK) {
                val intent = result.data
                val mResult = intent!!.getStringExtra("result")
                val intent2 = Intent()
                intent2.putExtra("result", mResult)
                setResult(RESULT_OK, intent2)
                finish()
            }
        }
}
