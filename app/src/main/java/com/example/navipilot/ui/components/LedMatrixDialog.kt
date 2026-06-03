package com.example.navipilot.ui.components

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.ui.utils.localized

@SuppressLint("MissingPermission")
@Composable
fun LedMatrixDialog(
    onDismiss: () -> Unit,
    ledManagerExternal: LedMatrixManager? = null,
    onConnectionStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val ledManager = remember { ledManagerExternal ?: LedMatrixManager.getInstance(context) }
    val ownsManager = false

    // ─── 状态 ───────────────────────────────────────────────────────
    var ledState   by remember { mutableStateOf(ledManager.state) }
    var ledMessage by remember { mutableStateOf(ledManager.stateMessage) }
    var deviceList by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    var deviceInfo by remember { mutableStateOf(ledManager.deviceInfo) }
    var displayReady by remember { mutableStateOf(false) }

    // 屏幕控制
    var screenOn   by remember { mutableStateOf(true) }
    var brightness by remember { mutableStateOf(80) }   // 0–100，初始 80%
    var brightnessSlider by remember { mutableStateOf(0.8f) }  // 跟踪拖动中的值

    // 自定义文字
    var customText     by remember { mutableStateOf("") }
    var sendSuccess    by remember { mutableStateOf(false) }

    val colorOptions = listOf(
        Color(0xFFFFFFFF) to localized("白", "W"),
        Color(0xFFFF3333) to localized("红", "R"),
        Color(0xFF33FF33) to localized("绿", "G"),
        Color(0xFF3388FF) to localized("蓝", "B"),
        Color(0xFFFFFF33) to localized("黄", "Y"),
        Color(0xFFFF33FF) to localized("紫", "P"),
    )
    var selectedColor by remember { mutableStateOf(colorOptions[0].first) }

    // 权限启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) ledManager.startScan()
        else { ledState = LedMatrixManager.State.ERROR; ledMessage = localized("蓝牙权限被拒绝", "Bluetooth permission denied") }
    }

    // ─── 回调注册 ────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        ledManager.onStateChanged = { state, msg ->
            ledState = state; ledMessage = msg
            if (state != LedMatrixManager.State.CONNECTED && state != LedMatrixManager.State.SENDING) {
                displayReady = false
            }
            onConnectionStateChanged?.invoke(
                state == LedMatrixManager.State.CONNECTED || state == LedMatrixManager.State.SENDING
            )
        }
        ledManager.onDevicesUpdated = { deviceList = ledManager.scannedDevices.toList() }
        ledManager.onDeviceInfoUpdated = { info -> deviceInfo = info }
        ledManager.onReadyToDisplay = { displayReady = true }
        onDispose {
            if (ownsManager) ledManager.destroy()
            ledManager.onDeviceInfoUpdated = null
            ledManager.onReadyToDisplay = null
        }
    }

    // ─── Dialog ─────────────────────────────────────────────────────
    Dialog(
        onDismissRequest = { if (ownsManager) ledManager.destroy(); onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .heightIn(max = 600.dp),
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── 标题行 ──────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            localized("LED点阵屏", "LED Matrix"),
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = { if (ownsManager) ledManager.destroy(); onDismiss() },
                            color = Color.Transparent, shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("✕", color = Color(0xFF64748B), fontSize = 16.sp, modifier = Modifier.padding(4.dp))
                        }
                    }
                }

                // ── 状态行 ──────────────────────────────────────────
                item {
                    val (dotColor, statusText) = when (ledState) {
                        LedMatrixManager.State.IDLE       -> Color(0xFF64748B) to localized("未连接", "Disconnected")
                        LedMatrixManager.State.SCANNING   -> Color(0xFFFBBF24) to localized("扫描中…", "Scanning…")
                        LedMatrixManager.State.CONNECTING -> Color(0xFFFBBF24) to localized("连接中…", "Connecting…")
                        LedMatrixManager.State.CONNECTED  -> Color(0xFF10B981) to localized("已连接", "Connected")
                        LedMatrixManager.State.SENDING    -> Color(0xFF3B82F6) to localized("发送中…", "Sending…")
                        LedMatrixManager.State.ERROR      -> Color(0xFFEF4444) to localized("错误", "Error")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = dotColor,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildString {
                                append(statusText)
                                if (ledMessage.isNotEmpty()) append(" · $ledMessage")
                                if ((ledState == LedMatrixManager.State.CONNECTED || ledState == LedMatrixManager.State.SENDING) && !displayReady)
                                    append(" · " + localized("初始化中…", "Initializing…"))
                            },
                            color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1
                        )
                    }
                }

                // ════════════════════════════════════════════════════
                //  未连接：扫描 + 设备列表
                // ════════════════════════════════════════════════════
                if (ledState != LedMatrixManager.State.CONNECTED && ledState != LedMatrixManager.State.SENDING) {

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (!ledManager.isBluetoothEnabled()) {
                                        ledState = LedMatrixManager.State.ERROR
                                        ledMessage = localized("请先开启蓝牙", "Enable Bluetooth first")
                                        return@Button
                                    }
                                    if (!ledManager.hasPermissions()) {
                                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                                        else
                                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                        permissionLauncher.launch(perms)
                                    } else {
                                        ledManager.startScan()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(8.dp),
                                enabled = ledState != LedMatrixManager.State.SCANNING
                            ) {
                                Text(localized("蓝牙扫描", "BLE Scan"), fontSize = 13.sp)
                            }
                            if (ledState == LedMatrixManager.State.SCANNING) {
                                Button(
                                    onClick = { ledManager.stopScan() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text(localized("停止", "Stop"), fontSize = 13.sp) }
                            }
                        }
                    }

                    if (deviceList.isNotEmpty()) {
                        item {
                            Text(
                                localized("发现 ${deviceList.size} 个设备:", "${deviceList.size} device(s) found:"),
                                color = Color(0xFF94A3B8), fontSize = 12.sp
                            )
                        }
                        items(deviceList) { device ->
                            val name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
                            Surface(
                                onClick = { ledManager.connect(device) },
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Bluetooth, contentDescription = null,
                                        tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(device.address, color = Color(0xFF64748B), fontSize = 10.sp)
                                    }
                                    Text(localized("连接", "Connect"), color = Color(0xFF3B82F6), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                } else {
                    // ════════════════════════════════════════════════
                    //  已连接区域
                    // ════════════════════════════════════════════════

                    // ── 断开按钮 ────────────────────────────────────
                    item {
                        Button(
                            onClick = { ledManager.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(localized("断开连接", "Disconnect"), fontSize = 13.sp) }
                    }

                    // ── 屏幕控制卡片 ─────────────────────────────────
                    item {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {

                                // 标题
                                Text(
                                    localized("屏幕控制", "Screen Control"),
                                    color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(10.dp))

                                // 显示开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(localized("显示", "Display"), color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        if (screenOn) localized("开", "ON") else localized("关", "OFF"),
                                        color = if (screenOn) Color(0xFF10B981) else Color(0xFF64748B),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Switch(
                                        checked = screenOn,
                                        onCheckedChange = { on ->
                                            screenOn = on
                                            ledManager.setScreenOn(on)
                                        },
                                        enabled = displayReady,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF10B981),
                                            uncheckedThumbColor = Color(0xFF94A3B8),
                                            uncheckedTrackColor = Color(0xFF334155)
                                        )
                                    )
                                }

                                // 亮度滑块（仅屏幕开启时显示）
                                if (screenOn) {
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider(color = Color(0xFF334155))
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(localized("亮度", "Brightness"), color = Color(0xFF94A3B8), fontSize = 13.sp)
                                        Slider(
                                            value = brightnessSlider,
                                            onValueChange = { v ->
                                                brightnessSlider = v
                                                brightness = (v * 100).toInt().coerceIn(0, 100)
                                            },
                                            onValueChangeFinished = {
                                                // 松手后发送亮度命令，防止拖动中频繁发包
                                                val level = (brightnessSlider * 255).toInt().coerceIn(0, 255)
                                                ledManager.setBrightness(level)
                                            },
                                            enabled = displayReady,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 10.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF3B82F6),
                                                activeTrackColor = Color(0xFF3B82F6),
                                                inactiveTrackColor = Color(0xFF334155),
                                                disabledThumbColor = Color(0xFF475569),
                                                disabledActiveTrackColor = Color(0xFF475569)
                                            )
                                        )
                                        Text(
                                            "$brightness%",
                                            color = Color(0xFF94A3B8), fontSize = 12.sp,
                                            modifier = Modifier.width(38.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 自定义文字 ───────────────────────────────────
                    item {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {

                                Text(
                                    localized("自定义文字", "Custom Text"),
                                    color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(10.dp))

                                // 颜色选择
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(localized("颜色", "Color"), color = Color(0xFF64748B), fontSize = 12.sp)
                                    colorOptions.forEach { (color, label) ->
                                        val isSelected = color == selectedColor
                                        Surface(
                                            onClick = { selectedColor = color },
                                            color = if (isSelected) color.copy(alpha = 0.25f) else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.size(32.dp),
                                            border = if (isSelected) BorderStroke(2.dp, color) else BorderStroke(1.dp, Color(0xFF334155))
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Text("●", color = color, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))

                                // 文字输入 + 发送
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customText,
                                        onValueChange = { if (it.length <= 32) customText = it },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        placeholder = {
                                            Text(localized("输入要显示的文字", "Enter display text"), fontSize = 12.sp)
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF3B82F6),
                                            unfocusedBorderColor = Color(0xFF334155),
                                            cursorColor = Color(0xFF3B82F6),
                                            focusedPlaceholderColor = Color(0xFF475569),
                                            unfocusedPlaceholderColor = Color(0xFF475569)
                                        ),
                                        textStyle = TextStyle(fontSize = 14.sp)
                                    )
                                    Button(
                                        onClick = {
                                            if (customText.isNotBlank()) {
                                                val androidColor = android.graphics.Color.rgb(
                                                    (selectedColor.red   * 255).toInt(),
                                                    (selectedColor.green * 255).toInt(),
                                                    (selectedColor.blue  * 255).toInt()
                                                )
                                                ledManager.sendText(customText, androidColor)
                                                sendSuccess = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (sendSuccess) Color(0xFF059669) else Color(0xFF10B981)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = customText.isNotBlank() && displayReady && screenOn
                                    ) {
                                        Text(
                                            if (sendSuccess) localized("✓ 已发", "✓ Sent") else localized("发送", "Send"),
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                // 字符计数 + 提示
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (!displayReady) localized("设备初始化中，请稍候…", "Initializing device…")
                                        else if (!screenOn) localized("屏幕已关闭", "Screen is off")
                                        else "",
                                        color = Color(0xFFFFAA00), fontSize = 11.sp
                                    )
                                    Text(
                                        "${customText.length}/32",
                                        color = Color(0xFF475569), fontSize = 11.sp
                                    )
                                }

                                // 发送后重置 sendSuccess 标记
                                if (sendSuccess) {
                                    LaunchedEffect(sendSuccess) {
                                        kotlinx.coroutines.delay(1500)
                                        sendSuccess = false
                                    }
                                }
                            }
                        }
                    }

                    // ── 快捷操作 ─────────────────────────────────────
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { ledManager.clearScreen() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                enabled = displayReady && screenOn
                            ) { Text(localized("清屏", "Clear"), fontSize = 13.sp) }

                            OutlinedButton(
                                onClick = { ledManager.sendDebugBlueLine() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3388FF)),
                                border = BorderStroke(1.dp, Color(0xFF3388FF).copy(alpha = if (displayReady && screenOn) 1f else 0.4f)),
                                enabled = displayReady && screenOn
                            ) { Text(localized("蓝线测试", "Blue Line"), fontSize = 13.sp, color = Color(0xFF3388FF)) }
                        }
                    }

                    // ── 设备信息 ─────────────────────────────────────
                    item {
                        val info = deviceInfo
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        localized("设备信息", "Device Info"),
                                        color = Color(0xFF64748B), fontSize = 11.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    if (info != null) {
                                        Text(
                                            buildString {
                                                if (info.model.isNotEmpty()) append("${info.model}  ")
                                                append("${info.width}×${info.height}")
                                                if (info.versionMajor > 0 || info.versionMinor > 0 || info.versionPatch > 0)
                                                    append("  v${info.versionMajor}.${info.versionMinor}.${info.versionPatch}")
                                            },
                                            color = Color.White, fontSize = 13.sp
                                        )
                                        if (info.memory > 0) {
                                            Text(
                                                "Flash: ${info.memoryAvailable / 1024}KB / ${info.memory / 1024}KB",
                                                color = Color(0xFF64748B), fontSize = 11.sp
                                            )
                                        }
                                    } else {
                                        Text(
                                            localized("获取中…（默认 64×16）", "Fetching… (default 64×16)"),
                                            color = Color(0xFF475569), fontSize = 12.sp
                                        )
                                    }
                                }
                                // 重新查询按钮
                                if (info == null) {
                                    OutlinedButton(
                                        onClick = { ledManager.requestDeviceInfo() },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64748B)),
                                        border = BorderStroke(1.dp, Color(0xFF334155)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) { Text(localized("重试", "Retry"), fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
