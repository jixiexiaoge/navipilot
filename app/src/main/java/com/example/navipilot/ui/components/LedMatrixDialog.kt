package com.example.navipilot.ui.components

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.ui.utils.localized

private data class LedTextColorOption(
    val label: String,
    val color: Color
)

@SuppressLint("MissingPermission")
@Composable
fun LedMatrixDialog(
    onDismiss: () -> Unit,
    ledManagerExternal: LedMatrixManager? = null,
    onConnectionStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val ledManager = remember { ledManagerExternal ?: LedMatrixManager.getInstance(context) }

    var ledState by remember { mutableStateOf(ledManager.state) }
    var ledMessage by remember { mutableStateOf(ledManager.stateMessage) }
    var deviceList by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    var customText by rememberSaveable { mutableStateOf("") }
    var selectedColorIndex by rememberSaveable { mutableStateOf(0) }
    var selectedAnimIndex by rememberSaveable { mutableStateOf(0) }

    data class LedAnimOption(
        val label: String,
        val code: Int
    )

    val colorOptions = remember {
        listOf(
            LedTextColorOption(localized("蓝色", "Blue"), Color(0xFF3388FF)),
            LedTextColorOption(localized("红色", "Red"), Color(0xFFFF4D4F)),
        )
    }

    val animOptions = remember {
        listOf(
            LedAnimOption(localized("静态", "Static"), LedMatrixManager.ANIM_STATIC),
            LedAnimOption(localized("左移", "Left"), LedMatrixManager.ANIM_LEFT),
            LedAnimOption(localized("右移", "Right"), LedMatrixManager.ANIM_RIGHT),
            LedAnimOption(localized("闪烁", "Blink"), LedMatrixManager.ANIM_BLINK)
        )
    }

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

    DisposableEffect(ledManager) {
        ledManager.onStateChanged = { state, message ->
            ledState = state
            ledMessage = message
            onConnectionStateChanged?.invoke(
                state == LedMatrixManager.State.CONNECTED || state == LedMatrixManager.State.SENDING
            )
        }
        ledManager.onDevicesUpdated = {
            deviceList = ledManager.scannedDevices.toList()
        }
        onDispose {
            ledManager.onDevicesUpdated = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = localized("LED点阵屏", "LED Matrix"),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onDismiss) {
                        Text(localized("关闭", "Close"))
                    }
                }

                val statusText = when (ledState) {
                    LedMatrixManager.State.IDLE -> localized("未连接", "Disconnected")
                    LedMatrixManager.State.SCANNING -> localized("扫描中…", "Scanning…")
                    LedMatrixManager.State.CONNECTING -> localized("连接中…", "Connecting…")
                    LedMatrixManager.State.CONNECTED -> localized("已连接", "Connected")
                    LedMatrixManager.State.SENDING -> localized("正在上传节目…", "Uploading program…")
                    LedMatrixManager.State.ERROR -> localized("错误", "Error")
                }
                Text(
                    text = buildString {
                        append(statusText)
                        if (ledMessage.isNotBlank()) append(" · $ledMessage")
                    },
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = localized(
                                "96×16 点阵，按官方协议上传文字资产并切换到节目播放。自定义文字最多 64 个字，支持颜色和动画（左移/右移/闪烁）。",
                                "96×16 matrix, uploads text as an official asset and switches to program playback. Custom text supports up to 64 characters with color and animation."
                            ),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                if (ledState == LedMatrixManager.State.CONNECTED || ledState == LedMatrixManager.State.SENDING) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = localized("自定义文本", "Custom text"),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { value ->
                                    customText = value
                                        .replace("\n", "")
                                        .replace("\r", "")
                                        .take(64)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = {
                                    Text(localized("最多 64 个字符", "Up to 64 characters"))
                                }
                            )
                            Text(
                                text = localized(
                                    "字数：${customText.length}/64",
                                    "Length: ${customText.length}/64"
                                ),
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = localized("文字颜色", "Text color"),
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    colorOptions.forEachIndexed { index, option ->
                                        val selected = selectedColorIndex == index
                                        Surface(
                                            onClick = { selectedColorIndex = index },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) option.color.copy(alpha = 0.22f) else Color(0xFF0F172A),
                                            border = androidx.compose.foundation.BorderStroke(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) option.color else Color(0xFF334155)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = option.color
                                                ) {
                                                    Spacer(
                                                        modifier = Modifier
                                                            .height(16.dp)
                                                            .fillMaxWidth(0.18f)
                                                    )
                                                }
                                                Text(
                                                    text = option.label,
                                                    color = Color.White,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = localized("动画效果", "Animation"),
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    animOptions.forEachIndexed { index, option ->
                                        val selected = selectedAnimIndex == index
                                        Surface(
                                            onClick = { selectedAnimIndex = index },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) Color(0xFF3B82F6).copy(alpha = 0.22f) else Color(0xFF0F172A),
                                            border = androidx.compose.foundation.BorderStroke(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) Color(0xFF3B82F6) else Color(0xFF334155)
                                            )
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    ledManager.sendCustomText(
                                        text = customText,
                                        color = colorOptions[selectedColorIndex].color,
                                        animCode = animOptions[selectedAnimIndex].code
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = customText.isNotBlank()
                            ) {
                                Text(localized("发送文本", "Send text"))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { ledManager.sendDebugOfficialText() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF6366F1)
                                    )
                                ) {
                                    Text(
                                        text = localized("调试发送(官方)", "Debug(official)"),
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = localized(
                                        "发送后会持续保留，直到你再次手动发送新的节目。",
                                        "The content stays on the panel until you manually send a new program."
                                    ),
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { ledManager.disconnect() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text(localized("断开连接", "Disconnect"))
                        }
                        Button(
                            onClick = { ledManager.fillBlueStrip() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                        ) {
                            Text(localized("蓝色灯带", "Blue strip"), fontSize = 12.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = localized(
                                "如官方APP也无法发送，请断开设备电源30秒以上重置",
                                "If official app also fails, tap [Clear] button or power-cycle device (>30s)"
                            ),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            if (!ledManager.isBluetoothEnabled()) {
                                ledState = LedMatrixManager.State.ERROR
                                ledMessage = localized("请先开启蓝牙", "Enable Bluetooth first")
                            } else if (!ledManager.hasPermissions()) {
                                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    arrayOf(
                                        android.Manifest.permission.BLUETOOTH_SCAN,
                                        android.Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                } else {
                                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                permissionLauncher.launch(permissions)
                            } else {
                                ledManager.startScan()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ledState != LedMatrixManager.State.SCANNING
                    ) {
                        Text(localized("蓝牙扫描", "BLE Scan"))
                    }
                }

                if (deviceList.isNotEmpty() && ledState != LedMatrixManager.State.CONNECTED && ledState != LedMatrixManager.State.SENDING) {
                    Text(
                        text = localized("点击设备后会进入官方节目上传流程", "Tap a device to start the official upload flow"),
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(260.dp)
                    ) {
                        items(deviceList) { device ->
                            Surface(
                                onClick = { ledManager.connect(device) },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1E293B)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bluetooth,
                                        contentDescription = null,
                                        tint = Color(0xFF3B82F6)
                                    )
                                    Spacer(Modifier.padding(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name ?: localized("未知设备", "Unknown device"),
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = device.address,
                                            color = Color(0xFF64748B),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
