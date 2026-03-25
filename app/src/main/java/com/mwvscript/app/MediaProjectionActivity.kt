package com.mwvscript.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

class MediaProjectionActivity : Activity() {

    companion object {
        const val REQUEST_CODE = 9001
        var pendingCallback: ((Any?) -> Unit)? = null
        var mediaProjection: MediaProjection? = null
    }

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            captureScreen()
        } else {
            pendingCallback?.invoke(null)
            pendingCallback = null
            finish()
        }
    }

    private fun captureScreen() {
        val mp = mediaProjection ?: run { finish(); return }
        val metrics = resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        virtualDisplay = mp.createVirtualDisplay(
            "MWVCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride   = planes[0].rowStride
                    val rowPadding  = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                    pendingCallback?.invoke(base64)
                } else {
                    pendingCallback?.invoke(null)
                }
            } catch (e: Exception) {
                android.util.Log.e("MWVScreen", "capture: ${e.message}")
                pendingCallback?.invoke(null)
            } finally {
                virtualDisplay?.release()
                mp.stop()
                imageReader.close()
                pendingCallback = null
                finish()
            }
        }, 500)
    }
}
