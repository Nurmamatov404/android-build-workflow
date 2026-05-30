package com.mlbb.trainer

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureEngine(
    private val mediaProjection: MediaProjection,
    private val displayMetrics: DisplayMetrics,
    private val densityDpi: Int
) {

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var saveThread: HandlerThread? = null
    private var saveHandler: Handler? = null
    private var isCapturing = false
    private var frameCount = 0
    private var framesDir: File? = null
    private var captureIntervalMs: Long = 100L

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        if (!isCapturing) return@OnImageAvailableListener
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        saveImage(image)
    }

    fun start(sessionDir: File, fps: Int = 10) {
        if (isCapturing) return
        captureIntervalMs = (1000L / fps).coerceAtLeast(33L)

        framesDir = File(sessionDir, "frames").also { it.mkdirs() }

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)

        val displayName = "MLBB-Capture-${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
        virtualDisplay = mediaProjection.createVirtualDisplay(
            displayName,
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener(frameListener, null)

        captureThread = HandlerThread("CaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        saveThread = HandlerThread("SaveThread").apply { start() }
        saveHandler = Handler(saveThread!!.looper)

        isCapturing = true
        frameCount = 0

        captureHandler?.post(captureRunnable)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing) return
            val reader = imageReader ?: return
            val image = reader.acquireLatestImage()
            if (image != null) {
                saveImage(image)
            }
            captureHandler?.postDelayed(this, captureIntervalMs)
        }
    }

    private fun saveImage(image: Image) {
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) return

        val seq = ++frameCount
        val timestamp = System.currentTimeMillis()
        val dir = framesDir ?: return

        saveHandler?.post {
            val file = File(dir, "frame_%06d_%d.png".format(seq, timestamp))
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val w = image.width; val h = image.height

        buffer.rewind()

        if (rowPadding == 0) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val rowBytes = w * pixelStride
        val line = ByteArray(rowBytes)
        for (y in 0 until h) {
            buffer.position(y * rowStride)
            buffer.get(line)
            for (x in 0 until w) {
                val i = x * 4
                val a = line[i + 3].toInt() and 0xFF
                val r = line[i].toInt() and 0xFF
                val g = line[i + 1].toInt() and 0xFF
                val b = line[i + 2].toInt() and 0xFF
                pixels[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun stop() {
        isCapturing = false
        captureHandler?.removeCallbacksAndMessages(null)
        saveHandler?.removeCallbacksAndMessages(null)

        captureThread?.quitSafely()
        saveThread?.quitSafely()

        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}

        virtualDisplay = null
        imageReader = null
        captureHandler = null
        saveHandler = null
        captureThread = null
        saveThread = null
    }

    fun getFrameCount(): Int = frameCount
}
