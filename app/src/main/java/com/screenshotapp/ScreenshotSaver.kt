package com.screenshotapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotSaver {

    private const val TAG = "ScreenshotSaver"

    /**
     * Compresses [bitmap] as JPEG at [quality] and writes it to [treeUri] when the user
     * picked a folder, otherwise to Pictures/ScreenshotApp via MediaStore.
     * Returns the saved image URI, or null on failure.
     */
    fun save(context: Context, bitmap: Bitmap, quality: Int, treeUri: Uri?): Uri? {
        val name = "Screenshot_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return try {
            if (treeUri != null) saveToTree(context, bitmap, quality, treeUri, name)
            else saveToMediaStore(context, bitmap, quality, name)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            null
        }
    }

    private fun saveToMediaStore(context: Context, bitmap: Bitmap, quality: Int, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Prefs.DEFAULT_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null")
            return null
        }
        val ok = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: false
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveToTree(
        context: Context,
        bitmap: Bitmap,
        quality: Int,
        tree: Uri,
        name: String,
    ): Uri? {
        val resolver = context.contentResolver
        // createDocument expects the parent *document* URI; convert the picked tree URI.
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val docUri = DocumentsContract.createDocument(resolver, parentDoc, "image/jpeg", name)
            ?: run {
                Log.e(TAG, "DocumentsContract.createDocument returned null")
                return null
            }
        val ok = resolver.openOutputStream(docUri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: false
        if (!ok) {
            try {
                DocumentsContract.deleteDocument(resolver, docUri)
            } catch (e: Exception) {
                Log.w(TAG, "cleanup of partial document failed", e)
            }
            return null
        }
        return docUri
    }
}
