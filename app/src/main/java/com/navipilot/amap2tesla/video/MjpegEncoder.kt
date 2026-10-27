package com.navipilot.amap2tesla.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

class MjpegEncoder(
    private val width: Int,
    private val height: Int,
    private val frameStore: LatestFrameStore,
    private val fps: Int = 30,
    private val quality: Int = 72
) : AutoCloseable {
    private val thread = HandlerThread("Amap2Tesla-mjpeg").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val output = ByteArrayOutputStream(512 * 1024)
    private var paddedBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCanvas: Canvas? = null
    private val cropRect = Rect(0, 0, width, height)
    private val framePacer = FramePacer(fps)
    @Volatile private var closed = false

    val surface: Surface
        get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (closed) return@setOnImageAvailableListener
            val image = try {
                source.acquireLatestImage()
            } catch (error: IllegalStateException) {
                if (!closed) Log.e("MjpegEncoder", "Cannot acquire projection image", error)
                null
            } ?: return@setOnImageAvailableListener
            val bitmap = try {
                if (!framePacer.shouldEncode(System.nanoTime())) return@setOnImageAvailableListener

                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                if (pixelStride <= 0 || rowStride < width * pixelStride) {
                    Log.e("MjpegEncoder", "Invalid image stride: pixel=$pixelStride row=$rowStride")
                    return@setOnImageAvailableListener
                }
                val paddedWidth = rowStride / pixelStride
                ensureBitmaps(paddedWidth)

                plane.buffer.rewind()
                paddedBitmap!!.copyPixelsFromBuffer(plane.buffer)
                if (paddedWidth == width) {
                    paddedBitmap!!
                } else {
                    cropCanvas!!.drawBitmap(paddedBitmap!!, cropRect, cropRect, null)
                    croppedBitmap!!
                }
            } finally {
                image.close()
            }
            if (closed) return@setOnImageAvailableListener
            output.reset()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)) {
                synchronized(this) {
                    if (!closed) frameStore.publish(output.toByteArray())
                }
            } else {
                Log.e("MjpegEncoder", "Projection JPEG compression failed")
            }
        }, handler)
    }

    private fun ensureBitmaps(paddedWidth: Int) {
        if (paddedBitmap?.width != paddedWidth || paddedBitmap?.height != height) {
            paddedBitmap?.recycle()
            paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        }
        if (paddedWidth != width &&
            (croppedBitmap?.width != width || croppedBitmap?.height != height)
        ) {
            croppedBitmap?.recycle()
            croppedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cropCanvas = Canvas(croppedBitmap!!)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        reader.setOnImageAvailableListener(null, null)
        // Cleanup runs after any in-flight compression, not concurrently with bitmap access.
        if (!handler.post { releaseResources(); thread.quitSafely() }) {
            releaseResources()
            thread.quitSafely()
        }
    }

    private fun releaseResources() {
        reader.close()
        paddedBitmap?.recycle()
        croppedBitmap?.recycle()
        paddedBitmap = null
        croppedBitmap = null
        cropCanvas = null
    }
}

internal class FramePacer(fps: Int) {
    private val intervalNanos = 1_000_000_000L / fps.also { require(it > 0) { "FPS must be positive" } }
    private val toleranceNanos = minOf(2_000_000L, intervalNanos / 10)
    private var nextFrameAtNanos: Long? = null

    fun shouldEncode(nowNanos: Long): Boolean {
        val deadline = nextFrameAtNanos
        if (deadline != null && nowNanos + toleranceNanos < deadline) return false
        nextFrameAtNanos = if (deadline == null) nowNanos + intervalNanos else {
            deadline + ((nowNanos - deadline).coerceAtLeast(0) / intervalNanos + 1) * intervalNanos
        }
        return true
    }
}
