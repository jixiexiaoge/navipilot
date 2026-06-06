package com.example.navipilot.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.MutableState
import com.amap.api.navi.ParallelRoadListener
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.InnerNaviInfo
import com.amap.api.navi.model.NaviInfo
import com.example.navipilot.AmapBroadcastHandlers
import com.example.navipilot.CarrotManFields
import com.example.navipilot.LaneInfo
import com.example.navipilot.ui.utils.localized
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高德手机导航 SDK（AMapNavi）→ [CarrotManFields] 数据桥接。
 * 继承 [SimpleNaviListener] 以适配高德 11.x 合并 listener 接口。
 *
 * 算路须在 [onInitNaviSuccess] 之后由 UI 通过 [pendingCalculateRoute] 触发（与官方 NaviDemo 一致）。
 *
 * **TTS 说明**：
 * 之前由 [AmapMobileNavPage]（已移除）使用 `setUseInnerVoice(false, true)` 启动导航，
 * 导航文字通过 [onGetNavigationText] 回调到此桥接器，由内置 TTS 引擎朗读。
 * 摄像头提示音（第二参数 true）仍由 SDK 内部处理，无需应用层干预。
 *
 * @param context  用于创建 TTS 引擎，推荐传 ApplicationContext
 */
class AmapNavDataBridge(
    private val carrotManFieldsState: MutableState<CarrotManFields>?,
    private val context: Context? = null
) : SimpleNaviListener(), ParallelRoadListener {

    /** 算路成功后由 UI 层注册：内部应调用 [com.amap.api.navi.AMapNavi.startNavi] */
    var onRouteCalculated: (() -> Unit)? = null

    /**
     * 引擎 [onInitNaviSuccess] 后执行：应调用 [com.amap.api.navi.AMapNavi.calculateDriveRoute]。
     * 须在 [com.amap.api.navi.AMapNavi.addAMapNaviListener] 之前赋值。
     */
    var pendingCalculateRoute: (() -> Unit)? = null

    /** 主线程 Toast / Snackbar（由 Composable 注入） */
    var showUserMessage: ((String) -> Unit)? = null

    /** 多路径算路成功且路线数大于 1 时回调（供 UI 调用 [com.amap.api.navi.AMapNavi.selectRouteId]） */
    var onMultiRouteIds: ((IntArray) -> Unit)? = null

    /** 路口放大图 Bitmap 回调（供 UI 渲染路口大图） */
    var onCrossBitmap: ((android.graphics.Bitmap?) -> Unit)? = null

    /** 模型路口大图 Bitmap 回调（供 UI 渲染 3D 模型路口大图） */
    var onModeCrossBitmap: ((android.graphics.Bitmap?) -> Unit)? = null


    /**
     * 每次算路成功（含偏航/拥堵重算）在 [notifyRouteReady] 之前回调一次。
     * 此时 [com.amap.api.navi.AMapNavi.getNaviPaths] 通常已可用，供 UI 拉取距离/时间摘要。
     */
    var onCalculateRouteResultDetail: ((AMapCalcRouteResult?) -> Unit)? = null

    /** AMapNavi 实例引用（由外部设置，供路径数据查询） */
    @Volatile
    var navi: com.amap.api.navi.AMapNavi? = null

    private val routeSuccessOnce = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 交通设施描述节流，避免高频回调刷屏 */
    private var lastTrafficFacilitySummary: String = ""
    private var lastTrafficFacilityPostMs: Long = 0L

    /** InnerNavi 节流（同签名短时间重复则跳过） */
    private var lastInnerNaviSig: String = ""
    private var lastInnerNaviPostMs: Long = 0L
    
    /** 位置更新日志节流（避免刷屏） */
    private var lastLocationLogMs: Long = 0L

    companion object {
        private const val TAG = "AmapNavBridge"

        /** 高德导航图标类型 → 与车机/Python 对齐的 nTBTTurnType（简化映射，可随路测扩充） */
        fun mapAmapIconToTurnType(iconType: Int): Int {
            return when (iconType) {
                0, 1 -> 51
                2 -> 12
                3 -> 13
                4 -> 102
                5 -> 101
                6 -> 17
                7 -> 19
                8, 9 -> 153
                10 -> 102
                11 -> 101
                12 -> 131
                13 -> 132
                14 -> 153
                15 -> 201
                16 -> 14
                else -> 51
            }
        }

        /** 车道数组 → [LaneInfo]（与车机 laneInfoList 对齐） */
        fun mapAmapLanesToLaneInfoList(lanes: Array<out AMapLaneInfo>?): List<LaneInfo> {
            if (lanes.isNullOrEmpty()) return emptyList()
            return lanes.mapIndexed { index, lane ->
                val typeStr = try {
                    val arr = lane.getLaneTypeIdArray()
                    if (arr != null && arr.isNotEmpty()) String(arr) else index.toString()
                } catch (_: Exception) {
                    index.toString()
                }
                LaneInfo(
                    id = typeStr,
                    isRecommended = try {
                        lane.isRecommended
                    } catch (_: Exception) {
                        false
                    },
                    driveWayNumber = try {
                        lane.laneCount
                    } catch (_: Exception) {
                        0
                    },
                    driveWayLaneExtended = "0",
                    trafficLaneExtendedNew = 0,
                    trafficLaneType = 0
                )
            }
        }

        /** 根据道路限速粗分道路类型（与车机展示对齐的简化规则） */
        private fun inferRoadcate(speedLimitKmh: Int, currentRoadcate: Int, roadName: String): Int {
            if (roadName.isNotEmpty()) {
                val isHighway = roadName.contains("高速") || roadName.contains("快速") ||
                    roadName.contains("环线") || roadName.contains("高架") ||
                    roadName.matches(Regex(".*[GS]\\d{1,4}.*"))
                if (isHighway) return 10
            }
            return when {
                speedLimitKmh >= 100 -> 10
                speedLimitKmh >= 80 -> 10
                speedLimitKmh > 0 -> 6
                else -> currentRoadcate
            }
        }

        /**
         * 从高德 TTS 播报文本中提取道路限速（km/h）。
         *
         * 匹配的高德 SDK 播报模式（源自真机实测）：
         *   "前方道路限速60公里，请注意控制车速"
         *   "进入限速区域，限速80公里每小时"
         *   "当前限速60，您已超速"
         *   "限速120公里"
         *   "限速解除" / "解除限速" → 返回 0（表示清除）
         *
         * @return 提取的限速 km/h（10~250），0 表示解除限速，-1 表示文本中无限速信息
         */
        fun extractSpeedLimitFromTts(text: String): Int {
            // 限速解除
            if (text.contains("限速解除") || text.contains("解除限速") ||
                text.contains("不限速") || text.contains("限速取消")) {
                return 0
            }
            // 提取 "限速" 后跟随的数字（允许中间有空白/冒号/顿号等）
            val match = Regex("限速[^\\d]*(\\d+)").find(text)
            val value = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return -1
            // 合理范围过滤：10~250 km/h
            return if (value in 10..250) value else -1
        }
    }

    // ── TTS 引擎（用于朗读 setUseInnerVoice=false 后的导航文字） ────
    @Volatile private var ttsEngine: TextToSpeech? = null
    @Volatile private var ttsReady = false

    /** 延迟初始化 TTS（首次调用 onGetNavigationText 时触发） */
    private fun ensureTts() {
        if (ttsEngine != null || context == null) return
        try {
            ttsEngine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = ttsEngine?.setLanguage(Locale.CHINA)
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ttsReady) ttsReady = true  // 语言不支持时仍尝试
                    Log.d(TAG, "AMap TTS 初始化成功 ttsReady=$ttsReady")
                } else {
                    Log.w(TAG, "AMap TTS 初始化失败 status=$status")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AMap TTS 创建失败: ${e.message}")
        }
    }

    private fun speakNavText(text: String) {
        if (!ttsReady || ttsEngine == null) return
        try {
            // QUEUE_ADD：不打断正在播报的语音，排队等待
            ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "amap_nav_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak 失败: ${e.message}")
        }
    }

    private fun postUserMessage(msg: String) {
        val cb = showUserMessage ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb(msg)
        } else {
            mainHandler.post { cb(msg) }
        }
    }

    private fun userAlert(zh: String, en: String) {
        postUserMessage(localized(zh, en))
    }

    /** 释放页面前调用：取消待算路、清 Toast 回调、重置「仅启动一次导航」标志 */
    fun resetSessionState() {
        pendingCalculateRoute = null
        showUserMessage = null
        onMultiRouteIds = null
        onCrossBitmap = null
        onModeCrossBitmap = null
        onCalculateRouteResultDetail = null
        routeSuccessOnce.set(false)
        lastTrafficFacilitySummary = ""
        lastTrafficFacilityPostMs = 0L
        lastInnerNaviSig = ""
        lastInnerNaviPostMs = 0L
        lastLocationLogMs = 0L
    }

    /** 释放 TTS 引擎资源（应在页面 onDispose 时调用） */
    fun destroyTts() {
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
        ttsEngine = null
        ttsReady = false
    }

    private fun postFieldsMutate(block: (MutableState<CarrotManFields>) -> Unit) {
        val st = carrotManFieldsState ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(st)
        } else {
            mainHandler.post { block(st) }
        }
    }

    private fun isRoundaboutTurn(turnType: Int): Boolean {
        return turnType in setOf(131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142)
    }

    private fun resolveTollRole(tollName: String): Pair<String, String> {
        if (tollName.isBlank()) return "" to ""
        val lower = tollName.lowercase()
        val isEntrance = tollName.contains("入口") || lower.contains("entrance") || lower.contains("entry")
        val isExit = tollName.contains("出口") || lower.contains("exit")
        return when {
            isEntrance && !isExit -> tollName to ""
            isExit && !isEntrance -> "" to tollName
            else -> "" to ""
        }
    }

    fun onNavigationStopped() {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                isNavigating = false,
                source_last = "amap_mobile",
                amapSdkCrossVisible = false,
                amapSdkModeCrossVisible = false,
                amapParallelElevatedFlag = -1,
                amapParallelMainSideFlag = -1
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // TTS 播报文字拦截（setUseInnerVoice(false, true) 后生效）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 高德 SDK 准备播报导航文字时回调此方法（需 setUseInnerVoice(false, true)）。
     *
     * 两件事：
     *  1. 用 TTS 引擎朗读文本（替代 SDK 内置语音）
     *  2. 用正则提取限速信息，更新 [CarrotManFields.nRoadLimitSpeed]
     *
     * @param type 播报类型（0=常规导航，1+=特殊提示；具体值依 SDK 版本而定）
     * @param text 待播报的完整文本，例如"前方道路限速60公里，请注意控制车速"
     */
    override fun onGetNavigationText(type: Int, text: String?) {
        super.onGetNavigationText(type, text)
        if (text.isNullOrBlank()) return

        Log.d(TAG, "🔊 TTS[type=$type]: $text")

        // 1. 朗读（延迟初始化 TTS 引擎）
        ensureTts()
        speakNavText(text)

        // 2. 提取限速
        val extracted = extractSpeedLimitFromTts(text)
        when {
            extracted > 0 -> {
                Log.i(TAG, "🎙️ TTS 限速提取成功: ${extracted}km/h ← \"$text\"")
                postFieldsMutate { s ->
                    val cur = s.value
                    s.value = cur.copy(
                        nRoadLimitSpeed = extracted,
                        roadcate = inferRoadcate(extracted, cur.roadcate, cur.szPosRoadName),
                        roadType = inferRoadcate(extracted, cur.roadcate, cur.szPosRoadName),
                        source_last = "amap_mobile"
                    )
                }
            }
            extracted == 0 -> {
                // 限速解除：不立即清零（保留上次值直到 onUpdateNaviSpeedLimitSection 更新）
                Log.d(TAG, "🎙️ TTS 检测到限速解除")
            }
            // extracted == -1：文本中无限速信息，忽略
        }
    }

    /**
     * 旧版 SDK 兼容接口（已弃用但仍需 override 以防 SDK 回调旧版本）。
     * 逻辑与 [onGetNavigationText(Int, String?)] 一致。
     */
    @Deprecated("旧版 SDK 接口，优先使用带 type 参数的版本")
    override fun onGetNavigationText(text: String?) {
        @Suppress("DEPRECATION")
        super.onGetNavigationText(text)
        if (text.isNullOrBlank()) return
        // 复用新版逻辑（type 传 -1 表示未知类型）
        onGetNavigationText(-1, text)
    }

    override fun notifyParallelRoad(status: AMapNaviParallelRoadStatus?) {
        if (status == null || carrotManFieldsState == null) return
        val elevatedFlag = try { status.getmElevatedRoadStatusFlag() } catch (_: Exception) { -1 }
        val mainSideFlag = try { status.getmParallelRoadStatusFlag() } catch (_: Exception) { -1 }
        Log.i(TAG, "主辅路状态: elevated=$elevatedFlag mainSide=$mainSideFlag")
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                amapParallelElevatedFlag = elevatedFlag,
                amapParallelMainSideFlag = mainSideFlag,
                source_last = "amap_mobile"
            )
        }
    }

    override fun onInitNaviSuccess() {
        super.onInitNaviSuccess()
        val pending = pendingCalculateRoute
        pendingCalculateRoute = null
        if (pending != null) {
            try {
                pending.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "pendingCalculateRoute 执行失败: ${e.message}", e)
                routeSuccessOnce.set(false)
                userAlert("算路请求异常", "Routing request failed (see log)")
            }
        } else {
            Log.w(TAG, "onInitNaviSuccess: pendingCalculateRoute 为空，跳过算路")
        }
    }

    override fun onInitNaviFailure() {
        super.onInitNaviFailure()
        Log.e(TAG, "导航引擎初始化失败")
        routeSuccessOnce.set(false)
        pendingCalculateRoute = null
        userAlert("高德导航引擎初始化失败", "AMap navigation engine init failed")
    }

    override fun onLocationChange(location: AMapNaviLocation?) {
        if (location == null || carrotManFieldsState == null) return
        val coord = location.coord ?: return
        val (wgsLat, wgsLon) = CoordinateConverter.gcj02ToWgs84(coord.latitude, coord.longitude)
        val spd = location.speed
        val kph = (spd * 3.6f).toInt().coerceAtLeast(0)
        
        // 🔧 优化4: 添加位置更新日志（每10秒记录一次，避免刷屏）
        val now = System.currentTimeMillis()
        if (now - lastLocationLogMs > 10000) {
            Log.d(TAG, "📍 位置更新: WGS84(${"%.6f".format(wgsLat)},${"%.6f".format(wgsLon)}) 速度:${kph}km/h 精度:${location.accuracy}m")
            lastLocationLogMs = now
        }
        
        postFieldsMutate { s ->
            val cur = s.value
            val bearing = location.bearing.toDouble()
            s.value = cur.copy(
                latitude = wgsLat,
                longitude = wgsLon,
                heading = bearing,
                accuracy = location.accuracy.toDouble(),
                gps_speed = (spd.coerceAtLeast(0f)).toDouble(),
                nPosSpeed = spd.toDouble(),
                // 🆕 补充导航位置字段（与 vpPosPointLat/Lon 对齐）
                vpPosPointLat = wgsLat,
                vpPosPointLon = wgsLon,
                nPosAngle = bearing,
                isNavigating = true,
                source_last = "amap_mobile"
            )
        }

    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null || carrotManFieldsState == null) return
        val turnType = mapAmapIconToTurnType(info.iconType)
        val tbtText = info.nextRoadName.orEmpty()
        val resolvedTurn =
            if (turnType != 51 || tbtText.isBlank()) turnType
            else TurnTypeTextInference.inferTurnTypeFromText(tbtText).takeIf { it >= 0 } ?: turnType
        val curRoad = info.currentRoadName.orEmpty()

        // 🆕 P0: 提取高速出口信息（单次反射，避免字段不一致）
        val (exitDir, exitName) = try {
            val exitInfo = info.javaClass.getMethod("getHighwayExitInfo").invoke(info)
            val dir = exitInfo?.javaClass?.getMethod("getExitDirection")?.invoke(exitInfo) as? String ?: ""
            val name = exitInfo?.javaClass?.getMethod("getExitName")?.invoke(exitInfo) as? String ?: ""
            dir to name
        } catch (_: Exception) {
            "" to ""
        }

        // 🆕 P0: 提取环岛信息
        val roundAbout = try {
            info.javaClass.getMethod("getRoundAboutExitNumber").invoke(info) as? Int ?: -1
        } catch (_: Exception) { -1 }

        val roundTotal = try {
            info.javaClass.getMethod("getRoundAboutTotalExit").invoke(info) as? Int ?: -1
        } catch (_: Exception) { -1 }

        postFieldsMutate { s ->
            val cur = s.value
            // 🆕 目的地坐标（反射方式提取，兼容旧版 SDK）
            val goalPosX = try { info.javaClass.getMethod("getEndLng").invoke(info) as? Double ?: cur.goalPosX } catch (_: Exception) { cur.goalPosX }
            val goalPosY = try { info.javaClass.getMethod("getEndLat").invoke(info) as? Double ?: cur.goalPosY } catch (_: Exception) { cur.goalPosY }
            val szGoalName = try { info.javaClass.getMethod("getEndName").invoke(info) as? String ?: "" } catch (_: Exception) { cur.szGoalName }
            // 🆕 尝试从 NaviInfo 反射读取限速（部分 SDK 版本在 NaviInfo 中包含 limitSpeed）
            // ⚠️ 仅作为首次启动兜底（nRoadLimitSpeed <= 0），
            //    不覆盖 onUpdateNaviSpeedLimitSection 提供的真实路段限速。
            val naviInfoLimit = try {
                (info.javaClass.getMethod("getLimitSpeed").invoke(info) as? Number)?.toInt()?.takeIf { it in 10..250 } ?: 0
            } catch (_: Exception) { 0 }

            // 限速优先级（降序）：
            //   P0: onUpdateNaviSpeedLimitSection — 路段分段限速（真实限速源）
            //   P1: NaviInfo 反射限速 — 仅首次兜底
            //   P2: 路名推断 — 仅首次兜底
            //   P3: 保留现有值
            val (startupLimit, startupRoadcate) = when {
                cur.nRoadLimitSpeed > 0 -> cur.nRoadLimitSpeed to cur.roadcate
                naviInfoLimit > 0 -> {
                    Log.d(TAG, "🚦 NaviInfo 反射读取限速（首次兜底）: ${naviInfoLimit}km/h")
                    naviInfoLimit to inferRoadcate(naviInfoLimit, cur.roadcate, curRoad)
                }
                curRoad.isNotEmpty() -> {
                    val rc = inferRoadcate(0, cur.roadcate, curRoad)
                    val lim = if (rc == 10) 120 else 60
                    lim to rc
                }
                else -> cur.nRoadLimitSpeed to cur.roadcate
            }
            s.value = cur.copy(
                nGoPosDist = info.pathRetainDistance,
                nGoPosTime = info.pathRetainTime,
                nTBTDist = info.curStepRetainDistance,
                nTBTTurnType = resolvedTurn,
                szTBTMainText = tbtText,
                szNearDirName = tbtText,
                szPosRoadName = curRoad,
                nPosSpeed = info.currentSpeed.toDouble(),
                // nRoadLimitSpeed 优先由 onUpdateNaviSpeedLimitSection 提供真实值；
                // 若尚未触发（导航起步阶段）则用路名推断兜底，确保 comma3 不忽略数据
                nRoadLimitSpeed = startupLimit,
                roadcate = startupRoadcate,
                roadType = startupRoadcate,
                // 🆕 P0: 补充 NOA 增强字段
                exitNameInfo = exitName.ifBlank { cur.exitNameInfo },
                goalPosX = goalPosX,
                goalPosY = goalPosY,
                szGoalName = szGoalName,
                isNavigating = true,
                source_last = "amap_mobile"
            )
        }
    }

    override fun onUpdateNaviSpeedLimitSection(speedLimitKmh: Int) {
        super.onUpdateNaviSpeedLimitSection(speedLimitKmh)
        if (carrotManFieldsState == null) return
        val limit = speedLimitKmh.coerceAtLeast(0)
        if (limit <= 0) return
        postFieldsMutate { s ->
            val cur = s.value
            val road = cur.szPosRoadName
            s.value = cur.copy(
                nRoadLimitSpeed = limit,
                roadcate = inferRoadcate(limit, cur.roadcate, road),
                source_last = "amap_mobile"
            )
        }
    }

    override fun updateCameraInfo(cameras: Array<out AMapNaviCameraInfo>?) {
        if (carrotManFieldsState == null) return
        // 无摄像头 → 清除所有 SDI 字段
        if (cameras.isNullOrEmpty()) {
            postFieldsMutate { s ->
                s.value = s.value.copy(
                    nSdiType = -1, nSdiSpeedLimit = 0, nSdiDist = 0,
                    nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0,
                    source_last = "amap_mobile"
                )
            }
            return
        }
        val c = cameras[0]
        val dist = c.cameraDistance.coerceAtLeast(0)
        val shouldClear = dist <= 20
        val sdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(c.cameraType)

        postFieldsMutate { s ->
            val cur = s.value
            // 主摄像头
            var updated = if (shouldClear) {
                cur.copy(nSdiType = -1, nSdiSpeedLimit = 0, nSdiDist = 0, source_last = "amap_mobile")
            } else {
                cur.copy(
                    nSdiType = sdiType,
                    nSdiSpeedLimit = c.cameraSpeed,
                    nSdiDist = dist,
                    nAmapCameraType = c.cameraType,
                    nRoadLimitSpeed = if (cur.nRoadLimitSpeed <= 0 && c.cameraSpeed > 0) c.cameraSpeed else cur.nRoadLimitSpeed,
                    source_last = "amap_mobile"
                )
            }
            // 第二摄像头 → nSdiPlus
            if (cameras.size >= 2) {
                val c2 = cameras[1]
                val d2 = c2.cameraDistance.coerceAtLeast(0)
                updated = if (d2 <= 20) {
                    updated.copy(nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0)
                } else {
                    val sdi2 = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(c2.cameraType)
                    updated.copy(nSdiPlusType = sdi2, nSdiPlusSpeedLimit = c2.cameraSpeed, nSdiPlusDist = d2)
                }
            } else {
                updated = updated.copy(nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0)
            }
            s.value = updated
        }
    }

    /** 区间测速 / 多摄像头：第三参数为区间状态（与 nSdiBlockType 等对齐，路测可微调） */
    override fun updateIntervalCameraInfo(
        start: AMapNaviCameraInfo?,
        end: AMapNaviCameraInfo?,
        sectionFlag: Int
    ) {
        super.updateIntervalCameraInfo(start, end, sectionFlag)
        if (carrotManFieldsState == null) return
        postFieldsMutate { s ->
            val cur = s.value
            val s0 = start
            val s1 = end
            val avg = when {
                s0 != null && s0.averageSpeed >= 0 -> s0.averageSpeed
                s1 != null && s1.averageSpeed >= 0 -> s1.averageSpeed
                else -> cur.nSdiAverageSpeed
            }
            val intervalRemain = when {
                s0 != null && s0.intervalRemainDistance >= 0 -> s0.intervalRemainDistance
                s1 != null && s1.intervalRemainDistance >= 0 -> s1.intervalRemainDistance
                else -> 0
            }
            val primary = s0 ?: s1
            val sdiType = primary?.let { AmapBroadcastHandlers.mapAmapCameraTypeToSdi(it.cameraType) } ?: cur.nSdiType
            val spd = primary?.cameraSpeed ?: cur.nSdiSpeedLimit
            val dist = primary?.cameraDistance?.coerceAtLeast(0) ?: cur.nSdiDist
            s.value = cur.copy(
                nSdiType = sdiType,
                nSdiSpeedLimit = spd,
                nSdiDist = dist,
                nAmapCameraType = primary?.cameraType ?: cur.nAmapCameraType,
                nSdiBlockType = sectionFlag,
                nSdiBlockSpeed = spd,        // 🆕 区间限速
                nSdiAverageSpeed = avg,
                nSdiBlockDist = intervalRemain,
                nSdiSection = intervalRemain,      // 🆕 区间测速剩余距离，与 Tencent 的 distToEnd 对齐
                source_last = "amap_mobile"
            )
        }
    }

    override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) {
        super.onNaviRouteNotify(data)
        if (data == null || carrotManFieldsState == null) return
        val desc = listOfNotNull(data.reason, data.subTitle).filter { it.isNotBlank() }.joinToString(" · ")
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                situationType = data.notifyType,
                situationDistance = data.distance.coerceAtLeast(0),
                situationDescription = desc.ifBlank { cur.situationDescription },
                lastUpdateTime = System.currentTimeMillis(),
                source_last = "amap_mobile"
            )
        }
        Log.i(TAG, "路线事件 notifyType=${data.notifyType} ok=${data.isSuccess} $desc")
    }

    override fun onServiceAreaUpdate(areas: Array<out AMapServiceAreaInfo>?) {
        super.onServiceAreaUpdate(areas)
        if (areas.isNullOrEmpty() || carrotManFieldsState == null) return
        val a = areas[0]
        postFieldsMutate { s ->
            val cur = s.value
            val name = try {
                a.name.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val remain = try {
                a.remainDist
            } catch (_: Exception) {
                -1
            }
            val typ = try {
                a.type
            } catch (_: Exception) {
                -1
            }
            s.value = cur.copy(
                sapaName = name.ifBlank { cur.sapaName },
                sapaDist = remain,
                sapaType = typ,
                sapaNum = areas.size,
                lastUpdateTime = System.currentTimeMillis(),
                source_last = "amap_mobile"
            )
        }
    }

    @Deprecated("高德 SDK 中已标记弃用，保留以实现路况设施监听")
    override fun OnUpdateTrafficFacility(facilities: Array<out AMapNaviTrafficFacilityInfo>?) {
        @Suppress("DEPRECATION")
        super.OnUpdateTrafficFacility(facilities)
        if (facilities.isNullOrEmpty() || carrotManFieldsState == null) return
        val f = facilities[0]
        postFieldsMutate { s ->
            val cur = s.value
            val typ = try {
                f.type
            } catch (_: Exception) {
                -1
            }
            val dist = try {
                f.distance
            } catch (_: Exception) {
                0
            }
            val lim = try {
                f.limitSpeed
            } catch (_: Exception) {
                0
            }
            // 🆕 P0: 提取收费站信息 (type=4 通常表示收费站)
            val tollName = try {
                if (typ == 4) {
                    f.javaClass.getMethod("getName").invoke(f) as? String ?: ""
                } else ""
            } catch (_: Exception) { "" }

            val (tollEntranceName, tollExitName) = resolveTollRole(tollName)
            val summary = buildString {
                append("设施×${facilities.size} type=$typ dist=${dist}m")
                if (lim > 0) append(" limit=${lim}")
                if (tollName.isNotBlank()) append(" name=$tollName")
            }
            val now = System.currentTimeMillis()
            val skip = summary == lastTrafficFacilitySummary && now - lastTrafficFacilityPostMs < 1800L
            if (!skip) {
                lastTrafficFacilitySummary = summary
                lastTrafficFacilityPostMs = now
                s.value = cur.copy(
                    trafficDescription = summary,
                    // 🆕 P0: 收费站信息（仅在名称中出现入口/出口语义时更新）
                    tencentSlice = cur.tencentSlice.copy(
                        tollEntranceName = tollEntranceName.ifBlank { cur.tencentSlice.tollEntranceName },
                        tollExitName = tollExitName.ifBlank { cur.tencentSlice.tollExitName }
                    ),
                    lastUpdateTime = now,
                    source_last = "amap_mobile"
                )
            }
        }
    }

    @Deprecated("高德 SDK 中已标记弃用，保留以实现车道大图数据监听")
    override fun showLaneInfo(lanes: Array<out AMapLaneInfo>?, bg: ByteArray?, rec: ByteArray?) {
        @Suppress("DEPRECATION")
        super.showLaneInfo(lanes, bg, rec)
        applyLaneList(mapAmapLanesToLaneInfoList(lanes))
    }

    override fun showLaneInfo(lane: AMapLaneInfo?) {
        super.showLaneInfo(lane)
        if (lane == null) {
            applyLaneList(emptyList())
        } else {
            applyLaneList(mapAmapLanesToLaneInfoList(arrayOf(lane)))
        }
    }

    override fun hideLaneInfo() {
        super.hideLaneInfo()
        applyLaneList(emptyList())
    }

    private fun applyLaneList(list: List<LaneInfo>) {
        if (carrotManFieldsState == null) return
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                laneInfoList = list,
                nLaneCount = list.size,
                lastUpdateTime = System.currentTimeMillis(),
                source_last = "amap_mobile"
            )
        }
    }

    override fun onInnerNaviInfoUpdate(info: InnerNaviInfo?) {
        super.onInnerNaviInfoUpdate(info)
        if (info == null || carrotManFieldsState == null) return
        val now = System.currentTimeMillis()
        val sig = "${info.driveDist}_${info.crossIconType}_${info.iconType}"
        if (sig == lastInnerNaviSig && now - lastInnerNaviPostMs < 400L) {
            return
        }
        lastInnerNaviSig = sig
        lastInnerNaviPostMs = now

        // 🆕 P0: 提取远处方向名（第二转弯）
        val farDirName = try {
            info.javaClass.getMethod("getNextNextRoadName").invoke(info) as? String ?: ""
        } catch (_: Exception) { "" }

        postFieldsMutate { s ->
            val cur = s.value
            val crossIcon = info.crossIconType
            val nextTurn = mapAmapIconToTurnType(crossIcon)
            s.value = cur.copy(
                amapIcon = info.iconType,
                amapIconNext = crossIcon,
                nTBTTurnTypeNext = nextTurn,
                nTBTDistNext = info.driveDist.coerceAtLeast(0),
                // 🆕 P0: 补充 TBT 增强字段
                szFarDirName = farDirName.ifBlank { cur.szFarDirName },
                szTBTMainTextNext = farDirName,
                lastUpdateTime = now,
                source_last = "amap_mobile"
            )
        }
    }


    override fun showCross(aMapNaviCross: AMapNaviCross?) {
        super.showCross(aMapNaviCross)
        Log.d(TAG, "showCross")
        val bitmap = try {
            aMapNaviCross?.bitmap
        } catch (_: Exception) {
            null
        }
        onCrossBitmap?.invoke(bitmap)
        postFieldsMutate { s ->
            s.value = s.value.copy(amapSdkCrossVisible = true, source_last = "amap_mobile")
        }
    }

    override fun hideCross() {
        super.hideCross()
        Log.d(TAG, "hideCross")
        onCrossBitmap?.invoke(null)
        postFieldsMutate { s ->
            s.value = s.value.copy(amapSdkCrossVisible = false, source_last = "amap_mobile")
        }
    }

    override fun showModeCross(aMapModelCross: AMapModelCross?) {
        super.showModeCross(aMapModelCross)
        Log.d(TAG, "showModeCross")
        val bitmap = try {
            aMapModelCross?.picBuf1?.let { bytes ->
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
        onModeCrossBitmap?.invoke(bitmap)
        postFieldsMutate { s ->
            s.value = s.value.copy(amapSdkModeCrossVisible = true, source_last = "amap_mobile")
        }
    }

    override fun hideModeCross() {
        super.hideModeCross()
        Log.d(TAG, "hideModeCross")
        onModeCrossBitmap?.invoke(null)
        postFieldsMutate { s ->
            s.value = s.value.copy(amapSdkModeCrossVisible = false, source_last = "amap_mobile")
        }
    }

    override fun onArriveDestination() {
        Log.i(TAG, "到达目的地")
        onNavigationStopped()
    }

    override fun onStartNavi(naviType: Int) {
        super.onStartNavi(naviType)
        val typeName = when (naviType) {
            0 -> "GPS导航"
            1 -> "模拟导航"
            2 -> "实时导航"
            else -> "未知类型($naviType)"
        }
        Log.i(TAG, "开始导航: $typeName")
    }

    override fun onEndEmulatorNavi() {
        super.onEndEmulatorNavi()
        Log.i(TAG, "模拟导航结束")
        onNavigationStopped()
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        super.onGpsOpenStatus(enabled)
        if (!enabled) {
            userAlert("请打开设备定位（GPS）", "Please enable device location (GPS)")
        }
    }

    override fun onGpsSignalWeak(isWeak: Boolean) {
        super.onGpsSignalWeak(isWeak)
        if (isWeak) {
            userAlert("GPS信号弱，请到空旷地区行驶", "GPS signal weak, please drive in open area")
        }
        Log.w(TAG, "GPS信号弱: $isWeak")
        // 🆕 P0: 记录 GPS 信号状态
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                tencentSlice = cur.tencentSlice.copy(
                    gpsSignalStatus = if (isWeak) 1 else 0  // 0=正常 1=弱 2=无信号
                ),
                source_last = "amap_mobile"
            )
        }
    }

    override fun onReCalculateRouteForYaw() {
        super.onReCalculateRouteForYaw()
        Log.i(TAG, "偏航重算路线")
        userAlert("检测到偏航，正在重新规划路线", "Recalculating route due to deviation")
        postFieldsMutate { s ->
            s.value = s.value.copy(situationType = 2) // 偏航
        }
    }

    override fun onReCalculateRouteForTrafficJam() {
        super.onReCalculateRouteForTrafficJam()
        Log.i(TAG, "拥堵重算路线")
        userAlert("前方拥堵，正在重新规划路线", "Recalculating due to traffic jam")
        postFieldsMutate { s ->
            s.value = s.value.copy(situationType = 3) // 拥堵
        }
    }

    private fun notifyRouteReady() {
        if (!routeSuccessOnce.compareAndSet(false, true)) return
        try {
            onRouteCalculated?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "onRouteCalculated 回调异常: ${e.message}", e)
            routeSuccessOnce.set(false)
        }
    }

    @Deprecated("高德 SDK 中已标记弃用，保留以兼容仅返回 routeIds 的算路回调")
    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        @Suppress("DEPRECATION")
        super.onCalculateRouteSuccess(routeIds)
        notifyRouteReady()
    }

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        super.onCalculateRouteSuccess(result)
        try {
            onCalculateRouteResultDetail?.invoke(result)
        } catch (e: Exception) {
            Log.w(TAG, "onCalculateRouteResultDetail: ${e.message}")
        }
        val ids = result?.routeid
        if (ids != null && ids.size > 1) {
            try {
                onMultiRouteIds?.invoke(ids)
            } catch (e: Exception) {
                Log.w(TAG, "onMultiRouteIds: ${e.message}")
            }
        }
        notifyRouteReady()
    }

    override fun onCalculateRouteFailure(p0: AMapCalcRouteResult?) {
        super.onCalculateRouteFailure(p0)
        val code = p0?.errorCode
        val desc = p0?.errorDescription.orEmpty().ifBlank { p0?.errorDetail.orEmpty() }
        Log.e(TAG, "算路失败: $code $desc")
        routeSuccessOnce.set(false)
        val base = localized("算路失败", "Route calculation failed")
        val detail = listOfNotNull(code?.let { "[$it]" }, desc.takeIf { it.isNotBlank() }).joinToString(" ")
        postUserMessage(if (detail.isNotBlank()) "$base $detail" else base)
    }

    /**
     * 🆕 P1: 提取高德路线点坐标（WGS-84）
     * 参考腾讯 TencentNavDataBridge.extractRoutePoints()
     * 用于发送至 comma3 设备 (TCP 7709)
     *
     * @param navi AMapNavi 实例
     * @param routeId 路线 ID（通常为 0，多路线时可指定）
     * @return 路线点列表 (lon, lat) WGS-84 坐标
     */
    fun extractRoutePointsFromAmap(
        navi: com.amap.api.navi.AMapNavi?,
        routeId: Int = 0
    ): List<Pair<Double, Double>> {
        if (navi == null || carrotManFieldsState == null) {
            Log.w(TAG, "⚠️ 无法提取路线点: navi 或 carrotManFieldsState 为 null")
            return emptyList()
        }

        return try {
            @Suppress("DEPRECATION")
            val paths = navi.naviPaths
            if (paths.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ 无路线数据可提取")
                return emptyList()
            }

            val path = paths[routeId] ?: paths.values.firstOrNull()
            if (path == null) {
                Log.w(TAG, "⚠️ 无法获取路线 ID $routeId")
                return emptyList()
            }

            // 提取所有路段的坐标点
            val allSteps: List<Any>? = try {
                @Suppress("UNCHECKED_CAST")
                path.steps as? List<Any>
            } catch (_: Exception) {
                null
            }

            if (allSteps == null || allSteps.isEmpty()) {
                Log.w(TAG, "⚠️ 路线无步骤数据")
                return emptyList()
            }

            val routePoints = mutableListOf<Pair<Double, Double>>()

            allSteps.forEach { step ->
                try {
                    val coords = step.javaClass.getMethod("getCoords").invoke(step) as? List<*>
                    coords?.forEach { coord ->
                        try {
                            val coordObj = coord ?: return@forEach
                            val lat = coordObj.javaClass.getMethod("getLatitude").invoke(coordObj) as? Double ?: 0.0
                            val lon = coordObj.javaClass.getMethod("getLongitude").invoke(coordObj) as? Double ?: 0.0
                            if (lat != 0.0 && lon != 0.0) {
                                // GCJ-02 → WGS-84 转换
                                val (wgsLat, wgsLon) = CoordinateConverter.gcj02ToWgs84(lat, lon)
                                routePoints.add(wgsLon to wgsLat)  // 注意：存储为 (lon, lat)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "跳过坐标点: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "跳过路段: ${e.message}")
                }
            }

            if (routePoints.isNotEmpty()) {
                Log.i(TAG, "✅ 高德路线点提取成功: ${routePoints.size} 个点")

                // 调试：打印前3个点
                routePoints.take(3).forEachIndexed { i, (lon, lat) ->
                    Log.d(TAG, "  [$i] lon=${"%.6f".format(lon)}, lat=${"%.6f".format(lat)}")
                }
            } else {
                Log.w(TAG, "⚠️ 未提取到有效路线点")
            }

            routePoints
        } catch (e: Exception) {
            Log.e(TAG, "❌ 高德路线点提取失败: ${e.message}", e)
            emptyList()
        }
    }
}
