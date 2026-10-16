package com.screenshotapp

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE_NAME = "screenshot_app_prefs"
    const val KEY_JPEG_QUALITY = "jpeg_quality"
    const val KEY_BUTTON_SIZE_PX = "button_size_px"
    const val KEY_OUTPUT_TREE_URI = "output_tree_uri"

    const val DEFAULT_JPEG_QUALITY = 90
    const val DEFAULT_BUTTON_SIZE_PX = 50
    const val MIN_BUTTON_SIZE_PX = 50
    const val MAX_BUTTON_SIZE_PX = 200

    /** MediaStore relative path used until the user picks a folder. */
    const val DEFAULT_RELATIVE_PATH = "Pictures/ScreenshotApp"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun jpegQuality(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY).coerceIn(10, 100)

    fun buttonSizePx(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BUTTON_SIZE_PX, DEFAULT_BUTTON_SIZE_PX).coerceIn(MIN_BUTTON_SIZE_PX, MAX_BUTTON_SIZE_PX)

    fun outputTreeUri(prefs: SharedPreferences): String? =
        prefs.getString(KEY_OUTPUT_TREE_URI, null)
}
