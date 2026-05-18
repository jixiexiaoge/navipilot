package com.example.carrotamap.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carrotamap.ui.utils.localized

private const val PRIVACY_PREFS = "privacy_consent"
private const val KEY_AGREED = "privacy_agreed"
private const val KEY_AGREED_VERSION = "privacy_version"
private const val CURRENT_PRIVACY_VERSION = 1

// Google 地图首次启动弹窗
private const val KEY_GOOGLE_WELCOME_SHOWN = "google_welcome_shown"
private const val GOOGLE_WELCOME_PREFS = "google_welcome_prefs"

/**
 * 检查是否已同意隐私政策
 */
fun hasPrivacyConsent(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_AGREED, false) &&
           prefs.getInt(KEY_AGREED_VERSION, 0) >= CURRENT_PRIVACY_VERSION
}

/**
 * 记录隐私政策同意
 */
fun setPrivacyConsent(context: Context, agreed: Boolean) {
    context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_AGREED, agreed)
        .putInt(KEY_AGREED_VERSION, CURRENT_PRIVACY_VERSION)
        .apply()
}

/**
 * 隐私政策弹窗（首次启动时显示）
 */
@Composable
fun PrivacyConsentDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        title = {
            Text(
                text = localized("用户协议与隐私政策", "Terms & Privacy Policy"),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = localized(
                        "欢迎使用 Navipilot！在使用本应用前，请仔细阅读以下内容：",
                        "Welcome to Navipilot! Please read the following before using this app:"
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF374151)
                )
                Spacer(Modifier.height(12.dp))
                
                PrivacySection(
                    title = localized("📍 位置信息", "📍 Location Data"),
                    content = localized(
                        "我们收集 GPS 位置数据用于导航和路线规划。位置数据仅在导航期间使用，不会上传到第三方服务器。",
                        "We collect GPS location data for navigation and route planning. Location data is only used during navigation and is not uploaded to third-party servers."
                    )
                )
                PrivacySection(
                    title = localized("🚗 驾驶数据", "🚗 Driving Data"),
                    content = localized(
                        "我们记录驾驶行为数据（速度、加速度、接管次数）用于驾驶评分功能。数据存储在本地设备上。",
                        "We record driving behavior data (speed, acceleration, takeover events) for the driving score feature. Data is stored locally on your device."
                    )
                )
                PrivacySection(
                    title = localized("📱 设备信息", "📱 Device Info"),
                    content = localized(
                        "我们收集设备标识符用于用户识别和崩溃报告。崩溃日志包含设备型号和系统版本，用于改进应用稳定性。",
                        "We collect device identifiers for user identification and crash reporting. Crash logs include device model and OS version to improve app stability."
                    )
                )
                PrivacySection(
                    title = localized("🔒 数据安全", "🔒 Data Security"),
                    content = localized(
                        "敏感数据使用加密存储。您可以随时在「我的」页面删除所有本地数据。",
                        "Sensitive data is stored with encryption. You can delete all local data at any time from the Profile page."
                    )
                )
                PrivacySection(
                    title = localized("📡 网络通信", "📡 Network Communication"),
                    content = localized(
                        "应用通过 UDP 与 comma3 设备通信（局域网），通过 HTTP/HTTPS 访问路线规划服务和用户 API。",
                        "The app communicates with comma3 devices via UDP (LAN), and accesses routing services and user API via HTTP/HTTPS."
                    )
                )
                
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildAnnotatedString {
                        append(localized("点击「同意」即表示您已阅读并同意", "By clicking \"Agree\", you accept "))
                        withStyle(SpanStyle(color = Color(0xFF2563EB), textDecoration = TextDecoration.Underline)) {
                            append(localized("《用户协议》", "Terms of Service"))
                        }
                        append(localized("和", " and "))
                        withStyle(SpanStyle(color = Color(0xFF2563EB), textDecoration = TextDecoration.Underline)) {
                            append(localized("《隐私政策》", "Privacy Policy"))
                        }
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFF6B7280)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    setPrivacyConsent(context, true)
                    onAgree()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(localized("同意并继续", "Agree & Continue"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text(localized("不同意", "Disagree"), color = Color(0xFF9CA3AF))
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PrivacySection(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1F2937))
        Text(content, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF6B7280))
    }
}

/**
 * 删除所有用户数据（隐私合规：数据删除权）
 */
fun deleteAllUserData(context: Context) {
    // 清除所有 SharedPreferences
    val prefsNames = listOf(
        "CarrotAmap", "driving_sessions", "speed_limit_learner",
        "auth_config", "map_addresses", "device_prefs",
        "crash_reporter", "privacy_consent", "app_analytics"
    )
    prefsNames.forEach { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
    }
    // 清除崩溃日志
    val crashDir = java.io.File(context.filesDir, "crash_logs")
    crashDir.listFiles()?.forEach { it.delete() }

    android.util.Log.i("PrivacyDialog", "🗑️ 所有用户数据已删除")
}

// ========== Google 地图首次欢迎弹窗 ==========

/**
 * 检查是否已显示过 Google 地图欢迎弹窗
 */
fun hasGoogleWelcomeShown(context: Context): Boolean {
    return context.getSharedPreferences(GOOGLE_WELCOME_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_GOOGLE_WELCOME_SHOWN, false)
}

/**
 * 标记 Google 地图欢迎弹窗已显示（仅显示一次）
 */
fun setGoogleWelcomeShown(context: Context) {
    context.getSharedPreferences(GOOGLE_WELCOME_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_GOOGLE_WELCOME_SHOWN, true)
        .apply()
}

/**
 * Google 地图首次使用欢迎弹窗
 */
@Composable
fun GoogleWelcomeDialog(
    onConfirm: () -> Unit
) {
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        title = {
            Text(
                text = localized("欢迎使用 Google 地图导航", "Welcome to Google Navigation"),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column {
                Text(
                    text = localized(
                        "您选择了 Google 地图导航模式。\n\n请确保：",
                        "You selected Google Maps navigation mode.\n\nPlease ensure:"
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF374151)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localized(
                        "• 已安装 Google Maps 应用\n• 网络连接正常\n• 定位权限已开启",
                        "• Google Maps app is installed\n• Network connection is available\n• Location permission is granted"
                    ),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = localized(
                        "导航数据将通过 UDP 端口 7706 发送至 comma3 设备，辅助自动驾驶功能。",
                        "Navigation data will be sent to your comma3 device via UDP port 7706 to assist automated driving."
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    setGoogleWelcomeShown(ctx)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(localized("确认", "Confirm"))
            }
        },
        dismissButton = null,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
