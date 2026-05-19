package com.example.navipilot

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.navipilot.utils.CoordinatePreferences
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.ui.components.HelpPage
import com.example.navipilot.ui.components.NavMode
import com.example.navipilot.ui.components.OsmMapView
import com.example.navipilot.ui.components.ProfilePage
import com.example.navipilot.ui.components.AutoSwitchExperimentPage
import com.example.navipilot.ui.components.Carrot7706JsonDebugOverlay
import com.example.navipilot.ui.components.LedMatrixPreview
import com.example.navipilot.ui.components.ModelSwitcherPage
import com.example.navipilot.data.ModelDownloadManager
import com.example.navipilot.data.SshConnectionManager
import com.example.navipilot.ui.components.OnboardingScreen
import com.example.navipilot.ui.components.TencentNavPage
import com.example.navipilot.ui.components.AmapMobileNavPage
import com.example.navipilot.ui.components.GoogleNavPage
import com.example.navipilot.navigation.GoogleNavManager
import com.example.navipilot.ui.components.GoogleWelcomeDialog
import com.example.navipilot.ui.components.hasGoogleWelcomeShown
import com.example.navipilot.ui.components.PrivacyConsentDialog
import com.example.navipilot.ui.components.hasPrivacyConsent
import com.example.navipilot.ui.components.isOnboardingCompleted
import com.example.navipilot.ui.components.setOnboardingCompleted
import com.example.navipilot.ui.driving.DrivingReportScreen
import com.example.navipilot.ui.theme.NavipilotTheme
import com.example.navipilot.ui.utils.localized
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * MainActivity UI组件管理类
 * 负责所有UI组件的定义和界面逻辑
 */
class MainActivityUI(
    private val core: MainActivityCore
) {

    /**
     * 设置用户界面
     */
    @Composable
    fun SetupUserInterface() {
        NavipilotTheme {
            val appContext = LocalContext.current

            // 🆕 隐私政策 + 新手引导流程
            var showPrivacyDialog by remember { mutableStateOf(!hasPrivacyConsent(appContext)) }
            var showOnboarding by remember { mutableStateOf(
                hasPrivacyConsent(appContext) && !isOnboardingCompleted(appContext)
            ) }
            
            if (showPrivacyDialog) {
                PrivacyConsentDialog(
                    onAgree = {
                        showPrivacyDialog = false
                        if (!isOnboardingCompleted(appContext)) {
                            showOnboarding = true
                        }
                    },
                    onDisagree = {
                        // 不同意则退出应用
                        (appContext as? android.app.Activity)?.finish()
                    }
                )
                return@NavipilotTheme
            }
            
            if (showOnboarding) {
                OnboardingScreen(onComplete = {
                    setOnboardingCompleted(appContext)
                    showOnboarding = false
                })
                return@NavipilotTheme
            }

            // 🗾 Google 地图首次欢迎弹窗（首次启动时显示，仅显示一次）
            var showGoogleWelcome by remember { mutableStateOf(
                !hasGoogleWelcomeShown(appContext)
            ) }

            // Google 欢迎弹窗
            if (showGoogleWelcome) {
                GoogleWelcomeDialog(
                    onConfirm = {
                        showGoogleWelcome = false
                    }
                )
                // 阻止其他 UI 渲染直到确认
                return@NavipilotTheme
            }
            
            // 🤖 LED 自动化显示引擎 — 应用级别，不受页面切换影响
            val ledContext = LocalContext.current
            val ledManager = remember { com.example.navipilot.ui.components.LedMatrixManager.getInstance(ledContext) }
            var isLedConnectedGlobal by remember { mutableStateOf(
                ledManager.state == com.example.navipilot.ui.components.LedMatrixManager.State.CONNECTED ||
                ledManager.state == com.example.navipilot.ui.components.LedMatrixManager.State.SENDING
            ) }
            LaunchedEffect(Unit) {
                val prevCallback = ledManager.onStateChanged
                ledManager.onStateChanged = { state, msg ->
                    isLedConnectedGlobal = (state == com.example.navipilot.ui.components.LedMatrixManager.State.CONNECTED ||
                        state == com.example.navipilot.ui.components.LedMatrixManager.State.SENDING)
                    prevCallback?.invoke(state, msg)
                }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(800)
                    val fields = core.carrotManFields.value
                    val xiaogeSteeringAngle = core.xiaogeData.value?.carState?.steeringAngleDeg ?: 0f
                    val xiaogeData = core.xiaogeData.value
                    val xiaogeLeadX = xiaogeData?.modelV2?.lead0?.x ?: 0f
                    val xiaogeLeadProb = xiaogeData?.modelV2?.lead0?.prob ?: 0f
                    val xiaogeLeftBlind = xiaogeData?.carState?.leftBlindspot ?: false
                    val xiaogeRightBlind = xiaogeData?.carState?.rightBlindspot ?: false
                    // 车速 (m/s -> km/h)
                    val xiaogeSpeedKmh = (xiaogeData?.carState?.vEgo ?: 0f) * 3.6f
                    ledManager.updateAutoDisplay(
                        com.example.navipilot.ui.components.LedMatrixManager.AutoDisplayData(
                            isOnroad = fields.isOnroad,
                            isNavigating = fields.isNavigating,
                            active = fields.active,
                            xState = fields.xState,
                            vEgoKph = fields.vEgoKph,
                            vCruiseKph = fields.vCruiseKph,
                            nSdiDist = fields.nSdiDist,
                            nSdiSpeedLimit = fields.nSdiSpeedLimit,
                            nSdiType = fields.nSdiType,
                            nSdiBlockType = fields.nSdiBlockType,
                            nSdiBlockSpeed = fields.nSdiBlockSpeed,
                            nTBTDist = fields.nTBTDist,
                            nTBTTurnType = fields.nTBTTurnType,
                            szTBTMainText = fields.szTBTMainText,
                            trafficState = fields.trafficState,
                            szPosRoadName = fields.szPosRoadName,
                            nRoadLimitSpeed = fields.nRoadLimitSpeed,
                            leftBlindspot = xiaogeLeftBlind || fields.tencentSlice.leftLaneVehicle,
                            rightBlindspot = xiaogeRightBlind || fields.tencentSlice.rightLaneVehicle,
                            leadDistance = xiaogeLeadX,
                            leadProb = xiaogeLeadProb,
                            steeringAngleDeg = xiaogeSteeringAngle,
                            nGoPosDist = fields.nGoPosDist,
                            nextRoadNOAOrNot = fields.nextRoadNOAOrNot,
                            atcType = fields.atcType,
                            vTurnSpeed = fields.vTurnSpeed,
                        )
                    )
                }
            }

            // 🆕 导航结束自动跳转到驾驶报告页面
            var wasNavigating by remember { mutableStateOf(false) }
            LaunchedEffect(core.carrotManFields.value.isNavigating) {
                val isNowNavigating = core.carrotManFields.value.isNavigating
                // 检测导航结束：从"导航中"变为"非导航"
                if (wasNavigating && !isNowNavigating) {
                    // 导航刚结束，检查是否有驾驶数据
                    val collector = core.getDrivingDataCollectorSafely()
                    if (collector != null && collector.hasData()) {
                        // 有驾驶数据，跳转到驾驶报告页面
                        core.currentPage = 7
                    }
                }
                wasNavigating = isNowNavigating
            }

            // 🚗 停车位置记录功能
            val prefs = remember { ledContext.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) }
            var lastRecordedSpeed by remember { mutableStateOf(-1f) }

            // 🆕 读取保存的停车位置
            val parkedLocation = remember {
                com.example.navipilot.utils.CoordinatePreferences.getParkedLocation(prefs)
            }

            // 🚗 停车记录：连接comma3时使用其车速，否则使用gps_speed
            LaunchedEffect(core.carrotManFields.value.gps_speed, core.xiaogeData.value) {
                // 判断是否连接了 comma3
                val isCommaConnected = core.getNetworkClientSafely()?.let { client ->
                    !core.xiaogeDataTimeout.value &&
                    client.isRunning() && client.getCurrentDevice() != null
                } ?: false

                // 获取当前车速
                val currentSpeed = if (isCommaConnected) {
                    // 连接了 comma3，使用其车速 (m/s -> km/h)
                    (core.xiaogeData.value?.carState?.vEgo ?: 0f) * 3.6f
                } else {
                    // 未连接，使用 GPS 车速
                    core.carrotManFields.value.gps_speed
                }

                // 当速度从 >0 变为 =0 时，记录停车位置
                if (lastRecordedSpeed > 0 && currentSpeed == 0.0) {
                    val lat = core.carrotManFields.value.latitude
                    val lon = core.carrotManFields.value.longitude
                    if (lat != 0.0 && lon != 0.0) {
                        com.example.navipilot.utils.CoordinatePreferences.saveParkedLocation(prefs, lat, lon)
                        Log.i("MainActivityUI", "🚗 已记录停车位置: $lat, $lon (数据源: ${if (isCommaConnected) "comma3" else "GPS"})")
                    }
                }
                lastRecordedSpeed = currentSpeed.toFloat()
            }

            // 不使用 Scaffold 的 bottomBar，改为手动叠加，让导航栏浮在内容上方
            Box(modifier = Modifier.fillMaxSize()) {
                // 拦截返回键：正常返回
                BackHandler(enabled = true) {
                    if (core.currentPage != 0) {
                        core.currentPage = 0
                    }
                    // 主页时什么都不做，防止退出应用
                }

                // 导航模式状态：这里展示和选择的是用户偏好的导航地图。
                var navMode by remember { mutableStateOf(NavMode.AMAP_AUTO) }

                LaunchedEffect(core.userSelectedMode) {
                    if (core.userSelectedMode == "BAIDU") {
                        core.userSelectedMode = "AMAP"
                        core.persistUserSelectedNavMode()
                    }
                    // 地图源下拉已不提供 OSM，旧偏好统一为车机高德
                    if (core.userSelectedMode == "OSM") {
                        core.userSelectedMode = "AMAP"
                        core.persistUserSelectedNavMode()
                    }
                    navMode = when (core.userSelectedMode) {
                        "TENCENT" -> NavMode.TMAP
                        "AMAP" -> NavMode.AMAP_AUTO
                        "AMAP_MOBILE" -> NavMode.AMAP_MOBILE
                        else -> NavMode.AMAP_AUTO
                    }
                }

                // 地图源选择处理：这里只更新偏好，不立即跳转或拉起地图
                fun handleModeChange(mode: NavMode) {
                    core.userSelectedMode = when (mode) {
                        NavMode.TMAP -> "TENCENT"
                        NavMode.AMAP_AUTO -> "AMAP"
                        NavMode.AMAP_MOBILE -> "AMAP_MOBILE"
                        NavMode.OSM -> "AMAP" // 下拉已移除 OSM，兜底归一车机高德
                        NavMode.GOOGLE -> "GOOGLE"
                    }
                    core.persistUserSelectedNavMode()
                    navMode = mode
                }

                // 主内容区域（占满全屏）
                Box(modifier = Modifier.fillMaxSize()) {
                    // 根据当前页面显示不同内容
                    when (core.currentPage) {
                        0 -> HomePage(
                            userType = core.userType.value,
                            carrotManFields = core.carrotManFields.value,
                            xiaogeTcpConnected = core.xiaogeTcpConnected.value,
                            xiaogeDataTimeout = core.xiaogeDataTimeout.value,
                            onSendCommand = { command, arg -> core.sendCarrotCommand(command, arg) },
                            onSendRoadLimitSpeed = { core.sendCurrentRoadLimitSpeed() },
                            onLaunchAmap = { core.launchAmapAuto() },
                            onSendNavConfirmation = { core.sendNavigationConfirmationManually() },
                            onPageChange = { page ->
                                core.currentPage = page
                            }, // 传递页面切换回调
                            navMode = navMode,
                            onModeChange = { mode -> handleModeChange(mode) }
                        )
                        1 -> HelpPage(
                            deviceIP = core.networkManager.getCurrentDeviceIP()
                        )
                        2 -> ProfilePage(
                            deviceId = core.deviceId.value
                        )
                        4 -> AutoSwitchExperimentPage(
                            onBack = {
                                core.currentPage = 0
                            },
                            conditionalExperimentManager = core.getConditionalExperimentManagerSafely(),
                            carrotParamClient = core.getCarrotParamClientSafely(),
                            carrotManFields = core.carrotManFields
                        )
                        7 -> DrivingReportScreen(
                            onBack = {
                                core.currentPage = 0
                            },
                            drivingDataCollector = core.getDrivingDataCollectorSafely()
                        )
                        11 -> { /* SSH 已移除 */ }
                        13 -> {
                            val prefs = appContext.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                            val downloadManager = ModelDownloadManager.getInstance(appContext, prefs)
                            val sshManager = remember { SshConnectionManager(appContext) }
                            ModelSwitcherPage(
                                onBack = {
                                    core.currentPage = 0
                                },
                                downloadManager = downloadManager,
                                sshManager = sshManager,
                                discoveredDeviceIp = null
                            )
                        }
                    }

                    // 腾讯导航已嵌入 HomePage 地图槽；若仍有代码将 currentPage 设为 12，拉回主页避免空白
                    LaunchedEffect(core.currentPage) {
                        if (core.currentPage == 12) {
                            core.currentPage = 0
                        }
                    }
                    
                    // 功能说明弹窗 (用户类型3/4不弹窗，15秒自动关闭)
                    var showFeatureDialog by remember { mutableStateOf(false) }
                    var hasShownDialog by remember { mutableStateOf(false) }
                    val currentUserType = core.userType.value
                    
                    LaunchedEffect(currentUserType) {
                        if (!hasShownDialog && currentUserType != 0) {
                            // userType 已从网络加载（非默认值0），执行判断
                            hasShownDialog = true
                            if (currentUserType != 3 && currentUserType != 4) {
                                showFeatureDialog = true
                                delay(15000)
                                if (showFeatureDialog) {
                                    showFeatureDialog = false
                                }
                            }
                        }
                    }
                    
                    if (showFeatureDialog) {
                        AppFeatureDialog(
                            onDismiss = { showFeatureDialog = false }
                        )
                    }
                }

            }
        }
    }

    /**
     * 主页组件
     */
    @Composable
    private fun HomePage(
        userType: Int,
        carrotManFields: CarrotManFields,
        xiaogeTcpConnected: Boolean,
        xiaogeDataTimeout: Boolean,
        onSendCommand: (String, String) -> Unit,
        onSendRoadLimitSpeed: () -> Unit,
        onLaunchAmap: () -> Unit,
        onSendNavConfirmation: () -> Unit,
        onPageChange: (Int) -> Unit, // 页面切换回调
        navMode: NavMode,             // 当前导航模式
        onModeChange: (NavMode) -> Unit // 切换导航模式回调
    ) {
        val scrollState = rememberScrollState()
        val data by core.xiaogeData
        // 视频展开/折叠状态（默认隐藏，用户点击摄像机图标才显示）
        var isVideoExpanded by remember { mutableStateOf(false) }
        // 数据卡片展开/折叠状态
        var isDataCardExpanded by remember { mutableStateOf(true) }
        // 高阶功能对话框状态
        var showAdvancedDialog by remember { mutableStateOf(false) }
        // 点击首页 LED 预览条：全屏 7706 JSON 调试
        var show7706JsonDebug by remember { mutableStateOf(false) }
        val carrotFieldsLive by core.carrotManFields
        val mapContext = LocalContext.current

        // ===== 面板显示用状态（由 OsmMapView 回调更新）=====
        var isLedConnected by remember { mutableStateOf(false) }
        var homeAddressSet by remember { mutableStateOf(false) }
        var companyAddressSet by remember { mutableStateOf(false) }

        // LED 实时预览状态
        val ledManager = remember { com.example.navipilot.ui.components.LedMatrixManager.getInstance(mapContext) }
        val ledDisplayState by ledManager.currentDisplayState.collectAsState()

        // ===== 动作触发器（面板按钮点击时递增，OsmMapView 监听执行内部逻辑）=====
        var searchShowTrigger by remember { mutableIntStateOf(0) }
        var ledMatrixTrigger by remember { mutableIntStateOf(0) }
        var homeNavTrigger by remember { mutableIntStateOf(0) }
        var homeNavLongTrigger by remember { mutableIntStateOf(0) }
        var companyNavTrigger by remember { mutableIntStateOf(0) }
        var companyNavLongTrigger by remember { mutableIntStateOf(0) }
        
        // 定时更新地图服务类型（每5秒检查一次）
        LaunchedEffect(Unit) {
            while (true) {
                core.updateMapServiceType()
                kotlinx.coroutines.delay(5000)
            }
        }

        // ===== 竖屏 / 横屏检测 =====
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.screenWidthDp <= configuration.screenHeightDp

        // ===== 读取停车位置（用于找车功能）=====
        val prefs = remember { mapContext.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) }
        val parkedLoc = remember { CoordinatePreferences.getParkedLocation(prefs) }
        val parkedLocPair = parkedLoc?.let { Pair(it.latitude, it.longitude) }

        // 步行导航到停车位置
        fun navigateToParkedCar() {
            val parked = parkedLoc
            if (parked != null) {
                val currentLat = carrotManFields.latitude
                val currentLon = carrotManFields.longitude
                if (currentLat != 0.0 && currentLon != 0.0) {
                    MainActivityUIComponents.startWalkingNavigationToParked(
                        mapContext, prefs, currentLat, currentLon, parked.latitude, parked.longitude
                    )
                }
            }
        }

        // ===== 地图相关状态 =====
        val mapService = core.userSelectedMode
        val gpsAccuracy = carrotManFields.gps_accuracy_phone
        val positionMode = when {
            gpsAccuracy < 3.0 -> "GPS"
            gpsAccuracy < 10.0 -> "GPS"
            else -> "GPS"
        }

        // 腾讯算路起点与 OsmMapView 一致：优先 WGS84 的 latitude/longitude，否则用 X 系列车位（避免 0,0 起点导致「起终点参数错误」）
        val tencentRouteStartLat = when {
            carrotManFields.latitude != 0.0 && carrotManFields.longitude != 0.0 -> carrotManFields.latitude
            carrotManFields.xPosLat != 0.0 && carrotManFields.xPosLon != 0.0 -> carrotManFields.xPosLat
            else -> 0.0
        }
        val tencentRouteStartLon = when {
            carrotManFields.latitude != 0.0 && carrotManFields.longitude != 0.0 -> carrotManFields.longitude
            carrotManFields.xPosLat != 0.0 && carrotManFields.xPosLon != 0.0 -> carrotManFields.xPosLon
            else -> 0.0
        }

        // 地图区 Composable lambda（复用于竖屏/横屏两种布局）
        val mapZoneContent: @Composable () -> Unit = {
            val isNavActive = carrotManFields.isNavigating
            when {
                // 导航中：根据用户选择的模式显示对应嵌入式导航
                isNavActive -> {
                    when (mapService) {
                        "TENCENT" -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                TencentNavPage(
                                    carrotManFieldsState = core.carrotManFields,
                                    goalLat = core.carrotManFields.value.goalPosY,
                                    goalLon = core.carrotManFields.value.goalPosX,
                                    goalName = core.carrotManFields.value.szGoalName,
                                    currentLat = tencentRouteStartLat,
                                    currentLon = tencentRouteStartLon,
                                    networkClient = core.networkManager.getNetworkClient(),
                                    deviceIP = core.networkManager.getCurrentDeviceIP(),
                                    onEnterTencentMode = { core.switchToTencentMode() },
                                    onExitTencentMode = { core.exitTencentMode() },
                                    onBack = {
                                        core.exitTencentMode()
                                        // 切换回默认地图服务
                                        core.userSelectedMode = "AMAP"
                                        core.persistUserSelectedNavMode()
                                    }
                                )
                            }
                        }
                        "AMAP_MOBILE" -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AmapMobileNavPage(
                                    carrotManFieldsState = core.carrotManFields,
                                    goalLat = core.carrotManFields.value.goalPosY,
                                    goalLon = core.carrotManFields.value.goalPosX,
                                    goalName = core.carrotManFields.value.szGoalName,
                                    currentLat = tencentRouteStartLat,
                                    currentLon = tencentRouteStartLon,
                                    onEnterAmapMobileMode = { core.switchToAmapMobileMode() },
                                    onExitAmapMobileMode = { core.exitAmapMobileMode() },
                                    onBack = {
                                        core.exitAmapMobileMode()
                                        // 切换回默认地图服务
                                        core.userSelectedMode = "AMAP"
                                        core.persistUserSelectedNavMode()
                                    }
                                )
                            }
                        }
                        "GOOGLE" -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                GoogleNavPage(
                                    carrotManFieldsState = core.carrotManFields,
                                    goalLat = core.carrotManFields.value.goalPosY,
                                    goalLon = core.carrotManFields.value.goalPosX,
                                    goalName = core.carrotManFields.value.szGoalName,
                                    currentLat = tencentRouteStartLat,
                                    currentLon = tencentRouteStartLon,
                                    networkClient = core.getNetworkClientSafely(),
                                    onEnterGoogleMode = { core.switchToGoogleMode() },
                                    onExitGoogleMode = { core.exitGoogleMode() },
                                    onBack = {
                                        core.exitGoogleMode()
                                        // 切换回默认地图服务
                                        core.userSelectedMode = "AMAP"
                                        core.persistUserSelectedNavMode()
                                    }
                                )
                            }
                        }
                        else -> OsmMapView(
                            latitude = carrotManFields.xPosLat,
                            longitude = carrotManFields.xPosLon,
                            bearing = carrotManFields.xPosAngle,
                            speedKmh = carrotManFields.vEgoKph.toDouble(),
                            isNavigating = carrotManFields.isNavigating,
                            goalLon = carrotManFields.goalPosX,
                            goalLat = carrotManFields.goalPosY,
                            goalName = carrotManFields.szGoalName,
                            remainDist = carrotManFields.nGoPosDist,
                            remainTime = carrotManFields.nGoPosTime,
                            nextTurnDist = carrotManFields.nTBTDist,
                            nextTurnType = carrotManFields.nTBTTurnType,
                            nextTurnText = carrotManFields.szTBTMainText,
                            laneInfoList = carrotManFields.laneInfoList,
                            trafficState = carrotManFields.traffic_state,
                            leftSec = carrotManFields.left_sec,
                            trafficLightDirection = carrotManFields.traffic_light_direction,
                            isVideoExpanded = isVideoExpanded,
                            onToggleVideo = { isVideoExpanded = !isVideoExpanded },
                            isDataCardExpanded = isDataCardExpanded,
                            onToggleDataCard = { isDataCardExpanded = true },
                            onPageChange = { page ->
                                core.currentPage = page
                            },
                            onOpenTencentEmbeddedNav = {
                                core.userSelectedMode = "TENCENT"
                                core.persistUserSelectedNavMode()
                            },
                            onOpenAmapMobileEmbeddedNav = {
                                core.userSelectedMode = "AMAP_MOBILE"
                                core.persistUserSelectedNavMode()
                            },
                            onOpenGoogleEmbeddedNav = {
                                core.userSelectedMode = "GOOGLE"
                                core.persistUserSelectedNavMode()
                            },
                            cruiseSetSpeed = try { carrotManFields.vCruiseKph.toInt() } catch (_: Exception) { 0 },
                            carCruiseSpeed = try { carrotManFields.carcruiseSpeed.toInt() } catch (_: Exception) { 0 },
                            onBlueRingClick = {
                                MainActivityUIComponents.startSimulatedNavigation(mapContext, carrotManFields)
                            },
                            onGreenRingClick = onLaunchAmap,
                            onHomeNavClick = {
                                MainActivityUIComponents.sendHomeNavigationToAmap(mapContext)
                            },
                            onCompanyNavClick = {
                                MainActivityUIComponents.sendCompanyNavigationToAmap(mapContext)
                            },
                            userType = userType,
                            onShowAdvancedDialog = { showAdvancedDialog = true },
                            isAutopilotActive = carrotManFields.active,
                            mapServiceType = mapService,
                            networkClient = core.getNetworkClientSafely(),
                            carrotManFieldsState = core.carrotManFields,
                            activeNavMode = core.activeNavMode,
                            xiaogeData = data,
                            gpsAccuracy = gpsAccuracy.toFloat(),
                            positionMode = positionMode,
                            parkedLocation = parkedLocPair,
                            onNavigateToParked = { navigateToParkedCar() },
                            commaConnectionState = core.getNetworkClientSafely()?.let { client ->
                                when {
                                    core.xiaogeDataTimeout.value -> 2
                                    client.isRunning() && client.getCurrentDevice() != null -> 1
                                    else -> 0
                                }
                            } ?: 0,
                            searchShowTrigger = searchShowTrigger,
                            ledMatrixTrigger = ledMatrixTrigger,
                            homeNavTrigger = homeNavTrigger,
                            homeNavLongTrigger = homeNavLongTrigger,
                            companyNavTrigger = companyNavTrigger,
                            companyNavLongTrigger = companyNavLongTrigger,
                            onLedConnectionChange = { isLedConnected = it },
                            onHomeAddressChange = { homeAddressSet = it },
                            onCompanyAddressChange = { companyAddressSet = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                // 非导航中：统一显示 OSM 地图
                else -> OsmMapView(
                    latitude = carrotManFields.xPosLat,
                    longitude = carrotManFields.xPosLon,
                    bearing = carrotManFields.xPosAngle,
                    speedKmh = carrotManFields.vEgoKph.toDouble(),
                    isNavigating = carrotManFields.isNavigating,
                    goalLon = carrotManFields.goalPosX,
                    goalLat = carrotManFields.goalPosY,
                    goalName = carrotManFields.szGoalName,
                    remainDist = carrotManFields.nGoPosDist,
                    remainTime = carrotManFields.nGoPosTime,
                    nextTurnDist = carrotManFields.nTBTDist,
                    nextTurnType = carrotManFields.nTBTTurnType,
                    nextTurnText = carrotManFields.szTBTMainText,
                    laneInfoList = carrotManFields.laneInfoList,
                    trafficState = carrotManFields.traffic_state,
                    leftSec = carrotManFields.left_sec,
                    trafficLightDirection = carrotManFields.traffic_light_direction,
                    isVideoExpanded = isVideoExpanded,
                    onToggleVideo = { isVideoExpanded = !isVideoExpanded },
                    isDataCardExpanded = isDataCardExpanded,
                    onToggleDataCard = { isDataCardExpanded = true },
                    onPageChange = { page ->
                        core.currentPage = page
                    },
                    onOpenTencentEmbeddedNav = {
                        core.userSelectedMode = "TENCENT"
                        core.persistUserSelectedNavMode()
                    },
                    onOpenAmapMobileEmbeddedNav = {
                        core.userSelectedMode = "AMAP_MOBILE"
                        core.persistUserSelectedNavMode()
                    },
                    onOpenGoogleEmbeddedNav = {
                        core.userSelectedMode = "GOOGLE"
                        core.persistUserSelectedNavMode()
                    },
                    cruiseSetSpeed = try { carrotManFields.vCruiseKph.toInt() } catch (_: Exception) { 0 },
                    carCruiseSpeed = try { carrotManFields.carcruiseSpeed.toInt() } catch (_: Exception) { 0 },
                    onBlueRingClick = {
                        MainActivityUIComponents.startSimulatedNavigation(mapContext, carrotManFields)
                    },
                    onGreenRingClick = onLaunchAmap,
                    onHomeNavClick = {
                        MainActivityUIComponents.sendHomeNavigationToAmap(mapContext)
                    },
                    onCompanyNavClick = {
                        MainActivityUIComponents.sendCompanyNavigationToAmap(mapContext)
                    },
                    userType = userType,
                    onShowAdvancedDialog = { showAdvancedDialog = true },
                    isAutopilotActive = carrotManFields.active,
                    mapServiceType = mapService,
                    networkClient = core.getNetworkClientSafely(),
                    carrotManFieldsState = core.carrotManFields,
                    activeNavMode = core.activeNavMode,
                    xiaogeData = data,
                    gpsAccuracy = gpsAccuracy.toFloat(),
                    positionMode = positionMode,
                    parkedLocation = parkedLocPair,
                    onNavigateToParked = { navigateToParkedCar() },
                    commaConnectionState = core.getNetworkClientSafely()?.let { client ->
                        when {
                            core.xiaogeDataTimeout.value -> 2
                            client.isRunning() && client.getCurrentDevice() != null -> 1
                            else -> 0
                        }
                    } ?: 0,
                    searchShowTrigger = searchShowTrigger,
                    ledMatrixTrigger = ledMatrixTrigger,
                    homeNavTrigger = homeNavTrigger,
                    homeNavLongTrigger = homeNavLongTrigger,
                    companyNavTrigger = companyNavTrigger,
                    companyNavLongTrigger = companyNavLongTrigger,
                    onLedConnectionChange = { isLedConnected = it },
                    onHomeAddressChange = { homeAddressSet = it },
                    onCompanyAddressChange = { companyAddressSet = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ===== 响应式主布局 =====
        if (isPortrait) {
            // 竖屏：上方 7/9 地图，下方 2/9 功能面板
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(7f)
                ) { mapZoneContent() }
                HomeControlPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f),
                    navMode = navMode,
                    onModeChange = onModeChange,
                    carrotManFields = carrotManFields,
                    isPortrait = true,
                    userType = userType,
                    cruiseSetSpeed = try { carrotManFields.vCruiseKph.toInt() } catch (_: Exception) { 0 },
                    carCruiseSpeed = try { carrotManFields.carcruiseSpeed.toInt() } catch (_: Exception) { 0 },
                    carrotParamClient = core.getCarrotParamClientSafely(),
                    isLedConnected = isLedConnected,
                    ledDisplayText = ledDisplayState.text,
                    ledDisplayColor = ledDisplayState.color,
                    ledAnimCode = ledDisplayState.animCode,
                    ledDisplayBitmapData = ledDisplayState.bitmapData,
                    homeAddressSet = homeAddressSet,
                    companyAddressSet = companyAddressSet,
                    onShowAdvancedDialog = { showAdvancedDialog = true },
                    onPageChange = onPageChange,
                    onSearchClick = { searchShowTrigger++ },
                    onLedMatrixClick = { ledMatrixTrigger++ },
                    onLedPreviewClick = { show7706JsonDebug = true },
                    onHomeNavClick = { homeNavTrigger++ },
                    onHomeNavLongClick = { homeNavLongTrigger++ },
                    onCompanyNavClick = { companyNavTrigger++ },
                    onCompanyNavLongClick = { companyNavLongTrigger++ }
                )
            }
        } else {
            // 横屏：左侧 7/9 地图，右侧 2/9 功能面板
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(7f)
                ) { mapZoneContent() }
                HomeControlPanel(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(2f),
                    navMode = navMode,
                    onModeChange = onModeChange,
                    carrotManFields = carrotManFields,
                    isPortrait = false,
                    userType = userType,
                    cruiseSetSpeed = try { carrotManFields.vCruiseKph.toInt() } catch (_: Exception) { 0 },
                    carCruiseSpeed = try { carrotManFields.carcruiseSpeed.toInt() } catch (_: Exception) { 0 },
                    carrotParamClient = core.getCarrotParamClientSafely(),
                    isLedConnected = isLedConnected,
                    ledDisplayText = ledDisplayState.text,
                    ledDisplayColor = ledDisplayState.color,
                    ledAnimCode = ledDisplayState.animCode,
                    ledDisplayBitmapData = ledDisplayState.bitmapData,
                    homeAddressSet = homeAddressSet,
                    companyAddressSet = companyAddressSet,
                    onShowAdvancedDialog = { showAdvancedDialog = true },
                    onPageChange = onPageChange,
                    onSearchClick = { searchShowTrigger++ },
                    onLedMatrixClick = { ledMatrixTrigger++ },
                    onLedPreviewClick = { show7706JsonDebug = true },
                    onHomeNavClick = { homeNavTrigger++ },
                    onHomeNavLongClick = { homeNavLongTrigger++ },
                    onCompanyNavClick = { companyNavTrigger++ },
                    onCompanyNavLongClick = { companyNavLongTrigger++ }
                )
            }
        }

        // 高阶功能对话框（Dialog 会自动叠加在界面最上层）
        if (showAdvancedDialog) {
            MainActivityUIComponents.AdvancedFunctionsDialog(
                onDismiss = { showAdvancedDialog = false },
                onSendCommand = onSendCommand,
                onSendRoadLimitSpeed = onSendRoadLimitSpeed,
                onLaunchAmap = onLaunchAmap,
                onSendNavConfirmation = onSendNavConfirmation,
                onPageChange = onPageChange,
                isOpenpilotActive = carrotManFields.active,
                carrotManFields = carrotManFields,
                networkManager = core.networkManager,
                context = mapContext
            )
        }
        if (show7706JsonDebug) {
            Carrot7706JsonDebugOverlay(
                fields = carrotFieldsLive,
                networkClient = core.getNetworkClientSafely(),
                onDismiss = { show7706JsonDebug = false },
            )
        }
    }

    /** 首页控制台：仅圆形图标（无障碍用语见 contentDescription，无底部文字） */
    @Composable
    private fun HomeControlPanelCircleIcon(
        modifier: Modifier = Modifier,
        background: Color,
        icon: ImageVector,
        contentDescription: String,
        iconTint: Color = Color.White,
        onClick: () -> Unit
    ) {
        val boxDp = 42.dp
        val iconDp = 21.dp
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(boxDp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription, Modifier.size(iconDp), tint = iconTint)
            }
        }
    }

    /** 家/公司：仅 emoji 圆形按钮；短按导航、长按清除地址（无障碍用语见 contentDescription） */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HomeControlPanelEmojiAddress(
        modifier: Modifier = Modifier,
        emoji: String,
        accessibilityLabel: String,
        addressSet: Boolean,
        onShortClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        val bg = if (addressSet) Color(0xFF1E293B).copy(alpha = 0.75f) else Color(0xFF334155).copy(alpha = 0.85f)
        val boxDp = 42.dp
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(boxDp)
                    .semantics { contentDescription = accessibilityLabel }
                    .clip(CircleShape)
                    .background(bg)
                    .combinedClickable(onClick = onShortClick, onLongClick = onLongClick),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 17.sp)
            }
        }
    }

    /** 速度环（无底部文字；modifier 可传 weight(1f) 等分） */
    @Composable
    private fun HomePanelSpeedRing(
        modifier: Modifier = Modifier,
        value: Int,
        color: Color,
        onClick: () -> Unit
    ) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            com.example.navipilot.ui.components.SpeedRingButton(
                value = value,
                color = color,
                onClick = onClick,
                diameter = 40.dp,
                valueTextSize = 10.sp
            )
        }
    }

    /** 根据当前选中的导航源显示不同图标（Material 无厂商 Logo，用语义区分车机/手机/腾讯/OSM） */
    private fun mapSourceButtonIcon(navMode: NavMode): ImageVector = when (navMode) {
        NavMode.AMAP_AUTO -> Icons.Default.DirectionsCar   // 高德车机版
        NavMode.TMAP -> Icons.Default.Navigation             // 腾讯导航
        NavMode.AMAP_MOBILE -> Icons.Default.Smartphone      // 高德手机版嵌入
        NavMode.OSM -> Icons.Default.Map                     // 应用内 OSM
        NavMode.GOOGLE -> Icons.Default.Explore          // Google 导航
    }

    /** 地图源圆形按钮（无底部文字；当前导航源在弹窗中选择） */
    @Composable
    private fun HomePanelMapSource(
        modifier: Modifier = Modifier,
        navMode: NavMode,
        onClick: () -> Unit
    ) {
        val mapDesc = localized("选择地图导航", "Choose map provider") + " (" + when (navMode) {
            NavMode.AMAP_AUTO -> localized("高德", "AMap")
            NavMode.TMAP -> localized("腾讯", "Tencent")
            NavMode.AMAP_MOBILE -> localized("高德手机", "AMap Mobile")
            NavMode.OSM -> localized("OSM", "OSM")
            NavMode.GOOGLE -> localized("Google", "Google")
        } + ")"
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF334155).copy(alpha = 0.9f))
                    .clickable { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = mapSourceButtonIcon(navMode),
                    contentDescription = mapDesc,
                    modifier = Modifier.size(21.dp),
                    tint = Color(0xFF93C5FD)
                )
            }
        }
    }

    /** 地图导航源选择弹窗：车机版优先，其次腾讯/高德手机版与谷歌 */
    @Composable
    private fun MapNavModePickerDialog(
        currentMode: NavMode,
        onDismiss: () -> Unit,
        onSelect: (NavMode) -> Unit
    ) {
        data class RowDef(
            val mode: NavMode,
            val title: String,
            val subtitle: String
        )
        val rows = buildList {
            add(
                RowDef(
                    NavMode.AMAP_AUTO,
                    localized("高德车机版", "Amap head unit"),
                    localized("与车机版广播联动（推荐）", "Vehicle broadcast integration (recommended)")
                )
            )
            add(
                RowDef(
                    NavMode.TMAP,
                    localized("腾讯导航", "Tencent navigation"),
                    localized("腾讯地图车联", "Tencent Maps integration")
                )
            )
            add(
                RowDef(
                    NavMode.AMAP_MOBILE,
                    localized("高德手机版", "Amap Mobile"),
                    localized("高德地图手机版导航", "AMap mobile navigation")
                )
            )
            add(
                RowDef(
                    NavMode.GOOGLE,
                    localized("Google 导航", "Google Navigation"),
                    localized("Google 地图官方导航", "Google Maps navigation")
                )
            )
        }
        val defaultMode = NavMode.AMAP_AUTO
        val hasValidSelection = rows.any { it.mode == currentMode }
        val effectiveMode = if (hasValidSelection) currentMode else defaultMode

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .widthIn(max = 300.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = localized("选择地图导航", "Choose navigation map"),
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 2,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = localized("关闭", "Close"),
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 四个导航源按钮，选中项绿色高亮
                    rows.forEach { row ->
                        val isSelected = row.mode == effectiveMode
                        val bgColor = if (isSelected) Color(0xFF22C55E).copy(alpha = 0.15f) else Color(0xFFF1F5F9)
                        val borderColor = if (isSelected) Color(0xFF22C55E) else Color(0xFFE2E8F0)
                        val titleColor = if (isSelected) Color(0xFF166534) else Color(0xFF1E293B)
                        val subtitleColor = if (isSelected) Color(0xFF22C55E) else Color(0xFF64748B)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                .background(bgColor)
                                .clickable { onSelect(row.mode) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.title,
                                    color = titleColor,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = row.subtitle,
                                    color = subtitleColor,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = localized("已选择", "Selected"),
                                    tint = Color(0xFF22C55E),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 首页功能控制面板（竖屏下方 2/9，横屏右侧 2/9）
     * 上方为模拟 LED 预览，其下为图标网格：竖屏 4+4（已移除原第5位账户、第8位找车）、横屏 3+3+2
     */
    @Composable
    private fun HomeControlPanel(
        modifier: Modifier = Modifier,
        navMode: NavMode,
        onModeChange: (NavMode) -> Unit,
        carrotManFields: CarrotManFields,
        isPortrait: Boolean,
        userType: Int,
        cruiseSetSpeed: Int,
        carCruiseSpeed: Int,
        carrotParamClient: CarrotParamClient?,
        isLedConnected: Boolean,
        ledDisplayText: String,
        ledDisplayColor: Color,
        ledAnimCode: Int,
        ledDisplayBitmapData: List<ByteArray>,
        homeAddressSet: Boolean,
        companyAddressSet: Boolean,
        onShowAdvancedDialog: () -> Unit,
        onPageChange: (Int) -> Unit,
        onSearchClick: () -> Unit,
        onLedMatrixClick: () -> Unit,
        onLedPreviewClick: () -> Unit,
        onHomeNavClick: () -> Unit,
        onHomeNavLongClick: () -> Unit,
        onCompanyNavClick: () -> Unit,
        onCompanyNavLongClick: () -> Unit
    ) {
        val panelContext = LocalContext.current
        val scrollState = rememberScrollState()
        var showMapModeDialog by remember { mutableStateOf(false) }

        // 解析设备端 ExperimentalMode 参数（与旧 SecondarySection 一致）
        fun parseExperimentalMode(value: Any?): Boolean? {
            return when (value) {
                is Boolean -> value
                is Int -> value != 0
                is Long -> value != 0L
                is Double -> value != 0.0
                is Float -> value != 0f
                is String -> value == "1" || value.equals("true", ignoreCase = true)
                else -> null
            }
        }

        val coroutineScope = rememberCoroutineScope()
        val tileBg = Color(0xFF1E293B).copy(alpha = 0.72f)
        val ledBg = if (isLedConnected) Color(0xFF10B981).copy(alpha = 0.9f) else tileBg
        var isExperimentalMode by remember(carrotParamClient) { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(carrotParamClient) {
            if (carrotParamClient == null) {
                isExperimentalMode = null
                return@LaunchedEffect
            }
            val result = carrotParamClient.getParams("ExperimentalMode")
            isExperimentalMode = result.getOrNull()?.get("ExperimentalMode")?.let(::parseExperimentalMode)
        }

        val experimentBg = when (isExperimentalMode) {
            true -> Color(0xFF8B5CF6).copy(alpha = 0.92f)
            false -> Color(0xFF06B6D4).copy(alpha = 0.92f)
            null -> tileBg
        }

        val onCruiseSetClick: () -> Unit = {
            if (userType == 3 || userType == 4 || userType == 0) onShowAdvancedDialog()
            else android.widget.Toast.makeText(
                panelContext,
                "⭐ 高阶功能需要赞助者权限",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val onExperimentClick: () -> Unit = {
            if (carrotParamClient == null) {
                android.widget.Toast.makeText(
                    panelContext,
                    localized("设备未连接", "Device not connected"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                coroutineScope.launch {
                    val currentMode = isExperimentalMode ?: carrotParamClient
                        .getParams("ExperimentalMode")
                        .getOrNull()
                        ?.get("ExperimentalMode")
                        ?.let(::parseExperimentalMode)
                        ?: false
                    val targetMode = !currentMode
                    val result = carrotParamClient.setExperimentalMode(targetMode)
                    if (result.isSuccess) {
                        isExperimentalMode = targetMode
                        android.widget.Toast.makeText(
                            panelContext,
                            if (targetMode) localized("已切换实验模式", "Exp Mode ON")
                            else localized("已切换Chill模式", "Chill Mode ON"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            panelContext,
                            result.exceptionOrNull()?.message
                                ?: localized("切换失败", "Switch failed"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        if (showMapModeDialog) {
            MapNavModePickerDialog(
                currentMode = navMode,
                onDismiss = { showMapModeDialog = false },
                onSelect = { mode ->
                    onModeChange(mode)
                    showMapModeDialog = false
                }
            )
        }

        Box(
            modifier = modifier.background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        horizontal = if (isPortrait) 10.dp else 6.dp,
                        vertical = if (isPortrait) 10.dp else 10.dp
                    ),
                horizontalAlignment = if (isPortrait) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isPortrait) 10.dp else 12.dp)
            ) {
                // 模拟 LED 预览在按钮网格上方
                val hasRealtimeLedText = ledDisplayText.isNotBlank()
                LedMatrixPreview(
                    text = if (hasRealtimeLedText) ledDisplayText else "机械小鸽",
                    color = if (hasRealtimeLedText) ledDisplayColor else Color(0xFFFF6B35),
                    animCode = if (hasRealtimeLedText) ledAnimCode else 0,
                    bitmapData = if (hasRealtimeLedText) ledDisplayBitmapData else emptyList(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onLedPreviewClick,
                )
                if (isPortrait) {
                    // 竖屏：第一行 4 格（蓝速/地图/绿速/搜索），第二行 4 格（家/公司/实验/LED）；已移除第5位账户与第8位找车
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomePanelSpeedRing(
                            modifier = Modifier.weight(1f),
                            value = cruiseSetSpeed,
                            color = Color(0xFF2196F3),
                            onClick = onCruiseSetClick
                        )
                        HomePanelMapSource(
                            modifier = Modifier.weight(1f),
                            navMode = navMode,
                            onClick = { showMapModeDialog = true }
                        )
                        HomePanelSpeedRing(
                            modifier = Modifier.weight(1f),
                            value = carCruiseSpeed,
                            color = Color(0xFF22C55E),
                            onClick = { onPageChange(2) }
                        )
                        HomeControlPanelCircleIcon(
                            modifier = Modifier.weight(1f),
                            background = tileBg,
                            icon = Icons.Default.Search,
                            contentDescription = localized("搜索地点", "Search"),
                            onClick = onSearchClick
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeControlPanelEmojiAddress(
                            modifier = Modifier.weight(1f),
                            emoji = "🏠",
                            accessibilityLabel = localized("家", "Home"),
                            addressSet = homeAddressSet,
                            onShortClick = onHomeNavClick,
                            onLongClick = onHomeNavLongClick
                        )
                        HomeControlPanelEmojiAddress(
                            modifier = Modifier.weight(1f),
                            emoji = "🏢",
                            accessibilityLabel = localized("公司", "Work"),
                            addressSet = companyAddressSet,
                            onShortClick = onCompanyNavClick,
                            onLongClick = onCompanyNavLongClick
                        )
                        HomeControlPanelCircleIcon(
                            modifier = Modifier.weight(1f),
                            background = Color(0xFF10B981).copy(alpha = 0.92f),
                            icon = Icons.Default.SwapHoriz,
                            contentDescription = localized("模型切换器", "Model Switcher"),
                            onClick = { onPageChange(13) }
                        )
                        HomeControlPanelCircleIcon(
                            modifier = Modifier.weight(1f),
                            background = ledBg,
                            icon = Icons.Default.Bluetooth,
                            contentDescription = localized("LED点阵屏", "LED Matrix"),
                            iconTint = if (isLedConnected) Color.White else Color(0xFF94A3B8),
                            onClick = onLedMatrixClick
                        )
                    }
                } else {
                    // 横屏：3 + 3 + 2 网格（末行已移除账户，仅实验/LED）
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 288.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                HomePanelSpeedRing(
                                    modifier = Modifier.weight(1f),
                                    value = cruiseSetSpeed,
                                    color = Color(0xFF2196F3),
                                    onClick = onCruiseSetClick
                                )
                                HomePanelMapSource(
                                    modifier = Modifier.weight(1f),
                                    navMode = navMode,
                                    onClick = { showMapModeDialog = true }
                                )
                                HomePanelSpeedRing(
                                    modifier = Modifier.weight(1f),
                                    value = carCruiseSpeed,
                                    color = Color(0xFF22C55E),
                                    onClick = { onPageChange(2) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                HomeControlPanelEmojiAddress(
                                    modifier = Modifier.weight(1f),
                                    emoji = "🏠",
                                    accessibilityLabel = localized("家", "Home"),
                                    addressSet = homeAddressSet,
                                    onShortClick = onHomeNavClick,
                                    onLongClick = onHomeNavLongClick
                                )
                                HomeControlPanelCircleIcon(
                                    modifier = Modifier.weight(1f),
                                    background = tileBg,
                                    icon = Icons.Default.Search,
                                    contentDescription = localized("搜索地点", "Search"),
                                    onClick = onSearchClick
                                )
                                HomeControlPanelEmojiAddress(
                                    modifier = Modifier.weight(1f),
                                    emoji = "🏢",
                                    accessibilityLabel = localized("公司", "Work"),
                                    addressSet = companyAddressSet,
                                    onShortClick = onCompanyNavClick,
                                    onLongClick = onCompanyNavLongClick
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                HomeControlPanelCircleIcon(
                                    modifier = Modifier.weight(1f),
                                    background = Color(0xFF10B981).copy(alpha = 0.92f),
                                    icon = Icons.Default.SwapHoriz,
                                    contentDescription = localized("模型切换器", "Model Switcher"),
                                    onClick = { onPageChange(13) }
                                )
                                HomeControlPanelCircleIcon(
                                    modifier = Modifier.weight(1f),
                                    background = ledBg,
                                    icon = Icons.Default.Bluetooth,
                                    contentDescription = localized("LED点阵屏", "LED Matrix"),
                                    iconTint = if (isLedConnected) Color.White else Color(0xFF94A3B8),
                                    onClick = onLedMatrixClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 状态信息卡片（小型数据展示单元）
     */
    @Composable
    private fun StatusCard(
        label: String,
        value: String,
        unit: String
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }

    /**
     * 应用功能说明弹窗组件
     */
    @Composable
    private fun AppFeatureDialog(
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = localized("🚗 CP搭子", "🚗 NaviPilot"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // 致谢卡片
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = localized("感谢各位车友的支持！您的赞助是我们持续优化的动力。", "Thank you for your support! Your sponsorship drives our continuous improvement."),
                            fontSize = 13.sp,
                            color = Color(0xFF92400E),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 核心功能
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeatureItem("🗺️", localized("高德导航集成", "Amap Navigation"), localized("与车机版无缝对接", "Seamless integration with car system"))
                        FeatureItem("🚗", localized("智能驾驶辅助", "Smart Driving Assist"), localized("自动按导航变道转弯", "Auto lane change & turn by navigation"))
                        FeatureItem("👁️", localized("视觉车道感知", "Visual Lane Detection"), localized("识别实虚线避免违章", "Detect lane markings to avoid violations"))
                        FeatureItem("🎯", localized("超车决策提醒", "Overtake Decision"), localized("智能判定超车时机", "Smart overtake timing"))
                        FeatureItem("☁️", localized("配置云端共享", "Cloud Config Sharing"), localized("社区参数一键下发", "One-click community config sync"))
                        FeatureItem("🚦", localized("交通灯感知", "Traffic Light Detection"), localized("红灯自动减速停车", "Auto decelerate at red lights"))
                    }
                    
                    // 底部提示
                    Text(
                        text = localized("15秒后自动关闭 · 更多信息请查看「我的」页面", "Auto-close in 15s · See \"Profile\" for more info"),
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(localized("知道了", "Got it"), fontWeight = FontWeight.Medium)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    /**
     * 功能项组件
     */
    @Composable
    private fun FeatureItem(icon: String, title: String, description: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = icon,
                fontSize = 18.sp,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E293B)
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}
