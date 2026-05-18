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

    fun updateLocation(lat: Double, lon: Double, heading: Float, speed: Float) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                latitude = lat,
                longitude = lon,
                heading = heading.toDouble(),
                gps_speed = speed.toDouble(),
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
                goalPosX = destLon,
                goalPosY = destLat,
                szGoalName = destName,
                isNavigating = true,
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
     * 更新限速信息
     * @param speedLimitKmh 道路限速 (km/h)
     * @param currentSpeedKmh 当前速度 (km/h)
     */
    fun updateSpeedLimit(speedLimitKmh: Int, currentSpeedKmh: Int) {
        postFieldsMutate { s ->
            s.value = s.value.copy(
                nRoadLimitSpeed = speedLimitKmh,
                nPosSpeed = currentSpeedKmh.toDouble(),
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