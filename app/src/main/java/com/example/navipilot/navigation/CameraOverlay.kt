package com.example.navipilot.navigation

import android.util.Log
import com.amap.api.maps.AMap
import com.amap.api.maps.model.Marker
import com.amap.api.navi.model.AMapNaviCameraInfo

/**
 * 高德电子眼覆盖层（占位实现）。
 *
 * SDK 11.1.200 的 AMapNaviCameraInfo 不暴露经纬度，
 * 电子眼数据通过广播（[com.example.navipilot.AmapBroadcastHandlers]）传输。
 *
 * 此类保留接口骨架，供后续 SDK 版本启用完整覆盖层。
 */
class CameraOverlay(private val aMap: AMap?) {

    private val markers = mutableListOf<Marker>()
    private val TAG = "CameraOverlay"

    /** 清除所有摄像头 Marker */
    fun clear() {
        val copy = markers.toList()
        copy.forEach { it.remove() }
        markers.clear()
    }

    /**
     * 更新摄像头覆盖层（占位 — SDK 不提供坐标，暂不可用）。
     * @see [com.amap.api.navi.AMapNaviCameraInfo]
     */
    fun updateCameras(cameras: List<AMapNaviCameraInfo>) {
        clear()
        Log.d(TAG, "摄像头覆盖层: 收到 ${cameras.size} 个摄像头数据（SDK 未暴露坐标，留待后续版本）")
    }

    /** 高亮最近电子眼（占位实现） */
    fun highlightNearestCamera(camera: AMapNaviCameraInfo?) {
        if (camera == null || markers.isEmpty()) return
        // SDK 不能获取坐标，暂不实现
        Log.d(TAG, "高亮最近电子眼: ${camera.cameraType}")
    }
}
