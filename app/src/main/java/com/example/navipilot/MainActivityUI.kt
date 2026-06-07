package com.example.navipilot

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.navipilot.utils.CoordinatePreferences
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Divider
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
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
import com.example.navipilot.ui.theme.Surface700
import com.example.navipilot.ui.theme.Surface800
import com.example.navipilot.ui.theme.Surface900
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.navipilot.ui.components.TencentNavPage
import com.example.navipilot.ui.components.GoogleNavPage
import com.example.navipilot.navigation.GoogleNavManager
import com.example.navipilot.ui.components.GoogleWelcomeDialog
import com.example.navipilot.ui.components.hasGoogleWelcomeShown
import com.example.navipilot.ui.components.PrivacyConsentDialog
import com.example.navipilot.ui.components.hasPrivacyConsent
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

            // 🆕 隐私政策
            var showPrivacyDialog by remember { mutableStateOf(!hasPrivacyConsent(appContext)) }
            
            if (showPrivacyDialog) {
                PrivacyConsentDialog(
                    onAgree = {
                        showPrivacyDialog = false
                    },
                    onDisagree = {
                        (appContext as? android.app.Activity)?.finish()
                    }
                )
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
                        "TENCENT" -> NavMode.TENCENT
                        "GOOGLE" -> NavMode.GOOGLE
                        "AMAP" -> NavMode.AMAP_AUTO
                        else -> NavMode.AMAP_AUTO
                    }
                    // 用户类型 3（赞助者）每日自动回退到高德车机版
                    if (core.userType.value == 3 &&
                        (navMode == NavMode.TENCENT)
                    ) {
                        val resetPrefs = appContext.getSharedPreferences("navipilot_prefs", Context.MODE_PRIVATE)
                        val today = java.time.LocalDate.now().toString()
                        val lastReset = resetPrefs.getString("user3_daily_reset", "") ?: ""
                        if (lastReset != today) {
                            resetPrefs.edit().putString("user3_daily_reset", today).apply()
                            core.userSelectedMode = "AMAP"
                            core.persistUserSelectedNavMode()
                            navMode = NavMode.AMAP_AUTO
                        }
                    }
                }

                // 地图源选择处理：这里只更新偏好，不立即跳转或拉起地图
                fun handleModeChange(mode: NavMode) {
                    core.userSelectedMode = when (mode) {
                        NavMode.TENCENT -> "TENCENT"
                        NavMode.AMAP_AUTO -> "AMAP"
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
                            wsConnected = core.wsConnected.value,
                            wsDataTimeout = core.wsDataTimeout.value,
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
        wsConnected: Boolean,
        wsDataTimeout: Boolean,
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
        // 🆕 摄像头全屏模式（点击右侧摄像头预览切换）
        var isCameraFullscreen by remember { mutableStateOf(false) }
        // 数据卡片展开/折叠状态
        var isDataCardExpanded by remember { mutableStateOf(true) }
        // 高阶功能对话框状态
        var showAdvancedDialog by remember { mutableStateOf(false) }
        // 点击首页预览条：全屏 7706 JSON 调试
        var show7706JsonDebug by remember { mutableStateOf(false) }
        val carrotFieldsLive by core.carrotManFields
        val mapContext = LocalContext.current

        // ===== 面板显示用状态（由 OsmMapView 回调更新）=====
        var homeAddressSet by remember { mutableStateOf(false) }
        var companyAddressSet by remember { mutableStateOf(false) }

        // ===== 动作触发器（面板按钮点击时递增，OsmMapView 监听执行内部逻辑）=====
        var searchShowTrigger by remember { mutableIntStateOf(0) }
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

        // ===== 地图相关状态 =====
        val mapService = core.userSelectedMode
        val gpsAccuracy = carrotManFields.accuracy
        val positionMode = when {
            gpsAccuracy < 3.0 -> "GPS"
            gpsAccuracy < 10.0 -> "GPS"
            else -> "GPS"
        }

        // 导航起点与 OsmMapView 一致：优先 WGS84 的 latitude/longitude，否则用 X 系列车位（避免 0,0 起点导致「起终点参数错误」）
        val currentNavStartLat = when {
            carrotManFields.latitude != 0.0 && carrotManFields.longitude != 0.0 -> carrotManFields.latitude
            carrotManFields.vpPosPointLat != 0.0 && carrotManFields.vpPosPointLon != 0.0 -> carrotManFields.vpPosPointLat
            else -> 0.0
        }
        val currentNavStartLon = when {
            carrotManFields.latitude != 0.0 && carrotManFields.longitude != 0.0 -> carrotManFields.longitude
            carrotManFields.vpPosPointLat != 0.0 && carrotManFields.vpPosPointLon != 0.0 -> carrotManFields.vpPosPointLon
            else -> 0.0
        }

        // comma 设备连接状态：0=未连接, 1=已连接, 2=异常
        val commaConnectionState = core.getNetworkClientSafely()?.let { client ->
            when {
                core.wsDataTimeout.value -> 2
                client.isRunning() && client.getCurrentDevice() != null -> 1
                else -> 0
            }
        } ?: 0

        // 共享 OsmMapView Composable，消除两处重复调用
        val osmMapView: @Composable () -> Unit = {
            OsmMapView(
                latitude = carrotManFields.vpPosPointLat,
                longitude = carrotManFields.vpPosPointLon,
                bearing = carrotManFields.nPosAngle,
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
                trafficState = carrotManFields.trafficLightState,
                leftSec = carrotManFields.trafficLightCountdown,
                trafficLightDirection = carrotManFields.amap_traffic_light_dir,
                isVideoExpanded = isVideoExpanded,
                onToggleVideo = { isVideoExpanded = !isVideoExpanded },
                isDataCardExpanded = isDataCardExpanded,
                onToggleDataCard = { isDataCardExpanded = true },
                onPageChange = { page -> core.currentPage = page },
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
                onHomeNavClick = { MainActivityUIComponents.sendHomeNavigationToAmap(mapContext) },
                onCompanyNavClick = { MainActivityUIComponents.sendCompanyNavigationToAmap(mapContext) },
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
                commaConnectionState = commaConnectionState,
                searchShowTrigger = searchShowTrigger,
                homeNavTrigger = homeNavTrigger,
                homeNavLongTrigger = homeNavLongTrigger,
                companyNavTrigger = companyNavTrigger,
                companyNavLongTrigger = companyNavLongTrigger,
                onHomeAddressChange = { homeAddressSet = it },
                onCompanyAddressChange = { companyAddressSet = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 地图区 Composable lambda（复用于竖屏/横屏两种布局）
        val mapZoneContent: @Composable () -> Unit = {
            if (isCameraFullscreen) {
                // 摄像头全屏模式
                val wsClient = core.carrotWsClient
                val cameraFrame = wsClient?.cameraFrame?.collectAsState()
                val camData = cameraFrame?.value
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0F172A))) {
                    if (camData != null && camData.payload.isNotEmpty()) {
                        com.example.navipilot.ui.components.CameraPreview(
                            frameBytes = camData.payload,
                            width = camData.width,
                            height = camData.height,
                            isKeyFrame = camData.keyFrame,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Text(
                                "📷 ${localized("等待摄像头画面...", "Waiting for camera...")}",
                                fontSize = 16.sp, color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            } else {
                // 正常地图模式
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
                                    currentLat = currentNavStartLat,
                                    currentLon = currentNavStartLon,
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
                        "GOOGLE" -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                GoogleNavPage(
                                    navManager = core.googleNavManager,
                                    carrotManFieldsState = core.carrotManFields,
                                    goalLat = core.carrotManFields.value.goalPosY,
                                    goalLon = core.carrotManFields.value.goalPosX,
                                    goalName = core.carrotManFields.value.szGoalName,
                                    currentLat = currentNavStartLat,
                                    currentLon = currentNavStartLon,
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
                        else -> osmMapView()
                    }
                }
                // 非导航中：统一显示 OSM 地图
                else -> osmMapView()
            }
            }
        }

        // ===== 横屏布局：左4 : 中12 : 右4 三栏布局 =====
        val cruiseSetSpeed = try { carrotManFields.vCruiseKph.toInt() } catch (_: Exception) { 0 }
        val wsClient = core.carrotWsClient
        val wsVehicleData = wsClient?.vehicleData?.collectAsState()?.value
        val wsDeviceStatus = wsClient?.deviceStatus?.collectAsState()?.value
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧（4份）：HomeControlPanel
            HomeControlPanel(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(4f),
                navMode = navMode,
                onModeChange = onModeChange,
                carrotManFields = carrotManFields,
                userType = userType,
                cruiseSetSpeed = cruiseSetSpeed,
                carCruiseSpeed = try { carrotManFields.carcruiseSpeed.toInt() } catch (_: Exception) { 0 },
                carrotParamClient = core.getCarrotParamClientSafely(),
                homeAddressSet = homeAddressSet,
                companyAddressSet = companyAddressSet,
                commaConnectionState = commaConnectionState,
                onShowAdvancedDialog = { showAdvancedDialog = true },
                onPageChange = onPageChange,
                onSearchClick = { searchShowTrigger++ },
                onHomeNavClick = { homeNavTrigger++ },
                onHomeNavLongClick = { homeNavLongTrigger++ },
                onCompanyNavClick = { companyNavTrigger++ },
                onCompanyNavLongClick = { companyNavLongTrigger++ },
                vehicleData = wsVehicleData,
                deviceStatus = wsDeviceStatus,
                onLanePanelClick = { show7706JsonDebug = true },
            )
                // 中央（12份）：地图
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(12f)
                ) { mapZoneContent() }
                // 右侧（4份）：摄像头 + 底部按钮
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(4f)
                        .background(Surface800)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 自动连接摄像头（数据连接成功后）
                    LaunchedEffect(wsClient?.connectionState?.collectAsState()?.value) {
                        val state = wsClient?.connectionState?.value
                        if (state == com.example.navipilot.data.ConnectionState.CONNECTED) {
                            // 从 core.networkManager 获取 IP
                            val ip = core.networkManager.getCurrentDeviceIP()
                            if (ip != null && ip.isNotEmpty()) {
                                wsClient?.connectCamera(ip)
                            }
                        }
                    }

                    // ===== 实时摄像头画面（点击切换全屏）=====
                    val cameraFrame = wsClient?.cameraFrame?.collectAsState()
                    val camData = cameraFrame?.value
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.85f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .clickable { isCameraFullscreen = !isCameraFullscreen }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (camData != null && camData.payload.isNotEmpty()) {
                                // 有帧数据：显示实时画面
                                com.example.navipilot.ui.components.CameraPreview(
                                    frameBytes = camData.payload,
                                    width = camData.width,
                                    height = camData.height,
                                    isKeyFrame = camData.keyFrame,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                // 无帧数据：显示待命/占位
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val connState = wsClient?.connectionState?.collectAsState()
                                    val isConn = connState?.value == com.example.navipilot.data.ConnectionState.CONNECTED
                                    if (isConn) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("📷", fontSize = 20.sp)
                                            Text(localized("等待画面...", "Waiting..."), fontSize = 9.sp, color = Color(0xFF64748B))
                                            Text(localized("点击全屏", "Tap for fullscreen"), fontSize = 7.sp, color = Color(0xFF475569))
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("📹", fontSize = 20.sp)
                                            Text(localized("实时画面", "Live Camera"), fontSize = 9.sp, color = Color(0xFF64748B))
                                            Text(localized("未连接", "Disconnected"), fontSize = 8.sp, color = Color(0xFF475569))
                                        }
                                    }
                                }
                            }
                            // 全屏模式指示
                            if (isCameraFullscreen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0x80000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "⏺ ${localized("全屏中·点击退出", "Fullscreen·Tap exit")}",
                                        fontSize = 8.sp, color = Color.White
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                                        .background(Color(0x80000000), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("⛶", fontSize = 8.sp, color = Color.White)
                                }
                            }
                        }
                    }

                    // ===== 底部：连接状态 + 功能按钮 =====
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // ===== 连接状态指示 =====
                    val connState = wsClient?.connectionState?.collectAsState()
                    val isConnected = connState?.value == com.example.navipilot.data.ConnectionState.CONNECTED
                    val isTimeout = wsClient?.isDataTimeout?.collectAsState()?.value ?: false
                    val connText = if (isConnected) {
                        if (isTimeout) localized("⚠️ 数据超时", "⚠️ Data timeout")
                        else localized("✅ 已连接", "✅ Connected")
                    } else {
                        localized("🔴 未连接", "🔴 Disconnected")
                    }
                    Text(connText, fontSize = 9.sp, color = Color(0xFF94A3B8))

                    // 右下：搜索/家/公司 三个功能按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                            HomeControlPanelCircleIcon(
                                modifier = Modifier,
                                background = Color(0xFF10B981).copy(alpha = 0.9f),
                                icon = Icons.Default.Search,
                                contentDescription = localized("搜索", "Search"),
                                onClick = { searchShowTrigger++ }
                            )
                            HomeControlPanelEmojiAddress(
                                modifier = Modifier,
                                emoji = "🏠",
                                accessibilityLabel = localized("家", "Home"),
                                addressSet = homeAddressSet,
                                onShortClick = { homeNavTrigger++ },
                                onLongClick = { homeNavLongTrigger++ }
                            )
                            HomeControlPanelEmojiAddress(
                                modifier = Modifier,
                                emoji = "🏢",
                                accessibilityLabel = localized("公司", "Work"),
                                addressSet = companyAddressSet,
                                onShortClick = { companyNavTrigger++ },
                                onLongClick = { companyNavLongTrigger++ }
                            )
                    }  // 按钮Row
                    }  // 底部Column
                }  // 右Column
            }  // 主Row

        // 高阶功能对话框 - 九宫格
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

        // 7706 JSON 调试面板（点击车道盲区面板触发）
        if (show7706JsonDebug) {
            Carrot7706JsonDebugOverlay(
                fields = carrotFieldsLive,
                networkClient = core.getNetworkClientSafely(),
                onDismiss = { show7706JsonDebug = false }
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
        val boxDp = 48.dp
        val iconDp = 22.dp
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
        val boxDp = 48.dp
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
                diameter = 48.dp,
                valueTextSize = 11.sp
            )
        }
    }

    /** 根据当前选中的导航源显示不同图标（Material 无厂商 Logo，用语义区分车机/手机/腾讯/OSM） */
    private fun mapSourceButtonIcon(navMode: NavMode): ImageVector = when (navMode) {
        NavMode.AMAP_AUTO -> Icons.Default.DirectionsCar   // 高德车机版
        NavMode.TENCENT -> Icons.Default.Navigation             // 腾讯导航
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
            NavMode.TENCENT -> localized("腾讯", "Tencent")
            NavMode.GOOGLE -> localized("Google", "Google")
        } + ")"
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(48.dp)
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

    /** 将用户类型数字转为可读文本（与 ProfilePage 一致） */
    private fun userTypeDisplayName(userType: Int): String = when (userType) {
        -1 -> localized("管理员", "Admin")
        0 -> localized("未知用户", "Unknown")
        1 -> localized("新用户", "New User")
        2 -> localized("支持者", "Supporter")
        3 -> localized("赞助者", "Sponsor")
        4 -> localized("铁粉", "Super Fan")
        else -> localized("未知类型", "Unknown Type")
    }

    /** 地图导航源选择弹窗：车机版优先，其次腾讯/高德手机版与谷歌 */
    @Composable
    private fun MapNavModePickerDialog(
        currentMode: NavMode,
        userType: Int,
        restrictedModesUsedToday: Set<NavMode> = emptySet(),
        onRestrictedModeUsed: (NavMode) -> Unit = {},
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
                    NavMode.TENCENT,
                    localized("腾讯导航", "Tencent navigation"),
                    localized("腾讯地图车联", "Tencent Maps integration")
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

        /** 判断该导航源对于当前用户是否可用 */
        fun isModeEnabled(mode: NavMode): Boolean = when (mode) {
            NavMode.AMAP_AUTO, NavMode.GOOGLE -> true
            NavMode.TENCENT -> when (userType) {
                4 -> true
                3 -> mode !in restrictedModesUsedToday
                else -> false
            }
        }

        /** 不可用时的副标题提示 */
        fun disabledSubtitle(mode: NavMode): String = when (userType) {
            3 -> localized("今日额度已用完", "Daily quota used")
            else -> localized("仅铁粉可用", "Super Fan only")
        }

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
                            text = buildString {
                                append(localized("选择地图导航", "Choose navigation map"))
                                append(" | ")
                                append(userTypeDisplayName(userType))
                            },
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

                    // 四个导航源按钮，选中项绿色高亮，不可用项灰色置灰
                    rows.forEach { row ->
                        val isSelected = row.mode == effectiveMode
                        val enabled = isModeEnabled(row.mode)
                        val displaySubtitle = if (!enabled && (row.mode == NavMode.TENCENT)) {
                            disabledSubtitle(row.mode)
                        } else row.subtitle

                        val bgColor = if (isSelected) Color(0xFF22C55E).copy(alpha = 0.15f) else Color(0xFFF1F5F9)
                        val borderColor = if (isSelected) Color(0xFF22C55E) else Color(0xFFE2E8F0)
                        val titleColor = if (isSelected) Color(0xFF166534)
                                        else if (!enabled) Color(0xFF94A3B8)
                                        else Color(0xFF1E293B)
                        val subtitleColor = if (isSelected) Color(0xFF22C55E)
                                           else if (!enabled) Color(0xFFCBD5E1)
                                           else Color(0xFF64748B)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                .background(bgColor)
                                .then(
                                    if (enabled) Modifier.clickable {
                                        if (userType == 3 && (row.mode == NavMode.TENCENT)) {
                                            onRestrictedModeUsed(row.mode)
                                        }
                                        onSelect(row.mode)
                                    } else Modifier
                                )
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
                                    text = displaySubtitle,
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
     * 首页功能控制面板 - 横屏三栏布局
     * 上：道路信息+限速+前车 | 中：速度圆环+地图源 | 下：驾驶状态+红绿灯
     */
    @Composable
    private fun HomeControlPanel(
        modifier: Modifier = Modifier,
        navMode: NavMode,
        onModeChange: (NavMode) -> Unit,
        carrotManFields: CarrotManFields,
        userType: Int,
        cruiseSetSpeed: Int,
        carCruiseSpeed: Int,
        carrotParamClient: CarrotParamClient?,
        homeAddressSet: Boolean,
        companyAddressSet: Boolean,
        commaConnectionState: Int = 0,
        onShowAdvancedDialog: () -> Unit,
        onPageChange: (Int) -> Unit,
        onSearchClick: () -> Unit,
        onHomeNavClick: () -> Unit,
        onHomeNavLongClick: () -> Unit,
        onCompanyNavClick: () -> Unit,
        onCompanyNavLongClick: () -> Unit,
        vehicleData: com.example.navipilot.data.VehicleData? = null,
        deviceStatus: com.example.navipilot.data.DeviceStatus? = null,
        onLanePanelClick: () -> Unit = {},
    ) {
        val panelContext = LocalContext.current
        val scrollState = rememberScrollState()
        var showMapModeDialog by remember { mutableStateOf(false) }
        var restrictedModesUsedToday by remember { mutableStateOf<Set<NavMode>>(emptySet()) }

        // 弹窗打开时从 SharedPreferences 加载今日已用的受限模式
        LaunchedEffect(showMapModeDialog) {
            if (showMapModeDialog) {
                val prefs = panelContext.getSharedPreferences("navipilot_prefs", Context.MODE_PRIVATE)
                val today = java.time.LocalDate.now().toString()
                restrictedModesUsedToday = setOf(
                    NavMode.TENCENT.takeIf { prefs.getString("restricted_used_TENCENT", "") == today }
                ).filterNotNull().toSet()
            }
        }

        val onRestrictedModeUsed: (NavMode) -> Unit = { mode ->
            val prefs = panelContext.getSharedPreferences("navipilot_prefs", Context.MODE_PRIVATE)
            val today = java.time.LocalDate.now().toString()
            prefs.edit().putString("restricted_used_${mode.name}", today).apply()
            restrictedModesUsedToday = restrictedModesUsedToday + mode
        }

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
        val tileBg = Surface800.copy(alpha = 0.72f)
        val searchBgTarget = when (commaConnectionState) {
            1 -> Color(0xFF10B981).copy(alpha = 0.9f)
            2 -> Color(0xFFEF4444).copy(alpha = 0.9f)
            else -> tileBg
        }
        val searchBg by animateColorAsState(
            targetValue = searchBgTarget,
            animationSpec = tween(durationMillis = 500),
            label = "searchBgColor"
        )
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
                userType = userType,
                restrictedModesUsedToday = restrictedModesUsedToday,
                onRestrictedModeUsed = onRestrictedModeUsed,
                onDismiss = { showMapModeDialog = false },
                onSelect = { mode ->
                    onModeChange(mode)
                    showMapModeDialog = false
                }
            )
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Surface900, Surface800)))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部：车道线 + 盲区动态面板（点击打开 7706 调试）
                LaneBlindspotPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(224.dp)
                        .clickable { onLanePanelClick() },
                    laneLineProbs = vehicleData?.modelV2?.laneLineProbs ?: emptyList(),
                    leftDist = vehicleData?.modelV2?.leftDist ?: 0f,
                    rightDist = vehicleData?.modelV2?.rightDist ?: 0f,
                    leftBlindspot = vehicleData?.carState?.leftBlindspot ?: false,
                    rightBlindspot = vehicleData?.carState?.rightBlindspot ?: false,
                    leftLatDist = vehicleData?.carState?.leftLatDist ?: 0f,
                    leadX = vehicleData?.modelV2?.leadX ?: 0f,
                    leadProb = vehicleData?.modelV2?.leadProb ?: 0f,
                )
                // 中：红绿灯数据（高德车机版 + 魔行星距离辅助）
                val trafficData by com.example.navipilot.FloatWindowReaderService.trafficData.collectAsState()
                val panelCtx = panelContext
                fun isAccessibilityEnabled(): Boolean {
                    return try {
                        val am = panelCtx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                        val enabledServices = android.provider.Settings.Secure.getString(
                            panelCtx.contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: ""
                        enabledServices.contains(panelCtx.packageName)
                    } catch (_: Exception) { false }
                }
                val accessibilityOn = remember { mutableStateOf(isAccessibilityEnabled()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        accessibilityOn.value = isAccessibilityEnabled()
                        kotlinx.coroutines.delay(3000)
                    }
                }
                // 高德车机版红绿灯数据
                val amapState = carrotManFields.trafficLightState     // -1=无, 0=绿灯, 1=红灯, 2=黄灯
                val amapCountdown = carrotManFields.trafficLightCountdown // 倒计时秒数
                val amapDist = carrotManFields.trafficLightDistance    // 距离
                val hasAmapData = amapState >= 0 && amapCountdown > 0
                val hasMXingDist = trafficData.distance > 0
                val showTrafficCard = hasAmapData || hasMXingDist || !accessibilityOn.value

                if (showTrafficCard) {
                    // 红绿灯颜色
                    val tColor = when (amapState) {
                        0 -> Color(0xFF22C55E)   // 绿灯
                        1 -> Color(0xFFEF4444)   // 红灯
                        2 -> Color(0xFFFBBF24)   // 黄灯
                        else -> Color(0xFF64748B)
                    }
                    val tLabel = when (amapState) {
                        0 -> localized("绿灯", "Green")
                        1 -> localized("红灯", "Red")
                        2 -> localized("黄灯", "Yellow")
                        else -> localized("--", "--")
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.85f)),
                        modifier = if (!accessibilityOn.value && !hasAmapData)
                            Modifier.fillMaxWidth().clickable {
                                try { panelCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                            }
                        else Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 高德红绿灯
                            if (hasAmapData) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(tColor))
                                    Spacer(Modifier.width(3.dp))
                                    Column {
                                        Text(tLabel, fontSize = 9.sp, color = tColor, fontWeight = FontWeight.Bold)
                                        Text("${amapCountdown}s", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                // 分隔
                                Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF374151)))
                            }
                            // 高德距离
                            if (hasAmapData && amapDist > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📏", fontSize = 12.sp)
                                    Text("${amapDist}m", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                                Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF374151)))
                            }
                            // 魔行星距离（补充）
                            if (hasMXingDist) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🚥", fontSize = 12.sp)
                                    Text("${trafficData.distance}m", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                            }
                            // 无障碍引导（无数据时显示）
                            if (!hasAmapData && !hasMXingDist && !accessibilityOn.value) {
                                Text("📡", fontSize = 14.sp)
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(localized("开启读取红绿灯", "Enable traffic reader"), fontSize = 9.sp, color = Color(0xFF94A3B8))
                                    Text(localized("点击设置", "Tap to settings"), fontSize = 7.sp, color = Color(0xFF64748B))
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                }

                // 下：速度圆环 + 地图切换（移至左下角）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HomePanelSpeedRing(
                        modifier = Modifier,
                        value = cruiseSetSpeed,
                        color = Color(0xFF2196F3),
                        onClick = onCruiseSetClick
                    )
                    HomePanelSpeedRing(
                        modifier = Modifier,
                        value = carrotManFields.vEgoKph,
                        color = Color(0xFF22C55E),
                        onClick = { onPageChange(2) }
                    )
                    HomePanelMapSource(
                        modifier = Modifier,
                        navMode = navMode,
                        onClick = { showMapModeDialog = true }
                    )
                }
            }
        }
    }

    /** 速度显示面板（参考图片左侧速度60样式） */
    @Composable
    private fun SpeedDisplayPanel(
        value: Int,
        label: String,
        backgroundColor: Color
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(width = 72.dp, height = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor.copy(alpha = 0.85f))
                .padding(4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value.toString(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp
            )
        }
    }

    /** 导航状态面板（H 120 ↑ 样式） */
    @Composable
    private fun NavStatusPanel(
        roadName: String,
        limitSpeed: Int,
        nTBTTurnType: Int
    ) {
        val turnArrow = when (nTBTTurnType) {
            1 -> "↑"   // 直行
            2 -> "→"   // 右转
            3 -> "←"   // 左转
            4 -> "↻"   // 环岛
            else -> "—"
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (roadName.isNotEmpty()) {
                    Text(
                        text = roadName.take(2),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (limitSpeed > 0) limitSpeed.toString() else "—",
                    color = Color(0xFFFBBF24),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = turnArrow,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    /** 数值显示面板（预留样式） */
    @Composable
    private fun NumberDisplayPanel(
        value: Int,
        label: String
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(width = 64.dp, height = 56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF475569).copy(alpha = 0.8f))
                .padding(4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value.toString(),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }

    /** 道路信息卡片（道路名 + 限速 + 前车距离） */
    @Composable
    private fun RoadInfoCard(
        roadName: String,
        limitSpeed: Int,
        leadDist: Float,
        leadProb: Float,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.9f))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 道路名 + 限速
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = if (roadName.isNotEmpty()) roadName else localized("未知道路", "Unknown Rd"),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⛔", fontSize = 11.sp)
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = if (limitSpeed > 0) "${limitSpeed} km/h" else localized("-- km/h", "-- km/h"),
                        color = Color(0xFFFBBF24),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // 前车距离
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = localized("🚘 前车", "🚘 Lead"),
                    fontSize = 9.sp,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = if (leadProb > 0.3f) "${leadDist.toInt()} m" else localized("-- m", "-- m"),
                    color = if (leadProb > 0.3f) Color(0xFF22C55E) else Color(0xFF64748B),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    /** 车道感知仪表盘 - 俯视三车道视图 */
    @Composable
    private fun LaneBlindspotPanel(
        modifier: Modifier = Modifier,
        laneLineProbs: List<Float>,
        leftDist: Float,
        rightDist: Float,
        leftBlindspot: Boolean,
        rightBlindspot: Boolean,
        leftLatDist: Float,
        leadX: Float = 0f,
        leadProb: Float = 0f
    ) {
        val ll0 = laneLineProbs.getOrNull(0) ?: 0.5f  // 左路缘
        val ll1 = laneLineProbs.getOrNull(1) ?: 0.5f  // 左车道线
        val ll2 = laneLineProbs.getOrNull(2) ?: 0.5f  // 右车道线
        val ll3 = laneLineProbs.getOrNull(3) ?: 0.5f  // 右路缘
        val hasLead = leadProb > 0.3f && leadX > 0

        fun laneColor(p: Float) = if (p > 0.6f) Color(0xFF60A5FA) else Color(0xFF334155)
        fun dashColor(p: Float) = if (p > 0.6f) Color(0xFF94A3B8) else Color(0xFF334155)

        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Surface800.copy(alpha = 0.85f))
                .border(1.dp, Surface700.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            Text(localized("🚦 车道感知", "🚦 Lane"), fontSize = 9.sp, color = Color(0xFF64748B))
            Spacer(Modifier.height(4.dp))

            // 俯视道路图
            Box(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                // 道路底色
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val laneW = w / 3f         // 每车道宽度
                    val leftEdge = 0f
                    val rightEdge = w

                    // 道路沥青底色
                    drawRect(Color(0xFF141E33))

                    // 路缘线（第0条和第3条）
                    drawLine(laneColor(ll0), androidx.compose.ui.geometry.Offset(leftEdge + 2f, 0f), androidx.compose.ui.geometry.Offset(leftEdge + 2f, h), 3f)
                    drawLine(laneColor(ll3), androidx.compose.ui.geometry.Offset(rightEdge - 2f, 0f), androidx.compose.ui.geometry.Offset(rightEdge - 2f, h), 3f)

                    // 车道虚线（第1条和第2条）
                    fun drawDashed(x: Float, color: Color) {
                        var y = 0f
                        while (y < h) { drawLine(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, (y + 10f).coerceAtMost(h)), 2f); y += 18f }
                    }
                    drawDashed(laneW, dashColor(ll1))
                    drawDashed(laneW * 2f, dashColor(ll2))

                    // 侧方车辆 - 左右盲区
                    fun drawSideCar(cx: Float, blind: Boolean) {
                        if (blind) {
                            for (i in 1..2) drawCircle(Color(0xFFEF4444).copy(alpha = 0.15f), 18f + i * 5f, androidx.compose.ui.geometry.Offset(cx, h * 0.35f))
                            drawRoundRect(Color(0xFF6B7280), androidx.compose.ui.geometry.Offset(cx - 7f, h * 0.35f - 10f), androidx.compose.ui.geometry.Size(14f, 20f), androidx.compose.ui.geometry.CornerRadius(3f))
                            drawLine(Color(0xFFEF4444), androidx.compose.ui.geometry.Offset(cx, h * 0.35f + 10f), androidx.compose.ui.geometry.Offset(cx, h * 0.45f), 1f)
                        }
                    }
                    drawSideCar(laneW / 2f, leftBlindspot)
                    drawSideCar(laneW * 2.5f, rightBlindspot)

                    // 前车（中间车道顶部）
                    if (hasLead) {
                        val s = (leadX.coerceIn(15f, 80f) / 80f).coerceIn(0.3f, 1f)
                        val carW = (16f / s).coerceIn(14f, 26f)
                        val carH = carW * 1.5f
                        val cx = laneW * 1.5f
                        val cy = h * 0.08f + carH / 2f
                        for (i in 1..3) drawCircle(Color(0xFF3B82F6).copy(alpha = 0.08f), carW * 0.6f + i * 5f, androidx.compose.ui.geometry.Offset(cx, cy))
                        // 前车车身 - 俯视
                        drawRoundRect(Color(0xFF94A3B8), androidx.compose.ui.geometry.Offset(cx - carW / 2, cy - carH / 2), androidx.compose.ui.geometry.Size(carW, carH), androidx.compose.ui.geometry.CornerRadius(3f))
                        drawRoundRect(Color(0xFF6B7280).copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(cx - carW * 0.3f, cy - carH / 3), androidx.compose.ui.geometry.Size(carW * 0.6f, carH * 0.2f), androidx.compose.ui.geometry.CornerRadius(1f))
                        // 连接到本车虚线
                        drawLine(Color(0xFF475569).copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(cx, cy + carH / 2), androidx.compose.ui.geometry.Offset(cx, h * 0.55f), 1f)
                    }

                    // 本车（中间车道底部）
                    val myCX = laneW * 1.5f
                    val myCY = h * 0.72f
                    val myW = 24f
                    val myH = 38f
                    // 车身蓝色光晕
                    for (i in 1..2) drawCircle(Color(0xFF3B82F6).copy(alpha = 0.08f), myW * 0.7f + i * 6f, androidx.compose.ui.geometry.Offset(myCX, myCY))
                    // 车身（深蓝）
                    drawRoundRect(Color(0xFF2563EB), androidx.compose.ui.geometry.Offset(myCX - myW / 2, myCY - myH / 2), androidx.compose.ui.geometry.Size(myW, myH), androidx.compose.ui.geometry.CornerRadius(5f))
                    // 车头（浅蓝高亮）
                    drawRoundRect(Color(0xFF60A5FA).copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(myCX - myW * 0.35f, myCY - myH / 2), androidx.compose.ui.geometry.Size(myW * 0.7f, myH * 0.25f), androidx.compose.ui.geometry.CornerRadius(2f))
                    // 挡风玻璃
                    drawRoundRect(Color(0xFF1E3A5F).copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(myCX - myW * 0.3f, myCY - myH * 0.15f), androidx.compose.ui.geometry.Size(myW * 0.6f, myH * 0.2f), androidx.compose.ui.geometry.CornerRadius(1f))
                    // 左大灯
                    drawCircle(Color(0xFFFBBF24).copy(alpha = 0.6f), 2f, androidx.compose.ui.geometry.Offset(myCX - myW * 0.35f, myCY - myH / 2 + 3f))
                    // 右大灯
                    drawCircle(Color(0xFFFBBF24).copy(alpha = 0.6f), 2f, androidx.compose.ui.geometry.Offset(myCX + myW * 0.35f, myCY - myH / 2 + 3f))
                    // 尾灯
                    drawCircle(Color(0xFFEF4444).copy(alpha = 0.5f), 2f, androidx.compose.ui.geometry.Offset(myCX - myW * 0.3f, myCY + myH / 2 - 3f))
                    drawCircle(Color(0xFFEF4444).copy(alpha = 0.5f), 2f, androidx.compose.ui.geometry.Offset(myCX + myW * 0.3f, myCY + myH / 2 - 3f))
                }
            }

            Spacer(Modifier.height(4.dp))
            // 底部车道线置信度 + 盲区状态条
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 四条车道线概率条
                val probs = listOf(ll0, ll1, ll2, ll3)
                probs.forEachIndexed { i, p ->
                    val barColor = when {
                        p > 0.8f -> Color(0xFF22C55E)
                        p > 0.5f -> Color(0xFFFBBF24)
                        else -> Color(0xFF475569)
                    }
                    val label = when (i) { 0 -> "L0"; 1 -> "L1"; 2 -> "L2"; else -> "L3" }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(barColor.copy(alpha = p.coerceIn(0.2f, 1f))))
                        Text(label, fontSize = 6.sp, color = Color(0xFF64748B))
                    }
                }
                // 盲区指示
                Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0xFF334155)))
                Text(if (leftBlindspot) "⚠" else "●", fontSize = 8.sp, color = if (leftBlindspot) Color(0xFFEF4444) else Color(0xFF22C55E))
                Text(if (rightBlindspot) "⚠" else "●", fontSize = 8.sp, color = if (rightBlindspot) Color(0xFFEF4444) else Color(0xFF22C55E))
            }
        }
    }

@Composable
private fun LaneIndicator(
    label: String,
    lineProb: Float,
    laneProb: Float,
    hasBlindspot: Boolean,
    isLeft: Boolean
) {
    val lineColor = if (lineProb > 0.5f) Color(0xFF60A5FA) else Color(0xFF374151)
    val laneColor = if (laneProb > 0.5f) Color(0xFF475569) else Color(0xFF1E293B)
    val activeColor = Color(0xFF3B82F6)
    val warnColor = Color(0xFFEF4444)

    Column(
        modifier = Modifier.width(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 路缘线
        Box(
            modifier = Modifier
                .width(2.dp).height(20.dp)
                .background(lineColor)
        )
        // 车道区域（有盲区时变红）
        Box(
            modifier = Modifier
                .width(28.dp).height(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hasBlindspot) warnColor.copy(alpha = 0.2f) else laneColor),
            contentAlignment = Alignment.Center
        ) {
            if (hasBlindspot) {
                // 盲区车辆
                Box(
                    modifier = Modifier
                        .size(14.dp, 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF6B7280))
                )
            }
            // 车道线置信度指示条
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .align(if (isLeft) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(activeColor.copy(alpha = laneProb.coerceIn(0.2f, 1f)))
            )
        }
        Box(
            modifier = Modifier
                .width(2.dp).height(20.dp)
                .background(lineColor)
        )
    }
}

    /** 驾驶状态面板（openpilot 状态 + 巡航 + 红绿灯倒计时） */
    @Composable
    private fun DrivingStatusPanel(
        active: Boolean,
        vEgo: Int,
        vCruise: Int,
        isExperimental: Boolean?,
        trafficState: Int,
        trafficCountdown: Int,
        modifier: Modifier = Modifier
    ) {
        // trafficState: -1=无数据, 0=绿灯, 1=红灯, 2=黄灯
        val (tColor, tText) = when (trafficState) {
            0 -> Color(0xFF22C55E) to localized("绿灯", "Green")
            1 -> Color(0xFFEF4444) to localized("红灯", "Red")
            2 -> Color(0xFFFBBF24) to localized("黄灯", "Yellow")
            else -> Color(0xFF64748B) to localized("--", "--")
        }
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.9f))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // openpilot 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (active) "🟢" else "⚪",
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = if (active) localized("已激活", "Active")
                               else localized("待机", "Standby"),
                        fontSize = 11.sp,
                        color = if (active) Color(0xFF22C55E) else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold
                    )
                    val expText = when (isExperimental) {
                        true -> localized("🧪 实验", "🧪 Exp")
                        false -> localized("❄️ Chill", "❄️ Chill")
                        null -> "--"
                    }
                    Text(expText, fontSize = 8.sp, color = Color(0xFF64748B))
                }
            }
            // 速度信息
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${vEgo}/${vCruise}",
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = localized("km/h", "km/h"),
                    fontSize = 8.sp,
                    color = Color(0xFF94A3B8)
                )
            }
            // 红绿灯倒计时
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(tColor)
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = if (trafficCountdown > 0) "${trafficCountdown}s" else tText,
                        fontSize = 13.sp,
                        color = tColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = localized("🚥", "🚥"),
                        fontSize = 8.sp,
                        color = Color(0xFF64748B)
                    )
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
                    // ⚠️ 雷达车型提示
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = localized(
                                "⚠️ 仅部分支持雷达统合的车型体验最佳。非雷达车型部分功能（如前车距离检测）可能受限。",
                                "⚠️ Best experience on vehicles with radar integration. Non-radar vehicles may have limited features (e.g. lead distance detection)."
                            ),
                            fontSize = 13.sp,
                            color = Color(0xFF92400E),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 简洁功能列表
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FeatureItem("🗺️", localized("三地图导航", "Triple Nav"), localized("高德车机/腾讯/Google", "Amap/Tencent/Google"))
                        FeatureItem("🚗", localized("驾驶辅助", "Driving Assist"), localized("自动变道转弯", "Auto lane change & turn"))
                        FeatureItem("📊", localized("驾驶报告", "Driving Report"), localized("评分与建议", "Score & tips"))
                    }

                    Text(
                        text = localized("15秒后自动关闭", "Auto-close in 15s"),
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
