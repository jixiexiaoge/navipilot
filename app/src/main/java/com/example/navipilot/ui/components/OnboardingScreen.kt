package com.example.navipilot.ui.components

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.ui.utils.localized
import kotlinx.coroutines.launch

private const val ONBOARDING_PREFS = "onboarding"
private const val KEY_COMPLETED = "onboarding_completed"

fun isOnboardingCompleted(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPLETED, false)

fun setOnboardingCompleted(context: Context) {
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_COMPLETED, true).apply()
}

private data class OnboardingPage(
    val emoji: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
    val gradient: Brush,
    val bgGradient: Brush   // 页面整体背景渐变
)

private val pages = listOf(
    OnboardingPage(
        "🗺️",
        "多模式导航 · 免费畅行",
        "Multi-Mode Navigation · Free",
        "默认高德车机版导航（完全免费），支持车道级引导、实时路况、电子眼播报。\n" +
        "亦可切换 腾讯SDK / Google / OSM 导航模式——三模式互斥，数据零冲突。\n" +
        "导航数据通过 UDP 7706 / TCP 7709 实时发送至 comma3/openpilot。",
        "Default AMap Auto (free) with lane guidance, live traffic & camera alerts.\n" +
        "Switch to Tencent/Google/OSM navigation — mutually exclusive, no data clash.\n" +
        "Navigation data sent to comma3/openpilot via UDP 7706 / TCP 7709.",
        Brush.linearGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D))),
        Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF0D1B2A), Color(0xFF0F172A)))
    ),
    OnboardingPage(
        "🚗",
        "智能超车 · comma3 联动",
        "Smart Overtake · comma3 Link",
        "三模式超车决策：拨杆超车 / 自动变道 / 禁止超车，可设保守/标准/激进风格。\n" +
        "ML Kit 视觉检测邻道车辆，3 帧防抖防误判，TBT 方向偏好避让出口。\n" +
        "ZMQ 7710 发控指令，HTTP 7000 读写 comma3 参数，SSH 远程管理设备。",
        "3-mode overtaking: stalk/auto/off, with conservative/standard/aggressive profiles.\n" +
        "ML Kit vision detects adjacent lanes, 3-frame debounce, TBT exit avoidance.\n" +
        "ZMQ 7710 commands, HTTP 7000 param R/W, SSH remote device management.",
        Brush.linearGradient(listOf(Color(0xFFFC5C7D), Color(0xFF6A82FB))),
        Brush.linearGradient(listOf(Color(0xFF1A0A2E), Color(0xFF16213E), Color(0xFF0F172A)))
    ),
    OnboardingPage(
        "📊",
        "驾驶评分 · 成就报告",
        "Driving Score · Achievements",
        "五维评分：平稳性(30%) · 预判力(25%) · 接管依赖(20%) · 节能(15%) · NOO稳定(10%)\n" +
        "每次行程自动记录，Canvas 雷达图 + 历史趋势 + 驾驶风格标签。\n" +
        "8 项成就系统（百公里达人、稳如泰山、智驾先锋……），一键分享驾驶报告。",
        "5D scoring: Smoothness(30%) · Prediction(25%) · Intervention(20%) · Eco(15%) · Stability(10%)\n" +
        "Auto-recorded trips, Canvas radar chart, history trends, driving style tags.\n" +
        "8 achievements (100km Club, Steady Ace, NOO Pioneer…), one-tap share.",
        Brush.linearGradient(listOf(Color(0xFFF093FB), Color(0xFFF5576C))),
        Brush.linearGradient(listOf(Color(0xFF2D1B69), Color(0xFF1A1A2E), Color(0xFF0F172A)))
    ),
    OnboardingPage(
        "🤖",
        "模型管理 · LED 点阵屏",
        "Model Manager · LED Matrix",
        "从 JihuLab 拉取 openpilot 模型清单（JSON 签名验证），多文件并行下载。\n" +
        "SSH 密钥认证一键上传至 comma3，支持模型删除/切换。\n" +
        "蓝牙 LED 点阵屏（0x5E UART 协议）：20 级优先级自动显示车速、导航指令、电子眼……",
        "Fetch openpilot model manifests from JihuLab (signed JSON), parallel downloads.\n" +
        "SSH key-auth upload to comma3, model delete/switch supported.\n" +
        "BLE LED matrix (0x5E UART): 20-level auto-display for speed, TBT, cameras…",
        Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2))),
        Brush.linearGradient(listOf(Color(0xFF1A1A3E), Color(0xFF16213E), Color(0xFF0F172A)))
    ),
    OnboardingPage(
        "🧪",
        "条件实验 · 高阶功能",
        "Conditional Experiment · Pro",
        "7 种驾驶条件自动切换 openpilot 实验/Chill 模式：弯道 · 前车 · 低速 · 导航转弯 · 测速 · 停车 · 巡航调速\n" +
        "GCJ-02 ↔ WGS-84 坐标转换，CommaDeviceDiscovery 局域网自动发现设备。\n" +
        "TCP 7711 接收设备状态（心跳/指数退避重连），驾驶报告分享、停车位置记录……\n" +
        "⚠️ 实验模式风险由用户自行承担。",
        "7 driving conditions auto-switch openpilot Experimental/Chill: curve · lead · low-speed · turn · camera · stop · cruise.\n" +
        "GCJ-02 ↔ WGS-84 conversion, mDNS device discovery, TCP 7711 status with heartbeat & backoff reconnection.\n" +
        "Driving report sharing, parking location, privacy compliance…\n" +
        "⚠️ Experimental features are at your own risk.",
        Brush.linearGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D))),
        Brush.linearGradient(listOf(Color(0xFF2D1B1B), Color(0xFF1A1A2E), Color(0xFF0F172A)))
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // 当前页过渡动画
    val targetBgGradient = pages[pagerState.currentPage].bgGradient

    // 背景色渐变过渡
    val bgProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(targetBgGradient)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(50.dp))

            // ===== 页面主内容（水平滑动） =====
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                pageSpacing = 0.dp,
                userScrollEnabled = true,
                beyondViewportPageCount = 0
            ) { page ->
                val p = pages[page]
                // 内容卡片——玻璃拟态效果
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 图标圆形背景（带发光阴影效果）
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(p.gradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p.emoji, fontSize = 54.sp)
                    }

                    Spacer(Modifier.height(40.dp))

                    // 标题
                    Text(
                        text = localized(p.titleZh, p.titleEn),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(16.dp))

                    // 描述（带半透明背景的圆角卡片）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 20.dp, vertical = 22.dp)
                    ) {
                        Text(
                            text = localized(p.descZh, p.descEn),
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = Color(0xFFCBD5E1),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 页面指示器（弹簧动画） =====
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                pages.indices.forEach { i ->
                    val isSelected = i == pagerState.currentPage

                    // 选中用弹簧动画，非选中用默认
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 28.dp else 8.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "dotWidth"
                    )
                    val dotAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.35f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "dotAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .width(dotWidth)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSelected) Color(0xFF3B82F6)
                                else Color(0xFF475569).copy(alpha = dotAlpha)
                            )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 底部按钮栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 20.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：上一步 / 跳过
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    ) {
                        Text(
                            localized("上一步", "Back"),
                            color = Color(0xFF94A3B8),
                            fontSize = 15.sp
                        )
                    }
                } else {
                    TextButton(onClick = onComplete) {
                        Text(
                            localized("跳过", "Skip"),
                            color = Color(0xFF94A3B8),
                            fontSize = 15.sp
                        )
                    }
                }

                // 右侧：下一步 / 开始使用
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                        if (pagerState.currentPage < pages.size - 1) {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3B82F6),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .widthIn(min = 120.dp)
                ) {
                    Text(
                        text = if (pagerState.currentPage < pages.size - 1)
                            localized("下一步", "Next")
                        else
                            localized("开始使用", "Get Started"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
