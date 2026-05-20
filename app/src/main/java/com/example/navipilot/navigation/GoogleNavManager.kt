package com.example.navipilot.navigation

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.navipilot.BuildConfig
import com.example.navipilot.CarrotManFields
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.Waypoint
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Navigator.RouteStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google 导航管理器
 *
 * 负责 Google Navigation SDK 的初始化、路线规划和导航控制
 * Google Maps 使用 WGS-84 坐标系，与内部存储一致，无需转换
 */
class GoogleNavManager(
    private val context: Context,
    private val carrotManFieldsState: MutableState<CarrotManFields>?
) {
    companion object {
        private const val TAG = "GoogleNavManager"
    }

    private var _navigator: Navigator? = null
    val navigator: Navigator? get() = _navigator
    private var isInitialized = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // 数据桥接器：将导航数据写入 CarrotManFields
    private var dataBridge: GoogleNavDataBridge? = null

    // 监听器引用（用于清理）
    private var routeChangedListener: Navigator.RouteChangedListener? = null
    private var remainingTimeOrDistanceChangedListener: Navigator.RemainingTimeOrDistanceChangedListener? = null
    // Note: Google Navigation SDK 7.0.0 移除了以下监听器 API
    // private var locationListener: ((android.location.Location) -> Unit)? = null
    // private var speedingListener: ((com.google.android.libraries.navigation.SpeedingUpdatedInfo) -> Unit)? = null
    // private var routeSegmentListener: (() -> Unit)? = null
    // private var trafficDataListener: (() -> Unit)? = null

    fun isReady(): Boolean = isInitialized && navigator != null

    /**
     * 外部设置 navigator 引用（由 GoogleNavPage 在 NavigatorListener.onNavigatorReady 中调用）
     */
    fun setNavigator(nav: Navigator) {
        _navigator = nav
        isInitialized = true
        nav.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

        // 初始化数据桥接器
        if (dataBridge == null) {
            dataBridge = GoogleNavDataBridge(carrotManFieldsState)
        }

        // 注册导航监听器
        registerNavigationListeners(nav)

        Log.i(TAG, "Navigator 已注入 GoogleNavManager，监听器已注册")
    }

    /**
     * 注册导航监听器：路线变化、剩余时间/距离等
     */
    private fun registerNavigationListeners(nav: Navigator) {
        try {
            // 1. 路线变化监听器
            routeChangedListener = Navigator.RouteChangedListener {
                Log.i(TAG, "🔄 路线已变化")
                // 路线变化时可以重新获取路线信息
            }
            nav.addRouteChangedListener(routeChangedListener)
            Log.i(TAG, "✅ 已注册路线变化监听器")

            // 2. 剩余时间/距离监听器
            remainingTimeOrDistanceChangedListener = Navigator.RemainingTimeOrDistanceChangedListener {
                try {
                    val timeInfo = nav.timeAndDistanceList
                    if (timeInfo.isNotEmpty()) {
                        val remaining = timeInfo[0]
                        dataBridge?.updateRemaining(
                            distMeters = remaining.meters.toLong(),
                            timeSeconds = remaining.seconds.toLong()
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "更新剩余时间/距离失败: ${e.message}")
                }
            }
            nav.addRemainingTimeOrDistanceChangedListener(
                /* remainingDistance= */ 100,  // 每 100 米更新一次
                /* remainingTime= */ 60,      // 每 60 秒更新一次
                remainingTimeOrDistanceChangedListener
            )
            Log.i(TAG, "✅ 已注册剩余时间/距离监听器")

            // Note: Google Navigation SDK 7.0.0 移除了以下监听器 API
            // 位置、超速、路段变化等监听器在 7.0.0 中不再可用
            // 如需这些功能，请考虑降级到 6.x 版本或使用替代方案
            Log.i(TAG, "⚠️ Google Navigation SDK 7.0.0 已移除位置/超速/路段监听器 API")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 注册导航监听器失败: ${e.message}", e)
        }
    }

    /**
     * 提取当前路线的所有坐标点（WGS-84）
     * 用于发送至 comma3 设备 (TCP 7709)
     */
    fun extractRoutePoints(): List<Pair<Double, Double>> {
        val nav = navigator ?: return emptyList()

        return try {
            val routeSegments = nav.routeSegments
            val points = mutableListOf<Pair<Double, Double>>()

            routeSegments?.forEach { segment ->
                segment.latLngs?.forEach { latLng ->
                    points.add(Pair(latLng.longitude, latLng.latitude))
                }
            }

            Log.i(TAG, "✅ 提取路线点: ${points.size} 个坐标")
            points
        } catch (e: Exception) {
            Log.e(TAG, "❌ 提取路线点失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 提取并发送路线点到 comma3 设备
     * @param networkClient 网络客户端，用于发送数据到设备
     */
    fun extractAndSendRoutePoints(networkClient: com.example.navipilot.CarrotManNetworkClient?) {
        val points = extractRoutePoints()

        if (points.isNotEmpty()) {
            networkClient?.sendRoutePointsViaTcp(points)

            // 调试日志（前3个点）
            points.take(3).forEachIndexed { i, (lon, lat) ->
                Log.d(TAG, "[$i] lon=${"%.6f".format(lon)}, lat=${"%.6f".format(lat)}")
            }

            Log.i(TAG, "✅ 路线点已发送至设备 (TCP 7709): ${points.size}个点")
        } else {
            Log.w(TAG, "⚠️ 无路线点可发送")
        }
    }

    private fun postFieldsMutate(block: (CarrotManFields) -> CarrotManFields) {
        val st = carrotManFieldsState ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            st.value = block(st.value)
        } else {
            mainHandler.post { st.value = block(st.value) }
        }
    }

    /**
     * 初始化 Google Navigation SDK
     * 必须在主线程调用，通常在页面 Composable 时触发
     */
    fun initializeNavigator(activity: Activity, onReady: () -> Unit = {}, onError: (Int, Int) -> Unit = { _, _ -> }) {
        if (isInitialized) {
            Log.w(TAG, "Navigator 已初始化，跳过")
            onReady()
            return
        }

        try {
            NavigationApi.getNavigator(
                activity,
                object : NavigatorListener {
                    override fun onNavigatorReady(navigator: Navigator) {
                        Log.i(TAG, "Google Navigation SDK 初始化成功")
                        this@GoogleNavManager._navigator = navigator
                        isInitialized = true

                        // 设置退出时行为
                        navigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

                        onReady()
                    }

                    override fun onError(errorCode: Int) {
                        Log.e(TAG, "Google 导航错误: code=$errorCode")
                        val msg = when (errorCode) {
                            NavigationApi.ErrorCode.NOT_AUTHORIZED ->
                                "API Key 无效或未授权使用 Navigation API"
                            NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED ->
                                "用户未接受导航服务条款"
                            else -> "导航错误: $errorCode"
                        }
                        Log.e(TAG, msg)
                        // 根据 errorCode 判断是否需要调用 onError
                        onError(errorCode, 0)
                    }
                }
            )
            Log.i(TAG, "Google Navigation SDK 初始化请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Google Navigation SDK 初始化失败: ${e.message}", e)
        }
    }

    /**
     * 设置目的地并开始导航
     *
     * @param startLat 起点纬度 (WGS-84)，0.0 表示使用当前位置
     * @param startLon 起点经度 (WGS-84)，0.0 表示使用当前位置
     * @param destLat 目的地纬度 (WGS-84)
     * @param destLon 目的地经度 (WGS-84)
     * @param destName 目的地名称
     * @param simulate 是否模拟行程（debug 构建默认 true，release 默认 false）
     * @param onRouteError 路线错误回调
     * @param networkClient 可选网络客户端，用于发送路线点到设备
     */
    fun startNavigation(
        startLat: Double = 0.0,
        startLon: Double = 0.0,
        destLat: Double,
        destLon: Double,
        destName: String,
        simulate: Boolean = false,  // 默认使用真实 GPS 导航
        routeTimeoutMs: Long = 15_000,
        onNavigationStarted: (() -> Unit)? = null,
        onRouteError: ((String) -> Unit)? = null,
        networkClient: com.example.navipilot.CarrotManNetworkClient? = null
    ) {
        val nav = navigator ?: run {
            Log.e(TAG, "Navigator 未初始化，无法开始导航")
            onRouteError?.invoke("导航服务未初始化")
            return
        }

        if (destLat == 0.0 || destLon == 0.0) {
            Log.e(TAG, "目的地坐标无效: lat=$destLat, lon=$destLon")
            onRouteError?.invoke("目的地坐标无效")
            return
        }

        Log.i(TAG, "开始导航: $destName")
        Log.i(TAG, "  起点: ($startLat, $startLon)")
        Log.i(TAG, "  终点: ($destLat, $destLon)")
        Log.i(TAG, "  模拟: $simulate")
        Log.i(TAG, "  路线超时: ${routeTimeoutMs}ms")

        try {
            // 构建路线请求 — 始终只设置目的地，SDK 自动使用当前 GPS 作为起点
            // 使用 setDestinations（复数）而非 setDestination（单数），确保 SDK 正确触发路线计算
            Log.i(TAG, "使用当前GPS位置作为起点")
            val destination = Waypoint.builder().setLatLng(destLat, destLon).build()
            val pendingRoute = nav.setDestinations(listOf(destination))

            Log.i(TAG, "等待路线计算结果...")

            if (pendingRoute == null) {
                val msg = "无法创建路线请求（pendingRoute 为 null）"
                Log.e(TAG, "❌ $msg")
                onRouteError?.invoke(msg)
                return
            }

            val finished = AtomicBoolean(false)
            val timeoutRunnable = Runnable {
                if (!finished.compareAndSet(false, true)) return@Runnable
                val msg = "路线规划超时（>${routeTimeoutMs}ms）。通常是网络/Google 服务不可用导致 SDK 长时间等待后才返回 NO_ROUTE_FOUND。"
                Log.w(TAG, "⏱️ $msg")
                try {
                    nav.stopGuidance()
                    nav.clearDestinations()
                } catch (e: Exception) {
                    Log.w(TAG, "超时时清理导航状态失败: ${e.message}")
                }
                onRouteError?.invoke(msg)
            }
            if (routeTimeoutMs > 0) {
                mainHandler.postDelayed(timeoutRunnable, routeTimeoutMs)
            }

            pendingRoute.setOnResultListener { code ->
                if (!finished.compareAndSet(false, true)) {
                    Log.i(TAG, "收到路线状态回调（已结束/超时忽略）: $code")
                    return@setOnResultListener
                }
                mainHandler.removeCallbacks(timeoutRunnable)

                Log.i(TAG, "收到路线状态回调: $code")
                when (code) {
                    RouteStatus.OK -> {
                        Log.i(TAG, "✅ 路线规划成功，开始导航: $destName")

                        // 启用语音播报
                        nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)

                        // 🆕 提取并发送路线点到 comma3 设备 (TCP 7709)
                        if (networkClient != null) {
                            extractAndSendRoutePoints(networkClient)
                        } else {
                            Log.w(TAG, "⚠️ networkClient 为 null，跳过路线点发送")
                        }

                        // 模拟行程（参考官方示例：仅在 debug 构建中启用）
                        if (simulate) {
                            Log.i(TAG, "✅ 启动模拟导航（5倍速）")
                            nav.simulator.simulateLocationsAlongExistingRoute(
                                SimulationOptions().speedMultiplier(5f)
                            )
                        } else {
                            Log.i(TAG, "✅ 使用真实 GPS 位置进行导航")
                        }

                        // 开始导航
                        nav.startGuidance()
                        Log.i(TAG, "导航已启动")

                        // 更新 CarrotManFields
                        carrotManFieldsState?.let { state ->
                            state.value = state.value.copy(
                                goalPosX = destLon,
                                goalPosY = destLat,
                                szGoalName = destName,
                                isNavigating = true,
                                source_last = "google_nav"
                            )
                        }

                        onNavigationStarted?.invoke()
                    }
                    RouteStatus.ROUTE_CANCELED -> {
                        val msg = "路线规划已取消"
                        Log.w(TAG, "⚠️ $msg")
                        onRouteError?.invoke(msg)
                    }
                    RouteStatus.NO_ROUTE_FOUND -> {
                        val msg = "未找到从起点到终点的路线，请检查坐标是否正确或网络连接"
                        Log.w(TAG, "⚠️ $msg")
                        Log.w(TAG, "   起点: ($startLat, $startLon)")
                        Log.w(TAG, "   终点: ($destLat, $destLon)")
                        onRouteError?.invoke(msg)
                    }
                    RouteStatus.NETWORK_ERROR -> {
                        val msg = "网络错误，无法规划路线"
                        Log.w(TAG, "⚠️ $msg")
                        onRouteError?.invoke(msg)
                    }
                    else -> {
                        val msg = "路线规划失败: $code"
                        Log.w(TAG, "⚠️ $msg")
                        onRouteError?.invoke(msg)
                    }
                }
            }

        } catch (e: Exception) {
            val msg = "启动导航失败: ${e.message}"
            Log.e(TAG, "❌ $msg", e)
            onRouteError?.invoke(msg)
        }
    }

    /**
     * 通过 Place ID 设置目的地（推荐方式，可获得更准确的路线和 ETA）
     */
    fun startNavigationByPlaceId(
        placeId: String,
        destName: String,
        simulate: Boolean = false,  // 默认使用真实 GPS 导航
        routeTimeoutMs: Long = 15_000,
        onNavigationStarted: (() -> Unit)? = null,
        onRouteError: ((String) -> Unit)? = null
    ) {
        val nav = navigator ?: run {
            Log.e(TAG, "Navigator 未初始化，无法开始导航")
            onRouteError?.invoke("导航服务未初始化")
            return
        }

        try {
            val destination = Waypoint.builder()
                .setPlaceIdString(placeId)
                .build()

            val pendingRoute = nav.setDestination(destination)

            if (pendingRoute == null) {
                val msg = "无法创建路线请求（pendingRoute 为 null）"
                Log.e(TAG, "❌ $msg")
                onRouteError?.invoke(msg)
                return
            }

            val finished = AtomicBoolean(false)
            val timeoutRunnable = Runnable {
                if (!finished.compareAndSet(false, true)) return@Runnable
                val msg = "路线规划超时（>${routeTimeoutMs}ms）"
                Log.w(TAG, "⏱️ $msg")
                try {
                    nav.stopGuidance()
                    nav.clearDestinations()
                } catch (e: Exception) {
                    Log.w(TAG, "超时时清理导航状态失败: ${e.message}")
                }
                onRouteError?.invoke(msg)
            }
            if (routeTimeoutMs > 0) {
                mainHandler.postDelayed(timeoutRunnable, routeTimeoutMs)
            }

            pendingRoute.setOnResultListener { code ->
                if (!finished.compareAndSet(false, true)) return@setOnResultListener
                mainHandler.removeCallbacks(timeoutRunnable)

                when (code) {
                    RouteStatus.OK -> {
                        nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)

                        // 模拟行程（参考官方示例：仅在 debug 构建中启用）
                        if (simulate) {
                            Log.i(TAG, "✅ 启动模拟导航（5倍速）- Place ID")
                            nav.simulator.simulateLocationsAlongExistingRoute(
                                SimulationOptions().speedMultiplier(5f)
                            )
                        } else {
                            Log.i(TAG, "✅ 使用真实 GPS 位置进行导航 - Place ID")
                        }

                        nav.startGuidance()

                        carrotManFieldsState?.let { state ->
                            state.value = state.value.copy(
                                szGoalName = destName,
                                isNavigating = true,
                                source_last = "google_nav"
                            )
                        }

                        onNavigationStarted?.invoke()
                    }
                    RouteStatus.ROUTE_CANCELED -> onRouteError?.invoke("路线规划已取消")
                    RouteStatus.NO_ROUTE_FOUND -> onRouteError?.invoke("未找到路线（PlaceId）")
                    RouteStatus.NETWORK_ERROR -> onRouteError?.invoke("网络错误，无法规划路线")
                    else -> onRouteError?.invoke("路线规划失败: $code")
                }
            }
        } catch (e: Exception) {
            val msg = "通过 Place ID 启动导航失败: ${e.message}"
            Log.e(TAG, "❌ $msg", e)
            onRouteError?.invoke(msg)
        }
    }

    /**
     * 停止导航
     */
    fun stopNavigation() {
        try {
            navigator?.stopGuidance()
            navigator?.clearDestinations()

            // 通知数据桥接器导航已停止
            dataBridge?.onNavigationStopped()

            carrotManFieldsState?.let { state ->
                state.value = state.value.copy(
                    isNavigating = false,
                    source_last = "google_nav"
                )
            }
            Log.i(TAG, "🛑 Google 导航已停止")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 停止 Google 导航失败: ${e.message}", e)
        }
    }

    /**
     * 清理资源
     */
    fun destroy() {
        try {
            stopNavigation()

            // 清理监听器
            navigator?.let { nav ->
                routeChangedListener?.let {
                    nav.removeRouteChangedListener(it)
                }
                remainingTimeOrDistanceChangedListener?.let {
                    nav.removeRemainingTimeOrDistanceChangedListener(it)
                }
                // Note: Google Navigation SDK 7.0.0 移除了以下监听器 API
                // locationListener, speedingListener, routeSegmentListener 不再需要移除
            }

            routeChangedListener = null
            remainingTimeOrDistanceChangedListener = null
            dataBridge = null
            _navigator = null
            isInitialized = false

            Log.i(TAG, "✅ GoogleNavManager 资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 清理资源失败: ${e.message}", e)
        }
    }
}
