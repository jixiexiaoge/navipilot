package com.jixiexiaoge.drivingassist.ui.components

import com.jixiexiaoge.drivingassist.ui.theme.Surface900
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.CustomIcons
import com.jixiexiaoge.drivingassist.ui.utils.localized
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.Surface700
import com.jixiexiaoge.drivingassist.ui.theme.Surface800
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary

/**
 * 分区只剩两页（7.9.44）：**实时**与**控制**。
 *
 * 原「导航」页的所有元素已整块并入实时页（驾驶卡下方滚动区）：
 * 导航总览、灯组、投屏预览、巡航道路、导航对照、高德详细数据、7000 回传核对。
 * 导航页因此没有任何独占内容，整个分区删除，避免两页重复展示同一批数据。
 */
private val homeSections = listOf(
    localized("实时", "Live"),
    localized("控制", "Controls"),
)

private val homeSectionIcons = listOf(
    // 实时页 = 前视画面 + 导航 + 车辆数据，图标用播放符强调「实时」
    Icons.Default.PlayArrow,
    Icons.Default.Settings,
)

/** 控制页（唯一可写页）在分区列表中的位置，供「手动 / 只读」标记判断 */
internal const val CONTROLS_SECTION = 1

private fun sectionTitle(section: Int): String =
    homeSections.getOrElse(section) { homeSections.first() }

private fun sectionSummary(section: Int): String = when (section) {
    0 -> localized("前视画面 · 导航与车辆数据", "Live camera · navigation and vehicle data")
    else -> localized("手动操作与功能设置", "Manual actions and feature settings")
}

/**
 * 分区切换的作用域（由 [HomeDashboardTabs] 提供）。
 *
 * 7.9.45 起**卡内的分区按钮彻底取消**：进入控制页只走驾驶卡上的方向盘，
 * 离开控制页走 [OnPage]（控制页左上角那一行 48dp 触控高度的两只按钮）。
 * 实时页不需要「当前页」入口，卡左上角因此整块空出来，不再和 Auto 角标抢位置。
 */
internal class SectionTabsScope(
    val selected: Int,
    private val compact: Boolean,
    private val onSelect: (Int) -> Unit,
) {
    /** 供卡内元素（方向盘）跳页：点方向盘进控制页 */
    fun select(index: Int) {
        if (index in homeSections.indices) onSelect(index)
    }
    /** 控制页左上角：深色页面样式（48dp 触控高度） */
    @Composable
    fun OnPage(modifier: Modifier = Modifier) {
        Row(
            modifier.testTag("section-tabs-on-page"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            homeSections.forEachIndexed { index, label ->
                DashboardSectionTab(index, label, compact, selected == index) { onSelect(index) }
            }
        }
    }
}

/** 由 [HomeDashboardTabs] 提供；驾驶卡用它的 [SectionTabsScope.select] 响应方向盘点击，页面外为 null */
internal val LocalSectionTabs = compositionLocalOf<SectionTabsScope?> { null }

/**
 * 首页看板：内容区 + 页面底部固定条。
 *
 * 版式（页面标题栏整个取消）：
 * - **分区切换**：只在控制页左上角（[SectionTabsScope.OnPage]）；实时页没有分区按钮，
 *   进控制页走驾驶卡上的方向盘（`DriveViewCard` 的 `onEnterControls`）
 * - **标题 / 副标题 / 只读标记**：下移到页面底部（[SectionTitleFooter]）
 * - 驾驶卡内的车道宽度、车道识别、路口车道条各有归处，不再占用页面标题
 */
@Composable
internal fun HomeDashboardTabs(
    /** 页面底部固定条：实时页不放（引导条在驾驶卡下方），控制页放 [SectionTitleFooter] */
    footer: @Composable (Int) -> Unit = {},
    content: @Composable SectionTabsScope.(Int) -> Unit,
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    // 旧版本保存过「指南」页（index 3）等更靠后的分区，升级后索引会越界；
    // 统一收敛到合法范围，避免读到不存在的分区导致内容错位。
    val section = selectedSection.coerceIn(0, homeSections.lastIndex)
    val stateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("home-section-content")) {
        val tabs = remember(section, maxWidth) {
            // 窄屏（手机竖屏）页面上的分区按钮只留图标
            SectionTabsScope(section, maxWidth < 620.dp) { selectedSection = it }
        }
        CompositionLocalProvider(LocalSectionTabs provides tabs) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().weight(1f)) {
                    // 控制页：分区切换固定在页面左上角
                    if (section != 0) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            tabs.OnPage()
                        }
                    }
                    stateHolder.SaveableStateProvider("dashboard-v2-$section") {
                        // 实时页内部自带滚动（画面固定、下方看板滚动），不能套外层滚动容器
                        if (section == 0) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { tabs.content(0) }
                        } else {
                            AdaptivePageColumn(Modifier.weight(1f)) { tabs.content(section) }
                        }
                    }
                }
                // 页面最底部：实时页不渲染，控制页是标题与手动标记
                Box(Modifier.widthIn(max = 1040.dp).fillMaxWidth()) { footer(section) }
            }
        }
    }
}

/**
 * 页面底部标题条：原标题栏的大标题、副标题和「只读 / 手动」标记。
 * 深色页面上用一条淡底横条收口，右端是模式标记。
 */
@Composable
internal fun SectionTitleFooter(section: Int) {
    Row(
        Modifier.fillMaxWidth()
            .background(Surface900.copy(alpha = 0.72f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("home-section-heading-$section"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            sectionTitle(section),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            sectionSummary(section),
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = if (section == CONTROLS_SECTION) Color(0xFF78350F).copy(alpha = 0.72f)
            else Accent.copy(alpha = 0.13f),
        ) {
            Text(
                if (section == CONTROLS_SECTION) localized("手动", "MANUAL") else localized("只读", "READ ONLY"),
                Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                color = if (section == CONTROLS_SECTION) Color(0xFFFBBF24) else Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DashboardSectionTab(
    index: Int,
    label: String,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 44.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = label
                stateDescription = if (selected) {
                    localized("当前分类", "Selected")
                } else {
                    localized("切换到此分类", "Not selected")
                }
            }.testTag("home-section-$index"),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Accent.copy(alpha = 0.18f) else Surface800,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (selected) Accent.copy(alpha = 0.6f) else Surface700,
        ),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp)
                .padding(horizontal = if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 5.dp),
        ) {
            Icon(
                homeSectionIcons[index],
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) Accent else TextSecondary,
            )
            if (!compact) {
                Text(
                    label,
                    color = if (selected) Accent else TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

internal fun hasRoomForTwoPanes(widthDp: Float, fontScale: Float = 1f): Boolean =
    widthDp / fontScale.coerceAtLeast(1f) >= 600f

@Composable
internal fun AdaptivePageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val expanded = hasRoomForTwoPanes(maxWidth.value, LocalDensity.current.fontScale)
        Column(
            modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun AdaptiveHomeOverview(
    map: @Composable (Modifier) -> Unit,
    navigation: @Composable () -> Unit,
    results: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentMap by rememberUpdatedState(map)
    val currentNavigation by rememberUpdatedState(navigation)
    val currentResults by rememberUpdatedState(results)
    val currentControls by rememberUpdatedState(controls)
    // Reparent the existing cards without restarting the preview or resetting HUD state.
    val navigationCard = remember { movableContentOf { currentNavigation() } }
    val resultsCard = remember { movableContentOf { currentResults() } }
    val controlsCard = remember { movableContentOf { currentControls() } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val twoPanes = hasRoomForTwoPanes(maxWidth.value, LocalDensity.current.fontScale)
        // A fold/font fallback discards expansion; reopening starts with the normal dashboard.
        var mapExpanded by remember(twoPanes) { mutableStateOf(false) }
        val toggleLabel = if (mapExpanded) localized("恢复地图大小", "Restore map size")
            else localized("放大地图", "Expand map")
        val mapModifier by rememberUpdatedState(
            if (twoPanes) Modifier.clickable(role = Role.Button, onClickLabel = toggleLabel) {
                mapExpanded = !mapExpanded
            }.semantics {
                contentDescription = toggleLabel
                stateDescription = if (mapExpanded) localized("地图已放大", "Map expanded")
                    else localized("地图正常大小", "Map normal size")
            } else Modifier,
        )
        val indicator by rememberUpdatedState(if (twoPanes) toggleLabel else null)
        val mapCard = remember {
            movableContentOf {
                Box(Modifier.fillMaxWidth()) {
                    currentMap(mapModifier)
                    indicator?.let {
                        Surface(
                            Modifier.align(Alignment.BottomStart).padding(8.dp),
                            shape = RoundedCornerShape(8.dp), color = Color(0xDD202630),
                        ) { Text(it, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = Color.White, fontSize = 11.sp) }
                    }
                }
            }
        }
        if (twoPanes) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mapExpanded) mapCard()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!mapExpanded) mapCard()
                        navigationCard()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        controlsCard()
                        resultsCard()
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                mapCard()
                navigationCard()
                resultsCard()
                controlsCard()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveCardGrid(content: @Composable FlowRowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (hasRoomForTwoPanes(maxWidth.value, LocalDensity.current.fontScale)) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
