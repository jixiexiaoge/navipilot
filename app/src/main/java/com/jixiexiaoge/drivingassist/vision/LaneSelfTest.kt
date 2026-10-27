@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * 调试自检：用 `assets/test/road_sim.jpg` 跑一次完整推理。
 *
 * 目的不是"证明模型准"，而是**在没有 comma 设备、没有真实画面的情况下**
 * 验证全链路通：dlopen libVAE → 会话创建 → 预处理 → runSession → 后处理 → LaneResult。
 *
 * 触发方式（只 debug 包生效）：
 * ```
 * adb shell touch /sdcard/Android/data/<pkg>/files/lane_selftest.flag
 * ```
 * 下次模型加载完成后自动跑一帧，并把结果打进 logcat（tag = LaneSelfTest），
 * 然后把 flag 删掉，不会重复跑。
 */
internal object LaneSelfTest {
    private const val TAG = "LaneSelfTest"

    const val FLAG = "lane_selftest.flag"

    class Gray(val bytes: ByteArray, val width: Int, val height: Int)

    fun requested(context: Context): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        val marker = File(dir, FLAG)
        if (!marker.exists()) return false
        marker.delete()
        return true
    }

    fun load(context: Context): Gray? {
        try {
            context.assets.open("test/road_sim.jpg").use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                if (bmp == null) {
                    Log.w(TAG, "decode road_sim.jpg failed")
                    return null
                }
                val width = bmp.width
                val height = bmp.height
                val pixels = IntArray(width * height)
                bmp.getPixels(pixels, 0, width, 0, 0, width, height)
                bmp.recycle()
                val gray = ByteArray(width * height)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    gray[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
                Log.i(TAG, "self-test frame ${width}x$height")
                return Gray(gray, width, height)
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "self-test asset unavailable", throwable)
            return null
        }
    }
}
