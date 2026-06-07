package com.example.navipilot.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer

/**
 * H264 实时摄像头预览组件
 *
 * 接收来自 /ws/camera/road 的 H264 NAL 单元，
 * 通过 MediaCodec 解码并渲染到 TextureView。
 *
 * @param frameBytes 最新的 H264 帧数据（含起始码）
 * @param width  视频宽度（首次收到 keyframe 时设置）
 * @param height 视频高度
 * @param isKeyFrame 是否为关键帧（用于初始化编解码器）
 * @param modifier Compose 修饰符
 */
@androidx.compose.runtime.Composable
fun CameraPreview(
    frameBytes: ByteArray?,
    width: Int,
    height: Int,
    isKeyFrame: Boolean,
    modifier: Modifier = Modifier
) {
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var mediaCodec by remember { mutableStateOf<MediaCodec?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var codecConfigured by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableStateOf(640) }
    var videoHeight by remember { mutableStateOf(480) }

    // 更新分辨率
    LaunchedEffect(width, height) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    // 初始化/重置 MediaCodec
    fun initCodec(surf: Surface, w: Int, h: Int) {
        try {
            mediaCodec?.release()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surf, null, 0)
            codec.start()
            mediaCodec = codec
            codecConfigured = true
            Log.i("CameraPreview", "📹 MediaCodec 初始化完成: ${w}x${h}")
        } catch (e: Exception) {
            Log.e("CameraPreview", "MediaCodec 初始化失败: ${e.message}")
            codecConfigured = false
        }
    }

    // 将 H264 数据送入解码器
    fun feedFrame(data: ByteArray) {
        val codec = mediaCodec ?: return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(5000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(inputBufferIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }

            // 输出缓冲（渲染到 Surface）
            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w("CameraPreview", "解码帧失败: ${e.message}")
        }
    }

    // 收到关键帧时重置编解码器
    LaunchedEffect(isKeyFrame, videoWidth, videoHeight, surface) {
        if (isKeyFrame && surface != null && videoWidth > 0 && videoHeight > 0) {
            initCodec(surface!!, videoWidth, videoHeight)
        }
    }

    // 输入帧数据
    LaunchedEffect(frameBytes) {
        val data = frameBytes ?: return@LaunchedEffect
        if (codecConfigured) {
            feedFrame(data)
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                textureView = tv
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surf: SurfaceTexture, w: Int, h: Int) {
                        surface = Surface(surf)
                        videoWidth = w
                        videoHeight = h
                        // 等待关键帧触发 codec 初始化
                    }

                    override fun onSurfaceTextureSizeChanged(surf: SurfaceTexture, w: Int, h: Int) {
                        if (frameBytes != null && isKeyFrame) {
                            surface?.let { initCodec(it, w, h) }
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surf: SurfaceTexture): Boolean {
                        mediaCodec?.release()
                        mediaCodec = null
                        codecConfigured = false
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surf: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier
    )

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            mediaCodec?.release()
            mediaCodec = null
            codecConfigured = false
            surface?.release()
            surface = null
        }
    }
}
