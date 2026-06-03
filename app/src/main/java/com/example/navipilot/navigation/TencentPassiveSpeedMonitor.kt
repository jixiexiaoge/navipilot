package com.example.navipilot.navigation

import android.app.Application
import android.util.Log
import com.example.navipilot.TencentNavSdkBootstrap
import com.tencent.navix.api.NavigatorZygote
import com.tencent.navix.api.navigator.NavigatorDrive
import com.tencent.navix.api.observer.SimpleNavigatorDriveObserver

/**
 * 腾讯导航 SDK 后台被动限速监控
 *
 * 当活跃导航模式为「高德手机版」或「谷歌导航」时，利用已初始化但未绑定UI的
 * [NavigatorDrive] 实例，以"自由行驶"（Free-Drive）模式接收摄像头/限速回调，
 * 将 [onCameraInfoUpdate] 中的限速信息回传给调用方（作为 [onSpeedLimitUpdate]）。
 *
 * 设计原则：
 * - **零侵入**：不绑定视图、不规划路线，只注册 observer
 * - **幂等**：[start]/[stop] 可多次安全调用
 * - **静默降级**：SDK 不支持自由行驶摄像头时，仅打印 warn 日志，不崩溃
 *
 * 腾讯 SDK 已在 [TencentNavSdkBootstrap.ensureInitialized] 中初始化，
 * 此处直接复用同一 Zygote，不重复 init/start。
 */
object TencentPassiveSpeedMonitor {
    private const val TAG = "TencentPassiveMonitor"

    /** 限速更新回调：限速 (km/h)，-1 表示清除 */
    var onSpeedLimitUpdate: ((speedLimitKmh: Int) -> Unit)? = null

    @Volatile private var navigatorDrive: NavigatorDrive? = null
    @Volatile private var isRunning = false
    private var lastCameraSpeedKmh = 0

    /**
     * 内部 observer：监听导航数据和摄像头数据
     */
    private val cameraObserver = object : SimpleNavigatorDriveObserver() {

        /**
         * 🆕 自由行驶模式下捕获道路限速（非摄像头数据）
         * 部分 SDK 版本在 NavDriveDataInfo 级别提供 getLimitSpeed / getCurrentSpeedLimit，
         * 比 onCameraInfoUpdate 的摄像头限速更准确（反映真实道路限速而非执法限速）。
         */
        override fun onNavDataInfoUpdate(info: com.tencent.navix.api.model.NavDriveDataInfo?) {
            super.onNavDataInfoUpdate(info)
            if (!isRunning || info == null) return
            try {
                // 尝试反射读取 info 级别的道路限速（自由行驶模式可能无 route，但有 general 数据）
                val roadLimit = try {
                    info.javaClass.getMethod("getLimitSpeed").invoke(info) as? Number
                } catch (_: Exception) { null }
                val roadLimit2 = roadLimit ?: try {
                    info.javaClass.getMethod("getCurrentSpeedLimit").invoke(info) as? Number
                } catch (_: Exception) { null }

                val speedLimit = roadLimit2?.toInt()?.takeIf { it in 10..250 } ?: 0
                if (speedLimit > 0 && speedLimit != lastCameraSpeedKmh) {
                    lastCameraSpeedKmh = speedLimit
                    Log.d(TAG, "🛣️ 道路限速: ${speedLimit}km/h (来自 NavDriveDataInfo)")
                    onSpeedLimitUpdate?.invoke(speedLimit)
                }
            } catch (e: Exception) {
                Log.v(TAG, "onNavDataInfoUpdate 读取限速失败（SDK 版本可能不支持）: ${e.message}")
            }
        }

        override fun onCameraInfoUpdate(
            cameraInfoList: MutableList<com.tencent.navix.api.model.NavCameraInfo>
        ) {
            super.onCameraInfoUpdate(cameraInfoList)
            if (!isRunning) return

            if (cameraInfoList.isEmpty()) {
                if (lastCameraSpeedKmh != 0) {
                    lastCameraSpeedKmh = 0
                    onSpeedLimitUpdate?.invoke(-1)  // 摄像头已过，通知清除
                }
                return
            }

            try {
                val cam = cameraInfoList[0]
                // 距离 ≤ 20m 视为已过，跳过
                val dist = try { cam.javaClass.getMethod("getDistance").invoke(cam) as? Int ?: 0 } catch (_: Exception) { 0 }
                if (dist <= 20) {
                    if (lastCameraSpeedKmh != 0) {
                        lastCameraSpeedKmh = 0
                        onSpeedLimitUpdate?.invoke(-1)
                    }
                    return
                }

                val speedLimit = try {
                    (cam.javaClass.getMethod("getLimitSpeedKMPH").invoke(cam) as? Int)?.takeIf { it > 0 }
                        ?: (cam.javaClass.getMethod("getSpeedLimit").invoke(cam) as? Int)?.takeIf { it > 0 }
                        ?: 0
                } catch (_: Exception) { 0 }

                if (speedLimit > 0 && speedLimit != lastCameraSpeedKmh) {
                    lastCameraSpeedKmh = speedLimit
                    Log.d(TAG, "📷 摄像头限速: ${speedLimit}km/h dist=${dist}m")
                    onSpeedLimitUpdate?.invoke(speedLimit)
                }
            } catch (e: Exception) {
                Log.w(TAG, "摄像头数据解析失败: ${e.message}")
            }
        }
    }

    /**
     * 启动被动监控。
     *
     * 应在进入 AMAP_MOBILE / GOOGLE 导航模式时调用，从主线程调用。
     * 腾讯 SDK 须事先已经完成初始化（[TencentNavSdkBootstrap.ensureInitialized]）。
     *
     * @param app Application 上下文
     */
    @Synchronized
    fun start(app: Application) {
        if (isRunning) return
        try {
            TencentNavSdkBootstrap.ensureInitialized(app)

            val drive = NavigatorZygote.with(app).navigator(NavigatorDrive::class.java)
            drive.registerObserver(cameraObserver)
            navigatorDrive = drive
            isRunning = true
            lastCameraSpeedKmh = 0
            Log.i(TAG, "✅ 腾讯后台限速监控已启动（自由行驶模式）")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 腾讯后台限速监控启动失败（SDK可能不支持无路线监听）: ${e.message}")
        }
    }

    /**
     * 停止被动监控。
     *
     * 应在退出 AMAP_MOBILE / GOOGLE 导航模式时调用，或进入 TENCENT 模式前调用
     * （避免与 [com.example.navipilot.ui.components.TencentNavPage] 的导航实例冲突）。
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        try {
            navigatorDrive?.unregisterObserver(cameraObserver)
        } catch (e: Exception) {
            Log.w(TAG, "注销摄像头观察者失败: ${e.message}")
        }
        navigatorDrive = null
        isRunning = false
        lastCameraSpeedKmh = 0
        Log.i(TAG, "🛑 腾讯后台限速监控已停止")
    }
}
