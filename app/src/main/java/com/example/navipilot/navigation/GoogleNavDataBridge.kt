package com.example.navipilot.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.navipilot.CarrotManFields
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
     */
    fun updateTbtEnhanced(
        szFarDirName: String? = null,
        nTBTDistNext: Int = -1,
        nTBTTurnTypeNext: Int = -1
    ) {
        postFieldsMutate { s ->
            val cur = s.value
            s.value = cur.copy(
                szFarDirName = szFarDirName ?: cur.szFarDirName,
                nTBTDistNext = if (nTBTDistNext >= 0) nTBTDistNext else cur.nTBTDistNext,
                nTBTTurnTypeNext = if (nTBTTurnTypeNext >= 0) nTBTTurnTypeNext else cur.nTBTTurnTypeNext,
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
     */
    fun updateCurrentRoad(roadName: String) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                szPosRoadName = roadName,
                source_last = "google_nav"
            )
        }
    }
}
