package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.camera.CameraPlayback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 实时画面格的排版比例（7.9.64）。
 *
 * 画面格的高度必须由 **5001 实际送上来的帧尺寸** 反推，不能用写死的 16:9：
 * 写死时容器比例与码流比例不一致，WebRTC 渲染器虽然仍等比缩放（不会拉伸变形），
 * 但会在另一侧留黑边（或与 FILL 相反地把画面裁掉）。
 */
class RoadCameraAspectTest {
    @Test
    fun sharedHeightPreservesBothSourceRatiosWithoutInternalMargins() {
        val map = 4f / 3f
        val video = 964f / 604f
        val height = mediaRowHeight(600.dp, map, video, 260.dp)
        assertEquals(600f, height.value * (map + video), 0.01f)
        assertEquals(map, (height * map).value / height.value, 0.001f)
        assertEquals(video, (height * video).value / height.value, 0.001f)
        assertEquals(100f, mediaRowHeight(600.dp, map, video, 100.dp).value, 0.01f)
    }

    @Test
    fun usesReportedFrameSizeWhenFramesArrived() {
        // 长焦道路摄像头常见码流：1928×1208 ≈ 1.596
        val aspect = CameraPlayback(width = 1928, height = 1208).aspect()
        assertEquals(1928f / 1208f, aspect, 0.001f)
    }

    @Test
    fun fallsBackToWideAspectBeforeFirstFrame() {
        assertEquals(16f / 9f, CameraPlayback().aspect(), 0.001f)
        assertEquals(16f / 9f, CameraPlayback(width = 0, height = 604).aspect(), 0.001f)
        assertEquals(16f / 9f, CameraPlayback(width = 964, height = 0).aspect(), 0.001f)
    }

    @Test
    fun rotatedFramesUseTheSwappedSize() {
        // 播放器在 onFrame 里已按旋转角换过宽高，这里只认换算后的值
        assertEquals(604f / 964f, CameraPlayback(width = 604, height = 964).aspect(), 0.001f)
    }

    @Test
    fun paneHeightFollowsTheVideoAspect() {
        // 7.9.65：两格平分屏宽后，行高由实时画面的比例反推 —— 108dp 宽按 16:9 得 60.75dp
        assertEquals(108f * 9f / 16f, mediaPaneHeight(108.dp, 16f / 9f).value, 0.01f)
        // 换成更「横」的码流，行高会相应压矮（视频比例是硬约束，宽度不动）
        assertEquals(108f / 1.596f, mediaPaneHeight(108.dp, 1928f / 1208f).value, 0.01f)
    }

    @Test
    fun paneHeightHonoursTheMaxCap() {
        // 超高 / 很窄的码流会把行高顶得很高 —— 超过上限就压到上限，别吃掉整屏
        assertEquals(60f, mediaPaneHeight(108.dp, 4f / 3f, maxHeight = 60.dp).value, 0.01f)
        assertEquals(40f, mediaPaneHeight(108.dp, 1f, maxHeight = 40.dp).value, 0.01f)
        // 没超上限时不干涉，仍是「宽度 / 比例」
        assertEquals(81f, mediaPaneHeight(108.dp, 4f / 3f, maxHeight = 260.dp).value, 0.01f)
    }

    @Test
    fun invalidAspectFallsBackToWideRatio() {
        assertEquals(108f * 9f / 16f, mediaPaneHeight(108.dp, 0f).value, 0.01f)
        assertEquals(108f * 9f / 16f, mediaPaneHeight(108.dp, -1f).value, 0.01f)
    }
}
