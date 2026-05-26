package com.example.navipilot.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.navipilot.CarrotManFields
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.navigation.Navigator

/**
 * Google Navigation SDK → CarrotManFields 数据桥接器
 *
 * 实现 Navigator.Listener 接口，将导航数据写入中央数据模型
 * Google Maps 使用 WGS-84 坐标系，与内部存储一致，无需转换
 */
class GoogleNavDataBridge(
    private val carrotManFieldsState: MutableState<CarrotManFields>?
) {
    companion object {
        private const val TAG = "GoogleNavBridge"

        /** 从路名推断道路类别（Google Nav SDK 不直接提供 roadcate） */
        fun inferRoadcateFromName(roadName: String, current: Int): Int {
            if (roadName.isEmpty()) return current
            val u = roadName.uppercase()
            if (u.contains("HIGHWAY") || u.contains("FREEWAY") || u.contains("INTERSTATE") ||
                u.contains("MOTORWAY") || u.contains("EXPRESSWAY") ||
                roadName.contains("高速") || roadName.contains("快速路") ||
                roadName.matches(Regex(".*[GS]\\d{1,4}.*"))
            ) return 10
            return if (current == 8) 6 else current  // 未知路名降级为普通道路
        }

        /**
         * Google Maneuver type → nTBTTurnType 映射
         *
         * Google Navigation SDK Maneuver 类型（来源于 SDK 文档）:
         * - UNKNOWN = 0
         * - DEPART = 1
         * - DESTINATION = 2
         * - DESTINATION_LEFT = 3
         * - DESTINATION_RIGHT = 4
         * - STRAIGHT = 5
         * - TURN_LEFT = 6
         * - TURN_RIGHT = 7
         * - TURN_KEEP_LEFT = 8
         * - TURN_KEEP_RIGHT = 9
         * - TURN_SLIGHT_LEFT = 10
         * - TURN_SLIGHT_RIGHT = 11
         * - TURN_SHARP_LEFT = 12
         * - TURN_SHARP_RIGHT = 13
         * - TURN_U_TURN_CLOCKWISE = 14
         * - TURN_U_TURN_COUNTERCLOCKWISE = 15
         * - ROUNDABOUT_CLOCKWISE = 16
         * - ROUNDABOUT_COUNTERCLOCKWISE = 17
         * - FERRY_BOAT = 18
         * - ENTER_MERGE = 19
         * - EXIT_MERGE = 20
         * - ENTER_ROUNDABOUT = 22
         * - EXIT_ROUNDABOUT = 23
         * - END_FERRY_SEGMENT = 24
         * - DEFAULT = 25
         *
         * nTBTTurnType 对照：
         * 51=直行, 12=左转, 13=右转, 102=左前方, 101=右前方, 131=环岛, 14=掉头 等
         */
        fun mapManeuverToTurnType(maneuver: Int): Int {
            return when (maneuver) {
                // 直行/出发
                1 /*DEPART*/, 5 /*STRAIGHT*/ -> 51
                // 左转系列
                6 /*TURN_LEFT*/ -> 12
                // 右转系列
                7 /*TURN_RIGHT*/ -> 13
                // 左前方 (keep left / slight left)
                8 /*TURN_KEEP_LEFT*/, 10 /*TURN_SLIGHT_LEFT*/ -> 102
                // 右前方 (keep right / slight right)
                9 /*TURN_KEEP_RIGHT*/, 11 /*TURN_SLIGHT_RIGHT*/ -> 101
                // 左后方 (sharp left)
                12 /*TURN_SHARP_LEFT*/ -> 17
                // 右后方 (sharp right)
                13 /*TURN_SHARP_RIGHT*/ -> 19
                // 掉头
                14 /*TURN_U_TURN_CLOCKWISE*/, 15 /*TURN_U_TURN_COUNTERCLOCKWISE*/ -> 14
                // 环岛（进入）
                16 /*ROUNDABOUT_CLOCKWISE*/, 17 /*ROUNDABOUT_COUNTERCLOCKWISE*/,
                22 /*ENTER_ROUNDABOUT*/ -> 131
                // 环岛（驶出）
                23 /*EXIT_ROUNDABOUT*/ -> 132
                // 渡船
                18 /*FERRY_BOAT*/, 24 /*END_FERRY_SEGMENT*/ -> 153
                // 进入/驶出合流道
                19 /*ENTER_MERGE*/ -> 101
                20 /*EXIT_MERGE*/ -> 102
                // 目的地
                2 /*DESTINATION*/, 3 /*DESTINATION_LEFT*/, 4 /*DESTINATION_RIGHT*/ -> 201
                else -> 51
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * S7: 从 NavInfo 对象更新 TBT 全部字段
     *
     * 由 GoogleNavManager.onNavInfoReceived() 调用，每个 NavInfo 消息触发一次。
     * 提取当前步骤操作、道路名称、距离、剩余步骤等信息写入 CarrotManFields。
     */
    fun updateFromNavInfo(navInfo: NavInfo) {
        when (navInfo.navState) {
            NavState.ENROUTE -> {
                val currentStep = navInfo.currentStep ?: return

                val maneuver = currentStep.maneuver
                val turnType = mapManeuverToTurnType(maneuver)
                val roadName = currentStep.fullRoadName ?: ""
                val distanceToTurn = (navInfo.distanceToCurrentStepMeters ?: 0).toInt().coerceAtLeast(0)

                val remainingSteps = navInfo.remainingSteps
                val nextStep = if (remainingSteps.isNotEmpty()) remainingSteps[0] else null
                val nextTurnType = nextStep?.let { mapManeuverToTurnType(it.maneuver) } ?: -1
                val nextDist = nextStep?.distanceFromPrevStepMeters?.coerceAtLeast(0) ?: -1
                val nextRoadName = nextStep?.fullRoadName

                val finalDist = (navInfo.distanceToFinalDestinationMeters ?: 0).toLong()
                val finalTime = (navInfo.timeToFinalDestinationSeconds ?: 0).toLong()

                // 尝试从 StepInfo 反射读取限速（SDK 7.0 可能暴露此方法）
                val stepSpeedLimit = try {
                    val v = currentStep.javaClass.getMethod("getSpeedLimitKph").invoke(currentStep)
                    (v as? Int)?.takeIf { it > 0 } ?: (v as? Double)?.toInt()?.takeIf { it > 0 } ?: 0
                } catch (_: Exception) { 0 }

                // 单次合并更新，减少 Compose recompose 次数
                postFieldsMutate { s ->
                    val cur = s.value
                    val gpsSpeedKmh = (cur.gps_speed * 3.6).toInt().coerceAtLeast(0)
                    // 限速优先级：StepInfo反射 > SpeedingListener缓存 > 道路类别推断兜底
                    // 关键：nRoadLimitSpeed=0 时 comma3 会忽略整个导航数据块，必须给非零值
                    val inferredRoadcate = if (roadName.isNotEmpty())
                        inferRoadcateFromName(roadName, cur.roadcate) else cur.roadcate
                    val newLimit = when {
                        stepSpeedLimit > 0 -> stepSpeedLimit
                        cur.nRoadLimitSpeed > 0 -> cur.nRoadLimitSpeed   // 保持上次超速时获得的值
                        inferredRoadcate == 10 -> 120  // 高速/快速路推断兜底
                        roadName.isNotEmpty() -> 60    // 有路名但未知限速 → 60km/h 兜底
                        else -> 0
                    }
                    // roadcate：有限速时用限速推断，否则用路名推断
                    val newRoadcate = when {
                        newLimit >= 100 -> 10
                        newLimit > 0 -> inferredRoadcate.takeIf { it > 0 } ?: 6
                        else -> inferredRoadcate
                    }
                    s.value = cur.copy(
                        nTBTTurnType = turnType,
                        nTBTDist = distanceToTurn,
                        szTBTMainText = roadName.ifEmpty { cur.szTBTMainText },
                        szNearDirName = roadName.ifEmpty { cur.szNearDirName },
                        szPosRoadName = if (roadName.isNotEmpty()) roadName else cur.szPosRoadName,
                        szFarDirName = nextRoadName ?: cur.szFarDirName,
                        nTBTDistNext = if (nextDist >= 0) nextDist else cur.nTBTDistNext,
                        nTBTTurnTypeNext = if (nextTurnType >= 0) nextTurnType else cur.nTBTTurnTypeNext,
                        szTBTMainTextNext = nextRoadName ?: cur.szTBTMainTextNext,
                        nGoPosDist = if (finalDist > 0) finalDist.toInt() else cur.nGoPosDist,
                        nGoPosTime = if (finalDist > 0) finalTime.toInt() else cur.nGoPosTime,
                        nRoadLimitSpeed = newLimit,
                        roadcate = newRoadcate,
                        roadType = newRoadcate,
                        // GPS 回填导航位置字段（SDK 不提供道路吸附坐标）
                        vpPosPointLat = if (cur.latitude != 0.0) cur.latitude else cur.vpPosPointLat,
                        vpPosPointLon = if (cur.longitude != 0.0) cur.longitude else cur.vpPosPointLon,
                        nPosAngle = cur.heading,
                        nPosSpeed = gpsSpeedKmh.toDouble(),
                        isNavigating = true,
                        source_last = "google_nav"
                    )
                }
            }
            NavState.STOPPED -> onNavigationStopped()
            NavState.REROUTING -> Log.d(TAG, "Google Nav 重新规划路线中...")
        }
    }

    private fun postFieldsMutate(block: (MutableState<CarrotManFields>) -> Unit) {
        val st = carrotManFieldsState ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(st)
        } else {
            mainHandler.post { block(st) }
        }
    }

    fun onNavigationStopped() {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                isNavigating = false,
                source_last = "google_nav"
            )
        }
    }

    /**
     * 将 SDI 字段重置为默认值，用于 Google 导航开始或重新开始时调用
     *
     * SDK 7.0.0 中不再需要手动重置 SDI，由 [updateFromNavInfo] 自动管理。
     */
    @Deprecated("SDK 7.0.0 不再需要手动重置，由 updateFromNavInfo 自动处理")
    fun resetSdiFields() {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                nSdiType = -1,
                nSdiSpeedLimit = 0,
                nSdiDist = 0,
                nSdiSection = 0,
                nSdiBlockType = -1,
                nSdiBlockSpeed = 0,
                nSdiBlockDist = 0,
                source_last = "google_nav"
            )
        }
    }

    @Deprecated("SDK 7.0.0 位置数据由 LocationSensorManager/NavInfo 提供，不直接调用")
    fun updateLocation(lat: Double, lon: Double, heading: Float, speed: Float, accuracy: Float = 0f) {
        postFieldsMutate { s ->
            val cur = s.value
            val headingD = heading.toDouble()
            s.value = s.value.copy(
                latitude = lat,
                longitude = lon,
                heading = headingD,
                gps_speed = speed.toDouble(),
                // 🆕 P0: 补充 GPS 完整字段
                accuracy = accuracy.toDouble(),
                nPosAngle = if (speed > 0.5f && heading > 0f) headingD else cur.nPosAngle,
                // 🆕 补充导航位置字段
                vpPosPointLat = lat,
                vpPosPointLon = lon,
                source_last = "google_nav"
            )
        }
    }

    @Deprecated("SDK 7.0.0 使用 updateFromNavInfo(NavInfo) 替代")
    fun updateNaviInfo(turnType: Int, distance: Long, roadName: String, destLat: Double, destLon: Double, destName: String) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                nTBTTurnType = turnType,
                nTBTDist = distance.toInt(),
                szTBTMainText = roadName,
                // 🆕 P0: 补充 TBT 增强字段（szNearDirName 与 szTBTMainText 相同）
                szNearDirName = roadName,
                goalPosX = destLon,
                goalPosY = destLat,
                szGoalName = destName,
                isNavigating = true,
                source_last = "google_nav"
            )
        }
    }

    /**
     * 🆕 P0: 更新 TBT 增强字段（下一转弯信息）
     * @param szFarDirName 远处方向名（下一转弯后的道路）
     * @param nTBTDistNext 下一转弯距离
     * @param nTBTTurnTypeNext 下一转弯类型
     * @param szTBTMainTextNext 下一转弯指令文本，非 null 则更新
     */
    fun updateTbtEnhanced(
        szFarDirName: String? = null,
        nTBTDistNext: Int = -1,
        nTBTTurnTypeNext: Int = -1,
        szTBTMainTextNext: String? = null
    ) {
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                szFarDirName = szFarDirName ?: cur.szFarDirName,
                nTBTDistNext = if (nTBTDistNext >= 0) nTBTDistNext else cur.nTBTDistNext,
                nTBTTurnTypeNext = if (nTBTTurnTypeNext >= 0) nTBTTurnTypeNext else cur.nTBTTurnTypeNext,
                szTBTMainTextNext = szTBTMainTextNext ?: cur.szTBTMainTextNext,
                source_last = "google_nav"
            )
        }
    }

    fun updateRemaining(distMeters: Long, timeSeconds: Long) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                nGoPosDist = distMeters.toInt(),
                nGoPosTime = timeSeconds.toInt(),
                source_last = "google_nav"
            )
        }
    }

    /**
     * 🆕 P0: 更新限速信息（增强版，包含道路分类）
     * @param speedLimitKmh 道路限速 (km/h)
     * @param currentSpeedKmh 当前速度 (km/h)
     * @param roadName 道路名称（用于推断道路类型）
     */
    fun updateSpeedLimit(speedLimitKmh: Int, currentSpeedKmh: Int, roadName: String = "") {
        // 🆕 P0: 根据限速和路名推断道路类别
        val roadcate = when {
            speedLimitKmh >= 100 -> 10  // 高速
            roadName.contains("Highway", ignoreCase = true) -> 10
            roadName.contains("Freeway", ignoreCase = true) -> 10
            roadName.contains("Interstate", ignoreCase = true) -> 10
            roadName.contains("Expressway", ignoreCase = true) -> 10
            roadName.contains("Motorway", ignoreCase = true) -> 10
            speedLimitKmh > 0 -> 6      // 地方道路
            else -> 8                   // 默认
        }

        postFieldsMutate { s ->
            s.value = s.value.copy(
                nRoadLimitSpeed = speedLimitKmh,
                nPosSpeed = currentSpeedKmh.toDouble(),
                // 🆕 P0: 补充道路分类字段
                roadcate = roadcate,
                roadType = roadcate,
                source_last = "google_nav"
            )
        }
    }

    /**
     * 更新当前道路名称
     * @param roadName 当前道路名称
     *
     * SDK 7.0.0 中此方法不再外部调用，道路名称由 [updateFromNavInfo] 自动提取。
     */
    @Deprecated("SDK 7.0.0 道路名称由 updateFromNavInfo 自动提取")
    fun updateCurrentRoad(roadName: String) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                szPosRoadName = roadName,
                source_last = "google_nav"
            )
        }
    }
}
