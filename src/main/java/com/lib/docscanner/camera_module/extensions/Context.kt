package com.lib.docscanner.camera_module.extensions

import android.content.Context
import android.os.Build
import android.os.Environment
import com.lib.docscanner.R
import java.io.File

/** Use external media if it is available, our app's file directory otherwise */
internal val Context.outputDirectory: File
    get() {
        val appContext = applicationContext
        val mediaDir: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
                File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() }
            }!!
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().let {
                File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() }
            }
        }
        return if (mediaDir.exists())
            mediaDir else appContext.filesDir
    }
