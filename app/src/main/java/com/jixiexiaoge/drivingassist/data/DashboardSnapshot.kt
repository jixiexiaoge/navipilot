@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.data

import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DashboardMetric(val id: String, val label: String, val value: String, val unit: String = "")
internal data class DashboardPanel(
    val id: String,
    val title: String,
    val source: String,
    val status: String,
    val remainingMs: Long,
    val metrics: List<DashboardMetric>,
    val note: String,
    val tone: String = "blue",
    val receiptKey: String = "",
)
internal data class DashboardSnapshot(
    val panels: List<DashboardPanel>,
    val schemaVersion: Int = 1,
    val road: DashboardRoad = DashboardRoad(),
    val driving: DrivingDisplay = DrivingDisplay(),
)

internal class DashboardSnapshotPublisher(
    scope: CoroutineScope,
    private val capture: () -> DashboardInputs = { DashboardStore.capture() },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val build: suspend (DashboardInputs) -> DashboardSnapshot = {
        android.os.Trace.beginSection("Navipilot.dashboard.snapshot")
        try {
            it.snapshot(System.nanoTime() / 1_000_000)
        } finally {
            android.os.Trace.endSection()
        }
    },
    private val publish: (DashboardSnapshot) -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        var pending = false
        while (true) {
            if (!pending) requests.receive()
            val input = capture()
            val result = withContext(computationDispatcher) { build(input) }
            pending = requests.tryReceive().isSuccess
            if (!pending) publish(result)
        }
    }

    fun refresh() {
        if (worker.isActive) requests.trySend(Unit)
    }
}

internal data class DashboardInputs(
    val telemetry: CompactTelemetry?,
    val navigation: List<Pair<Long, DashboardPanel>>,
    val world: XiaogeBridgeClient.World?,
) {
    fun snapshot(monoMs: Long): DashboardSnapshot {
        val base = buildDashboardSnapshot(telemetry, monoMs)
        val map = navigation.map { (deadline, panel) ->
            val ttl = (deadline - monoMs).coerceAtLeast(0)
            panel.copy(remainingMs = ttl, status = if (ttl > 0) panel.status else "expired",
                metrics = if (ttl > 0 && panel.status == "fresh") panel.metrics
                    else panel.metrics.map { it.copy(value = "未知") })
        }
        val live = world?.takeIf { monoMs - it.receivedAtMs in 0..XiaogeBridgeClient.DATA_TIMEOUT_MS }
        return base.copy(
            panels = base.panels + map,
            road = base.road.copy(
                trafficLightState = live?.trafficLightColor?.takeIf { it in 1..3 } ?: 0,
                stopLineM = live?.stopLineDistM?.takeIf {
                    it in 0.01f..XiaogeBridgeClient.MAX_VALID_DISTANCE_M
                },
            ),
        )
    }
}

/** Immutable inputs only: HTTP threads never read Compose state or retain an Activity. */
internal object DashboardStore {
    @Volatile var telemetry: CompactTelemetry? = null
    @Volatile private var navigation: List<Pair<Long, DashboardPanel>> = emptyList()

    /** 7711 广播来的世界量（Tesla 红绿灯 / 停止线），带本地单调时间戳用于独立过期 */
    @Volatile private var world: XiaogeBridgeClient.World? = null

    fun clear() {
        telemetry = null
        navigation = emptyList()
        world = null
    }

    /**
     * 车端 7711 的 `carState.stopLineDist / trafficLightColor`。
     * 只存原始值，不在写入侧做任何推断；过期判定在 [snapshot] 里按本地单调时钟做。
     */
    fun publishWorld(sample: XiaogeBridgeClient.World?) {
        world = sample
    }

    /** 与车端 1 s 的 `DAS_road` 超时保持一致，再留半秒给 20 Hz 广播抖动 */
    private const val WORLD_TTL_MS = XiaogeBridgeClient.DATA_TIMEOUT_MS

    /**
     * 7714 `traffic_signal` 合成用的最近一帧世界量。
     *
     * 时钟基准是单调时钟（`System.nanoTime`），与 7711 客户端写帧时用的是同一个来源，
     * 因此这里不能用墙钟比较。
     */
    fun latestWorld(monoMs: Long = System.nanoTime() / 1_000_000): XiaogeBridgeClient.World? =
        world?.takeIf { monoMs - it.receivedAtMs in 0..WORLD_TTL_MS }

    /** 世界量帧龄（单调毫秒），给诊断面板与 7714 合成用 */
    fun worldAgeMs(sample: XiaogeBridgeClient.World,
                   monoMs: Long = System.nanoTime() / 1_000_000): Long = monoMs - sample.receivedAtMs

    fun capture(): DashboardInputs = DashboardInputs(telemetry, navigation, world)

    fun snapshot(monoMs: Long): DashboardSnapshot = capture().snapshot(monoMs)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun publishNavigation(
        map: AmapNavigationDisplay,
        ready: Boolean,
        clocks: Map<String, Long>,
        wallMs: Long,
        monoMs: Long,
    ) {
        fun panel(id: String, title: String, clock: String, metrics: List<DashboardMetric>, note: String) =
            (clocks[clock]?.let {
                if (wallMs < it) 0 else (10_000 - (wallMs - it)).coerceIn(0, 10_000)
            } ?: 0).let { ttl ->
                // Stop publication when the existing foreground sampler is no longer running.
                monoMs + minOf(ttl, 750) to DashboardPanel(id, title, "AMAP/$clock",
                    if (ttl > 0 && map.state == AmapDisplayState.Fresh) "fresh" else "missing",
                    0, metrics, note, "cyan", clocks[clock]?.toString().orEmpty())
            }
        val eventClocks = listOf("sdi", "sdi_secondary").mapNotNull { clocks[it] }
            .filter { wallMs - it in 0..10_000 }
        val eventClock = eventClocks.minOrNull()
        navigation = if (map.state != AmapDisplayState.Fresh) emptyList() else buildList {
            add(panel("map-road", "高德道路", "dashboard_road_limit", listOf(
                DashboardMetric("road-limit", "原始道路限速", map.roadLimitKph?.toString() ?: "未知", "km/h"),
            ), "原始道路限速 · 不使用车端处理速度回填"))
            add(panel("map-trip", "高德行程", "dashboard_guide", listOf(
                DashboardMetric("map-destination", "目的地", map.destination ?: "未知"),
                DashboardMetric("map-remaining", "剩余距离", map.remainingM?.toString() ?: "未知", "m"),
                DashboardMetric("map-remaining-time", "预计剩余", map.remainingSec?.toString() ?: "未知", "s"),
            ), "高德广播行程 · 本机展示，不是车辆执行结果"))
            val events = buildList {
                map.events.forEachIndexed { index, event ->
                    add(DashboardMetric("map-event-$index-type", "事件 ${index + 1} 原码", event.type.toString()))
                    add(DashboardMetric("map-event-$index-limit", "事件 ${index + 1} 限速",
                        event.limitKph?.toString() ?: "未知", "km/h"))
                    add(DashboardMetric("map-event-$index-distance", "事件 ${index + 1} 距离",
                        event.distanceM.toString(), "m"))
                }
            }.ifEmpty { listOf(DashboardMetric("map-event-empty", "测速事件", "当前无有效事件")) }
            val eventPanel = DashboardPanel("map-events", "高德测速事件", "AMAP/sdi+sdi_secondary",
                if (eventClock != null) "fresh" else "missing", 0, events,
                "测速提醒不是下一道路限速 · 合并展示使用最旧包含来源时效", "cyan", eventClock?.toString().orEmpty())
            add(monoMs + minOf(eventClock?.let { (10_000 - (wallMs - it)).coerceAtLeast(0) } ?: 0, 750) to eventPanel)
            add(panel("map-section", "高德区间", "dashboard_section", listOf(
                DashboardMetric("map-section-limit", "区间限速",
                    (map.sectionDetails?.limitKph ?: map.section?.limitKph)?.toString() ?: "未知", "km/h"),
                DashboardMetric("map-section-average", "区间平均速度",
                    (map.sectionDetails?.averageKph ?: map.section?.averageKph)?.let {
                        String.format(Locale.ROOT, "%.1f", it.toDouble())
                    } ?: "未知", "km/h"),
                DashboardMetric("map-section-remaining", "区间剩余距离",
                    (map.sectionDetails?.remainingM ?: map.section?.remainingM)?.toString() ?: "未知", "m"),
                DashboardMetric("map-section-start-text", "起点距离原文", map.sectionDetails?.startDistanceText ?: "未知"),
                DashboardMetric("map-section-end-text", "终点距离原文", map.sectionDetails?.endDistanceText ?: "未知"),
            ), "区间均速只与区间限速对照 · 原始文本不转换成米值"))
            if (ready) {
                add(panel("map-current", "当前导航", "guidance_current", listOf(
                    DashboardMetric("current-action", "当前动作", map.current?.label ?: "未知"),
                    DashboardMetric("current-road", "道路", map.current?.roadName ?: map.roadName ?: "未知"),
                    DashboardMetric("current-distance", "动作距离", map.current?.distanceM?.toString() ?: "未知", "m"),
                    DashboardMetric("current-time", "当前航段剩余", map.current?.remainingSec?.toString() ?: "未知", "s"),
                ), "地图引导 · 非车端接收或执行确认"))
                add(panel("map-next", "随后航段", "guidance_next", listOf(
                    DashboardMetric("next-action", "随后动作", map.next?.label ?: "未知"),
                    DashboardMetric("next-distance", "随后航段距离", map.next?.distanceM?.toString() ?: "未知", "m"),
                    DashboardMetric("next-time", "随后航段用时", map.next?.remainingSec?.toString() ?: "未知", "s"),
                ), "随后航段距离与用时不是从当前位置累计值"))
                val ordered = map.junction?.leftToRightKnown == true
                add(panel("map-lanes", "高德路口标记", "lane_current", listOf(
                    DashboardMetric("map-lane-count", "广播条目数", map.lanes?.count?.toString() ?: "未知"),
                    DashboardMetric("map-lane-order", "左右顺序", if (ordered) "已报告" else "未确认"),
                    DashboardMetric("map-lane-flags", "从左到右推荐标记",
                        if (ordered) map.lanes?.recommendedIndices?.joinToString("、") { "${it + 1}" }
                            ?.ifBlank { "无推荐标记" } ?: "未知" else "顺序未确认，不编号"),
                ), "地图推荐不是本车车道定位、合法性或变道许可"))
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun buildDashboardSnapshot(
    input: CompactTelemetry?,
    monoMs: Long,
): DashboardSnapshot {
    val telemetry = input?.copy(observedAtMs = monoMs, services = input.services.filterValues {
        monoMs >= it.receivedAtMs
    })
    val display = telemetry.toDisplaySnapshot()
    val observation = telemetry.toVehicleObservation()
    fun values(service: String) = telemetry?.fresh(service)?.frame?.values
    fun number(value: Any?, min: Float = -Float.MAX_VALUE, max: Float = Float.MAX_VALUE): Float? =
        (value as? Number)?.toFloat()?.takeIf { it.isFinite() && it in min..max }
    fun decimal(value: Number?): String = value?.let { String.format(Locale.ROOT, "%.1f", it.toDouble()) } ?: "未知"
    fun bool(value: Any?): String = when (value) { true -> "是"; false -> "否"; else -> "未知" }
    fun metric(id: String, label: String, value: String?, unit: String = "") =
        DashboardMetric(id, label, value?.takeIf { it.isNotBlank() } ?: "未知", unit)
    fun panel(id: String, title: String, service: String, metrics: List<DashboardMetric>, note: String,
              tone: String = "blue"): DashboardPanel {
        val sample = telemetry?.services?.get(service)
        val ttl = sample?.let { (it.expiresAtMs - monoMs).coerceAtLeast(0) } ?: 0
        return DashboardPanel(id, title, "7000/$service",
            if (ttl > 0) "fresh" else if (sample == null) "missing" else "expired", ttl, metrics, note, tone,
            sample?.receivedAtMs?.toString().orEmpty())
    }
    val car = values("carState")
    val control = values("carControl")
    val controls = values("controlsState")
    val lane = values("lateralPlan")
    val result = values("carrotMan")
    val normalizedEvents = display.events.takeIf {
        telemetry?.carrotNavi?.let { it.connected == true && it.schemaVersion == 1 } == true
    }.orEmpty()
    val gear = mapOf("park" to "P", "drive" to "D", "neutral" to "N", "reverse" to "R",
        "sport" to "S", "low" to "L", "brake" to "B", "eco" to "E", "manumatic" to "M")
    fun selected(name: String) = values("radarState")?.objectValue(name)?.get("status") as? Boolean
    fun leadState(name: String) = when (selected(name)) {
        true -> "已选择（非车道身份确认）"
        false -> "未选择（非无车确认）"
        null -> "未知"
    }
    val panels = buildList {
        add(panel("vehicle", "车辆实况", "carState", listOf(
            metric("speed", "实际车速", decimal(number(car?.get("vEgo"), 0f, 100f)?.times(3.6f)), "km/h"),
            metric("cruise", "巡航设定", decimal(number(car?.get("vCruiseCluster"), 0.1f, 249f)), "km/h"),
            metric("gear", "挡位", gear[car?.get("gearShifter")]),
            metric("accel", "实际加速度", decimal(observation.vehicleAccelerationMps2), "m/s²"),
            metric("blinker-left", "左转向灯", bool(car?.get("leftBlinker"))),
            metric("blinker-right", "右转向灯", bool(car?.get("rightBlinker"))),
            metric("hold", "制动保持", bool(observation.brakeHoldActive)),
            metric("soft-hold", "Soft hold", bool(observation.softHoldActive)),
            metric("steer-angle", "实际转向角", decimal(number(car?.get("steeringAngleDeg"))), "°"),
            metric("brake-lights", "制动灯", bool(car?.get("brakeLights"))),
            metric("cluster-speed", "仪表车速", decimal(number(car?.get("vEgoCluster"), 0f, 100f)?.times(3.6f)), "km/h"),
            metric("gear-step", "挡位级别原值", (car?.get("gearStep") as? Number)?.toInt()?.toString()),
            metric("carrot-cruise", "Carrot巡航原码", (car?.get("carrotCruise") as? Number)?.toInt()?.toString()),
        ), "实际运动与保持状态 · 不推断停车原因", "cyan"))
        add(panel("alert", "车端告警", "selfdriveState", listOf(
            metric("alert-title", "告警标题",
                display.alert?.first ?: if (values("selfdriveState") != null) "车端未提供告警" else null),
            metric("alert-detail", "告警详情", display.alert?.second),
            metric("assistance-enabled", "车端辅助启用", bool(values("selfdriveState")?.get("enabled"))),
            metric("driver-personality", "跟车风格原值", values("selfdriveState")?.get("personality") as? String),
        ), "未提供告警不等于安全确认", display.alert?.status ?: "blue"))
        add(panel("control", "车端控制状态", "carControl", listOf(
            metric("lat-active", "横向激活", bool(control?.get("latActive"))),
            metric("long-active", "纵向激活", bool(control?.get("longActive"))),
            metric("requested-accel", "请求加速度", decimal(display.actuation?.accelerationMps2), "m/s²"),
            metric("requested-angle", "请求转向角", decimal(display.actuation?.steeringAngleDeg), "°"),
            metric("requested-curvature", "请求曲率",
                number(control?.objectValue("actuators")?.get("curvature"))?.toString(), "1/m"),
        ), "车端报告与请求 · 非实际执行，不授予 App 控制权限"))
        add(panel("lateral", "横向反馈", "controlsState", listOf(
            metric("active-lines", "车道线采用反馈", bool(controls?.get("activeLaneLine"))),
            metric("steer-output", "横向输出原值", decimal(number(controls?.get("lateralOutput")))),
            metric("curvature", "实际曲率", number(controls?.get("curvature"))?.toString(), "1/m"),
            metric("desired-curvature", "目标曲率", number(controls?.get("desiredCurvature"))?.toString(), "1/m"),
            metric("lateral-accel", "实际横向加速度", decimal(number(controls?.get("actualLateralAccel"))), "m/s²"),
            metric("desired-lateral-accel", "目标横向加速度",
                decimal(number(controls?.get("desiredLateralAccel"))), "m/s²"),
        ), "controlsState 独立时效 · 不等于方向盘实际执行"))
        add(panel("lane", "变道与车道线规划", "lateralPlan", listOf(
            metric("lane-phase", "变道阶段", when (lane?.get("laneChangeState")) {
                "off" -> "未变道"; "preLaneChange" -> "准备"; "laneChangeStarting" -> "开始"
                "laneChangeFinishing" -> "收尾（非完成确认）"; else -> null
            }),
            metric("lane-direction", "方向", when (lane?.get("laneChangeDirection")) {
                "none" -> "无"; "left" -> "左"; "right" -> "右"; else -> null
            }),
            metric("planned-lines", "规划采用车道线", bool(lane?.get("useLaneLines"))),
            metric("lateral-debug", "横向规划原文", lane?.get("latDebugText") as? String),
        ), "规划状态 · 不是变道许可"))
        add(panel("plan", "纵向规划", "longitudinalPlan", listOf(
            metric("plan-speed", "规划首点速度", decimal(observation.plannedSpeedKph), "km/h"),
            metric("plan-accel", "规划首点加速度", decimal(observation.plannedAccelerationMps2), "m/s²"),
            metric("follow-time", "目标跟车时距", decimal(display.following?.timeSec), "s"),
            metric("follow-distance", "目标跟车间距", decimal(display.following?.distanceM), "m"),
            metric("vehicle-mode", "车端驾驶风格", when (display.behavior?.style) {
                1 -> "节能"; 2 -> "安全"; 3 -> "普通"; 4 -> "高速"; else -> null
            }),
            metric("traffic-state", "规划交通状态原码", display.behavior?.traffic?.toString()),
            metric("plan-stage", "纵向规划阶段原码", display.behavior?.stage?.toString()),
            metric("plan-target", "规划巡航目标", decimal(display.behavior?.cruiseTargetKph), "km/h"),
            metric("plan-jerk", "规划首点加加速度", decimal(display.behavior?.jerk), "m/s³"),
            metric("plan-source", "纵向规划来源", display.following?.source),
        ), "规划不是实际运动 · 车端风格不是 App 路线辅助模式"))
        add(panel("radar", "目标观察", "radarState", listOf(
            metric("lead-status", "前车选择", leadState("leadOne")),
            metric("lead-distance", "前车距离", decimal(display.lead?.distanceM), "m"),
            metric("lead-relative", "前车相对速度", decimal(display.lead?.relativeKph), "km/h"),
            metric("left-status", "左侧前方选择", leadState("leadLeft")),
            metric("left-distance", "左侧前方距离", decimal(display.leftLead?.distanceM), "m"),
            metric("left-relative", "左侧前方相对速度", decimal(display.leftLead?.relativeKph), "km/h"),
            metric("right-status", "右侧前方选择", leadState("leadRight")),
            metric("right-distance", "右侧前方距离", decimal(display.rightLead?.distanceM), "m"),
            metric("right-relative", "右侧前方相对速度", decimal(display.rightLead?.relativeKph), "km/h"),
            metric("second-status", "第二前车选择", leadState("leadTwo")),
            metric("second-distance", "第二前车距离", decimal(display.secondLead?.distanceM), "m"),
            metric("second-relative", "第二前车相对速度", decimal(display.secondLead?.relativeKph), "km/h"),
        ), "负相对速度表示接近 · 选中目标不证明位于相邻车道"))
        add(panel("sides", "车辆侧向观测", "carState", listOf(
            metric("blind-left", "左盲区", when (display.leftBlindspot) {
                true -> "触发"; false -> "未触发（非安全确认）"; null -> null
            }),
            metric("blind-right", "右盲区", when (display.rightBlindspot) {
                true -> "触发"; false -> "未触发（非安全确认）"; null -> null
            }),
            metric("line-left", "左车道线原码",
                (car?.get("leftLaneLine") as? Number)?.toInt()?.takeIf { it >= 0 }?.toString()),
            metric("line-right", "右车道线原码",
                (car?.get("rightLaneLine") as? Number)?.toInt()?.takeIf { it >= 0 }?.toString()),
        ), "原始观测 · 不推断车道身份、合法性或可通行"))
        add(panel("model", "模型质量与边界", "modelV2", listOf(1, 0, 2, 3).mapIndexed { index, probability ->
            metric("prob-$probability", listOf("左内边界", "左外侧", "右内边界", "右外侧")[index],
                decimal(display.quality?.lineProbabilities?.getOrNull(probability)?.times(100)), "%")
        } + listOf(
            metric("model-age", "模型帧龄", display.quality?.age?.toString()),
            metric("model-drop", "模型丢帧比例", decimal(display.quality?.dropPercent), "%"),
            metric("model-execution", "模型推理耗时", decimal(display.quality?.executionMs), "ms"),
            metric("edge-left-std", "左路缘标准差", decimal(display.quality?.edgeStds?.getOrNull(0)), "m"),
            metric("edge-right-std", "右路缘标准差", decimal(display.quality?.edgeStds?.getOrNull(1)), "m"),
        ), "模型概率不是线型、定位或变道授权 · 推理耗时不是传输延迟"))
        add(panel("result", "车端速度处理", "carrotMan", listOf(
            metric("desired-speed", "车端建议速度", display.desiredSpeedKph?.toString(), "km/h"),
            metric("adopted-limit", "车端采用限速", display.adoptedLimitKph?.toString(), "km/h"),
            metric("speed-source", "速度约束来源原码", display.desiredSource),
            metric("vehicle-navi-speed", "车辆自带导航限速",
                display.vehicleNavigation?.speedKph?.toString(), "km/h"),
            metric("vehicle-navi-active", "车辆自带导航激活", bool(result?.get("vehicleNaviActive"))),
            metric("vehicle-navi-available", "车辆自带导航可用", bool(display.vehicleNavigation?.available)),
            metric("vehicle-navi-section", "车辆自带区间状态", bool(display.vehicleNavigation?.sectionActive)),
        ) + buildList {
            if (telemetry?.carrotNavi?.let { it.connected == true && it.schemaVersion == 1 } != true) {
                display.events.forEachIndexed { index, event ->
                    add(metric("legacy-event-$index-type", "旧版测速事件 ${index + 1} 原码", event.type.toString()))
                    add(metric("legacy-event-$index-limit", "旧版事件限速", event.limitKph?.toString(), "km/h"))
                    add(metric("legacy-event-$index-distance", "旧版事件距离", event.distanceM.toString(), "m"))
                    event.description?.let { add(metric("legacy-event-$index-text", "旧版事件原文", it)) }
                }
            }
        }, "非高德原始道路限速 · 非导航接收或执行 ACK", "orange"))
        add(panel("navigation", "7000 导航结果", "carrotNavi", listOf(
            metric("navi-status", "接收状态", when (display.navigation) {
                NavigationReception.Guiding -> "引导中"; NavigationReception.OffRoute -> "偏航"
                NavigationReception.Disconnected -> "未连接"; NavigationReception.Idle -> "未引导"
                NavigationReception.Unsupported -> "不支持的版本"; else -> null
            }),
            metric("navi-current", "车端当前引导", display.currentGuidance?.text),
            metric("navi-current-road", "车端当前道路", display.currentGuidance?.roadName),
            metric("navi-current-distance", "车端当前动作距离", display.currentGuidance?.distanceM?.toString(), "m"),
            metric("navi-next", "车端随后引导", display.nextGuidance?.text),
            metric("navi-next-distance", "车端随后航段距离", display.nextGuidance?.distanceM?.toString(), "m"),
            metric("navi-lane-count", "车端路口条目数", display.navigationLanes?.count?.toString()),
            metric("navi-lane-flags", "车端推荐标记",
                display.navigationLanes?.recommendedIndices?.joinToString("、") { "${it + 1}" }
                    ?.ifEmpty { "无推荐标记" }),
            metric("journey-distance", "剩余距离", display.journey?.distanceM?.toString(), "m"),
            metric("journey-time", "剩余时间", display.journey?.timeSec?.toString(), "s"),
            metric("journey-progress", "行程进度", decimal(display.journey?.progress?.times(100)), "%"),
        ), "车端归一化接收结果 · 不是采用或执行确认"))
        add(panel("navi-events", "7000 测速与区间", "carrotNavi", buildList {
            normalizedEvents.forEachIndexed { index, event ->
                add(metric("event-$index-type", "测速事件 ${index + 1} 原码", event.type.toString()))
                add(metric("event-$index-limit", "事件 ${index + 1} 限速", event.limitKph?.toString(), "km/h"))
                add(metric("event-$index-distance", "事件 ${index + 1} 距离", event.distanceM.toString(), "m"))
            }
            add(metric("section-limit", "有效区间限速", display.section?.limitKph?.toString(), "km/h"))
            add(metric("section-average", "区间平均速度", decimal(display.section?.averageKph), "km/h"))
            add(metric("section-distance", "区间剩余距离", decimal(display.section?.remainingM), "m"))
            add(metric("section-progress", "区间进度", decimal(display.section?.progress?.times(100)), "%"))
        }, "新鲜、有效、未偏航的车端导航事件 · 不混用道路限速"))
        add(panel("navi-signal", "7000 导航灯组", "carrotNavi", buildList {
            add(metric("navi-signal-distance", "导航灯组距离", display.navigationSignal?.distanceM?.toString(), "m"))
            display.navigationSignal?.lamps?.forEach { lamp ->
                val name = mapOf("red" to "红灯", "left" to "左转", "green" to "直行", "right" to "右转",
                    "uturn" to "掉头")[lamp.direction] ?: lamp.direction
                add(metric("lamp-${lamp.direction}", name,
                    "${if (lamp.on) "亮" else "灭"} · ${lamp.remainingSec?.let { "$it s" } ?: "倒计时未知"}"))
            }
        }, "导航灯组不是 Tesla CAN 灯色或规划交通状态 · 不本机合成倒计时"))
    }
    return DashboardSnapshot(panels, road = telemetry.dashboardRoad(), driving = telemetry.drivingDisplay(display))
}
