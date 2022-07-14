package org.tensorflow.lite.examples.ocr.utils

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import kotlin.jvm.Volatile
import org.tensorflow.lite.examples.ocr.utils.SpUtil

/**
 * A util class for SharedPreferences
 */
class SpUtil private constructor() {
    private var mContext: Context? = null
    private var mPref: SharedPreferences? = null

    /**
     * initialization of context, use only first time later it will use this again and again
     *
     * @param context app context: first time
     */
    fun init(context: Context?) {
        if (mContext == null) {
            mContext = context
        }
        if (mPref == null) {
            mPref = PreferenceManager.getDefaultSharedPreferences(mContext)
        }
    }

    fun putString(key: String?, value: String?) {
        val editor = mPref!!.edit()
        editor.putString(key, value)
        editor.apply()
    }

    fun putLong(key: String?, value: Long) {
        val editor = mPref!!.edit()
        editor.putLong(key, value)
        editor.apply()
    }

    fun putInt(key: String?, value: Int) {
        val editor = mPref!!.edit()
        editor.putInt(key, value)
        editor.apply()
    }

    fun putBoolean(key: String?, value: Boolean) {
        val editor = mPref!!.edit()
        editor.putBoolean(key, value)
        editor.apply()
    }

    fun getBoolean(key: String?): Boolean {
        return mPref!!.getBoolean(key, false)
    }

    fun getBoolean(key: String?, def: Boolean): Boolean {
        return mPref?.getBoolean(key, def) ?: false
    }

    fun getString(key: String?): String? {
        return mPref!!.getString(key, "")
    }

    fun getString(key: String?, def: String?): String? {
        return mPref!!.getString(key, def)
    }

    fun getStringSet(key: String?, def: Set<String?>?): Set<String>? {
        return mPref!!.getStringSet(key, def)
    }

    fun getLong(key: String?): Long {
        return mPref!!.getLong(key, 0)
    }

    fun getLong(key: String?, defInt: Int): Long {
        return mPref!!.getLong(key, defInt.toLong())
    }

    fun getInt(key: String?): Int {
        return mPref!!.getInt(key, 0)
    }

    fun getInt(key: String?, defInt: Int): Long {
        return mPref!!.getInt(key, defInt).toLong()
    }

    operator fun contains(key: String?): Boolean {
        return mPref!!.contains(key)
    }

    fun remove(key: String?) {
        val editor = mPref!!.edit()
        editor.remove(key)
        editor.apply()
    }

    fun clear() {
        val editor = mPref!!.edit()
        editor.clear()
        editor.apply()
    }

    companion object {
        @Volatile
        private var mInstance: SpUtil? = null

        /**
         * A factory method for
         *
         * @return a instance of this class
         */
        val instance: SpUtil?
            get() {
                if (null == mInstance) {
                    synchronized(SpUtil::class.java) {
                        if (null == mInstance) {
                            mInstance = SpUtil()
                        }
                    }
                }
                return mInstance
            }
    }
}