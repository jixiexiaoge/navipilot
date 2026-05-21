package com.example.navipilot.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import com.example.navipilot.R
import com.example.navipilot.core.SecurePrefs
import com.example.navipilot.ui.utils.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用户数据模型
 */
data class UserData(
    val deviceId: String,
    val sponsorAmount: Float = 0f,
    val userType: Int = 0,
    val email: String = "",
    val contactName: String = ""  // 微信名/Discord用户名
)

/** 登录状态持久化 Key */
private const val AUTH_PREFS = "auth_config"
private const val KEY_LOGIN_EMAIL = "login_email"
private const val KEY_AUTH_TOKEN = "auth_token"
private const val LEGACY_KEY_LOGIN_METHOD = "login_method"
private const val LEGACY_KEY_GITHUB_NAME = "github_name"
private const val MAP_ADDRESS_PREFS = "map_addresses"
private const val KEY_PLATE_NUMBER = "plate_number"

/**
 * 我的页面组件
 */
@Composable
fun ProfilePage(deviceId: String) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // 登录状态
    val authPrefs = remember { context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE) }
    val securePrefs = remember { SecurePrefs.get(context) }
    val addressPrefs = remember { context.getSharedPreferences(MAP_ADDRESS_PREFS, Context.MODE_PRIVATE) }
    var loginEmail by remember { mutableStateOf(authPrefs.getString(KEY_LOGIN_EMAIL, "") ?: "") }
    val isLoggedIn = loginEmail.isNotBlank()
    
    // 用户数据状态
    var userData by remember { mutableStateOf<UserData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showUserForm by remember { mutableStateOf(false) }
    
    // 二维码弹窗状态（独立于编辑表单）
    var showQrCodeDialog by remember { mutableStateOf(false) }
    
    // 表单状态
    var sponsorAmount by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var plateNumber by remember { mutableStateOf(addressPrefs.getString(KEY_PLATE_NUMBER, "") ?: "") }
    var plateSaved by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    
    // 登录成功回调
    val onLoginSuccess: (String) -> Unit = { email ->
        loginEmail = email
        authPrefs.edit()
            .putString(KEY_LOGIN_EMAIL, email)
            .remove(LEGACY_KEY_LOGIN_METHOD)
            .remove(LEGACY_KEY_GITHUB_NAME)
            .apply()
    }
    
    val onLogout: () -> Unit = {
        loginEmail = ""
        authPrefs.edit()
            .remove(KEY_LOGIN_EMAIL)
            .remove(LEGACY_KEY_LOGIN_METHOD)
            .remove(LEGACY_KEY_GITHUB_NAME)
            .apply()
        // 清除安全存储中的 auth_token
        securePrefs.edit().remove(KEY_AUTH_TOKEN).apply()
    }
    
    // 重试触发器
    var retryTrigger by remember { mutableIntStateOf(0) }
    
    // 获取用户数据
    LaunchedEffect(deviceId, retryTrigger) {
        if (!hasInternetConnection(context)) {
            android.util.Log.w("ProfilePage", "无互联网连接（可能连接的是设备热点），跳过网络请求")
            errorMessage = localized(
                "⚠️ 当前无互联网连接（可能连接的是设备热点），用户数据暂时无法加载",
                "⚠️ No internet connection (possibly on device hotspot). User data unavailable."
            )
            isLoading = false
            return@LaunchedEffect
        }
        try {
            userData = fetchUserData(deviceId)
            if (userData != null) {
                sponsorAmount = userData!!.sponsorAmount.toString()
                contactName = userData!!.contactName
                android.util.Log.d("ProfilePage", "用户数据获取成功: ${userData!!.deviceId}, 用户类型: ${userData!!.userType}")
                // 缓存wechat_name到SharedPreferences，供分享海报等功能使用
                context.getSharedPreferences("device_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putString("wechat_name", userData!!.contactName).apply()
            }
            isLoading = false
        } catch (e: Exception) {
            android.util.Log.w("ProfilePage", "获取用户数据失败: ${e.message}")
            
            if (e.message?.contains("404") == true || e.message?.contains("用户不存在") == true || e.message?.contains("不存在") == true) {
                android.util.Log.i("ProfilePage", "用户不存在，开始自动注册...")
                try {
                    userData = registerUser(deviceId)
                    if (userData != null) {
                        sponsorAmount = userData!!.sponsorAmount.toString()
                        contactName = userData!!.contactName
                        android.util.Log.i("ProfilePage", "用户自动注册成功: ${userData!!.deviceId}, 用户类型: ${userData!!.userType}")
                        context.getSharedPreferences("device_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putString("wechat_name", userData!!.contactName).apply()
                    }
                } catch (registerError: Exception) {
                    android.util.Log.e("ProfilePage", "用户自动注册失败", registerError)
                    errorMessage = localized("用户自动注册失败: ${registerError.message}", "Auto-registration failed: ${registerError.message}")
                }
            } else {
                android.util.Log.e("ProfilePage", "获取用户数据时发生错误", e)
                errorMessage = localized("获取用户数据失败: ${e.message}", "Failed to load user data: ${e.message}")
            }
            isLoading = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 重要提醒卡片 (已禁用)
        // ImportantNoticeCard()
        
        // 登录卡片
        LoginCard(
            deviceId = deviceId,
            isLoggedIn = isLoggedIn,
            loginEmail = loginEmail,
            onLoginSuccess = onLoginSuccess,
            onLogout = onLogout
        )
        
        // 用户信息卡片
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = localized("用户信息", "User Info"),
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = localized("用户信息", "User Info"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                }
                
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF3B82F6))
                        }
                    }
                    errorMessage != null -> {
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(
                                text = errorMessage!!,
                                color = if (errorMessage!!.contains("⚠️")) Color(0xFFD97706) else Color.Red,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    errorMessage = null
                                    isLoading = true
                                    retryTrigger++
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(localized("重试", "Retry"), fontSize = 13.sp)
                            }
                        }
                    }
                    userData != null -> {
                        UserInfoDisplay(
                            userData = userData!!,
                            plateNumber = plateNumber,
                            isLoggedIn = isLoggedIn,
                            onEditClick = { 
                                showUserForm = true
                                showQrCodeDialog = true
                            }
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = Color(0xFFE2E8F0)
                )

                PlateNumberSection(
                    plateNumber = plateNumber,
                    onPlateNumberChange = {
                        plateNumber = it
                        plateSaved = false
                    },
                    onSave = {
                        addressPrefs.edit()
                            .putString(KEY_PLATE_NUMBER, plateNumber.trim())
                            .apply()
                        plateSaved = true
                    },
                    saved = plateSaved
                )
            }
        }
        
        // 用户信息编辑表单
        if (showUserForm && userData != null) {
            UserFormCard(
                sponsorAmount = sponsorAmount,
                onSponsorAmountChange = { sponsorAmount = it },
                contactName = contactName,
                onContactNameChange = { contactName = it },
                isUpdating = isUpdating,
                onSave = { isUpdating = true },
                onCancel = { 
                    showUserForm = false
                    showQrCodeDialog = false
                }
            )
        }
    }
    
    // 二维码弹窗
    if (showQrCodeDialog) {
        // 根据系统语言判断默认显示哪个二维码
        // 非中文环境默认显示 PayPal，中文环境默认显示微信支付
        val isChinese = remember {
            val locale = java.util.Locale.getDefault()
            locale.language == "zh" || locale.country == "CN" || locale.country == "TW" || locale.country == "HK"
        }
        
        // 0=WeChat, 1=WeChatOld, 2=PayPal
        var currentQrIndex by remember { mutableIntStateOf(if (isChinese) 0 else 2) }
        
        val qrImages = listOf(
            R.drawable.wepay,      // 0: 微信支付主二维码
            R.drawable.wepayold,   // 1: 微信支付备用二维码
            R.drawable.paypal      // 2: PayPal 二维码
        )
        
        val qrDescriptions = listOf(
            localized("微信支付赞助二维码", "WeChat Pay sponsor QR code"),
            localized("微信支付备用二维码", "WeChat Pay alternate QR code"),
            "PayPal sponsor QR code"
        )
        
        val qrTitles = listOf(
            localized("微信支付", "WeChat Pay"),
            localized("微信支付 (备用)", "WeChat Pay (Alt)"),
            "PayPal"
        )
        
        AlertDialog(
            onDismissRequest = { showQrCodeDialog = false },
            title = {
                Column {
                    Text(
                        text = localized("赞助二维码", "Sponsor QR Code") + " - ${qrTitles[currentQrIndex]}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    // PayPal 专属友好提示
                    if (currentQrIndex == 2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFEFF6FF),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("💙", fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Support Open Source Development",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1E40AF)
                                    )
                                }
                                Text(
                                    text = "Your contribution helps us build better autonomous driving features for everyone. Every donation, big or small, makes a real difference!",
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFF1E40AF)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🎯 Suggested: $10-50",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF2563EB)
                                    )
                                    Text(
                                        text = "Thank you! 🙏",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF059669)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = qrImages[currentQrIndex]),
                        contentDescription = qrDescriptions[currentQrIndex],
                        modifier = Modifier
                            .size(250.dp)
                            .shadow(4.dp, RoundedCornerShape(8.dp))
                            .clickable { 
                                // 循环切换: WeChat -> WeChatOld -> PayPal -> WeChat
                                currentQrIndex = (currentQrIndex + 1) % qrImages.size
                            },
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 根据不同支付方式显示不同的提示
                    when (currentQrIndex) {
                        2 -> {
                            // PayPal 提示
                            Text(
                                text = "Scan with PayPal app or camera",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF0070BA)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap image to switch to WeChat Pay",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        else -> {
                            // 微信支付提示
                            Text(
                                text = localized("扫码赞助", "Scan to sponsor"),
                                fontSize = 14.sp,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = localized("点击图片切换其他支付方式", "Tap image to switch payment method"),
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    // 指示器
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        qrImages.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == currentQrIndex) 8.dp else 6.dp)
                                    .background(
                                        color = if (index == currentQrIndex) Color(0xFF3B82F6) else Color(0xFFCBD5E1),
                                        shape = RoundedCornerShape(50)
                                    )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showQrCodeDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentQrIndex == 2) Color(0xFF0070BA) else Color(0xFF3B82F6)
                    )
                ) {
                    Text(localized("确定", "OK"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrCodeDialog = false }) {
                    Text(localized("关闭", "Close"))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
    
    // 处理保存操作
    LaunchedEffect(isUpdating) {
        if (isUpdating && showUserForm && userData != null) {
            try {
                updateUserData(
                    deviceId,
                    sponsorAmount.toFloatOrNull() ?: 0f,
                    loginEmail,
                    contactName.trim()
                )
                userData = fetchUserData(deviceId)
                showUserForm = false
            } catch (e: Exception) {
                errorMessage = localized("保存失败: ${e.message}", "Save failed: ${e.message}")
            } finally {
                isUpdating = false
            }
        }
    }
}

/**
 * 用户信息显示组件
 */
@Composable
private fun UserInfoDisplay(
    userData: UserData,
    plateNumber: String,
    isLoggedIn: Boolean,
    onEditClick: () -> Unit
) {
    Column {
        InfoRow(localized("设备ID", "Device ID"), userData.deviceId)
        
        val userTypeText = when (userData.userType) {
            -1 -> localized("管理员专用", "Admin")
            0 -> localized("未知用户", "Unknown")
            1 -> localized("新用户", "New User")
            2 -> localized("支持者", "Supporter")
            3 -> localized("赞助者", "Sponsor")
            4 -> localized("铁粉", "Super Fan")
            else -> localized("未知类型", "Unknown Type")
        }
        InfoRow(localized("用户类型", "User Type"), userTypeText)
        
        InfoRow(localized("邮箱", "Email"), userData.email.ifEmpty { localized("未绑定", "Not bound") })
        
        InfoRow(
            localized("微信/Discord", "WeChat/Discord"),
            userData.contactName.ifEmpty { localized("未填写", "Not set") }
        )

        InfoRow(
            localized("车牌号", "License Plate"),
            plateNumber.ifEmpty { localized("未填写", "Not set") }
        )
        
        InfoRow(localized("赞助金额", "Sponsor Amount"), "${userData.sponsorAmount}${localized("元", " CNY")}")
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onEditClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = isLoggedIn,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3B82F6),
                disabledContainerColor = Color(0xFFCBD5E1)
            )
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = localized("编辑", "Edit"),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(localized("编辑用户信息", "Edit User Info"))
        }
        
        if (!isLoggedIn) {
            Text(
                text = localized("请先登录绑定邮箱后才能编辑", "Please login and bind email first"),
                fontSize = 11.sp,
                color = Color(0xFFEF4444),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 信息行组件
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF64748B)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1E293B)
        )
    }
}

/**
 * 用户信息编辑表单卡片
 */
@Composable
private fun UserFormCard(
    sponsorAmount: String,
    onSponsorAmountChange: (String) -> Unit,
    contactName: String,
    onContactNameChange: (String) -> Unit,
    isUpdating: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var sponsorAmountError by remember { mutableStateOf("") }
    
    fun isValidDecimal(value: String): Boolean {
        if (value.isEmpty()) return true
        val parts = value.split(".")
        return when (parts.size) {
            1 -> true
            2 -> parts[1].length <= 1
            else -> false
        }
    }
    
    fun validateForm(): Boolean {
        val amount = sponsorAmount.toFloatOrNull()
        if (sponsorAmount.isBlank()) {
            sponsorAmountError = localized("赞助金额不能为空", "Sponsor amount is required")
            return false
        } else if (amount == null) {
            sponsorAmountError = localized("请输入有效的数字", "Please enter a valid number")
            return false
        } else if (amount < 1) {
            sponsorAmountError = localized("赞助金额不能小于1元", "Amount must be at least 1 CNY")
            return false
        } else if (amount > 9999) {
            sponsorAmountError = localized("赞助金额不能超过9999元", "Amount cannot exceed 9999 CNY")
            return false
        } else if (!isValidDecimal(sponsorAmount)) {
            sponsorAmountError = localized("最多只能输入一位小数", "One decimal place max")
            return false
        } else {
            sponsorAmountError = ""
        }
        return true
    }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = localized("编辑用户信息", "Edit User Info"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            OutlinedTextField(
                value = contactName,
                onValueChange = { onContactNameChange(it.take(30)) },
                label = { Text(localized("微信名称", "Discord Username")) },
                placeholder = { Text(localized("请输入微信昵称", "Enter Discord username")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        localized("用于核实赞助信息", "Used to verify sponsor info"),
                        color = Color(0xFF64748B)
                    )
                }
            )
            
            OutlinedTextField(
                value = sponsorAmount,
                onValueChange = onSponsorAmountChange,
                label = { Text(localized("赞助金额 *", "Sponsor Amount *")) },
                placeholder = { Text(localized("请输入赞助金额，务必正确输入", "Enter sponsor amount accurately")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = sponsorAmountError.isNotEmpty(),
                supportingText = if (sponsorAmountError.isNotEmpty()) { 
                    { Text(sponsorAmountError, color = Color.Red) } 
                } else { 
                    { Text(localized("注意正确填写，最多一位小数", "One decimal place max"), color = Color(0xFF64748B)) }
                }
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF94A3B8))
                ) {
                    Text(localized("取消", "Cancel"))
                }
                
                Button(
                    onClick = {
                        if (validateForm()) {
                            onSave()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isUpdating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                    } else {
                        Text(localized("保存", "Save"))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlateNumberSection(
    plateNumber: String,
    onPlateNumberChange: (String) -> Unit,
    onSave: () -> Unit,
    saved: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = localized("车牌号设置", "License Plate"),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B)
        )

        OutlinedTextField(
            value = plateNumber,
            onValueChange = { onPlateNumberChange(it.take(10).uppercase()) },
            label = { Text(localized("车牌号（限行避开）", "License Plate")) },
            placeholder = { Text(localized("如 京A12345", "e.g. 京A12345")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            supportingText = {
                Text(
                    localized("填写后，腾讯导航算路将自动避开限行区域", "Tencent Nav will avoid traffic restriction zones when set"),
                    color = Color(0xFF64748B)
                )
            }
        )

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(localized("保存车牌号", "Save License Plate"), fontSize = 13.sp)
        }

        if (saved) {
            Text(
                text = localized("已保存", "Saved"),
                fontSize = 11.sp,
                color = Color(0xFF10B981)
            )
        }
    }
}

/**
 * 检查是否有互联网连接（非局域网）
 */
private fun hasInternetConnection(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    // 必须有 INTERNET 能力，且验证过可达（排除仅局域网的WiFi热点）
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
           caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * 获取用户数据 - 主要用 IP，备用域名
 */
private suspend fun fetchUserData(deviceId: String): UserData = withContext(Dispatchers.IO) {
    data class EndpointConfig(val url: String, val connectTimeout: Int, val readTimeout: Int)
    
    val endpoints = listOf(
        EndpointConfig("http://31.97.51.107:8600/api/user/$deviceId", 3000, 5000),   // 主要：IP 直连，超时短
        EndpointConfig("https://md.jixiexiaoge.com/api/user/$deviceId", 8000, 8000)  // 备用：域名 + HTTPS，超时长
    )
    
    val errors = mutableListOf<String>()
    
    for ((index, endpoint) in endpoints.withIndex()) {
        try {
            android.util.Log.d("ProfilePage", "尝试连接 (${index + 1}/${endpoints.size}): ${endpoint.url}")
            
            val url = URL(endpoint.url)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                connectTimeout = endpoint.connectTimeout
                readTimeout = endpoint.readTimeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "NaviPilot/1.0")
                setRequestProperty("Connection", "close")
            }
            
            val responseCode = connection.responseCode
            android.util.Log.d("ProfilePage", "响应码: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                
                if (jsonObject.getBoolean("success")) {
                    val data = jsonObject.getJSONObject("data")
                    val protocol = if (endpoint.url.startsWith("https")) "HTTPS" else "HTTP"
                    val target = if (endpoint.url.contains("31.97")) "IP" else "域名"
                    android.util.Log.i("ProfilePage", "✅ 用户数据获取成功 ($target + $protocol)")
                    return@withContext UserData(
                        deviceId = data.getString("device_id"),
                        sponsorAmount = data.optDouble("sponsor_amount", 0.0).toFloat(),
                        userType = data.optInt("user_type", 0),
                        email = data.optString("email", ""),
                        contactName = data.optString("wechat_name", "")
                    )
                } else {
                    throw Exception("API返回失败: ${jsonObject.optString("message", "未知错误")}")
                }
            } else if (responseCode == 404) {
                // 用户不存在，无需尝试备用地址
                throw Exception("404")
            } else {
                throw Exception("HTTP错误: $responseCode")
            }
        } catch (e: Exception) {
            errors.add("[${index + 1}] ${e.message}")
            android.util.Log.w("ProfilePage", "连接失败 (${index + 1}/${endpoints.size}): ${e.message}")
            // 404 表示用户不存在，直接抛出不再尝试备用
            if (e.message == "404") {
                throw Exception("用户不存在(404)")
            }
            if (index < endpoints.size - 1) {
                delay(200)
            }
        }
    }
    
    val summary = errors.joinToString("; ")
    android.util.Log.e("ProfilePage", "所有连接尝试均失败: $summary")
    throw Exception(localized("网络连接失败: $summary", "Connection failed: $summary"))
}

/**
 * 注册新用户 - 主要用 IP，备用域名
 */
private suspend fun registerUser(deviceId: String): UserData = withContext(Dispatchers.IO) {
    data class EndpointConfig(val url: String, val connectTimeout: Int, val readTimeout: Int)
    
    val endpoints = listOf(
        EndpointConfig("http://31.97.51.107:8600/api/user/register", 3000, 5000),
        EndpointConfig("https://md.jixiexiaoge.com/api/user/register", 8000, 8000)
    )
    
    val errors = mutableListOf<String>()
    
    for ((index, endpoint) in endpoints.withIndex()) {
        try {
            android.util.Log.i("ProfilePage", "尝试注册新用户 (${index + 1}/${endpoints.size}): $deviceId")
            
            val url = URL(endpoint.url)
            val connection = url.openConnection() as HttpURLConnection
            
            val requestBody = JSONObject().apply {
                put("device_id", deviceId)
                put("usage_count", 0)
                put("usage_duration", 0)
                put("total_distance", 0)
            }.toString()
            
            android.util.Log.d("ProfilePage", "注册请求数据: $requestBody")
            
            connection.apply {
                requestMethod = "POST"
                connectTimeout = endpoint.connectTimeout
                readTimeout = endpoint.readTimeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "NaviPilot/1.0")
                setRequestProperty("Connection", "close")
                doOutput = true
            }
            
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toByteArray())
            }
            
            val responseCode = connection.responseCode
            android.util.Log.d("ProfilePage", "注册响应码: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                android.util.Log.d("ProfilePage", "注册响应数据: $response")
                
                val jsonObject = JSONObject(response)
                val userType = jsonObject.optInt("user_type", 1)
                
                val protocol = if (endpoint.url.startsWith("https")) "HTTPS" else "HTTP"
                val target = if (endpoint.url.contains("31.97")) "IP" else "域名"
                android.util.Log.i("ProfilePage", "✅ 新用户注册成功 ($target + $protocol), 用户类型: $userType")
                
                return@withContext UserData(
                    deviceId = deviceId,
                    userType = userType,
                    sponsorAmount = 0f
                )
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "无错误信息"
                android.util.Log.e("ProfilePage", "注册失败，响应码: $responseCode, 错误信息: $errorResponse")
                throw Exception("HTTP错误: $responseCode, $errorResponse")
            }
        } catch (e: Exception) {
            errors.add("[${index + 1}] ${e.message}")
            android.util.Log.w("ProfilePage", "注册失败 (${index + 1}/${endpoints.size}): ${e.message}")
            if (index < endpoints.size - 1) {
                delay(200)
            }
        }
    }
    
    val summary = errors.joinToString("; ")
    android.util.Log.e("ProfilePage", "所有注册尝试均失败: $summary")
    throw Exception(localized("注册失败: $summary", "Registration failed: $summary"))
}

/**
 * 更新用户数据 - 赞助金额 + 邮箱 - 主要用 IP，备用域名
 */
private suspend fun updateUserData(
    deviceId: String, 
    sponsorAmount: Float,
    email: String,
    contactName: String = ""
): UserData = withContext(Dispatchers.IO) {
    data class EndpointConfig(val url: String, val connectTimeout: Int, val readTimeout: Int)
    
    val endpoints = listOf(
        EndpointConfig("http://31.97.51.107:8600/api/user/update", 3000, 5000),
        EndpointConfig("https://md.jixiexiaoge.com/api/user/update", 8000, 8000)
    )
    
    val errors = mutableListOf<String>()
    
    for ((index, endpoint) in endpoints.withIndex()) {
        try {
            val url = URL(endpoint.url)
            val connection = url.openConnection() as HttpURLConnection
            
            // 根据赞助金额确定用户类型
            val userType = when {
                sponsorAmount < 35 -> 2  // 支持者
                sponsorAmount < 90 -> 3  // 赞助者
                else -> 4               // 铁粉
            }
            
            val requestBody = JSONObject().apply {
                put("device_id", deviceId)
                put("sponsor_amount", sponsorAmount)
                put("user_type", userType)
                if (email.isNotBlank()) {
                    put("email", email)
                }
                if (contactName.isNotBlank()) {
                    put("wechat_name", contactName)
                }
            }.toString()
            
            connection.apply {
                requestMethod = "POST"
                connectTimeout = endpoint.connectTimeout
                readTimeout = endpoint.readTimeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "NaviPilot/1.0")
                setRequestProperty("Connection", "close")
                doOutput = true
            }
            
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                
                if (jsonObject.getBoolean("success")) {
                    val data = jsonObject.getJSONObject("data")
                    val protocol = if (endpoint.url.startsWith("https")) "HTTPS" else "HTTP"
                    val target = if (endpoint.url.contains("31.97")) "IP" else "域名"
                    android.util.Log.i("ProfilePage", "✅ 用户数据更新成功 ($target + $protocol)")
                    return@withContext UserData(
                        deviceId = data.getString("device_id"),
                        sponsorAmount = data.getDouble("sponsor_amount").toFloat(),
                        userType = data.getInt("user_type"),
                        email = data.optString("email", ""),
                        contactName = data.optString("wechat_name", "")
                    )
                } else {
                    throw Exception("API返回失败: ${jsonObject.optString("message", "未知错误")}")
                }
            } else {
                throw Exception("HTTP错误: $responseCode")
            }
        } catch (e: Exception) {
            errors.add("[${index + 1}] ${e.message}")
            android.util.Log.w("ProfilePage", "更新失败 (${index + 1}/${endpoints.size}): ${e.message}")
            if (index < endpoints.size - 1) {
                delay(200)
            }
        }
    }
    
    val summary = errors.joinToString("; ")
    android.util.Log.e("ProfilePage", "所有更新尝试均失败: $summary")
    throw Exception(localized("更新失败: $summary", "Update failed: $summary"))
}

/**
 * 登录卡片 - 仅保留邮箱验证码登录
 */
@Composable
private fun LoginCard(
    deviceId: String,
    isLoggedIn: Boolean,
    loginEmail: String,
    onLoginSuccess: (email: String) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 邮箱登录状态
    var emailInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    var emailLoading by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf("") }
    
    // 倒计时效果
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    localized("账号登录", "Account Login"),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B)
                )
            }
            
            if (isLoggedIn) {
                Surface(color = Color(0xFFECFDF5), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                localized("已登录", "Logged In"),
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoRow(localized("邮箱", "Email"), loginEmail)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onLogout,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(localized("退出登录", "Logout"))
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it; emailError = "" },
                    label = { Text(localized("邮箱地址", "Email Address"), fontSize = 12.sp) },
                    placeholder = { Text("user@example.com", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(localized("验证码", "Code"), fontSize = 12.sp) },
                        placeholder = { Text("123456", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    Button(
                        onClick = {
                            if (emailInput.isBlank() || !emailInput.contains("@")) {
                                emailError = localized("请输入有效邮箱", "Invalid email")
                                return@Button
                            }
                            if (!hasInternetConnection(context)) {
                                emailError = localized("无互联网连接，请切换到有网络的WiFi", "No internet. Switch to a network with internet access.")
                                return@Button
                            }
                            emailLoading = true
                            emailError = ""
                            scope.launch {
                                try {
                                    sendEmailVerificationCode(emailInput, deviceId)
                                    cooldown = 60
                                } catch (e: Exception) {
                                    emailError = e.message ?: localized("发送失败", "Send failed")
                                } finally {
                                    emailLoading = false
                                }
                            }
                        },
                        enabled = cooldown == 0 && !emailLoading,
                        modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (emailLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (cooldown > 0) "${cooldown}s" else localized("发送", "Send"),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (emailError.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(emailError, fontSize = 11.sp, color = Color.Red)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (codeInput.length != 6) {
                            emailError = localized("请输入6位验证码", "Enter 6-digit code")
                            return@Button
                        }
                        if (!hasInternetConnection(context)) {
                            emailError = localized("无互联网连接，请切换到有网络的WiFi", "No internet. Switch to a network with internet access.")
                            return@Button
                        }
                        emailLoading = true
                        emailError = ""
                        scope.launch {
                            try {
                                verifyEmailCode(emailInput, codeInput, deviceId)
                                onLoginSuccess(emailInput)
                            } catch (e: Exception) {
                                emailError = e.message ?: localized("验证失败", "Verification failed")
                            } finally {
                                emailLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = codeInput.length == 6 && !emailLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (emailLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(localized("验证并登录", "Verify & Login"))
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                Text(
                    localized(
                        "登录后邮箱将与设备ID绑定，方便跨设备同步数据",
                        "Login binds your email to device ID for cross-device sync"
                    ),
                    fontSize = 10.sp, color = Color(0xFF94A3B8)
                )
                        }
        }
    }
}

// ==================== 登录 API 函数 ====================

/**
 * 发送邮箱验证码 - 主要用 IP，备用域名
 */
private suspend fun sendEmailVerificationCode(email: String, deviceId: String) = withContext(Dispatchers.IO) {
    data class EndpointConfig(val url: String, val connectTimeout: Int, val readTimeout: Int)
    
    val endpoints = listOf(
        EndpointConfig("http://31.97.51.107:8600/api/auth/email/send", 3000, 5000),
        EndpointConfig("https://md.jixiexiaoge.com/api/auth/email/send", 8000, 8000)
    )
    
    val errors = mutableListOf<String>()
    
    for ((index, endpoint) in endpoints.withIndex()) {
        try {
            val url = URL(endpoint.url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = endpoint.connectTimeout
                readTimeout = endpoint.readTimeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("email", email)
                put("device_id", deviceId)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val protocol = if (endpoint.url.startsWith("https")) "HTTPS" else "HTTP"
                val target = if (endpoint.url.contains("31.97")) "IP" else "域名"
                android.util.Log.i("ProfilePage", "✅ 验证码发送成功 ($target + $protocol)")
                return@withContext
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $code: $err")
            }
        } catch (e: Exception) {
            errors.add("[${index + 1}] ${e.message}")
            android.util.Log.w("ProfilePage", "发送验证码失败 (${index + 1}/${endpoints.size}): ${e.message}")
            if (index < endpoints.size - 1) {
                delay(200)
            }
        }
    }
    
    throw Exception(errors.joinToString("; "))
}

/**
 * 验证邮箱验证码 - 主要用 IP，备用域名
 */
private suspend fun verifyEmailCode(email: String, code: String, deviceId: String): JSONObject = withContext(Dispatchers.IO) {
    data class EndpointConfig(val url: String, val connectTimeout: Int, val readTimeout: Int)
    
    val endpoints = listOf(
        EndpointConfig("http://31.97.51.107:8600/api/auth/email/verify", 3000, 8000),
        EndpointConfig("https://md.jixiexiaoge.com/api/auth/email/verify", 8000, 10000)
    )
    
    val errors = mutableListOf<String>()
    
    for ((index, endpoint) in endpoints.withIndex()) {
        try {
            val url = URL(endpoint.url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = endpoint.connectTimeout
                readTimeout = endpoint.readTimeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("email", email)
                put("code", code)
                put("device_id", deviceId)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            
            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val protocol = if (endpoint.url.startsWith("https")) "HTTPS" else "HTTP"
                val target = if (endpoint.url.contains("31.97")) "IP" else "域名"
                android.util.Log.i("ProfilePage", "✅ 验证码验证成功 ($target + $protocol)")
                return@withContext JSONObject(resp)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val serverMsg = try {
                    JSONObject(err).optString("message", "")
                } catch (_: Exception) { "" }
                val displayMsg = if (serverMsg.isNotBlank()) serverMsg
                    else localized("验证码错误或已过期 (HTTP $responseCode)", "Invalid or expired code (HTTP $responseCode)")
                android.util.Log.w("ProfilePage", "验证码验证失败: HTTP $responseCode, 服务器返回: $err")
                throw Exception(displayMsg)
            }
        } catch (e: Exception) {
            errors.add("[${index + 1}] ${e.message}")
            android.util.Log.w("ProfilePage", "验证失败 (${index + 1}/${endpoints.size}): ${e.message}")
            // 如果服务器已明确响应（业务错误），不再重试备用地址
            val isServerReject = e.message?.let { 
                it.contains("HTTP ") || it.contains("验证码") || it.contains("expired") || it.contains("Invalid")
            } ?: false
            if (isServerReject) break
            if (index < endpoints.size - 1) {
                delay(200)
            }
        }
    }
    
    throw Exception(errors.joinToString("; "))
}

