package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.data.CarrotDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.ReceivedGuidance
import com.jixiexiaoge.drivingassist.navigation.AmapCruiseLamp
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.SecondaryLight
import com.jixiexiaoge.drivingassist.ui.theme.Surface800
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.theme.TextTertiary
import com.jixiexiaoge.drivingassist.ui.utils.localized
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导航引导的取数结果。
 *
 * 取数集中在这里、渲染只有 [NaviGuidePanel] 一处，是为了「高德 / 车端」两个来源
 * 永远走同一份判定：高德 Fresh 就全用高德，否则整卡退回车端 7000，
 * 不会出现图标来自高德、距离来自车端这种混读。
 */
internal class NaviSummary(
    val fresh: Boolean,
    val current: AmapDisplayAction?,
    val next: AmapDisplayAction?,
    val remainingM: Int?,
    val remainingSec: Int?,
    val arrivalAtMs: Long?,
)

/** 高德 Fresh 用高德；没接入时回退车端 7000 引导（不混源） */
internal fun naviSummary(display: AmapNavigationDisplay, fallback: CarrotDisplaySnapshot?): NaviSummary {
    val fresh = display.state == AmapDisplayState.Fresh
    return NaviSummary(
        fresh = fresh,
        current = if (fresh) display.current else vehicleAction(fallback?.currentGuidance),
        next = if (fresh) display.next else vehicleAction(fallback?.nextGuidance),
        remainingM = if (fresh) display.remainingM else fallback?.journey?.distanceM,
        remainingSec = if (fresh) display.remainingSec else fallback?.journey?.timeSec,
        arrivalAtMs = if (fresh) display.arrivalAtMs else null,
    )
}

/**
 * 道路引导卡 —— 地图右侧那一格，版式照高德导航的路口指引卡来。
 *
 * 参照高德那张卡的三段式：
 * 1. **本段**：大号转向图标 +「81 米 + 动作」+ 道路名（多行时省略）；
 * 2. **细分隔线**；
 * 3. **随后一段**：小图标 +「随后 106 米」；
 * 4. **底行**：`33公里 · 1时7分`（左）+ `21:32到`（右）。
 *
 * 7.9.49 起取代原来的「行程进度条 + 剩余距离/时间/到达三行读数」：
 * 开车时要的是「下一个动作是什么、还有多远」，进度条只在核对时才有意义；
 * 那份行程信息并没有丢，压成了底行的高德式一句话。
 *
 * 数据仍走 [naviSummary] 的统一取数（高德 Fresh 用高德，否则回退车端 7000），
 * 这里不另开一套判定。行高由调用方按并排的实时画面比例反推（见 `mediaPaneHeight`），
 * 内容整体**垂直居中**：这卡只有四行，钉住上下两头会在中间留一条大空缝；
 * 数据来源（高德 / 车端 7000）由下方「导航对照」卡说明，这里不再重复一行小字。
 */
@Composable
internal fun NaviGuidePanel(
    display: AmapNavigationDisplay,
    fallback: CarrotDisplaySnapshot? = null,
    modifier: Modifier = Modifier,
) {
    val summary = naviSummary(display, fallback)
    val current = summary.current
    // 随后一段：距离为 0 表示没有下一段，整行不画（否则会出现「随后 0米」）
    val next = summary.next?.takeIf { (it.distanceM ?: 0) > 0 }
    val (amount, unit) = guideDistance(current?.distanceM)
    // 没有当前动作时给一句占位提示，而不是只留一条横线（卡上一片空白会像坏掉）
    val action = current?.label?.takeIf { it.isNotBlank() }
        ?: localized("等待导航动作", "Waiting for maneuver")
    // 车端 7000 的动作文字本身可能就是「进入XX路」，与道路名重复时不再画第二行
    val road = current?.roadName?.takeIf { it.isNotBlank() && action?.contains(it) != true }
    val (nextAmount, nextUnit) = guideDistance(next?.distanceM)
    val trip = tripSummary(summary.remainingM, summary.remainingSec)
    val eta = summary.arrivalAtMs?.let {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        localized("${clock}到", "Arr $clock")
    }
    Surface(
        modifier = modifier.testTag("navi-guide-panel"),
        shape = RoundedCornerShape(18.dp),
        color = Surface800,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        // 高度由左格路口图反推（两侧等高），所以内容**整体垂直居中**：
        // 这卡只有四行，钉在上下两头会在中间留出一条大空缝（真机 323×200dp 实测），
        // 居中后上下留白对称，看着是「留白」而不是「缺了一块」。
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // ── 本段：图标 +「81 米 右转」+ 道路名 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AmapManeuverIcon(current?.maneuverIcon, Accent, Modifier.size(46.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (amount.isNotEmpty()) Text(
                            amount,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.testTag("navi-guide-distance"),
                        )
                        if (unit.isNotEmpty()) Text(
                            unit,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                        )
                        action?.let {
                            Text(
                                it,
                                color = TextSecondary,
                                // 没有距离时这行是占位提示（如「等待导航动作」），放大到 15sp
                                fontSize = if (amount.isEmpty()) 15.sp else 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp)
                                    .testTag("navi-guide-action"),
                            )
                        }
                    }
                    if (road != null) {
                        Text(
                            road,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("navi-guide-road"),
                        )
                    }
                }
            }
            if (next == null && trip.isEmpty()) {
                // 全空（没接高德、车端也没引导）：与其画一条分隔线和一条横线，
                // 不如直接说清「什么时候会有内容」，卡面不留假占位。
                Spacer(Modifier.height(9.dp))
                Text(
                    localized(
                        "接入高德导航后显示路口指引",
                        "Junction guidance appears with AMap navigation",
                    ),
                    color = TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("navi-guide-hint"),
                )
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            Spacer(Modifier.height(10.dp))
            // ── 随后一段：小图标 +「随后 106 米」；没有下一段就整行不画 ──
            next?.let { follow ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("navi-guide-next"),
                ) {
                    AmapManeuverIcon(follow.maneuverIcon, TextTertiary, Modifier.size(18.dp))
                    Text(
                        localized("随后 ", "Then ") + nextAmount + nextUnit,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(11.dp))
            }
            // ── 底行：总里程 · 总用时｜到达时刻 ──
            Row(
                Modifier.fillMaxWidth().testTag("navi-guide-trip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    trip,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                eta?.let {
                    Text(
                        it,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.testTag("navi-guide-eta"),
                    )
                }
            }
        }
    }
}

/**
 * 引导距离 →（数值串，单位串）。高德卡上的「81 米」就是数字大、单位小，
 * 所以拆成两段交给调用方分别排版；1 公里以上换成公里并保留一位小数。
 */
private fun guideDistance(meters: Int?): Pair<String, String> = when {
    meters == null || meters < 0 -> "" to ""
    meters >= 1000 -> "%.1f".format(meters / 1000f) to localized("公里", "km")
    else -> meters.toString() to localized("米", "m")
}

/** 底行左半：`33公里 · 1时7分`（缺哪项就少哪项，都没有时返回空串） */
private fun tripSummary(meters: Int?, seconds: Int?): String {
    val parts = listOfNotNull(
        meters?.takeIf { it >= 0 }?.let(::tripDistance),
        seconds?.takeIf { it > 0 }?.let(::tripDuration),
    )
    return parts.joinToString(" · ")
}

/** 高德只在 10 公里以内保留一位小数，`33.0公里` 那种写法在车上读起来是噪音 */
private fun tripDistance(meters: Int): String {
    if (meters < 1000) return localized("${meters}米", "${meters} m")
    val km = meters / 1000f
    val text = if (km >= 10f) "%.0f".format(km) else "%.1f".format(km)
    return localized("${text}公里", "$text km")
}

/** 高德写「1时7分」，不足 1 小时就只写分钟，免得白占一位 */
private fun tripDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600 + 30) / 60
    return when {
        hours > 0 -> localized("${hours}时${minutes}分", "${hours}h ${minutes}m")
        minutes > 0 -> localized("${minutes}分", "$minutes min")
        else -> localized("${seconds}秒", "$seconds s")
    }
}

private fun vehicleAction(guidance: ReceivedGuidance?): AmapDisplayAction? {
    val text = guidance?.text?.takeIf { it.isNotBlank() } ?: return null
    return AmapDisplayAction(text, guidance.roadName, guidance.distanceM ?: 0)
}

/**
 * 巡航灯组行。
 *
 * 7.9.59：高德红绿灯从本行**移出**——原来的「三个色点 + 左转 + 12 s」文字卡
 * 改成高德车机版的胶囊样式（倒数数字 + 彩色方向圆盘），画进驾驶卡右侧
 * （见 `DriveViewCard.amapSignal` / `AmapSignalCapsule`）。本行只剩巡航灯组，
 * 没有灯时整行不渲染，不留空壳。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaviCruiseLampsRow(display: AmapNavigationDisplay, modifier: Modifier = Modifier) {
    val lamps = display.cruiseLamps
    if (lamps.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("navi-cruise-lamps-row"),
        shape = RoundedCornerShape(14.dp),
        color = Surface800.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                lamps.take(MAX_VISIBLE_LAMPS).forEach { lamp ->
                    LampChip(lamp)
                }
                if (lamps.size > MAX_VISIBLE_LAMPS) {
                    Text(
                        "+${lamps.size - MAX_VISIBLE_LAMPS}",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LampChip(lamp: AmapCruiseLamp) {
    val color = when (lamp.status) {
        0 -> Color(0xFFFB7185)
        1 -> SecondaryLight
        else -> TextTertiary
    }
    val direction = cruiseLampDirectionLabel(lamp.direction)
    val status = when (lamp.status) {
        0 -> localized("红", "Red")
        1 -> localized("绿", "Green")
        else -> localized("未知", "Unknown")
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Text("$direction · $status", color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("${lamp.remainingSec}s", color = color.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

internal fun cruiseLampDirectionLabel(code: Int): String = when (code) {
    1 -> localized("左转", "Left")
    2 -> localized("直行", "Straight")
    3 -> localized("右转", "Right")
    else -> localized("方向未确认", "Direction unconfirmed")
}

/**
 * 折叠区：把「只在核对时才看」的长面板收起来，默认收起。
 *
 * 导航页原来一屏半全是面板；折叠后首屏只剩投屏、总览、灯组、巡航道路和导航对照，
 * 想看原始广播值再展开，展开状态按 [tag] 记忆，切页回来不会重置。
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    subtitle: String,
    tag: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(initiallyExpanded) }
    val arrow by animateFloatAsState(if (expanded) 90f else 0f, label = "collapseArrow")
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = Surface800.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("›", color = Accent, fontSize = 19.sp, modifier = Modifier.rotate(arrow))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (expanded) localized("收起", "Hide") else localized("展开", "Show"),
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (expanded) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag("$tag-content"),
                ) { content() }
            }
        }
    }
}

private const val MAX_VISIBLE_LAMPS = 4
