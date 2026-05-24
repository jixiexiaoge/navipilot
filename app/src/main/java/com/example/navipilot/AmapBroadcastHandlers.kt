package com.example.navipilot

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.MutableState
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max

/**
 * 高德地图广播处理器扩展
 * 包含各种类型广播的具体处理逻辑
 * 🎯 整合了AmapDataProcessor、AmapDestinationManager、AmapNavigationManager、AmapTrafficHandlers的功能
 */
class AmapBroadcastHandlers(
    private val carrotManFields: MutableState<CarrotManFields>,
    private val networkManager: NetworkManager? = null,
    private val context: android.content.Context? = null,
    private val updateUI: ((String) -> Unit)? = null
) {
    private var lastRelayTriggerTime = 0L
    private val RELAY_TRIGGER_DEBOUNCE_MS = 5000L // 5秒去重

    companion object {
        private const val TAG = "AmapBroadcastHandlers"

        // 🎯 已删除多余的计算函数
        // 这些计算应该由comma3设备处理，手机app只负责数据映射

        // 🎯 已删除SDI描述函数
        // carrot_serv.py中已有_get_sdi_descr函数处理SDI描述，手机app不需要重复

        // 🎯 已删除导航路径生成函数
        // 路径数据应该由高德地图直接提供，不需要手机app生成

        /**
         * 更新CarrotMan索引 (基于Python carrot_man.py逻辑)
         * 每次导航数据更新时递增索引
         */
        fun updateCarrotIndex(carrotManFields: MutableState<CarrotManFields>): Long {
            val currentIndex = carrotManFields.value.carrotIndex + 1
            carrotManFields.value = carrotManFields.value.copy(
                carrotIndex = currentIndex
            )
            return currentIndex
        }

        // 🎯 注意：命令执行功能已移至Python端

        // 🎯 注意：DETECT命令处理已移至Python端

        /**
         * 更新远程IP地址 (基于Python update_navi方法)
         * @param remoteIP 远程设备IP地址
         */
        fun updateRemoteIP(carrotManFields: MutableState<CarrotManFields>, remoteIP: String) {
            // 已移除 -> remote 字段已清理
        }

        /**
         * 简化的调试文本生成
         * @param fields 当前CarrotMan字段
         * @return 调试文本字符串
         */
        private fun generateDebugText(fields: CarrotManFields): String {
            val parts = mutableListOf<String>()

            parts.add("${fields.nRoadLimitSpeed}")
            // 🎯 navType, navModifier 由Python端计算，Android不发送

            return parts.joinToString(",")
        }

        /**
         * 更新数据源跟踪 (基于Python source_last逻辑)
         * @param source 数据来源标识
         */
        fun updateDataSource(carrotManFields: MutableState<CarrotManFields>, source: String) {
            carrotManFields.value = carrotManFields.value.copy(
                source_last = source
            )
           // Log.d(TAG, "📊 数据源已更新: $source")
        }

        // 🎯 注意：ATC控制功能已移至Python端处理
        // Android只发送原始的nTBTTurnType数据，Python端负责所有计算

        // 🎯 注意：ATC控制功能已移至Python端

        // 🎯 注意：用户接管检测功能已移至Python端处理

        // 🎯 注意：CarrotMan命令处理功能已移至Python端
        // Android只负责发送数据，不处理来自Comma3的命令


        /**
         * 统一映射：高德 CAMERA_TYPE → Python nSdiType
         * 按照0-100顺序排列，保留原有映射关系，并补充高德官方道路设施类型定义
         * 参考: https://lbs.amap.com/api/android-navi-sdk/guide/tool/poitype
         * 
         * 映射策略：
         * 1. 按照0-100顺序排列所有映射
         * 2. 高德官方定义的类型优先使用官方映射
         * 3. 保留原有映射关系（兼容旧版本）
         * 4. 未知类型映射到100+CAMERA_TYPE，Python端显示"需更新"+编号
         */
        fun mapAmapCameraTypeToSdi(cameraType: Int): Int {
            return when (cameraType) {
                // ========== 按顺序排列：0-100 ==========
                0 -> 100          // 未知道路设施 -> 需更新0 (100+0) [高德官方定义]
                1 -> 14           // 道路拍照 -> 治安监控 (14) [原有映射]
                2 -> 6            // 闯红灯拍照 -> 闯红灯拍照 (6) [原有映射]
                3 -> 17           // 违章拍照 -> 违停拍照点 (17) [原有映射]
                4 -> 8            // 测速拍照 -> 测速拍照 (8) [高德官方定义]
                5 -> 5            // 违章拍照 -> 路口压线拍照 (5) [高德官方定义]
                6 -> 8            // 测速拍照 -> 测速拍照 (8) [原有映射]
                7 -> 7            // 非机动车道拍照 -> 流动测速摄像头 (7) [原有映射]
                8 -> 2            // 区间限速启点 -> 区间测速开始 (2) [原有映射]
                9 -> 3            // 区间限速终点 -> 区间测速结束 (3) [原有映射]
                10 -> 7           // 流动测速电子眼 -> 流动测速摄像头 (7) [原有映射]
                11 -> 26          // ECT计费拍照 -> ETC计费拍照 (26) [原有映射]
                12 -> 19          // 铁路道口 -> 铁路道口 (19) [高德官方定义]
                13 -> 48          // 左侧落石 -> 落石危险路段 (48) [高德官方定义]
                14 -> 53          // 事故易发地段 -> 事故易发地段 (53) [高德官方定义]
                15 -> 49          // 路段易滑 -> 路段易滑 (49) [高德官方定义]
                16 -> 54          // 村庄 -> 村庄 (54) [高德官方定义]
                17 -> 17          // 违停拍照点 -> 违停拍照点 (17) [原有映射]
                18 -> 20          // 学校 -> 学校区域开始 (20) [高德官方定义]
                19 -> 19          // 有人看管的铁路道口 -> 铁路道口 (19) [高德官方定义]
                20 -> 19          // 无人看管的铁路道口 -> 铁路道口 (19) [高德官方定义]
                21 -> 69          // 道路两侧变窄 -> 道路两侧变窄 (69) [高德官方定义]
                22 -> 30          // 向左急弯路 -> 急弯路段 (30) [高德官方定义]
                23 -> 30          // 向右急弯路 -> 急弯路段 (30) [高德官方定义]
                24 -> 30          // 反向弯路 -> 急弯路段 (30) [高德官方定义]
                25 -> 30          // 连续弯路 -> 急弯路段 (30) [高德官方定义]
                26 -> 51          // 左侧车辆交汇处 -> 汇入道路 (51) [高德官方定义]
                27 -> 51          // 右侧车辆交汇处 -> 汇入道路 (51) [高德官方定义]
                28 -> 14          // 监控摄像 -> 监控摄像 (14) [高德官方定义]
                29 -> 9           // 公交专用道拍照 -> 公交专用道拍照 (9) [高德官方定义]
                30 -> 30          // 急弯路段 -> 急弯路段 (30) [原有映射]
                31 -> 84          // 禁止超车 -> 禁止超车 (84) [高德官方定义]
                32 -> 32          // 陡坡路段 -> 陡坡路段 (32) [原有映射]
                33 -> 33          // 野生动物出没路段 -> 野生动物出没路段 (33) [原有映射]
                34 -> 34          // 右侧视野不良点 -> 右侧视野不良点 (34) [原有映射]
                35 -> 35          // 视野不良点 -> 视野不良点 (35) [原有映射]
                36 -> 71          // 右侧变窄 -> 右侧变窄 (71) [高德官方定义]
                37 -> 70          // 左侧变窄 -> 左侧变窄 (70) [高德官方定义]
                38 -> 72          // 窄桥 -> 窄桥 (72) [高德官方定义]
                39 -> 73          // 左右绕行 -> 左右绕行 (73) [高德官方定义]
                40 -> 74          // 左侧绕行 -> 左侧绕行 (74) [高德官方定义]
                41 -> 75          // 右侧绕行 -> 右侧绕行 (75) [高德官方定义]
                42 -> 48          // 右侧落石 -> 落石危险路段 (48) [高德官方定义]
                43 -> 77          // 左侧靠山险路 -> 左侧靠山险路 (77) [高德官方定义]
                44 -> 76          // 右侧靠山险路 -> 右侧靠山险路 (76) [高德官方定义]
                45 -> 45          // 事故多发点 -> 事故多发点 (45) [原有映射]
                46 -> 46          // 行人事故多发点 -> 行人事故多发点 (46) [原有映射]
                47 -> 78          // 上陡坡 -> 上陡坡 (78) [高德官方定义]
                48 -> 79          // 下陡坡 -> 下陡坡 (79) [高德官方定义]
                49 -> 80          // 过水路面 -> 过水路面 (80) [高德官方定义]
                50 -> 81          // 路面不平 -> 路面不平 (81) [高德官方定义]
                51 -> 51          // 汇入道路 -> 汇入道路 (51) [原有映射]
                52 -> 82          // 慢行 -> 慢行 (82) [高德官方定义]
                53 -> 53          // 事故易发地段 -> 事故易发地段 (53) [高德官方定义]
                54 -> 83          // 横风区 -> 横风区 (83) [高德官方定义]
                55 -> 55          // 立交 -> 立交 (55) [原有映射]
                56 -> 56          // 分岔点 -> 分岔点 (56) [原有映射]
                57 -> 57          // 服务区（可加气） -> 服务区（可加气） (57) [原有映射]
                58 -> 67          // 隧道 -> 隧道 (67) [高德官方定义]
                59 -> 68          // 渡口 -> 渡口 (68) [高德官方定义]
                60 -> 60          // 越线事故多发点 -> 越线事故多发点 (60) [原有映射]
                61 -> 61          // 违法通行事故多发点 -> 违法通行事故多发点 (61) [原有映射]
                62 -> 62          // 目的地在对面 -> 目的地在对面 (62) [原有映射]
                63 -> 63          // 瞌睡停车区 -> 瞌睡停车区 (63) [原有映射]
                64 -> 64          // 老旧柴油车管制 -> 老旧柴油车管制 (64) [原有映射]
                65 -> 65          // 隧道内变道拍照 -> 隧道内变道拍照 (65) [原有映射]
                66 -> 166         // 未知类型 -> 需更新66 (100+66)
                67 -> 167         // 未知类型 -> 需更新67 (100+67)
                68 -> 168         // 未知类型 -> 需更新68 (100+68)
                69 -> 169         // 未知类型 -> 需更新69 (100+69)
                70 -> 170         // 未知类型 -> 需更新70 (100+70)
                71 -> 171         // 未知类型 -> 需更新71 (100+71)
                72 -> 172         // 未知类型 -> 需更新72 (100+72)
                73 -> 173         // 未知类型 -> 需更新73 (100+73)
                74 -> 174         // 未知类型 -> 需更新74 (100+74)
                75 -> 175         // 未知类型 -> 需更新75 (100+75)
                76 -> 176         // 未知类型 -> 需更新76 (100+76)
                77 -> 177         // 未知类型 -> 需更新77 (100+77)
                78 -> 178         // 未知类型 -> 需更新78 (100+78)
                79 -> 179         // 未知类型 -> 需更新79 (100+79)
                80 -> 180         // 未知类型 -> 需更新80 (100+80)
                81 -> 181         // 未知类型 -> 需更新81 (100+81)
                82 -> 182         // 未知类型 -> 需更新82 (100+82)
                83 -> 183         // 未知类型 -> 需更新83 (100+83)
                84 -> 184         // 未知类型 -> 需更新84 (100+84)
                85 -> 185         // 未知类型 -> 需更新85 (100+85)
                86 -> 186         // 未知类型 -> 需更新86 (100+86)
                87 -> 187         // 未知类型 -> 需更新87 (100+87)
                88 -> 188         // 未知类型 -> 需更新88 (100+88)
                89 -> 189         // 未知类型 -> 需更新89 (100+89)
                90 -> 190         // 未知类型 -> 需更新90 (100+90)
                91 -> 191         // 未知类型 -> 需更新91 (100+91)
                92 -> 6           // 闯红灯拍照 -> 闯红灯拍照 (6) [高德官方定义]
                93 -> 11          // 应急车道拍照 -> 应急车道拍照 (11) [高德官方定义]
                94 -> 86          // 非机动车道拍照 -> 非机动车道拍照 (86) [高德官方定义]
                95 -> 195         // 未知类型 -> 需更新95 (100+95)
                96 -> 196         // 未知类型 -> 需更新96 (100+96)
                97 -> 197         // 未知类型 -> 需更新97 (100+97)
                98 -> 198         // 未知类型 -> 需更新98 (100+98)
                99 -> 199         // 未知类型 -> 需更新99 (100+99)
                100 -> 85         // 违章高发地 -> 违章高发地 (85) [高德官方定义]
                
                // ========== 超出范围的处理 ==========
                else -> (100 + cameraType).coerceAtMost(999)  // 其他未知（>100） -> 需更新+编号，最大999
            }
        }

        /**
         * 映射高德交通灯状态到CarrotMan协议状态 (基于逆向分析文档修正)
         * CarrotMan协议状态: 0=off, 1=red, 2=green, 3=left(左转绿灯)
         * @param amapStatus 高德交通灯状态
         * @param direction 方向信息 (用于区分左转等特殊情况)
         * @return CarrotMan协议交通状态
         */
        private fun mapTrafficLightStatus(amapStatus: Int, direction: Int = 0): Int {
            return when (amapStatus) {
                -1 -> -1
                0 -> 0
                1 -> 1
                2 -> if (direction == 1 || direction == 3) 3 else 2
                3 -> 1
                4 -> 2
                else -> 0
            }
        }

        private fun getTrafficLightDirectionDesc(direction: Int): String {
            return when (direction) {
                0 -> "直行黄灯"
                1 -> "左转"
                2 -> "右转"
                3 -> "左转掉头"
                4 -> "直行"
                5 -> "右转掉头"
                else -> "方向$direction"
            }
        }
    }

    /**
     * 检查并触发继电器指令（用于右转/右侧分岔提醒）
     * 🎯 触发条件：右转/向右进入岔路，且距离 <= 180m
     * 🎯 去重逻辑：5秒内只触发一次
     */
    private fun checkAndTriggerRelay(turnType: Int, distance: Int) {
        // 触发条件：右转(13) 或 右前方(101/66) 或 向右进入辅道(1007/101) 或 环岛相关右转(131/132/139)，且距离 <= 180m
        // 映射参考 mapAmapIconToCarrotTurn
        val isRightAction = when (turnType) {
            3, 13 -> true           // 右转
            5, 66, 101 -> true      // 右前方 / 靠右
            1007 -> true            // 向右进入辅道
            11, 131 -> true         // 进入环岛 (通常包含右偏)
            12, 132 -> true         // 驶出环岛
            22, 139 -> true         // 环岛右转
            else -> false
        }
        
        if (isRightAction && distance > 0 && distance <= 180) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRelayTriggerTime >= RELAY_TRIGGER_DEBOUNCE_MS) {
                lastRelayTriggerTime = currentTime
                Log.i(TAG, "🎯 检测到右转动作：转弯类型=$turnType, 距离=${distance}m")
            }
        }
    }

    // ===============================
    // 地图状态处理 - KEY_TYPE: 10019
    // ===============================
    fun handleMapState(intent: Intent) {
        //Log.d(TAG, "🗺️ 处理地图状态广播")
        
        val extraState = intent.getSafeIntExtra("EXTRA_STATE", -1)
        //Log.i(TAG, "地图状态: EXTRA_STATE=$extraState")
        
        // 检查是否为到达目的地状态
        if (extraState == AppConstants.AmapBroadcast.NavigationState.ARRIVE_DESTINATION) {
            //Log.i(TAG, "🎯 检测到到达目的地状态！")

            carrotManFields.value = carrotManFields.value.copy(
                // 导航状态
                isNavigating = false,

                // 转弯信息 - 设置为到达目的地
                nTBTTurnType = 201,           // 到达目的地转弯类型
                nTBTDist = 0,                 // 距离设为0
                szTBTMainText = "到达目的地",   // 主要文本
                szNearDirName = "目的地",      // 附近方向名称
                szFarDirName = "",            // 远方向名称清空

                // 🎯 注意：xTurnInfo 由Python端根据nTBTTurnType=201计算
                // Android只设置原始数据

                // 🎯 注意：navType, navModifier 由Python端根据nTBTTurnType=201计算

                // 距离和时间信息
                nGoPosDist = 0,               // 到目的地距离设为0
                nGoPosTime = 0,               // 到目的地时间设为0
                nTBTDistNext = 0,             // 下一段距离清空

                // 系统状态
                active_carrot = 0,            // CarrotMan激活状态设为0
                source_last = "amap",
                lastUpdateTime = System.currentTimeMillis(),
            )

            //Log.i(TAG, "✅ 已更新CarrotMan字段：导航状态=false，转弯类型=201(到达目的地)")
        }
        
        // 🚀 修复：移除立即发送，由NetworkManager统一200ms间隔发送避免闪烁
    }

    // ===============================
    // 引导信息处理 - KEY_TYPE: 10001
    // ===============================
    fun handleGuideInfo(intent: Intent) {
        //Log.d(TAG, "🧭 处理引导信息广播 (KEY_TYPE: 10001)")

        try {
            // 基础道路信息
            val currentRoad = intent.getStringExtra("CUR_ROAD_NAME") ?: ""
            val nextRoad = intent.getStringExtra("NEXT_ROAD_NAME") ?: ""
            val nextNextRoad = intent.getStringExtra("NEXT_NEXT_ROAD_NAME") ?: ""
            val speedLimit = intent.getSafeIntExtra("LIMITED_SPEED", 0)
            val currentSpeed = intent.getSafeIntExtra("CUR_SPEED", 0)
            val carDirection = intent.getSafeIntExtra("CAR_DIRECTION", 0)
            
            // 🆕 添加道路限速调试日志（注意：在修正逻辑之前，这里显示原始值）
            // 修正后的值会在下面获取 roadType 后显示
            if (speedLimit > 0) {
                //Log.d(TAG, "🚦 从高德广播接收道路限速: ${speedLimit}km/h")
            } else {
                Log.v(TAG, "⚠️ 高德广播未包含道路限速信息 (LIMITED_SPEED=0)")
            }

            // 距离和时间信息
            val remainDistance = intent.getSafeIntExtra("ROUTE_REMAIN_DIS", 0)
            val remainTime = intent.getSafeIntExtra("ROUTE_REMAIN_TIME", 0)
            val remainTimeString = intent.getStringExtra("ROUTE_REMAIN_TIME_STRING") ?: ""
            val routeAllDis = intent.getSafeIntExtra("ROUTE_ALL_DIS", 0)
            val routeAllTime = intent.getSafeIntExtra("ROUTE_ALL_TIME", 0)
            val routeRemainDisAuto = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO") ?: ""
            val routeRemainTimeAuto = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO") ?: ""
            val nextSegRemainDisAuto = intent.getStringExtra("NEXT_SEG_REMAIN_DIS_AUTO") ?: ""
            val nextSapaDistAuto = intent.getStringExtra("NEXT_SAPA_DIST_AUTO") ?: ""
            val sapaDistAuto = intent.getStringExtra("SAPA_DIST_AUTO") ?: ""
            val nextRoadProgressPercent = intent.getSafeIntExtra("NEXT_ROAD_PROGRESS_PERCENT", -1)
            
            val segRemainDis = intent.getSafeIntExtra("SEG_REMAIN_DIS", 0)
            val segRemainTime = intent.getSafeIntExtra("SEG_REMAIN_TIME", 0)
            val nextSegRemainDis = intent.getSafeIntExtra("NEXT_SEG_REMAIN_DIS", 0)
            val nextSegRemainTime = intent.getSafeIntExtra("NEXT_SEG_REMAIN_TIME", 0)
            val curSegNum = intent.getSafeIntExtra("CUR_SEG_NUM", 0)
            val curPointNum = intent.getSafeIntExtra("CUR_POINT_NUM", 0)

            // 转向图标和环岛信息
            val icon = intent.getSafeIntExtra("ICON", -1)
            val newIcon = intent.getSafeIntExtra("NEW_ICON", -1)
            val nextNextTurnIcon = intent.getSafeIntExtra("NEXT_NEXT_TURN_ICON", -1)

            // 位置信息
            val carLatitude = intent.getSafeDoubleExtra("CAR_LATITUDE", 0.0)
            val carLongitude = intent.getSafeDoubleExtra("CAR_LONGITUDE", 0.0)

            // 🚀 关键修复：使用effectiveLatitude策略，确保始终有有效的GPS数据
            // 当高德GPS为0时，使用手机GPS作为后备方案
            val effectiveLatitude = if (carLatitude != 0.0) carLatitude else carrotManFields.value.vpPosPointLat
            val effectiveLongitude = if (carLongitude != 0.0) carLongitude else carrotManFields.value.vpPosPointLon
            
            // 记录GPS坐标映射情况
            if (carLatitude == 0.0 && carLongitude == 0.0) {
                //Log.d(TAG, "📍 GPS坐标为0，使用手机GPS: lat=$effectiveLatitude, lon=$effectiveLongitude")
            } else {
                Log.d(TAG, "📍 使用导航GPS坐标: lat=$effectiveLatitude, lon=$effectiveLongitude")
            }

            // 🆕 高德坐标 GCJ-02 → WGS-84 转换，用于发送给 comma3
            // 高德广播的 CAR_LATITUDE/CAR_LONGITUDE 是 GCJ-02 坐标系（道路吸附），
            // comma3 使用 WGS-84，需要转换后写入 vpPosPointLat/Lon
            val effectiveWgsLat: Double
            val effectiveWgsLon: Double
            if (carLatitude != 0.0 && carLongitude != 0.0) {
                val (wgsLat, wgsLon) = com.example.navipilot.navigation.CoordinateConverter.gcj02ToWgs84(
                    carLatitude, carLongitude
                )
                effectiveWgsLat = wgsLat
                effectiveWgsLon = wgsLon
            } else {
                // 高德坐标不可用时，保持当前值（由 LocationSensorManager 更新的 WGS-84）
                effectiveWgsLat = carrotManFields.value.vpPosPointLat
                effectiveWgsLon = carrotManFields.value.vpPosPointLon
            }

            // 服务区和电子眼信息
            val sapaDist = intent.getSafeIntExtra("SAPA_DIST", 0)
            val sapaType = intent.getSafeIntExtra("SAPA_TYPE", -1)
            val sapaNum = intent.getSafeIntExtra("SAPA_NUM", 0)
            val sapaName = intent.getStringExtra("SAPA_NAME") ?: ""
            
            // 🚀 关键修复：KEY_TYPE: 10001 中的摄像头字段可能是可选的，需要检查字段是否存在
            val hasCameraDist = intent.hasExtra("CAMERA_DIST")
            val hasCameraType = intent.hasExtra("CAMERA_TYPE")
            val hasCameraSpeed = intent.hasExtra("CAMERA_SPEED")
            val hasCameraIndex = intent.hasExtra("CAMERA_INDEX")
            
            // 只有当字段存在时才获取值，否则使用默认值（-1表示字段不存在）
            val cameraDist = if (hasCameraDist) intent.getSafeIntExtra("CAMERA_DIST", -1) else -1
            val cameraType = if (hasCameraType) intent.getSafeIntExtra("CAMERA_TYPE", -1) else -1
            val cameraSpeed = if (hasCameraSpeed) intent.getSafeIntExtra("CAMERA_SPEED", 0) else 0
            val cameraIndex = if (hasCameraIndex) intent.getSafeIntExtra("CAMERA_INDEX", -1) else -1
            
            // 🐛 修复：使用更安全的方式获取 cameraID，直接从 Bundle 获取并检查类型，避免系统 getLongExtra 内部抛出 ClassCastException 并在日志中打印警告
            val cameraID = intent.getSafeLongExtra("cameraID", -1L)
            
            val cameraPenalty = intent.getBooleanExtra("cameraPenalty", false)
            val newCamera = intent.getBooleanExtra("newCamera", false)
            
            // 🚀 关键修复：KEY_TYPE: 10001 中也包含区间测速信息（可选字段）
            // 🔑 关键发现：这些字段只在有区间测速时才存在，需要检查字段是否存在
            val hasStartDistance = intent.hasExtra("START_DISTANCE")
            val hasEndDistance = intent.hasExtra("END_DISTANCE")
            val hasIntervalDistance = intent.hasExtra("INTERVAL_DISTANCE")
            val hasAverageSpeed = intent.hasExtra("AVERAGE_SPEED")
            
            // 只有当字段存在时才获取值，否则使用默认值0（表示字段不存在）
            val startDistance = if (hasStartDistance) intent.getSafeDoubleExtra("START_DISTANCE", 0.0) else 0.0
            val endDistance = if (hasEndDistance) intent.getSafeDoubleExtra("END_DISTANCE", 0.0) else 0.0
            val intervalDistance = if (hasIntervalDistance) intent.getSafeDoubleExtra("INTERVAL_DISTANCE", 0.0) else 0.0
            val averageSpeed = if (hasAverageSpeed) intent.getSafeIntExtra("AVERAGE_SPEED", 0) else 0
            
            // 🚀 关键修复：KEY_TYPE: 10001 中的区间测速处理
            // CAMERA_TYPE=8 在开始和区间中都是 8，只有结束时才是 9
            // ⚠️ 重要：只有当 CAMERA_TYPE=8/9 且有区间测速字段时才处理
            // 🔑 关键：需要同时检查 CAMERA_TYPE 字段是否存在
            val isSectionSpeedControl = hasCameraType && cameraType in listOf(8, 9) && (hasStartDistance || hasEndDistance || hasIntervalDistance)
            if (isSectionSpeedControl) {
                Log.i(TAG, "🚦 [KEY_TYPE:10001] 检测到区间测速: CAMERA_TYPE=$cameraType (存在=$hasCameraType), CAMERA_DIST=$cameraDist (存在=$hasCameraDist)")
                Log.i(TAG, "🚦   字段存在性: START_DISTANCE=$hasStartDistance, END_DISTANCE=$hasEndDistance, INTERVAL_DISTANCE=$hasIntervalDistance, AVERAGE_SPEED=$hasAverageSpeed")
                if (hasStartDistance || hasEndDistance || hasIntervalDistance) {
                    Log.i(TAG, "🚦   距离信息: START=$startDistance, END=$endDistance, INTERVAL=$intervalDistance, AVG_SPEED=$averageSpeed")
                }
            } else if (cameraType == -1 && cameraDist == -1) {
                // 没有摄像头信息，清除区间测速状态（如果是普通导航更新）
                // ⚠️ 注意：这里不清除，保留之前的区间测速状态，除非明确收到结束信号
            }

            // 导航类型和其他信息
            val naviType = intent.getSafeIntExtra("TYPE", 0)
            val trafficLightNum = intent.getSafeIntExtra("TRAFFIC_LIGHT_NUM", 0)
            val routeRemainTrafficLightNum = intent.getSafeIntExtra("routeRemainTrafficLightNum", 0)
            val nextRoadNOAOrNot = intent.getBooleanExtra("nextRoadNOAOrNot", false)

            // 获取道路类型
            val roadType = intent.getSafeIntExtra("ROAD_TYPE", 8) // 默认为8（未知）
            
            // 🚀 关键修复：高速公路/快速道路限速修正
            // 🔑 当 ROAD_TYPE 是 0（高速公路）或 6（快速道），且 LIMITED_SPEED 为 40 时，强制修正为 55
            val correctedSpeedLimit = if ((roadType == 0 || roadType == 6) && speedLimit == 40) {
                Log.i(TAG, "🚦 限速修正: ROAD_TYPE=$roadType (${getRoadTypeDescription(roadType)}), LIMITED_SPEED=40 -> 强制修正为55km/h")
                55
            } else {
                speedLimit
            }
            
            // 如果限速被修正，记录最终使用的限速值
            if (correctedSpeedLimit != speedLimit) {
                Log.i(TAG, "🚦 最终限速: ${correctedSpeedLimit}km/h (原始值: ${speedLimit}km/h)")
            }
            
            // 🎯 将高德地图的 ROAD_TYPE 映射到 CarrotMan 的 roadcate（简化规则）
            val mappedRoadcate = mapRoadTypeToRoadcate(roadType)
            Log.d(TAG, "🛣️ 道路类型映射: ROAD_TYPE=$roadType (${getRoadTypeDescription(roadType)}) -> roadcate=$mappedRoadcate (${getRoadcateDescription(mappedRoadcate)})")

            // 目的地信息
            val endPOIName = intent.getStringExtra("endPOIName") ?: ""
            val endPOIAddr = intent.getStringExtra("endPOIAddr") ?: ""
            val endPOILatitude = intent.getSafeDoubleExtra("endPOILatitude", 0.0)
            val endPOILongitude = intent.getSafeDoubleExtra("endPOILongitude", 0.0)

            // 🎯 转弯类型映射和导航类型计算
            val primaryIcon = if (newIcon != -1) newIcon else icon
            val carrotTurnType = if (primaryIcon != -1) {
                val mappedType = mapAmapIconToCarrotTurn(primaryIcon)
                //Log.d(TAG, "🔄 转弯映射: 高德图标=$primaryIcon -> CarrotMan类型=$mappedType")
                mappedType
            } else {
                carrotManFields.value.nTBTTurnType
            }

            val carrotNextTurnType = if (nextNextTurnIcon != -1) {
                val mappedNextType = mapAmapIconToCarrotTurn(nextNextTurnIcon)
                //Log.d(TAG, "🔄 下一转弯映射: 高德图标=$nextNextTurnIcon -> CarrotMan类型=$mappedNextType")
                mappedNextType
            } else {
                carrotManFields.value.nTBTTurnTypeNext
            }

            // 🎯 注意：navType, navModifier, xTurnInfo 由Python端计算
            // Android只需要发送原始的nTBTTurnType数据

            // 简化的时间更新
            val currentTime = System.currentTimeMillis()

            // 🚀 触发蓝牙继电器逻辑
            checkAndTriggerRelay(carrotTurnType, segRemainDis)

             Log.i(TAG, "🧭 引导信息: 道路=$currentRoad->$nextRoad, 转弯类型=$carrotTurnType, 距离=${segRemainDis}m")
             
             // 🚀 区间测速日志（如果检测到区间测速）
             if (isSectionSpeedControl) {
                 val thresholdDistance = 100
                 // 🔑 使用与nSdiBlockType相同的逻辑计算当前状态（用于日志）
                 val currentNSdiBlockType = when {
                     cameraType == 9 -> 3
                     cameraType == 8 && hasStartDistance && startDistance > 0 -> {
                         // 如果有 END_DISTANCE 且 <= 阈值，视为接近终点
                         if (hasEndDistance && endDistance > 0 && endDistance <= thresholdDistance) {
                             3  // 接近终点，视为结束
                         } else {
                             2  // 区间测速中
                         }
                     }
                     cameraType == 8 && (hasStartDistance && startDistance <= 50 || !hasStartDistance) -> 1
                     cameraType == 8 -> 1
                     else -> -1
                 }
                 // 🔑 计算实际的 nSdiType（与上面赋值逻辑一致）
                 val actualNSdiType = when {
                     cameraType == 8 && hasCameraType -> 2
                     cameraType == 9 && hasCameraType -> 3
                     hasCameraDist && cameraDist > 20 && cameraType >= 0 && hasCameraType -> mapAmapCameraTypeToSdi(cameraType)
                     hasCameraDist && cameraDist <= 20 && cameraDist > 0 && cameraType >= 0 && hasCameraType -> -1
                     else -> carrotManFields.value.nSdiType
                 }
                 
                 Log.i(TAG, "🚦 [KEY_TYPE:10001] 区间测速映射:")
                 Log.i(TAG, "🚦   字段存在性: START=$hasStartDistance, END=$hasEndDistance, INTERVAL=$hasIntervalDistance, AVG_SPEED=$hasAverageSpeed")
                 if (hasStartDistance || hasEndDistance || hasIntervalDistance) {
                     Log.i(TAG, "🚦   距离信息: START=$startDistance (已行驶), END=$endDistance (剩余), INTERVAL=$intervalDistance (总距离), AVG_SPEED=$averageSpeed")
                 }
                 Log.i(TAG, "🚦   CAMERA_TYPE=$cameraType (存在=$hasCameraType), CAMERA_DIST=$cameraDist (存在=$hasCameraDist) → nSdiType=$actualNSdiType (${when(actualNSdiType) { 2 -> "区间开始" 3 -> "区间结束" 4 -> "未知类型4" else -> "其他/映射值=$actualNSdiType" }})")
                 Log.i(TAG, "🚦   nSdiBlockType=$currentNSdiBlockType (${when(currentNSdiBlockType) { 1 -> "开始" 2 -> "进行中" 3 -> "结束" else -> "无效" }})")
                 Log.i(TAG, "🚦   Python将处理: ${if (currentNSdiBlockType in listOf(2, 3)) "xSpdType=4 (区间测速), xSpdDist=nSdiBlockDist" else "xSpdType=nSdiType ($actualNSdiType), xSpdDist=nSdiDist"}")
             } else if (cameraType == -1 && cameraDist == -1) {
                 // 没有摄像头信息，这是正常的导航更新（不影响区间测速状态）
                 // Log.v(TAG, "🚦 [KEY_TYPE:10001] 无摄像头信息（正常导航更新）")
             }

            // 🚀 新增：NOA 增强字段提取
            val exitDirectionInfo = intent.getStringExtra("EXIT_DIRECTION_INFO") ?: ""
            val exitNameInfo = intent.getStringExtra("EXIT_NAME_INFO") ?: ""
            
            val segAssistantAction = intent.getSafeIntExtra("SEG_ASSISTANT_ACTION", -1)
            
            // 下下个动作图标（如果有）
            val nextNextAddIcon = intent.getSafeIntExtra("NEXT_NEXT_ADD_ICON", -1)
            val mappedNextNextAddIcon = if (nextNextAddIcon != -1) {
                mapAmapIconToCarrotTurn(nextNextAddIcon).toString()
            } else {
                ""
            }

            // 途径点信息
            val viaPOIdistance = intent.getSafeIntExtra("viaPOIdistance", -1)
            val viaPOItime = intent.getSafeIntExtra("viaPOItime", -1)

            // 更新CarrotMan字段
            carrotManFields.value = carrotManFields.value.copy(
                // 基础导航信息 - 确保关键字段总是被更新
                szPosRoadName = currentRoad.takeIf { it.isNotEmpty() } ?: carrotManFields.value.szPosRoadName,
                szNearDirName = nextRoad,  // 总是更新，即使为空
                szFarDirName = nextNextRoad,  // 总是更新，即使为空
                nRoadLimitSpeed = correctedSpeedLimit.takeIf { it > 0 } ?: carrotManFields.value.nRoadLimitSpeed.also {
                    // 🆕 如果高德广播没有道路限速，记录当前值（用于调试）
                    if (speedLimit == 0 && it > 0) {
                        //Log.v(TAG, "⚠️ 高德广播LIMITED_SPEED=0，保持当前道路限速: ${it}km/h")
                    }
                },
                nGoPosDist = remainDistance.takeIf { it > 0 } ?: carrotManFields.value.nGoPosDist,
                nGoPosTime = remainTime.takeIf { it > 0 } ?: carrotManFields.value.nGoPosTime,
                nPosSpeed = currentSpeed.toDouble(),
                nPosAngle = carDirection.toDouble(),

                // 转向和导航段信息
                nTBTDist = segRemainDis,
                nTBTDistNext = nextSegRemainDis,
                nTBTTurnType = carrotTurnType,
                nTBTTurnTypeNext = carrotNextTurnType,
                
                // 高德地图原始ICON信息
                amapIcon = primaryIcon,
                amapIconNext = nextNextTurnIcon,

                // TBT转弯指令文本
                szTBTMainText = generateTurnInstruction(carrotTurnType, nextRoad, segRemainDis),
                szTBTMainTextNext = generateTurnInstruction(carrotNextTurnType, nextNextRoad, nextSegRemainDis),

                // 🎯 注意：xTurnInfo, navType, navModifier 由Python端计算
                // Android只发送原始数据：nTBTTurnType, nTBTDist等

                // 🎯 注意：atcType 由Python端根据nTBTTurnType计算
                // Android只发送原始数据

                // 🚀 关键修复：使用effectiveLatitude/effectiveLongitude确保始终有GPS数据
                // 位置信息 - 高德导航坐标专用于Navi字段，使用有效坐标

                // 🆕 高德道路吸附坐标 (GCJ-02→WGS-84) 写入 vpPosPointLat/Lon
                // 高德的道路吸附坐标比原始GPS更精确（已投射到道路上），
                // 转换为WGS-84后发送给comma3，提升定位精度
                vpPosPointLat = effectiveWgsLat,
                vpPosPointLon = effectiveWgsLon,

                // 目的地信息 - 确保目的地信息总是被更新
                goalPosX = endPOILongitude.takeIf { it != 0.0 } ?: carrotManFields.value.goalPosX,
                goalPosY = endPOILatitude.takeIf { it != 0.0 } ?: carrotManFields.value.goalPosY,
                szGoalName = endPOIName,  // 总是更新目的地名称

                // 道路和导航状态
                isNavigating = true,
                active_carrot = if (remainDistance > 0 || speedLimit > 0) 1 else carrotManFields.value.active_carrot,
                
                // 🎯 道路类别映射 - 关键修复
                roadcate = mappedRoadcate,
                roadType = roadType,

                // 🚀 关键修复：KEY_TYPE=10001 处理区间测速开始和结束
                // 🔑 规则：
                // - CAMERA_TYPE=8 → nSdiType=2 (区间测速开始)
                // - CAMERA_TYPE=9 → nSdiType=3 (区间测速结束)
                // - CAMERA_DIST 正常映射
                // 🔑 注意：区间中进行中由 KEY_TYPE:12110 处理（nSdiType=4）
                nSdiType = when {
                    // 情况1：CAMERA_TYPE=8 → nSdiType=2（区间测速开始）
                    cameraType == 8 && hasCameraType -> {
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=8 → nSdiType=2 (区间测速开始)")
                        2  // CAMERA_TYPE=8 → nSdiType=2 (区间测速开始)
                    }
                    // 情况2：CAMERA_TYPE=9 → nSdiType=3（区间测速结束）
                    cameraType == 9 && hasCameraType -> {
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=9 → nSdiType=3 (区间测速结束)")
                        3  // CAMERA_TYPE=9 → nSdiType=3 (区间测速结束)
                    }
                    // 情况3：其他有效的 CAMERA_TYPE，使用映射函数
                    // 🔑 重要：只有当 CAMERA_DIST 存在且 > 20 时才映射其他类型
                    hasCameraDist && cameraDist > 20 && cameraType >= 0 && hasCameraType -> {
                        val mappedType = mapAmapCameraTypeToSdi(cameraType)
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=$cameraType, CAMERA_DIST=$cameraDist → nSdiType=$mappedType (使用映射函数)")
                        mappedType
                    }
                    // 情况4：距离太近，清除普通测速（只有当 CAMERA_DIST 存在时才判断）
                    hasCameraDist && cameraDist <= 20 && cameraDist > 0 && cameraType >= 0 && hasCameraType -> {
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=$cameraType, CAMERA_DIST=$cameraDist <= 20 → nSdiType=-1 (距离太近，清除)")
                        -1  // 距离太近，清除普通测速
                    }
                    // 情况5：无摄像头信息或字段不存在，保留之前的状态
                    else -> {
                        Log.v(TAG, "🚦 [KEY_TYPE:10001] 无摄像头信息或字段不存在 → 保留之前状态")
                        carrotManFields.value.nSdiType  // 保留之前的状态
                    }
                },
                nSdiSpeedLimit = if (isSectionSpeedControl && cameraSpeed > 0 && hasCameraSpeed) {
                    cameraSpeed  // 区间测速限速
                } else if (hasCameraDist && cameraDist > 20 && cameraSpeed > 0 && hasCameraSpeed) {
                    cameraSpeed  // 普通测速限速
                } else if (hasCameraDist && cameraDist <= 20 && cameraDist > 0 && cameraType >= 0 && hasCameraType) {
                    0  // 距离太近，清除限速
                } else {
                    carrotManFields.value.nSdiSpeedLimit  // 保留之前的状态
                },
                nSdiDist = if (isSectionSpeedControl && hasEndDistance && endDistance > 0) {
                    endDistance.toInt()  // 使用 END_DISTANCE（到终点剩余距离）
                } else if (hasCameraDist && cameraDist > 20) {
                    cameraDist  // 普通测速距离
                } else if (hasCameraDist && cameraDist <= 20 && cameraDist > 0 && cameraType >= 0 && hasCameraType) {
                    0  // 距离太近，清除距离
                } else {
                    carrotManFields.value.nSdiDist  // 保留之前的状态
                },
                nAmapCameraType = if (cameraType >= 0) cameraType else carrotManFields.value.nAmapCameraType, // 保存高德原始CAMERA_TYPE用于调试
                // 🚀 区间测速相关字段（如果 KEY_TYPE: 10001 中包含 these fields）
                // ⚠️ 重要：只有当字段存在时才更新，否则保留之前的状态
                nSdiSection = if (isSectionSpeedControl && hasIntervalDistance && intervalDistance > 0) {
                    intervalDistance.toInt()  // 使用 INTERVAL_DISTANCE 作为唯一标识
                } else {
                    carrotManFields.value.nSdiSection  // 保留之前的状态
                },
                nSdiBlockType = when {
                    // 情况1：区间结束（CAMERA_TYPE=9）
                    cameraType == 9 && hasCameraType -> {
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=9 → nSdiBlockType=3 (区间结束)")
                        3  // 区间结束
                    }
                    // 情况2：区间开始（CAMERA_TYPE=8）
                    cameraType == 8 && hasCameraType -> {
                        Log.i(TAG, "🚦 [KEY_TYPE:10001] CAMERA_TYPE=8 → nSdiBlockType=1 (区间开始)")
                        1  // 区间开始
                    }
                    // 情况3：其他情况保持之前的状态
                    else -> {
                        carrotManFields.value.nSdiBlockType  // 保留之前的状态
                    }
                },
                nSdiBlockSpeed = if (isSectionSpeedControl && correctedSpeedLimit > 0) {
                    correctedSpeedLimit  // 来自 LIMITED_SPEED（已修正）
                } else {
                    carrotManFields.value.nSdiBlockSpeed  // 保留之前的状态
                },
                nSdiBlockDist = if (isSectionSpeedControl && hasIntervalDistance && intervalDistance > 0) {
                    intervalDistance.toInt()  // 映射 INTERVAL_DISTANCE（区间总长度）
                } else {
                    carrotManFields.value.nSdiBlockDist  // 保留之前的状态
                },

                // 🚀 NOA 增强字段更新
                curSegNum = curSegNum,
                curPointNum = curPointNum,
                exitNameInfo = exitNameInfo,
                segAssistantAction = segAssistantAction,
                sapaName = sapaName,
                sapaDist = sapaDist,
                sapaType = sapaType,
                sapaNum = sapaNum,

                // 时间戳更新
                lastUpdateTime = currentTime
            )

            // 简化的更新逻辑
            Companion.updateCarrotIndex(carrotManFields)
            // 🎯 注意：ATC控制由Python端处理，Android只发送原始数据
            Companion.updateDataSource(carrotManFields, "amap_navi")

            //🔍 验证Navi GPS字段（由LocationSensorManager持续更新主要字段）
            //val updatedFields = carrotManFields.value

            // 🚀 修复：移除立即发送，由NetworkManager统一200ms间隔发送避免闪烁

            //Log.i(TAG, "✅ 引导信息已更新到CarrotMan字段")

        } catch (e: Exception) {
            Log.e(TAG, "处理引导信息失败: ${e.message}", e)
        }
    }

    /**
     * 将高德地图的ICON映射到CarrotMan使用的nTBTTurnType代码
     * 🎯 基于高德官方图标文档和Python代码逆向分析修正
     */
    private fun mapAmapIconToCarrotTurn(amapIcon: Int): Int {
        return when (amapIcon) {
            // 0-9（按编号顺序）
            0 -> 51               // 无转弯/通知指令
            1 -> 51               // 直行（与9统一为直行，简化）
            2 -> 12               // 左转
            3 -> 13               // 右转
            4 -> 102              // 左前方 -> 靠左/轻微左
            5 -> 101              // 右前方 -> 靠右/轻微右
            6 -> 17               // 左后方
            7 -> 19               // 右后方
            8 -> 14               // 掉头
            9 -> 51               // 直行（官方：直行图标）

            // 10-19（按编号顺序）
            10 -> 51              // 到达途经点 → 直行/通知 (Python turn_type_mapping 已有51)
            11 -> 131             // 进入环岛（右侧通行，逆时针）- 轻微右进入
            12 -> 132             // 驶出环岛（右侧通行）- 轻微右驶出
            13 -> 55              // 到达服务区 -> 通知（设施）
            14 -> 153             // 到达收费站 → TG (Python turn_type_mapping 已有153)
            15 -> 201             // 到达目的地
            16 -> 51              // 到达/进入隧道 → 直行/通知 (Python turn_type_mapping 已有51)
            17 -> 140             // 左侧通行环岛：进入（轻微左）
            18 -> 141             // 左侧通行环岛：驶出（轻微右的镜像）
            19 -> 14              // 右转掉头（左侧通行地区的掉头）-> 统一用掉头

            // 20-24（按编号顺序）
            20 -> 51              // 顺行 -> 直行
            21 -> 133             // 标准小环岛，绕环岛左转（右侧通行地区的逆时针）
            22 -> 139             // 标准小环岛，绕环岛右转（右侧通行）
            23 -> 142             // 标准小环岛，绕环岛直行（右侧通行）
            24 -> 134             // 标准小环岛，绕环岛调头（右侧通行）

            // 25-28（左侧通行地区的小环岛）
            25 -> 133             // 左侧通行小环岛左转（镜像策略，采用同一类别）
            26 -> 139             // 左侧通行小环岛右转
            27 -> 142             // 左侧通行小环岛直行
            28 -> 134             // 左侧通行小环岛调头

            55 -> 55              // 行驶到出口 -> 通知 //手动增加
            65 -> 102              // 高架 靠左行驶测试 改回来测试
            66 -> 101              // 高架 靠右下匝道

            // 其他扩展与兼容
            //65 -> 1006            // 左辅道
            101 -> 101            // 向右进入辅道 → off ramp slight right (Python turn_type_mapping 已有101)

            else -> amapIcon      // 其余保持原值，用于调试
        }
    }

    // ===============================
    // 定位信息处理 - KEY_TYPE: 10065
    // ===============================
    fun handleLocationInfo(intent: Intent) {
       // Log.d(TAG, "📍 处理定位信息广播")
        
        try {
            val latitude = intent.getDoubleExtra("LATITUDE", 0.0)
            val longitude = intent.getDoubleExtra("LONGITUDE", 0.0)
            val speed = intent.getFloatExtra("SPEED", 0.0f).toDouble()
            val bearing = intent.getFloatExtra("BEARING", 0.0f).toDouble()
            
            if (latitude != 0.0 && longitude != 0.0) {
               // Log.d(TAG, "📍 高德定位广播: lat=$latitude, lon=$longitude, speed=${speed}km/h, bearing=${bearing}°")
                
                // 简化的时间更新
                val currentTime = System.currentTimeMillis()

                // 🆕 高德定位坐标 GCJ-02 → WGS-84 转换
                val (wgsLat, wgsLon) = com.example.navipilot.navigation.CoordinateConverter.gcj02ToWgs84(
                    latitude, longitude
                )
                
                // 🚀 关键修复：更新Navi GPS + 转换后的WGS-84坐标写入主字段
                carrotManFields.value = carrotManFields.value.copy(
                    // 🆕 高德道路吸附坐标转WGS-84后写入主字段
                    vpPosPointLat = wgsLat,
                    vpPosPointLon = wgsLon,
                    // 🆕 GPS主字段（WGS-84，已由GCJ-02转换）
                    latitude = wgsLat,
                    longitude = wgsLon,
                    heading = bearing,
                    // 🆕 GPS速度 m/s（原始km/h除以3.6）
                    gps_speed = speed / 3.6,
                    // 协议标准位置字段同步（方向和速度）
                    nPosSpeed = speed,
                    nPosAngle = bearing,
                    lastUpdateTime = currentTime
                )
                
                //Log.d(TAG, "✅ 定位信息（Navi字段）已更新，主要GPS字段由LocationSensorManager持续更新")
                
                // 🚀 修复：移除立即发送，由NetworkManager统一200ms间隔发送避免闪烁
            } else {
                Log.w(TAG, "⚠️ 定位信息无效: lat=$latitude, lon=$longitude")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理定位信息失败: ${e.message}", e)
        }
    }

    // ===============================
    // 转向信息处理 - KEY_TYPE: 10006
    // ===============================
    fun handleTurnInfo(intent: Intent) {
        //Log.d(TAG, "🔄 处理转向信息广播")
        
        try {
            val turnDistance = intent.getIntExtra("TURN_DISTANCE", 0)
            val turnType = intent.getIntExtra("TURN_TYPE", -1)
            val turnInstruction = intent.getStringExtra("TURN_INSTRUCTION") ?: ""
            val nextTurnDistance = intent.getIntExtra("NEXT_TURN_DISTANCE", 0)
            val nextTurnType = intent.getIntExtra("NEXT_TURN_TYPE", -1)
            
            //Log.i(TAG, "转向信息: 距离=${turnDistance}m, 类型=$turnType, 指令=$turnInstruction")
            //Log.i(TAG, "下一转向: 距离=${nextTurnDistance}m, 类型=$nextTurnType")
            
            carrotManFields.value = carrotManFields.value.copy(
                nTBTDist = turnDistance,
                nTBTTurnType = turnType,
                szTBTMainText = turnInstruction,
                nTBTDistNext = nextTurnDistance,
                nTBTTurnTypeNext = nextTurnType,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            //Log.i(TAG, "✅ 转向信息已更新到CarrotMan字段")
            
            // 🚀 触发蓝牙继电器逻辑
            checkAndTriggerRelay(turnType, turnDistance)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理转向信息失败: ${e.message}", e)
        }
    }

    // ===============================
    // 导航状态处理 - KEY_TYPE: 10042
    // ===============================
    fun handleNavigationStatus(intent: Intent) {
        Log.d(TAG, "🚗 处理导航状态广播")
        
        try {
            val naviStatus = intent.getIntExtra("NAVI_STATUS", -1)
            val isNavigating = naviStatus == 1 // 假设1表示导航中
            
            //Log.i(TAG, "导航状态: status=$naviStatus, 导航中=$isNavigating")
            
            carrotManFields.value = carrotManFields.value.copy(
                isNavigating = isNavigating,
                active_carrot = if (isNavigating) 1 else 0,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            //Log.i(TAG, "✅ 导航状态已更新到CarrotMan字段")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理导航状态失败: ${e.message}", e)
        }
    }

    /**
     * 处理路线信息广播 (KEY_TYPE: 10003)
     */
    fun handleRouteInfo(intent: Intent) {
        //Log.d(TAG, "🛣️ 处理路线信息广播")
        
        try {
            val routeDistance = intent.getIntExtra("ROUTE_DISTANCE", 0)
            val routeTime = intent.getIntExtra("ROUTE_TIME", 0)
            val routeType = intent.getIntExtra("ROUTE_TYPE", -1)
            
           // Log.d(TAG, "🛣️ 路线信息: 距离=${routeDistance}m, 时间=${routeTime}s, 类型=$routeType")
            
            carrotManFields.value = carrotManFields.value.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            //Log.e(TAG, "❌ 处理路线信息失败: ${e.message}", e)
        }
    }

    /**
     * 处理限速信息广播 (KEY_TYPE: 12110)
     * 包含区间测速逻辑判断
     */
    fun handleSpeedLimit(intent: Intent) {
        Log.i(TAG, "🚦 开始处理限速信息广播 (KEY_TYPE: 12110)")  // 🚀 增强日志级别
        
        try {
            val speedLimit = intent.getIntExtra("LIMITED_SPEED", 0)
            val roadName = intent.getStringExtra("ROAD_NAME") ?: ""
            val speedLimitType = intent.getIntExtra("SPEED_LIMIT_TYPE", -1)
            
            // 🚀 关键修复：使用类型安全的通用读取函数（参考之前代码版本）
            // 🔑 支持多种数据类型：Int, Long, Float, Double, String
            @Suppress("DEPRECATION")
            fun readNumberAsInt(key: String): Int {
                val extras = intent.extras
                if (extras == null || !extras.containsKey(key)) return 0
                val raw = extras.get(key)
                return when (raw) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is Float -> raw.toInt()
                    is Double -> raw.toInt()
                    is String -> raw.toDoubleOrNull()?.toInt() ?: 0
                    else -> 0
                }
            }
            
            @Suppress("DEPRECATION")
            fun readNumberAsDouble(key: String): Double {
                val extras = intent.extras
                if (extras == null || !extras.containsKey(key)) return 0.0
                val raw = extras.get(key)
                return when (raw) {
                    is Int -> raw.toDouble()
                    is Long -> raw.toDouble()
                    is Float -> raw.toDouble()
                    is Double -> raw
                    is String -> raw.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
            
            // 🔑 字段存在性检查
            val hasStartDistance = intent.hasExtra("START_DISTANCE")
            val hasEndDistance = intent.hasExtra("END_DISTANCE")
            val hasIntervalDistance = intent.hasExtra("INTERVAL_DISTANCE")
            val hasAverageSpeed = intent.hasExtra("AVERAGE_SPEED")
            
            // 🚀 关键修复：使用通用读取函数，支持多种数据类型
            val startDistance = if (hasStartDistance) readNumberAsDouble("START_DISTANCE") else 0.0
            val endDistance = if (hasEndDistance) readNumberAsDouble("END_DISTANCE") else 0.0
            val intervalDistance = if (hasIntervalDistance) readNumberAsDouble("INTERVAL_DISTANCE") else 0.0
            val startDistanceInt = if (hasStartDistance) readNumberAsInt("START_DISTANCE") else 0
            val endDistanceInt = if (hasEndDistance) readNumberAsInt("END_DISTANCE") else 0
            val intervalDistanceInt = if (hasIntervalDistance) readNumberAsInt("INTERVAL_DISTANCE") else 0
            val cameraType = intent.getSafeIntExtra("CAMERA_TYPE", -1)
            val cameraIndex = intent.getSafeIntExtra("CAMERA_INDEX", -1)  // 🚀 新增：摄像头索引
            val averageSpeed = if (hasAverageSpeed) intent.getSafeIntExtra("AVERAGE_SPEED", 0) else 0
            
            Log.i(TAG, "🚦 限速信息: 限速=${speedLimit}km/h, 道路='$roadName', 类型=$speedLimitType")
            
            Log.i(TAG, "🚦 区间测速: 开始距离=${startDistanceInt}m (存在=$hasStartDistance), 结束距离=${endDistanceInt}m (存在=$hasEndDistance), 区间距离=${intervalDistanceInt}m (存在=$hasIntervalDistance), 摄像头类型=$cameraType, 摄像头索引=$cameraIndex, 平均速度=${averageSpeed}km/h (存在=$hasAverageSpeed)")
            
            // 🚀 关键修复：KEY_TYPE: 12110 只在区间中进行中时出现
            // 🔑 判断是否为区间测速进行中：只要有START_DISTANCE、END_DISTANCE或INTERVAL_DISTANCE任一字段存在，就认为是区间测速进行中
            // 注意：区间开始(CAMERA_TYPE=8)和区间结束(CAMERA_TYPE=9)都是从 KEY_TYPE: 10001 识别
            val isInSectionSpeedControl = hasStartDistance || hasEndDistance || hasIntervalDistance
            
            // 规则1: nSdiType 映射
            // 🔑 KEY_TYPE: 12110 出现时，如果检测到区间测速字段，nSdiType 应该映射成 4（区间进行中）
            val nSdiType = if (isInSectionSpeedControl) {
                // KEY_TYPE: 12110 中出现区间测速字段，说明这是区间进行中的数据
                // 区间进行中时，nSdiType 应该映射成 4
                Log.i(TAG, "🚦 [KEY_TYPE:12110] 检测到区间测速字段 → nSdiType=4 (区间进行中)")
                4  // 区间进行中
            } else {
                // 没有区间测速字段，保持之前的状态（区间开始/结束由 KEY_TYPE: 10001 处理）
                Log.v(TAG, "🚦 [KEY_TYPE:12110] 未检测到区间测速字段 → 保持之前nSdiType=${carrotManFields.value.nSdiType}")
                carrotManFields.value.nSdiType
            }
            
            // 规则2: nSdiDist 映射 END_DISTANCE（到终点剩余距离）
            // Python逻辑：普通情况下 xSpdDist = nSdiDist，但当nSdiBlockType in [2,3]时使用nSdiBlockDist
            // 🔑 重要：只有当字段存在且值大于0时才更新
            val nSdiDist = if (isInSectionSpeedControl && hasEndDistance && endDistanceInt > 0) {
                Log.i(TAG, "🚦 nSdiDist=$endDistanceInt (映射自END_DISTANCE - 到终点剩余距离)")
                endDistanceInt
            } else {
                carrotManFields.value.nSdiDist  // 保留之前的状态
            }
            
            // 规则3: nSdiBlockType 区间测速状态机
            // 🔑 KEY_TYPE: 12110 只在区间中进行中时出现
            // 规则：区间中进行中，nSdiBlockType 应该为 2（进行中）
            // 注意：开始和结束由 KEY_TYPE: 10001 处理
            val previous = carrotManFields.value
            val nSdiBlockType = if (isInSectionSpeedControl) {
                // KEY_TYPE: 12110 中出现区间测速字段，说明是区间进行中的数据
                // 区间进行中，nSdiBlockType = 2
                Log.i(TAG, "🚦 [KEY_TYPE:12110] 检测到区间测速字段 → nSdiBlockType=2 (区间测速进行中)")
                2  // 区间测速进行中
            } else {
                // 没有区间测速字段，保持之前的状态
                val keptType = previous.nSdiBlockType
                Log.v(TAG, "🚦 [KEY_TYPE:12110] 未检测到区间测速字段 → 保持之前nSdiBlockType=$keptType")
                keptType
            }
            
            // 规则4: nSdiSection 暂存 START_DISTANCE（便于调试/对照，参考之前代码版本）
            val nSdiSection = if (isInSectionSpeedControl && hasStartDistance && startDistanceInt >= 0) {
                startDistanceInt
            } else {
                carrotManFields.value.nSdiSection  // 保留之前的状态
            }
            
            // 规则5: nSdiBlockDist 映射 INTERVAL_DISTANCE（区间总长度）
            // Python逻辑：当nSdiBlockType in [2,3]时，xSpdDist = nSdiBlockDist（这是关键！）
            // 🔑 重要：使用 Int 类型，参考之前代码版本
            // 🔑 关键：这是Python用来显示区间测速距离的字段，必须正确设置
            val nSdiBlockDist = if (isInSectionSpeedControl && hasIntervalDistance && intervalDistanceInt > 0) {
                Log.i(TAG, "🚦 nSdiBlockDist=$intervalDistanceInt (映射自INTERVAL_DISTANCE - 区间总长度，Python将使用此值作为xSpdDist)")
                intervalDistanceInt
            } else {
                carrotManFields.value.nSdiBlockDist  // 保留之前的状态
            }
            
            // 规则6: nSdiBlockSpeed 映射 LIMITED_SPEED
            // 🔑 关键：Python使用此值作为xSpdLimit的基础值
            val nSdiBlockSpeed = if (isInSectionSpeedControl && speedLimit > 0) {
                Log.i(TAG, "🚦 nSdiBlockSpeed=$speedLimit (映射自LIMITED_SPEED)")
                speedLimit
            } else {
                carrotManFields.value.nSdiBlockSpeed.takeIf { it > 0 } ?: 0
            }
            
            // 规则7: nSdiSpeedLimit 也需要更新（Python代码检查此字段）
            // 🔑 关键：Python的_update_sdi需要nSdiSpeedLimit > 0才能激活区间测速控制
            val nSdiSpeedLimit = if (isInSectionSpeedControl && speedLimit > 0) {
                Log.i(TAG, "🚦 nSdiSpeedLimit=$speedLimit (映射自LIMITED_SPEED - Python需要此值>0才能激活区间测速)")
                speedLimit
            } else {
                carrotManFields.value.nSdiSpeedLimit.takeIf { it > 0 } ?: 0
            }
            
            // 🚀 关键修复：只有在检测到区间测速字段时才更新相关字段
            // 🔑 确保所有区间测速相关字段都被正确更新，以便Python正确识别和处理
            carrotManFields.value = carrotManFields.value.copy(
                nRoadLimitSpeed = speedLimit.takeIf { it > 0 } ?: carrotManFields.value.nRoadLimitSpeed,
                szPosRoadName = roadName.takeIf { it.isNotEmpty() } ?: carrotManFields.value.szPosRoadName,
                speedLimitType = speedLimitType.takeIf { it >= 0 } ?: carrotManFields.value.speedLimitType,
                // 区间测速相关字段（KEY_TYPE: 12110 - 区间中进行中时的数据）
                // 🔑 KEY_TYPE: 12110 只在区间中进行中时出现，此时 nSdiType=4, nSdiBlockType=2
                // 🔑 关键：Python的_update_sdi函数需要以下条件才能激活区间测速：
                //   1. nSdiType in [0,1,2,3,4,7,8,75,76] ✓ (nSdiType=4满足)
                //   2. nSdiSpeedLimit > 0 ✓ (已设置)
                //   3. nSdiBlockType in [2,3] ✓ (nSdiBlockType=2满足)
                //   4. 当nSdiBlockType in [2,3]时，Python使用nSdiBlockDist作为xSpdDist ✓
                nSdiType = if (isInSectionSpeedControl) nSdiType else carrotManFields.value.nSdiType,  // 区间进行中时 nSdiType=4
                nSdiSpeedLimit = if (isInSectionSpeedControl) nSdiSpeedLimit else carrotManFields.value.nSdiSpeedLimit,  // Python需要此值>0
                nSdiDist = if (isInSectionSpeedControl) nSdiDist else carrotManFields.value.nSdiDist,  // 映射 END_DISTANCE（到终点剩余距离）
                nSdiSection = if (isInSectionSpeedControl) nSdiSection else carrotManFields.value.nSdiSection,  // 暂存 START_DISTANCE（已行驶距离）
                nSdiBlockType = if (isInSectionSpeedControl) nSdiBlockType else carrotManFields.value.nSdiBlockType,  // 区间进行中时 nSdiBlockType=2
                nSdiBlockSpeed = if (isInSectionSpeedControl) nSdiBlockSpeed else carrotManFields.value.nSdiBlockSpeed,  // Python使用此值作为xSpdLimit
                nSdiBlockDist = if (isInSectionSpeedControl) nSdiBlockDist else carrotManFields.value.nSdiBlockDist,  // Python使用此值作为xSpdDist
                nSdiAverageSpeed = if (hasAverageSpeed) averageSpeed else carrotManFields.value.nSdiAverageSpeed, // 🆕 新增：区间平均速度
                extraState = intent.getSafeIntExtra("EXTRA_STATE", -1).takeIf { it >= 0 } ?: carrotManFields.value.extraState, // 🆕 新增：额外状态
                lastUpdateTime = System.currentTimeMillis()
            )
            
            Log.i(TAG, "🚦 ====== [KEY_TYPE:12110] 区间中进行中数据映射完成 ======")
            Log.i(TAG, "🚦 输入数据: CAMERA_TYPE=$cameraType, EXTRA_STATE=${intent.getSafeIntExtra("EXTRA_STATE", -1)}, LIMITED_SPEED=$speedLimit")
            Log.i(TAG, "🚦   字段存在性: START_DISTANCE=$hasStartDistance, END_DISTANCE=$hasEndDistance, INTERVAL_DISTANCE=$hasIntervalDistance, AVERAGE_SPEED=$hasAverageSpeed")
            Log.i(TAG, "🚦   区间测速检测: isInSectionSpeedControl=$isInSectionSpeedControl (只要有任一区间字段存在即为true)")
            if (isInSectionSpeedControl) {
                Log.i(TAG, "🚦   距离信息: START_DISTANCE=$startDistanceInt (已行驶距离), END_DISTANCE=$endDistanceInt (剩余距离), INTERVAL_DISTANCE=$intervalDistanceInt (总距离)")
            }
            Log.i(TAG, "🚦 输出字段:")
            Log.i(TAG, "🚦   nSdiType=${carrotManFields.value.nSdiType} (区间进行中时应该为4, Python将使用此值判断是否激活区间测速)")
            Log.i(TAG, "🚦   nSdiSpeedLimit=${carrotManFields.value.nSdiSpeedLimit} (Python需要此值>0才能激活, 来自LIMITED_SPEED=$speedLimit)")
            Log.i(TAG, "🚦   nSdiDist=${carrotManFields.value.nSdiDist} (END_DISTANCE - 剩余距离, Python在nSdiBlockType不在[2,3]时使用)")
            Log.i(TAG, "🚦   nSdiSection=${carrotManFields.value.nSdiSection} (START_DISTANCE - 已行驶距离, 用于调试)")
            Log.i(TAG, "🚦   nSdiBlockType=${carrotManFields.value.nSdiBlockType} (区间进行中时应该为2, Python将据此判断使用nSdiBlockDist)")
            Log.i(TAG, "🚦   nSdiBlockSpeed=${carrotManFields.value.nSdiBlockSpeed} (LIMITED_SPEED, Python将使用此值*安全系数作为xSpdLimit)")
            Log.i(TAG, "🚦   nSdiBlockDist=${carrotManFields.value.nSdiBlockDist} (INTERVAL_DISTANCE - 区间总长度, 🔑关键：Python在nSdiBlockType in [2,3]时使用此值作为xSpdDist)")
            Log.i(TAG, "🚦 Python处理逻辑:")
            Log.i(TAG, "🚦   - 当nSdiBlockType in [2,3]时，Python会设置: xSpdDist = nSdiBlockDist, xSpdType = 4")
            Log.i(TAG, "🚦   - 这样UI就能正确显示区间测速的距离信息")
            Log.i(TAG, "🚦 说明: KEY_TYPE:12110 只在区间中进行中时出现，此时 nSdiType=4, nSdiBlockType=2")
            Log.i(TAG, "🚦 说明: 区间开始(CAMERA_TYPE=8)和结束(CAMERA_TYPE=9)由 KEY_TYPE:10001 处理")
            Log.i(TAG, "🚦 ==================================================")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理限速信息失败: ${e.message}", e)
        }
    }

    /**
     * 处理电子眼信息广播 (KEY_TYPE: 13005)
     */
    fun handleCameraInfo(intent: Intent) {
        //Log.d(TAG, "📷 处理电子眼信息广播")
        
        try {
            val cameraType = intent.getSafeIntExtra("CAMERA_TYPE", -1)
            val cameraDistance = intent.getSafeIntExtra("CAMERA_DISTANCE", 0)
            val cameraSpeedLimit = intent.getSafeIntExtra("CAMERA_SPEED_LIMIT", 0)
            
            Log.d(TAG, "📷 电子眼信息: 类型=$cameraType, 距离=${cameraDistance}m, 限速=${cameraSpeedLimit}km/h")
            
            // 映射高德CAMERA_TYPE到Python nSdiType
            val mappedSdiType = if (cameraType >= 0) Companion.mapAmapCameraTypeToSdi(cameraType) else carrotManFields.value.nSdiType
            
            // 根据距离判断是否需要清空SDI信息 - 距离小于20米时清空
            val shouldClearSdi = cameraDistance <= 20
            
            carrotManFields.value = carrotManFields.value.copy(
                nAmapCameraType = if (cameraType >= 0) cameraType else carrotManFields.value.nAmapCameraType,
                nSdiType = if (shouldClearSdi) -1 else mappedSdiType,  // 距离为0时清空SDI类型
                nSdiDist = if (shouldClearSdi) 0 else cameraDistance,  // 距离为0时清空距离
                nSdiSpeedLimit = if (shouldClearSdi) 0 else cameraSpeedLimit,  // 距离为0时清空限速
                lastUpdateTime = System.currentTimeMillis()
            )
            
            if (shouldClearSdi) {
                Log.d(TAG, "🧹 SDI信息已清空: 摄像头距离=${cameraDistance}m (小于20米阈值)")
            }
            
            Log.d(TAG, "📷 映射结果: 高德类型=$cameraType -> Python SDI类型=$mappedSdiType")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理电子眼信息失败: ${e.message}", e)
        }
    }

    /**
     * 处理SDI Plus信息广播 (KEY_TYPE: 10007)
     */
    fun handleSdiPlusInfo(intent: Intent) {
       // Log.d(TAG, "📊 处理SDI Plus信息广播")
        
        try {
            val sdiPlusType = intent.getSafeIntExtra("SDI_PLUS_TYPE", -1)
            val sdiPlusDistance = intent.getSafeIntExtra("SDI_PLUS_DISTANCE", 0)
            val sdiPlusSpeedLimit = intent.getSafeIntExtra("SDI_PLUS_SPEED_LIMIT", 0)
            
          //  Log.d(TAG, "📊 SDI Plus信息: 类型=$sdiPlusType, 距离=${sdiPlusDistance}m, 限速=${sdiPlusSpeedLimit}km/h")

         carrotManFields.value = carrotManFields.value.copy(
                nSdiPlusType = sdiPlusType,
                nSdiPlusDist = sdiPlusDistance,
                nSdiPlusSpeedLimit = sdiPlusSpeedLimit,
             lastUpdateTime = System.currentTimeMillis()
         )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理SDI Plus信息失败: ${e.message}", e)
        }
    }

    // ===============================
    // 交通相关处理（整合自AmapTrafficHandlers）
    // ===============================
    // 注意：handleSpeedLimit、handleCameraInfo、handleSdiPlusInfo已在上面实现，这里不再重复
    // 需要添加：handleTrafficInfo、handleNaviSituation、handleTrafficLightInfo

    /**
     * 处理路况信息广播 (KEY_TYPE: 10070)
     */
    fun handleTrafficInfo(intent: Intent) {
        try {
            val trafficLevel = intent.getSafeIntExtra("TRAFFIC_LEVEL", -1)
            val trafficDescription = intent.getStringExtra("TRAFFIC_DESCRIPTION") ?: ""

            carrotManFields.value = carrotManFields.value.copy(
                trafficLevel = trafficLevel,
                trafficDescription = trafficDescription,
                lastUpdateTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理路况信息失败: ${e.message}", e)
        }
    }

    /**
     * 处理导航态势广播 (KEY_TYPE: 13003)
     */
    fun handleNaviSituation(intent: Intent) {
        try {
            val situationType = intent.getSafeIntExtra("SITUATION_TYPE", -1)
            val situationDistance = intent.getSafeIntExtra("SITUATION_DISTANCE", 0)
            val situationDescription = intent.getStringExtra("SITUATION_DESCRIPTION") ?: ""

            carrotManFields.value = carrotManFields.value.copy(
                situationType = situationType,
                situationDistance = situationDistance,
                situationDescription = situationDescription,
                lastUpdateTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理导航态势失败: ${e.message}", e)
        }
    }

    /**
     * 处理红绿灯信息广播 - KEY_TYPE: 60073
     */
    fun handleTrafficLightInfo(intent: Intent) {
        try {
            val trafficLightStatus = when {
                intent.hasExtra("trafficLightStatus") -> intent.getSafeIntExtra("trafficLightStatus", 0)
                intent.hasExtra("TRAFFIC_LIGHT_STATUS") -> intent.getSafeIntExtra("TRAFFIC_LIGHT_STATUS", 0)
                intent.hasExtra("LIGHT_STATUS") -> intent.getSafeIntExtra("LIGHT_STATUS", 0)
                else -> 0
            }

            val redLightCountDown = intent.getSafeIntExtra("redLightCountDownSeconds", 0)
            val greenLightCountDown = intent.getSafeIntExtra("greenLightLastSecond", 0)
            val direction = when {
                intent.hasExtra("dir") -> intent.getSafeIntExtra("dir", 0)
                intent.hasExtra("TRAFFIC_LIGHT_DIRECTION") -> intent.getSafeIntExtra("TRAFFIC_LIGHT_DIRECTION", 0)
                intent.hasExtra("LIGHT_DIRECTION") -> intent.getSafeIntExtra("LIGHT_DIRECTION", 0)
                else -> 0
            }
            val waitRound = intent.getSafeIntExtra("waitRound", 0)

            var carrotTrafficState = Companion.mapTrafficLightStatus(trafficLightStatus, direction)
            var leftSec = if (trafficLightStatus == 1 || trafficLightStatus == 3 || trafficLightStatus == 2 || trafficLightStatus == 4) redLightCountDown else redLightCountDown

            val previousTrafficState = carrotManFields.value.trafficLightState
            val previousLeftSec = carrotManFields.value.trafficLightCountdown

            if (carrotTrafficState == 0 && leftSec <= 0) {
                if (previousTrafficState == 1 && previousLeftSec <= 3) {
                    carrotTrafficState = 2
                    leftSec = 30
                }
            }

            val stateChanged = (carrotTrafficState != previousTrafficState) || (leftSec != previousLeftSec)

            carrotManFields.value = carrotManFields.value.copy(
                trafficLightState = carrotTrafficState,
                trafficLightCountdown = leftSec,
                trafficLightDistance = 0,  // compatibility field, distance not available from 60073 broadcast
                amap_traffic_light_status = trafficLightStatus,
                amap_traffic_light_dir = direction,
                amap_green_light_last_second = greenLightCountDown,
                amap_wait_round = waitRound,
                lastUpdateTime = System.currentTimeMillis()
            )

            if (stateChanged) {
                val directionDesc = Companion.getTrafficLightDirectionDesc(direction)
                Log.v(TAG, "🚦 交通灯状态变化: state=$carrotTrafficState, left=$leftSec, dir=$directionDesc")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理红绿灯信息失败: ${e.message}", e)
        }
    }

    // ===============================
    // 目的地和导航管理（整合自AmapDestinationManager和AmapNavigationManager）
    // ===============================

    // 目的地缓存
    private val destinationCache = mutableMapOf<String, Triple<Double, Double, String>>()

    /**
     * 🎯 处理和验证目的地信息
     * 从高德地图获取目的地信息并自动发送给comma3设备
     */
    fun handleDestinationInfo(intent: Intent) {
        // 从高德地图获取目的地信息
        val endPOIName = intent.getStringExtra("endPOIName") ?: ""
        val endPOIAddr = intent.getStringExtra("endPOIAddr") ?: ""
        val endPOILatitude = intent.getSafeDoubleExtra("endPOILatitude", 0.0)
        val endPOILongitude = intent.getSafeDoubleExtra("endPOILongitude", 0.0)

        // 获取导航路线信息
        val destinationName = intent.getStringExtra("DESTINATION_NAME") ?: endPOIName
        val routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", 0)
        val routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", 0)

        // 验证目的地信息有效性
        if (validateDestination(endPOILongitude, endPOILatitude, endPOIName)) {
            val currentDestination = carrotManFields.value

            // 检查目的地是否发生变化
            if (shouldUpdateDestination(
                    currentDestination.goalPosX, currentDestination.goalPosY, currentDestination.szGoalName,
                    endPOILongitude, endPOILatitude, endPOIName
                )) {

                Log.i(TAG, "🎯 目的地信息更新:")
                Log.d(TAG, "   名称: $endPOIName")
                Log.d(TAG, "   地址: $endPOIAddr")
                Log.d(TAG, "   坐标: ($endPOILatitude, $endPOILongitude)")
                Log.d(TAG, "   剩余距离: ${routeRemainDis}米")
                Log.d(TAG, "   预计时间: ${routeRemainTime}秒")

                // 更新CarrotMan字段
                carrotManFields.value = carrotManFields.value.copy(
                    goalPosX = endPOILongitude,
                    goalPosY = endPOILatitude,
                    szGoalName = endPOIName.takeIf { it.isNotEmpty() } ?: destinationName,
                    nGoPosDist = routeRemainDis.takeIf { it > 0 } ?: carrotManFields.value.nGoPosDist,
                    nGoPosTime = routeRemainTime.takeIf { it > 0 } ?: carrotManFields.value.nGoPosTime,
                    lastUpdateTime = System.currentTimeMillis()
                )

                // 🎯 自动发送目的地信息给comma3（修复坐标顺序：经度，纬度）
                sendDestinationToComma3(endPOILongitude, endPOILatitude, endPOIName, endPOIAddr)

                // 缓存目的地信息
                cacheDestination("current_destination", endPOILongitude, endPOILatitude, endPOIName)

                // 更新UI显示
                updateUI?.invoke("目的地已更新: $endPOIName")
            }
        } else {
            Log.w(TAG, "⚠️ 目的地信息无效: 坐标($endPOILatitude, $endPOILongitude), 名称: $endPOIName")
        }
    }

    /**
     * 处理收藏点数据
     */
    fun handleFavoriteData(favoriteData: String) {
        try {
            val json = JSONObject(favoriteData)
            val latitude = json.optDouble("latitude", 0.0)
            val longitude = json.optDouble("longitude", 0.0)
            val name = json.optString("name", "")
            val type = json.optString("type", "favorite")

            if (validateDestination(longitude, latitude, name)) {
                Log.i(TAG, "🌟 收藏点数据: $name ($latitude, $longitude)")

                carrotManFields.value = carrotManFields.value.copy(
                    goalPosX = longitude,
                    goalPosY = latitude,
                    szGoalName = name,
                    lastUpdateTime = System.currentTimeMillis()
                )

                sendDestinationToComma3(longitude, latitude, name, "收藏点: $type")
                cacheDestination("favorite_$type", longitude, latitude, name)
                updateUI?.invoke("收藏点已设置: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析收藏点数据失败: ${e.message}", e)
        }
    }

    /**
     * 处理家庭/公司地址数据
     */
    fun handleHomeCompanyAddress(type: String, intent: Intent) {
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)
        val address = intent.getStringExtra("address") ?: ""
        val name = if (type == "home") "家" else "公司"

        if (validateDestination(longitude, latitude, name)) {
            Log.i(TAG, "🏠 ${name}地址: $address ($latitude, $longitude)")

            carrotManFields.value = carrotManFields.value.copy(
                goalPosX = longitude,
                goalPosY = latitude,
                szGoalName = name,
                lastUpdateTime = System.currentTimeMillis()
            )

            sendDestinationToComma3(longitude, latitude, name, address)
            cacheDestination(type + "_address", longitude, latitude, name)

            updateUI?.invoke("${name}地址已设置: $address")
        }
    }

    /**
     * 处理家庭/公司导航请求
     */
    fun handleHomeCompanyNavigation(intent: Intent) {
        val navigationType = intent.getStringExtra("navigation_type") ?: ""
        when (navigationType.lowercase()) {
            "home" -> {
                Log.i(TAG, "🏠 处理回家导航请求")
                handleHomeCompanyAddress("home", intent)
            }
            "company" -> {
                Log.i(TAG, "🏢 处理到公司导航请求")
                handleHomeCompanyAddress("company", intent)
            }
            else -> {
                Log.w(TAG, "⚠️ 未知的家庭/公司导航类型: $navigationType")
            }
        }
    }

    /**
     * 处理收藏点结果
     */
    fun handleFavoriteResult(intent: Intent) {
        val favoriteData = intent.getStringExtra("FAVORITE_DATA")
        if (!favoriteData.isNullOrEmpty()) {
            Log.i(TAG, "🌟 处理收藏点结果")
            handleFavoriteData(favoriteData)
        } else {
            val name = intent.getStringExtra("favorite_name") ?: ""
            val latitude = intent.getDoubleExtra("favorite_latitude", 0.0)
            val longitude = intent.getDoubleExtra("favorite_longitude", 0.0)

            if (name.isNotEmpty() && latitude != 0.0 && longitude != 0.0) {
                Log.i(TAG, "🌟 从分散字段获取收藏点信息: $name")
                val syntheticJson = JSONObject().apply {
                    put("name", name)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("type", "favorite")
                }
                handleFavoriteData(syntheticJson.toString())
            }
        }
    }

    /**
     * 🎯 处理路线规划
     */
    fun handleRoutePlanning(intent: Intent) {
        Log.i(TAG, "🗺️ 处理路线规划")

        val startLat = intent.getDoubleExtra("start_latitude", 0.0)
        val startLon = intent.getDoubleExtra("start_longitude", 0.0)
        val endLat = intent.getDoubleExtra("end_latitude", 0.0)
        val endLon = intent.getDoubleExtra("end_longitude", 0.0)
        val endName = intent.getStringExtra("end_name") ?: ""

        if (endLat != 0.0 && endLon != 0.0) {
            Log.d(TAG, "   起点: ($startLat, $startLon)")
            Log.d(TAG, "   终点: $endName ($endLat, $endLon)")

            // 创建合成的目的地Intent并处理
            val syntheticIntent = Intent().apply {
                putExtra("endPOIName", endName)
                putExtra("endPOILatitude", endLat)
                putExtra("endPOILongitude", endLon)
                putExtra("ROUTE_REMAIN_DIS", 0)  // 规划阶段暂无距离信息
                putExtra("ROUTE_REMAIN_TIME", 0)
            }

            handleDestinationInfo(syntheticIntent)
        }
    }

    /**
     * 🎯 处理开始导航
     */
    fun handleStartNavigation(intent: Intent) {
        Log.i(TAG, "🚀 开始导航")

        carrotManFields.value = carrotManFields.value.copy(
            isNavigating = true,
            active_carrot = 1,
            lastUpdateTime = System.currentTimeMillis()
        )

        // 如果有目的地信息，重新发送到comma3
        val currentFields = carrotManFields.value
        if (currentFields.goalPosX != 0.0 && currentFields.goalPosY != 0.0 && currentFields.szGoalName.isNotEmpty()) {
            // 通过destinationManager发送目的地信息
            val syntheticIntent = Intent().apply {
                putExtra("endPOIName", currentFields.szGoalName)
                putExtra("endPOILatitude", currentFields.goalPosY)
                putExtra("endPOILongitude", currentFields.goalPosX)
                putExtra("endPOIAddr", "导航开始")
            }
            handleDestinationInfo(syntheticIntent)
        }

        updateUI?.invoke("导航已开始")
    }

    /**
     * 🎯 处理停止导航
     */
    fun handleStopNavigation(intent: Intent) {
        Log.i(TAG, "🛑 停止导航")

        carrotManFields.value = carrotManFields.value.copy(
            isNavigating = false,
            active_carrot = 0,
            nGoPosDist = 0,
            nGoPosTime = 0,
            nTBTDist = 0,
            szTBTMainText = "",
            lastUpdateTime = System.currentTimeMillis()
        )

        updateUI?.invoke("导航已停止")
    }

    /**
     * 道路限速更新 - 直接映射到CarrotMan字段（整合自AmapDataProcessor）
     */
    fun updateRoadSpeedLimit(newLimit: Int) {
        if (newLimit <= 0) return

        // 直接更新到carrotManFields，不进行变化检测
        carrotManFields.value = carrotManFields.value.copy(
            nRoadLimitSpeed = newLimit,
            lastUpdateTime = System.currentTimeMillis()
        )
        
        Log.d(TAG, "🚦 限速已更新: ${newLimit}km/h (实时更新到carrotManFields)")
    }

    // ===============================
    // 私有辅助方法
    // ===============================

    /**
     * 自动发送目的地信息给comma3设备
     */
    private fun sendDestinationToComma3(longitude: Double, latitude: Double, name: String, address: String = "") {
        try {
            networkManager?.sendDestinationToComma3(longitude, latitude, name, address)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 发送目的地信息失败: ${e.message}")
        }
    }

    /**
     * 缓存目的地信息
     */
    private fun cacheDestination(key: String, longitude: Double, latitude: Double, name: String) {
        destinationCache[key] = Triple(longitude, latitude, name)
        Log.d(TAG, "📝 目的地已缓存: $key -> $name")
    }

    /**
     * 验证目的地坐标和信息的有效性
     */
    private fun validateDestination(longitude: Double, latitude: Double, name: String): Boolean {
        val isValidLongitude = longitude in -180.0..180.0
        val isValidLatitude = latitude in -90.0..90.0
        val isValidName = name.isNotEmpty() && name.length <= 100
        val isNonZeroCoordinates = longitude != 0.0 && latitude != 0.0
        return isValidLongitude && isValidLatitude && isValidName && isNonZeroCoordinates
    }

    /**
     * 检查是否需要更新目的地信息
     */
    private fun shouldUpdateDestination(
        currentLon: Double, currentLat: Double, currentName: String,
        newLon: Double, newLat: Double, newName: String
    ): Boolean {
        val distance = haversineDistance(currentLat, currentLon, newLat, newLon)
        return currentName != newName || distance > 100.0 || (currentLon == 0.0 && currentLat == 0.0)
    }

    /**
     * 计算两点间距离（哈弗辛公式），单位：米
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    /**
     * 处理地理位置信息广播 (KEY_TYPE: 12205)
     */
    fun handleGeolocationInfo(intent: Intent) {
        // 地理位置信息已不再写入CarrotManFields（字段已清理）
        // 如需使用，可从intent读取: ADMIN_AREA, CITY_NAME, DISTRICT_NAME
    }

    /**
     * 处理未知信息13011广播 (KEY_TYPE: 13011)
     */
    fun handleUnknownInfo13011(intent: Intent) {
        Log.d(TAG, "❓ 处理未知信息13011广播")
        
        try {
            // 记录所有额外数据用于调试
            intent.extras?.let { bundle ->
                Log.d(TAG, "📋 未知信息13011包含的数据:")
                for (key in bundle.keySet()) {
                    @Suppress("DEPRECATION")
                    val value = bundle.get(key)
                    Log.d(TAG, "  $key = $value")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 处理未知信息13011失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理车道线信息广播 - KEY_TYPE: 13012
     * 根据官方协议EXTRA_DRIVE_WAY字段提取真实的车道数量
     */
    fun handleDriveWayInfo(intent: Intent) {
        try {
            val driveWayJson = intent.getStringExtra("EXTRA_DRIVE_WAY")
            
            if (driveWayJson.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ 车道线数据为空")
                return
            }

            Log.i(TAG, "🛣️ 收到车道线信息:")
            Log.i(TAG, "  📄 原始JSON: $driveWayJson")

            // 解析车道线JSON数据
            val jsonObject = org.json.JSONObject(driveWayJson)
            
            // 提取关键字段
            val driveWayEnabled = jsonObject.optString("drive_way_enabled", "false")
            val driveWaySize = jsonObject.optInt("drive_way_size", 0)
            
            //Log.i(TAG, "  ✅ 车道线是否有效: $driveWayEnabled")
            Log.i(TAG, "  🔢 车道数量: $driveWaySize")

            // 如果车道线有效且车道数量大于0，则更新字段
            if (driveWayEnabled == "true" && driveWaySize > 0) {
                // 解析车道详细信息
                val laneInfoList = mutableListOf<LaneInfo>()
                if (jsonObject.has("drive_way_info")) {
                    val driveWayInfo = jsonObject.getJSONArray("drive_way_info")
                    for (i in 0 until driveWayInfo.length()) {
                        val laneObj = driveWayInfo.getJSONObject(i)
                        val iconId = laneObj.optString("drive_way_lane_Back_icon", "0")
                        val isAdvised = laneObj.optBoolean("trafficLaneAdvised", false)
                        val driveWayNumber = laneObj.optInt("drive_way_number", i)
                        val driveWayLaneExtended = laneObj.optString("drive_way_lane_Extended", "0")
                        val trafficLaneExtendedNew = laneObj.optInt("trafficLaneExtendedNew", 0)
                        val trafficLaneType = laneObj.optInt("trafficLaneType", 0)
                        
                        laneInfoList.add(LaneInfo(
                            id = iconId,
                            isRecommended = isAdvised,
                            driveWayNumber = driveWayNumber,
                            driveWayLaneExtended = driveWayLaneExtended,
                            trafficLaneExtendedNew = trafficLaneExtendedNew,
                            trafficLaneType = trafficLaneType
                        ))
                    }
                }

                carrotManFields.value = carrotManFields.value.copy(
                    nLaneCount = driveWaySize,
                    laneInfoList = laneInfoList, // 更新车道列表
                    lastUpdateTime = System.currentTimeMillis()
                )
                
                Log.i(TAG, "  🎯 已更新车道数量到CarrotMan字段: $driveWaySize 车道, 列表: ${laneInfoList.map { "${it.id}(trafficLaneType=${it.trafficLaneType},推荐=${it.isRecommended})" }}")
                
            } else {
                Log.w(TAG, "  ❌ 车道线信息无效或车道数量为0")
                // 可选：将车道数量设为0表示无车道信息
                carrotManFields.value = carrotManFields.value.copy(
                    nLaneCount = 0,
                    laneInfoList = emptyList(), // 清空车道列表
                    lastUpdateTime = System.currentTimeMillis()
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 解析车道线信息失败: ${e.message}", e)
        }
    }
    
    /**
     * 🎯 将高德地图的 ROAD_TYPE 映射到 CarrotMan 的 roadcate
     * 重要：roadcate 是道路类别，10,11 表示高速公路，其他值表示非高速公路
     * 基于逆向工程文档和Python代码的映射逻辑
     * 用户说明：roadcate is the width of the road, 10,11 is the highway
     */
    private fun mapRoadTypeToRoadcate(roadType: Int): Int {
        // 简化规则（不依赖车道数）：
        // - 高速(0) → roadcate=10
        // - 快速道(6) → roadcate=10
        // - 其他全部 → roadcate=6
        return when (roadType) {
            0 -> 10  // 高速公路
            6 -> 10  // 快速道
            else -> 6  // 其他所有道路类型
        }
    }
    
    /**
     * 🎯 获取道路类型描述
     * 用于日志记录和调试
     */
    private fun getRoadTypeDescription(roadType: Int): String {
        return when (roadType) {
            0 -> "高速公路"
            1 -> "国道"
            2 -> "省道"
            3 -> "县道"
            4 -> "乡公路"
            5 -> "县乡村内部道路"
            6 -> "快速道"
            7 -> "主要道路"
            8 -> "次要道路"
            9 -> "普通道路"
            10 -> "非导航道路"
            else -> "未知道路类型"
        }
    }
    
    /**
     * 🎯 获取 roadcate 含义描述
     * roadcate 是道路类别参数，10,11 表示高速公路，其他值表示非高速公路
     * 用户说明：roadcate is the width of the road, 10,11 is the highway
     */
    private fun getRoadcateDescription(roadcate: Int): String {
        return when (roadcate) {
            0 -> "默认/未知宽度"
            2 -> "窄道路（单车道）"
            6 -> "中等宽度（双车道）"
            8 -> "宽道路（三车道）"
            10, 11 -> "很宽道路（四车道及以上）"
            else -> "未知 roadcate 值: $roadcate"
        }
    }

    /**
     * 生成转弯指令文本
     */
    private fun generateTurnInstruction(turnType: Int, roadName: String, distance: Int): String {
        val action = when (turnType) {
            12 -> "左转"
            13 -> "右转"
            14 -> "掉头"
            202 -> "到达途经点"
            16 -> "急左转"
            19 -> "急右转"
            51 -> "直行"
            52 -> "直行"
            53 -> "直行进入"  // 高架入口
            206 -> "到达收费站"
            207 -> "进入隧道"
            54 -> "直行"  // 桥梁
            55 -> "直行"      // 其他通知
            101 -> "右前方"
            102 -> "靠左行驶" //手动纠正
            201 -> "到达目的地"
            1000 -> "轻微左转"
            1001 -> "轻微右转"
            1006 -> "靠左行驶"
            1007 -> "靠右行驶"
            // 分岔路口
            7, 17, 44, 75, 76, 118, 1002 -> "左侧分岔"
            6, 43, 73, 74, 117, 123, 124, 1003 -> "右侧分岔"
            // 环岛
            131, 132, 140, 141 -> "环岛轻微转弯"
            133, 139 -> "环岛转弯"
            134, 135, 136, 137, 138 -> "环岛急转弯"
            142 -> "环岛直行"
            else -> "继续行驶"
        }

        return when {
            turnType == 201 -> "到达目的地"
            roadName.isNotEmpty() && distance > 0 -> "${action}进入${roadName}，${formatDistance(distance)}"
            roadName.isNotEmpty() -> "${action}进入${roadName}"
            distance > 0 -> "${action}，${formatDistance(distance)}"
            else -> action
        }
    }

    /**
     * 格式化距离显示
     * 超过10公里显示公里，超过1公里显示几点几公里，1公里内显示米
     * @param distanceMeters 距离（米）
     * @return 格式化的距离字符串
     */
    private fun formatDistance(distanceMeters: Int): String {
        return when {
            distanceMeters >= 10000 -> {
                val kilometers = distanceMeters / 1000
                "${kilometers}公里"
            }
            distanceMeters >= 1000 -> {
                val kilometers = distanceMeters / 1000.0
                "${String.format("%.1f", kilometers)}公里"
            }
            else -> "${distanceMeters}米"
        }
    }
}

/**
 * 🛠️ Intent 扩展方法：安全地获取各种数值类型的 Extra 数据，避免 ClassCastException 和系统日志警告
 */
private fun Intent.getSafeLongExtra(name: String, defaultValue: Long): Long {
    @Suppress("DEPRECATION")
    val value = this.extras?.get(name)
    return when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: defaultValue
        else -> defaultValue
    }
}

private fun Intent.getSafeIntExtra(name: String, defaultValue: Int): Int {
    @Suppress("DEPRECATION")
    val value = this.extras?.get(name)
    return when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}

private fun Intent.getSafeDoubleExtra(name: String, defaultValue: Double): Double {
    @Suppress("DEPRECATION")
    val value = this.extras?.get(name)
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: defaultValue
        else -> defaultValue
    }
}
