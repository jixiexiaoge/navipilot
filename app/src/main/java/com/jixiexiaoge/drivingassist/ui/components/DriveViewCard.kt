@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "LongMethod")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.CustomIcons
import com.jixiexiaoge.drivingassist.data.DrivingDisplay
import com.jixiexiaoge.drivingassist.data.DashboardRoad
import com.jixiexiaoge.drivingassist.data.DashboardRoadCurve
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.data.laneBand
import com.jixiexiaoge.drivingassist.data.window
import com.jixiexiaoge.drivingassist.navigation.AmapDisplaySignal
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLanes
import com.jixiexiaoge.drivingassist.data.CommaConnectionDisplay
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlin.math.max
import kotlin.math.min

/**
 * Dashpilot Vanilla 风格的原生驾驶卡；绘图独立实现，保留本 App 的前视画中画。
 *
 * ## 为什么坐标是这些数字
 * preview.jpg 是 **2280×1080** 的实机截图（不是设计稿），所以所有版式参数都按
 * 「占卡片宽/高的比例」从截图像素直接量出来，再换算到 [DESIGN_W]×[DESIGN_H] 的设计网格上。
 * 原版 HTML 里的 CSS px 与截图对不上（`#speedUnit` 写 18px，实测是速度字的 39%），
 * 所以以像素测量为准，CSS 只用来理解元素层级与语义。
 *
 * | 元素 | preview 像素 | 换算后的设计值 |
 * |---|---|---|
 * | 速度数字 | x 224 / top 235 / 字高 154 | left 98 / top 103 / 字号 94 |
 * | km/h | left 530 / baseline 389 | left 232 / 字号 37 |
 * | 挡位块 | 168×73 @ (210,411) | 74×32 @ (92,180) |
 * | 方向盘 | 183×183 @ (210,596) | 80×80 @ (92,262) |
 * | 限速牌 | ⌀209 @ 右 106 / top 131 | ⌀92 / 右 47 / top 57 |
 * | 自车 | 174×184，底部 713 | 76×81，底部 0.660H |
 * | 车道带 | top 295 → bottom 790 | 0.273H → 0.731H |
 * | 灭点 | y≈101 | 0.0935H |
 * | 模式角标 | — | 右 47 / top 18（限速牌正上方，共用同一条右对齐线） |
 * | 分区切换 | — | 左 47 / top 18（与模式角标左右对称，速度数字上方；**只剩「导航」一个入口**） |
 * | 车道宽度 | — | 居中 +105（自车正下方，原「左盲区提示」文字的位置） |
 * | 车道方向卡 | — | 顶部居中 top 18 / 单格高 52（与模式角标同一条水平线，7.9.49 起贴上沿） |
 * | 车速字号 | — | 7.9.51 从 95 收到 82（占卡高从 20% 降到 17%），km/h 26→23，间距 34→30 |
 *
 * 数值直接消费独立 TTL 过滤后的 [DashboardSnapshot.driving]，不反解析本地化展示文本。
 * CAN 灯色/停止线和高德导航灯分别展示，不做跨源灯组关联。
 *
 * 7.9.41 起：卡上的高德红绿灯 pill 删除（灯色只由 CAN 停止线/立杆表达）。
 * 7.9.45 起：**卡左上角的分区按钮删除**——进控制页只点方向盘（见 [onEnterControls]），
 * 左上角让给场景本身，不再与右上角的 Auto 角标成对出现。
 * 7.9.46 起：**外框改成三态**——默认灰 / 横向控制蓝 / 纵向控制绿（见 [CardEdgeIdle] 一带），
 * 并删掉左下角的「CAN 红灯」pill（灯色只由场景里的停止线与立杆表达）。
 * 7.9.48 起：**顶部中间放高德原生的车道方向卡**（见 [laneGuide] / `AmapLaneCard`），
 * 原来贴在卡片正下方那条二次开发的车道引导条随之删除。
 * 7.9.49 起：车道卡**贴住卡片上沿**（18u，与模式角标对齐），转向灯在它出现时让到下方。
 */
@Composable
internal fun DriveViewCard(
    snapshot: DashboardSnapshot,
    modifier: Modifier = Modifier,
    /** 右下角画中画插槽（实时页用来嵌前视画面）。传 null 时卡片与原来完全一致。 */
    pip: (@Composable () -> Unit)? = null,
    /** 驾驶模式（只读建议）：Auto / EAP / NOA / NOO，显示在卡片右上角 */
    drivingMode: DrivingMode? = null,
    /** 车道宽度：`左 | 本 | 右`（米），显示在自车正下方（原盲区提示文字的位置） */
    laneGeometry: LaneGeometry? = null,
    /**
     * 车道方向卡（高德 13012 车道组）：画在**卡片顶部中间**。
     *
     * 7.9.48 起换成高德 9.5 的原生位图（`navistate_landfront_*`）。
     * 7.9.49 起它贴住卡片上沿：顶边 18u 与右上角模式角标对齐，总高约 58u；
     * 转向灯见 [BLINKER_TOP] / [BLINKER_TOP_WITH_LANES]，会主动让到卡片下方，
     * 车道多时按可用宽度等比缩小，不换行。
     */
    laneGuide: AmapJunctionLanes? = null,
    /** 点方向盘进入控制页；null 时方向盘只作指示、不可点 */
    onEnterControls: (() -> Unit)? = null,
    /** 右下角状态角标（设备 IP / 连接状态 / 分辨率）：原实时页顶部状态行，7.9.53 并进卡里 */
    cameraBadge: CameraBadgeInfo? = null,
    onConnectionClick: (() -> Unit)? = null,
    /**
     * 高德红绿灯（10001 广播的灯色/方向/倒数），7.9.59 起画成**胶囊**贴在卡片右侧。
     *
     * 7.9.59 前它是滚动区里一行「三个色点 + 左转 + 12 s」的文字卡（原 `NaviSignalRow`），
     * 开车时在滚动区里根本看不到；换成高德车机版的胶囊样式后挪到驾驶卡上——
     * 位置取「#6 自车」与「#7 提示」之间的高度带（限速牌之下、设备角标之上），
     * 右对齐 47u 与限速牌/模式角标同线，不与任何元素重叠。null 时整颗不画。
     */
    amapSignal: AmapDisplaySignal? = null,
    jetlink: com.jixiexiaoge.drivingassist.data.JetlinkStatus? = null,
    onGearClick: (() -> Unit)? = null,
) {
    val driving = snapshot.driving
    val speed = driving.speedKph?.toInt()
    val gear = driving.gear.gearLetter()
    val steerDeg = driving.steeringDeg
    val cruise = driving.cruiseKph?.toInt()
    val suggested = driving.suggestedKph
    val limit = driving.adoptedLimitKph
    val braking = driving.brakeLights == true
    val leftBlind = driving.leftBlindspot == true
    val rightBlind = driving.rightBlindspot == true
    val curves = snapshot.road.curves
    val lamp = resolveLamp(snapshot.road.trafficLightState, null)
    val stopLineM = snapshot.road.stopLineM
    // 原版按挡位切 3D 相机；挡位未知时保持后视，避免画面无故跳动
    val camera = CameraView.of(driving.gear)
    // 外框：纵向 > 横向 > 默认灰；两者都为 null（未知）时也算未激活，不冒充开启
    val edgeColor = when {
        driving.longitudinalActive == true -> CardEdgeLongitudinal
        driving.lateralActive == true -> CardEdgeLateral
        else -> CardEdgeIdle
    }

    BoxWithConstraints(
        modifier.fillMaxWidth()
            .aspectRatio(DRIVE_VIEW_ASPECT)
            .testTag("drive-view-card"),
    ) {
        // u = 1 个设计像素对应多少 dp
        val u = maxWidth.value / DESIGN_W
        // 外层刻意**不裁剪**：视频用的 SurfaceView 走独立图层，放进 clip 容器会显示异常
        Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(PageBg, RoundedCornerShape((14 * u).dp))
                .border(max(2f, 9 * u).dp, edgeColor, RoundedCornerShape((14 * u).dp))
                .clip(RoundedCornerShape((14 * u).dp)),
        ) {
            Canvas(Modifier.fillMaxSize().testTag("drive-view-scene-${camera.kind.name.lowercase()}")) {
                drawLaneScene(snapshot.road, braking, camera, stopLineM, lamp, steerDeg)
            }
            if (leftBlind) BlindWash(left = true)
            if (rightBlind) BlindWash(left = false)
            // ── 自车正下方：三条车道宽度 `左 | 本 | 右` ──
            // 这里原来是「左 / 右盲区提示」文字的位置。盲区改由左右两侧的红色渐变条表达
            // （哪一侧亮就是哪一侧有车），腾出的位置给车道宽度，读数就在车尾下方、抬头即见。
            laneGeometry?.let {
                LaneWidthStrip(
                    geometry = it,
                    modifier = Modifier.align(Alignment.Center).offset(y = (105 * u).dp),
                    fontSize = scaled(20f, u, 8f),
                    unitFontSize = scaled(17f, u, 7f),
                    accent = LaneWidthAccent,
                    muted = Muted,
                )
            }
            if (curves.isEmpty()) {
                Text(
                    localized("等待模型道路", "Waiting for road model"),
                    Modifier.align(Alignment.Center).offset(y = (-80 * u).dp)
                        .testTag("drive-view-road-missing"),
                    color = Muted, fontSize = scaled(18f, u, 9f),
                )
            }

            // ── 左列：车速 / 挡位 / 方向盘（原版 #leftArea，left 50 top 30）──
            SpeedBlock(speed, Modifier.offset(x = (98 * u).dp, y = (83 * u).dp), u)
            jetlink?.let {
                JetlinkBadge(
                    it,
                    Modifier.align(Alignment.TopStart)
                        .offset(x = (47 * u).dp, y = (18 * u).dp)
                        .widthIn(max = (DESIGN_W * u * 0.28f).dp),
                    u,
                )
            }
            GearBadge(gear, Modifier.offset(x = (92 * u).dp, y = (198 * u).dp), u, onGearClick)
            // 方向盘同时是「进入控制页」的入口（卡内分区只剩导航，控制页没有别的入口）
            SteeringWheel(steerDeg, driving.lateralActive == true,
                Modifier.offset(x = (92 * u).dp, y = (262 * u).dp), u, onEnterControls)

            // ── 右上：定速 + 限速牌（原版 #speedSignRow）──
            if (!driving.parked) SpeedSignRow(
                cruise = cruise,
                limit = limit,
                adasOn = driving.longitudinalActive == true,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-47 * u).dp, y = (57 * u).dp),
                u = u,
            )

            // ── 右上角：驾驶模式角标（Auto / EAP / NOA / NOO），在定速行上方一行 ──
            // 与限速牌右对齐（同为 47u 内缩），不压绿框、也不和定速/限速牌重叠。
            ModePill(
                drivingMode,
                Modifier.align(Alignment.TopEnd).offset(x = (-47 * u).dp, y = (18 * u).dp),
                u,
            )

            // ── 顶部中间：车道方向卡（高德原生素材）──
            // 7.9.49：贴住卡片上沿。顶边 18u 与右上角模式角标是同一条水平线（内缩一致），
            // 不再挂在转向灯下面 —— 没有转向灯时它会在上方留出一片空白、看着浮在中间。
            val laneCardShown = !laneGuide?.lanes.isNullOrEmpty()
            if (laneCardShown) {
                laneGuide?.let {
                    AmapLaneCard(
                        lanes = it,
                        modifier = Modifier.align(Alignment.TopCenter)
                            .offset(y = (LANE_CARD_TOP * u).dp),
                        height = (52 * u).dp,
                    )
                }
            }

            // ── 顶部中间：转向灯（原版 #blinkerRow）──
            // 没有车道卡时仍在原位 24u；有车道卡时让到它下面（81u），
            // 卡总高约 58u（18u 起 → 76u 收），箭头 39u 高、到 120u 收尾，
            // 与 130u 起的驾驶提示仍留 10u 空档，三者不互相压。
            BlinkerRow(
                leftOn = driving.leftBlinker == true,
                rightOn = driving.rightBlinker == true,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset(y = ((if (laneCardShown) BLINKER_TOP_WITH_LANES else BLINKER_TOP) * u).dp),
                u = u,
            )

            // ── 左下：建议速度 / 制动（原版 #bottomLeft；右下角让给画中画）──
            // 7.9.41 删掉了高德投屏「直行 12s」pill；7.9.46 又删掉「CAN 红灯」pill：
            // 灯色已经由场景里的停止线 + 立杆表达，卡上不再重复一条文字。
            Row(
                Modifier.align(Alignment.BottomStart).offset(x = (92 * u).dp, y = (-20 * u).dp),
                horizontalArrangement = Arrangement.spacedBy((22 * u).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Footnote(suggested = suggested, braking = braking, u = u)
            }
            DriverNotice(driving, Modifier.align(Alignment.TopCenter).offset(y = (130 * u).dp)
                .fillMaxWidth(0.4f), u)

            // ── 右下角：设备/画面状态角标（7.9.53 从页面状态行搬进来）──
            // 与左下角的「建议速度」共用 -20u 的底边距，左右对称
            cameraBadge?.let {
                CameraBadge(
                    it,
                    Modifier.align(Alignment.BottomEnd)
                        .offset(x = (-47 * u).dp, y = (-20 * u).dp),
                    u,
                    onConnectionClick,
                )
            }

            // ── 右侧：高德红绿灯胶囊（7.9.59，替代滚动区文字行）──
            // 垂直位置取 #6 自车（中心 ≈272u）与 #7 提示（中心 ≈176u）之间的高度带：
            // CenterEnd 再上提 10u，胶囊中心落在 ≈227u；上方限速牌收在 149u、
            // 下方设备角标起于 ≈420u，互不重叠。右对齐 47u 与限速牌同一条线。
            amapSignal?.let {
                AmapSignalCapsule(
                    it,
                    Modifier.align(Alignment.CenterEnd)
                        .offset(x = (-47 * u).dp, y = (-10 * u).dp),
                    u,
                )
            }
        }

        // ── 右下角画中画：距边留 18u（外框 9u 之外再留 9u），不压外框 ──
        // 圆角比原来大得多（18u），外面套一圈模糊白晕做「毛玻璃」边。
        // SurfaceView 不吃 clip，四角用与卡片同色的遮罩补掉（见 PipCornerMask）。
        if (pip != null) {
            val pipCorner = (18 * u).dp
            val pipShape = RoundedCornerShape(pipCorner)
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = (PIP_INSET * u).dp, bottom = (PIP_INSET * u).dp)
                    .fillMaxWidth(PIP_WIDTH_RATIO)
                    .aspectRatio(PIP_ASPECT)
                    .testTag("drive-view-pip"),
            ) {
                // 毛玻璃外晕：白色半透明垫在下面再模糊，在白色卡片上形成柔和的玻璃边
                Box(
                    Modifier.matchParentSize()
                        .blur((9 * u).dp, BlurredEdgeTreatment.Unbounded)
                        .background(Color.White.copy(alpha = 0.55f), pipShape),
                )
                Box(Modifier.matchParentSize().background(PipBacking, pipShape))
                Box(Modifier.matchParentSize().padding((1.5 * u).dp)) { pip() }
                // 圆弧之外的四个直角用卡片底色盖掉
                Canvas(Modifier.matchParentSize()) {
                    val radius = pipCorner.toPx()
                    val outer = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
                    val inner = Path().apply {
                        addRoundRect(
                            RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radius, radius)),
                        )
                    }
                    drawPath(
                        Path().apply { op(outer, inner, PathOperation.Difference) },
                        PageBg,
                    )
                }
                // 玻璃边缘高光 + 内衬
                Box(
                    Modifier.matchParentSize().border(
                        max(1f, 1.6f * u).dp,
                        Color.White.copy(alpha = 0.72f),
                        pipShape,
                    ),
                )
            }
        }
        }
    }
}

/**
 * 驾驶卡右下角状态角标的数据：设备 IP、7000 连接状态、是否出画面、分辨率/FPS。
 *
 * 单独抽一个数据类，是为了让驾驶卡不依赖相机模块（`CameraPlayback`），
 * 只吃「已经算好的四个值」。
 */
internal data class CameraBadgeInfo(
    val deviceIp: String?,
    val connection: CommaConnectionDisplay,
    val live: Boolean,
    /** 例如 `1280×720 · 30 FPS`；没画面时为 null，整行不画 */
    val detail: String? = null,
)

/** 高德投屏红绿灯（状态码与 CarrotManFields.trafficLightState 一致：1 红 / 2 绿 / 3 左转绿） */
internal data class TrafficHint(val state: Int, val remainSec: Int, val direction: Int)

/**
 * 路口灯色的统一表示。
 *
 * 两个来源语义不同，必须分开映射，不能共用同一套数字：
 * - 车端 7711（Tesla `DAS_road.trafficLightColor`，dashpilot `TL_COLOR_MAP`）：**1 红 / 2 绿 / 3 黄**
 * - 高德投屏广播（[TrafficHint.state]）：**1 红 / 2 绿 / 3 左转绿**
 *
 * 仅转换编码；调用方必须将 CAN 和导航分开绘制，不借用另一来源的秒数或距离。
 */
internal enum class TrafficLamp { NONE, RED, GREEN, YELLOW }

internal fun resolveLamp(worldState: Int, hint: TrafficHint?): TrafficLamp = when {
    worldState == 1 -> TrafficLamp.RED
    worldState == 2 -> TrafficLamp.GREEN
    worldState == 3 -> TrafficLamp.YELLOW
    hint?.state == 1 -> TrafficLamp.RED
    hint != null && hint.state in 2..3 -> TrafficLamp.GREEN
    else -> TrafficLamp.NONE
}

// ---------------------------------------------------------------------------
// 设计网格：preview.jpg 2280×1080 → 1000×474（等比，保证所有百分比与截图像素一致）
// ---------------------------------------------------------------------------

private const val DESIGN_W = 1000f
private const val DESIGN_H = 474f
internal const val DRIVE_VIEW_ASPECT = DESIGN_W / DESIGN_H

/** 灭点（占卡片高度），由车道带上下边反解：半宽 ∝ (y - 灭点) */
private const val Y_VP = 0.0935f
/** 相机到自车的距离（米），由「自车 0.6602H、50 米处 0.2731H」两点反解 */
private const val Z0 = 22.9f
/** yFrac - Y_VP = Y_K / (Z0 + 前向距离) */
private const val Y_K = 0.5713f * Z0
/** 每米对应像素（占卡片宽度）= A * yFrac - B */
private const val PXM_A = 0.0554f
private const val PXM_B = 0.00518f
private const val VIEW_RANGE_M = 50f
/** 自车所在位置的归一化 y（= yFrac(0)），作为 [CameraView.zoom] 的缩放锚点 */
private const val EGO_BOTTOM = 0.6648f


private val PageBg = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF777777)
private val GearBg = Color(0xFFADB3B3)
private val WheelTint = Color(0xFF5E5F61)
/** 车道带填充色（实测 #6BDF97 叠加在白底上） */
private val LaneGreen = Color(0xFF6BDF97)
private val LaneLine = Color(0xFFCFD3D7)
/** 最外侧两条线是路缘（curb），用褐色和中间的车道线区分开 */
private val CurbBrown = Color(0xFF8C5A3C)
/** 模型预测路径：原色偏青，线宽是普通线的 6 倍（7.9.41 用户要求「加粗到看着舒服」） */
private val PredictedPath = Color(0xFF3E8FA0)
private const val PREDICTED_PATH = "模型预测路径"
private const val PREDICTED_PATH_WIDTH = 6f
private val LimitRed = Color(0xFFE8112D)
/**
 * 外框三态（7.9.46）：
 * - 默认 **灰**：没有任何控制激活
 * - **横向**控制开启 → 蓝
 * - **纵向**控制开启 → 绿（与原版 `showBorder` 的 #5cbd68 一致）
 *
 * 两者同时开启时取绿：纵向（ACC/定速）代表车在主动跟车，比横向更该被一眼看到。
 */
private val CardEdgeIdle = Color(0xFFAEB6C2)
private val CardEdgeLateral = Color(0xFF2F6FEB)
private val CardEdgeLongitudinal = Color(0xFF5BBD68)
private val BlindRed = Color(0xFFFF0000)
private val AccActive = Color(0xFF5CBD68)
private val AccIdle = Color(0xFFD3D3D3)
/** 画中画的深色内衬（SurfaceView 是直角，靠这层边框收边） */
private val PipBacking = Color(0xFF12171C)
/** 驾驶模式角标色：白底上取 600 档，保证可读性（对应侧栏的亮色系） */
private val ModeEap = Color(0xFF2563EB)
private val ModeNoa = Color(0xFF7C3AED)
private val ModeNoo = Color(0xFFEA580C)
/** 白底上的车道宽度强调色（本车道）：cyan-700，白底可读性比主题亮青好 */
private val LaneWidthAccent = Color(0xFF0E7490)

/** 画中画宽度占卡片宽度的比例（用户指定「约 1/3」） */
private const val PIP_WIDTH_RATIO = 1f / 3f
/** 画中画宽高比：road 流常见 964×604 */
private const val PIP_ASPECT = 964f / 604f
/** 画中画与卡片边缘的距离（设计单位）；外框厚 9u，再留 9u 不压线 */
private const val PIP_INSET = 18f

/**
 * 车道方向卡的顶边（设计单位）。
 *
 * 18u = 右上角模式角标的 top，两处内缩一致，视觉上是一条水平线；
 * 卡片本体 58u 高（52u 内容 + 上下各 3u 内边距），下沿落在 76u。
 */
private const val LANE_CARD_TOP = 18f
/** 没有车道卡时转向灯的原位（原版 #blinkerRow，top 30 / 1000×474 网格换算） */
private const val BLINKER_TOP = 24f
/** 有车道卡时转向灯让到卡片下方：76u 下沿 + 5u 间隙 */
private const val BLINKER_TOP_WITH_LANES = 81f

internal fun String?.gearLetter(): String = when (this) {
    "park" -> "P"; "reverse" -> "R"; "neutral" -> "N"; "drive" -> "D"
    "sport" -> "S"; "low" -> "L"; "brake" -> "B"; "eco" -> "E"; "manumatic" -> "M"
    else -> "-"
}

/**
 * 按设计稿缩放字号，同时兜底可读下限（原版是给大屏做的，小屏要抬高次要文字）。
 *
 * ⚠️ `u` 是「1 设计像素 = 多少 dp」的比例，直接拿去当 `.sp` 会被系统 `fontScale`
 * **再放大一次**；而本卡片是 `aspectRatio` 固定高度、且刻意不裁剪，fontScale ≥ 1.3 时
 * 速度/挡位/限速牌就会互相压盖。这里预先除回 fontScale，让版式尺寸恒定
 * （代价是本卡不跟随系统字号 —— 它是图像式版式，版式优先于字号偏好）。
 */
@Composable
private fun scaled(designPx: Float, u: Float, floor: Float): TextUnit {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return (max(designPx * u, floor) / fontScale).sp
}

// ---------------------------------------------------------------------------
// 左列覆盖层
// ---------------------------------------------------------------------------

@Composable
private fun SpeedBlock(speed: Int?, modifier: Modifier, u: Float) {
    Row(modifier) {
        Text(
            speed?.toString() ?: "-",
            Modifier.alignByBaseline().testTag("drive-view-speed"),
            color = Ink,
            // 7.9.51：95u 时数字占卡高 1/5，压在无线区左侧太抢眼，收到 82u
            fontSize = scaled(82f, u, 30f),
            fontWeight = FontWeight.Light,
            lineHeight = scaled(80f, u, 30f),
            maxLines = 1,
        )
        Text(
            "km/h",
            Modifier.padding(start = (30 * u).dp).alignByBaseline(),
            color = Muted,
            fontSize = scaled(23f, u, 9f),
            maxLines = 1,
        )
    }
}

@Composable
private fun GearBadge(gear: String, modifier: Modifier, u: Float, onClick: (() -> Unit)?) {
    Box(
        modifier.widthIn(min = (72 * u).dp).height((34 * u).dp)
            .background(GearBg, RoundedCornerShape((6 * u).dp))
            .testTag("drive-view-gear")
            .clickable(enabled = onClick != null,
                onClickLabel = localized("全屏地图", "Full-screen map")) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            gear,
            color = Color.White,
            fontSize = scaled(22f, u, 8f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 方向盘：外圈 + 三幅 + 中心，按实际转向角旋转（原版 55px，实测 80 设计单位）。
 * 原版 `#steeringWheel` 在 ADAS 激活时给图标刷色（浅色主题绿、深色主题青），这里同样按 [adasOn] 换色。
 */
/**
 * 方向盘：角度随 [angleDeg] 旋转，横向激活时变绿。
 *
 * 7.9.41 起它同时是**进入控制页的入口**（[onClick]）：卡内分区只剩「导航」，
 * 控制页改从这里进；触控区就是 80u 见方的整个圆（约 80dp，够指头点）。
 */
@Composable
private fun SteeringWheel(
    angleDeg: Float?,
    adasOn: Boolean,
    modifier: Modifier,
    u: Float,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (adasOn) AccActive else WheelTint
    val wheelModifier = if (onClick == null) {
        modifier.size((80 * u).dp).testTag("drive-view-wheel")
    } else {
        modifier.size((80 * u).dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = localized("进入控制页", "Open controls") }
            .testTag("drive-view-wheel")
    }
    Canvas(wheelModifier) {
        val r = size.minDimension / 2f
        val stroke = size.minDimension * 0.10f
        rotate(angleDeg ?: 0f) {
            drawCircle(tint, radius = r - stroke / 2, style = Stroke(stroke))
            drawCircle(tint, radius = r * 0.20f, style = Stroke(stroke * 0.85f))
            // 三幅：左上、右上、正下
            drawLine(
                tint, Offset(center.x - r * 0.20f, center.y - r * 0.10f),
                Offset(center.x - r + stroke, center.y - r * 0.42f),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
            drawLine(
                tint, Offset(center.x + r * 0.20f, center.y - r * 0.10f),
                Offset(center.x + r - stroke, center.y - r * 0.42f),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
            drawLine(
                tint, Offset(center.x, center.y + r * 0.20f),
                Offset(center.x, center.y + r - stroke),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 右上：定速 + 限速牌
// ---------------------------------------------------------------------------

/**
 * 原版 `#speedSignRow`：`[ACC 定速(max)] [限速红圈]`，两者水平排列、垂直居中、间距 16。
 * 定速只在「行驶中且已设定」时出现，颜色随 ACC 激活状态变化（原版 `car.adasOn`）。
 */
@Composable
private fun SpeedSignRow(cruise: Int?, limit: Int?, adasOn: Boolean, modifier: Modifier, u: Float) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((20 * u).dp),
    ) {
        if (cruise != null && cruise > 0) {
            Row(Modifier.testTag("drive-view-acc")) {
                Text(
                    cruise.toString(),
                    Modifier.alignByBaseline(),
                    color = if (adasOn) AccActive else AccIdle,
                    fontSize = scaled(41f, u, 10f),
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    localized("定速", "max"),
                    Modifier.padding(start = (6 * u).dp).alignByBaseline(),
                    color = (if (adasOn) AccActive else AccIdle).copy(alpha = 0.85f),
                    fontSize = scaled(15f, u, 7f),
                    maxLines = 1,
                )
            }
        }
        if (limit != null) LimitSign(limit, u)
    }
}

/** 限速牌：白底红圈黑字，实测 ⌀209、环宽 26（设计值 ⌀92 / 环 11） */
@Composable
private fun LimitSign(limit: Int?, u: Float) {
    Box(
        Modifier.size((92 * u).dp)
            .border((11 * u).dp, LimitRed, CircleShape)
            .testTag("drive-view-limit"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            limit?.toString() ?: "-",
            color = Ink,
            fontSize = scaled(42f, u, 10f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 右上角：驾驶模式角标
// ---------------------------------------------------------------------------

/**
 * 驾驶模式角标（**只读**，不可点击），放在驾驶卡右上角。
 *
 * 四个取值：`Auto`（还没识别出场景）/ `EAP`（高速，道路类型 0）/
 * `NOA`（高架快速路，类型 6）/ `NOO`（常规道路，类型 1-5、7-9）。
 * 卡片是浅色底，所以颜色用加深版本而不是侧栏那套亮色，
 * 形态沿用卡内 pill 语言（淡底 + 半透明描边 + 色点/图标 + 文字）。
 *
 * 模式只影响本机「推荐车道」的取法，不代表车端已进入该模式。
 */
@Composable
private fun ModePill(mode: DrivingMode?, modifier: Modifier, u: Float) {
    val label = when (mode) {
        DrivingMode.HIGHWAY_EAP -> "EAP"
        DrivingMode.ELEVATED_NOA -> "NOA"
        DrivingMode.REGULAR_NOO -> "NOO"
        null -> "Auto"
    }
    val color = when (mode) {
        DrivingMode.HIGHWAY_EAP -> ModeEap
        DrivingMode.ELEVATED_NOA -> ModeNoa
        DrivingMode.REGULAR_NOO -> ModeNoo
        null -> Muted
    }
    Row(
        modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .border(max(1f, 1.5f * u).dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = (11 * u).dp, vertical = (4 * u).dp)
            .testTag("drive-view-mode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((5 * u).dp),
    ) {
        Icon(
            CustomIcons.Speed,
            contentDescription = null,
            modifier = Modifier.size((20 * u).dp),
            tint = color,
        )
        Text(
            label,
            color = color,
            fontSize = scaled(21f, u, 8f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 右侧：高德红绿灯胶囊（7.9.59）
// ---------------------------------------------------------------------------

/** Navigation display states are red or green; unsupported phases stay neutral. */
private fun signalCapsuleColor(state: Int): Color = when (state) {
    1 -> LimitRed
    2 -> SignalGreen
    else -> Color.Gray
}
private val SignalGreen = Color(0xFF22C55E)

/**
 * 高德车机版红绿灯胶囊：`[倒数秒数] [●方向]`。
 *
 * 版式按用户提供的两张高德截图 1:1 复刻：
 * 深色圆角胶囊，左侧白色粗体倒数数字，右侧一颗实心彩圆、
 * 圆内白色方向箭头（红灯白↑、绿灯白←，方向码 1 左转 / 2 右转 / 3 左掉头 / 4 直行 / 5 右掉头）。
 * 不再用文字写「左转」「红」，方向和灯色全部图形化。
 */
@Composable
private fun AmapSignalCapsule(signal: AmapDisplaySignal, modifier: Modifier, u: Float) {
    if (signal.state !in 1..2 || signal.remainingSec?.let { it <= 0 } != false) return
    val color = signalCapsuleColor(signal.state)
    Row(
        modifier.testTag("drive-view-signal")
            .background(Color(0xFF15181D).copy(alpha = 0.92f), RoundedCornerShape(50))
            // 7.9.60：整体缩到原来的 ~0.75（高 64u → 48u），避免在卡片右侧太抢眼
            .padding(
                start = (13 * u).dp, end = (5 * u).dp,
                top = (5 * u).dp, bottom = (5 * u).dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((9 * u).dp),
    ) {
        Text(
            signal.remainingSec?.toString() ?: "-",
            color = Color.White,
            fontSize = scaled(30f, u, 12f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.testTag("drive-view-signal-countdown"),
        )
        Box(
            Modifier.size((38 * u).dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (signal.direction in 1..5) {
                SignalArrow(signal.direction, Modifier.size((23 * u).dp))
            } else {
                Text("?", color = Color.White, modifier = Modifier.testTag("drive-view-signal-unknown"))
            }
        }
    }
}

/**
 * 高德 60073 红绿灯方向码 → 导航动作 ICON。
 *
 * 复用导航区同一套 [AmapManeuverIcon] 图形，不再单独手绘。
 * 原手绘版笔画 0.17W / 头长 0.36W / 头半宽 0.28W：头部宽占画布 56%，
 * 转弯段水平线只有约 0.54W，头把线整段吃掉 → 左/右转呈实心三角；
 * 掉头两翼回落到 y=0.30 正好与顶横杆重合 → 看起来像 M / 门框。
 */
internal fun signalDirectionIcon(direction: Int): Int = when (direction) {
    1 -> 2     // 左转
    2 -> 3     // 右转
    3 -> 8     // 左掉头
    4 -> 1     // 直行
    else -> 19 // 右掉头
}

/** 圆盘内的白色方向箭头：与导航卡共用 [AmapManeuverIcon] 的几何。 */
@Composable
private fun SignalArrow(direction: Int, modifier: Modifier) {
    AmapManeuverIcon(
        icon = signalDirectionIcon(direction),
        color = Color.White,
        modifier = modifier.testTag("drive-view-signal-arrow"),
        // 圆盘只有 38u、箭头 23u，按导航区原比例画线会显虚，加粗到 1.3 倍；
        // 左右转用「直行箭头旋转 90°」的画法（导航卡仍用折角 L 型）。
        strokeScale = 1.3f,
        turnStyle = TurnArrowStyle.Rotated,
    )
}

// ---------------------------------------------------------------------------
// 顶部转向灯 / 左下文本
// ---------------------------------------------------------------------------

@Composable
private fun BlinkerRow(leftOn: Boolean, rightOn: Boolean, modifier: Modifier, u: Float) {
    if (!leftOn && !rightOn) return
    val transition = rememberInfiniteTransition(label = "turn-signals")
    val phase by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "turn-phase",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((23 * u).dp)) {
        BlinkerArrow(left = true, alpha = if (leftOn && phase >= 0.5f) 1f else 0f,
            tag = "drive-view-left-turn", u = u)
        BlinkerArrow(left = false, alpha = if (rightOn && phase >= 0.5f) 1f else 0f,
            tag = "drive-view-right-turn", u = u)
    }
}

@Composable
private fun BlinkerArrow(left: Boolean, alpha: Float, tag: String, u: Float) {
    val tint = AccActive
    Canvas(Modifier.size((39 * u).dp).testTag(tag)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path()
        if (left) {
            path.moveTo(w * 0.10f, h * 0.5f)
            path.lineTo(w * 0.47f, h * 0.14f)
            path.lineTo(w * 0.47f, h * 0.35f)
            path.lineTo(w * 0.90f, h * 0.35f)
            path.lineTo(w * 0.90f, h * 0.65f)
            path.lineTo(w * 0.47f, h * 0.65f)
            path.lineTo(w * 0.47f, h * 0.86f)
        } else {
            path.moveTo(w * 0.90f, h * 0.5f)
            path.lineTo(w * 0.53f, h * 0.14f)
            path.lineTo(w * 0.53f, h * 0.35f)
            path.lineTo(w * 0.10f, h * 0.35f)
            path.lineTo(w * 0.10f, h * 0.65f)
            path.lineTo(w * 0.53f, h * 0.65f)
            path.lineTo(w * 0.53f, h * 0.86f)
        }
        path.close()
        drawPath(path, tint.copy(alpha = alpha))
    }
}

/** 原版 `#bottomLeft`：次要文本区（原版放里程），这里放「建议速度」 */
@Composable
private fun Footnote(suggested: Int?, braking: Boolean, u: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy((14 * u).dp)) {
        if (suggested != null) Text(
            localized("建议 ", "Tgt ") + suggested + " km/h",
            Modifier.testTag("drive-view-suggested"),
            color = Muted,
            fontSize = scaled(18f, u, 7.5f),
            maxLines = 1,
        )
        if (braking) {
            Text(
                localized("制动", "Brake"),
                Modifier.testTag("drive-view-brake"),
                color = LimitRed,
                fontSize = scaled(18f, u, 7.5f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 驾驶卡右下角的状态角标（原实时页顶部 #15 状态行，7.9.53 并进卡里）。
 *
 * 只保留「一眼要确认」的：设备 IP、连接状态、画面分辨率/FPS。
 * 不放重连按钮 —— 没画面时画面格里本来就有「重新连接」，卡片上再放一个会误触。
 */
@Composable
private fun CameraBadge(info: CameraBadgeInfo, modifier: Modifier, u: Float, onClick: (() -> Unit)?) {
    val dot = if (info.live) LiveGreenDot else info.connection.color()
    Column(
        modifier.testTag("drive-view-camera")
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() }),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy((1 * u).dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((7 * u).dp),
        ) {
            Box(
                Modifier.size((11 * u).dp).clip(CircleShape).background(dot),
            )
            Text(
                info.deviceIp ?: localized("未发现设备", "No device"),
                color = Muted,
                fontSize = scaled(18f, u, 8f),
                maxLines = 1,
                modifier = Modifier.testTag("drive-view-camera-ip"),
            )
            Text(
                info.connection.localizedLabel(),
                color = Muted.copy(alpha = 0.72f),
                fontSize = scaled(17f, u, 8f),
                maxLines = 1,
            )
        }
        info.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Muted.copy(alpha = 0.62f),
                fontSize = scaled(15f, u, 7f),
                maxLines = 1,
            )
        }
    }
}

/** 画面在线时的状态点色（与视频格里的「实时」同色系） */
private val LiveGreenDot = Color(0xFF34D399)

@Composable
private fun DriverNotice(driving: DrivingDisplay, modifier: Modifier, u: Float) {
    val alert = driving.alert
    val phase = driving.laneChange?.state
    val text = when {
        alert != null -> listOfNotNull(alert.first, alert.second).filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank {
                if (alert.status == "critical") localized("车端严重告警", "Vehicle critical alert")
                else localized("车端请求提示", "Vehicle prompt")
            }
        phase in setOf("preLaneChange", "laneChangeStarting", "laneChangeFinishing") -> {
            val label = when (phase) {
                "preLaneChange" -> localized("准备变道", "Preparing lane change")
                "laneChangeStarting" -> localized("正在变道", "Changing lane")
                else -> localized("变道收尾", "Finishing lane change")
            }
            val side = when (driving.laneChange?.direction) {
                "left" -> localized("左", "Left")
                "right" -> localized("右", "Right")
                else -> ""
            }
            "$side $label"
        }
        driving.speedEvent != null ->
            localized("前方测速提醒", "Speed camera ahead") + " ${driving.speedEvent.distanceM}m"
        else -> return
    }
    Text(
        text, modifier.testTag("drive-view-notice"),
        color = if (alert != null) LimitRed else Muted,
        fontSize = scaled(21f, u, 10f), textAlign = TextAlign.Center,
    )
}
/** 盲区：左右红色横向渐变，宽度占卡片 17%（原版 150 / 877） */
@Composable
private fun BoxScope.BlindWash(left: Boolean) {
    val colors = if (left) {
        listOf(BlindRed.copy(alpha = 0.42f), Color.Transparent)
    } else {
        listOf(Color.Transparent, BlindRed.copy(alpha = 0.42f))
    }
    Box(
        Modifier.fillMaxHeight().fillMaxWidth(0.17f)
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .background(Brush.horizontalGradient(colors)),
    )
}

// ---------------------------------------------------------------------------
// 车道（透视与 preview 实测一致：半宽 ∝ 距灭点距离）
// ---------------------------------------------------------------------------

/**
 * 相机视角 —— 原版 3D 场景按挡位切 `camera_view`：
 * `D` → back / 1.0、`P` → left_side / 0.9、`R` → top / 0.9、其它 → back / 0.9。
 *
 * 卡片是 2D 透视绘制，没有真正的 3D 相机，所以用同一套参数近似：
 * - [perspective] 1 = 原透视（后视），0 = 完全正交（俯视时车道线趋于平行）
 * - [vpY] 灭点高度占卡片比例（俯视抬高、侧视略降）
 * - [vpX] 灭点横向位置（侧视右移，车身因此偏左）
 * - [zoom] 视野倍率（<1 看得更远）
 */
private data class CameraView(
    val kind: DrivingCamera = DrivingCamera.REAR,
    val vpY: Float = Y_VP,
    val vpX: Float = 0.5f,
    val perspective: Float = 1f,
    val zoom: Float = 1f,
    /** 自车横向跟随灭点偏移（占卡片宽比例），否则车身会脱出车道 */
    val egoShiftX: Float = 0f,
    /** 自车下移量（占卡片高比例） */
    val egoDrop: Float = 0f,
    val egoScale: Float = 1f,
) {
    companion object {
        val BACK = CameraView()
        /** 俯视：灭点抬到 1/3 高、透视几乎取消、车身下移放大、车道铺到画面底 */
        val TOP = CameraView(
            kind = DrivingCamera.TOP,
            vpY = 0.30f, perspective = 0.12f, zoom = 0.9f,
            egoDrop = 0.245f, egoScale = 0.8f,
        )
        /** 左侧视：灭点右移一点、地平线略降，车身跟着右移保持居中 */
        val LEFT_SIDE = CameraView(
            kind = DrivingCamera.LEFT,
            vpY = Y_VP * 0.86f, vpX = 0.5f + 0.055f, perspective = 0.72f, zoom = 0.9f,
            egoShiftX = 0.055f,
        )

        /** 挡位 → 视角（原版 `gearMap`：1=P 2=R 3=N 4=D，这里直接用已译好的字母） */
        fun of(gear: String?): CameraView = when (drivingCamera(gear)) {
            DrivingCamera.LEFT -> LEFT_SIDE
            DrivingCamera.TOP -> TOP
            DrivingCamera.REAR -> if (gear == "drive") BACK else BACK.copy(zoom = 0.9f)
        }

    }
}

internal enum class DrivingCamera { REAR, LEFT, TOP }

internal fun drivingCamera(gear: String?): DrivingCamera = when (gear) {
    "park" -> DrivingCamera.LEFT
    "reverse" -> DrivingCamera.TOP
    else -> DrivingCamera.REAR
}

private class Projection(val w: Float, val h: Float, val view: CameraView = CameraView.BACK) {
    val cx = w * view.vpX

    /** 前向距离 → 归一化 y（原式是 `y - 灭点 ∝ 1/距离`，这就是地面平面透视的精确解） */
    fun yFrac(forwardM: Float): Float {
        val f = forwardM.coerceIn(-4f, 400f)
        val nonLinear = view.vpY + Y_K / (Z0 + f)
        // 正交近似：同样的起止点之间做线性插值，车道线于是保持等宽
        val linear = yFracAt0() + (f / VIEW_RANGE_M) * (yFracAt50() - yFracAt0())
        return nonLinear * view.perspective + linear * (1f - view.perspective)
    }

    /** 前向距离 → 屏幕 y；[zoom] < 1 时把近处压低、远处抬高，相当于相机后拉 */
    fun y(forwardM: Float): Float {
        val fraction = yFrac(forwardM)
        val anchored = EGO_BOTTOM + (fraction - EGO_BOTTOM) / view.zoom
        return h * anchored
    }

    /** 每米像素：透视下与「距灭点距离」成正比；正交近似下取中间尺度保持等宽 */
    fun pxPerM(forwardM: Float): Float {
        val perspectiveScale = PXM_A * yFrac(forwardM) - PXM_B
        val flatScale = PXM_A * ((yFracAt0() + yFracAt50()) / 2f) - PXM_B
        return w * (perspectiveScale * view.perspective + flatScale * (1f - view.perspective))
    }

    fun x(forwardM: Float, rightM: Float): Float = cx + rightM * pxPerM(forwardM)

    private fun yFracAt0() = view.vpY + Y_K / Z0

    private fun yFracAt50() = view.vpY + Y_K / (Z0 + VIEW_RANGE_M)
}

private fun DrawScope.drawLaneScene(
    road: DashboardRoad,
    braking: Boolean,
    view: CameraView,
    stopLineM: Float?,
    lamp: TrafficLamp,
    steeringDeg: Float?,
) {
    val proj = Projection(size.width, size.height, view)
    drawRoad(proj, road)
    drawStopLine(proj, stopLineM, lamp)
    drawSignalObject(proj, stopLineM, lamp)
    when (view.kind) {
        DrivingCamera.REAR -> drawEgoCar(braking, view)
        DrivingCamera.TOP -> drawTopCar(braking, view, steeringDeg)
        DrivingCamera.LEFT -> drawSideCar(braking, view, steeringDeg)
    }
}

/**
 * 停止线（原版 3D 场景的 `stop_line_dist`）。
 * 车端只在 `0 < dist < 127` 时给值，超过就不画——那是「超出感知范围」的哨兵。
 * 线的颜色跟灯色一致，让「红灯 + 停止线」在视觉上是一件事。
 */
private fun DrawScope.drawStopLine(proj: Projection, stopLineM: Float?, lamp: TrafficLamp) {
    if (stopLineM == null || stopLineM <= 0f || stopLineM >= 127f) return
    val y = proj.y(stopLineM)
    if (y < 0f || y > size.height) return
    val halfWidth = proj.pxPerM(stopLineM) * 2.2f
    val color = when (lamp) {
        TrafficLamp.RED -> LimitRed
        TrafficLamp.GREEN -> AccActive
        TrafficLamp.YELLOW -> Color(0xFFE8B21A)
        TrafficLamp.NONE -> Color(0xFFB9BEC3)
    }
    drawLine(
        color.copy(alpha = 0.9f),
        Offset(proj.cx - halfWidth, y),
        Offset(proj.cx + halfWidth, y),
        strokeWidth = max(3f, size.width * 0.0045f),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSignalObject(proj: Projection, distance: Float?, lamp: TrafficLamp) {
    if (distance == null || lamp == TrafficLamp.NONE) return
    val x = proj.x(distance, -3f)
    val y = proj.y(distance)
    val unit = (proj.pxPerM(distance) * 0.35f).coerceIn(size.width * 0.005f, size.width * 0.012f)
    val color = when (lamp) {
        TrafficLamp.RED -> LimitRed
        TrafficLamp.GREEN -> AccActive
        TrafficLamp.YELLOW -> Color(0xFFE8B21A)
        TrafficLamp.NONE -> return
    }
    drawLine(Muted, Offset(x, y), Offset(x, y - unit * 7f), strokeWidth = unit * 0.4f)
    drawRoundRect(Ink, Offset(x - unit, y - unit * 10f), Size(unit * 2f, unit * 6f),
        CornerRadius(unit * 0.4f))
    listOf(TrafficLamp.RED, TrafficLamp.YELLOW, TrafficLamp.GREEN).forEachIndexed { index, state ->
        drawCircle(if (state == lamp) color else WheelTint, radius = unit * 0.65f,
            center = Offset(x, y - unit * (9f - index * 2f)))
    }
}

private fun DrawScope.drawRoad(proj: Projection, road: DashboardRoad) {
    val curves = road.curves
    val band = road.laneBand()
    if (band != null) drawLaneBand(proj, band.first, band.second)
    curves.filter { it.label !in LANE_LABELS }.forEach { curve ->
        // 模型预测路径：6 倍线宽（好认得多），其它辅助曲线保持原来的细线
        val predicted = curve.label == PREDICTED_PATH
        drawSolidCurve(
            curve, proj,
            if (predicted) PredictedPath.copy(alpha = 0.5f) else Muted.copy(alpha = 0.35f),
            widthScale = if (predicted) PREDICTED_PATH_WIDTH else 1f,
        )
    }
    curves.filter { it.label in LANE_LABELS }.forEach { curve ->
        val probability = curve.probability?.takeIf { it > 0f } ?: return@forEach
        val outer = curve.label == LEFT_OUTER || curve.label == RIGHT_OUTER
        val code = when (curve.label) {
            LEFT_INNER -> road.leftLineCode
            RIGHT_INNER -> road.rightLineCode
            else -> null
        }
        // 最外侧两条是**路缘**，用褐色区分于中间的车道线；内边界仍按线型着色
        val color = if (outer) CurbBrown.copy(alpha = probability * 0.95f)
        else laneColor(code).copy(alpha = probability)
        if (code != null && code % 10 == 0) drawDashed(curve, proj, color)
        else drawSolidCurve(curve, proj, color)
    }
}

private fun laneColor(code: Int?): Color = when (code?.div(10)) {
    2 -> Color(0xFFD3AD37)
    3 -> Color(0xFF4B9AC1)
    else -> LaneLine
}

private val LANE_LABELS = setOf(LEFT_OUTER, LEFT_INNER, RIGHT_INNER, RIGHT_OUTER)
private const val LEFT_OUTER = "左外侧线"
private const val LEFT_INNER = "左内边界"
private const val RIGHT_INNER = "右内边界"
private const val RIGHT_OUTER = "右外侧线"

/** Fill only the overlapping, noncrossing, high-confidence model boundaries. */
private fun DrawScope.drawLaneBand(
    proj: Projection,
    left: DashboardRoadCurve,
    right: DashboardRoadCurve,
) {
    val bottomY = proj.y(max(left.points.first().forwardM, right.points.first().forwardM))
    val path = Path()
    left.points.forEachIndexed { index, point ->
        val x = proj.x(point.forwardM, point.rightM)
        val y = proj.y(point.forwardM)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    right.points.asReversed().forEach { point ->
        path.lineTo(proj.x(point.forwardM, point.rightM), proj.y(point.forwardM))
    }
    path.close()

    val topY = proj.y(min(left.points.last().forwardM, right.points.last().forwardM))
    drawPath(
        path,
        brush = Brush.verticalGradient(
            colors = listOf(LaneGreen.copy(alpha = 0.26f), LaneGreen.copy(alpha = 0.10f)),
            startY = bottomY,
            endY = topY,
        ),
    )
}

private fun DrawScope.drawSolidCurve(
    curve: DashboardRoadCurve,
    proj: Projection,
    color: Color,
    widthScale: Float = 1f,
) {
    val visible = curve.window() ?: return
    val path = Path()
    visible.points.forEachIndexed { index, point ->
        val x = proj.x(point.forwardM, point.rightM)
        val y = proj.y(point.forwardM)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color,
        style = Stroke(max(1f, size.width * 0.002f * widthScale), cap = StrokeCap.Round),
    )
}

/**
 * 车道线：**按世界坐标切段**，不是屏幕空间 dashPathEffect。
 * 这样近处的段更长更粗、远处自动变短变细，和原版 3D 渲染的观感一致。
 */
private fun DrawScope.drawDashed(
    curve: DashboardRoadCurve,
    proj: Projection,
    color: Color,
) {
    if (curve.points.size < 2) return
    val maxF = min(curve.points.last().forwardM, VIEW_RANGE_M)
    val minF = max(curve.points.first().forwardM, 0f)
    if (maxF <= minF) return

    var start: Offset? = null
    var end: Offset? = null
    var f = minF
    while (f <= maxF) {
        val phase = ((f % DASH_PERIOD_M) + DASH_PERIOD_M) % DASH_PERIOD_M
        if (phase < DASH_ON_M) {
            val offset = Offset(xAt(curve, proj, f), proj.y(f))
            if (start == null) start = offset
            end = offset
        } else if (start != null && end != null) {
            strokeSegment(start, end, proj, color)
            start = null
            end = null
        }
        f += DASH_STEP_M
    }
    if (start != null && end != null) strokeSegment(start, end, proj, color)
}

/**
 * 取前向 [f] 处车道线的屏幕 x。`f` 小于曲线起点时按「直线车道 x 与 (y-灭点) 成正比」外推，
 * 这样自车后方那一小段也能接上，不会在车尾处断掉。
 */
private fun xAt(curve: DashboardRoadCurve, proj: Projection, f: Float): Float {
    return proj.x(f, rightAt(curve, f))
}

/** 线宽按世界坐标 0.15 米换算，近处粗、远处细（原版 3D 线宽是等宽的） */
private fun DrawScope.strokeSegment(start: Offset, end: Offset, proj: Projection, color: Color) {
    val yFrac = ((start.y + end.y) / 2f) / proj.h
    val pxPerM = proj.w * (PXM_A * yFrac - PXM_B)
    val width = (pxPerM * 0.15f).coerceIn(proj.w * 0.0011f, proj.w * 0.0055f)
    drawLine(color, start, end, strokeWidth = width, cap = StrokeCap.Round)
}

/** 在曲线上插值取前向 [f] 处的横向偏移 */
private fun rightAt(curve: DashboardRoadCurve, f: Float): Float {
    val points = curve.points
    val bounded = f.coerceIn(points.first().forwardM, points.last().forwardM)
    val upper = points.indexOfFirst { it.forwardM >= bounded }.coerceAtLeast(1)
    val a = points[upper - 1]
    val b = points[upper]
    val fraction = (bounded - a.forwardM) / (b.forwardM - a.forwardM)
    return a.rightM + (b.rightM - a.rightM) * fraction
}

private const val DASH_ON_M = 1.3f
private const val DASH_GAP_M = 0.9f
private const val DASH_PERIOD_M = DASH_ON_M + DASH_GAP_M
private const val DASH_STEP_M = 0.4f

private fun DrawScope.drawTopCar(braking: Boolean, view: CameraView, steeringDeg: Float?) {
    val w = size.width * 0.063f
    val h = size.height * 0.23f
    val cx = size.width * (0.5f + view.egoShiftX)
    val bottom = size.height * (0.6602f + view.egoDrop)
    val top = bottom - h
    val turn = ((steeringDeg ?: 0f) / 12f).coerceIn(-30f, 30f)
    for (side in listOf(-1f, 1f)) {
        val x = cx + side * w * 0.49f
        rotate(turn, pivot = Offset(x, top + h * 0.23f)) {
            drawRoundRect(Ink, Offset(x - w * 0.055f, top + h * 0.16f),
                Size(w * 0.11f, h * 0.16f), CornerRadius(w * 0.02f))
        }
        drawRoundRect(Ink, Offset(x - w * 0.055f, top + h * 0.7f),
            Size(w * 0.11f, h * 0.16f), CornerRadius(w * 0.02f))
    }
    drawRoundRect(
        Brush.horizontalGradient(listOf(Ink, WheelTint, Ink), cx - w / 2f, cx + w / 2f),
        Offset(cx - w / 2f, top), Size(w, h), CornerRadius(w * 0.22f),
    )
    drawRoundRect(Color(0xFF87929A), Offset(cx - w * 0.37f, top + h * 0.26f),
        Size(w * 0.74f, h * 0.2f), CornerRadius(w * 0.06f))
    drawRoundRect(Color(0xFF333B42), Offset(cx - w * 0.34f, top + h * 0.49f),
        Size(w * 0.68f, h * 0.26f), CornerRadius(w * 0.06f))
    val tail = if (braking) LimitRed else Color(0xFF7A3A36)
    for (side in listOf(-1f, 1f)) {
        drawLine(tail, Offset(cx + side * w * 0.16f, bottom - h * 0.04f),
            Offset(cx + side * w * 0.41f, bottom - h * 0.04f), strokeWidth = h * 0.035f)
    }
}

private fun DrawScope.drawSideCar(braking: Boolean, view: CameraView, steeringDeg: Float?) {
    val w = size.width * 0.14f
    val h = size.height * 0.12f
    val cx = size.width * (0.5f + view.egoShiftX)
    val bottom = size.height * 0.66f
    val top = bottom - h
    val left = cx - w / 2f
    val body = Path().apply {
        moveTo(left, bottom - h * 0.18f)
        lineTo(left + w * 0.04f, top + h * 0.46f)
        lineTo(left + w * 0.2f, top + h * 0.4f)
        lineTo(left + w * 0.32f, top + h * 0.04f)
        quadraticTo(left + w * 0.5f, top - h * 0.08f, left + w * 0.67f, top + h * 0.09f)
        lineTo(left + w * 0.81f, top + h * 0.45f)
        lineTo(left + w, top + h * 0.57f)
        lineTo(left + w, bottom - h * 0.13f)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(WheelTint, Ink), top, bottom))
    val windows = Path().apply {
        moveTo(left + w * 0.25f, top + h * 0.42f)
        lineTo(left + w * 0.34f, top + h * 0.11f)
        lineTo(left + w * 0.64f, top + h * 0.15f)
        lineTo(left + w * 0.75f, top + h * 0.44f)
        close()
    }
    drawPath(windows, Color(0xFF89959C))
    drawLine(Ink, Offset(cx, top + h * 0.13f), Offset(cx, top + h * 0.44f), strokeWidth = w * 0.02f)
    for (fraction in listOf(0.2f, 0.8f)) {
        val center = Offset(left + w * fraction, bottom - h * 0.13f)
        drawCircle(Ink, h * 0.22f, center)
        drawCircle(LaneLine, h * 0.12f, center)
        if (fraction == 0.8f) rotate(((steeringDeg ?: 0f) / 12f).coerceIn(-30f, 30f), center) {
            drawLine(WheelTint, center - Offset(0f, h * 0.1f), center + Offset(0f, h * 0.1f),
                strokeWidth = h * 0.05f)
        }
    }
    drawLine(if (braking) LimitRed else Color(0xFF7A3A36),
        Offset(left + w * 0.025f, top + h * 0.55f), Offset(left + w * 0.07f, top + h * 0.55f),
        strokeWidth = h * 0.12f)
}

// ---------------------------------------------------------------------------
// 自车（原版是 Bevy 3D 渲染的 Tesla 尾部视角，这里按截图形状重画）
// ---------------------------------------------------------------------------

/**
 * 自车：按 preview 里那台 Tesla 的「略微俯视车尾」重画。
 *
 * 尺寸取自截图像素：车宽（含外后视镜）7.6% 卡片宽、车高 17.0% 卡片高，底部落在 0.660H。
 * 层次从下往上：地面投影 → 车身（五档横向渐变做体积）→ 车顶面 → 后风挡（含边条与反光）
 * → 尾箱折线 → 贯穿尾灯 → 牌照 → 后保险杠（含散热鳍与反光条）→ 外后视镜。
 * 7.9.41 重画：加了轮廓描边、玻璃反光、牌照槽与散热鳍，白底上更容易读成一台车。
 */
private fun DrawScope.drawEgoCar(braking: Boolean, view: CameraView = CameraView.BACK) {
    val cw = size.width * 0.0605f * view.egoScale
    val ch = size.height * 0.1704f * view.egoScale
    val cx = size.width * (0.5f + view.egoShiftX)
    val bottom = size.height * (0.6602f + view.egoDrop)
    val top = bottom - ch
    val rim = Color(0xFF15171A)
    val bodyDark = Color(0xFF23262A)
    val bodyMid = Color(0xFF34383D)
    val bodyLight = Color(0xFF4A5057)
    val glassTop = Color(0xFF808993)
    val glassBottom = Color(0xFF343941)
    val tail = if (braking) Color(0xFFF2564C) else Color(0xFF8E3A33)

    // 地面投影：外圈淡、内圈实，比单层椭圆更像真的影子
    drawOval(
        Color.Black.copy(alpha = 0.055f),
        Offset(cx - cw * 0.74f, bottom - ch * 0.075f), Size(cw * 1.48f, ch * 0.15f),
    )
    drawOval(
        Color.Black.copy(alpha = 0.10f),
        Offset(cx - cw * 0.52f, bottom - ch * 0.055f), Size(cw * 1.04f, ch * 0.105f),
    )

    // 车身：车顶窄 0.27，肩线在 0.16 高度就到位（0.50），侧面近乎垂直，尾端收到 0.43。
    // 肩线早到位是关键——慢慢鼓起来会像个面包车，先宽后直才像轿车/SUV 的尾部。
    val body = Path().apply {
        moveTo(cx - cw * 0.27f, top + ch * 0.028f)
        cubicTo(
            cx - cw * 0.43f, top + ch * 0.048f,
            cx - cw * 0.50f, top + ch * 0.115f,
            cx - cw * 0.50f, top + ch * 0.24f,
        )
        cubicTo(
            cx - cw * 0.505f, top + ch * 0.52f,
            cx - cw * 0.495f, top + ch * 0.78f,
            cx - cw * 0.43f, bottom,
        )
        lineTo(cx + cw * 0.43f, bottom)
        cubicTo(
            cx + cw * 0.495f, top + ch * 0.78f,
            cx + cw * 0.505f, top + ch * 0.52f,
            cx + cw * 0.50f, top + ch * 0.24f,
        )
        cubicTo(
            cx + cw * 0.50f, top + ch * 0.115f,
            cx + cw * 0.43f, top + ch * 0.048f,
            cx + cw * 0.27f, top + ch * 0.028f,
        )
        close()
    }
    drawPath(
        body,
        brush = Brush.horizontalGradient(
            listOf(bodyDark, bodyMid, bodyLight, bodyMid, bodyDark),
            startX = cx - cw * 0.5f, endX = cx + cw * 0.5f,
        ),
    )
    // 轮廓描边：白底上把车从背景里「抠」出来
    drawPath(body, rim, style = Stroke(max(0.6f, cw * 0.016f)))


    // 车顶面（俯视看到的一小片）
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(bodyLight, bodyMid),
            startY = top + ch * 0.02f, endY = top + ch * 0.12f,
        ),
        topLeft = Offset(cx - cw * 0.275f, top + ch * 0.02f),
        size = Size(cw * 0.55f, ch * 0.10f),
        cornerRadius = CornerRadius(cw * 0.15f),
    )

    // 后风挡：上窄下宽，玻璃比车身亮（反天空）
    val glass = Path().apply {
        moveTo(cx - cw * 0.235f, top + ch * 0.135f)
        quadraticTo(cx, top + ch * 0.115f, cx + cw * 0.235f, top + ch * 0.135f)
        quadraticTo(cx + cw * 0.33f, top + ch * 0.30f, cx + cw * 0.355f, top + ch * 0.50f)
        quadraticTo(cx, top + ch * 0.545f, cx - cw * 0.355f, top + ch * 0.50f)
        quadraticTo(cx - cw * 0.33f, top + ch * 0.30f, cx - cw * 0.235f, top + ch * 0.135f)
        close()
    }
    drawPath(
        glass,
        brush = Brush.verticalGradient(
            listOf(glassTop, glassBottom),
            startY = top + ch * 0.12f, endY = top + ch * 0.55f,
        ),
    )
    drawPath(glass, Color(0xFF1B1E22).copy(alpha = 0.75f), style = Stroke(max(0.5f, cw * 0.012f)))
    // 玻璃反光：左上角一道斜亮面
    drawPath(
        Path().apply {
            moveTo(cx - cw * 0.20f, top + ch * 0.155f)
            lineTo(cx - cw * 0.02f, top + ch * 0.145f)
            lineTo(cx - cw * 0.26f, top + ch * 0.40f)
            lineTo(cx - cw * 0.31f, top + ch * 0.40f)
            close()
        },
        Color.White.copy(alpha = 0.09f),
    )

    // 尾箱盖折线（一条横向钣金缝）
    drawLine(
        Color.Black.copy(alpha = 0.35f),
        Offset(cx - cw * 0.36f, top + ch * 0.555f),
        Offset(cx + cw * 0.36f, top + ch * 0.555f),
        strokeWidth = max(0.5f, cw * 0.012f),
    )

    // 贯穿式尾灯：左右两段，中间留车标
    val tailTop = top + ch * 0.60f
    val tailH = ch * 0.052f
    for (side in listOf(-1f, 1f)) {
        val x0 = if (side < 0) cx - cw * 0.46f else cx + cw * 0.10f
        val barWidth = cw * 0.36f
        drawRoundRect(tail, Offset(x0, tailTop), Size(barWidth, tailH), CornerRadius(tailH * 0.45f))
        drawRoundRect(
            (if (braking) Color(0xFFFFB3AE) else Color(0xFFC97A72))
                .copy(alpha = if (braking) 0.9f else 0.55f),
            Offset(x0 + barWidth * 0.06f, tailTop + tailH * 0.30f),
            Size(barWidth * 0.88f, tailH * 0.40f),
            CornerRadius(tailH * 0.2f),
        )
    }
    drawCircle(
        Color(0xFF9AA1A8).copy(alpha = 0.55f),
        radius = cw * 0.022f,
        center = Offset(cx, tailTop + tailH * 0.5f),
    )

    // 牌照：深色底槽 + 白牌
    drawRoundRect(
        Color(0xFF1A1D21),
        Offset(cx - cw * 0.19f, top + ch * 0.690f),
        Size(cw * 0.38f, ch * 0.095f),
        CornerRadius(cw * 0.015f),
    )
    drawRoundRect(
        Color(0xFFD9DEE3),
        Offset(cx - cw * 0.165f, top + ch * 0.703f),
        Size(cw * 0.33f, ch * 0.070f),
        CornerRadius(cw * 0.010f),
    )

    // 后保险杠：中间收窄的梯形，带下护板
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF3C4147), Color(0xFF202327)),
            startY = bottom - ch * 0.17f, endY = bottom,
        ),
        topLeft = Offset(cx - cw * 0.40f, bottom - ch * 0.17f),
        size = Size(cw * 0.80f, ch * 0.155f),
        cornerRadius = CornerRadius(cw * 0.055f),
    )
    // 下护板散热鳍
    for (index in 0..3) {
        val y = bottom - ch * 0.075f + index * ch * 0.016f
        drawLine(
            Color(0xFF121417).copy(alpha = 0.75f),
            Offset(cx - cw * 0.16f, y), Offset(cx + cw * 0.16f, y),
            strokeWidth = max(0.5f, ch * 0.006f),
        )
    }
    // 反光条
    val reflex = if (braking) Color(0xFFD2483E) else Color(0xFF7A3A34)
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(
            reflex,
            Offset(if (side < 0) cx - cw * 0.37f else cx + cw * 0.29f, bottom - ch * 0.052f),
            Size(cw * 0.08f, ch * 0.020f),
            CornerRadius(cw * 0.009f),
        )
    }

    // 外后视镜：壳体 + 一点镜面
    for (side in listOf(-1f, 1f)) {
        val x = if (side < 0) cx - cw * 0.615f else cx + cw * 0.505f
        drawRoundRect(
            bodyMid,
            Offset(x, top + ch * 0.225f),
            Size(cw * 0.11f, ch * 0.055f),
            CornerRadius(cw * 0.032f),
        )
        drawRoundRect(
            Color(0xFF6C757D).copy(alpha = 0.5f),
            Offset(x + cw * 0.015f, top + ch * 0.238f),
            Size(cw * 0.075f, ch * 0.022f),
            CornerRadius(cw * 0.012f),
        )
    }
}
