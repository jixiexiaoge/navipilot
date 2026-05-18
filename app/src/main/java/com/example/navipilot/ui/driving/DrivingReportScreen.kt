package com.example.navipilot.ui.driving

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.scoring.*
import com.example.navipilot.ui.utils.localized
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ==================== 设计系统 ====================
private val BgPrimary = Color(0xFF0A0A0F)
private val BgCard = Color(0xFF161620)
private val BgCardElevated = Color(0xFF1C1C2A)
private val BorderSubtle = Color(0xFF2A2A3C)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentTeal = Color(0xFF64D2FF)
private val AccentGreen = Color(0xFF30D158)
private val AccentYellow = Color(0xFFFFD60A)
private val AccentOrange = Color(0xFFFF9F0A)
private val AccentRed = Color(0xFFFF453A)
private val AccentPurple = Color(0xFFBF5AF2)
private val AccentPink = Color(0xFFFF375F)
private val GoldGradStart = Color(0xFFFFD700)
private val GoldGradEnd = Color(0xFFFFA500)

private val GradientBlue = Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6)))
private val GradientGreen = Brush.linearGradient(listOf(Color(0xFF30D158), Color(0xFF34C759)))
private val GradientGold = Brush.linearGradient(listOf(GoldGradStart, GoldGradEnd))

fun scoreColor(score: Int): Color = when {
    score >= 90 -> AccentGreen
    score >= 80 -> Color(0xFF34C759)
    score >= 70 -> AccentYellow
    score >= 60 -> AccentOrange
    else -> AccentRed
}

fun scoreGradient(score: Int): Brush = when {
    score >= 90 -> Brush.linearGradient(listOf(Color(0xFF30D158), Color(0xFF63E6BE)))
    score >= 80 -> Brush.linearGradient(listOf(Color(0xFF34C759), Color(0xFF30D158)))
    score >= 70 -> Brush.linearGradient(listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A)))
    score >= 60 -> Brush.linearGradient(listOf(Color(0xFFFF9F0A), Color(0xFFFF6723)))
    else -> Brush.linearGradient(listOf(Color(0xFFFF453A), Color(0xFFFF375F)))
}

fun scoreLabel(score: Int): String = when {
    score >= 90 -> localized("优秀", "Excellent")
    score >= 80 -> localized("良好", "Good")
    score >= 70 -> localized("一般", "Average")
    score >= 60 -> localized("待提升", "Needs Work")
    else -> localized("加油", "Keep Going")
}

/** 计算会话的有效距离（优先用累计距离，否则用 avgSpeed * time 估算） */
internal fun DrivingSession.effectiveDistance(): Float =
    if (totalDistance > 0.01f) totalDistance
    else if (avgSpeed > 0 && totalTimeSeconds > 0) avgSpeed * totalTimeSeconds / 3600f
    else 0f

/** 计算会话的有效NOO距离 */
internal fun DrivingSession.effectiveNooDistance(): Float =
    if (nooDistance > 0.01f) nooDistance
    else if (avgSpeed > 0 && nooTimeSeconds > 0) avgSpeed * nooTimeSeconds / 3600f
    else 0f

/** 计算会话的有效时长（毫秒） */
internal fun DrivingSession.effectiveDurationMs(): Long =
    if (endTime > startTime) endTime - startTime
    else if (totalTimeSeconds > 0) totalTimeSeconds * 1000L
    else 0L

// ==================== 主入口 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivingReportScreen(
    onBack: () -> Unit,
    drivingDataCollector: DrivingDataCollector?
) {
    val scoreEngine = remember { DrivingScoreEngine() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val sessions = remember { mutableStateOf<List<DrivingSession>>(emptyList()) }
    val todaySessions = remember { mutableStateOf<List<DrivingSession>>(emptyList()) }
    val weekSessions = remember { mutableStateOf<List<DrivingSession>>(emptyList()) }
    val isCollecting = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            drivingDataCollector?.let {
                sessions.value = it.getAllSessionsIncludingCurrent()
                todaySessions.value = it.getTodaySessions()
                weekSessions.value = it.getWeekSessions()
                isCollecting.value = it.isCollecting()
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("驾驶报告", "Driving Report"), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        if (isCollecting.value) {
                            Spacer(Modifier.width(10.dp))
                            LiveIndicator()
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, localized("返回", "Back"), tint = AccentBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary),
                actions = {
                    val shareContext = LocalContext.current
                    IconButton(onClick = {
                        DrivingReportShareImage.shareAsImage(
                            shareContext,
                            todaySessions.value,
                            weekSessions.value,
                            sessions.value,
                            scoreEngine
                        )
                    }) {
                        Icon(Icons.Default.Share, localized("分享", "Share"), tint = AccentBlue)
                    }
                }
            )
        },
        containerColor = BgPrimary
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 分段控制器（iOS风格）
            SegmentedControl(
                items = listOf(localized("概览", "Overview"), localized("分析", "Analysis"), localized("成就", "Achievements"), localized("历史", "History")),
                selectedIndex = selectedTab,
                onSelected = { selectedTab = it }
            )
            when (selectedTab) {
                0 -> OverviewTab(todaySessions.value, scoreEngine, isCollecting.value)
                1 -> AnalysisTab(todaySessions.value, weekSessions.value, scoreEngine)
                2 -> AchievementsTab(sessions.value, scoreEngine)
                3 -> HistoryTab(sessions.value, scoreEngine)
            }
        }
    }
}

// ==================== iOS 分段控制器 ====================
@Composable
fun SegmentedControl(items: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .padding(3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEachIndexed { i, title ->
            val isSelected = selectedIndex == i
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) BgCardElevated else Color.Transparent)
                    .clickable { onSelected(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    color = if (isSelected) TextPrimary else TextTertiary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ==================== 实时采集指示灯 ====================
@Composable
fun LiveIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "live")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(AccentRed.copy(alpha = alpha)))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", color = AccentRed.copy(alpha = alpha), fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ==================== 概览Tab ====================
@Composable
fun OverviewTab(sessions: List<DrivingSession>, engine: DrivingScoreEngine, collecting: Boolean) {
    if (sessions.isEmpty() && !collecting) {
        EmptyState("🚗", localized("今日暂无驾驶记录", "No driving records today"), localized("连接设备并上路后自动开始记录", "Records start automatically when driving"))
        return
    }

    val totalDist = sessions.sumOf { it.effectiveDistance().toDouble() }.toFloat()
    val nooDist = sessions.sumOf { it.effectiveNooDistance().toDouble() }.toFloat()
    val avgScore = if (sessions.isNotEmpty()) sessions.map { it.totalScore }.average().toInt() else 0
    val totalInterventions = sessions.sumOf { it.interventionCount }
    val nooRatio = if (totalDist > 0) nooDist / totalDist else 0f
    val latestSession = sessions.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeroScoreCard(avgScore, totalDist, nooRatio, totalInterventions, sessions.size) }
        if (latestSession != null) {
            item { DimensionCard(latestSession) }
            item { StyleInsightCard(latestSession, engine) }
            item { TipsCard(latestSession, engine) }
        }
        if (sessions.isNotEmpty()) {
            item {
                Text(localized("今日行程", "Today's Trips"), color = TextPrimary, fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            }
            items(sessions) { s -> TripCard(s, engine) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ==================== 主评分卡片（Hero） ====================
@Composable
fun HeroScoreCard(score: Int, dist: Float, nooRatio: Float, interventions: Int, trips: Int) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(BgCardElevated, BgCard)
                    ),
                    RoundedCornerShape(20.dp)
                )
                .border(1.dp, BorderSubtle.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(localized("今日评分", "Today's Score"), color = TextSecondary, fontSize = 13.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(12.dp))

                // 环形进度 + 分数
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                    CircularScoreRing(score)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$score",
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor(score)
                        )
                        Text(
                            scoreLabel(score),
                            color = scoreColor(score).copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 统计指标行
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricPill("🛣️", "%.1f".format(dist), "km")
                    MetricPill("🤖", "${(nooRatio * 100).toInt()}", "%NOO")
                    MetricPill("🖐️", "$interventions", localized("接管", "Takeover"))
                    MetricPill("📍", "$trips", localized("行程", "Trips"))
                }
            }
        }
    }
}

// ==================== 环形评分进度 ====================
@Composable
fun CircularScoreRing(score: Int) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(score) {
        animatedProgress.animateTo(
            score / 100f,
            animationSpec = tween(1200, easing = EaseOutCubic)
        )
    }
    val color = scoreColor(score)
    Canvas(Modifier.size(160.dp)) {
        val strokeWidth = 10f
        val radius = (size.minDimension - strokeWidth) / 2
        val topLeft = Offset(
            (size.width - radius * 2) / 2,
            (size.height - radius * 2) / 2
        )
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        // 背景环
        drawArc(
            color = color.copy(alpha = 0.1f),
            startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round)
        )
        // 进度环
        drawArc(
            color = color,
            startAngle = -90f, sweepAngle = 360f * animatedProgress.value,
            useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun MetricPill(icon: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = TextTertiary, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

// ==================== 五维评分卡片 ====================
@Composable
fun DimensionCard(session: DrivingSession) {
    GlassCard {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localized("五维评分", "5D Score"), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Text(localized("最新行程", "Latest Trip"), color = TextTertiary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))

            // 雷达图
            RadarChart(
                scores = listOf(
                    session.smoothnessScore, session.predictionScore,
                    session.interventionScore, session.ecoScore, session.stabilityScore
                ),
                labels = listOf(localized("平稳", "Smooth"), localized("预判", "Predict"), localized("接管", "Takeover"), localized("节能", "Eco"), localized("稳定", "Stable")),
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )

            Spacer(Modifier.height(16.dp))

            // 分数条
            DimensionBar(localized("平稳", "Smooth"), session.smoothnessScore,
                localized("急加速", "Hard Accel") + " ${session.harshAccelCount}  " + localized("急刹", "Hard Brake") + " ${session.harshBrakeCount}", AccentTeal)
            DimensionBar(localized("预判", "Predict"), session.predictionScore,
                localized("弯道预判", "Curve Predict") + " ${session.smoothBrakeBeforeCurveCount}  " + localized("限速预判", "Limit Predict") + " ${session.smoothDecelBeforeLimitCount}", AccentBlue)
            DimensionBar(localized("接管", "Takeover"), session.interventionScore,
                localized("接管", "Takeover") + " ${session.interventionCount} " + localized("次", "times"), AccentPurple)
            DimensionBar(localized("节能", "Eco"), session.ecoScore,
                localized("巡航占比", "Cruise Ratio") + " ${(session.cruiseRatio * 100).toInt()}%", AccentGreen)
            DimensionBar(localized("稳定", "Stable"), session.stabilityScore,
                "NOO" + localized("占比", " Ratio") + " ${(session.nooRatio * 100).toInt()}%", AccentOrange)
        }
    }
}

@Composable
fun DimensionBar(label: String, score: Int, detail: String, accent: Color) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(score) {
        animatedProgress.animateTo(score / 100f, tween(800, easing = EaseOutCubic))
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(36.dp))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(accent.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(animatedProgress.value)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("$score", color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
        }
        Text(detail, color = TextTertiary, fontSize = 11.sp,
            modifier = Modifier.padding(start = 44.dp, top = 2.dp))
    }
}

// ==================== 雷达图（精致版） ====================
@Composable
fun RadarChart(scores: List<Int>, labels: List<String>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val radius = min(cx, cy) * 0.65f
        val n = scores.size
        val angleStep = 2 * PI / n
        val startAngle = -PI / 2

        // 背景网格（4层同心多边形）
        for (layer in 1..4) {
            val r = radius * layer / 4
            val path = Path()
            for (i in 0 until n) {
                val angle = startAngle + i * angleStep
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Color.White.copy(alpha = 0.06f), style = Stroke(1f))
        }

        // 轴线
        for (i in 0 until n) {
            val angle = startAngle + i * angleStep
            drawLine(
                Color.White.copy(alpha = 0.08f), Offset(cx, cy),
                Offset(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat()),
                strokeWidth = 1f
            )
        }

        // 数据填充
        val dataPath = Path()
        for (i in 0 until n) {
            val angle = startAngle + i * angleStep
            val r = radius * scores[i] / 100f
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, AccentBlue.copy(alpha = 0.15f))
        drawPath(dataPath, AccentBlue.copy(alpha = 0.8f), style = Stroke(2f))

        // 顶点
        for (i in 0 until n) {
            val angle = startAngle + i * angleStep
            val r = radius * scores[i] / 100f
            val pos = Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
            drawCircle(AccentBlue, 5f, pos)
            drawCircle(Color.White, 2.5f, pos)
        }

        // 标签
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 245, 245, 247)
            textSize = 26f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val scorePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(120, 142, 142, 147)
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        for (i in 0 until n) {
            val angle = startAngle + i * angleStep
            val labelR = radius + 28f
            val lx = cx + labelR * cos(angle).toFloat()
            val ly = cy + labelR * sin(angle).toFloat()
            drawContext.canvas.nativeCanvas.drawText(labels[i], lx, ly + 4f, paint)
            drawContext.canvas.nativeCanvas.drawText("${scores[i]}", lx, ly + 22f, scorePaint)
        }
    }
}

// ==================== 驾驶风格洞察卡片 ====================
@Composable
fun StyleInsightCard(session: DrivingSession, engine: DrivingScoreEngine) {
    val styleLabel = engine.getStyleLabel(session.drivingStyle)
    val icon = styleLabel.take(2)
    val name = styleLabel.drop(2).trim()

    GlassCard {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // 风格图标
            Box(
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 28.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(localized("驾驶风格", "Driving Style"), color = TextTertiary, fontSize = 12.sp)
                Text(name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    localized("均速", "Avg") + " %.0f km/h · ".format(session.avgSpeed) + localized("最高", "Max") + " %.0f km/h".format(session.maxSpeed),
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        }
    }
}

// ==================== 改进建议卡片 ====================
@Composable
fun TipsCard(session: DrivingSession, engine: DrivingScoreEngine) {
    val tips = engine.getImprovementTips(session)
    GlassCard {
        Column(Modifier.padding(20.dp)) {
            Text(localized("改进建议", "Improvement Tips"), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            tips.forEach { tip ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("→", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tip.removePrefix("💡 ").removePrefix("🎉 "),
                        color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ==================== 分析Tab ====================
@Composable
fun AnalysisTab(
    todaySessions: List<DrivingSession>,
    weekSessions: List<DrivingSession>,
    engine: DrivingScoreEngine
) {
    val allDetails = todaySessions.flatMap { it.interventionDetails }
    val weekDetails = weekSessions.flatMap { it.interventionDetails }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { InterventionBreakdownCard(allDetails, engine, localized("今日接管分析", "Today's Takeover Analysis")) }
        if (weekDetails.isNotEmpty()) {
            item { InterventionBreakdownCard(weekDetails, engine, localized("本周接管分析", "Weekly Takeover Analysis")) }
        }
        if (weekSessions.size >= 2) {
            item { TrendCard(weekSessions) }
        }
        if (allDetails.isNotEmpty()) {
            item {
                Text(localized("接管详情", "Takeover Details"), color = TextPrimary, fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(allDetails.sortedByDescending { it.timestamp }) { detail ->
                InterventionRow(detail, engine)
            }
        }
        if (allDetails.isEmpty() && weekDetails.isEmpty()) {
            item { EmptyState("🎯", localized("暂无接管数据", "No takeover data"), localized("使用NOO驾驶后将自动记录", "Records start after NOO driving")) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ==================== 接管分析卡片 ====================
@Composable
fun InterventionBreakdownCard(
    details: List<InterventionDetail>,
    engine: DrivingScoreEngine,
    title: String
) {
    val reasons = engine.analyzeInterventionReasons(details)
    if (reasons.isEmpty()) return

    val palette = listOf(AccentPink, AccentBlue, AccentOrange, AccentGreen, AccentPurple, TextTertiary)

    GlassCard {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("${details.size}次", color = TextTertiary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))

            reasons.entries.forEachIndexed { i, (reason, pct) ->
                val c = palette[i % palette.size]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(10.dp))
                    Text(engine.getReasonLabel(reason), color = TextPrimary, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Text("%.0f%%".format(pct), color = c, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 18.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.copy(alpha = 0.08f))
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(pct / 100f)
                            .clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

// ==================== 接管详情行 ====================
@Composable
fun InterventionRow(detail: InterventionDetail, engine: DrivingScoreEngine) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val reasonIcon = when (detail.reason) {
        "curve" -> "🔄"; "traffic" -> "🚗"; "speed_limit" -> "🚨"
        "lane_change" -> "↔️"; "no_nav" -> "🗺️"; else -> "⚠️"
    }

    GlassCard {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(AccentBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(reasonIcon, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(engine.getReasonLabel(detail.reason), color = TextPrimary,
                    fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    buildString {
                        append(timeFormat.format(Date(detail.timestamp)))
                        append(" · %.0f km/h".format(detail.speed))
                        if (detail.roadName.isNotEmpty()) append(" · ${detail.roadName}")
                    },
                    color = TextTertiary, fontSize = 12.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val isActive = detail.isActive
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) AccentGreen.copy(alpha = 0.15f) else AccentOrange.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (isActive) localized("主动", "Active") else localized("被动", "Passive"),
                        color = if (isActive) AccentGreen else AccentOrange,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (detail.leadDistance > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(localized("前车", "Lead") + " %.0fm".format(detail.leadDistance), color = TextTertiary, fontSize = 11.sp)
                }
            }
        }
    }
}

// ==================== 周趋势卡片 ====================
@Composable
fun TrendCard(sessions: List<DrivingSession>) {
    val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
    val dayGroups = sessions.groupBy { dateFormat.format(Date(it.startTime)) }
        .mapValues { (_, v) -> v.map { it.totalScore }.average().toInt() }
        .toList().takeLast(7)

    GlassCard {
        Column(Modifier.padding(20.dp)) {
            Text(localized("评分趋势", "Score Trend"), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(16.dp))

            if (dayGroups.isNotEmpty()) {
                // 折线图
                val maxScore = dayGroups.maxOf { it.second }.coerceAtLeast(100)
                Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                    val w = size.width
                    val h = size.height
                    val stepX = if (dayGroups.size > 1) w / (dayGroups.size - 1) else w / 2
                    val points = dayGroups.mapIndexed { i, (_, score) ->
                        Offset(i * stepX, h - (score.toFloat() / maxScore * h * 0.85f))
                    }

                    // 填充区域
                    if (points.size >= 2) {
                        val fillPath = Path().apply {
                            moveTo(points.first().x, h)
                            points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, h)
                            close()
                        }
                        drawPath(fillPath, Brush.verticalGradient(
                            listOf(AccentBlue.copy(alpha = 0.2f), Color.Transparent)
                        ))
                    }

                    // 折线
                    for (i in 0 until points.size - 1) {
                        drawLine(AccentBlue, points[i], points[i + 1], strokeWidth = 2.5f, cap = StrokeCap.Round)
                    }

                    // 圆点
                    points.forEach { p ->
                        drawCircle(AccentBlue, 5f, p)
                        drawCircle(BgCard, 2.5f, p)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 日期标签
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    dayGroups.forEach { (date, score) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$score", color = scoreColor(score), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(date, color = TextTertiary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 成就Tab ====================
@Composable
fun AchievementsTab(sessions: List<DrivingSession>, engine: DrivingScoreEngine) {
    val achievements = engine.checkAchievements(sessions)
    val unlocked = achievements.filter { it.isUnlocked }
    val locked = achievements.filter { !it.isUnlocked }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 成就概览
        item {
            GlassCard {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🏆", fontSize = 44.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${unlocked.size} / ${achievements.size}",
                        color = GoldGradStart,
                        fontWeight = FontWeight.Bold, fontSize = 28.sp
                    )
                    Text(localized("已解锁成就", "Unlocked"), color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    // 进度条
                    val progress = if (achievements.isNotEmpty()) unlocked.size.toFloat() / achievements.size else 0f
                    Box(
                        Modifier.fillMaxWidth(0.6f).height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(GoldGradStart.copy(alpha = 0.12f))
                    ) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(progress)
                                .clip(RoundedCornerShape(3.dp))
                                .background(GradientGold)
                        )
                    }
                }
            }
        }

        if (unlocked.isNotEmpty()) {
            item {
                Text(localized("已解锁", "Unlocked"), color = AccentGreen, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(unlocked) { a -> AchievementRow(a, true) }
        }
        if (locked.isNotEmpty()) {
            item {
                Text(localized("进行中", "In Progress"), color = TextTertiary, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(locked) { a -> AchievementRow(a, false) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun AchievementRow(achievement: Achievement, unlocked: Boolean) {
    GlassCard(alpha = if (unlocked) 1f else 0.5f) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (unlocked) GoldGradStart.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.04f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(achievement.icon, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    achievement.name,
                    color = if (unlocked) GoldGradStart else TextTertiary,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Text(achievement.description, color = TextTertiary, fontSize = 12.sp)
                if (!unlocked) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(AccentBlue.copy(alpha = 0.1f))
                        ) {
                            Box(
                                Modifier.fillMaxHeight()
                                    .fillMaxWidth(achievement.progress.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(AccentBlue.copy(alpha = 0.6f))
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(achievement.progress * 100).toInt().coerceAtMost(99)}%",
                            color = TextTertiary, fontSize = 11.sp
                        )
                    }
                }
            }
            if (unlocked) {
                Text("✅", fontSize = 18.sp)
            }
        }
    }
}

// ==================== 历史Tab ====================
@Composable
fun HistoryTab(sessions: List<DrivingSession>, engine: DrivingScoreEngine) {
    if (sessions.isEmpty()) {
        EmptyState("📊", localized("暂无历史记录", "No history yet"), localized("驾驶数据将自动保存在这里", "Driving data will be saved here"))
        return
    }

    val totalDist = sessions.sumOf { it.effectiveDistance().toDouble() }.toFloat()
    val avgScore = sessions.map { it.totalScore }.average().toInt()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GlassCard {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn("🛣️", "%.1f km".format(totalDist), localized("总里程", "Total Dist"))
                    VerticalDividerThin()
                    StatColumn("📍", "${sessions.size}", localized("总行程", "Total Trips"))
                    VerticalDividerThin()
                    StatColumn("⭐", "$avgScore", localized("平均分", "Avg Score"))
                }
            }
        }
        items(sessions) { s -> TripCard(s, engine) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun StatColumn(icon: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = TextTertiary, fontSize = 12.sp)
    }
}

@Composable
fun VerticalDividerThin() {
    Box(Modifier.width(1.dp).height(50.dp).background(BorderSubtle))
}

// ==================== 行程卡片 ====================
@Composable
fun TripCard(session: DrivingSession, engine: DrivingScoreEngine) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val durationMs = if (session.endTime > session.startTime) {
        session.endTime - session.startTime
    } else if (session.totalTimeSeconds > 0) {
        session.totalTimeSeconds * 1000L
    } else 0L
    val durationMin = durationMs / 60000
    val durationText = if (durationMin > 0) {
        "${durationMin}" + localized("分钟", "min")
    } else {
        "${durationMs / 1000}" + localized("秒", "s")
    }
    // 距离：优先用 totalDistance，如果为0则用 totalTimeSeconds * avgSpeed 估算
    val distance = if (session.totalDistance > 0.01f) {
        session.totalDistance
    } else if (session.avgSpeed > 0 && session.totalTimeSeconds > 0) {
        session.avgSpeed * session.totalTimeSeconds / 3600f
    } else 0f
    val styleLabel = engine.getStyleLabel(session.drivingStyle)
    var expanded by remember { mutableStateOf(false) }

    GlassCard {
        Column(Modifier.padding(16.dp).clickable { expanded = !expanded }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dateFormat.format(Date(session.startTime)),
                        color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "$durationText · ${"%.1f".format(distance)} km · $styleLabel",
                        color = TextTertiary, fontSize = 12.sp
                    )
                }
                // 分数圆环
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    CircularScoreRingSmall(session.totalScore)
                    Text(
                        "${session.totalScore}",
                        color = scoreColor(session.totalScore),
                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
            }

            // 五维分数行
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactScore(localized("平稳", "Smooth"), session.smoothnessScore)
                CompactScore(localized("预判", "Predict"), session.predictionScore)
                CompactScore(localized("接管", "Takeover"), session.interventionScore)
                CompactScore(localized("节能", "Eco"), session.ecoScore)
                CompactScore(localized("稳定", "Stable"), session.stabilityScore)
            }

            // 展开详情
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CompactDetail(localized("急加速", "Hard Accel"), "${session.harshAccelCount}")
                    CompactDetail(localized("急刹车", "Hard Brake"), "${session.harshBrakeCount}")
                    CompactDetail(localized("接管", "Takeover"), "${session.interventionCount}")
                    CompactDetail("NOO", "${(session.nooRatio * 100).toInt()}%")
                    CompactDetail(localized("巡航", "Cruise"), "${(session.cruiseRatio * 100).toInt()}%")
                }

                val tips = engine.getImprovementTips(session)
                tips.forEach { tip ->
                    Text(
                        "→ ${tip.removePrefix("💡 ").removePrefix("🎉 ")}",
                        color = TextTertiary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CircularScoreRingSmall(score: Int) {
    val color = scoreColor(score)
    Canvas(Modifier.size(48.dp)) {
        val strokeWidth = 4f
        val radius = (size.minDimension - strokeWidth) / 2
        val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        drawArc(color.copy(alpha = 0.12f), -90f, 360f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * score / 100f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}

@Composable
fun CompactScore(label: String, score: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$score", color = scoreColor(score), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(label, color = TextTertiary, fontSize = 10.sp)
    }
}

@Composable
fun CompactDetail(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(label, color = TextTertiary, fontSize = 10.sp)
    }
}

// ==================== 通用组件 ====================
@Composable
fun GlassCard(alpha: Float = 1f, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(BgCard.copy(alpha = alpha), RoundedCornerShape(16.dp))
                .border(0.5.dp, BorderSubtle.copy(alpha = 0.4f * alpha), RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

@Composable
fun EmptyState(icon: String, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(icon, fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = TextTertiary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

// ==================== 分享驾驶报告 ====================

/**
 * 生成驾驶报告文本摘要并通过系统分享
 */
private fun shareDrivingReport(
    context: Context,
    sessions: List<DrivingSession>,
    engine: DrivingScoreEngine
) {
    if (sessions.isEmpty()) {
        android.widget.Toast.makeText(
            context,
            localized("暂无驾驶数据可分享", "No driving data to share"),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    val totalDist = sessions.sumOf { it.effectiveDistance().toDouble() }.toFloat()
    val nooDist = sessions.sumOf { it.effectiveNooDistance().toDouble() }.toFloat()
    val avgScore = sessions.map { it.totalScore }.average().toInt()
    val totalInterventions = sessions.sumOf { it.interventionCount }
    val nooRatio = if (totalDist > 0) (nooDist / totalDist * 100).toInt() else 0
    val latest = sessions.firstOrNull()

    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val text = buildString {
        appendLine("🚗 Navipilot ${localized("驾驶报告", "Driving Report")} - $dateStr")
        appendLine("━━━━━━━━━━━━━━━━━━")
        appendLine("⭐ ${localized("综合评分", "Overall Score")}: $avgScore/100 ${scoreLabel(avgScore)}")
        appendLine("🛣️ ${localized("总里程", "Total Distance")}: ${"%.1f".format(totalDist)} km")
        appendLine("🤖 NOO ${localized("占比", "Ratio")}: $nooRatio%")
        appendLine("🖐️ ${localized("接管次数", "Takeovers")}: $totalInterventions")
        appendLine("📍 ${localized("行程数", "Trips")}: ${sessions.size}")
        if (latest != null) {
            appendLine()
            appendLine("📊 ${localized("五维评分", "5D Score")}:")
            appendLine("  ${localized("平稳", "Smooth")}: ${latest.smoothnessScore}  ${localized("预判", "Predict")}: ${latest.predictionScore}  ${localized("接管", "Takeover")}: ${latest.interventionScore}")
            appendLine("  ${localized("节能", "Eco")}: ${latest.ecoScore}  ${localized("稳定", "Stable")}: ${latest.stabilityScore}")
            val style = engine.getStyleLabel(latest.drivingStyle)
            appendLine("🏷️ ${localized("驾驶风格", "Style")}: $style")
        }
        appendLine()
        appendLine("━━━━━━━━━━━━━━━━━━")
        appendLine("Powered by Navipilot × comma3")
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Navipilot ${localized("驾驶报告", "Driving Report")} - $dateStr")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, localized("分享驾驶报告", "Share Driving Report")))
}
