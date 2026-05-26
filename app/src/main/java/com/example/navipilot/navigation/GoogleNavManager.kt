package com.example.navipilot.navigation

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.navipilot.BuildConfig
import com.example.navipilot.CarrotManFields
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import com.google.android.libraries.navigation.DisplayOptions
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Navigator.RouteStatus
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedAlertSeverity
import com.google.android.libraries.navigation.SpeedingListener
import com.example.navipilot.CarrotManNetworkClient
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import kotlin.math.roundToInt
import org.json.JSONObject
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
) : GoogleNavInfoService.Companion.OnNavInfoListener {
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
    private var speedingListener: SpeedingListener? = null
    // private var routeSegmentListener: (() -> Unit)? = null
    // private var trafficDataListener: (() -> Unit)? = null

    // S7: 网络客户端引用，由 startNavigation 设置，供 RouteChangedListener 重发路线点
    private var _currentNetworkClient: CarrotManNetworkClient? = null

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

        // S7: 注册 NavInfo 服务以接收 TBT 数据
        registerNavUpdates(nav)

        Log.i(TAG, "Navigator 已注入 GoogleNavManager，监听器已注册")
    }

    /**
     * 注册导航监听器：路线变化、剩余时间/距离等
     */
    private fun registerNavigationListeners(nav: Navigator) {
        try {
            // 1. 路线变化监听器 + S5: 重新提取路线点/限速并发送
            routeChangedListener = Navigator.RouteChangedListener {
                Log.i(TAG, "🔄 路线已变化，重新提取路线点和限速")
                extractSpeedLimitFromRoute()
                val points = extractRoutePoints()
                if (points.isNotEmpty()) {
                    _currentNetworkClient?.sendRoutePointsViaTcp(points)
                    Log.i(TAG, "✅ 路线变化后已重新发送路线点: ${points.size}个")
                }
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

            // 3. 速度告警监听器 (SpeedingListener) — SDK 7.0.0 可用
            // 通过 onSpeedingUpdated 的 percentageAboveLimit 反算道路限速
            val speedAlertOptions = SpeedAlertOptions.Builder()
                .setSpeedAlertThresholdPercentage(SpeedAlertSeverity.MINOR, 5f)
                .setSpeedAlertThresholdPercentage(SpeedAlertSeverity.MAJOR, 10f)
                .setSeverityUpgradeDurationSeconds(5.0)
                .build()
            nav.setSpeedAlertOptions(speedAlertOptions)
            speedingListener = SpeedingListener { percentageAboveLimit, _ ->
                onSpeedingUpdated(percentageAboveLimit)
            }
            nav.setSpeedingListener(speedingListener)
            Log.i(TAG, "✅ 已注册 SpeedingListener（通过超速比例反算限速）")

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

    // ================================================================
    // S7: NavInfo 服务注册/注销 — 接收 SDK 7.0.0 的 TBT 转弯数据
    // ================================================================

    /**
     * 注册 NavInfo 服务，使 Navigation SDK 通过 Messenger IPC 发送 NavInfo 消息
     * 失败时自动重试最多 3 次
     */
    private fun registerNavUpdates(nav: Navigator, attempt: Int = 1) {
        try {
            val ok = nav.registerServiceForNavUpdates(
                context.packageName,
                GoogleNavInfoService::class.java.name,
                3  // max remaining steps
            )
            if (ok) {
                GoogleNavInfoService.setListener(this)
                Log.i(TAG, "NavInfo 更新服务已注册 (attempt $attempt)")
            } else if (attempt < 3) {
                val delayMs = attempt * 2000L
                Log.w(TAG, "registerServiceForNavUpdates 返回 false，${delayMs}ms 后重试 ($attempt/3)")
                mainHandler.postDelayed({ registerNavUpdates(nav, attempt + 1) }, delayMs)
            } else {
                Log.w(TAG, "registerServiceForNavUpdates 失败（已重试3次）— 导航转弯数据不可用")
            }
        } catch (e: Exception) {
            if (attempt < 3) {
                val delayMs = attempt * 2000L
                Log.w(TAG, "注册 NavInfo 服务异常: ${e.message}，${delayMs}ms 后重试 ($attempt/3)")
                mainHandler.postDelayed({ registerNavUpdates(nav, attempt + 1) }, delayMs)
            } else {
                Log.e(TAG, "注册 NavInfo 服务失败（已重试3次）: ${e.message}")
            }
        }
    }

    /**
     * 注销 NavInfo 服务
     */
    private fun unregisterNavUpdates() {
        try {
            val ok = navigator?.unregisterServiceForNavUpdates() ?: false
            GoogleNavInfoService.setListener(null)
            if (ok) {
                Log.i(TAG, "NavInfo 更新服务已注销")
            } else {
                Log.d(TAG, "unregisterServiceForNavUpdates 返回 false（可能未注册）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注销 NavInfo 服务失败: ${e.message}")
        }
    }

    // ================================================================
    // S7: OnNavInfoListener 实现 — 将 NavInfo → CarrotManFields
    // ================================================================

    override fun onNavInfoReceived(navInfo: NavInfo) {
        try {
            // 将 NavInfo 数据推送到桥接器，更新 TBT/距离/时间等字段
            dataBridge?.updateFromNavInfo(navInfo)

            // 路线变更时重新提取限速
            if (navInfo.routeChanged) {
                extractSpeedLimitFromRoute()
            }

            // 限速查询：通过当前步骤道路名称 + 坐标
            val roadName = navInfo.currentStep?.fullRoadName ?: ""
            if (roadName.isNotEmpty()) {
                val lat = carrotManFieldsState?.value?.latitude ?: 0.0
                val lon = carrotManFieldsState?.value?.longitude ?: 0.0
                querySpeedLimitForCurrentRoad(lat, lon, roadName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理 NavInfo 失败: ${e.message}")
        }
    }

    // ================================================================
    // 限速查询 — OSM Overpass API + 道路名称推断
    // 替代已移除的 RouteSegment.getSpeedLimit()
    // ================================================================

    /** 限速缓存（key=道路名, value=km/h） */
    private val speedLimitCache = mutableMapOf<String, Int>()
    /** 当前正在查询限速的道路名（防止重复查询） */
    private var pendingSpeedLimitRoad: String = ""
    /** 上次有效限速值 */
    private var lastSpeedLimitKmh: Int = 0

    /**
     * 提取当前道路限速（由 onNavInfoReceived 中的道路名称变化触发）
     *
     * 限速来源（三层策略）:
     * 1) 缓存命中 → 即时返回
     * 2) 道路名称关键词推断 → 快速回退
     * 3) OSM Overpass API → 异步查询，更新缓存后覆盖
     */
    fun extractSpeedLimitFromRoute() {
        // 实际查询由 onNavInfoReceived 通过道路名称触发
        Log.d(TAG, "extractSpeedLimitFromRoute: 由道路名称变化触发")
    }

    /**
     * 查询当前道路限速
     * @param lat 当前纬度
     * @param lon 当前经度
     * @param roadName 当前道路名称
     */
    fun querySpeedLimitForCurrentRoad(lat: Double, lon: Double, roadName: String) {
        if (roadName.isEmpty()) return
        // 同一道路只查询一次
        if (roadName == pendingSpeedLimitRoad) return
        pendingSpeedLimitRoad = roadName

        val currentSpeed = (carrotManFieldsState?.value?.gps_speed ?: 0.0).toInt()

        // 1) 缓存
        speedLimitCache[roadName]?.let { cached ->
            lastSpeedLimitKmh = cached
            dataBridge?.updateSpeedLimit(cached, currentSpeed, roadName)
            Log.d(TAG, "限速(缓存): $cached km/h ($roadName)")
            return
        }

        // 2) 道路名称推断
        val inferred = inferSpeedLimitFromRoadName(roadName)
        if (inferred > 0) {
            speedLimitCache[roadName] = inferred
            lastSpeedLimitKmh = inferred
            dataBridge?.updateSpeedLimit(inferred, currentSpeed, roadName)
            Log.i(TAG, "限速(推断): $inferred km/h ($roadName)")
        }

        // 3) OSM Overpass API 异步查询（更精确）
        queryOverpassSpeedLimit(lat, lon, roadName)
    }

    /**
     * 从道路名称关键词推断限速
     */
    private fun inferSpeedLimitFromRoadName(roadName: String): Int {
        val name = roadName.lowercase().trim()
        return when {
            // 英文高速
            name.contains("motorway") || name.contains("freeway") -> 120
            name.contains("interstate") || name.contains("expressway") -> 110
            name.contains("highway") -> 100
            // 中文高速
            name.contains("高速公路") || name.contains("高速") -> 120
            name.contains("快速路") || name.contains("快速") -> 80
            // 国道
            name.contains("国道") -> 80
            Regex("""\bg\d{1,3}\b""").containsMatchIn(name) -> 80
            // 省道
            name.contains("省道") -> 60
            Regex("""\bs\d{1,3}\b""").containsMatchIn(name) -> 60
            // 县道/乡道
            name.contains("县道") || name.contains("乡道") -> 40
            // 有名称的城市道路
            name.length > 2 -> 50
            // 未知
            else -> 0
        }
    }

    /**
     * OSM Overpass API 异步查询限速
     * 查询坐标周围 25m 内带 maxspeed 标签的道路
     */
    private fun queryOverpassSpeedLimit(lat: Double, lon: Double, roadName: String) {
        val query = "[out:json];way(around:25,${"%.5f".format(lat)},${"%.5f".format(lon)})[\"highway\"][\"maxspeed\"];out tags 1;"
        val url = "https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(query, "utf-8")}"

        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Navipilot/1.0 (Android)")

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val elements = json.optJSONArray("elements") ?: return@Thread
                if (elements.length() == 0) return@Thread

                val tags = elements.optJSONObject(0)?.optJSONObject("tags") ?: return@Thread
                val raw = tags.optString("maxspeed", "") ?: return@Thread
                val speedLimit = parseMaxspeed(raw)

                if (speedLimit > 0) {
                    // 更新缓存
                    speedLimitCache[roadName] = speedLimit
                    lastSpeedLimitKmh = speedLimit
                    val currentSpeed = (carrotManFieldsState?.value?.gps_speed ?: 0.0).toInt()
                    mainHandler.post {
                        dataBridge?.updateSpeedLimit(speedLimit, currentSpeed, roadName)
                    }
                    Log.i(TAG, "限速(OSM): $speedLimit km/h ($roadName)")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Overpass 查询失败: ${e.message} — 使用推断值")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 解析 OSM maxspeed 值
     * 支持: "120", "120 km/h", "60 mph", "80\;120"（分段取最小）
     */
    private fun parseMaxspeed(raw: String): Int {
        val parts = raw.split("\\", ";", " ")
        var minSpeed = Int.MAX_VALUE
        for (part in parts) {
            val cleaned = part.trim().lowercase()
                .replace("km/h", "").replace("kmh", "").replace("kph", "")
                .replace("mph", "").trim()
            val speed = cleaned.toIntOrNull()
            if (speed != null && speed in 5..200) {
                val inKmh = if (part.trim().lowercase().contains("mph")) (speed * 1.609).toInt()
                           else speed
                minSpeed = minOf(minSpeed, inKmh)
            }
        }
        return if (minSpeed == Int.MAX_VALUE) 0 else minSpeed
    }

    // ================================================================
    // SpeedingListener 限速反算 — 通过 SDK 超速比例百分比反推道路限速
    // ================================================================

    /** SpeedingListener 更新节流（避免高频写入 CarrotManFields） */
    private var lastSpeedingUpdateMs = 0L
    private val SPEEDING_UPDATE_INTERVAL_MS = 2000L

    /**
     * SpeedingListener 回调：利用 percentageAboveLimit 反推道路限速
     *
     * 原理：percentageAboveLimit = ((currentSpeed - speedLimit) / speedLimit) × 100
     *      → speedLimit = currentSpeed / (1 + percentageAboveLimit / 100)
     *
     * 由于 percentage 是纯比值，与单位无关。SDK 内部使用 km/h 或 mph 计算，
     * 我们先用 km/h 试算，若结果不合常理则按 mph 重算。
     */
    private fun onSpeedingUpdated(percentageAboveLimit: Float) {
        // 节流
        val now = System.currentTimeMillis()
        if (now - lastSpeedingUpdateMs < SPEEDING_UPDATE_INTERVAL_MS) return
        lastSpeedingUpdateMs = now

        // 必须有 GPS 速度
        val gpsSpeedMs = carrotManFieldsState?.value?.gps_speed ?: return
        if (gpsSpeedMs <= 0.0) return

        val limitKmh = calculateSpeedLimitFromPercentage(gpsSpeedMs.toFloat(), percentageAboveLimit)
        if (limitKmh < 10 || limitKmh > 250) {
            Log.d(TAG, "SpeedingListener 反算限速 $limitKmh km/h 不合理，跳过")
            return
        }

        val roadName = carrotManFieldsState?.value?.szPosRoadName ?: ""
        val currentSpeedKmh = (gpsSpeedMs * 3.6).toInt()

        // 更新缓存
        if (roadName.isNotEmpty()) speedLimitCache[roadName] = limitKmh
        lastSpeedLimitKmh = limitKmh

        // 推送到 CarrotManFields
        dataBridge?.updateSpeedLimit(limitKmh, currentSpeedKmh, roadName)
        Log.i(TAG, "限速(SpeedingListener): $limitKmh km/h (当前速度=${currentSpeedKmh}km/h, 超速${"%.1f".format(percentageAboveLimit)}%)")
    }

    /**
     * 从 GPS 速度和超速百分比反算限速
     *
     * 先按 km/h 试算，结果若在合理范围(20~200)内则直接使用；
     * 若不在范围内，按 mph 试算后转 km/h。
     * SDK 会自动根据设备地区决定使用 km/h 或 mph，我们通过试探法确定。
     */
    private fun calculateSpeedLimitFromPercentage(gpsSpeedMs: Float, pct: Float): Int {
        if (pct <= 0f) return 0

        // 方案 A：假设 SDK 使用 km/h
        val speedKmh = gpsSpeedMs * 3.6f
        val limitKmh = speedKmh / (1f + pct / 100f)
        if (limitKmh in 20f..200f) return limitKmh.roundToInt()

        // 方案 B：假设 SDK 使用 mph（US/UK）
        val speedMph = gpsSpeedMs * 2.237f
        val limitMph = speedMph / (1f + pct / 100f)
        val limitFromMph = limitMph * 1.609f
        if (limitFromMph in 20f..200f) return limitFromMph.roundToInt()

        // 兜底
        return limitKmh.roundToInt()
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
    @Suppress("DEPRECATION")
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

        // 保存网络客户端引用，供 RouteChangedListener 使用
        _currentNetworkClient = networkClient

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
            val displayOptions = DisplayOptions()
                .showTrafficLights(true)
                .showStopSigns(true)
            val pendingRoute = nav.setDestinations(listOf(destination), RoutingOptions(), displayOptions)

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
    @Suppress("DEPRECATION")
    fun startNavigationByPlaceId(
        placeId: String,
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

        // 保存网络客户端引用，供 RouteChangedListener 使用
        _currentNetworkClient = networkClient

        try {
            val destination = Waypoint.builder()
                .setPlaceIdString(placeId)
                .build()

            val displayOptions = DisplayOptions()
                .showTrafficLights(true)
                .showStopSigns(true)
            val pendingRoute = nav.setDestinations(listOf(destination), RoutingOptions(), displayOptions)

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

                        // 🆕 提取并发送路线点到 comma3 设备 (TCP 7709)
                        if (networkClient != null) {
                            extractAndSendRoutePoints(networkClient)
                        } else {
                            Log.w(TAG, "⚠️ networkClient 为 null，跳过路线点发送 (PlaceID)")
                        }

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
                            // 🆕 P0: 从路线段提取目的地坐标（Place ID 方式无显式坐标）
                            val goalLat = try {
                                nav.routeSegments?.lastOrNull()
                                    ?.latLngs?.lastOrNull()?.latitude ?: 0.0
                            } catch (_: Exception) { 0.0 }
                            val goalLon = try {
                                nav.routeSegments?.lastOrNull()
                                    ?.latLngs?.lastOrNull()?.longitude ?: 0.0
                            } catch (_: Exception) { 0.0 }
                            state.value = state.value.copy(
                                goalPosX = goalLon,
                                goalPosY = goalLat,
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
                // 清理 SpeedingListener（set null 取消注册）
                speedingListener?.let { nav.setSpeedingListener(null) }

                // 官方示例：清理模拟器位置 + 释放 navigator 资源
                try {
                    nav.simulator?.unsetUserLocation()
                } catch (e: Exception) {
                    Log.w(TAG, "simulator.unsetUserLocation: ${e.message}")
                }
                try {
                    nav.cleanup()
                } catch (e: Exception) {
                    Log.w(TAG, "navigator.cleanup: ${e.message}")
                }
            }

            // S7: 注销 NavInfo 服务
            unregisterNavUpdates()

            routeChangedListener = null
            remainingTimeOrDistanceChangedListener = null
            dataBridge = null
            _navigator = null
            _currentNetworkClient = null
            isInitialized = false

            Log.i(TAG, "✅ GoogleNavManager 资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 清理资源失败: ${e.message}", e)
        }
    }
}
