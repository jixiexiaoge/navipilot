@file:Suppress("FunctionNaming", "MagicNumber")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jixiexiaoge.drivingassist.camera.CameraPhase
import com.jixiexiaoge.drivingassist.camera.CameraPlayback
import com.jixiexiaoge.drivingassist.camera.RoadFrameTap
import com.jixiexiaoge.drivingassist.camera.WebRtcRoadCameraPlayer
import com.jixiexiaoge.drivingassist.data.CarrotDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.CommaConnectionDisplay
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLanes
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import com.jixiexiaoge.drivingassist.navigation.NaviDeliverySnapshot
import com.jixiexiaoge.drivingassist.ui.theme.Surface900
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.utils.localized
import com.jixiexiaoge.drivingassist.vision.LaneInference
import com.jixiexiaoge.drivingassist.vision.LaneModelMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.roundToInt

/** 还没收到首帧、拿不到真实分辨率时用的兜底宽高比 */
private const val VIDEO_ASPECT_FALLBACK = 16f / 9f

/**
 * 画面格的实际宽高比：**优先用 5001 传上来的真实帧尺寸**（[CameraPlayback.width] /
 * [CameraPlayback.height]，已按旋转角换算过），没出帧才回落 16:9。
 *
 * 比例必须跟着源走，否则容器比例与码流比例不一致时，虽然 WebRTC 渲染器是等比缩放、
 * 不会拉伸变形，但会在另一侧留出黑边（或与 FILL 相反地裁掉画面）。
 */
internal fun CameraPlayback.aspect(): Float =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else VIDEO_ASPECT_FALLBACK

/** 两格（地图 / 实时画面）统一的圆角半径 */
internal val MEDIA_CORNER_RADIUS = 18.dp

/**
 * 两格（地图 / 实时画面）**平分一行宽度**时的共用高度。
 *
 * 高度只能由**实时画面的真实宽高比**反推（[aspect] 来自 5001 的帧尺寸）：
 * 视频的比例是硬约束，不能为了迁就地图去压它，否则画面就会变形或被裁。
 * 于是宽度对半 → 高度由视频格定 → 左格地图去适应这个高度
 * （WebView 内用 contain 完整显示地图，允许留边，见 `amap2tesla_player.html`）。
 *
 * [maxHeight] 是兜底上限，避免大屏上这一行把下面的滚动区挤没。
 */
internal fun mediaPaneHeight(paneWidth: Dp, aspect: Float, maxHeight: Dp? = null): Dp {
    val height = paneWidth / if (aspect > 0f) aspect else VIDEO_ASPECT_FALLBACK
    return if (maxHeight == null) height else minOf(height, maxHeight)
}

internal fun mediaRowHeight(width: Dp, mapAspect: Float, videoAspect: Float, maxHeight: Dp): Dp =
    minOf(width / (mapAspect + videoAspect), maxHeight)

/**
 * 地图格是否出现：**有高德显示模型，或本机投屏服务正在运行**。
 *
 * 8.0.26：投屏模式不再依赖高德导航。原来只看高德显示模型（加上内嵌面板要求广播新鲜），
 * 导致「只开投屏、没开高德导航」时左格永远停在「请启动高德地图车机版」，
 * 用户明明把投屏开起来了却看不到地图。
 *
 * @param hasAmapDisplay 调用方是否传入了高德导航显示模型
 * @param projectionPort 本进程投屏服务发布的端口；非空即代表服务在跑
 */
internal fun mapPaneVisible(hasAmapDisplay: Boolean, projectionPort: Int?): Boolean =
    hasAmapDisplay || projectionPort != null

/**
 * 导航行（左地图 / 右实时画面）的最大高度。
 *
 * 半屏宽按 1.46:1 算下来约 240dp，太高会把下面的滚动区挤没（真机实测），
 * 压到 260dp 后整块能落在首屏内。
 */
private val NAVI_MAP_MAX_HEIGHT = 260.dp

private val WarnAmber = Color(0xFFFBBF24)
private val OnlineGreen = Color(0xFF22C55E)
private val TimeoutOrange = Color(0xFFF97316)

/** 设备连接状态的中文/英文标签（看板与画面区共用一套措辞） */
internal fun CommaConnectionDisplay.localizedLabel(): String = when (this) {
    CommaConnectionDisplay.Compact -> localized("已连接", "Connected")
    CommaConnectionDisplay.Searching -> localized("搜索中", "Searching")
    CommaConnectionDisplay.Connecting -> localized("连接中", "Connecting")
    CommaConnectionDisplay.Waiting -> localized("等待数据", "Waiting data")
    CommaConnectionDisplay.Timeout -> localized("数据超时", "Data timeout")
    CommaConnectionDisplay.Disconnected -> localized("未连接", "Offline")
}

internal fun CommaConnectionDisplay.color(): Color = when (this) {
    CommaConnectionDisplay.Compact -> OnlineGreen
    CommaConnectionDisplay.Timeout -> TimeoutOrange
    else -> TextSecondary
}

/**
 * 实时页 —— 原「AR」页与「看板」页合并后的唯一实时视图。
 *
 * 页面结构（顺序即视觉顺序）：
 * 1. **前视画面**：固定高度、不参与滚动。SurfaceView 放在滚动容器里会脱离父级裁剪，
 *    滚动时可能盖住标题栏，所以画面区单独占一块。
 * 2. **画面状态行**：连接状态 + 设备 IP + 实际分辨率/FPS + 重连按钮。
 * 3. **下方滚动区**：驾驶视图卡片 + 运营摘要 + 规划与控制（即原看板全部内容），
 *    再往下是车道模型切换与安全声明。
 *
 * 7.9.44：原「导航」页整块并入本页滚动区（分区只剩 实时 / 控制）。
 * 7.9.45：投屏地图从整屏宽缩到**半屏**，右半屏并排一列行程读数。
 * 7.9.48：左半屏的**投屏实时画面换成矢量路口图**；车道方向卡改成高德原生素材并挪到驾驶卡顶部中间。
 * 7.9.64：**路口放大图整块下线**（[JunctionCrossView] / 路口矢量数据链路一并删除），
 * 右半屏恒显示 5001 的实时画面 —— 不再按「有没有路口数据」在图和视频之间来回切换。
 * 画面格按码流真实宽高比排版，渲染器改用 `SCALE_ASPECT_FIT`，保证等比、不裁切。
 * 当前顺序：画面状态行 → **左半内嵌地图浏览器 / 右半实时画面** → 巡航灯组 →
 * 高德巡航道路 → 导航对照 →
 * 折叠「高德详细数据」→ 折叠「7000 回传核对」→ 告警与规划面板 → 车道模型切换。
 * 宽屏固定区为地图/视频行 → 驾驶卡，下方详情独立滚动。
 * 窄屏视频固定顶部，地图 → 驾驶卡 → 详情在下方共同滚动。
 *
 * 原右侧侧栏删除后，**设备搜索/发现信息并入本页的画中画**（7.9.39）：
 * - 视频接上 → 直接出画面
 * - 只发现设备（7000 在线但没画面）→ 显示 IP + 连接状态
 * - 5001 连不上 → 额外提示「需开启视频流功能」
 *
 * 页面标题栏取消（7.9.40）：
 * - **分区按钮已从驾驶卡左上角删除**（7.9.45）：进控制页点方向盘，卡上不再有 pill
 * - 车道识别 chip 叠在**画面居中靠下**
 * - 车道宽度画在**自车正下方**（原盲区提示文字的位置）
 * - 车道方向卡画在**卡片顶部中间**（高德原生素材，7.9.48）
 *
 * @param host 选中设备的 IP；为空时表示还没发现设备
 * @param snapshot 看板数据；为 null 时只渲染摄像头部分（测试与降级用）
 * @param drivingMode 驾驶模式（Auto/EAP/NOA/NOO），显示在驾驶卡右上角
 * @param junction 高德路口车道引导（13012），显示在**驾驶卡正下方居中**（7.9.41 从页面底部移上来）
 * @param laneGeometry 车道宽度（7000 modelV2 四线差分），显示在驾驶卡自车正下方
 * @param deviceIp 搜索/发现到的设备 IP（与 [host] 同源，单独传是为了表述清楚）
 * @param connection 设备在线状态（来自 7000 数据链路）
 * @param onConnectionClick 点连接状态：打开 7706 数据调试面板
 * @param amap 高德导航显示模型（原导航页的导航总览 / 灯组 / 巡航道路 / 详细面板都取自它）
 * @param vehicle 7000 车端显示快照：导航总览的回退数据源 + 导航对照卡
 * @param delivery 7714 导航下发状态，透传给导航对照卡
 * @param routeReady 路线任务是否就绪；未就绪时导航对照会撤下旧动作
 */
@Composable
internal fun RealTimePage(
    host: String?,
    snapshot: DashboardSnapshot? = null,
    drivingMode: DrivingMode? = null,
    junction: AmapJunctionLanes? = null,
    laneGeometry: LaneGeometry? = null,
    laneFusion: com.jixiexiaoge.drivingassist.data.LaneFusionEstimate =
        com.jixiexiaoge.drivingassist.data.LaneFusionEstimate(),
    deviceIp: String? = host,
    connection: CommaConnectionDisplay = CommaConnectionDisplay.Disconnected,
    onConnectionClick: () -> Unit = {},
    // ── 原「导航」页并入的数据（7.9.44）──
    amap: AmapNavigationDisplay? = null,
    vehicle: CarrotDisplaySnapshot? = null,
    delivery: NaviDeliverySnapshot = NaviDeliverySnapshot(),
    routeReady: Boolean = true,
    /** 「关于」入口：导航页删除后，原来点应用名打开关于的入口挪到本页最底部 */
    onAboutClick: () -> Unit = {},
    /** 左半屏内嵌浏览器在「没开高德 / 投屏未开启」时要跳去的投屏页（7.9.60） */
    onOpenProjection: () -> Unit = {},
    /**
     * 投屏视口（端口 + 画面比例）。默认读进程级 `ProjectionState`；
     * 抽成参数是为了让测试能注入「投屏服务在跑」的场景，
     * 验证「只开投屏、没开高德」时地图格也会出现（8.0.26）。
     */
    projection: MapProjectionViewport = rememberProjectionViewport(),
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
    val hideInlineMedia = maxWidth > maxHeight && maxHeight < 480.dp
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var mapFullscreen by rememberSaveable { mutableStateOf(false) }
    // 投屏服务在跑（本进程 ProjectionService 已绑屏并起了 HTTP 服务）→ 端口非空。
    // 这是一条**独立于高德导航**的链路：只要投屏开着，左格就该显示地图，
    // 不该再要求「高德广播新鲜」（原来只开投屏、没开高德时会一直停在
    // 「请启动高德地图车机版」的提示上，见 8.0.26）。
    val projectionRunning = projection.port != null
    val mapAvailable = mapPaneVisible(amap != null, projection.port)
    val expanded = mapFullscreen && mapAvailable
    val videoEnabled = !expanded && !hideInlineMedia
    var mapBounds by remember { mutableStateOf(Rect.Zero) }
    var scrollBounds by remember { mutableStateOf(Rect.Zero) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val compactScroll = rememberScrollState()
    val detailsScroll = rememberScrollState()
    var compactOffset by rememberSaveable { mutableIntStateOf(0) }
    var detailsOffset by rememberSaveable { mutableIntStateOf(0) }
    val jetlink = rememberJetlinkStatus(host)
    // 分区切换作用域：点方向盘要跳到控制页，lambda 里不能读 CompositionLocal，先取出来
    val sectionTabs = LocalSectionTabs.current
    val onEnterControls: (() -> Unit)? = sectionTabs?.let { scope ->
        { scope.select(CONTROLS_SECTION) }
    }
    var output by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val player = remember(host, retry) { WebRtcRoadCameraPlayer(context) }
    val state by player.state.collectAsState()
    var foreground by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    // 车道数推理：模型常驻内存，推理在独立线程；结果显示在顶部标题栏
    val inference = remember { LaneInference(context) }
    var modeIndex by remember { mutableIntStateOf(0) }
    val modes = LaneModelMode.entries

    DisposableEffect(player) {
        onDispose { player.close() }
    }
    DisposableEffect(inference) {
        inference.start()
        onDispose {
            player.frameTap = null
            inference.close()
        }
    }
    val laneRoadEnabled = laneFusion.state != com.jixiexiaoge.drivingassist.data.LaneFusionState.RoadTypeBlocked
    DisposableEffect(player, inference, host, amap?.routeGeneration, output, foreground, state.phase, expanded,
        hideInlineMedia,
        laneRoadEnabled) {
        val active = foreground && videoEnabled && state.phase == CameraPhase.LIVE && laneRoadEnabled
        val sessionId = if (active && !host.isNullOrBlank() && output != null) {
            inference.beginSession(host, amap?.routeGeneration ?: -1)
        } else null
        player.frameTap = sessionId?.let { id -> RoadFrameTap { inference.onRoadFrame(it, id) } }
        onDispose {
            player.frameTap = null
            sessionId?.let { inference.endSession(it) }
        }
    }
    LaunchedEffect(inference, modeIndex) {
        inference.setMode(modes[modeIndex])
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner, host, output, player, expanded, hideInlineMedia) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val target = output
            if (videoEnabled && !host.isNullOrBlank() && target != null) player.play(host, target)
            else awaitCancellation()
        }
    }

    val live = foreground && videoEnabled && state.phase == CameraPhase.LIVE
    val message = cameraMessage(host, foreground, state.phase)
    val onRetry: () -> Unit = { retry++ }

    // 画面组件：卡片里是「右下角画中画」，无看板数据时退回独立一块。
    // 车道识别 chip 叠在画面里（居中靠下），只在真的出画面时显示 ——
    // 否则它会压住「需开启视频流功能」这类提示文字。
    @Composable
    fun CameraSlot(slotModifier: Modifier) {
        Box(slotModifier) {
            CameraVideo(
                state = state,
                live = live,
                message = message,
                host = host,
                deviceIp = deviceIp,
                connection = connection,
                onRetry = onRetry,
                onConnectionClick = onConnectionClick,
                player = player,
                onRenderer = { output = it },
                modifier = Modifier.fillMaxSize(),
                visible = !expanded,
            )
            if (live) {
                ArLaneChip(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
                    compact = true,
                    onVideo = true,
                    fusion = laneFusion,
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("realtime-page").onGloballyPositioned {
        pageOrigin = it.positionInRoot()
    }) {
        val stacked = maxWidth / density.fontScale.coerceAtLeast(1f) < 600.dp
        val pageHeight = maxHeight
        val contentWidth = (maxWidth - 12.dp).coerceAtLeast(0.dp)
        LaunchedEffect(stacked) {
            val offset = if (stacked) compactOffset else detailsOffset
            val scroll = if (stacked) compactScroll else detailsScroll
            if (offset > 0) {
                snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
                scroll.scrollTo(offset)
            }
            snapshotFlow { scroll.value }.collect {
                if (stacked) compactOffset = it else detailsOffset = it
            }
        }
        Column(
            Modifier.fillMaxSize().padding(6.dp).alpha(if (expanded) 0f else 1f)
                .then(if (expanded) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                } else Modifier),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (stacked && !hideInlineMedia) {
                CameraSlot(Modifier.fillMaxWidth().height(
                    mediaPaneHeight(contentWidth, state.aspect(), pageHeight * 0.28f),
                ).testTag("compact-video-pane"))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { scrollBounds = it.boundsInRoot() }
                    .then(if (stacked) Modifier.verticalScroll(compactScroll)
                        .testTag("realtime-compact-scroll") else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // SurfaceView 始终在滚动区外；窄屏地图和驾驶卡随下方内容滚动。
            // 地图格的出现条件：有高德显示模型，或**投屏服务已经在跑**（8.0.26 修）。
            // 原来只看 amap，投屏模式单独开启时整行不出现、画面独占一行，用户看不到地图。
            if (hideInlineMedia) {
                // Landscape phones reserve their short viewport for the driving card.
            } else if (mapAvailable && stacked) {
                Box(Modifier.fillMaxWidth()
                    .height(mediaPaneHeight(contentWidth, projection.aspect, 320.dp))
                    .testTag("compact-map-anchor")
                    .onGloballyPositioned {
                        mapBounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    })
            } else if (mapAvailable) {
                NaviMapRow(
                    mapAspect = projection.aspect,
                    videoAspect = state.aspect(),
                    onMapBounds = { mapBounds = it },
                    video = { CameraSlot(Modifier.fillMaxSize()) },
                )
            } else if (snapshot != null && !stacked) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val videoHeight = minOf(maxWidth / state.aspect(), NAVI_MAP_MAX_HEIGHT)
                    CameraSlot(Modifier.fillMaxWidth().height(videoHeight))
                }
            }
            if (snapshot != null) {
                DriveViewCard(
                    snapshot = snapshot,
                    modifier = if (hideInlineMedia) Modifier.widthIn(
                        max = (pageHeight - 64.dp).coerceAtLeast(100.dp) * DRIVE_VIEW_ASPECT,
                    ) else Modifier,
                    // 7.9.52：画中画从卡片右下角剥离，改成路口图那一格（见 NaviMapRow）。
                    // 卡片右下角因此空出来，卡内布局按原版留白。
                    drivingMode = drivingMode,
                    laneGeometry = laneGeometry,
                    // 车道方向卡：高德原生素材，画在卡片顶部中间（7.9.48 从卡下方的二次开发条挪上来）
                    laneGuide = junction,
                    // 方向盘 = 控制页入口（卡内分区只剩「导航」）
                    onEnterControls = onEnterControls,
                    // 原 #15 状态行的信息，7.9.53 起画在卡片右下角
                    cameraBadge = CameraBadgeInfo(
                        deviceIp = deviceIp,
                        connection = connection,
                        live = live,
                        detail = if (live) "${state.width}×${state.height} · ${state.fps} FPS" else null,
                    ),
                    onConnectionClick = onConnectionClick,
                    // 7.9.59：高德红绿灯胶囊画进驾驶卡右侧（文字行已删除）
                    amapSignal = amap?.trafficSignal,
                    jetlink = jetlink,
                    onGearClick = if (mapAvailable) ({ mapFullscreen = true }) else null,
                )
            } else {
                // 没有看板数据时驾驶卡不渲染（分区按钮本来画在卡片左上角），
                // 这里退回页面左上角，避免被困在本页出不去
                LocalSectionTabs.current?.let { tabs ->
                    Row(Modifier.fillMaxWidth()) { tabs.OnPage() }
                }
                if (!mapAvailable && !stacked && !hideInlineMedia) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val videoHeight = minOf(maxWidth / state.aspect(), maxHeight * 0.5f)
                        CameraSlot(Modifier.fillMaxWidth().height(videoHeight))
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().then(
                    if (stacked) Modifier else Modifier.weight(1f).verticalScroll(detailsScroll),
                ).testTag("realtime-details"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (amap != null) {
                    // 高德巡航灯组（红绿灯 7.9.59 起改成胶囊画进驾驶卡，不再走这里）
                    NaviCruiseLampsRow(amap)
                    // 高德巡航道路（仅巡航态且数据新鲜时出现）
                    AmapCruiseRoadCard(amap)
                    // 高德 / 车端导航对照（含 7714 下发状态）：没有车端快照时不画，
                    // 否则整张卡都是「—」，反而挡住下面的内容
                    if (vehicle != null) {
                        NavigationComparisonCard(amap, vehicle, delivery, routeReady)
                    }
                    // 原始广播值默认收起，要看再展开
                    if (amap.state == AmapDisplayState.Fresh) {
                        CollapsibleCard(
                            title = localized("高德详细数据", "AMap details"),
                            subtitle = localized(
                                "当前道路 · 限速 · 出口 · 测速 · 区间 · 路况 · 设施",
                                "Road · limit · exit · speed events · sections · traffic · facilities",
                            ),
                            tag = "amap-details",
                        ) {
                            AmapNavigationCard(amap, routeReady)
                        }
                    }
                    if (snapshot != null) {
                        CollapsibleCard(
                            title = localized("7000 车端回传核对", "Vehicle telemetry check"),
                            subtitle = localized(
                                "速度处理 · 导航结果 · 测速区间 · 导航灯组",
                                "Speed · navigation result · speed events · lamp group",
                            ),
                            tag = "navi-check",
                        ) {
                            NavigationAndLimitsDetails(snapshot)
                        }
                    }
                }
                if (snapshot != null) {
                    DashboardDetailBlocks(snapshot)
                }
                ModelModeRow(
                    modelName = modeLabel(modes[modeIndex]),
                    onCycleModel = { modeIndex = (modeIndex + 1) % modes.size },
                )
                // 关于 / 版本：原导航页点应用名进入，导航页删除后放到本页末尾
                TextButton(
                    onClick = onAboutClick,
                    modifier = Modifier.fillMaxWidth().testTag("about-entry"),
                ) {
                    Text(
                        localized("关于 CP搭子 · 版本与致谢", "About · version and credits"),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            }
        }
        if (mapAvailable) {
            // A stable parent avoids AndroidView/movable-content reparenting across subcompositions.
            val viewport = if (stacked) scrollBounds else mapBounds
            val mapPlacement = if (expanded) {
                Modifier.fillMaxSize().background(Surface900).testTag("map-fullscreen")
            } else if (hideInlineMedia) {
                Modifier.size(0.dp).clearAndSetSemantics {}
            } else {
                Modifier.absoluteOffset {
                    IntOffset(
                        (viewport.left - pageOrigin.x).roundToInt(),
                        (viewport.top - pageOrigin.y).roundToInt(),
                    )
                }.size(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })
            }
            Box(mapPlacement.clipToBounds()) {
                val inlineMap = if (expanded) Modifier.fillMaxSize() else Modifier.absoluteOffset {
                    IntOffset(
                        (mapBounds.left - viewport.left).roundToInt(),
                        (mapBounds.top - viewport.top).roundToInt(),
                    )
                }.size(with(density) { mapBounds.width.toDp() }, with(density) { mapBounds.height.toDp() })
                LocalMapBrowserPanel(
                    // 两个独立入口，任一成立就去连投屏服务（8.0.26）：
                    // ① 高德广播新鲜（导航中）；② 本机投屏服务已经在跑（只开了投屏模式）。
                    // 之前只认 ①，所以「开了投屏但没开高德导航」时永远停在启动提示上。
                    amapActive = amap?.state == AmapDisplayState.Fresh || projectionRunning,
                    onOpenProjection = onOpenProjection,
                    modifier = inlineMap,
                    serverPort = projection.port,
                )
            }
        }
    }
    BackHandler(enabled = expanded) { mapFullscreen = false }
    }
}

/**
 * 内嵌地图浏览器（左） + 5001 实时画面（右）并排一行。
 *
 * 7.9.45：地图从整屏宽度缩到**半屏**，右半屏不再留空。
 * 7.9.56：比例按高德主体区 1.46:1，两格等高（[guidePaneHeight]），不会一高一低留出缺口。
 * 7.9.60：左格整块换成 [LocalMapBrowserPanel]：没广播提示「请启动高德地图车机版」
 * （点跳投屏页），有广播则自动探 127.0.0.1:8081×3 → 8080×3，全失败提示「当前地图投屏功能未开启」。
 * **7.9.64：右格的路口放大图整块下线**（原 [JunctionCrossView] 及路口矢量数据链路已删除），
 * 右格恒为实时画面：以前有路口数据时这里被图占掉、画面整个消失，现在永远只看这一路流。
 * 两格按投屏服务实际尺寸与视频帧比例分配宽度，保持等高、完整显示且不留内部黑边。
 * 高度受屏幕上限约束时整行居中，不拉伸画面或改变共享虚拟屏尺寸。
 */
@Composable
private fun NaviMapRow(
    mapAspect: Float,
    videoAspect: Float,
    onMapBounds: (Rect) -> Unit,
    video: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val availableWidth = maxWidth - gap
        val heightCap = minOf(NAVI_MAP_MAX_HEIGHT, LocalConfiguration.current.screenHeightDp.dp * 0.45f)
        val paneHeight = mediaRowHeight(availableWidth, mapAspect, videoAspect, heightCap)
        val mapWidth = paneHeight * mapAspect
        val videoWidth = paneHeight * videoAspect
        Row(
            Modifier.fillMaxWidth().testTag("navi-map-row"),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        ) {
            Box(Modifier.width(mapWidth).height(paneHeight).onGloballyPositioned {
                onMapBounds(it.boundsInRoot())
            })
            // 右格恒为 5001 的实时画面，不按路口数据切换。
            Box(Modifier.width(videoWidth).height(paneHeight).testTag("navi-video-pane")) {
                video()
                RoundedCornerMask(MEDIA_CORNER_RADIUS)
            }
        }
    }
}

/**
 * 圆角遮罩：用页面底色把四角的直角盖住（[color] 之外不画）。
 *
 * 只给 SurfaceView 这类**不吃父级裁剪**的画面用；普通 View / WebView 直接 `clip` 就行。
 */
@Composable
private fun RoundedCornerMask(radius: Dp, color: Color = Surface900) {
    Canvas(Modifier.fillMaxSize()) {
        val r = radius.toPx()
        val outer = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
        val inner = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r)) }
        drawPath(Path.combine(PathOperation.Difference, outer, inner), color)
    }
}

private fun cameraMessage(host: String?, foreground: Boolean, phase: CameraPhase): String = when {
    host.isNullOrBlank() -> localized("请先连接 comma 设备", "Connect a comma device first")
    !foreground -> localized("画面已暂停", "Video paused")
    else -> when (phase) {
        CameraPhase.LIVE -> localized("实时画面", "Live video")
        CameraPhase.CONNECTING -> localized("正在协商 WebRTC…", "Negotiating WebRTC…")
        CameraPhase.WAITING -> localized("等待 WebRTC 首帧…", "Waiting for the first WebRTC frame…")
        CameraPhase.STALE -> localized("画面暂时冻结，等待恢复", "Video temporarily frozen, waiting for recovery")
        CameraPhase.BUSY -> localized(
            "道路摄像头正被其他客户端使用",
            "Road camera is active on another client",
        )
        // 5001 不通：设备在、但车机没开视频流，直接告诉用户去开
        CameraPhase.UNREACHABLE -> localized("需开启视频流功能", "Enable the video stream")
        CameraPhase.RETRYING -> localized("WebRTC 中断，自动重连中", "WebRTC interrupted, reconnecting")
        CameraPhase.STOPPED -> localized("等待画面输出…", "Waiting for video output…")
    }
}

/** 5001 不可达时的补充说明（只在这种相位下出现，避免和普通断线混为一谈） */
private fun unreachableHint(host: String?, phase: CameraPhase): String? =
    if (!host.isNullOrBlank() && phase == CameraPhase.UNREACHABLE) {
        localized(
            "车机 5001 未响应，请在车机上开启视频流",
            "Device port 5001 is not responding; enable the video stream on the device",
        )
    } else {
        null
    }

@Composable
private fun CameraVideo(
    state: CameraPlayback,
    live: Boolean,
    message: String,
    host: String?,
    deviceIp: String?,
    connection: CommaConnectionDisplay,
    onRetry: () -> Unit,
    onConnectionClick: () -> Unit,
    player: WebRtcRoadCameraPlayer,
    onRenderer: (SurfaceViewRenderer?) -> Unit,
    modifier: Modifier,
    visible: Boolean = true,
) {
    BoxWithConstraints(
        modifier.background(Color.Black).testTag("ar-video"),
        contentAlignment = Alignment.Center,
    ) {
        // 画中画里只有一百多 dp，按宽度切换紧凑样式，否则文字和按钮会糊成一团
        val compact = maxWidth < 260.dp
        AndroidView(
            // 保持 SurfaceView（这个 webrtc 包里没有 TextureViewRenderer）：
            // SurfaceView 是独立图层、不吃裁剪，所以**用到它的那一行必须待在固定区**，
            // 不能放进 verticalScroll —— 见 RealTimePage 里导航行的位置注释。
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(player.eglContext, null)
                    // 7.9.64：必须是 FIT（等比缩放 + 留黑边），不能用 FILL：
                    // FILL 虽然也是等比，但会把超出容器的一侧**裁掉**，路口画面两侧容易被切。
                    // 配合外层按 5001 真实帧尺寸算出的容器比例，黑边几乎不可见。
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                    onRenderer(this)
                }
            },
            update = { it.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE },
            onRelease = {
                onRenderer(null)
                it.release()
            },
            modifier = Modifier.fillMaxSize().testTag("ar-video-surface"),
        )
        if (!live) {
            Column(
                Modifier.fillMaxSize().background(Color.Black)
                    .padding(if (compact) 5.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 设备行：连接状态 + IP。点一下打开 7706 调试（原侧栏那颗「搜索」按钮的入口）
                CameraConnectionLine(
                    connection = connection,
                    deviceIp = deviceIp,
                    compact = compact,
                    onClick = onConnectionClick,
                )
                Text(
                    message,
                    color = if (state.phase == CameraPhase.UNREACHABLE) WarnAmber else Color.White,
                    fontSize = if (compact) 10.sp else 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                unreachableHint(host, state.phase)?.let { hint ->
                    if (!compact) {
                        Text(hint, color = WarnAmber, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                if (state.detail.isNotEmpty() && !compact) {
                    Text(
                        state.detail, color = TextSecondary, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                // 画中画太小，按钮挪到下面的状态行；独立画面时留在这里
                if (!compact && !host.isNullOrBlank()) {
                    Button(onClick = onRetry) {
                        Text(localized("重新连接", "Reconnect"), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * 「● 已连接 · 192.168.1.23」一行：状态色点 + 连接状态 + IP。
 * 未发现设备时 IP 位置显示占位文案，并且没有可点的动作。
 */
@Composable
private fun CameraConnectionLine(
    connection: CommaConnectionDisplay,
    deviceIp: String?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val color = connection.color()
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .clickable(enabled = !deviceIp.isNullOrBlank(), onClick = onClick)
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 2.dp)
            .testTag("camera-connection"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(if (compact) 4.dp else 6.dp).clip(CircleShape).background(color))
        Text(
            deviceIp ?: localized("未发现设备", "No device"),
            color = color,
            fontSize = if (compact) 9.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("camera-connection-ip"),
        )
        Text(
            connection.localizedLabel(),
            color = TextSecondary,
            fontSize = if (compact) 9.sp else 12.sp,
            maxLines = 1,
        )
    }
}

private fun modeLabel(mode: LaneModelMode): String = when (mode) {
    LaneModelMode.AUTO -> localized("自动", "Auto")
    LaneModelMode.DAY -> localized("白天", "Day")
    LaneModelMode.NIGHT -> localized("夜间", "Night")
    LaneModelMode.LITE -> localized("轻量", "Lite")
}

@Composable
private fun ModelModeRow(modelName: String, onCycleModel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("ar-lane-model"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onCycleModel,
            modifier = Modifier.weight(1f).height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(localized("车道模型：$modelName", "Lane model: $modelName"), fontSize = 11.sp)
        }
    }
}
