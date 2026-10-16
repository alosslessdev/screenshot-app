package com.screenshotapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.StringRes
import java.util.concurrent.Executors

/**
 * Accessibility service that owns the floating capture button (an accessibility overlay,
 * so no SYSTEM_ALERT_WINDOW permission is needed) and captures the visible screen with
 * [AccessibilityService.takeScreenshot] (API 30+).
 */
class CaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "CaptureService"
        private const val EDGE_MARGIN_PX = 12
        private const val VIBRATION_MS = 50L
    }

    private var prefs: SharedPreferences? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var pressedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BUTTON_SIZE_PX) {
            mainHandler.post { updateOverlayLayout() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        val p = Prefs.get(this)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefsListener)
        showOverlay()
        val caps = serviceInfo?.capabilities ?: 0
        val canShoot = caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        Log.i(TAG, "service connected, CAPABILITY_CAN_TAKE_SCREENSHOT=$canShoot")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post { updateOverlayLayout() }
    }

    override fun onDestroy() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        removeOverlay()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // ---- floating button ----

    @SuppressLint("WrongConstant") // TYPE_ACCESSIBILITY_OVERLAY is the intended overlay window type
    private fun showOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val p = prefs ?: Prefs.get(this)
        val size = Prefs.buttonSizePx(p)
        // Services do not inherit the application theme; inflate with the app theme so
        // any theme attribute in the overlay resolves.
        val themed = ContextThemeWrapper(this, R.style.Theme_ScreenshotApp)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_button, null)
        view.setPadding(size / 5, size / 5, size / 5, size / 5)
        view.findViewById<ImageView>(R.id.captureButton).setOnClickListener { onCapturePressed() }
        try {
            wm.addView(view, overlayParams(size))
            overlayView = view
        } catch (e: RuntimeException) {
            Log.e(TAG, "adding overlay failed", e)
            toast(R.string.err_internal)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "removing overlay failed", e)
        }
        overlayView = null
    }

    private fun updateOverlayLayout() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val size = Prefs.buttonSizePx(prefs ?: Prefs.get(this))
        view.setPadding(size / 5, size / 5, size / 5, size / 5)
        try {
            wm.updateViewLayout(view, overlayParams(size))
        } catch (e: RuntimeException) {
            Log.w(TAG, "updating overlay failed", e)
        }
    }

    private fun overlayParams(size: Int): WindowManager.LayoutParams {
        val bounds = displayBounds()
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.width() - size).coerceAtLeast(0) - EDGE_MARGIN_PX
            y = (bounds.height() - size).coerceAtLeast(0) / 2
            title = "ScreenshotCaptureButton"
        }
    }

    private fun displayBounds(): Rect {
        val bounds = windowManager?.currentWindowMetrics?.bounds
        if (bounds != null && !bounds.isEmpty) return bounds
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    // ---- capture ----

    private fun onCapturePressed() {
        vibrate()
        if (capturing) {
            Log.d(TAG, "capture already in progress, ignoring press")
            return
        }
        capturing = true
        pressedAt = SystemClock.elapsedRealtime()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    Log.i(TAG, "screenshot returned after ${SystemClock.elapsedRealtime() - pressedAt} ms")
                    processScreenshot(result)
                }

                override fun onFailure(errorCode: Int) {
                    capturing = false
                    Log.w(TAG, "takeScreenshot failed with code $errorCode")
                    toast(errorMessage(errorCode))
                }
            })
        } catch (e: Exception) {
            capturing = false
            Log.e(TAG, "takeScreenshot threw", e)
            toast(R.string.err_internal)
        }
    }

    private fun processScreenshot(result: ScreenshotResult) {
        ioExecutor.execute {
            val hardwareBuffer = result.hardwareBuffer
            var savedUri: Uri? = null
            try {
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                if (hardwareBitmap != null) {
                    val software = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap.recycle()
                    if (software != null) {
                        try {
                            val p = prefs ?: Prefs.get(this)
                            savedUri = ScreenshotSaver.save(
                                applicationContext,
                                software,
                                Prefs.jpegQuality(p),
                                Prefs.outputTreeUri(p)?.let(Uri::parse),
                            )
                        } finally {
                            software.recycle()
                        }
                    } else {
                        Log.e(TAG, "hardware bitmap could not be copied to software config")
                    }
                } else {
                    Log.e(TAG, "Bitmap.wrapHardwareBuffer returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "screenshot processing failed", e)
            } finally {
                hardwareBuffer.close()
                capturing = false
            }
            if (savedUri == null) {
                toast(R.string.err_save)
            } else {
                Log.i(TAG, "saved $savedUri in ${SystemClock.elapsedRealtime() - pressedAt} ms (press -> file saved)")
            }
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    private fun errorMessage(errorCode: Int): Int = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> R.string.err_secure
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> R.string.err_interval
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> R.string.err_display
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> R.string.err_window
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> R.string.err_access
        else -> R.string.err_internal
    }

    private fun toast(@StringRes resId: Int) {
        mainHandler.post {
            Toast.makeText(applicationContext, getString(resId), Toast.LENGTH_SHORT).show()
        }
    }
}
