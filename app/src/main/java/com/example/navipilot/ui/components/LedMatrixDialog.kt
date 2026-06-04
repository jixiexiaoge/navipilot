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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                    LedMatrixManager.State.SENDING -> localized("正在点亮蓝条…", "Lighting blue band…")
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
                                "当前版本只保留一个功能：连接成功后自动进入涂鸦模式，并将 96×16 面板中间 96×6 区域点亮为蓝色。",
                                "This version only keeps one feature: after connecting, it switches to doodle mode and lights the centered 96x6 area blue on the 96x16 panel."
                            ),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                if (ledState == LedMatrixManager.State.CONNECTED || ledState == LedMatrixManager.State.SENDING) {
                    Button(
                        onClick = { ledManager.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(localized("断开连接", "Disconnect"))
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
                        text = localized("点击设备后会自动点亮蓝色中带", "Tap a device to connect and light the blue band"),
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
