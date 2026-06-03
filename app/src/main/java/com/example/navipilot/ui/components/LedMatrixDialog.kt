package com.example.navipilot.ui.components

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.ui.utils.localized

@SuppressLint("MissingPermission")
@Composable
fun LedMatrixDialog(onDismiss: () -> Unit, ledManagerExternal: LedMatrixManager? = null, onConnectionStateChanged: ((Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val ledManager = remember { ledManagerExternal ?: LedMatrixManager.getInstance(context) }
    val ownsManager = false
    var ledState by remember { mutableStateOf(ledManager.state) }
    var ledMessage by remember { mutableStateOf(ledManager.stateMessage) }
    var deviceList by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    var customText by remember { mutableStateOf("") }
    var selectedAnimation by remember { mutableStateOf(LedMatrixManager.AnimationType.STATIC) }

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
        ledManager.onDevicesUpdated = { deviceList = ledManager.scannedDevices.toList() }
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
                // 标题行
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized("LED点阵屏", "LED Matrix"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(onClick = { if (ownsManager) ledManager.destroy(); onDismiss() }, color = Color.Transparent, shape = RoundedCornerShape(4.dp)) {
                        Text("✕", color = Color(0xFF64748B), fontSize = 16.sp, modifier = Modifier.padding(4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 状态指示
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
                    // 未连接：扫描与设备列表
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
                    // 已连接：发送控制
                    Button(
                        onClick = { ledManager.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) { Text(localized("断开连接", "Disconnect"), fontSize = 13.sp) }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(localized("自定义文字", "Custom Text"), color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))

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
                                border = if (isSelected) BorderStroke(1.dp, Color(0xFF3B82F6)) else null
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text(label, color = if (isSelected) Color(0xFF3B82F6) else Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(localized("颜色:", "Color:"), color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        colorOptions.forEach { (color, label) ->
                            val isSelected = color == selectedColor
                            Surface(
                                onClick = { selectedColor = color },
                                color = if (isSelected) color.copy(alpha = 0.3f) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(30.dp),
                                border = if (isSelected) BorderStroke(2.dp, color) else null
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
                            textStyle = TextStyle(fontSize = 14.sp)
                        )
                        Button(
                            onClick = {
                                if (customText.isNotBlank()) {
                                    val androidColor = android.graphics.Color.rgb(
                                        (selectedColor.red * 255).toInt(),
                                        (selectedColor.green * 255).toInt(),
                                        (selectedColor.blue * 255).toInt()
                                    )
                                    ledManager.sendText(customText, androidColor)
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

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { ledManager.sendDebugBlueLine() },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3388FF)),
                        enabled = ledState != LedMatrixManager.State.SENDING,
                        border = BorderStroke(1.dp, Color(0xFF3388FF))
                    ) {
                        Text(localized("调试: 蓝色横线", "Debug: Blue Line"), fontSize = 13.sp, color = Color(0xFF3388FF))
                    }
                }
            }
        }
    }
}
