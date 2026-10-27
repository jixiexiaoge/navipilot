# 自动超车「以为在跑其实没跑」修复建议

## 当前实施记录：8.0.24（2026-10-25）

已按核查后的第一阶段修复实现，**不恢复自动超车或条件实验自动执行**。
下文旧研究稿全部为历史资料，其中A1/A2补丁不应执行，本节优先。

| 范围 | 当前实现 |
|---|---|
| 控制入口 | 三档标注“配置/请求暂停”，保留原prefs键及循环顺序；保存无车辆命令 |
| 常驻说明 | 复用`OvertakeStatusCard`显示配置、自动请求暂停、手动入队非执行 |
| 能力原因 | `data/AutomationObservation.kt`纯函数消费现有连接/超时及compact原TTL；未连接、等待必要服务、过期/rollback、观测新鲜但缺授权均不授予控制能力 |
| 条件实验 | 仅配置/自动切换未启用，删除“7705 Active后CEM可工作”卡；原手动实验/Chill独立标明，不改其动作 |
| 执行边界 | 不接回旧`update()`，不加默认允许发送开关，不改7706或参数链、不改WSL |
| 测试 | 增补纯状态边界单测及生产控制页/CEM保存仪器回归，包含无命令/无车辆参数写入与手动请求独立性 |

最终验证版本8.0.25/26102505：11项单测通过（`OvertakeDisplaySnapshotTest` 3、
`AutoOvertakeManagerTest` 3、`NetworkCommandDeliveryTest` 3、`ManualLaneChangePayloadTest` 2），
手机`OvertakeStatusCardTest` 4项通过，覆盖生产九宫格三档配置、原因切换、
手动左右请求独立、CEM配置保存零参数写入及既有大字体/独立TTL展示。
Debug/测试APK构建和lintDebug通过。detekt仍失败于全仓2129项既有发现，
相对2139项旧基线按文件/规则无增加；不宣称全仓静态分析通过。
最终APK为`artifacts\CPdazi-8.0.25-debug.apk`，已安装核验8.0.25/26102505。
收尾复核时源码及手机已被并行操作更新为8.0.26/26102506，未覆盖或降级；
本节测试结论仅对应保留的8.0.25包，不宣称已验证另一版本。
手动请求测试只调用本机记录回调，CEM用模拟参数客户端；未发实车控制，
不能视为车端接收、执行或自动功能已启用。

### 对旧稿的纠正

- 没有生产update调用属实，但`2d1e354`不足以证明迁移误删；当前明确主动挂起。
- `compactVehicleDisplayOnly`直接挡CEM；超车当前无update调用且收到compact即挂起，不是同一调用门。
- `AutoOvertakeManagerTest`已有空数据/不完整授权/发送拒绝覆盖，不是全部完整授权测试。
- compact已有模型frameId、几何和lateralPlan变道状态；它们不是完整控制授权。
- 7711读取超时已经1500ms，不是本功能前置条件，不应补作授权来源。
- 命令互斥拒绝重叠是既有保护，无证据要求改延迟队列；旧变道意图排队有过时风险。
- 旧A1可变`commandsAllowed`只拦最后发送，不能隔离提示音/待定状态/冷却记忆；
  对compact也只会触发首道拒绝门，不是无副作用观察。
- 旧A2不能用共享默认道路类型或高德路口数量证明当前横截面、车道编号或控制条件。

未来自动执行需独立授权契约和安全评审：替换固定2.5m推编号、编号变化当完成、
未检出/缺失目标当安全的旧判断，统一初始/待执行/返回路径的有效性门；
使用带身份/有效期的请求与反馈，不能用显示估计、active或对象非空解锁。

## 历史研究稿（已被上文核查与实施记录覆盖，勿照搬补丁）

- 项目：`C:/AIcode/CPdazi`｜分析日期：2026-10-25｜本文只给方案，**未改任何实现代码**
- 结论先行：**这不是"忘了调用"的简单遗漏，而是"数据授权不够 → 主动挂起 → 但 UI 没说清楚"的三层断裂。**
  直接把 `update()` 接回去是**危险且无效**的：接回去也只会永远返回「暂停自动超车」。

## 一、根因链（四层，逐层有证据）

| 层 | 事实 | 证据 |
|---|---|---|
| ① 调用点断了 | 早期版本 `update()` 在 7711 的 `onVehicleDataUpdated` 回调里调用；改到 7000 compact 通道时没有搬过来 | `git show 2d1e354 -- MainActivityLifecycle.kt`（原 `@@ -892,7 +889` 处）；当前全仓无 `.update(` 调用点 |
| ② 现在只做「挂起」 | compact 数据到达时只调用 `suspendForIncompleteTelemetry()`（清空防抖/待执行/车道记忆），不做评估 | `MainActivityLifecycle.kt:873-882`，`AutoOvertakeManager.kt:1598-1606` |
| ③ 数据闸门必然失败 | `update()` 第一道闸要求 `carState != null && systemState.active == true && controlsState.active == true && modelV2.meta != null`；而 compact 适配器把 `active` **写死 false**、`meta` **写死 null** | 闸门 `AutoOvertakeManager.kt:347-357`；适配器 `CompactTelemetry.kt:238-249` |
| ④ 反馈缺失（"以为在跑"的真正原因） | `OvertakeStatusCard` 零调用点、`overtakeStatus` 字段无任何消费者；控制页「自动超车」按钮只弹一次 Toast，之后按钮常亮「自动超车」 | `OvertakeStatusCard` grep 命中 0；`VehicleDataModels.kt:19` 仅定义；`MainActivityUIComponents.kt:196-210` |

### 附带发现：同一个开关还挡住了第二个功能

`core.compactVehicleDisplayOnly` 初始化为 `true`（`MainActivityCore.kt:395`），全仓只有 `= true`（`MainActivityLifecycle.kt:877`）、**没有任何一处置 false**。它同时把：

- `ConditionalExperimentManager.performModeSwitch(...)` 挡住（`MainActivityLifecycle.kt:575`）→ **条件实验自动切换同样从未生效**；
- 自动超车的入口挡住（`MainActivityLifecycle.kt:875`）。

也就是说，这是一个"永远打不开的总闸"，且 UI 上没有任何"能力未启用"的表示。

## 二、三条路线对比

| 方案 | 做法 | 工作量 | 风险 | 适用 |
|---|---|---|---|---|
| **A. 认知对齐（推荐先做）** | 不开自动执行，但把"为什么没在跑"如实、常驻地告诉用户 | 0.5–1 天 | 极低（不触碰发送路径） | 现在就要消除误解、并为将来启用铺路 |
| **B. 真正启用** | 补齐授权证据 + 把 `LANECHANGE` 从"自动执行"降级为"建议 + 人工确认" | 1–2 周（需车端配合） | 高（安全敏感） | 车端能补字段后再做 |
| **C. 彻底下线** | 删除 `AutoOvertakeManager`(1664 行) 与「自动」档位 | 1 天 | 低（减少维护面） | 近期确定不启用时 |

**建议：A（立刻）+ B（下一阶段）的组合，C 只在明确不做时选。**

## 三、方案 A 的落地步骤（可直接照做）

### A1. 加一个「只读评估」入口（零发送风险）

`update()` 的发送路径只有一个收口 `sendLaneChangeCommand()`（`AutoOvertakeManager.kt:1069`），因此可以复用它全部条件链而**保证不发命令**：

```kotlin
// AutoOvertakeManager.kt
/** 只读评估闸门：只读模式下一律拦下变道命令（唯一发送收口） */
private var commandsAllowed = true

/**
 * 只读评估：走与 update() 完全相同的条件链，但永不发送命令。
 * compact 通道没有控制授权时，这是唯一合法的用法——把「为什么没在超车」如实暴露出来。
 */
fun evaluateOnly(
    data: XiaogeVehicleData?,
    roadType: Int? = null,
    segAssistantAction: Int? = null,
    tbtMainText: String? = null,
    reasonOverride: String? = null,
): OvertakeStatusData? {
    commandsAllowed = false
    return try {
        update(data, roadType, segAssistantAction, tbtMainText)
            ?: OvertakeStatusData("暂停自动超车", false, blockingReason = "数据缺失")
    } finally {
        commandsAllowed = true
    }
}

private fun sendLaneChangeCommand(direction: String, playSound: Boolean = false): Boolean {
    if (!commandsAllowed) {
        Log.i(TAG, "只读评估：拦下变道命令 $direction")
        return false
    }
    // …原逻辑不变
}
```

> 注意：`reasonOverride` 可省略；闸门失败时 `update()` 已返回「控制授权或完整车辆数据不足」，无需另造文案。

### A2. 接回数据循环（只读）

```kotlin
// MainActivityLifecycle.kt  compact collect 处（现 873-882）
lifecycleScope.launch {
    wsClient.vehicleData.collect { vd ->
        val manager = core.getAutoOvertakeManagerOrNull()
        manager?.suspendForIncompleteTelemetry()
        core.compactVehicleDisplayOnly.value = true
        SmartSpeedLimiter.updateCurveOrientationRate(vd?.modelV2?.orientationRate?.takeIf { it.isFinite() })

        val display = vd?.toDisplayVehicleData(core.carrotManFields.value.nTBTDist)
        core.xiaogeData.value = display

        // 新增：只读评估，仅用于展示真实暂停原因
        val fields = core.carrotManFields.value
        manager?.navLaneCountCache = fields.nLaneCount
        core.overtakeStatus.value = manager?.evaluateOnly(
            display,
            roadType = if (fields.roadType == 8) null else fields.roadType,
            segAssistantAction = fields.segAssistantAction,
            tbtMainText = fields.szTBTMainText,
        )
    }
}
```

### A3. UI 常驻显示（关键：把"未启用"变成可见事实）

1. 新增 `core.overtakeStatus = mutableStateOf<OvertakeStatusData?>(null)`。
2. 控制页「超车」按钮（`MainActivityUIComponents.kt:3` 号格）：
   - 档位 2 文案改为 `自动\n(未启用)`，颜色从 `OvertakeAuto` 改为中性灰；
   - 按钮下方固定一行：`自动超车未启用 · 原因：控制授权不足`（读 `overtakeStatus.blockingReason`），**不再只用 Toast**。
3. 实时页驾驶卡如果能挤出位置，加一行 11sp 灰字状态（很廉价，但很有效）。
4. 把 `compactVehicleDisplayOnly` 改名为 `telemetryAuthorityIsCompact`（或加注释），并明确注释它同时禁用「自动超车」与「条件实验切换」——避免下一个人再踩。

### A4. 测试（守住"只读"这个不变量）

在 `AutoOvertakeManagerTest` 增加一条：

```kotlin
@Test
fun evaluateOnlyNeverSendsLaneChangeCommand() {
    val fake = FakeNetworkManager()          // 记录 sendControlCommand 调用
    val manager = AutoOvertakeManager(ctx, fake)
    val data = compactDisplayVehicleData()   // active=false / meta=null 的真实 compact 形状
    val status = manager.evaluateOnly(data)
    assertEquals("暂停自动超车", status?.statusText)
    assertTrue(fake.controlCommands.isEmpty())   // ← 核心断言
}
```

现有 `AutoOvertakeManagerTest` 全部用手工构造的"完整授权"数据，所以测试一直绿——这正是漏洞没被发现的原因。

### A5. 验证清单

- [ ] 真机注入 compact 帧：控制页常驻显示「自动超车未启用 · 控制授权不足」
- [ ] `logcat` 无 `📤 变道请求已入队`（即使把模式切到「自动」并跑高速数据）
- [ ] 模式切换 Toast 与常驻文案不矛盾
- [ ] `:app:testDebugUnitTest` 全绿

## 四、方案 B 的前置条件（若将来真要启用）

必补齐的字段（当前 compact 通道**全缺**，见 `CarrotWsClient.kt:259-292`）：

| 缺失证据 | 为什么必须有 |
|---|---|
| `steeringPressed` / `brakePressed` / `gasPressed` | 判断驾驶员是否在环；缺了就无法保证"不与人抢方向" |
| `standstill` / `steerFaultTemporary` / `steerFaultPermanent` | 故障与静止保护 |
| `modelV2.meta`（lane width / frameId） | 车道几何与数据时效校验，现已被闸门硬要求 |
| `engageAllowed` | 车端是否允许接管 |

建议的启用形态（比现状更安全）：

1. **默认不是全自动**，而是「检测到可超车 → 提示 + 等驾驶员拨杆确认」；把 `LANECHANGE` 的触发从定时器改为确认事件。
2. 先修 P0#3（`XiaogeBridgeClient` 的 `soTimeout=1500ms`），否则 7711 世界量闪断会让判断抖动。
3. `NetworkManager.commandInFlight` 是**单锁非队列**，自动变道与 `SPEED` 会互抢——启用前必须改成带优先级的队列。
4. 灰度开关 + 一键回滚（`overtake_mode` 已经能当开关，但要保证默认 0/1）。

## 五、风险提示

- **不要把 `compactVehicleDisplayOnly` 直接置 false**。那会让 `update()` 以不可信数据发起变道，同时把条件实验切换一起放开——这是本次分析里风险最高的单点改动。
- `suspendForIncompleteTelemetry()` 会清空待执行变道与车道记忆，只读评估可复用但要保证顺序（先挂起、后评估）。
- 若选 C（下线），记得同步清理：`overtake_mode` 三档 UI、两个参数滑块、`OvertakeStatusCard`、`AutoOvertakeManagerTest`(约 700 行)、以及 `XiaogeVehicleData.overtakeStatus` 字段。
