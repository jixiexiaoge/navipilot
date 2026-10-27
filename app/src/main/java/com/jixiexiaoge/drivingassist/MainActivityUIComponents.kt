package com.jixiexiaoge.drivingassist

import androidx.compose.ui.res.stringResource

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import com.jixiexiaoge.drivingassist.CustomIcons
import kotlinx.coroutines.launch
import com.jixiexiaoge.drivingassist.ui.utils.localized
import com.jixiexiaoge.drivingassist.ui.theme.*
import androidx.compose.runtime.*

/**
 * MainActivity UI组件 - 辅助组件和工具函数
 * 包含车辆控制按钮、高阶功能弹窗、导航相关函数等
 */
object MainActivityUIComponents {

    private const val AMAP_AUTO_PKG = "com.autonavi.amapauto"
    /** 冷启动后车机进程需要时间注册广播接收器，略延迟再发导航广播 */
    private const val AMAP_AUTO_BROADCAST_DELAY_MS = 320L

    /**
     * 拉起高德地图车机版主界面（与 [MainActivityCore.launchAmapAuto] 策略一致），便于未运行时仍能收到后续标准广播。
     */
    private fun tryLaunchAmapAutoApp(context: Context): Boolean {
        val appCtx = context.applicationContext
        try {
            val explicit = Intent().apply {
                setComponent(
                    ComponentName(AMAP_AUTO_PKG, "com.autonavi.auto.MainMapActivity")
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (explicit.resolveActivity(appCtx.packageManager) != null) {
                appCtx.startActivity(explicit)
                android.util.Log.i("MainActivity", "已拉起高德车机版 (MainMapActivity)")
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "显式启动高德车机版失败: ${e.message}")
        }
        return try {
            val li = appCtx.packageManager.getLaunchIntentForPackage(AMAP_AUTO_PKG) ?: return false
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(li)
            android.util.Log.i("MainActivity", "已拉起高德车机版 (getLaunchIntentForPackage)")
            true
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "隐式启动高德车机版失败: ${e.message}")
            false
        }
    }

    private fun sendAmapAutoBroadcastAfterLaunch(
        context: Context,
        launchedAmap: Boolean,
        broadcast: Intent,
        successLog: String
    ) {
        val appCtx = context.applicationContext
        fun sendNow() {
            try {
                appCtx.sendBroadcast(broadcast)
                android.util.Log.i("MainActivity", successLog)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "❌ 高德车机广播发送失败: ${e.message}", e)
            }
        }
        if (launchedAmap) {
            Handler(Looper.getMainLooper()).postDelayed({ sendNow() }, AMAP_AUTO_BROADCAST_DELAY_MS)
        } else {
            sendNow()
        }
    }
    
    /**
     * 高级功能内容（九宫格 + 超车参数）— 可同时用于弹窗和主页卡片
     */
    @Composable
    fun AdvancedFunctionsContent(
        onSendCommand: (String, String) -> Unit,
        onSendRoadLimitSpeed: () -> Unit,
        onLaunchAmap: () -> Unit,
        onSendNavConfirmation: () -> Unit,
        onPageChange: (Page) -> Unit,
        isOpenpilotActive: Boolean,
        carrotManFields: CarrotManFields,
        networkManager: NetworkManager? = null,
        context: android.content.Context,
        onDismiss: (() -> Unit)? = null,
        onShow7706Debug: (() -> Unit)? = null,  // 7706 调试面板回调
        onAlwaysLateralClick: () -> Unit = {},   // 横向控制模式切换 (AlwaysLateral)
        onExperimentClick: () -> Unit = onAlwaysLateralClick, // 兼容实验模式回调
        isAlwaysLateralEnabled: Boolean? = null, // 横向控制状态
        onShareDataClick: () -> Unit = {},       // 数据分享切换
        isShareDataEnabled: Boolean? = null,     // 数据分享状态
        trafficLightState: Int = -1,            // 红绿灯状态
        trafficLightCountdown: Int = 0,         // 红绿灯倒计时
        trafficLightDir: Int = 0,               // 红绿灯方向
        automationObservation: com.jixiexiaoge.drivingassist.data.AutomationObservation =
            com.jixiexiaoge.drivingassist.data.AutomationObservation.Disconnected,
    ) {
        fun playSound(resourceId: Int, soundName: String) {
            try {
                MediaPlayer.create(context, resourceId)?.apply {
                    setOnCompletionListener { release() }
                    setOnErrorListener { _, what, extra ->
                        android.util.Log.e("MainActivity", "❌ 音频播放错误($soundName): what=$what, extra=$extra")
                        release()
                        true
                    }
                    start()
                    android.util.Log.d("MainActivity", "🔊 开始播放${soundName}提示音")
                } ?: android.util.Log.w("MainActivity", "⚠️ 无法创建音频播放器($soundName)")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "❌ 播放${soundName}提示音失败: ${e.message}", e)
            }
        }

        var speedControlMode by remember {
            mutableStateOf(
                context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
                    .getInt("speed_from_pcm_mode", 0)
            )
        }
        var isSpeedModeLoading by remember { mutableStateOf(false) }

        val overtakePrefs = remember(context) {
            context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
        }
        var overtakeMode by remember(overtakePrefs) {
            mutableStateOf(
                overtakePrefs.getInt("overtake_mode", 1)
                    // 旧版本/备份恢复可能写入越界值，索引 Names/Colors 前必须夹紧，否则组合期直接崩
                    .coerceIn(0, 2)
            )
        }
        val leftGuidanceKey = com.jixiexiaoge.drivingassist.navigation.LeftGuidancePolicy.PREFERENCE_KEY
        var filterLeftGuidance by remember(overtakePrefs) {
            mutableStateOf(overtakePrefs.getBoolean(leftGuidanceKey, true))
        }
        DisposableEffect(overtakePrefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "overtake_mode") overtakeMode = prefs.getInt(key, 1).coerceIn(0, 2)
                if (key == leftGuidanceKey) filterLeftGuidance = prefs.getBoolean(key, true)
            }
            overtakePrefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { overtakePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        var isOvertakeModeLoading by remember { mutableStateOf(false) }

        val coroutineScope = rememberCoroutineScope()

        Column {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface800.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                val cols = 3
                val controlIds = listOf(3, 4, 6, 11, 12, 13, 9, 14, 15)
                val rows = controlIds.size / cols
                val gridSpacing = 8.dp
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(gridSpacing)
                ) {
                    for (row in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                        ) {
                            for (col in 0 until cols) {
                                when (controlIds[row * cols + col]) {
                            3 -> {
                                val overtakeModeNames = arrayOf(
                                    localized("超车：关闭", "Overtake: off"),
                                    localized("超车：拨杆", "Overtake: signal"),
                                    localized("超车：自动", "Overtake: auto"),
                                )
                                Button(
                                    onClick = {
                                        if (!isOvertakeModeLoading) {
                                            isOvertakeModeLoading = true
                                            coroutineScope.launch {
                                                val nextMode = (overtakeMode + 1) % 3
                                                overtakePrefs.edit().putInt("overtake_mode", nextMode).apply()
                                                kotlinx.coroutines.delay(300)
                                                isOvertakeModeLoading = false
                                                if (nextMode != 0) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        localized(
                                                            "已保存超车偏好，未启用App自动变道：7000缺少完整控制授权；手动左右变道请求不受此配置影响",
                                                            "Overtake preference saved; App automation unavailable " +
                                                                "without complete control authority",
                                                        ),
                                                        android.widget.Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-3").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isOvertakeModeLoading) Surface500 else Surface700),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp),
                                    enabled = !isOvertakeModeLoading
                                ) {
                                    Text(if (isOvertakeModeLoading) localized("保存\n中...", "Saving...") else
                                        overtakeModeNames[overtakeMode] + "\n" +
                                            if (overtakeMode == 0) localized("点击切换", "Tap to change")
                                            else localized("仅保存·未启用", "Saved · not enabled"),
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                                }
                            }
                            4 -> {
                                Button(
                                    onClick = { playSound(R.raw.left, "左变道"); onSendCommand("LANECHANGE", "LEFT"); onDismiss?.invoke() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-4").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = ButtonLaneChange),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Default.KeyboardArrowLeft, "左变道", Modifier.size(24.dp), tint = Color.White)
                                        Text(localized("变道", "Lane"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                            6 -> {
                                Button(
                                    onClick = { playSound(R.raw.right, "右变道"); onSendCommand("LANECHANGE", "RIGHT"); onDismiss?.invoke() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-6").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = ButtonLaneChange),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Default.KeyboardArrowRight, "右变道", Modifier.size(24.dp), tint = Color.White)
                                        Text(localized("变道", "Lane"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                            9 -> {
                                Button(
                                    onClick = { onPageChange(Page.Experiment); onDismiss?.invoke() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-9").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = ButtonExperiment),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(CustomIcons.BugReport, "实验", Modifier.size(24.dp), tint = Color.White)
                                        Text(localized("实验", "Exp"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                            11 -> {
                                // ⛔ NOA 路线速度预调
                                val limit = carrotManFields.nRoadLimitSpeed
                                Button(
                                    onClick = { onPageChange(Page.SmartSpeed); onDismiss?.invoke() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-11").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF78350F)),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(if (limit > 0) "$limit" else "--", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFCD34D))
                                        Text(localized("预调", "Prep"), fontSize = 12.sp, color = Color(0xFFFDE68A))
                                    }
                                }
                            }
                            12 -> {
                                val cruise = carrotManFields.vCruiseKph.toInt()
                                Button(
                                    onClick = {
                                        val ip = networkManager?.getCurrentDeviceIP()
                                        if (ip == null) {
                                            android.widget.Toast.makeText(context, localized("请先连接 comma 设备", "Connect a comma device first"), android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip:7000"))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                onDismiss?.invoke()
                                            } catch (e: android.content.ActivityNotFoundException) {
                                                android.util.Log.e("MainActivity", "无法打开车端网页", e)
                                                android.widget.Toast.makeText(context, localized("未找到可打开网页的应用", "No browser available"), android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-12").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14532D)),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("$cruise", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                        Text(localized("巡航", "Cruise"), fontSize = 12.sp, color = Color(0xFFBBF7D0))
                                    }
                                }
                            }
                            13 -> {
                                // ↔️ 横向控制切换（AlwaysLateral 0/1）
                                val isLateral = isAlwaysLateralEnabled ?: isOpenpilotActive
                                Button(
                                    onClick = { onAlwaysLateralClick() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-13").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isLateral) Color(0xFF6D28D9) else Color(0xFF334155)),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("↔️", fontSize = 18.sp)
                                        Text(localized("横向", "Lateral"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isLateral) Color.White else Color.White.copy(alpha = 0.55f))
                                    }
                                }
                            }
                            14 -> {
                                // 投屏入口：红绿灯数据已整合到顶部导航卡片。
                                Button(
                                    onClick = { onPageChange(Page.Projection); onDismiss?.invoke() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-14").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("📺", fontSize = 18.sp)
                                        Text(localized("投屏", "Cast"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    }
                                }
                            }
                            15 -> {
                                // 📤 数据分享
                                val isSharing = isShareDataEnabled == true
                                Button(
                                    onClick = { onShareDataClick() },
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp)
                                        .testTag("home-control-15").shadow(2.dp, RoundedCornerShape(16.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSharing) Color(0xFF15803D) else Color(0xFF334155)),
                                    contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("📤", fontSize = 16.sp)
                                        Text(text = if (isSharing) localized("分发", "On") else localized("分享", "Share"),
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isSharing) Color.White else Color.White.copy(alpha = 0.55f))
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
                } // Column
            } // Card

            com.jixiexiaoge.drivingassist.ui.components.overtakeConfigurationStatus(overtakeMode, automationObservation)

            // 超车参数调节区域
            Column(Modifier.testTag("overtake-parameters")) {
                Spacer(Modifier.height(2.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Surface900.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        localized("超车参数 · 所有模式仅保存配置", "Overtake settings · all modes save configuration only"),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        localized(
                            "自动请求未启用；激活状态、未检出目标或车道估计不能补齐控制授权。手动左右变道仅发送请求，入队不代表执行。",
                            "Automatic requests are unavailable. Active state, no detection and lane estimates do not grant authority. Manual lane-change enqueue is not execution.",
                        ),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(localized("高速/快速路：过滤左侧分岔引导", "Highway: filter left-fork guidance"),
                                color = Color.White, fontSize = 14.sp)
                            Text(localized(
                                "默认开启；真实左出口也不传车辆。道路未知时继续拦截；手机提示与手动变道保留，不判断本车第几车道。",
                                "Also blocks real left exits and unknown-road left forks. Phone guidance and manual requests remain; no ego-lane assumption.",
                            ), color = TextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = filterLeftGuidance,
                            onCheckedChange = { value ->
                                overtakePrefs.edit().putBoolean(leftGuidanceKey, value).apply()
                                filterLeftGuidance = value
                            },
                            modifier = Modifier.testTag("left-guidance-filter"),
                        )
                    }
                    OvertakeParameterRow(localized("最小超车速度", "Min Overtake Speed"), "", 70f, 50f, 120f, 5f, "overtake_param_min_speed_kph", context)
                    OvertakeParameterRow(localized("速度差阈值", "Speed Diff Threshold"), "", 10f, 5f, 30f, 1f, "overtake_param_speed_diff_kph", context)
                }
                }
            }
        }

    }

    @Composable
    fun AboutDialog(context: Context, aboutOpenTrigger: Int) {
        val aboutPrefs = remember { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) }
        val aboutAutoShownKey = "about_cp5_auto_shown"
        var showAboutDialog by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(!aboutPrefs.getBoolean(aboutAutoShownKey, false))
        }
        fun closeAboutDialog() {
            aboutPrefs.edit().putBoolean(aboutAutoShownKey, true).apply()
            showAboutDialog = false
        }
        LaunchedEffect(aboutOpenTrigger) {
            if (aboutOpenTrigger > 0) showAboutDialog = true
        }
        if (showAboutDialog) {
            Dialog(onDismissRequest = { closeAboutDialog() }) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp).heightIn(max = 680.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = DialogBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(color = Color(0xFF0F766E), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("🚗 ${stringResource(R.string.app_name)}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    modifier = Modifier.testTag("about-app-version"),
                                    fontSize = 14.sp, color = Color.White)
                                Text(localized("免费给所有 CP 用户使用", "Free for all CP users"), fontSize = 14.sp, color = Color(0xFFCCFBF1), fontWeight = FontWeight.SemiBold)
                                Text(localized("支持已经适配的 SP / openpilot 分支", "Supports adapted SP / openpilot forks"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.82f))
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().clickable { closeAboutDialog() }.padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                            Image(painter = painterResource(id = R.drawable.sponsor), contentDescription = localized("赞助图片", "Sponsor image"),
                                modifier = Modifier.fillMaxWidth(0.88f).heightIn(max = 150.dp), contentScale = ContentScale.Fit)
                        }
                        HorizontalDivider(color = Surface600)
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                            AboutFeatureItem("🗺️", localized("高德车机版导航联动", "AMap Auto Integration"))
                            AboutFeatureItem("🚦", localized("NOA 路线速度预调：高速/高架匝道平滑加减速", "NOA route speed preconditioning for highway/elevated ramps"))
                            AboutFeatureItem("📺", localized("高德车机版投屏到浏览器 / 特斯拉", "AMap Auto projection to browser / Tesla"))
                            AboutFeatureItem("🚗", localized("openpilot 驾驶辅助", "openpilot Driving Assist"))
                            AboutFeatureItem("📊", localized("车道感知与盲区监测", "Lane Awareness & Blindspot"))
                            AboutFeatureItem("🔄", localized("超车配置可保存 · App自动请求暂停", "Overtake configuration · App automatic requests paused"))
                            AboutFeatureItem("📤", localized("数据分享与分发", "Data Sharing & Distribution"))
                        }
                        Surface(color = Surface800.copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                localized(
                                    "亮点：CP搭子把高德车机导航、comma3 数据、投屏和 NOA 路线速度预调整合到一个驾驶辅助面板。路线预调只提供地图先验，最终速度与制动仍由 CP 决定。",
                                    "Highlights: CP Buddy combines AMap Auto navigation, comma3 data, projection, and NOA route speed preconditioning in one driving-assist panel. Route preconditioning is map context; CP remains responsible for final speed and braking."
                                ),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 7.9.53：「导出设置 / 恢复设置」两个入口已从关于页移除（功能与代码一并删除）
                        Button(onClick = { closeAboutDialog() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(44.dp)) {
                            Text(localized("知道了", "Got it"), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AboutFeatureItem(icon: String, text: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(icon, fontSize = 16.sp)
            Text(text, fontSize = 13.sp, color = TextSecondary)
        }
    }
    
    /**
     * 🆕 超车参数调节行组件
     * 显示参数名称、当前值，并提供加减按钮
     */
    @Composable
    private fun OvertakeParameterRow(
        label: String,
        unit: String,
        defaultValue: Float,
        minValue: Float,
        maxValue: Float,
        step: Float,
        prefKey: String,
        context: android.content.Context,
        displayMultiplier: Float = 1f  // 显示倍数（用于百分比等）
    ) {
        val prefs = remember { context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE) }
        var currentValue by remember { 
            mutableStateOf(prefs.getFloat(prefKey, defaultValue).coerceIn(minValue, maxValue))
        }
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            color = Surface800.copy(alpha = 0.82f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = localized("点击两侧调整，立即生效", "Tap sides to adjust"),
                        fontSize = 11.sp,
                        color = TextSecondary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            val newValue = (currentValue - step).coerceAtLeast(minValue)
                            currentValue = newValue
                            prefs.edit().putFloat(prefKey, newValue).apply()
                            android.util.Log.d("MainActivity", "🔧 调整参数 $label: $newValue")
                        },
                        modifier = Modifier.size(48.dp),
                        enabled = currentValue > minValue,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (currentValue > minValue) Color(0xFF334155) else Surface400,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Surface(
                        modifier = Modifier.width(58.dp).height(48.dp),
                        color = Color(0xFF0F766E).copy(alpha = 0.88f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${(currentValue * displayMultiplier).toInt()}${if (unit.isNotEmpty()) " $unit" else ""}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            val newValue = (currentValue + step).coerceAtMost(maxValue)
                            currentValue = newValue
                            prefs.edit().putFloat(prefKey, newValue).apply()
                            android.util.Log.d("MainActivity", "🔧 调整参数 $label: $newValue")
                        },
                        modifier = Modifier.size(48.dp),
                        enabled = currentValue < maxValue,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (currentValue < maxValue) Color(0xFF0EA5E9) else Surface400,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("+", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    
    /**
     * 发送回家导航指令给高德地图
     */
    fun sendHomeNavigationToAmap(context: android.content.Context) {
        try {
            android.util.Log.i("MainActivity", "🏠 发送一键回家指令给高德地图")
            val launched = tryLaunchAmapAutoApp(context)
            val homeIntent = android.content.Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10040)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("DEST", 0)
                putExtra("IS_START_NAVI", 0)
                setPackage(AMAP_AUTO_PKG)
                flags = android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            sendAmapAutoBroadcastAfterLaunch(
                context,
                launched,
                homeIntent,
                "✅ 一键回家导航广播已发送"
            )
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 发送一键回家指令失败: ${e.message}", e)
        }
    }
    
    /**
     * 发送导航到公司指令给高德地图
     */
    fun sendCompanyNavigationToAmap(context: android.content.Context) {
        try {
            android.util.Log.i("MainActivity", "🏢 发送导航到公司指令给高德地图")
            val launched = tryLaunchAmapAutoApp(context)
            val companyIntent = android.content.Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10040)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("DEST", 1)
                putExtra("IS_START_NAVI", 0)
                setPackage(AMAP_AUTO_PKG)
                flags = android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            sendAmapAutoBroadcastAfterLaunch(
                context,
                launched,
                companyIntent,
                "✅ 导航到公司广播已发送"
            )
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 发送导航到公司指令失败: ${e.message}", e)
        }
    }

    /**
     * 启动模拟导航功能
     */
    fun startSimulatedNavigation(context: android.content.Context, carrotManFields: CarrotManFields) {
        try {
            android.util.Log.i("MainActivity", "🔧 启动模拟导航功能")
            
            val currentLat = when {
                carrotManFields.vpPosPointLat != 0.0 -> {
                    android.util.Log.i("MainActivity", "✅ 使用实时GPS坐标（vpPosPointLat）: ${carrotManFields.vpPosPointLat}")
                    carrotManFields.vpPosPointLat
                }
                carrotManFields.latitude != 0.0 -> {
                    android.util.Log.i("MainActivity", "✅ 使用备用GPS坐标（latitude）: ${carrotManFields.latitude}")
                    carrotManFields.latitude
                }
                else -> {
                    val fallbackLat = getCurrentLocationLatitude(context)
                    android.util.Log.w("MainActivity", "⚠️ GPS坐标不可用，使用SharedPreferences坐标: $fallbackLat")
                    fallbackLat
                }
            }
            
            val currentLon = when {
                carrotManFields.vpPosPointLon != 0.0 -> {
                    android.util.Log.i("MainActivity", "✅ 使用实时GPS坐标（vpPosPointLon）: ${carrotManFields.vpPosPointLon}")
                    carrotManFields.vpPosPointLon
                }
                carrotManFields.longitude != 0.0 -> {
                    android.util.Log.i("MainActivity", "✅ 使用备用GPS坐标（longitude）: ${carrotManFields.longitude}")
                    carrotManFields.longitude
                }
                else -> {
                    val fallbackLon = getCurrentLocationLongitude(context)
                    android.util.Log.w("MainActivity", "⚠️ GPS坐标不可用，使用SharedPreferences坐标: $fallbackLon")
                    fallbackLon
                }
            }
            
            if (currentLat == 0.0 || currentLon == 0.0) {
                android.util.Log.w("MainActivity", "⚠️ GPS坐标无效，使用默认起点坐标（北京）")
                // 目的地：无锡硕放国际机场 (31.4944°N, 120.4290°E)
                sendSimulatedNavigationIntent(context, 39.9042, 116.4074, 31.4944, 120.4290)
                return
            }
            
            // 目的地：无锡硕放国际机场
            val destLat = 31.4944
            val destLon = 120.4290
            
            if (kotlin.math.abs(currentLat - destLat) < 0.001 && kotlin.math.abs(currentLon - destLon) < 0.001) {
                android.util.Log.w("MainActivity", "⚠️ 起点和终点坐标过于接近，调整目的地位置（使用苏州）")
                // 备用目的地：苏州市中心
                sendSimulatedNavigationIntent(context, currentLat, currentLon, 31.2989, 120.5853)
            } else {
                sendSimulatedNavigationIntent(context, currentLat, currentLon, destLat, destLon)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 启动模拟导航失败: ${e.message}", e)
        }
    }
    
    /**
     * 发送模拟导航Intent
     */
    private fun sendSimulatedNavigationIntent(
        context: android.content.Context,
        startLat: Double, 
        startLon: Double, 
        destLat: Double, 
        destLon: Double
    ) {
        try {
            val launched = tryLaunchAmapAutoApp(context)
            val intent = android.content.Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10076)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("EXTRA_SLAT", startLat)
                putExtra("EXTRA_SLON", startLon)
                putExtra("EXTRA_SNAME", localized("当前位置", "Current Location"))
                putExtra("EXTRA_DLAT", destLat)
                putExtra("EXTRA_DLON", destLon)
                putExtra("EXTRA_DNAME", localized("无锡硕放国际机场", "Wuxi Shuofang Intl Airport"))
                putExtra("EXTRA_DEV", 0)
                putExtra("EXTRA_M", 0)
                putExtra("KEY_RECYLE_SIMUNAVI", true)
                setPackage(AMAP_AUTO_PKG)
                flags = android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            sendAmapAutoBroadcastAfterLaunch(
                context,
                launched,
                intent,
                "✅ 模拟导航广播已发送给高德地图车机版"
            )
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 发送模拟导航广播失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取当前位置纬度
     */
    private fun getCurrentLocationLatitude(context: android.content.Context): Double {
        return try {
            val carrotPrefs = context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
            val devicePrefs = context.getSharedPreferences("device_prefs", android.content.Context.MODE_PRIVATE)
            // 🔧 修复：使用CoordinatePreferences读取坐标，避免Float精度损失
            var lat = com.jixiexiaoge.drivingassist.utils.CoordinatePreferences.getCoordinate(carrotPrefs, "vpPosPointLat", 0.0)
            if (lat == 0.0) {
                lat = com.jixiexiaoge.drivingassist.utils.CoordinatePreferences.getCoordinate(devicePrefs, "vpPosPointLat", 0.0)
            }
            if (lat != 0.0) {
                android.util.Log.i("MainActivity", "✅ 获取到当前位置纬度: $lat")
                lat
            } else {
                android.util.Log.w("MainActivity", "⚠️ 未找到当前位置，使用默认起点坐标（北京）")
                39.9042
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 获取当前位置纬度失败: ${e.message}", e)
            39.9042
        }
    }
    
    /**
     * 获取当前位置经度
     */
    private fun getCurrentLocationLongitude(context: android.content.Context): Double {
        return try {
            val carrotPrefs = context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
            val devicePrefs = context.getSharedPreferences("device_prefs", android.content.Context.MODE_PRIVATE)
            // 🔧 修复：使用CoordinatePreferences读取坐标，避免Float精度损失
            var lon = com.jixiexiaoge.drivingassist.utils.CoordinatePreferences.getCoordinate(carrotPrefs, "vpPosPointLon", 0.0)
            if (lon == 0.0) {
                lon = com.jixiexiaoge.drivingassist.utils.CoordinatePreferences.getCoordinate(devicePrefs, "vpPosPointLon", 0.0)
            }
            if (lon != 0.0) {
                android.util.Log.i("MainActivity", "✅ 获取到当前位置经度: $lon")
                lon
            } else {
                android.util.Log.w("MainActivity", "⚠️ 未找到当前位置，使用默认起点坐标（北京）")
                116.4074
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 获取当前位置经度失败: ${e.message}", e)
            116.4074
        }
    }
}
