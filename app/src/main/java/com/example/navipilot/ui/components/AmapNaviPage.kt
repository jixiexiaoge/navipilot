package com.example.navipilot.ui.components

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.ui.theme.NavipilotTheme
import com.example.navipilot.ui.utils.localized
import com.example.navipilot.CarrotManFields

/**
 * 高德导航页面
 *
 * 高德导航通过独立的Activity运行，这里提供启动入口和状态显示
 */
@Composable
fun AmapNaviPage(
    carrotManFields: CarrotManFields,
    onBack: () -> Unit,
    onLaunchNavi: (() -> Unit)? = null,  // 可选的导航启动回调
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLaunching by remember { mutableStateOf(false) }

    // 格式化距离
    val formattedDist = remember(carrotManFields.nGoPosDist) {
        when {
            carrotManFields.nGoPosDist <= 0 -> "-"
            carrotManFields.nGoPosDist >= 1000 -> "%.1f km".format(carrotManFields.nGoPosDist / 1000.0)
            else -> "${carrotManFields.nGoPosDist} m"
        }
    }

    // 格式化时间
    val formattedTime = remember(carrotManFields.nGoPosTime) {
        when {
            carrotManFields.nGoPosTime <= 0 -> "-"
            carrotManFields.nGoPosTime >= 3600 -> "${carrotManFields.nGoPosTime / 3600}h ${(carrotManFields.nGoPosTime % 3600) / 60}min"
            carrotManFields.nGoPosTime >= 60 -> "${carrotManFields.nGoPosTime / 60} min"
            else -> "${carrotManFields.nGoPosTime} s"
        }
    }

    NavipilotTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF1E293B))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 标题
            Text(
                text = "🗺️ " + localized("高德导航", "AMap Navigation"),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = localized(
                    "点击下方按钮启动高德地图导航",
                    "Tap the button below to launch AMap navigation"
                ),
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 启动导航按钮
            Button(
                onClick = {
                    isLaunching = true
                    onLaunchNavi?.invoke()
                    isLaunching = false
                },
                enabled = !isLaunching,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isLaunching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = localized("启动高德导航", "Launch AMap"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 导航状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = localized("📍 导航状态", "📍 Navigation Status"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Surface(
                            color = if (carrotManFields.isNavigating) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF64748B).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (carrotManFields.isNavigating) localized("进行中", "Active") else localized("未开始", "Idle"),
                                fontSize = 12.sp,
                                color = if (carrotManFields.isNavigating) Color(0xFF10B981) else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF475569))
                    Spacer(modifier = Modifier.height(16.dp))

                    // 目的地
                    InfoRow(
                        label = localized("目的地", "Destination"),
                        value = carrotManFields.szGoalName.ifEmpty { "-" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 剩余距离
                    InfoRow(
                        label = localized("剩余距离", "Remaining Distance"),
                        value = formattedDist
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 剩余时间
                    InfoRow(
                        label = localized("剩余时间", "Remaining Time"),
                        value = formattedTime
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 道路名称
                    InfoRow(
                        label = localized("当前道路", "Current Road"),
                        value = carrotManFields.szPosRoadName.ifEmpty { "-" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 限速
                    InfoRow(
                        label = localized("道路限速", "Speed Limit"),
                        value = if (carrotManFields.nRoadLimitSpeed > 0) "${carrotManFields.nRoadLimitSpeed} km/h" else "-"
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 当前速度
                    InfoRow(
                        label = localized("当前速度", "Current Speed"),
                        value = if (carrotManFields.vEgoKph > 0) "${carrotManFields.vEgoKph} km/h" else "-"
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 返回按钮
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64748B)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = localized("返回主页", "Back to Home"),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 信息行组件
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF94A3B8)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

/**
 * 启动高德导航
 * 通过Intent启动高德导航
 */
fun launchAmapNavi(context: android.content.Context) {
    try {
        Log.i("AmapNaviPage", "🚀 启动高德导航...")

        // 尝试启动高德导航主Activity
        val naviIntent = Intent().apply {
            setClassName(
                "com.autonavi.amapauto",
                "com.autonavi.auto.MainMapActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (naviIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(naviIntent)
            Log.i("AmapNaviPage", "✅ 高德导航已启动")
        } else {
            Log.w("AmapNaviPage", "⚠️ 未找到高德导航应用")
            android.widget.Toast.makeText(
                context,
                localized("未找到高德导航应用", "AMap Navigation not found"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Exception) {
        Log.e("AmapNaviPage", "❌ 启动高德导航失败: ${e.message}", e)
        android.widget.Toast.makeText(
            context,
            "启动高德导航失败: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 启动高德导航（通过组件化导航页）
 * 使用 AmapNaviPage 进行导航
 */
fun launchAmapNaviWithParams(
    context: android.content.Context,
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double,
    startName: String = "起点",
    endName: String = "终点"
) {
    try {
        Log.i("AmapNaviPage", "🚀 启动高德导航（途经点导航）...")

        // 使用高德导航的Broadcast发送路径规划请求
        val naviIntent = Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
            putExtra("KEY_TYPE", 10076)  // 路径规划请求
            putExtra("SOURCE_APP", "Navipilot")
            putExtra("EXTRA_SLAT", startLat)
            putExtra("EXTRA_SLON", startLon)
            putExtra("EXTRA_SNAME", startName)
            putExtra("EXTRA_DLAT", endLat)
            putExtra("EXTRA_DLON", endLon)
            putExtra("EXTRA_DNAME", endName)
            putExtra("EXTRA_DEV", 0)  // 不使用坐标偏移
            putExtra("EXTRA_M", 0)   // 导航方式：0=驾乘
            setPackage("com.autonavi.amapauto")
            flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        }

        context.sendBroadcast(naviIntent)
        Log.i("AmapNaviPage", "✅ 高德导航路线已发送")
    } catch (e: Exception) {
        Log.e("AmapNaviPage", "❌ 启动高德导航失败: ${e.message}", e)
        android.widget.Toast.makeText(
            context,
            "启动高德导航失败: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

