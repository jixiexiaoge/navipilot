package com.jixiexiaoge.drivingassist

import androidx.compose.runtime.collectAsState

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.jixiexiaoge.drivingassist.ui.theme.Surface700
import com.jixiexiaoge.drivingassist.ui.theme.Surface600
import com.jixiexiaoge.drivingassist.ui.theme.Surface800
import com.jixiexiaoge.drivingassist.ui.theme.Surface900
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.ui.components.AutoSwitchExperimentPage
import com.jixiexiaoge.drivingassist.ui.components.AdaptiveCardGrid
import com.jixiexiaoge.drivingassist.ui.components.AdaptivePageColumn
import com.jixiexiaoge.drivingassist.ui.components.HomeDashboardTabs
import com.jixiexiaoge.drivingassist.ui.components.rememberAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.CommaConnectionDisplay
import com.jixiexiaoge.drivingassist.data.resolveCommaConnectionDisplay
import com.jixiexiaoge.drivingassist.ui.components.Carrot7706JsonDebugOverlay
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.projection.ProjectionService
import com.navipilot.amap2tesla.projection.DailyProjectionTrial
import com.navipilot.amap2tesla.membership.ProjectionMembership
import com.navipilot.amap2tesla.membership.ProjectionMembershipClient
import com.navipilot.amap2tesla.membership.ProjectionPermanentUnlock
import com.navipilot.amap2tesla.membership.ProjectionPlan
import com.jixiexiaoge.drivingassist.core.Result
import rikka.shizuku.Shizuku

/**
 * MainActivity UI组件管理类
 * 负责所有UI组件的定义和界面逻辑
 */
@OptIn(ExperimentalLayoutApi::class)
class MainActivityUI(
    private val core: MainActivityCore
) {

    /**
     * 设置用户界面
     */
    @Composable
    fun SetupUserInterface() {
        NavipilotTheme {
            Box(modifier = Modifier.fillMaxSize().background(Surface900).safeDrawingPadding()) {
                // 拦截返回键：正常返回
                BackHandler(enabled = true) {
                    if (core.currentPage !is Page.CpFunctions) {
                        core.currentPage = Page.CpFunctions
                    }
                    // CP 功能页时什么都不做，防止退出应用
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    when (core.currentPage) {
                        is Page.Projection -> SecondaryPageHeader(
                            localized("高德投屏", "AMap projection"),
                            localized("连接浏览器 · 查看投屏状态与设置", "Connect a browser · projection status and settings"),
                        )
                        is Page.Membership -> SecondaryPageHeader(
                            localized("投屏会员", "Projection membership"),
                            localized("查看权益、试用与订单", "Entitlements, trial and orders"),
                        )
                        else -> Unit
                    }
                    Box(modifier = Modifier.weight(1f)) {
                    // 根据当前页面显示不同内容
                    when (core.currentPage) {
                        is Page.CpFunctions -> HomePage(
                            userType = core.userType.value,
                            carrotManFields = core.carrotManFields.value,
                            onSendCommand = { command, arg -> core.sendCarrotCommand(command, arg) },
                            onSendRoadLimitSpeed = { core.sendCurrentRoadLimitSpeed() },
                            onLaunchAmap = { core.launchAmapAuto() },
                            onSendNavConfirmation = { core.sendNavigationConfirmationManually() },
                        )
                        is Page.Projection -> ProjectionPage()
                        is Page.Membership -> MembershipPage()
                        is Page.Experiment -> AutoSwitchExperimentPage(
                            onBack = {
                                core.currentPage = Page.CpFunctions
                            },
                            conditionalExperimentManager = core.getConditionalExperimentManagerSafely(),
                            carrotParamClient = core.getCarrotParamClientSafely(),
                            carrotManFields = core.carrotManFields,
                            automationObservation = core.automationObservation,
                        )
                        is Page.SmartSpeed -> SmartSpeedPage(
                            onBack = {
                                core.currentPage = Page.CpFunctions
                            },
                            fields = core.carrotManFields.value,
                        )
                    }
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
        onSendCommand: (String, String) -> Unit,
        onSendRoadLimitSpeed: () -> Unit,
        onLaunchAmap: () -> Unit,
        onSendNavConfirmation: () -> Unit,
    ) {
        // 点击首页预览条：全屏 7706 JSON 调试
        var show7706JsonDebug by remember { mutableStateOf(false) }
        val carrotFieldsLive by core.carrotManFields

        fun connectionDisplayNow(): CommaConnectionDisplay {
            val client = core.getNetworkClientSafely()
            val compact = core.carrotTelemetry.value
            return resolveCommaConnectionDisplay(
                client?.isRunning() == true, client?.getCurrentDevice() != null,
                core.carrotWsClient?.connectionState?.value
                    ?: com.jixiexiaoge.drivingassist.data.ConnectionState.DISCONNECTED,
                core.wsDataTimeout.value,
                compact?.let { value -> value.services.keys.any { value.fresh(it) != null } } == true,
            )
        }
        val owner = LocalLifecycleOwner.current
        var commaConnectionDisplay by remember(core, owner) { mutableStateOf(connectionDisplayNow()) }
        LaunchedEffect(core, owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    commaConnectionDisplay = connectionDisplayNow()
                    delay(500)
                }
            }
        }

        // ===== 垂直布局：数据面板 + 底部控制栏 =====
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：数据面板（占据主要空间），无地图时纯深色背景
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Surface900)
            ) {
                // 数据面板（居中显示）
                // 注意：旧实现还传了 cruiseSetSpeed / carCruiseSpeed / vehicleData 三个参数，
                // 而 HomeControlPanel 体内一次都没用到，却让调用方每次重组都构造一遍完整
                // VehicleData 对象图（20Hz 车端数据 → 每秒白算 20 次）。已删除。
                HomeControlPanel(
                    modifier = Modifier
                        .fillMaxSize(),
                    carrotManFields = carrotManFields,
                    userType = userType,
                    carrotParamClient = core.getCarrotParamClientSafely(),
                    commaConnectionDisplay = commaConnectionDisplay,
                    onPageChange = { page: Page -> core.currentPage = page },
                    onSendCommand = onSendCommand,
                    onSendRoadLimitSpeed = onSendRoadLimitSpeed,
                    onLaunchAmap = onLaunchAmap,
                    onSendNavConfirmation = onSendNavConfirmation,
                    onShow7706Debug = { show7706JsonDebug = true },
                )
            }

        }

        // 7706 JSON 调试面板（点击车道盲区面板触发）
        if (show7706JsonDebug) {
            Carrot7706JsonDebugOverlay(
                fields = carrotFieldsLive,
                networkClient = core.getNetworkClientSafely(),
                v2Client = core.naviV2Client,
                streamManager = core.naviStreamManager,
                broadcastManager = core.getAmapBroadcastManagerOrNull(),
                bridge = core.xiaogeBridge,
                onDismiss = { show7706JsonDebug = false }
            )
        }

    }

    /**
     * 首页功能控制面板
     */
    @Composable
    private fun HomeControlPanel(
        modifier: Modifier = Modifier,
        carrotManFields: CarrotManFields,
        userType: Int,
        carrotParamClient: CarrotParamClient?,
        commaConnectionDisplay: CommaConnectionDisplay = CommaConnectionDisplay.Disconnected,
        onPageChange: (Page) -> Unit,
        onSendCommand: (String, String) -> Unit = { _, _ -> },
        onSendRoadLimitSpeed: () -> Unit = {},
        onLaunchAmap: () -> Unit = {},
        onSendNavConfirmation: () -> Unit = {},
        onShow7706Debug: () -> Unit,
    ) {
        val panelContext = LocalContext.current
        var aboutOpenTrigger by rememberSaveable { mutableIntStateOf(0) }
        // 只在原始 telemetry 变化时重建显示快照（20Hz 数据 + 每次重组都全量映射 = 掉帧主因之一）
        val telemetryRaw = core.carrotTelemetry.value
        val telemetryDisplay = remember(telemetryRaw) { telemetryRaw.toDisplaySnapshot() }
        val effectiveDrivingMode = if (core.automaticDrivingMode) core.detectedDrivingMode else core.drivingMode
        val amapDisplay = rememberAmapNavigationDisplay(
            carrotManFields, core.naviSources, core.activeNavMode.value, core::sampleAutomaticDrivingMode,
        )

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
        // 从设备读取 AlwaysLateral 参数值
        var isAlwaysLateral by remember(carrotParamClient) { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(carrotParamClient) {
            if (carrotParamClient == null) {
                isAlwaysLateral = null
                return@LaunchedEffect
            }
            val result = carrotParamClient.getParams("AlwaysLateral")
            isAlwaysLateral = result.getOrNull()?.get("AlwaysLateral")?.let(::parseExperimentalMode)
        }

        // 从设备读取 ShareData 参数值
        var isShareDataEnabled by remember(carrotParamClient) { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(carrotParamClient) {
            if (carrotParamClient == null) {
                isShareDataEnabled = null
                return@LaunchedEffect
            }
            val result = carrotParamClient.getParams("ShareData")
            isShareDataEnabled = result.getOrNull()?.get("ShareData")?.let(::parseExperimentalMode)
        }

        val onAlwaysLateralClick: () -> Unit = {
            if (carrotParamClient == null) {
                android.widget.Toast.makeText(
                    panelContext,
                    localized("设备未连接", "Device not connected"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                coroutineScope.launch {
                    val currentMode = isAlwaysLateral ?: carrotParamClient
                        .getParams("AlwaysLateral")
                        .getOrNull()
                        ?.get("AlwaysLateral")
                        ?.let(::parseExperimentalMode)
                        ?: false
                    val targetMode = !currentMode
                    val result = carrotParamClient.setAlwaysLateral(targetMode)
                    if (result.isSuccess) {
                        isAlwaysLateral = targetMode
                        android.widget.Toast.makeText(
                            panelContext,
                            if (targetMode) localized("已开启横向控制", "AlwaysLateral ON")
                            else localized("已关闭横向控制", "AlwaysLateral OFF"),
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

        val onShareDataClick: () -> Unit = {
            if (carrotParamClient == null) {
                android.widget.Toast.makeText(
                    panelContext,
                    localized("设备未连接", "Device not connected"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                coroutineScope.launch {
                    val currentMode = isShareDataEnabled ?: carrotParamClient
                        .getParams("ShareData")
                        .getOrNull()
                        ?.get("ShareData")
                        ?.let(::parseExperimentalMode)
                        ?: false
                    val targetMode = !currentMode
                    val result = carrotParamClient.setParam("ShareData", if (targetMode) 1 else 0)
                    if (result.isSuccess) {
                        isShareDataEnabled = targetMode
                        android.widget.Toast.makeText(
                            panelContext,
                            if (targetMode) localized("数据分发已开启", "Share ON")
                            else localized("数据分发已关闭", "Share OFF"),
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

        val selectedDeviceIP by core.selectedCameraHost.collectAsState()
        HomeDashboardTabs(footer = { section ->
            // 实时页没有底部标题条：路口引导条在驾驶卡正下方，导航数据在卡片下方滚动区。
            // 控制页（唯一非实时页）：显示大标题、副标题和「手动」标记。
            if (section != 0) {
                com.jixiexiaoge.drivingassist.ui.components.SectionTitleFooter(section)
            }
        }) { section ->
            when (section) {
                // 实时页：前视画面 + 原看板全部内容（原「看板」与「AR」两页合并）
                0 -> {
                    val cameraHost by core.selectedCameraHost.collectAsState()
                    com.jixiexiaoge.drivingassist.ui.components.RealTimePage(
                        host = cameraHost,
                        snapshot = core.dashboardSnapshot.value,
                        // 设备搜索/发现结果并进画面区：连上就出画面，只发现设备就显示 IP + 连接状态，
                        // 5001 不通则提示开启视频流功能
                        deviceIp = selectedDeviceIP,
                        connection = commaConnectionDisplay,
                        onConnectionClick = onShow7706Debug,
                        // 驾驶模式（只读）：Auto / EAP / NOA / NOO，显示在驾驶卡右上角
                        drivingMode = effectiveDrivingMode,
                        // 车道宽度：左 | 本 | 右（米）。来自 7000 modelV2 的 4 条 laneLines，
                        // 在前视 5 米处插值后相邻差分；不是高德广播给的值。
                        laneGeometry = core.laneGeometry.value,
                        laneFusion = core.laneFusion.value,
                        // 路口车道引导条：紧跟驾驶卡正下方居中。
                        // 沿用原来的取值优先级：导航短暂断流时优先显示保留的本路线最近图示，避免图标闪没。
                        junction = amapDisplay.displayJunction ?: amapDisplay.junction,
                        // 原导航页的全部内容（7.9.44 并入实时页滚动区）
                        amap = amapDisplay,
                        vehicle = telemetryDisplay,
                        delivery = core.naviDeliveryState,
                        routeReady = core.routeTaskState.ready,
                        onAboutClick = { aboutOpenTrigger++ },
                        // 左半屏内嵌浏览器：没开高德 / 投屏未开启时点提示跳投屏页
                        onOpenProjection = { onPageChange(Page.Projection) },
                    )
                }
                // 控制页（唯一可写页）；索引越界也落到这里
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MainActivityUIComponents.AdvancedFunctionsContent(
                        onSendCommand = onSendCommand,
                        onSendRoadLimitSpeed = onSendRoadLimitSpeed,
                        onLaunchAmap = onLaunchAmap,
                        onSendNavConfirmation = onSendNavConfirmation,
                        onPageChange = onPageChange,
                        isOpenpilotActive = carrotManFields.active,
                        carrotManFields = carrotManFields,
                        networkManager = try { core.networkManager } catch (_: Exception) { null },
                        context = panelContext,
                        onShow7706Debug = onShow7706Debug,
                        onAlwaysLateralClick = { onAlwaysLateralClick() },
                        isAlwaysLateralEnabled = isAlwaysLateral,
                        onShareDataClick = { onShareDataClick() },
                        isShareDataEnabled = isShareDataEnabled,
                        trafficLightState = carrotManFields.trafficLightState,
                        trafficLightCountdown = carrotManFields.trafficLightCountdown,
                        trafficLightDir = carrotManFields.amap_traffic_light_dir,
                        automationObservation = core.automationObservation,
                    )
                }
            }
        }
        MainActivityUIComponents.AboutDialog(panelContext, aboutOpenTrigger)
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun MapDestinationCapsule(
        onNavigateHome: () -> DestinationNavigationResult,
        onNavigateCompany: () -> DestinationNavigationResult,
    ) {
        val context = LocalContext.current
        var sliderPosition by remember { mutableStateOf(0f) }
        var destination by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(destination) {
            if (destination != null) {
                delay(850)
                sliderPosition = 0f
                destination = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(34.dp))
                .clip(RoundedCornerShape(34.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF1D4ED8), Color(0xFF334155), Color(0xFFEA580C)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("回家", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("公司", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = sliderPosition,
                onValueChange = { value ->
                    if (destination == null) sliderPosition = value
                },
                onValueChangeFinished = {
                    if (destination != null) return@Slider
                    destination = when {
                        sliderPosition <= -destinationTriggerThreshold -> {
                            val result = onNavigateHome()
                            android.widget.Toast.makeText(
                                context,
                                result.message,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            if (result.accepted) "home" else {
                                sliderPosition = 0f
                                null
                            }
                        }
                        sliderPosition >= destinationTriggerThreshold -> {
                            val result = onNavigateCompany()
                            android.widget.Toast.makeText(
                                context,
                                result.message,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            if (result.accepted) "company" else {
                                sliderPosition = 0f
                                null
                            }
                        }
                        else -> {
                            sliderPosition = 0f
                            null
                        }
                    }
                },
                valueRange = -1f..1f,
                enabled = destination == null,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFF4F7FB),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = Color(0xFFF4F7FB),
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .height(44.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF0EA5E9), Color(0xFF2563EB)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "导航",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun SmartSpeedPage(
        onBack: () -> Unit,
        fields: CarrotManFields,
    ) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) }
        var enabled by remember { mutableStateOf(prefs.getBoolean(SmartSpeedLimiter.KEY_ENABLED, true)) }
        var rampProtection by remember { mutableStateOf(prefs.getBoolean(SmartSpeedLimiter.KEY_RAMP_PROTECTION, true)) }
        var accelMainline by remember { mutableStateOf(prefs.getBoolean(SmartSpeedLimiter.KEY_ACCEL_TO_MAINLINE, true)) }
        var decelRamp by remember { mutableStateOf(prefs.getBoolean(SmartSpeedLimiter.KEY_DECEL_TO_RAMP, true)) }
        var curveHold by remember { mutableStateOf(prefs.getBoolean(SmartSpeedLimiter.KEY_CURVE_HOLD_ENABLED, true)) }

        fun save() {
            prefs.edit()
                .putBoolean(SmartSpeedLimiter.KEY_ENABLED, enabled)
                .putBoolean(SmartSpeedLimiter.KEY_RAMP_PROTECTION, rampProtection)
                .putBoolean(SmartSpeedLimiter.KEY_ACCEL_TO_MAINLINE, accelMainline)
                .putBoolean(SmartSpeedLimiter.KEY_DECEL_TO_RAMP, decelRamp)
                .putBoolean(SmartSpeedLimiter.KEY_CURVE_HOLD_ENABLED, curveHold)
                .apply()
        }

        LaunchedEffect(enabled, rampProtection, accelMainline, decelRamp, curveHold) {
            save()
        }

        val lastOutput = prefs.getInt(SmartSpeedLimiter.KEY_LAST_OUTPUT, fields.nRoadLimitSpeed)
        val lastTarget = prefs.getInt(SmartSpeedLimiter.KEY_LAST_TARGET, 0)
        val lastType = prefs.getString(SmartSpeedLimiter.KEY_LAST_TYPE, SmartSpeedTransitionType.None.name) ?: SmartSpeedTransitionType.None.name
        val lastDistance = prefs.getInt(SmartSpeedLimiter.KEY_LAST_DISTANCE, fields.nTBTDist)
        val lastReason = prefs.getString(SmartSpeedLimiter.KEY_LAST_REASON, "等待导航数据") ?: "等待导航数据"
        val waiting = lastType in setOf(
            SmartSpeedTransitionType.CurveHold.name,
            SmartSpeedTransitionType.WaitingForMainlineLimit.name,
            SmartSpeedTransitionType.WaitingForConfirmation.name,
            SmartSpeedTransitionType.RecoveryTimedOut.name,
        )
        val cardColor = if (waiting) Color(0xFF374151) else Color(0xFF0F766E)

        AdaptivePageColumn(modifier = Modifier.background(Surface900)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("智能调速", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("NOA 路线速度预调，仅高速 / 高架 / 快速路场景启用", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("最近策略输出 ${lastOutput.takeIf { it > 0 } ?: fields.nRoadLimitSpeed} km/h", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmartSpeedStatusChip("状态", smartSpeedTypeText(lastType))
                        SmartSpeedStatusChip("路线目标", if (lastTarget > 0) "${lastTarget}km/h" else "--")
                        SmartSpeedStatusChip("距离", if (lastDistance > 0) "${lastDistance}m" else "--")
                    }
                    Text(lastReason, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
                    Text(
                        "地图原始限速在看板独立显示；有效策略通过 7714 导航限速发送。此处为最近计算记录，非实时发送、车端采用或执行确认。",
                        color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp,
                    )
                }
            }

            AdaptiveCardGrid {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("固定规则", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        SmartSpeedInfoRow("下匝道", "距匝道 800m 开始，每档降 10km/h，按剩余距离分配档位，提前降到 60km/h；首次发现不足 800m 时按实际距离安排。")
                        SmartSpeedInfoRow("上匝道", "等待真实主路限速稳定3秒，不猜80/120；恢复不依赖旧动作距离，每3秒最多升一档10km/h，90秒未完成需人工确认。")
                        SmartSpeedInfoRow("弯道", "7000曲率与车速须新鲜配对，持续变缓2秒才允许恢复；缺失或弯道暂停升速。此为工程门槛，不证明已汇入或安全速度。")
                        SmartSpeedInfoRow("巡航", "既有自动提速仍以原始道路限速为上限；恢复期间另受策略限制，每档等待7000设定回报，手动调速取消本轮自动巡航提速。策略完成不等于车辆执行。")
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.72f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmartSpeedSwitchRow("匝道 40→60 保护", "高德在高速/快速路给 40 时，输出匝道基准限速", rampProtection) { rampProtection = it }
                        SmartSpeedSwitchRow("匝道进入主路升速", "真实限速确认后按时间分档恢复，不依赖旧匝道距离", accelMainline) { accelMainline = it }
                        SmartSpeedSwitchRow("主路进入匝道降速", "准备下匝道/出口时提前平滑降到匝道限速", decelRamp) { decelRamp = it }
                        SmartSpeedSwitchRow("弯道暂停升速", "曲率缺失、过期或弯道时暂停汇入升速；下匝道降速不受影响", curveHold) { curveHold = it }
                    }
                }
            }
        }
    }

    @Composable
    private fun SmartSpeedStatusChip(label: String, value: String) {
        Surface(color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
            Text("$label $value", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        }
    }

    @Composable
    private fun SmartSpeedSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun SmartSpeedInfoRow(title: String, detail: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(color = Color(0xFF0F766E), shape = RoundedCornerShape(8.dp)) {
                Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
            Text(detail, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        }
    }

    private fun smartSpeedTypeText(type: String): String = when (type) {
        SmartSpeedTransitionType.RampProtection.name -> "匝道保护"
        SmartSpeedTransitionType.RampToMainline.name -> "匝道进主路"
        SmartSpeedTransitionType.MainlineToRamp.name -> "主路进匝道"
        SmartSpeedTransitionType.CurveHold.name -> "曲率待确认"
        SmartSpeedTransitionType.WaitingForMainlineLimit.name -> "等待主路限速"
        SmartSpeedTransitionType.WaitingForConfirmation.name -> "等待限速稳定"
        SmartSpeedTransitionType.RecoveryTimedOut.name -> "恢复超时"
        else -> "跟随导航"
    }

    @Composable
    private fun SecondaryPageHeader(title: String, subtitle: String) {
        Surface(color = Surface800, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { core.currentPage = Page.CpFunctions }) {
                    Icon(CustomIcons.ArrowBack, localized("返回首页", "Back to dashboard"), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun ProjectionPage() {
        AdaptivePageColumn(modifier = Modifier.background(Surface900)) {
            AmapProjectionDashboardPanel(
                onOpenMembership = { core.currentPage = Page.Membership },
            )
            MapDestinationCapsule(
                onNavigateHome = {
                    core.sendHomeNavigationToAmap()
                    DestinationNavigationResult(true, "回家请求已发送，请查看高德导航画面")
                },
                onNavigateCompany = {
                    core.sendCompanyNavigationToAmap()
                    DestinationNavigationResult(true, "公司请求已发送，请查看高德导航画面")
                },
            )
        }
    }

    @Composable
    private fun MembershipPage() {
        val context = LocalContext.current
        val membershipClient = remember { ProjectionMembershipClient(context) }
        val membershipScope = rememberCoroutineScope()
        var membership by remember { mutableStateOf<ProjectionMembership?>(null) }
        var membershipMessage by remember { mutableStateOf<String?>(null) }
        var selectedPlanCode by remember { mutableStateOf<String?>(null) }
        var pendingOrderNo by remember { mutableStateOf<String?>(null) }
        var secretCode by remember { mutableStateOf<String?>(null) }
        var showMembershipDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            when (val result = membershipClient.loadMembership()) {
                is Result.Success -> {
                    membership = result.data
                    selectedPlanCode = result.data.plans.firstOrNull()?.code
                }
                is Result.Error -> membershipMessage = "会员服务暂不可用：${result.message}"
                Result.Loading -> Unit
            }
        }

        AdaptivePageColumn(modifier = Modifier.background(Surface900)) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14325A)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1D4F91), Color(0xFF0F1E35)),
                            ),
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("CP搭子会员", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("让高德车机版投屏更自由", color = Color(0xFFBFDBFE), fontSize = 14.sp)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.1f),
                    ) {
                        Text(
                            when {
                                membership?.entitlement?.enabled != true -> "每日 45 分钟免费试用正在进行中"
                                membership?.entitlement?.isLifetime == true -> "你已拥有永久会员权益"
                                else -> "会员有效至 ${membership?.entitlement?.expiresAt?.substringBefore("T")}"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            AdaptiveCardGrid {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.92f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("开通后可以获得什么？", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        MembershipBenefitRow("不限时高德车机版投屏", "不受每日免费试用时长限制")
                        MembershipBenefitRow("局域网触摸反控", "在浏览器中查看地图画面并进行触控操作")
                        MembershipBenefitRow("持续功能更新", "优先获得投屏稳定性和体验优化")
                        MembershipBenefitRow("换机与恢复支持", "支持安全迁移已开通的会员权益")
                    }
                }

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.92f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("为什么需要收费？", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "投屏功能需要持续维护兼容性、适配系统升级，并承担服务器、测试设备与日常开发成本。会员支持能帮助我们把 CP搭子做得更稳定、更好用。",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF18334D)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("感谢你的使用与支持", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "无论是开通会员、赞助，还是分享给需要的朋友，都是对持续开发最大的鼓励。",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    }
            }

            Button(
                onClick = { showMembershipDialog = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(
                    if (membership?.entitlement?.enabled == true) "管理会员与赞助支持" else "开通会员 · 支持 CP搭子",
                )
        }
        }

        if (showMembershipDialog) {
            ProjectionMembershipDialog(
                membership = membership,
                selectedPlanCode = selectedPlanCode,
                message = membershipMessage,
                pendingOrderNo = pendingOrderNo,
                onDismiss = { showMembershipDialog = false },
                onSelectPlan = { selectedPlanCode = it },
                onStartTrial = {
                    showMembershipDialog = false
                    core.currentPage = Page.Projection
                },
                onCreateOrder = { plan ->
                    membershipMessage = "正在创建支付订单"
                    membershipScope.launch {
                        when (val result = membershipClient.createOrder(plan.code)) {
                            is Result.Success -> {
                                pendingOrderNo = result.data.orderNo
                                membershipMessage = "已打开支付页面，完成付款后点击“我已完成支付”"
                                val checkoutIntent = Intent(Intent.ACTION_VIEW, Uri.parse(result.data.checkoutUrl))
                                if (checkoutIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(checkoutIntent)
                                } else {
                                    membershipMessage = "未找到浏览器，请复制订单后在浏览器中完成支付"
                                }
                            }
                            is Result.Error -> membershipMessage = "创建订单失败：${result.message}"
                            Result.Loading -> Unit
                        }
                    }
                },
                onRefreshOrder = {
                    val orderNo = pendingOrderNo ?: return@ProjectionMembershipDialog
                    membershipMessage = "正在确认支付状态"
                    membershipScope.launch {
                        when (val result = membershipClient.refreshOrder(orderNo)) {
                            is Result.Success -> {
                                membership = membership?.copy(entitlement = result.data)
                                membershipMessage = if (result.data.enabled) {
                                    pendingOrderNo = null
                                    "会员已开通，可以不限时投屏"
                                } else {
                                    "暂未查询到付款，请稍后重试"
                                }
                            }
                            is Result.Error -> membershipMessage = "查询失败：${result.message}"
                            Result.Loading -> Unit
                        }
                    }
                },
                onCreateTransferCode = {
                    membershipScope.launch {
                        when (val result = membershipClient.createTransferCode()) {
                            is Result.Success -> secretCode = "换机码（10 分钟内有效）：\n${result.data}"
                            is Result.Error -> membershipMessage = "生成换机码失败：${result.message}"
                            Result.Loading -> Unit
                        }
                    }
                },
                onCreateRecoveryCode = {
                    membershipScope.launch {
                        when (val result = membershipClient.createRecoveryCode()) {
                            is Result.Success -> secretCode = "恢复码（请妥善保存）：\n${result.data}"
                            is Result.Error -> membershipMessage = "生成恢复码失败：${result.message}"
                            Result.Loading -> Unit
                        }
                    }
                },
                onTransferLicense = { code, isRecovery ->
                    membershipMessage = "正在迁移会员"
                    membershipScope.launch {
                        val result = if (isRecovery) {
                            membershipClient.recoverLicense(code)
                        } else {
                            membershipClient.transferLicense(code)
                        }
                        when (result) {
                            is Result.Success -> {
                                membership = membership?.copy(entitlement = result.data)
                                membershipMessage = "会员已迁移到本机"
                            }
                            is Result.Error -> membershipMessage = "迁移失败：${result.message}"
                            Result.Loading -> Unit
                        }
                    }
                },
                onRedeemPermanentUnlock = { code ->
                    if (ProjectionPermanentUnlock.redeem(context, code)) {
                        membership = ProjectionMembership(
                            plans = emptyList(),
                            entitlement = ProjectionPermanentUnlock.entitlement(),
                        )
                        membershipMessage = "永久投屏已解锁，不受每日 45 分钟限制"
                    } else {
                        membershipMessage = "永久解锁码不正确"
                    }
                },
            )
        }

        secretCode?.let { code ->
            AlertDialog(
                onDismissRequest = { secretCode = null },
                title = { Text("会员迁移凭证") },
                text = { Text(code) },
                confirmButton = { TextButton(onClick = { secretCode = null }) { Text("已保存") } },
            )
        }
    }

    @Composable
    private fun MembershipBenefitRow(title: String, description: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2563EB).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color(0xFF93C5FD), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun AmapProjectionDashboardPanel(
        onOpenMembership: () -> Unit,
    ) {
        val context = LocalContext.current
        var refreshTick by remember { mutableIntStateOf(0) }
        var showHelp by remember { mutableStateOf(false) }
        var mode by remember { mutableStateOf(ConnectionMode.LAN) }
        var pendingProjectionTrialMode by remember { mutableStateOf(true) }
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val openOfficialDownload: (String) -> Unit = { url ->
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (browserIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(browserIntent)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "未找到浏览器，无法打开官方下载页面",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val vpnPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                startTeslaProjectionFromDashboard(context, pendingProjectionTrialMode)
            }
            pendingProjectionTrialMode = true
            refreshTick++
        }
        // 轮询必须绑定生命周期：旧实现是裸 while(isActive)，退到后台仍以 2Hz 唤醒主线程。
        // （CompositionLocal 只能在组合上下文里读，先把 lifecycleOwner 取出来再进协程）
        val pollLifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(pollLifecycleOwner) {
            pollLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(1_000)
                    refreshTick++
                }
            }
        }

        // ⚠️ 不能在组合期做阻塞型 IPC：Shizuku.pingBinder() 是同步 Binder 事务、
        // getPackageInfo() 是跨进程包查询。旧实现在 composition 里以 2Hz 直接执行，
        // 是投屏页掉帧/ANR 的直接原因。改为 IO 上低频采样。
        val envLifecycleOwner = LocalLifecycleOwner.current
        val projectionEnv by produceState(Triple(false, false, false)) {
            envLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    value = withContext(Dispatchers.IO) {
                        val ready = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                        val authorized = ready && runCatching {
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        }.getOrDefault(false)
                        val installed = runCatching {
                            context.packageManager.getPackageInfo(AMAP_AUTO_PACKAGE, 0)
                        }.isSuccess
                        Triple(ready, authorized, installed)
                    }
                    delay(2_000)
                }
            }
        }
        val shizukuReady = projectionEnv.first
        val shizukuAuthorized = projectionEnv.second
        val amapAutoInstalled = projectionEnv.third
        val projectionRunning = refreshTick.let { ProjectionState.running }
        val accessUrl = refreshTick.let { ProjectionState.accessUrl }
        val projectionStatus = refreshTick.let { ProjectionState.status }
        val virtualIpReady = refreshTick.let { TeslaVirtualIpService.isReady() }
        val permanentlyUnlocked = refreshTick.let { ProjectionPermanentUnlock.isActive(context) }
        val startProjection: () -> Unit = {
            val trialMode = !permanentlyUnlocked
            when {
                !shizukuReady -> showHelp = true
                !shizukuAuthorized -> Shizuku.requestPermission(shizukuRequestCode)
                !amapAutoInstalled -> showHelp = true
                mode == ConnectionMode.LAN -> ProjectionService.start(context, ConnectionMode.LAN, trialMode)
                VpnService.prepare(context) != null -> {
                    pendingProjectionTrialMode = trialMode
                    vpnPermissionLauncher.launch(VpnService.prepare(context))
                }
                else -> startTeslaProjectionFromDashboard(context, trialMode)
            }
            refreshTick++
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.9f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0F2A3B).copy(alpha = 0.6f), Surface800.copy(alpha = 0.2f)),
                        ),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF0EA5E9).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF67E8F9),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("高德车机版投屏", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (mode == ConnectionMode.LAN) {
                                "局域网浏览器投送与触摸反控"
                            } else {
                                "特斯拉热点专用地址投送与触摸反控"
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { showHelp = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "帮助",
                            tint = Color(0xFF93C5FD),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("帮助", color = Color(0xFF93C5FD), fontSize = 12.sp)
                    }
                    TextButton(onClick = onOpenMembership) {
                        Text("会员", color = Color(0xFF93C5FD), fontSize = 12.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0B1220).copy(alpha = 0.62f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("运行状态", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        ProjectionCheckRow(
                            label = "工作模式",
                            detail = if (mode == ConnectionMode.LAN) {
                                "局域网模式（默认）"
                            } else if (virtualIpReady) {
                                "特斯拉模式 · 专用地址已就绪"
                            } else {
                                "特斯拉模式 · 启动时将申请 VPN"
                            },
                            ready = mode == ConnectionMode.LAN || virtualIpReady,
                        )
                        ProjectionCheckRow(
                            label = "Android 版本",
                            detail = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                            ready = supported,
                        )
                        ProjectionCheckRow(
                            label = "Shizuku / Stellar",
                            detail = when {
                                !shizukuReady -> "未运行"
                                !shizukuAuthorized -> "已运行，尚未授权"
                                else -> "已授权"
                            },
                            ready = shizukuAuthorized,
                        )
                        ProjectionCheckRow(
                            label = "高德车机版",
                            detail = if (amapAutoInstalled) "已安装" else "未安装（$AMAP_AUTO_PACKAGE）",
                            ready = amapAutoInstalled,
                        )
                        ProjectionCheckRow(
                            label = "投屏服务",
                            detail = accessUrl ?: projectionStatus,
                            ready = projectionRunning,
                        )
                    }
                }
                Text(
                    if (permanentlyUnlocked) {
                        "永久投屏已解锁 · 不受每日 45 分钟限制"
                    } else {
                        "今日剩余试用 ${formatTrialDuration(DailyProjectionTrial.remainingMillis(context))}（每日共 45 分钟）"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = startProjection,
                        enabled = supported && !projectionRunning &&
                            (permanentlyUnlocked || DailyProjectionTrial.remainingMillis(context) > 0L),
                        modifier = Modifier.weight(1.1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("投屏")
                    }
                    OutlinedButton(
                        onClick = {
                            mode = if (mode == ConnectionMode.LAN) {
                                ConnectionMode.TESLA
                            } else {
                                ConnectionMode.LAN
                            }
                            ConnectionMode.save(context, mode)
                        },
                        enabled = !projectionRunning,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mode == ConnectionMode.LAN) Color(0xFF38BDF8) else Color(0xFFF97316),
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(if (mode == ConnectionMode.LAN) "局网" else "特斯拉")
                    }
                    Button(
                        onClick = {
                            ProjectionService.stop(context)
                            TeslaVirtualIpService.stop(context)
                            refreshTick++
                        },
                        enabled = projectionRunning || virtualIpReady,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface600,
                            disabledContainerColor = Surface700,
                        ),
                        modifier = Modifier.weight(1.1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("停止")
                    }
                }
            }
        }

        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                title = { Text("地图导航投屏 · 环境检查") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProjectionCheckRow(
                            label = "Android 版本",
                            detail = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                            ready = supported,
                        )
                        ProjectionCheckRow(
                            label = "Shizuku / Stellar",
                            detail = when {
                                !shizukuReady -> "未运行"
                                !shizukuAuthorized -> "已运行，尚未授权"
                                else -> "已授权"
                            },
                            ready = shizukuAuthorized,
                        )
                        ProjectionCheckRow(
                            label = "投屏地图",
                            detail = if (amapAutoInstalled) {
                                "高德车机版已安装"
                            } else {
                                "未安装（$AMAP_AUTO_PACKAGE）"
                            },
                            ready = amapAutoInstalled,
                        )
                        ProjectionCheckRow(
                            label = "工作模式",
                            detail = if (mode == ConnectionMode.LAN) {
                                "局域网模式（默认）"
                            } else if (virtualIpReady) {
                                "特斯拉模式 · 专用地址已就绪"
                            } else {
                                "特斯拉模式 · 启动时将申请 VPN"
                            },
                            ready = mode == ConnectionMode.LAN || virtualIpReady,
                        )
                        ProjectionCheckRow(
                            label = "投屏服务",
                            detail = accessUrl ?: ProjectionState.status,
                            ready = projectionRunning,
                        )
                        HorizontalDivider(color = Surface600.copy(alpha = 0.7f))
                        Text("使用步骤", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("1. 安装高德车机版。")
                        Text("2. 启动 Shizuku 或 Stellar，并在本页授权。")
                        Text("特权服务官方下载", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    openOfficialDownload("https://shizuku.rikka.app/download/")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("下载 Shizuku")
                            }
                            TextButton(
                                onClick = {
                                    openOfficialDownload(
                                        "https://github.com/roro2239/Stellar/releases",
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("下载 Stellar")
                            }
                        }
                        Text(
                            if (mode == ConnectionMode.LAN) {
                                "3. 与浏览器设备连接相同局域网；开始投屏后按本卡片显示的地址访问。"
                            } else {
                                "3. 手机开启热点，Tesla 连接热点；允许 VPN 后在车机浏览器打开 http://99.99.99.99:8080/。"
                            },
                        )
                        Text("画面与触控只在本地网络传输，不经过公网。", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHelp = false }) { Text("知道了") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    ) {
                        Text("通知设置")
                    }
                },
            )
        }

    }

    @Composable
    private fun ProjectionMembershipDialog(
        membership: ProjectionMembership?,
        selectedPlanCode: String?,
        message: String?,
        pendingOrderNo: String?,
        onDismiss: () -> Unit,
        onSelectPlan: (String) -> Unit,
        onStartTrial: () -> Unit,
        onCreateOrder: (ProjectionPlan) -> Unit,
        onRefreshOrder: () -> Unit,
        onCreateTransferCode: () -> Unit,
        onCreateRecoveryCode: () -> Unit,
        onTransferLicense: (String, Boolean) -> Unit,
        onRedeemPermanentUnlock: (String) -> Unit,
    ) {
        var migrationCode by remember { mutableStateOf("") }
        var permanentUnlockCode by remember { mutableStateOf("") }
        val entitlement = membership?.entitlement
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("投屏会员") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (entitlement?.enabled == true) {
                        Text(
                            if (entitlement.isLifetime) "投屏会员已永久开通"
                            else "有效至 ${entitlement.expiresAt?.substringBefore("T")}",
                            color = Color(0xFF22C55E),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onCreateTransferCode) { Text("生成换机码") }
                            TextButton(onClick = onCreateRecoveryCode) { Text("生成恢复码") }
                        }
                    } else {
                        Text("未开通时每日可试用 45 分钟；开通后投屏不限时。", color = TextSecondary)
                        membership?.plans?.forEach { plan ->
                            val selected = selectedPlanCode == plan.code
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) Color(0xFF1D4F91).copy(alpha = 0.35f)
                                    else Surface700,
                                ),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onSelectPlan(plan.code)
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (selected) "◉" else "○", color = Color(0xFF93C5FD), fontSize = 20.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(plan.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            plan.durationDays?.let { "$it 天投屏权益" } ?: "一次购买，长期使用",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Text(
                                        "¥%.2f".format(plan.priceCents / 100.0),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        Text("支付方式：支付宝", color = TextSecondary, fontSize = 12.sp)
                        OutlinedTextField(
                            value = migrationCode,
                            onValueChange = { migrationCode = it },
                            label = { Text("已有换机码或恢复码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = migrationCode.isNotBlank(),
                                onClick = { onTransferLicense(migrationCode, false) },
                            ) { Text("迁移会员") }
                            TextButton(
                                enabled = migrationCode.isNotBlank(),
                                onClick = { onTransferLicense(migrationCode, true) },
                            ) { Text("恢复会员") }
                        }
                        OutlinedTextField(
                            value = permanentUnlockCode,
                            onValueChange = { permanentUnlockCode = it },
                            label = { Text("永久解锁码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            enabled = permanentUnlockCode.isNotBlank(),
                            onClick = { onRedeemPermanentUnlock(permanentUnlockCode) },
                        ) {
                            Text("永久解除 45 分钟限制")
                        }
                    }
                    pendingOrderNo?.let {
                        TextButton(onClick = onRefreshOrder) { Text("我已完成支付") }
                    }
                    message?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                if (entitlement?.enabled != true) {
                    val selectedPlan = membership?.plans?.firstOrNull { it.code == selectedPlanCode }
                    Button(
                        enabled = selectedPlan != null,
                        onClick = { selectedPlan?.let(onCreateOrder) },
                    ) {
                        Text(selectedPlan?.let { "支付 ${"¥%.2f".format(it.priceCents / 100.0)}" } ?: "加载套餐中")
                    }
                }
            },
            dismissButton = {
                Row {
                    if (entitlement?.enabled != true) {
                        TextButton(onClick = onStartTrial) { Text("开始今日试用") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            },
        )
    }

    @Composable
    private fun ProjectionCheckRow(label: String, detail: String, ready: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (ready) Color(0xFF22C55E) else Color(0xFFFBBF24)),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }

    private val shizukuRequestCode = 4101
    private val teslaVpnReadyDelayMillis = 500L
    private val destinationTriggerThreshold = 0.85f

    private data class DestinationNavigationResult(
        val accepted: Boolean,
        val message: String,
    )

    private fun startTeslaProjectionFromDashboard(
        context: Context,
        trialMode: Boolean = true,
    ) {
        TeslaVirtualIpService.start(context)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            {
                if (TeslaVirtualIpService.isReady()) {
                    ProjectionService.start(context, ConnectionMode.TESLA, trialMode)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "特斯拉专用地址创建失败，请关闭其他 VPN 后重试",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
            teslaVpnReadyDelayMillis,
        )
    }

    private companion object {
        const val AMAP_AUTO_PACKAGE = "com.autonavi.amapauto"

        private fun formatTrialDuration(remainingMillis: Long): String {
            val remainingMinutes = (remainingMillis + 59_999L) / 60_000L
            return "${remainingMinutes} 分钟"
        }
    }
}
