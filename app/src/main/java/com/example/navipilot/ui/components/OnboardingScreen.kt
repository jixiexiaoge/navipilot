package com.example.navipilot.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.ui.utils.localized

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
    val gradient: Brush
)

private val pages = listOf(
    OnboardingPage(
        "🗺️", "免费导航，高德车机版",
        "Free Navigation - AMap Auto",
        "默认使用高德车机版导航，完全免费。\n支持车道级引导、实时路况、电子眼播报。\n也可切换腾讯/OSM等其他导航模式。",
        "Default AMap Auto navigation, completely free.\nLane-level guidance, real-time traffic, speed camera alerts.\nAlso supports Tencent/OSM and other navigation modes.",
        Brush.linearGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D)))
    ),
    OnboardingPage(
        "🚗", "智能超车辅助",
        "Smart Overtake Assist",
        "自动检测与前车速度差，智能判断超车时机。\n支持拨杆变道和自动变道两种模式。\n可在设置中切换超车策略。",
        "Auto-detects speed difference with lead vehicle.\nSupports stalk-activated or automatic lane changes.\nOvertake strategy is configurable in settings.",
        Brush.linearGradient(listOf(Color(0xFFFC5C7D), Color(0xFF6A82FB)))
    ),
    OnboardingPage(
        "📊", "驾驶评分系统",
        "Driving Score System",
        "五维评分：平稳性、预判力、接管依赖、节能、NOO 稳定度。\n每次行程自动记录，查看历史趋势。",
        "5D scoring: Smoothness, Prediction, Intervention, Eco, NOO Stability.\nEach trip is auto-recorded with history trends.",
        Brush.linearGradient(listOf(Color(0xFFF093FB), Color(0xFFF5576C)))
    ),
    OnboardingPage(
        "🤖", "模型切换",
        "Model Switching",
        "支持多种驾驶模型切换。\n可选：AutoPilot、Comfort、Sport 等模式。\n根据路况和个人喜好自由选择。",
        "Support multiple driving model switching.\nOptions: AutoPilot, Comfort, Sport, etc.\nFreely choose based on road conditions and preferences.",
        Brush.linearGradient(listOf(Color(0xFF667EEA), Color(0xFF764BA2)))
    ),
    OnboardingPage(
        "🧪", "实验模式",
        "Experimental Mode",
        "启用未完全测试的新功能。\n包括高级 NOA、自动泊车等前瞻特性。\n⚠️ 实验模式风险由用户自行承担。",
        "Enable untested new features.\nIncludes advanced NOA, auto parking and more.\n⚠️ Risks in experimental mode are at your own risk.",
        Brush.linearGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D)))
    )
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // 页面内容
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
                },
                modifier = Modifier.weight(1f),
                label = "onboarding"
            ) { page ->
                val p = pages[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Emoji icon with gradient background
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(p.gradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p.emoji, fontSize = 48.sp)
                    }

                    Spacer(Modifier.height(32.dp))

                    Text(
                        text = localized(p.titleZh, p.titleEn),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = localized(p.descZh, p.descEn),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 页面指示器
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                pages.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == currentPage) 24.dp else 8.dp, 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (i == currentPage) Color(0xFF3B82F6) else Color(0xFF475569)
                            )
                    )
                }
            }

            // 按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentPage > 0) {
                    TextButton(onClick = { currentPage-- }) {
                        Text(localized("上一步", "Back"), color = Color(0xFF94A3B8))
                    }
                } else {
                    TextButton(onClick = onComplete) {
                        Text(localized("跳过", "Skip"), color = Color(0xFF94A3B8))
                    }
                }

                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        } else {
                            onComplete()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        if (currentPage < pages.size - 1)
                            localized("下一步", "Next")
                        else
                            localized("开始使用", "Get Started"),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
