package com.example.carrotamap.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.carrotamap.BuildConfig
import com.example.carrotamap.ui.utils.localized

data class FAQItem(val question: String, val answer: String)

@Composable
fun HelpPage(deviceIP: String? = null) {
    val context = LocalContext.current
    var isFAQExpanded by remember { mutableStateOf(true) }
    var showC3ManagerBrowser by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text(
            text = localized("帮助中心", "Help Center"),
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (deviceIP != null && deviceIP.isNotEmpty()) {
                            showC3ManagerBrowser = true
                        } else {
                            android.widget.Toast.makeText(context,
                                localized("⚠️ 未检测到Comma3设备\n请确保设备已连接", "⚠️ Comma3 device not detected\nPlease ensure the device is connected"),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = localized("管理器", "Manager"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // FAQ Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { isFAQExpanded = !isFAQExpanded }.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = localized("常见问题", "FAQ"), tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = localized("常见问题", "FAQ"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B), modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (isFAQExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isFAQExpanded) localized("收起", "Collapse") else localized("展开", "Expand"),
                        tint = Color(0xFF64748B), modifier = Modifier.size(24.dp)
                    )
                }
                if (isFAQExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        getFAQItems().forEach { faq -> FAQItemCard(faq = faq) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showC3ManagerBrowser && deviceIP != null && deviceIP.isNotEmpty()) {
        FullscreenBrowserDialog(onDismiss = { showC3ManagerBrowser = false }, url = "http://$deviceIP:7000", title = localized("管理器", "Manager"))
    }
}

@Composable
private fun FAQItemCard(faq: FAQItem) {
    var isExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = localized("问题", "Question"), tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = faq.question, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(1f), lineHeight = 20.sp)
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) localized("收起", "Collapse") else localized("展开", "Expand"),
                    tint = Color(0xFF64748B), modifier = Modifier.size(20.dp)
                )
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = faq.answer, fontSize = 14.sp, color = Color(0xFF475569), lineHeight = 20.sp)
            }
        }
    }
}

private fun getFAQItems(): List<FAQItem> {
    return listOf(
        FAQItem(
            question = localized("国际版有何不同？", "What makes the International Edition different?"),
            answer = localized(
                "国际版面向全球用户，核心优势有 5 点：\n\n" +
                "• 国内可直接配合高德车机版使用\n" +
                "• 海外支持 OSM 地图\n" +
                "• 国内默认高德搜索（Android SDK，使用 Manifest 内 apikey），腾讯备选；国外使用 Photon 等备用搜索\n" +
                "• 支持车道级定位提醒\n" +
                "• 提供中英文界面，系统语言会自动切换",
                "The International Edition is built for global use, with five key advantages:\n\n" +
                "• Works with Amap Auto in China\n" +
                "• Supports OSM maps overseas\n" +
                "• In China: Amap search via Android SDK (Manifest apikey) by default, Tencent as fallback; abroad: Photon and other fallbacks\n" +
                "• Includes lane-level positioning alerts\n" +
                "• Offers Chinese and English UI with automatic language switching"
            )
        ),
        FAQItem(
            question = localized("CarrotAmap 和 CP搭子有什么区别？", "What's the difference between CarrotAmap and NaviPilot?"),
            answer = localized(
                "CP搭子可以理解为 CarrotAmap 的升级版。\n\n" +
                "CarrotAmap 已停止维护；CP搭子在稳定性、体验和后续更新上都更完善。\n\n" +
                "目前 CP搭子仅对赞助用户开放。",
                "NaviPilot is essentially the upgraded version of CarrotAmap.\n\n" +
                "CarrotAmap is no longer maintained, while NaviPilot improves stability, usability, and ongoing support.\n\n" +
                "NaviPilot is currently available to sponsors only."
            )
        ),
        FAQItem(
            question = localized("支持哪些车型？", "Which car models are supported?"),
            answer = localized(
                "支持范围较广。\n\n" +
                "除 OpenPilot 官方支持的 300 多款车型外，国内还额外适配了大量比亚迪及部分热门国产车型。\n\n" +
                "具体请以 OpenPilot 官方兼容列表为准。",
                "Coverage is broad.\n\n" +
                "In addition to the 300+ models supported by official OpenPilot, many BYD models and some popular Chinese models are also adapted.\n\n" +
                "Please refer to the official OpenPilot compatibility list for details."
            )
        ),
        FAQItem(
            question = localized("为什么选择 CP 而不是 SP、DP、FP？", "Why choose CP over SP, DP, or FP?"),
            answer = localized(
                "CP 是较早实现外挂导航辅助驾驶（NOO）的项目之一，在导航集成方面积累更深。\n\n" +
                "同时代码开源、方案成熟，因此在这一方向上更有参考价值。",
                "CP was one of the earliest projects to implement external navigation-assisted driving (NOO), giving it deeper experience in navigation integration.\n\n" +
                "Its code is open-source and the overall solution is relatively mature, which is why many users prefer it."
            )
        ),
        FAQItem(
            question = localized("只能用高德车机版吗？", "Is Amap Auto the only supported navigation app?"),
            answer = localized(
                "目前高德车机版是适配最完整、使用最省心的方案。\n\n" +
                "其他导航应用在技术上可以接入，但暂未适配；后续是否支持取决于需求和开发精力。",
                "Amap Auto is currently the most complete and reliable option.\n\n" +
                "Other navigation apps are technically possible, but they have not been adapted yet. Future support depends on demand and development time."
            )
        ),
        FAQItem(
            question = localized("为什么不是所有车都能自动超车？", "Why isn't auto-overtake available for all cars?"),
            answer = localized(
                "关键问题不是代码，而是安全。\n\n" +
                "不同车型的传感器配置差异很大，部分车辆缺少盲区监测等能力，无法可靠确认变道安全。\n\n" +
                "因此自动超车必须按车型逐一适配和验证，不能一刀切开放。",
                "The main issue is safety, not code.\n\n" +
                "Vehicle hardware varies widely, and some cars lack blind-spot monitoring or similar sensors, making lane changes hard to verify safely.\n\n" +
                "That is why auto-overtake must be adapted and validated per vehicle before it can be enabled."
            )
        ),
        FAQItem(
            question = localized("为什么CP搭子需要赞助才能使用？", "Why does NaviPilot require sponsorship?"),
            answer = localized(
                "项目初衷不是盈利，但服务器、维护和用户支持都需要长期投入。\n\n" +
                "在用户规模扩大后，个人已难以承担全部成本和反馈处理，因此采用赞助模式控制规模、保证服务质量。",
                "The project was not created for profit, but servers, maintenance, and support all require ongoing resources.\n\n" +
                "As the user base grew, it became unrealistic for one person to handle everything alone, so sponsorship was introduced to keep the project sustainable and support quality stable."
            )
        ),
        FAQItem(
            question = localized("CP搭子国际版（NaviPilot）有哪些变化？", "What's new in NaviPilot International Edition?"),
            answer = localized(
                "国际版主要做了三类调整：\n\n" +
                "• 精简部分本地化功能，降低维护成本\n" +
                "• 强化首页布局、地图展示和交通信息\n" +
                "• 根据地区区分导航方案：国内推荐高德，海外推荐 OSM + Mapbox\n\n" +
                "同时界面会根据语言环境自动切换中英文。",
                "The International Edition mainly focuses on three changes:\n\n" +
                "• Removes some localized features to simplify maintenance\n" +
                "• Improves the home layout, map presentation, and traffic info\n" +
                "• Uses region-based navigation choices: Amap for China, OSM + Mapbox overseas\n\n" +
                "The interface also switches automatically between Chinese and English based on system language."
            )
        ),
        FAQItem(
            question = localized("App 定位模式是什么意思？", "What do the positioning modes mean?"),
            answer = localized(
                "定位模式表示当前使用的定位增强等级，系统会自动选择可用的最高精度方案。\n\n" +
                "• GPS：基础定位，约 3-15 米\n" +
                "• SBAS：星基增强，约 2-5 米\n" +
                "• L1+L5：双频定位，约 1-3 米\n" +
                "• L1L5+S：双频 + SBAS，约 1-2 米\n" +
                "• DGNSS：通过 NTRIP 差分增强，约 1-2 米\n" +
                "• RTK 浮点/固定：厘米级定位，但对设备和环境要求更高\n\n" +
                "提示：开阔环境效果最好；颜色会直观显示精度等级。",
                "Positioning modes show the current level of location enhancement, and the app automatically uses the best available option.\n\n" +
                "• GPS: baseline positioning, about 3-15 m\n" +
                "• SBAS: satellite augmentation, about 2-5 m\n" +
                "• L1+L5: dual-frequency GNSS, about 1-3 m\n" +
                "• L1L5+S: dual-frequency plus SBAS, about 1-2 m\n" +
                "• DGNSS: NTRIP-based differential correction, about 1-2 m\n" +
                "• RTK Float/Fixed: centimeter-level positioning with higher device and environment requirements\n\n" +
                "Tip: accuracy is best in open areas, and the color indicator reflects quality at a glance."
            )
        ),
        FAQItem(
            question = localized("为什么没有 iPhone 版本？", "Why isn't there an iPhone version?"),
            answer = localized(
                "技术上可以做，但 iOS 需要分别处理国内外地图 SDK 适配，开发和维护成本都很高。\n\n" +
                "目前没有 iOS 版本计划；如果有开发者愿意参与，欢迎基于现有思路继续探索。",
                "It is technically possible, but iOS would require separate map SDK integrations for China and overseas, which makes development and maintenance expensive.\n\n" +
                "There is no active iOS plan for now, but developers interested in the idea are welcome to build on the existing approach."
            )
        )
    )
}

@SuppressLint("MissingPermission")
@Composable
fun LedMatrixDialog(onDismiss: () -> Unit, ledManagerExternal: LedMatrixManager? = null, onConnectionStateChanged: ((Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val ledManager = remember { ledManagerExternal ?: LedMatrixManager.getInstance(context) }
    val ownsManager = false  // 单例模式不再销毁
    var ledState by remember { mutableStateOf(ledManager.state) }
    var ledMessage by remember { mutableStateOf(ledManager.stateMessage) }
    var deviceList by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    var customText by remember { mutableStateOf("") }
    
    // 动画和显示参数
    var selectedAnimation by remember { mutableStateOf(LedMatrixManager.AnimationType.STATIC) }
    
    // 颜色选择
    val colorOptions = listOf(
        Color(0xFFFF3333) to localized("红", "R"),
        Color(0xFF33FF33) to localized("绿", "G"),
        Color(0xFF3388FF) to localized("蓝", "B"),
        Color(0xFFFFFF33) to localized("黄", "Y"),
        Color(0xFFFF33FF) to localized("紫", "P"),
        Color(0xFFFFFFFF) to localized("白", "W")
    )
    var selectedColor by remember { mutableStateOf(colorOptions[0].first) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ledManager.startScan()
        } else {
            ledState = LedMatrixManager.State.ERROR
            ledMessage = localized("蓝牙权限被拒绝", "Bluetooth permission denied")
        }
    }

    DisposableEffect(Unit) {
        ledManager.onStateChanged = { state, msg ->
            ledState = state
            ledMessage = msg
            onConnectionStateChanged?.invoke(state == LedMatrixManager.State.CONNECTED || state == LedMatrixManager.State.SENDING)
        }
        ledManager.onDevicesUpdated = {
            deviceList = ledManager.scannedDevices.toList()
        }
        onDispose { if (ownsManager) ledManager.destroy() }
    }

    Dialog(
        onDismissRequest = { if (ownsManager) ledManager.destroy(); onDismiss() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp),
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized("LED点阵屏", "LED Matrix"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(onClick = { if (ownsManager) ledManager.destroy(); onDismiss() }, color = Color.Transparent, shape = RoundedCornerShape(4.dp)) {
                        Text("✕", color = Color(0xFF64748B), fontSize = 16.sp, modifier = Modifier.padding(4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 状态
                val (statusColor, statusText) = when (ledState) {
                    LedMatrixManager.State.IDLE -> Color(0xFF64748B) to localized("未连接", "Disconnected")
                    LedMatrixManager.State.SCANNING -> Color(0xFFFBBF24) to localized("扫描中...", "Scanning...")
                    LedMatrixManager.State.CONNECTING -> Color(0xFFFBBF24) to localized("连接中...", "Connecting...")
                    LedMatrixManager.State.CONNECTED -> Color(0xFF10B981) to localized("已连接", "Connected")
                    LedMatrixManager.State.SENDING -> Color(0xFF3B82F6) to localized("发送中...", "Sending...")
                    LedMatrixManager.State.ERROR -> Color(0xFFEF4444) to localized("错误", "Error")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = statusColor, shape = RoundedCornerShape(50), modifier = Modifier.size(8.dp)) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("$statusText ${if (ledMessage.isNotEmpty()) "· $ledMessage" else ""}", color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (ledState != LedMatrixManager.State.CONNECTED && ledState != LedMatrixManager.State.SENDING) {
                    // 扫描按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!ledManager.isBluetoothEnabled()) {
                                    ledState = LedMatrixManager.State.ERROR
                                    ledMessage = localized("请先开启蓝牙", "Enable Bluetooth first")
                                    return@Button
                                }
                                if (!ledManager.hasPermissions()) {
                                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                                    } else { arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION) }
                                    permissionLauncher.launch(perms)
                                } else { ledManager.startScan() }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(8.dp),
                            enabled = ledState != LedMatrixManager.State.SCANNING
                        ) { Text(localized("蓝牙扫描", "BLE Scan"), fontSize = 13.sp) }
                        if (ledState == LedMatrixManager.State.SCANNING) {
                            Button(
                                onClick = { ledManager.stopScan() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(localized("停止", "Stop"), fontSize = 13.sp) }
                        }
                    }
                    // 设备列表
                    if (deviceList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(localized("发现 ${deviceList.size} 个设备:", "${deviceList.size} devices found:"), color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                            items(deviceList) { device ->
                                val name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
                                Surface(
                                    onClick = { ledManager.connect(device) },
                                    color = Color(0xFF1E293B), shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(device.address, color = Color(0xFF64748B), fontSize = 10.sp)
                                        }
                                        Text(localized("连接", "Connect"), color = Color(0xFF3B82F6), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // 已连接 — 文字输入和预设
                    Button(
                        onClick = { ledManager.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(localized("断开连接", "Disconnect"), fontSize = 13.sp) }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(localized("自定义文字", "Custom Text"), color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // 动画效果选择
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(localized("效果:", "Effect:"), color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        val animations = listOf(
                            LedMatrixManager.AnimationType.STATIC to localized("静态", "Static"),
                            LedMatrixManager.AnimationType.SCROLL_LEFT to localized("←滚", "←"),
                            LedMatrixManager.AnimationType.SCROLL_RIGHT to localized("滚→", "→"),
                            LedMatrixManager.AnimationType.BREATHE to localized("呼吸", "Breathe"),
                            LedMatrixManager.AnimationType.LASER to localized("激光", "Laser")
                        )
                        animations.forEach { (anim, label) ->
                            val isSelected = anim == selectedAnimation
                            Surface(
                                onClick = { selectedAnimation = anim },
                                color = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.3f) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(28.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6)) else null
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text(label, color = if (isSelected) Color(0xFF3B82F6) else Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    // 颜色选择
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(localized("颜色:", "Color:"), color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        colorOptions.forEach { (color, label) ->
                            val isSelected = color == selectedColor
                            Surface(
                                onClick = { selectedColor = color },
                                color = if (isSelected) color.copy(alpha = 0.3f) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(30.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, color) else null
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text("●", color = color, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            modifier = Modifier.weight(1f).height(50.dp),
                            placeholder = { Text(localized("输入文字", "Text"), fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFF3B82F6),
                                focusedPlaceholderColor = Color(0xFF475569), unfocusedPlaceholderColor = Color(0xFF475569)
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                        )
                        Button(
                            onClick = {
                                if (customText.isNotBlank()) {
                                    val androidColor = android.graphics.Color.rgb(
                                        (selectedColor.red * 255).toInt(),
                                        (selectedColor.green * 255).toInt(),
                                        (selectedColor.blue * 255).toInt()
                                    )
                                    ledManager.sendText(customText, androidColor, selectedAnimation)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp),
                            enabled = customText.isNotBlank() && ledState != LedMatrixManager.State.SENDING
                        ) { Text(localized("发送", "Send"), fontSize = 13.sp) }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { ledManager.clearScreen() },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                    ) { Text(localized("清屏", "Clear"), fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun FullscreenBrowserDialog(onDismiss: () -> Unit, url: String, title: String, fallbackUrl: String? = null) {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var hasFallenBack by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        webView?.let { wv -> if (wv.canGoBack()) wv.goBack() else onDismiss() } ?: onDismiss()
                    }) {
                        Icon(
                            imageVector = if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = if (canGoBack) localized("返回", "Back") else localized("关闭", "Close"),
                            tint = Color(0xFF1E293B)
                        )
                    }
                    Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { webView?.let { wv -> if (wv.canGoForward()) wv.goForward() } }, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = localized("前进", "Forward"), tint = if (canGoForward) Color(0xFF1E293B) else Color(0xFF94A3B8))
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = localized("刷新", "Refresh"), tint = Color(0xFF1E293B))
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView = this
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                }
                                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    // 主URL加载失败时切换到备用URL
                                    if (!hasFallenBack && fallbackUrl != null && request?.isForMainFrame == true) {
                                        hasFallenBack = true
                                        view?.loadUrl(fallbackUrl)
                                    }
                                }
                            }
                            settings.apply {
                                javaScriptEnabled = true; domStorageEnabled = true
                                loadWithOverviewMode = true; useWideViewPort = true
                                builtInZoomControls = true; displayZoomControls = false
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                @Suppress("DEPRECATION")
                                databaseEnabled = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                textZoom = 100; loadsImagesAutomatically = true; blockNetworkImage = false
                            }
                            isVerticalScrollBarEnabled = true; isHorizontalScrollBarEnabled = true
                            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
                            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
                            overScrollMode = android.view.View.OVER_SCROLL_ALWAYS
                            isNestedScrollingEnabled = true; setScrollContainer(true); isLongClickable = true
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize().weight(1f)
                )
            }
        }
    }
}
