package com.example.carrotamap.ui.components

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.carrotamap.data.SshConnectionManager
import com.example.carrotamap.data.SshConnectionState
import com.example.carrotamap.ui.utils.localized
import kotlinx.coroutines.launch

/**
 * SSH 配置弹窗
 */
@Composable
fun SshConfigDialog(
    sshManager: SshConnectionManager,
    discoveredIp: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // SSH 连接状态
    val connectionState by sshManager.connectionState.collectAsState()
    val connectionInfo by sshManager.connectionInfo.collectAsState()
    val errorMessage by sshManager.errorMessage.collectAsState()

    // 默认内置 SSH 私钥 (从 res/raw/default_ssh_key 加载)
    val defaultKeyUri = remember {
        Uri.parse("android.resource://${context.packageName}/${com.example.carrotamap.R.raw.default_ssh_key}")
    }

    // 表单状态
    var host by remember { mutableStateOf(discoveredIp ?: "") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("comma") }
    var privateKeyUri by remember { mutableStateOf<Uri?>(null) }
    var privateKeyFileName by remember { mutableStateOf<String?>(null) }
    var useCustomKey by remember { mutableStateOf(false) }

    // 文件选择器 launcher - 在组件级别创建以确保稳定性
    val privateKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化 URI 权限，允许跨组件访问
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 如果无法获取持久化权限（如某些云存储文件），仍然可以使用临时权限
                Log.w("SshConfigDialog", "无法获取持久化权限: ${e.message}")
            }
            privateKeyUri = uri
            privateKeyFileName = uri.lastPathSegment?.substringAfterLast('/') ?: "private.key"
            useCustomKey = true
        }
    }

    Dialog(onDismissRequest = {
        if (connectionState != SshConnectionState.CONNECTING) {
            onDismiss()
        }
    }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SSH 配置",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            if (connectionState != SshConnectionState.CONNECTING) {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = localized("关闭", "Close"),
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 连接状态显示
                if (connectionState == SshConnectionState.CONNECTED && connectionInfo != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF064E3B),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✓",
                                color = Color(0xFF4ADE80),
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "已连接 ${connectionInfo!!.host}",
                                color = Color(0xFF4ADE80),
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // IP 地址
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP 地址") },
                    leadingIcon = {
                        Icon(Icons.Default.Link, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = connectionState != SshConnectionState.CONNECTING,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 端口
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(localized("端口", "Port")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = connectionState != SshConnectionState.CONNECTING,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 用户名
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(localized("用户名", "Username")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = connectionState != SshConnectionState.CONNECTING,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 私钥文件选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localized("私钥文件", "Private Key"),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useCustomKey,
                                onCheckedChange = { useCustomKey = it },
                                enabled = connectionState != SshConnectionState.CONNECTING,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF60A5FA),
                                    uncheckedColor = Color(0xFF64748B)
                                )
                            )
                            Text(
                                text = localized("使用自定义密钥", "Use custom key"),
                                color = if (useCustomKey) Color.White else Color(0xFF64748B),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 显示密钥信息
                if (useCustomKey) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { privateKeyLauncher.launch(arrayOf("*/*")) },
                            enabled = connectionState != SshConnectionState.CONNECTING,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF60A5FA)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = privateKeyFileName ?: localized("选择文件", "Select File"),
                                color = Color(0xFF60A5FA)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF064E3B),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = localized("使用内置默认密钥", "Using built-in default key"),
                                color = Color(0xFF4ADE80),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 错误消息
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "❌ ${errorMessage}",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (connectionState == SshConnectionState.CONNECTED) {
                        TextButton(
                            onClick = { sshManager.disconnect() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color(0xFFEF4444)
                            )
                        ) {
                            Text(localized("断开", "Disconnect"))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = {
                            if (connectionState == SshConnectionState.CONNECTING) return@Button
                            if (host.isBlank()) return@Button

                            // 如果使用自定义密钥但未选择，则提示
                            if (useCustomKey && privateKeyUri == null) {
                                android.widget.Toast.makeText(
                                    context,
                                    localized("请选择私钥文件", "Please select private key"),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }

                            val portInt = port.toIntOrNull() ?: 22
                            val keyUri = if (useCustomKey) privateKeyUri else defaultKeyUri

                            coroutineScope.launch {
                                sshManager.connect(
                                    host = host,
                                    port = portInt,
                                    username = username,
                                    privateKeyUri = keyUri!!
                                )
                            }
                        },
                        enabled = connectionState != SshConnectionState.CONNECTING && host.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3B82F6)
                        )
                    ) {
                        if (connectionState == SshConnectionState.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localized("连接中...", "Connecting..."))
                        } else {
                            Text(localized("连接", "Connect"))
                        }
                    }
                }
            }
        }
    }
}