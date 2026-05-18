package com.example.navipilot.ui.driving

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import androidx.core.content.FileProvider
import com.example.navipilot.scoring.*
import com.example.navipilot.ui.utils.localized
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * 驾驶报告分享海报生成器
 * 生成吸引人的推广海报：驾驶数据 + 品牌推广 + 下载引导
 */
object DrivingReportShareImage {

    private const val TAG = "DrivingReportShare"
    private const val DOWNLOAD_URL = "https://github.com/jixiexiaoge/openpilot/releases"

    // 画布尺寸
    private const val W = 1080
    private const val H = 1920

    // 颜色系统
    private val cBg = Color.parseColor("#06060C")
    private val cCard = Color.parseColor("#12121E")
    private val cCardLight = Color.parseColor("#1A1A2E")
    private val cBorder = Color.parseColor("#2A2A3C")
    private val cText = Color.parseColor("#F5F5F7")
    private val cTextSub = Color.parseColor("#8E8E93")
    private val cTextDim = Color.parseColor("#4A4A56")
    private val cBlue = Color.parseColor("#0A84FF")
    private val cCyan = Color.parseColor("#32D7FF")
    private val cGreen = Color.parseColor("#30D158")
    private val cYellow = Color.parseColor("#FFD60A")
    private val cOrange = Color.parseColor("#FF9F0A")
    private val cRed = Color.parseColor("#FF453A")
    private val cPurple = Color.parseColor("#BF5AF2")
    private val cTeal = Color.parseColor("#64D2FF")
    private val cGold = Color.parseColor("#FFD700")
    private val cPink = Color.parseColor("#FF375F")

    private fun scoreColor(score: Int): Int = when {
        score >= 90 -> cGreen; score >= 80 -> Color.parseColor("#34C759")
        score >= 70 -> cYellow; score >= 60 -> cOrange; else -> cRed
    }

    private fun scoreLabelText(score: Int): String = when {
        score >= 90 -> "优秀"; score >= 80 -> "良好"
        score >= 70 -> "一般"; score >= 60 -> "待提升"; else -> "加油"
    }

    fun shareAsImage(
        context: Context,
        todaySessions: List<DrivingSession>,
        weekSessions: List<DrivingSession>,
        allSessions: List<DrivingSession>,
        engine: DrivingScoreEngine
    ) {
        if (todaySessions.isEmpty() && allSessions.isEmpty()) {
            android.widget.Toast.makeText(context, localized("暂无驾驶数据可分享", "No data"), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 从SharedPreferences读取用户信息
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val userType = prefs.getInt("user_type", 0)
        val wechatName = prefs.getString("wechat_name", "") ?: ""

        try {
            val bmp = generatePoster(todaySessions, weekSessions, allSessions, engine, userType, wechatName)
            val file = saveBitmap(context, bmp, userType, wechatName)
            shareFile(context, file)
            bmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "生成海报失败: ${e.message}", e)
            android.widget.Toast.makeText(context, localized("生成图片失败", "Failed"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 主生成逻辑 ====================
    private fun generatePoster(
        todaySessions: List<DrivingSession>,
        weekSessions: List<DrivingSession>,
        allSessions: List<DrivingSession>,
        engine: DrivingScoreEngine,
        userType: Int,
        wechatName: String
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 背景渐变
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(Color.parseColor("#0A0A1A"), Color.parseColor("#06060C"), Color.parseColor("#0D0D1F")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)

        // 装饰光晕
        drawGlowEffects(c)

        var y = 0f

        // ① 顶部品牌区
        y = drawBrandHeader(c, y, userType, wechatName)

        // ② 核心评分区
        y = drawHeroScore(c, y, todaySessions, engine)

        // ③ 数据卡片 2×2
        y = drawDataCards(c, y, todaySessions, weekSessions, allSessions, engine)

        // ④ 成就亮点
        y = drawAchievementHighlights(c, y, allSessions, engine)

        // ⑤ 推广区 + 下载引导
        drawPromoFooter(c, y)

        return bmp
    }

    // ==================== 装饰光晕 ====================
    private fun drawGlowEffects(c: Canvas) {
        // 顶部蓝色光晕
        val glow1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(W * 0.7f, 100f, 300f, cBlue and 0x20FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(W * 0.7f, 100f, 300f, glow1)
        // 中部紫色光晕
        val glow2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(W * 0.2f, H * 0.45f, 250f, cPurple and 0x15FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(W * 0.2f, H * 0.45f, 250f, glow2)
        // 底部青色光晕
        val glow3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(W * 0.8f, H * 0.85f, 280f, cCyan and 0x12FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(W * 0.8f, H * 0.85f, 280f, glow3)
    }

    // ==================== ① 品牌头部 ====================
    private fun drawBrandHeader(c: Canvas, startY: Float, userType: Int, wechatName: String): Float {
        var y = startY + 60f

        // 品牌名
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 52f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            shader = LinearGradient(0f, y, 400f, y, cCyan, cBlue, Shader.TileMode.CLAMP)
        }
        c.drawText("CP搭子", 60f, y + 48f, brandPaint)

        // 副标题
        val subPaint = p(cTextSub, 24f)
        c.drawText("拓展 openpilot NOO · 智能导航 · 驾驶评分", 60f, y + 86f, subPaint)

        // 右上角：用户信息 + 日期
        val userTypeLabel = when (userType) {
            1 -> "🆕 新用户"; 2 -> "💙 支持者"; 3 -> "⭐ 赞助者"; 4 -> "🔥 铁粉"; else -> "🚗 先锋用户"
        }
        val userTypeColor = when (userType) {
            2 -> cBlue; 3 -> cGold; 4 -> cRed; else -> cTextSub
        }
        val utPaint = p(userTypeColor, 22f, true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(userTypeLabel, W - 60f, y + 36f, utPaint)

        if (wechatName.isNotBlank()) {
            val namePaint = p(cTextSub, 20f).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(wechatName, W - 60f, y + 62f, namePaint)
        }

        val datePaint = p(cTextDim, 20f).apply { textAlign = Paint.Align.RIGHT }
        val dateStr = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date())
        c.drawText(dateStr, W - 60f, y + 86f, datePaint)

        // 分隔装饰线
        y += 120f
        val linePaint = Paint().apply {
            shader = LinearGradient(60f, y, W - 60f, y, cBlue and 0x40FFFFFF, cPurple and 0x40FFFFFF, Shader.TileMode.CLAMP)
            strokeWidth = 2f
        }
        c.drawLine(60f, y, W - 60f, y, linePaint)

        return y + 30f
    }

    // ==================== ② 核心评分 ====================
    private fun drawHeroScore(c: Canvas, startY: Float, sessions: List<DrivingSession>, engine: DrivingScoreEngine): Float {
        var y = startY

        if (sessions.isEmpty()) {
            val emptyPaint = p(cTextSub, 28f).apply { textAlign = Paint.Align.CENTER }
            c.drawText("今日暂无驾驶记录，上路后自动记录", W / 2f, y + 100f, emptyPaint)
            return y + 160f
        }

        val avgScore = sessions.map { it.totalScore }.average().toInt()
        val totalDist = sessions.sumOf { it.effectiveDistance().toDouble() }.toFloat()
        val nooDist = sessions.sumOf { it.effectiveNooDistance().toDouble() }.toFloat()
        val nooRatio = if (totalDist > 0) (nooDist / totalDist * 100).toInt() else 0
        val totalInterventions = sessions.sumOf { it.interventionCount }

        // 标题
        val titlePaint = p(cText, 28f)
        c.drawText("📊 今日驾驶报告", 60f, y + 30f, titlePaint)
        y += 56f

        // 大环形评分
        val cx = W / 2f
        val ringR = 100f
        val ringCy = y + ringR + 20f
        drawScoreRing(c, cx, ringCy, ringR, avgScore)
        y = ringCy + ringR + 16f

        // 评价标签
        val labelPaint = p(scoreColor(avgScore), 28f, true).apply { textAlign = Paint.Align.CENTER }
        c.drawText(scoreLabelText(avgScore), cx, y + 4f, labelPaint)
        y += 36f

        // 四个核心指标（横排）
        val metrics = listOf(
            "🛣️" to "${"%.1f".format(totalDist)}km",
            "🤖" to "${nooRatio}%NOO",
            "🖐️" to "${totalInterventions}次接管",
            "📍" to "${sessions.size}行程"
        )
        val metricW = (W - 120f) / 4f
        metrics.forEachIndexed { i, (icon, value) ->
            val mx = 60f + metricW * i + metricW / 2f
            val iconP = p(cText, 24f).apply { textAlign = Paint.Align.CENTER }
            val valP = p(cText, 22f, true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(icon, mx, y + 24f, iconP)
            c.drawText(value, mx, y + 52f, valP)
        }
        y += 76f

        return y
    }

    // ==================== ③ 数据卡片 2×2 ====================
    private fun drawDataCards(
        c: Canvas, startY: Float,
        todaySessions: List<DrivingSession>,
        weekSessions: List<DrivingSession>,
        allSessions: List<DrivingSession>,
        engine: DrivingScoreEngine
    ): Float {
        val pad = 48f
        val gap = 20f
        val cardW = (W - pad * 2 - gap) / 2f
        val cardH = 340f
        var y = startY + 16f

        // 左上：五维评分
        drawCardBg(c, pad, y, cardW, cardH)
        drawDimensionCard(c, pad, y, cardW, cardH, todaySessions)

        // 右上：接管分析
        drawCardBg(c, pad + cardW + gap, y, cardW, cardH)
        drawAnalysisCard(c, pad + cardW + gap, y, cardW, cardH, todaySessions, engine)

        y += cardH + gap

        // 左下：周趋势
        drawCardBg(c, pad, y, cardW, cardH)
        drawTrendCard(c, pad, y, cardW, cardH, weekSessions)

        // 右下：历史统计
        drawCardBg(c, pad + cardW + gap, y, cardW, cardH)
        drawHistoryCard(c, pad + cardW + gap, y, cardW, cardH, allSessions)

        return y + cardH + 20f
    }

    private fun drawCardBg(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val rect = RectF(x, y, x + w, y + h)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCard }
        c.drawRoundRect(rect, 20f, 20f, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cBorder; style = Paint.Style.STROKE; strokeWidth = 1f
        }
        c.drawRoundRect(rect, 20f, 20f, borderPaint)
    }

    // --- 五维评分卡 ---
    private fun drawDimensionCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, sessions: List<DrivingSession>) {
        val inset = 20f
        val titleP = p(cText, 22f, true)
        c.drawText("🎯 五维评分", x + inset, y + inset + 20f, titleP)

        val latest = sessions.firstOrNull() ?: return
        val dims = listOf(
            "平稳" to latest.smoothnessScore to cTeal,
            "预判" to latest.predictionScore to cBlue,
            "接管" to latest.interventionScore to cPurple,
            "节能" to latest.ecoScore to cGreen,
            "稳定" to latest.stabilityScore to cOrange
        )
        val barLeft = x + inset + 60f
        val barRight = x + w - inset - 50f
        val barH = 8f

        dims.forEachIndexed { i, (labelScore, color) ->
            val (label, score) = labelScore
            val by = y + 60f + i * 52f

            c.drawText(label, x + inset, by + 6f, p(cTextSub, 20f))

            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 30 }
            c.drawRoundRect(barLeft, by - 4f, barRight, by - 4f + barH, 4f, 4f, bg)

            val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val progress = barLeft + (barRight - barLeft) * score / 100f
            c.drawRoundRect(barLeft, by - 4f, progress, by - 4f + barH, 4f, 4f, fg)

            val sp = p(color, 20f, true).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("$score", x + w - inset, by + 6f, sp)
        }
    }

    // --- 接管分析卡 ---
    private fun drawAnalysisCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, sessions: List<DrivingSession>, engine: DrivingScoreEngine) {
        val inset = 20f
        val details = sessions.flatMap { it.interventionDetails }
        val titleP = p(cText, 22f, true)
        c.drawText("🔍 接管分析", x + inset, y + inset + 20f, titleP)

        if (details.isEmpty()) {
            c.drawText("暂无接管 🎉", x + inset, y + 90f, p(cTextSub, 20f))
            c.drawText("驾驶表现很棒", x + inset, y + 120f, p(cTextDim, 18f))
            return
        }

        val reasons = engine.analyzeInterventionReasons(details)
        val palette = listOf(cPink, cBlue, cOrange, cGreen, cPurple)

        c.drawText("共 ${details.size} 次", x + inset, y + inset + 48f, p(cTextSub, 18f))

        reasons.entries.take(4).forEachIndexed { i, (reason, pct) ->
            val color = palette[i % palette.size]
            val ry = y + 76f + i * 56f

            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            c.drawCircle(x + inset + 6f, ry + 4f, 6f, dot)
            c.drawText(engine.getReasonLabel(reason), x + inset + 22f, ry + 8f, p(cText, 20f))

            val pctP = p(color, 20f, true).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("%.0f%%".format(pct), x + w - inset, ry + 8f, pctP)

            val barL = x + inset + 22f; val barR = x + w - inset - 60f
            val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 25 }
            c.drawRoundRect(barL, ry + 18f, barR, ry + 24f, 3f, 3f, barBg)
            val barFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 160 }
            c.drawRoundRect(barL, ry + 18f, barL + (barR - barL) * pct / 100f, ry + 24f, 3f, 3f, barFg)
        }
    }

    // --- 周趋势卡 ---
    private fun drawTrendCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, weekSessions: List<DrivingSession>) {
        val inset = 20f
        c.drawText("📈 评分趋势", x + inset, y + inset + 20f, p(cText, 22f, true))

        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val dayGroups = weekSessions.groupBy { dateFormat.format(Date(it.startTime)) }
            .mapValues { (_, v) -> v.map { it.totalScore }.average().toInt() }
            .toList().takeLast(7)

        if (dayGroups.size < 2) {
            c.drawText("数据积累中...", x + inset, y + 90f, p(cTextSub, 20f))
            c.drawText("驾驶2天以上显示趋势", x + inset, y + 120f, p(cTextDim, 18f))
            return
        }

        val chartL = x + inset + 10f; val chartR = x + w - inset - 10f
        val chartT = y + 60f; val chartB = y + h - 60f
        val chartW = chartR - chartL; val chartH = chartB - chartT
        val maxS = dayGroups.maxOf { it.second }.coerceAtLeast(100)

        val pts = dayGroups.mapIndexed { i, (_, score) ->
            PointF(chartL + chartW * i / (dayGroups.size - 1), chartB - (score.toFloat() / maxS * chartH * 0.8f))
        }

        // 填充
        val fillPath = Path().apply {
            moveTo(pts.first().x, chartB); pts.forEach { lineTo(it.x, it.y) }; lineTo(pts.last().x, chartB); close()
        }
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, chartT, 0f, chartB, cBlue and 0x30FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawPath(fillPath, fillP)

        // 折线
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cBlue; strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val linePath = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        c.drawPath(linePath, lineP)

        // 圆点 + 标签
        pts.forEachIndexed { i, pt ->
            c.drawCircle(pt.x, pt.y, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cBlue })
            c.drawCircle(pt.x, pt.y, 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCard })
            val scoreP = p(cBlue, 18f, true).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${dayGroups[i].second}", pt.x, pt.y - 12f, scoreP)
            val dateP = p(cTextDim, 16f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(dayGroups[i].first, pt.x, chartB + 20f, dateP)
        }
    }

    // --- 历史统计卡 ---
    private fun drawHistoryCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, allSessions: List<DrivingSession>) {
        val inset = 20f
        c.drawText("📋 累计数据", x + inset, y + inset + 20f, p(cText, 22f, true))

        if (allSessions.isEmpty()) {
            c.drawText("暂无历史记录", x + inset, y + 90f, p(cTextSub, 20f))
            return
        }

        val totalDist = allSessions.sumOf { it.effectiveDistance().toDouble() }.toFloat()
        val avgScore = allSessions.map { it.totalScore }.average().toInt()
        val totalTime = allSessions.sumOf { it.effectiveDurationMs() }
        val totalHours = totalTime / 3600000f

        data class Stat(val icon: String, val value: String, val label: String)
        val stats = listOf(
            Stat("🛣️", "${"%.1f".format(totalDist)}km", "总里程"),
            Stat("📍", "${allSessions.size}", "总行程"),
            Stat("⭐", "$avgScore", "平均分"),
            Stat("⏱️", "${"%.1f".format(totalHours)}h", "总时长")
        )

        stats.forEachIndexed { i, stat ->
            val sy = y + 60f + i * 62f
            c.drawText(stat.icon, x + inset, sy + 24f, p(cText, 26f))
            c.drawText(stat.value, x + inset + 44f, sy + 22f, p(cText, 24f, true))
            val labelP = p(cTextDim, 18f).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(stat.label, x + w - inset, sy + 22f, labelP)
        }
    }

    // ==================== ④ 成就亮点 ====================
    private fun drawAchievementHighlights(c: Canvas, startY: Float, allSessions: List<DrivingSession>, engine: DrivingScoreEngine): Float {
        var y = startY + 10f
        val achievements = engine.checkAchievements(allSessions)
        val unlocked = achievements.filter { it.isUnlocked }

        // 成就条
        val barBg = RectF(48f, y, W - 48f, y + 70f)
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCard }
        c.drawRoundRect(barBg, 16f, 16f, bgP)
        val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cBorder; style = Paint.Style.STROKE; strokeWidth = 1f }
        c.drawRoundRect(barBg, 16f, 16f, borderP)

        c.drawText("🏆", 68f, y + 46f, p(cText, 28f))
        c.drawText("成就 ${unlocked.size}/${achievements.size}", 108f, y + 44f, p(cGold, 24f, true))

        // 已解锁成就图标横排
        val iconStartX = 300f
        unlocked.take(6).forEachIndexed { i, a ->
            c.drawText(a.icon, iconStartX + i * 50f, y + 46f, p(cText, 26f))
        }

        // 进度条
        val progress = if (achievements.isNotEmpty()) unlocked.size.toFloat() / achievements.size else 0f
        val pBarL = W - 48f - 180f; val pBarR = W - 68f
        val pBarY = y + 30f
        val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cGold; alpha = 30 }
        c.drawRoundRect(pBarL, pBarY, pBarR, pBarY + 8f, 4f, 4f, pBg)
        val pFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cGold }
        c.drawRoundRect(pBarL, pBarY, pBarL + (pBarR - pBarL) * progress, pBarY + 8f, 4f, 4f, pFg)
        val pctP = p(cGold, 18f).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("${(progress * 100).toInt()}%", pBarR, pBarY - 6f, pctP)

        return y + 90f
    }

    // ==================== ⑤ 推广底部 ====================
    private fun drawPromoFooter(c: Canvas, startY: Float) {
        var y = startY + 20f

        // 分隔装饰线
        val lineP = Paint().apply {
            shader = LinearGradient(60f, y, W - 60f, y, cCyan and 0x30FFFFFF, cPurple and 0x30FFFFFF, Shader.TileMode.CLAMP)
            strokeWidth = 1.5f
        }
        c.drawLine(60f, y, W - 60f, y, lineP)
        y += 40f

        // 推广主标题
        val sloganP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 44f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            shader = LinearGradient(0f, y, W.toFloat(), y, cCyan, cBlue, Shader.TileMode.CLAMP)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("CP搭子 — 解锁 openpilot 全部潜能", W / 2f, y + 40f, sloganP)
        y += 70f

        // 功能亮点（三列）
        val features = listOf(
            "🤖" to "NOO领航",
            "🧪" to "条件实验",
            "🚗" to "自动超车"
        )
        val colW = (W - 120f) / 3f
        features.forEachIndexed { i, (icon, label) ->
            val fx = 60f + colW * i + colW / 2f

            // 圆形背景
            val circleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCardLight }
            c.drawCircle(fx, y + 30f, 36f, circleBg)
            val circleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cBlue; alpha = 60; style = Paint.Style.STROKE; strokeWidth = 1.5f
            }
            c.drawCircle(fx, y + 30f, 36f, circleBorder)

            val iconP = p(cText, 30f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(icon, fx, y + 42f, iconP)
            val labelP = p(cText, 20f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(label, fx, y + 82f, labelP)
        }
        y += 110f

        // 推广描述
        val descLines = listOf(
            "拓展 openpilot NOO 能力的最佳搭档",
            "条件实验模式 · 自动超车 · LED点阵屏 · 驾驶评分",
            "兼容 carrot / OPKR / FrogPilot 等多分支"
        )
        descLines.forEach { line ->
            val descP = p(cTextSub, 22f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(line, W / 2f, y + 26f, descP)
            y += 36f
        }
        y += 16f

        // 下载按钮
        val btnL = W / 2f - 200f; val btnR = W / 2f + 200f
        val btnT = y; val btnB = y + 64f
        val btnRect = RectF(btnL, btnT, btnR, btnB)
        val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(btnL, btnT, btnR, btnB, cBlue, cCyan, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(btnRect, 32f, 32f, btnPaint)
        val btnTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 28f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("⬇ 免费下载", W / 2f, btnT + 42f, btnTextP)
        y = btnB + 16f

        // 下载链接（醒目显示）
        val urlP = p(cCyan, 24f).apply { textAlign = Paint.Align.CENTER }
        c.drawText(DOWNLOAD_URL, W / 2f, y + 22f, urlP)
        y += 44f

        // 底部品牌
        val footerP = p(cTextDim, 18f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Powered by CP搭子 × Comma × openpilot", W / 2f, y + 20f, footerP)
    }

    // ==================== 环形评分 ====================
    private fun drawScoreRing(c: Canvas, cx: Float, cy: Float, r: Float, score: Int) {
        val color = scoreColor(score)
        val sw = 14f

        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 25; style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        c.drawCircle(cx, cy, r, bgP)

        val fgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * score / 100f, false, fgP)

        val scoreP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = 64f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        c.drawText("$score", cx, cy + 22f, scoreP)
    }

    // ==================== 工具 ====================
    private fun p(color: Int, size: Float, bold: Boolean = false): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, userType: Int, wechatName: String): File {
        val dir = File(context.cacheDir, "share_images")
        if (!dir.exists()) dir.mkdirs()
        // 清理旧文件
        dir.listFiles()?.filter { it.name.startsWith("CP搭子") || it.name.startsWith("driving_report") }?.forEach { it.delete() }

        // 构建文件名：CP搭子_用户类型_用户名_时间.png
        val typeLabel = when (userType) {
            1 -> "新用户"; 2 -> "支持者"; 3 -> "赞助者"; 4 -> "铁粉"; else -> "先锋用户"
        }
        val namePart = if (wechatName.isNotBlank()) {
            // 清理文件名中不允许的字符
            wechatName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        } else "匿名"
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val fileName = "CP搭子_${typeLabel}_${namePart}_${timeStr}.png"

        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "海报已保存: ${file.name} (${file.length() / 1024}KB)")
        return file
    }

    private fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, localized("分享驾驶报告", "Share Report")))
    }
}
