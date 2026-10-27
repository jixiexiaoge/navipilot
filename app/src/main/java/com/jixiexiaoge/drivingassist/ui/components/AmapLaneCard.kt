@file:Suppress("FunctionNaming", "MagicNumber")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.R
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLanes
import com.jixiexiaoge.drivingassist.ui.utils.localized

/**
 * 高德原生的车道方向卡。
 *
 * ## 与旧的二次开发版的区别
 * 7.9.46 之前这里是 `JunctionLaneStrip`：用 `AmapLaneIcon` 自己拿 `Path` 画细线箭头，
 * 深色页面上垫一层半透明胶囊。那是二次开发的图形，不是高德的东西。
 * 7.9.48 起改为**直接复用高德车机版 9.5 的原始位图**：
 * `Auto_9.5.0.600013` 的 `res/drawable` 里 927 张 PNG 是混淆命名的，
 * 用 `aapt2 dump resources` 反查得到原始资源名 `navistate_landfront_*`（共 14 张），
 * 已放进 `res/drawable-nodpi/`，文件名保持高德原名以便追溯。
 *
 * ## 素材本身的语义（逐张放大确认过，不是猜的）
 * 高德把「本车道允许的所有方向」画进同一张 53×70 的图：**白色 = 强调方向，灰色 = 其余允许方向**。
 * 例如 `_21` 是「白左转 + 灰直行」，正好对应 `drive_way_lane_Back_icon = 2`（直行/左转）。
 *
 * | 素材 | 方向集合 | 白色强调 |
 * |---|---|---|
 * | `_0` | 直行 | 直行 |
 * | `_21` | 直行/左转 | 左转 |
 * | `_43` | 直行/右转 | 右转 |
 * | `_63` | 左转/右转 | 两个都白 |
 * | `_73` | 左/直/右 | 右转 |
 * | `_95` | 直行/左掉头 | 左掉头 |
 * | `_b1` | 左转/左掉头 | 左转 |
 * | `_e5` | 左转/左掉头 | 左掉头 |
 * | `_c8` | 右转/右掉头 | 右掉头 |
 * | `_101` | 左转/直行/左掉头 | 左转 |
 * | `_115` | 右转/左掉头 | 左掉头 |
 * | `_125` | 左/右/左掉头 | 左掉头 |
 * | `_135` | 直/右/左掉头 | 左掉头 |
 * | `_15` | 公交专用（黄色） | — |
 * | `_150` | 潮汐车道（黄色） | — |
 *
 * **已知近似**：高德素材集里没有「纯左转」「纯右转」「纯左掉头」三张，
 * 因为高德自己也是用带灰次方向的组合图表达（见上表 `_b1` / `_43` / `_e5`）。
 * 我们沿用同一套素材，主方向永远正确，灰次方向可能与实际车道略有出入，
 * 所以这里**不额外补画**任何东西，保持与高德一致。
 */

/** 一条素材：方向集合 + 强调集合（字母表 S 直行 / L 左 / R 右 / U 左掉头 / V 右掉头） */
private data class LaneAsset(val branches: String, val emphasized: String, val res: Int)

/**
 * 高德 9.5 原始车道素材表。`branches` 用集合语义（顺序无关）。
 * 强调集合用来区分形状相同、强调不同的两张（`_b1` 强调左转 / `_e5` 强调左掉头）。
 */
private val AMAP_LANE_ASSETS = listOf(
    LaneAsset("S", "S", R.drawable.navistate_landfront_0),
    LaneAsset("LS", "L", R.drawable.navistate_landfront_21),
    LaneAsset("SR", "R", R.drawable.navistate_landfront_43),
    LaneAsset("LR", "LR", R.drawable.navistate_landfront_63),
    LaneAsset("LSR", "R", R.drawable.navistate_landfront_73),
    LaneAsset("SU", "U", R.drawable.navistate_landfront_95),
    LaneAsset("LU", "L", R.drawable.navistate_landfront_b1),
    LaneAsset("LU", "U", R.drawable.navistate_landfront_e5),
    LaneAsset("RV", "RV", R.drawable.navistate_landfront_c8),
    LaneAsset("LSU", "L", R.drawable.navistate_landfront_101),
    LaneAsset("UR", "U", R.drawable.navistate_landfront_115),
    LaneAsset("ULR", "U", R.drawable.navistate_landfront_125),
    LaneAsset("USR", "U", R.drawable.navistate_landfront_135),
)

/** 一条车道图形的方向分支。字母表：S 直行、L 左转、R 右转、U 左掉头、V 右掉头 */
internal data class LaneIconShape(val branches: String, val emphasized: String = branches, val special: String? = null)

/**
 * `drive_way_lane_Back_icon` → 方向分支。
 *
 * 视觉编码独立于「推荐」与「是否允许通行」，只描述这条车道能往哪走。
 * `0..12` 是基础组合，`15..27` 是同一套的别名（高德两套编码并存），
 * `30..48` 是同形状换强调方向的变体，`56/62/71/85/88/89` 是特殊车道。
 */
internal fun laneIconShape(code: String): LaneIconShape? {
    val shapes = listOf("S", "L", "SL", "R", "SR", "U", "LR", "SLR", "V", "SU", "SV", "LU", "RV")
    val normalized = code.trim().lowercase()
    val number = normalized.toIntOrNull()
    if (number != null && number in 0..12) return LaneIconShape(shapes[number])
    if (number != null && number in 15..27) return LaneIconShape(shapes[number - 15])
    return when (number) {
        13, 28 -> LaneIconShape("S", special = "merge")
        14, 29 -> LaneIconShape("LU", special = "merge")
        30 -> LaneIconShape("SL", "S")
        31 -> LaneIconShape("SL", "L")
        32 -> LaneIconShape("SR", "S")
        33 -> LaneIconShape("SR", "R")
        34 -> LaneIconShape("LR", "L")
        35 -> LaneIconShape("LR", "R")
        36 -> LaneIconShape("SLR", "S")
        37 -> LaneIconShape("SLR", "L")
        38 -> LaneIconShape("SLR", "R")
        39 -> LaneIconShape("SU", "S")
        40 -> LaneIconShape("SU", "U")
        41 -> LaneIconShape("SV", "S")
        42 -> LaneIconShape("SV", "V")
        43 -> LaneIconShape("LU", "L")
        44 -> LaneIconShape("LU", "U")
        45 -> LaneIconShape("RV", "R")
        46 -> LaneIconShape("RV", "V")
        47 -> LaneIconShape("LU", "L", "merge")
        48 -> LaneIconShape("LU", "U", "merge")
        56 -> LaneIconShape("SLU")
        62 -> LaneIconShape("", special = "可变")
        71 -> LaneIconShape("SLU", "S")
        85 -> LaneIconShape("", special = "潮汐")
        88 -> LaneIconShape("", special = "专用")
        89 -> LaneIconShape("", special = "未划线")
        else -> when (normalized) {
            "straight" -> LaneIconShape("S")
            "left" -> LaneIconShape("L")
            "right" -> LaneIconShape("R")
            "straight_left" -> LaneIconShape("SL")
            "straight_right" -> LaneIconShape("SR")
            "uturn_left" -> LaneIconShape("U")
            "uturn_right" -> LaneIconShape("V")
            "left_right" -> LaneIconShape("LR")
            "straight_left_right" -> LaneIconShape("SLR")
            "straight_uturn_left" -> LaneIconShape("SU")
            "straight_uturn_right" -> LaneIconShape("SV")
            "left_uturn_left" -> LaneIconShape("LU")
            "right_uturn_right" -> LaneIconShape("RV")
            else -> null
        }
    }
}

/** 特殊车道（不走方向匹配）：专用 / 潮汐 */
private val BUS_LANE_RES = R.drawable.navistate_landfront_15
private val TIDAL_LANE_RES = R.drawable.navistate_landfront_150

/** 素材原始宽高（53×70），用来算每格宽度，保证箭头不变形 */
private const val LANE_ASSET_ASPECT = 53f / 70f

/**
 * `drive_way_lane_Back_icon` → 高德原生素材。
 *
 * 编码体系沿用 [laneIconShape]：`0..12` 是基础组合，`15..27` 是同一套的别名，
 * `30..48` 是「同形状但换一个强调方向」的变体，`62/85/88/89` 是可变/潮汐/专用/未划线。
 * 找不到可用素材时返回 null —— **不伪造方向**，调用方只画空白格子。
 */
internal fun laneCardAsset(rawIcon: String): Int? {
    val shape = laneIconShape(rawIcon) ?: return null
    // 特殊车道只有「潮汐」「专用」有原生图；可变/未划线没有对应图，
    // 宁可不画箭头也不借一张方向图冒充。
    // 「merge」只是汇入标记，方向本身照常走下面的 branches 匹配。
    when (shape.special) {
        "潮汐" -> return TIDAL_LANE_RES
        "专用" -> return BUS_LANE_RES
        "可变", "未划线" -> return null
    }
    val branches = shape.branches.toSortedSet()
    if (branches.isEmpty()) return null
    val emphasis = shape.emphasized.toSortedSet()
    // 1) 形状与强调完全一致；2) 形状一致；3) 形状是超集且多余方向最少
    fun assetOf(wanted: Set<Char>): LaneAsset? =
        AMAP_LANE_ASSETS.firstOrNull {
            it.branches.toSortedSet() == wanted && it.emphasized.toSortedSet() == emphasis
        } ?: AMAP_LANE_ASSETS.firstOrNull { it.branches.toSortedSet() == wanted }

    assetOf(branches)?.let { return it.res }
    // 2) 素材是超集：多画一个灰次方向，但主方向不会错
    AMAP_LANE_ASSETS
        .filter { it.branches.toSortedSet().containsAll(branches) }
        .minByOrNull { it.branches.length }
        ?.let { return it.res }
    // 3) 素材集里没有这个组合（纯单向 / 纯掉头 / 直行+右掉头）：退到主方向相同的那张。
    //    高德自己也不画这些组合 —— 它的车道卡同样用带灰次方向的图表达单方向车道。
    val key = shape.emphasized.toCharArray().sorted().joinToString("") + "|" +
        shape.branches.toCharArray().sorted().joinToString("")
    val fallbackBranches = LANE_ASSET_FALLBACK[key]
        ?: LANE_ASSET_FALLBACK[shape.branches.toCharArray().sorted().joinToString("")]
    return fallbackBranches?.toSortedSet()?.let { assetOf(it)?.res }
}

/**
 * 兜底表：形状 → 用哪套素材。
 *
 * 键是 `强调方向|完整方向`（都是排序后的字母串），值是素材的方向集合。
 * 只处理「高德没有对应图」的几种：纯左转、纯右转、纯左掉头、纯右掉头、直行+右掉头。
 */
private val LANE_ASSET_FALLBACK = mapOf(
    "L|L" to "LS",     // 纯左转 → 白左转（灰直行）
    "R|R" to "SR",     // 纯右转 → 白右转（灰直行）
    "U|U" to "SU",     // 纯左掉头 → 白掉头（灰直行）
    "V|V" to "RV",     // 纯右掉头 → 白右掉头（灰右转）
    "S|SV" to "RV",    // 直行+右掉头 → 白右掉头
    "V|SV" to "RV",
    "SV|SV" to "RV",
)

/**
 * 车道方向卡：**高德车机版的蓝色圆角卡 + 白色箭头**（7.9.50 起按实拍截图还原）。
 *
 * 与上一版（深色胶囊 + 推荐车道整格亮蓝）的差别，全部来自 2026-10-22 的高德车机实拍：
 * - 卡底是**高德品牌蓝**（实拍 #0151FE），不是深灰；
 * - 推荐车道 = **满白箭头**，其余车道 = **压暗成深蓝的同一张图**（实拍里就是深蓝，不是半透明白）；
 * - 没有格子高亮框、没有格子间距：箭头直接排在蓝底上。
 *
 * 尺寸策略：格子宽度由 [height] 按素材 53:70 反推，保证箭头不被拉扁；
 * 车道多到放不下时**整体等比缩小**（不换行、不裁切），所以 8 车道也不会溢出。
 *
 * @param lanes 13012 车道组；为空或空列表时整卡不渲染
 * @param height 单格内容高度（不含卡片内边距）
 */
@Composable
internal fun AmapLaneCard(
    lanes: AmapJunctionLanes?,
    modifier: Modifier = Modifier,
    height: Dp,
) {
    val list = lanes?.lanes.orEmpty()
    if (list.isEmpty()) return
    // 圆角和内边距都跟着高度走：驾驶卡在任何屏宽下都是等比缩放，写死 dp 会在窄屏上显胖
    val shape = RoundedCornerShape(height * 0.20f)
    BoxWithConstraints(modifier.testTag("amap-lane-card")) {
        val insetH = height * 0.16f
        val insetV = height * 0.05f
        val available = (maxWidth - insetH * 2).coerceAtLeast(1.dp)
        val cellWidth = minOf(height * LANE_ASSET_ASPECT, available / list.size)
        Row(
            Modifier
                .background(LaneCardBlue, shape)
                .padding(horizontal = insetH, vertical = insetV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            list.forEach { lane ->
                val recommended = lane.recommended
                val description = lane.direction?.label
                    ?: localized("车道方向未识别", "Lane direction unknown")
                Box(
                    Modifier.width(cellWidth).height(height)
                        .testTag(if (recommended) "amap-lane-cell-advised" else "amap-lane-cell")
                        .semantics {
                            contentDescription = if (recommended) {
                                localized("推荐车道 · $description", "Advised lane · $description")
                            } else {
                                description
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    laneCardAsset(lane.rawIcon)?.let { res ->
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            // 素材本身是白色（推荐）/ 半透明白（其余），
                            // 直接染成白 / 深蓝，跟高德蓝色卡上的对比关系一致
                            colorFilter = ColorFilter.tint(
                                if (recommended) Color.White else LaneCardDimArrow,
                                BlendMode.SrcIn,
                            ),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 高德车机版车道卡的品牌蓝（实测 #0151FE） */
private val LaneCardBlue = Color(0xFF0A56FC)
/** 非推荐车道箭头：蓝底上压暗成深蓝（实测就是深蓝，不是半透明白） */
private val LaneCardDimArrow = Color(0xFF0B2E6B)
