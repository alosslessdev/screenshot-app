package com.screenshotapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var folderText: TextView

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    // Provider did not offer persistent access; the grant still works
                    // until the device reboots.
                }
                prefs.edit().putString(Prefs.KEY_OUTPUT_TREE_URI, uri.toString()).apply()
            }
            updateFolderText()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs.get(this)
        statusText = findViewById(R.id.statusText)
        qualityLabel = findViewById(R.id.qualityLabel)
        sizeLabel = findViewById(R.id.sizeLabel)
        folderText = findViewById(R.id.folderText)

        findViewById<MaterialButton>(R.id.enableButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.chooseFolderButton).setOnClickListener {
            folderPicker.launch(null)
        }

        val quality = Prefs.jpegQuality(prefs)
        qualityLabel.text = getString(R.string.label_quality, quality)
        val qualitySlider = findViewById<Slider>(R.id.qualitySlider)
        qualitySlider.value = quality.toFloat()
        qualitySlider.addOnChangeListener { _, value, _ ->
            val q = value.toInt()
            prefs.edit().putInt(Prefs.KEY_JPEG_QUALITY, q).apply()
            qualityLabel.text = getString(R.string.label_quality, q)
        }

        val size = Prefs.buttonSizePx(prefs)
        sizeLabel.text = getString(R.string.label_button_size, size)
        val sizeSlider = findViewById<Slider>(R.id.sizeSlider)
        sizeSlider.value = size.toFloat()
        sizeSlider.addOnChangeListener { _, value, _ ->
            val s = value.toInt()
            prefs.edit().putInt(Prefs.KEY_BUTTON_SIZE_PX, s).apply()
            sizeLabel.text = getString(R.string.label_button_size, s)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateFolderText()
    }

    private fun updateStatus() {
        val enabled = isCaptureServiceEnabled()
        statusText.text = getString(if (enabled) R.string.status_on else R.string.status_off)
        statusText.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.status_on else R.color.status_off),
        )
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        // The reported id may use either the full or the shortened component form
        // (com.screenshotapp/com.screenshotapp.CaptureService vs .../.CaptureService).
        return list.any { info ->
            val component = ComponentName.unflattenFromString(info.id)
            component?.packageName == packageName &&
                component.className == CaptureService::class.java.name
        }
    }

    private fun updateFolderText() {
        val tree = Prefs.outputTreeUri(prefs)
        folderText.text = if (tree == null) {
            getString(R.string.default_folder)
        } else {
            getString(R.string.selected_folder, displayName(Uri.parse(tree)))
        }
    }

    private fun displayName(tree: Uri): String = try {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        contentResolver
            .query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getString(0)
                } else {
                    getString(R.string.folder_query_failed)
                }
            } ?: getString(R.string.folder_query_failed)
    } catch (e: Exception) {
        getString(R.string.folder_query_failed)
    }
}
