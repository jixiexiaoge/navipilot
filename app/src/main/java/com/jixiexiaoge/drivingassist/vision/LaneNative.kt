@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.vision

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 高德 AR 视觉模型的原生桥。
 *
 * 推理运行时不是官方 MNN，而是高德车机版 libVAE.so 里静态链接的那一份——
 * 这批 2021–2022 年导出的 int8 量化模型含 EltwiseInt8 等算子，官方 MNN 3.x
 * 会直接报 `Create execution error: 518`。
 *
 * 原生侧用 dlopen + dlsym 转发 13 个 MNN 方法（libVAE.so 的符号表畸形，lld 拒绝链接）。
 */
internal object LaneNative {
    private const val TAG = "LaneNative"
    private const val LIB = "lanenet"

    private val loadError: String? = try {
        System.loadLibrary(LIB)
        val status = nativeLoadStatus()
        Log.i(TAG, "MNN runtime status: $status")
        if (status == "ok") null else status
    } catch (throwable: Throwable) {
        Log.e(TAG, "native library load failed", throwable)
        "${throwable.javaClass.simpleName}: ${throwable.message}"
    }

    val ready: Boolean get() = loadError == null
    val error: String get() = loadError.orEmpty()

    fun setRoi(left: Float, right: Float) {
        if (ready) nativeSetRoi(left, right)
    }

    fun open(path: String): Long = if (ready) nativeInit(path) else 0L

    fun close(handle: Long) {
        if (handle != 0L) nativeRelease(handle)
    }

    fun describe(handle: Long): String = if (handle != 0L) nativeDescribe(handle) else "no net"

    /** {W, H, 输出个数} */
    fun inputShape(handle: Long): IntArray =
        if (handle != 0L) nativeInputShape(handle) else intArrayOf(0, 0, 0)

    /** {H, W, C, 1=NHWC/0=NCHW} */
    fun roadShape(handle: Long): IntArray =
        if (handle != 0L) nativeRoadShape(handle) else intArrayOf(0, 0, 0, 1)

    fun outNames(handle: Long): Array<String> =
        if (handle != 0L) nativeOutNames(handle) else emptyArray()

    fun outSizes(handle: Long): IntArray = if (handle != 0L) nativeOutSizes(handle) else IntArray(0)

    fun outShapeAt(handle: Long, index: Int): IntArray =
        if (handle != 0L) nativeOutShapeAt(handle, index) else intArrayOf(0, 0, 0)

    /**
     * 车道线专用推理。road / counts 由调用方复用，避免每帧经 JNI 新建大数组。
     * 返回 0 成功。
     */
    fun infer(
        handle: Long,
        gray: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        road: FloatArray,
        counts: FloatArray,
    ): Int = if (handle == 0L) {
        -1
    } else {
        nativeInferCompact(handle, gray, width, height, stride, road, counts)
    }

    private external fun nativeLoadStatus(): String

    private external fun nativeSetRoi(left: Float, right: Float)

    private external fun nativeInit(path: String): Long

    private external fun nativeInputShape(handle: Long): IntArray

    private external fun nativeRoadShape(handle: Long): IntArray

    private external fun nativeOutNames(handle: Long): Array<String>

    private external fun nativeOutSizes(handle: Long): IntArray

    private external fun nativeOutShapeAt(handle: Long, index: Int): IntArray

    private external fun nativeInferCompact(
        handle: Long,
        gray: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        road: FloatArray,
        counts: FloatArray,
    ): Int

    private external fun nativeDescribe(handle: Long): String

    private external fun nativeRelease(handle: Long)
}

/** assets/models 下的 .mnn 落地到 filesDir，runSession 需要真实文件路径 */
internal object LaneModels {
    private const val TAG = "LaneModels"

    /** 白天权重 · 主力 */
    const val DAY = "vlaneDay.mnn"
    /** 夜间权重 */
    const val NIGHT = "vlaneNight.mnn"
    /** 半分辨率降级档，也可作车道数第二票 */
    const val LITE = "multitask.mnn"

    val all = listOf(DAY, NIGHT, LITE)

    fun shortName(asset: String): String = asset.substringBeforeLast('.')

    fun prepare(context: Context, asset: String): File? {
        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "mkdirs failed: $dir")
            return null
        }
        val out = File(dir, asset)
        val expected = runCatching { context.assets.open("models/$asset").use { it.available() } }
            .getOrDefault(0)
        if (out.exists() && out.length() > 0L && (expected <= 0 || out.length() == expected.toLong())) {
            return out
        }
        return try {
            context.assets.open("models/$asset").use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
            }
            Log.i(TAG, "copied $asset -> ${out.length()} bytes")
            out
        } catch (throwable: Throwable) {
            Log.e(TAG, "copy asset failed: $asset", throwable)
            null
        }
    }
}
