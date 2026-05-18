package com.example.carrotamap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.example.carrotamap.navigation.CoordinateConverter
import com.example.carrotamap.ui.utils.localized
import com.example.carrotamap.ui.theme.*

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
     * 车辆控制按钮组件 - 带速度圆环显示
     */
    @Composable
    fun VehicleControlButtons(
        core: MainActivityCore,
        onPageChange: (Int) -> Unit,
        onSendCommand: (String, String) -> Unit,
        onSendRoadLimitSpeed: () -> Unit,
        onLaunchAmap: () -> Unit,
        onSendNavConfirmation: () -> Unit,
        userType: Int,
        carrotManFields: CarrotManFields
    ) {
        var showAdvancedDialog by remember { mutableStateOf(false) }
        val context = androidx.compose.ui.platform.LocalContext.current
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：速度圆环（蓝色在上，绿色在下）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 蓝色圆环：巡航设定速度
                    SpeedRing(
                        speed = carrotManFields.vCruiseKph.toInt(),
                        label = localized("巡航", "Cruise"),
                        color = SpeedCruise,
                        onClick = { /* 模拟导航 */ }
                    )
                    // 绿色圆环：当前车速
                    SpeedRing(
                        speed = carrotManFields.vEgoKph,
                        label = localized("车速", "Speed"),
                        color = SpeedCurrent,
                        onClick = { onLaunchAmap() }
                    )
                }

                // 右侧：控制按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 回家按钮
                    ControlButton(
                        icon = Icons.Default.Home,
                        label = "",
                        color = ButtonHome,
                        onClick = {
                            android.util.Log.i("MainActivity", "🏠 主页：用户点击回家按钮")
                            MainActivityUIComponents.sendHomeNavigationToAmap(context)
                        }
                    )

                    // 高阶按钮
                    ControlButton(
                        icon = Icons.Default.Settings,
                        label = "",
                        color = ButtonAdvanced,
                        onClick = {
                            android.util.Log.i("MainActivity", "🚀 主页：用户点击高阶按钮，用户类型: $userType")
                            if (userType == 3 || userType == 4 || userType == 0) {
                                showAdvancedDialog = true
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    localized("⭐ 高阶功能需要赞助者权限\n请前往「我的」页面\n检查信息并更新用户类型", "⭐ Advanced features require Sponsor access\nGo to \"Profile\" page\nto check and update your user type"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )

                    // 公司按钮
                    ControlButton(
                        icon = Icons.Default.Work,
                        label = "",
                        color = ButtonCompany,
                        onClick = {
                            android.util.Log.i("MainActivity", "🏢 主页：用户点击公司按钮")
                            MainActivityUIComponents.sendCompanyNavigationToAmap(context)
                        }
                    )
                }
            }
        }
        
        // 高阶功能弹窗
        if (showAdvancedDialog) {
            AdvancedFunctionsDialog(
                onDismiss = { showAdvancedDialog = false },
                onSendCommand = onSendCommand,
                onSendRoadLimitSpeed = onSendRoadLimitSpeed,
                onLaunchAmap = onLaunchAmap,
                onSendNavConfirmation = onSendNavConfirmation,
                onPageChange = onPageChange, // 传递页面切换回调
                isOpenpilotActive = carrotManFields.active,
                carrotManFields = carrotManFields,
                networkManager = core.networkManager, // 传递networkManager用于直接发送坐标
                context = context
            )
        }
    }

    /**
     * 控制按钮组件（优化版 - 使用Material Icons）
     */
    @Composable
    fun ControlButton(
        icon: ImageVector,
        label: String,
        color: Color,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    onClick = {
                        android.util.Log.i("MainActivity", "🔍 ControlButton: 检测到点击事件")
                        onClick()
                    }
                )
                .shadow(2.dp, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(
                    color = color,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (label.isEmpty()) {
                // 只有图标，居中显示
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White
                )
            } else {
                // 图标 + 文字，垂直排列
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }

    /**
     * 高阶功能弹窗 - 3x3 九宫格（1=帮助，7=报告，2/8 加减速，3 超车，4/6 变道，5 控速占位，9 实验）
     */
    @Composable
    fun AdvancedFunctionsDialog(
        onDismiss: () -> Unit,
        onSendCommand: (String, String) -> Unit,
        onSendRoadLimitSpeed: () -> Unit,
        onLaunchAmap: () -> Unit,
        onSendNavConfirmation: () -> Unit,
        onPageChange: (Int) -> Unit, // 新增：页面切换回调
        isOpenpilotActive: Boolean,
        carrotManFields: CarrotManFields,
        networkManager: NetworkManager, // 添加networkManager参数用于直接发送坐标
        context: android.content.Context
    ) {
        // 🆕 通用音频播放函数 - 减少重复代码
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
        
        // 智能控速模式状态：0=智能控速, 1=原车巡航, 2=弯道减速
        var speedControlMode by remember { 
            mutableStateOf(
                context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
                    .getInt("speed_from_pcm_mode", 0)
            ) 
        }
        var isSpeedModeLoading by remember { mutableStateOf(false) }
        
        // 🆕 超车模式状态：0=禁止超车, 1=拨杆超车, 2=自动超车（默认值0）
        var overtakeMode by remember { 
            mutableStateOf(
                context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
                    .getInt("overtake_mode", 1)  // 默认值改为 1（拨杆超车）
            ) 
        }
        var isOvertakeModeLoading by remember { mutableStateOf(false) }
        
        val coroutineScope = rememberCoroutineScope()
        // 计算弹窗宽度：九宫格宽度（60dp * 3 + 10dp * 2 = 200dp）+ 左右padding（12dp * 2 = 24dp）= 224dp
        val dialogWidth = 60.dp * 3 + 10.dp * 2 + 12.dp * 2  // 200dp + 24dp = 224dp

        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss
        ) {
            Card(
                modifier = Modifier
                    .width(dialogWidth)
                    .wrapContentHeight()
                    .padding(0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DialogBackground
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 标题栏
                    Text(
                        text = localized("高级功能", "Advanced"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    // 3x3 九宫格按钮
                    for (row in 0..2) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (col in 0..2) {
                                val buttonNumber = row * 3 + col + 1
                                
                                when (buttonNumber) {
                                    // 1号按钮 - 帮助中心（MainActivityUI currentPage = 1 → HelpPage）
                                    1 -> {
                                        Button(
                                            onClick = {
                                                onPageChange(1)
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonHelp
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.HelpOutline,
                                                    contentDescription = "帮助",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("帮助", "Help"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 2号按钮 - 加速（绿色）
                                    2 -> {
                                        val currentCruiseSpeed = carrotManFields.vCruiseKph.toInt()
                                        val newSpeed = if (currentCruiseSpeed > 0) {
                                            minOf(currentCruiseSpeed + 10, 150)
                                        } else {
                                            50
                                        }

                                        Button(
                                            onClick = {
                                                android.util.Log.i("MainActivity", "🎮 高阶弹窗：用户点击加速按钮")
                                                onSendCommand("SPEED", newSpeed.toString())
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonAccel
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowUp,
                                                    contentDescription = "加速",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("加速", "Accel"),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 3号按钮 - 超车模式切换
                                    3 -> {
                                        val overtakeModeNames = arrayOf(localized("禁止\n超车", "No\nOvertake"), localized("拨杆\n超车", "Signal\nOvertake"), localized("自动\n超车", "Auto\nOvertake"))
                                        val overtakeModeColors = arrayOf(
                                            OvertakeDisabled,
                                            OvertakeManual,
                                            OvertakeAuto
                                        )

                                        Button(
                                            onClick = {
                                                if (!isOvertakeModeLoading) {
                                                    isOvertakeModeLoading = true
                                                    coroutineScope.launch {
                                                        // 从正确的SharedPreferences读取用户类型
                                                        val devicePrefs = context.getSharedPreferences("device_prefs", android.content.Context.MODE_PRIVATE)
                                                        val userType = devicePrefs.getInt("user_type", 0)

                                                        android.util.Log.d("MainActivity", "🔧 超车模式切换：用户类型=$userType, 当前模式=$overtakeMode")

                                                    val nextMode = if (userType == 4 || userType == 0) {
                                                            // 用户类型4（铁粉）和先锋用户（0）：可以在 0、1、2 之间循环切换
                                                        (overtakeMode + 1) % 3
                                                    } else {
                                                            // 其他用户类型：只在 0 和 1 之间切换
                                                        if (overtakeMode == 0) 1 else 0
                                                    }

                                                        android.util.Log.d("MainActivity", "🔧 超车模式切换：下一模式=$nextMode")

                                                        // 保存到CarrotAmap SharedPreferences
                                                        val prefs = context.getSharedPreferences("CarrotAmap", android.content.Context.MODE_PRIVATE)
                                                        prefs.edit()
                                                            .putInt("overtake_mode", nextMode)
                                                            .apply()

                                                        kotlinx.coroutines.delay(300)
                                                        overtakeMode = nextMode
                                                        isOvertakeModeLoading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isOvertakeModeLoading) {
                                                    Surface500
                                                } else {
                                                    overtakeModeColors[overtakeMode]
                                                }
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                            enabled = !isOvertakeModeLoading
                                        ) {
                                            Text(
                                                text = if (isOvertakeModeLoading) {
                                                    localized("切换\n中...", "Switch\ning...")
                                                } else {
                                                    overtakeModeNames[overtakeMode]
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                lineHeight = 12.sp
                                            )
                                        }
                                    }
                                    // 4号按钮 - 左变道
                                    4 -> {
                                        Button(
                                            onClick = {
                                                playSound(R.raw.left, "左变道")
                                                onSendCommand("LANECHANGE", "LEFT")
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonLaneChange
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowLeft,
                                                    contentDescription = "左变道",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("变道", "Lane"),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 5号按钮 - 已移除（智能控速）
                                    5 -> {
                                        Button(
                                            onClick = { },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Surface500.copy(alpha = 0.3f)
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                            enabled = false
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Speed,
                                                    contentDescription = "控速",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White.copy(alpha = 0.5f)
                                                )
                                                Text(
                                                    text = localized("控速", "Speed"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                    // 6号按钮 - 右变道
                                    6 -> {
                                        Button(
                                            onClick = {
                                                playSound(R.raw.right, "右变道")
                                                onSendCommand("LANECHANGE", "RIGHT")
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonLaneChange
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowRight,
                                                    contentDescription = "右变道",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("变道", "Lane"),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 7号按钮 - 驾驶报告（MainActivityUI currentPage = 7 → DrivingReportScreen）
                                    7 -> {
                                        Button(
                                            onClick = {
                                                onPageChange(7)
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonReport
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Assessment,
                                                    contentDescription = "报告",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("报告", "Report"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 8号按钮 - 减速
                                    8 -> {
                                        val currentCruiseSpeed = carrotManFields.vCruiseKph.toInt()
                                        val newSpeed = if (currentCruiseSpeed > 20) {
                                            maxOf(currentCruiseSpeed - 10, 20)
                                        } else {
                                            currentCruiseSpeed
                                        }

                                        Button(
                                            onClick = {
                                                if (newSpeed < currentCruiseSpeed) {
                                                    onSendCommand("SPEED", newSpeed.toString())
                                                } else {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        localized("⚠️ 已是最低速度（${currentCruiseSpeed}km/h）", "⚠️ Already at minimum speed (${currentCruiseSpeed}km/h)"),
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonDecel
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowDown,
                                                    contentDescription = "减速",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("减速", "Decel"),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 9号按钮 - 自动切换实验
                                    9 -> {
                                        Button(
                                            onClick = {
                                                onPageChange(4) // 切换到自动切换实验页面（原page 5改为page 4）
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ButtonExperiment
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.BugReport,
                                                    contentDescription = "实验",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = Color.White
                                                )
                                                Text(
                                                    text = localized("实验", "Exp"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                    // 其他未定义的按钮（应该不会出现，因为1-9都已定义）
                                    else -> {
                                        Button(
                                            onClick = {
                                                onDismiss()
                                            },
                                            modifier = Modifier.size(56.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF94A3B8)
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "$buttonNumber",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 🆕 超车参数调节区域（仅当超车模式不为0时显示）
                    if (overtakeMode != 0) {
                    HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                        color = Surface600,
                        thickness = 1.dp
                    )
                    
                    // 参数调节区域（紧凑布局）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 参数1：最小超车速度
                        OvertakeParameterRow(
                            label = localized("最小超车速度", "Min Overtake Speed"),
                                unit = "",
                            defaultValue = 70f,
                            minValue = 50f,
                            maxValue = 120f,
                            step = 5f,
                            prefKey = "overtake_param_min_speed_kph",
                            context = context
                        )
                        
                        // 参数2：速度差阈值
                        OvertakeParameterRow(
                            label = localized("速度差阈值", "Speed Diff Threshold"),
                                unit = "",
                            defaultValue = 10f,
                            minValue = 5f,
                            maxValue = 30f,
                            step = 1f,
                            prefKey = "overtake_param_speed_diff_kph",
                            context = context
                        )
                        }
                    }
                }
            }
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
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 参数名称（左侧，不占用多余空间）
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            
            // 减号按钮、数值、加号按钮（右侧，更紧凑排列）
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 减号按钮（更小）
                Button(
                    onClick = {
                        val newValue = (currentValue - step).coerceAtLeast(minValue)
                        currentValue = newValue
                        prefs.edit().putFloat(prefKey, newValue).apply()
                        android.util.Log.d("MainActivity", "🔧 调整参数 $label: $newValue")
                    },
                    modifier = Modifier.size(24.dp),
                    enabled = currentValue > minValue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentValue > minValue) ButtonDecel else Surface400
                    ),
                    contentPadding = PaddingValues(0.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "−",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // 当前值显示（移除单位，紧凑宽度）
                Text(
                    text = "${(currentValue * displayMultiplier).toInt()}${if (unit.isNotEmpty()) " $unit" else ""}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryLight,
                    modifier = Modifier.width(35.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                )

                // 加号按钮（更小）
                Button(
                    onClick = {
                        val newValue = (currentValue + step).coerceAtMost(maxValue)
                        currentValue = newValue
                        prefs.edit().putFloat(prefKey, newValue).apply()
                        android.util.Log.d("MainActivity", "🔧 调整参数 $label: $newValue")
                    },
                    modifier = Modifier.size(24.dp),
                    enabled = currentValue < maxValue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentValue < maxValue) ButtonAccel else Surface400
                    ),
                    contentPadding = PaddingValues(0.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "+",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
    
    /**
     * 向高德地图车机版发送 POI 导航广播（KEY_TYPE 10038，与 OsmMapView 逻辑一致）
     * @param destLatWgs84 目的地纬度 WGS84
     * @param destLonWgs84 目的地经度 WGS84
     */
    fun sendPoiNavigationToAmapAuto(
        context: Context,
        poiName: String,
        destLatWgs84: Double,
        destLonWgs84: Double
    ): Boolean {
        return try {
            val launched = tryLaunchAmapAutoApp(context)
            val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(destLatWgs84, destLonWgs84)
            val intent = Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10038)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("POINAME", poiName)
                putExtra("LAT", gcjLat)
                putExtra("LON", gcjLon)
                putExtra("DEV", 0)
                putExtra("STYLE", 0)
                setPackage(AMAP_AUTO_PKG)
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            sendAmapAutoBroadcastAfterLaunch(
                context,
                launched,
                intent,
                "✅ 高德车机版 POI 导航已发送: $poiName"
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 高德车机版 POI 准备失败: ${e.message}", e)
            false
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
     * 🆕 发起步行导航到停车位置
     * 使用腾讯地图URI Scheme发起步行导航
     */
    fun startWalkingNavigationToParked(
        context: android.content.Context,
        prefs: android.content.SharedPreferences,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ) {
        try {
            android.util.Log.i("MainActivity", "🚶 发起步行导航到停车位置: ($toLat, $toLon)")

            // 优先使用腾讯地图步行导航
            val tencentUri = android.net.Uri.parse(
                "qqmap://map/walk?from=$fromLat,$fromLon&to=$toLat,$toLon&coordtype=1&referer=myapp"
            )
            val tencentIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, tencentUri)
            tencentIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            if (tencentIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(tencentIntent)
                android.util.Log.i("MainActivity", "✅ 已打开腾讯地图步行导航")
                return
            }

            // 腾讯地图不可用，尝试高德地图步行导航
            android.util.Log.w("MainActivity", "⚠️ 腾讯地图不可用，尝试高德地图")
            val gaodeUri = Uri.parse(
                "androidamap://route?sourceApplication=Navipilot&slat=$fromLat&slon=$fromLon&sname=${java.net.URLEncoder.encode("当前位置", "UTF-8")}" +
                        "&dlat=$toLat&dlon=$toLon&dname=${java.net.URLEncoder.encode("停车位置", "UTF-8")}&dev=0&m=2&t=2"
            )
            val gaodeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, gaodeUri)
            gaodeIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            if (gaodeIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(gaodeIntent)
                android.util.Log.i("MainActivity", "✅ 已打开高德地图步行导航")
                return
            }

            // 两者都不可用
            android.util.Log.w("MainActivity", "⚠️ 未找到可用的地图应用")
            android.widget.Toast.makeText(
                context,
                "请安装腾讯地图或高德地图",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ 发起步行导航失败: ${e.message}", e)
            android.widget.Toast.makeText(
                context,
                "无法打开地图应用: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
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
            var lat = com.example.carrotamap.utils.CoordinatePreferences.getCoordinate(carrotPrefs, "vpPosPointLat", 0.0)
            if (lat == 0.0) {
                lat = com.example.carrotamap.utils.CoordinatePreferences.getCoordinate(devicePrefs, "vpPosPointLat", 0.0)
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
            var lon = com.example.carrotamap.utils.CoordinatePreferences.getCoordinate(carrotPrefs, "vpPosPointLon", 0.0)
            if (lon == 0.0) {
                lon = com.example.carrotamap.utils.CoordinatePreferences.getCoordinate(devicePrefs, "vpPosPointLon", 0.0)
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

/**
 * 速度圆环Compose组件（紧凑版，优化字体）
 */
@Composable
fun SpeedIndicatorCompose(
    value: Int,
    color: Color,
    label: String,
    onClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val radius = size.minDimension / 2f - 5.dp.toPx()
                drawCircle(
                    color = Color.White,
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = color,
                    radius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx())
                )
            }

            Text(
                text = value.toString(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }

        if (label.isNotEmpty()) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = TextTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}



/**
 * 速度圆环组件（优化版）
 */
@Composable
fun SpeedRing(
    speed: Int,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clickable(onClick = onClick)
            .background(color.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$speed",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}
