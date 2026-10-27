package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.CustomIcons
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 条件实验模式页面 (Conditional Experimental Mode)
 * 配置在特定条件下自动切换到实验模式的功能
 * 
 * Current compact telemetry does not authorize automatic mode switching.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoSwitchExperimentPage(
    onBack: () -> Unit,
    conditionalExperimentManager: com.jixiexiaoge.drivingassist.ConditionalExperimentManager? = null,
    carrotParamClient: com.jixiexiaoge.drivingassist.CarrotParamClient? = null,
    carrotManFields: androidx.compose.runtime.State<com.jixiexiaoge.drivingassist.CarrotManFields>? = null,
    automationObservation: com.jixiexiaoge.drivingassist.data.AutomationObservation =
        com.jixiexiaoge.drivingassist.data.AutomationObservation.Disconnected,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    
    // 主开关
    var cemEnabled by remember { mutableStateOf(prefs.getBoolean("cem_enabled", false)) }

    // 1. 弯道检测
    var ceCurvesEnabled by remember { mutableStateOf(prefs.getBoolean("ce_curves", true)) }
    var ceCurvesSpeed by remember { mutableStateOf(prefs.getInt("ce_curves_speed", 40)) }
    var ceCurvesCurvature by remember { mutableStateOf(prefs.getInt("ce_curves_curvature", 5)) }
    var ceCurvesLead by remember { mutableStateOf(prefs.getBoolean("ce_curves_lead", false)) }
    
    // 2. 前车检测
    var ceLeadEnabled by remember { mutableStateOf(prefs.getBoolean("ce_lead", true)) }
    var ceSlowerLead by remember { mutableStateOf(prefs.getBoolean("ce_slower_lead", true)) }
    var ceStoppedLead by remember { mutableStateOf(prefs.getBoolean("ce_stopped_lead", true)) }
    var ceLeadDist by remember { mutableStateOf(prefs.getInt("ce_lead_dist", 80)) }
    var ceLeadSpeedDiff by remember { mutableStateOf(prefs.getInt("ce_lead_speed_diff", 7)) }
    
    // 3. 低速条件
    var ceSpeedEnabled by remember { mutableStateOf(prefs.getBoolean("ce_speed_enabled", true)) }
    var ceSpeed by remember { mutableStateOf(prefs.getInt("ce_speed", 30)) }
    var ceSpeedLead by remember { mutableStateOf(prefs.getInt("ce_speed_lead", 20)) }
    
    // 4. 导航转弯条件（改用7705 tbtDist）
    var ceNavigationEnabled by remember { mutableStateOf(prefs.getBoolean("ce_navigation", true)) }
    var ceNavTurnDistance by remember { mutableStateOf(prefs.getInt("ce_nav_turn_distance", 200)) }
    var ceNavLead by remember { mutableStateOf(prefs.getBoolean("ce_nav_lead", true)) }
    
    // 5. 测速点条件（新增，使用7705 sdiDist）
    var ceSdiEnabled by remember { mutableStateOf(prefs.getBoolean("ce_sdi", false)) }
    var ceSdiDistance by remember { mutableStateOf(prefs.getInt("ce_sdi_distance", 300)) }
    
    // 6. 驾驶状态条件（新增，使用7705 xState）
    var ceXStateEnabled by remember { mutableStateOf(prefs.getBoolean("ce_xstate", false)) }
    var ceXStateLead by remember { mutableStateOf(prefs.getBoolean("ce_xstate_lead", false)) }
    var ceXStateStop by remember { mutableStateOf(prefs.getBoolean("ce_xstate_stop", true)) }
    var ceXStateStopped by remember { mutableStateOf(prefs.getBoolean("ce_xstate_stopped", true)) }
    var ceXStatePrepare by remember { mutableStateOf(prefs.getBoolean("ce_xstate_prepare", false)) }
    
    // 7. 巡航速度调整条件（限速变化时临时切换）
    var ceCruiseAdjEnabled by remember { mutableStateOf(prefs.getBoolean("ce_cruise_adj", false)) }
    var ceCruiseTolerance by remember { mutableStateOf(prefs.getInt("ce_cruise_tolerance", 5)) }
    var ceCruiseTimeout by remember { mutableStateOf(prefs.getInt("ce_cruise_timeout", 30)) }
    var ceCruiseMinLimit by remember { mutableStateOf(prefs.getInt("ce_cruise_min_limit", 80)) }
    var ceCruiseMaxLimit by remember { mutableStateOf(prefs.getInt("ce_cruise_max_limit", 120)) }
    
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = localized("条件实验模式", "Conditional Experimental Mode"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = CustomIcons.ArrowBack,
                            contentDescription = localized("返回", "Back")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        AdaptivePageColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .padding(paddingValues),
        ) {
            // 功能说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "ℹ️", fontSize = 20.sp)
                        Text(
                            text = localized(
                                "本页只配置条件，当前不执行自动切换；保存设置不修改车辆参数",
                                "Configure conditions only; automatic switching is unavailable. " +
                                    "Saving does not write vehicle parameters."
                            ),
                            fontSize = 12.sp,
                            color = Color(0xFFE5E7EB),
                            lineHeight = 16.sp
                        )
                    }
                    Text(
                        text = "📡 " + localized(
                            "7000 compact缺少完整控制授权；激活标记不代表自动功能可用",
                            "7000 compact lacks complete control authority; an active flag does not enable automation"
                        ),
                        fontSize = 10.sp,
                        color = Color(0xFF60A5FA),
                        lineHeight = 14.sp
                    )
                }
            }
            
            // 7705 active 状态 + 实时数据
            // 车端 20Hz 更新：直接读 .value 会让本页（975 行 + 十余个 Slider/Switch）
            // 每秒重组约 20 次。用 derivedStateOf 收敛 —— 只有这些值真的变化时才触发重组。
            val live = remember(carrotManFields) {
                derivedStateOf { carrotManFields?.value ?: com.jixiexiaoge.drivingassist.CarrotManFields() }
            }
            val currentTbtDist = live.value.nTBTDist
            val currentSdiDist = live.value.nSdiDist
            val currentXState = live.value.xState
            val currentVCruise = live.value.vCruiseKph
            val currentCarCruise = live.value.carcruiseSpeed
            val currentRoadLimit = live.value.nRoadLimitSpeed
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    automationCapabilityNotice(automationObservation, experimental = true)
                    Text(localized("下方旧配置参考值不保证新鲜，不作为运行或授权依据",
                        "Legacy configuration references below may be stale; " +
                            "they are not runtime or authority evidence"),
                        color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
            
            // 主开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = localized("条件实验配置开关（非运行开关）", "CEM configuration (not runtime enable)"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Switch(
                            modifier = Modifier.testTag("cem-config-switch"),
                            checked = cemEnabled,
                            onCheckedChange = { cemEnabled = it; hasUnsavedChanges = true },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF475569)
                            )
                        )
                    }
                    
                    HorizontalDivider(color = Color(0xFF475569), thickness = 1.dp)

                    Text(localized("手动切换车辆模式（与自动配置无关）",
                        "Manual vehicle mode changes (separate from automatic configuration)"),
                        color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    if (carrotParamClient != null) {
                                        try {
                                            val result = carrotParamClient.setExperimentalMode(true)
                                            val msg = if (result.isSuccess) "🧪 " + localized("已切换实验模式", "Exp Mode ON")
                                                else "❌ ${result.exceptionOrNull()?.message}"
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "❌ ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        android.widget.Toast.makeText(context, "⚠️ " + localized("设备未连接", "Not connected"), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(text = "🧪 " + localized("手动实验", "Manual Exp"), fontSize = 12.sp)
                        }
                        
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    if (carrotParamClient != null) {
                                        try {
                                            val result = carrotParamClient.setExperimentalMode(false)
                                            val msg = if (result.isSuccess) "😌 " + localized("已切换Chill模式", "Chill Mode ON")
                                                else "❌ ${result.exceptionOrNull()?.message}"
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "❌ ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        android.widget.Toast.makeText(context, "⚠️ " + localized("设备未连接", "Not connected"), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(text = "😌 " + localized("手动Chill", "Manual Chill"), fontSize = 12.sp)
                        }
                    }
                }
            }
            
            // 条件参数配置
            if (cemEnabled) {
                AdaptiveCardGrid {
                
                    // ===== 条件1: 弯道检测 =====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("弯道检测", "Curve Detection"),
                        icon = "🛣️",
                        enabled = ceCurvesEnabled,
                        onEnabledChange = { ceCurvesEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: ModelV2 curvature.maxOrientationRate\n" +
                            "曲率(rad/s): 0.02=缓弯 0.05=中弯(≈200m半径) 0.10=急弯(≈100m半径)\n" +
                            "正值=左转 负值=右转，取绝对值判断",
                            "Source: ModelV2 curvature.maxOrientationRate\n" +
                            "Curvature: 0.02=gentle 0.05=medium(≈200m) 0.10=sharp(≈100m)\n" +
                            "Positive=left, negative=right, uses absolute value"
                        )
                    ) {
                        // 曲率阈值（新增）
                        ParameterSlider(
                            label = localized("曲率阈值", "Curvature Threshold"),
                            value = ceCurvesCurvature,
                            unit = "×0.01",
                            range = 2f..15f,
                            step = 1,
                            onValueChange = { ceCurvesCurvature = it; hasUnsavedChanges = true },
                            recommended = localized("推荐5(=0.05)", "rec:5(=0.05)")
                        )
                        // 曲率说明
                        Text(
                            text = localized(
                                "当前: ${ceCurvesCurvature/100f} rad/s ≈ ${(1.0/(ceCurvesCurvature/100f)).toInt()}m半径弯道",
                                "Current: ${ceCurvesCurvature/100f} rad/s ≈ ${(1.0/(ceCurvesCurvature/100f)).toInt()}m radius"
                            ),
                            fontSize = 10.sp,
                            color = Color(0xFF60A5FA)
                        )
                        ParameterSlider(
                            label = localized("触发速度上限", "Max Trigger Speed"),
                            value = ceCurvesSpeed,
                            unit = "km/h",
                            range = 20f..80f,
                            step = 5,
                            onValueChange = { ceCurvesSpeed = it; hasUnsavedChanges = true },
                            recommended = localized("推荐40", "rec:40")
                        )
                        ParameterSwitch(
                            label = localized("有前车时也触发", "Trigger with lead"),
                            checked = ceCurvesLead,
                            onCheckedChange = { ceCurvesLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐关", "rec:off")
                        )
                    }
                
                    // ===== 条件2: 前车检测 =====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("前车检测", "Lead Detection"),
                        icon = "🚗",
                        enabled = ceLeadEnabled,
                        onEnabledChange = { ceLeadEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: ModelV2 lead0 (prob/x/v)\n" +
                            "prob>0.5=有前车, v<1m/s=停止, 速度差>阈值=较慢",
                            "Source: ModelV2 lead0 (prob/x/v)\n" +
                            "prob>0.5=lead exists, v<1m/s=stopped, speed diff>threshold=slower"
                        )
                    ) {
                        ParameterSlider(
                            label = localized("前车距离上限", "Max Lead Distance"),
                            value = ceLeadDist,
                            unit = "m",
                            range = 20f..150f,
                            step = 10,
                            onValueChange = { ceLeadDist = it; hasUnsavedChanges = true },
                            recommended = localized("推荐80", "rec:80")
                        )
                        ParameterSwitch(
                            label = localized("较慢前车", "Slower Lead"),
                            checked = ceSlowerLead,
                            onCheckedChange = { ceSlowerLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐开", "rec:on")
                        )
                        if (ceSlowerLead) {
                            ParameterSlider(
                                label = localized("速度差阈值", "Speed Diff Threshold"),
                                value = ceLeadSpeedDiff,
                                unit = "km/h",
                                range = 3f..20f,
                                step = 1,
                                onValueChange = { ceLeadSpeedDiff = it; hasUnsavedChanges = true },
                                recommended = localized("推荐7", "rec:7")
                            )
                        }
                        ParameterSwitch(
                            label = localized("停止前车", "Stopped Lead"),
                            checked = ceStoppedLead,
                            onCheckedChange = { ceStoppedLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐开", "rec:on")
                        )
                    }
                
                    // ===== 条件3: 低速条件 =====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("低速条件", "Low Speed"),
                        icon = "🐌",
                        enabled = ceSpeedEnabled,
                        onEnabledChange = { ceSpeedEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: CarState.vEgo (m/s→km/h)\n" +
                            "低速场景实验模式更安全，有/无前车分别设阈值",
                            "Source: CarState.vEgo (m/s→km/h)\n" +
                            "Exp mode safer at low speed, separate thresholds for lead/no-lead"
                        )
                    ) {
                        ParameterSlider(
                            label = localized("无前车速度", "No Lead Speed"),
                            value = ceSpeed,
                            unit = "km/h",
                            range = 10f..50f,
                            step = 5,
                            onValueChange = { ceSpeed = it; hasUnsavedChanges = true },
                            recommended = localized("推荐30", "rec:30")
                        )
                        ParameterSlider(
                            label = localized("有前车速度", "With Lead Speed"),
                            value = ceSpeedLead,
                            unit = "km/h",
                            range = 0f..40f,
                            step = 5,
                            onValueChange = { ceSpeedLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐20", "rec:20")
                        )
                    }
                
                    // ===== 条件4: 导航转弯（7705 tbtDist）=====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("导航转弯 (7705)", "Navigation Turn (7705)"),
                        icon = "🗺️",
                        enabled = ceNavigationEnabled,
                        onEnabledChange = { ceNavigationEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: 7705 JSON → tbt_dist (转弯距离)\n" +
                            "替代原nTBTDist/nTBTTurnType方案\n" +
                            "tbt_dist>0即有转弯指令，不依赖高德导航运行",
                            "Source: 7705 JSON → tbt_dist (turn distance)\n" +
                            "Replaces nTBTDist/nTBTTurnType approach\n" +
                            "tbt_dist>0 means valid turn, no Amap dependency"
                        )
                    ) {
                        ParameterSlider(
                            label = localized("转弯距离阈值", "Turn Distance"),
                            value = ceNavTurnDistance,
                            unit = "m",
                            range = 50f..500f,
                            step = 50,
                            onValueChange = { ceNavTurnDistance = it; hasUnsavedChanges = true },
                            recommended = localized("推荐200", "rec:200")
                        )
                        ParameterSwitch(
                            label = localized("有前车时也触发", "Trigger with lead"),
                            checked = ceNavLead,
                            onCheckedChange = { ceNavLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐开", "rec:on")
                        )
                        // 实时数据
                        Text(
                            text = "📊 " + localized(
                                "当前tbt_dist: ${currentTbtDist}m",
                                "Current tbt_dist: ${currentTbtDist}m"
                            ),
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                
                    // ===== 条件5: 测速点（7705 sdiDist）=====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("测速点 (7705)", "Speed Camera (7705)"),
                        icon = "📷",
                        enabled = ceSdiEnabled,
                        onEnabledChange = { ceSdiEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: 7705 JSON → sdi_dist (测速点距离)\n" +
                            "接近测速点时切换实验模式，速度控制更精确\n" +
                            "sdi_dist>0表示前方有测速点",
                            "Source: 7705 JSON → sdi_dist (speed camera distance)\n" +
                            "Switch to exp mode near speed cameras for precise control\n" +
                            "sdi_dist>0 means camera ahead"
                        )
                    ) {
                        ParameterSlider(
                            label = localized("触发距离", "Trigger Distance"),
                            value = ceSdiDistance,
                            unit = "m",
                            range = 100f..800f,
                            step = 50,
                            onValueChange = { ceSdiDistance = it; hasUnsavedChanges = true },
                            recommended = localized("推荐300", "rec:300")
                        )
                        Text(
                            text = "📊 " + localized(
                                "当前sdi_dist: ${currentSdiDist}m",
                                "Current sdi_dist: ${currentSdiDist}m"
                            ),
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                
                    // ===== 条件6: 驾驶状态（7705 xState）=====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("驾驶状态 (7705)", "Drive State (7705)"),
                        icon = "⚙️",
                        enabled = ceXStateEnabled,
                        onEnabledChange = { ceXStateEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: 7705 JSON → xState (纵向控制状态)\n" +
                            "0=跟车 1=巡航 2=e2e巡航 3=e2e停车中 4=e2e准备 5=e2e已停\n" +
                            "选择哪些状态下切换到实验模式",
                            "Source: 7705 JSON → xState (longitudinal state)\n" +
                            "0=lead 1=cruise 2=e2eCruise 3=e2eStop 4=e2ePrepare 5=e2eStopped\n" +
                            "Select which states trigger experimental mode"
                        )
                    ) {
                        ParameterSwitch(
                            label = localized("跟车时 (xState=0)", "Lead follow (xState=0)"),
                            checked = ceXStateLead,
                            onCheckedChange = { ceXStateLead = it; hasUnsavedChanges = true },
                            recommended = localized("推荐关", "rec:off")
                        )
                        ParameterSwitch(
                            label = localized("停车中 (xState=3)", "Stopping (xState=3)"),
                            checked = ceXStateStop,
                            onCheckedChange = { ceXStateStop = it; hasUnsavedChanges = true },
                            recommended = localized("推荐开", "rec:on")
                        )
                        ParameterSwitch(
                            label = localized("准备起步 (xState=4)", "Preparing (xState=4)"),
                            checked = ceXStatePrepare,
                            onCheckedChange = { ceXStatePrepare = it; hasUnsavedChanges = true },
                            recommended = localized("推荐关", "rec:off")
                        )
                        ParameterSwitch(
                            label = localized("已停车 (xState=5)", "Stopped (xState=5)"),
                            checked = ceXStateStopped,
                            onCheckedChange = { ceXStateStopped = it; hasUnsavedChanges = true },
                            recommended = localized("推荐开", "rec:on")
                        )
                        // 实时数据
                        val xStateNames = mapOf(
                            0 to "lead(跟车)", 1 to "cruise(巡航)", 2 to "e2eCruise",
                            3 to "e2eStop(停车中)", 4 to "e2ePrepare(准备)", 5 to "e2eStopped(已停)"
                        )
                        Text(
                            text = "📊 " + localized(
                                "当前xState: $currentXState (${xStateNames[currentXState] ?: "未知"})",
                                "Current xState: $currentXState (${xStateNames[currentXState] ?: "unknown"})"
                            ),
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // ===== 条件7: 巡航速度调整（限速变化）=====
                    ConditionGroupCard(
                        modifier = Modifier.weight(1f),
                        title = localized("巡航调速 (限速变化)", "Cruise Adjust (Limit Change)"),
                        icon = "🚦",
                        enabled = ceCruiseAdjEnabled,
                        onEnabledChange = { ceCruiseAdjEnabled = it; hasUnsavedChanges = true },
                        description = localized(
                            "数据源: nRoadLimitSpeed + 7705 v_cruise_kph/carcruiseSpeed\n" +
                            "高速限速变化(如100→120)时，临时切实验模式让系统自动调速\n" +
                            "当carcruiseSpeed与vCruiseKph对齐后自动切回Chill",
                            "Source: nRoadLimitSpeed + 7705 v_cruise_kph/carcruiseSpeed\n" +
                            "When highway limit changes (e.g. 100→120), temp switch to exp mode\n" +
                            "Auto-switch back when carcruiseSpeed aligns with vCruiseKph"
                        )
                    ) {
                        ParameterSlider(
                            label = localized("限速下限", "Min Speed Limit"),
                            value = ceCruiseMinLimit,
                            unit = "km/h",
                            range = 60f..100f,
                            step = 10,
                            onValueChange = { ceCruiseMinLimit = it; hasUnsavedChanges = true },
                            recommended = localized("推荐80", "rec:80")
                        )
                        ParameterSlider(
                            label = localized("限速上限", "Max Speed Limit"),
                            value = ceCruiseMaxLimit,
                            unit = "km/h",
                            range = 100f..140f,
                            step = 10,
                            onValueChange = { ceCruiseMaxLimit = it; hasUnsavedChanges = true },
                            recommended = localized("推荐120", "rec:120")
                        )
                        ParameterSlider(
                            label = localized("对齐容差", "Alignment Tolerance"),
                            value = ceCruiseTolerance,
                            unit = "km/h",
                            range = 2f..15f,
                            step = 1,
                            onValueChange = { ceCruiseTolerance = it; hasUnsavedChanges = true },
                            recommended = localized("推荐5", "rec:5")
                        )
                        Text(
                            text = localized(
                                "当 |carcruiseSpeed - vCruiseKph| > ${ceCruiseTolerance}km/h 时触发",
                                "Trigger when |carCruise - vCruise| > ${ceCruiseTolerance}km/h"
                            ),
                            fontSize = 10.sp,
                            color = Color(0xFF60A5FA)
                        )
                        ParameterSlider(
                            label = localized("超时保护", "Timeout"),
                            value = ceCruiseTimeout,
                            unit = "s",
                            range = 10f..60f,
                            step = 5,
                            onValueChange = { ceCruiseTimeout = it; hasUnsavedChanges = true },
                            recommended = localized("推荐30", "rec:30")
                        )
                        // 实时数据
                        val cruiseMismatch = abs(currentCarCruise - currentVCruise)
                        Text(
                            text = "📊 " + localized(
                                "限速:${currentRoadLimit} vCruise:${currentVCruise.toInt()} carCruise:${currentCarCruise.toInt()} 差:${cruiseMismatch.toInt()}",
                                "Limit:${currentRoadLimit} vCruise:${currentVCruise.toInt()} carCruise:${currentCarCruise.toInt()} diff:${cruiseMismatch.toInt()}"
                            ),
                            fontSize = 10.sp,
                            color = if (cruiseMismatch > ceCruiseTolerance) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            // 保存按钮
            Button(
                onClick = { showSaveDialog = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasUnsavedChanges) Color(0xFF10B981) else Color(0xFF475569)
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = hasUnsavedChanges
            ) {
                Text(
                    text = if (hasUnsavedChanges) "💾 " + localized("保存设置", "Save Settings")
                        else "✓ " + localized("已保存 · 自动切换未启用", "Saved · automatic switching unavailable"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    
    // 保存确认对话框
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = {
                Text(
                    text = "💾 " + localized("保存设置", "Save Settings"),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(localized("仅保存本机CEM配置，不启用自动切换或写车辆参数",
                        "Save local CEM configuration only; no automatic switching or vehicle parameter writes"))
                    Text(
                        text = localized("已配置的条件（未运行）：", "Configured conditions (not running):"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF60A5FA)
                    )
                    if (cemEnabled) {
                        if (ceCurvesEnabled) Text("• " + localized("弯道检测 (曲率≥${ceCurvesCurvature/100f})", "Curve (≥${ceCurvesCurvature/100f})"), fontSize = 12.sp)
                        if (ceLeadEnabled) Text("• " + localized("前车检测", "Lead Detection"), fontSize = 12.sp)
                        if (ceSpeedEnabled) Text("• " + localized("低速条件", "Speed Condition"), fontSize = 12.sp)
                        if (ceNavigationEnabled) Text("• " + localized("导航转弯 (7705 tbtDist)", "Nav Turn (7705 tbtDist)"), fontSize = 12.sp)
                        if (ceSdiEnabled) Text("• " + localized("测速点 (7705 sdiDist)", "Speed Camera (7705 sdiDist)"), fontSize = 12.sp)
                        if (ceXStateEnabled) Text("• " + localized("驾驶状态 (7705 xState)", "Drive State (7705 xState)"), fontSize = 12.sp)
                        if (ceCruiseAdjEnabled) Text("• " + localized("巡航调速 (限速变化)", "Cruise Adjust (Limit Change)"), fontSize = 12.sp)
                    } else {
                        Text(
                            text = localized("(主开关已关闭)", "(Main switch OFF)"),
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().apply {
                            putBoolean("cem_enabled", cemEnabled)
                            // 弯道
                            putBoolean("ce_curves", ceCurvesEnabled)
                            putInt("ce_curves_speed", ceCurvesSpeed)
                            putInt("ce_curves_curvature", ceCurvesCurvature)
                            putBoolean("ce_curves_lead", ceCurvesLead)
                            // 前车
                            putBoolean("ce_lead", ceLeadEnabled)
                            putBoolean("ce_slower_lead", ceSlowerLead)
                            putBoolean("ce_stopped_lead", ceStoppedLead)
                            putInt("ce_lead_dist", ceLeadDist)
                            putInt("ce_lead_speed_diff", ceLeadSpeedDiff)
                            // 低速
                            putBoolean("ce_speed_enabled", ceSpeedEnabled)
                            putInt("ce_speed", ceSpeed)
                            putInt("ce_speed_lead", ceSpeedLead)
                            // 导航（7705）
                            putBoolean("ce_navigation", ceNavigationEnabled)
                            putInt("ce_nav_turn_distance", ceNavTurnDistance)
                            putBoolean("ce_nav_lead", ceNavLead)
                            // 测速点（7705）
                            putBoolean("ce_sdi", ceSdiEnabled)
                            putInt("ce_sdi_distance", ceSdiDistance)
                            // 驾驶状态（7705）
                            putBoolean("ce_xstate", ceXStateEnabled)
                            putBoolean("ce_xstate_lead", ceXStateLead)
                            putBoolean("ce_xstate_stop", ceXStateStop)
                            putBoolean("ce_xstate_stopped", ceXStateStopped)
                            putBoolean("ce_xstate_prepare", ceXStatePrepare)
                            // 巡航调速（限速变化）
                            putBoolean("ce_cruise_adj", ceCruiseAdjEnabled)
                            putInt("ce_cruise_tolerance", ceCruiseTolerance)
                            putInt("ce_cruise_timeout", ceCruiseTimeout)
                            putInt("ce_cruise_min_limit", ceCruiseMinLimit)
                            putInt("ce_cruise_max_limit", ceCruiseMaxLimit)
                            apply()
                        }
                        hasUnsavedChanges = false
                        showSaveDialog = false
                        android.widget.Toast.makeText(
                            context,
                            "✅ " + localized("配置已保存，自动切换仍未启用",
                                "Configuration saved; automatic switching remains unavailable"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text(localized("确认保存", "Confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(localized("取消", "Cancel"))
                }
            },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE5E7EB)
        )
    }
}


/**
 * 条件组卡片 - 带开关和说明
 */
@Composable
private fun ConditionGroupCard(
    title: String,
    icon: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    description: String = "",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = icon, fontSize = 18.sp)
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) Color.White else Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF10B981),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            
            if (enabled) {
                // 数据源说明
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        lineHeight = 16.sp
                    )
                }
                HorizontalDivider(color = Color(0xFF475569), thickness = 1.dp)
                content()
            }
        }
    }
}

/**
 * 参数滑块组件
 */
@Composable
private fun ParameterSlider(
    label: String,
    value: Int,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    onValueChange: (Int) -> Unit,
    recommended: String = ""
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFFE5E7EB))
                if (recommended.isNotEmpty()) {
                    Text(text = recommended, fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }
            Text(
                text = "$value $unit",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF60A5FA)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF60A5FA),
                activeTrackColor = Color(0xFF3B82F6),
                inactiveTrackColor = Color(0xFF475569)
            ),
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

/**
 * 参数开关组件
 */
@Composable
private fun ParameterSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    recommended: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFFE5E7EB))
            if (recommended.isNotEmpty()) {
                Text(text = recommended, fontSize = 11.sp, color = Color(0xFF64748B))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF10B981),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF475569)
            ),
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}
