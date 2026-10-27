# 车道定位 / 车道数：实现原理、生效条件与改进空间

> 只读研究，未修改任何源码。分析基于当前工作区代码（版本 8.0.28 / 26102508，2026-10-25）。
> 结论先行：**这套实现的质量在"判断逻辑"上是本项目最好的一块**（三源交叉、双时钟校验、帧序校验、失败一律降级而不是猜），
> 但**"能力开关"上被不必要地绑死在高德广播与路型上**，导致 4 个场景下功能整个不工作，其中一个和上次修的「地图必须开导航才显示」是同一类问题。

---

## 一、一句话概括

项目里所谓的「车道定位」**不是定位**，是三个独立来源的**交叉验证**：

| 来源 | 给什么 | 精度性质 |
|---|---|---|
| 高德 13012 广播 | 路口**车道图**：共 N 条、每道允许方向、哪条是推荐道 | 语义正确，但只在**导航中 + 有路口数据**时才有 |
| 本机 AR 视觉模型（MNN，4+4 分类） | 当前车道**左侧有几道 / 右侧有几道** → 总数 + 左起编号 | 像素级推断，慢变量，存在四分类饱和未知 |
| 7000 车端 `modelV2` 几何 | 本车道**宽度（米）**、左右余宽、左右各几个"宽度带" | 真实几何量，但 roadEdges 是模型外推 |

三者**不做"定位"（不会告诉你经纬度上你在第几条车道）**，只做：
- 数量一致 → 给出「左起第 M / 共 N 道」**估计值**
- 不一致 → 退回「仅几何宽度」或「未知」，绝不硬报

代码里到处强调这一点，例如 `LaneResult.kt:11`「不是核准的当前车道号」、
`LaneFusion.kt:17`「an agreement between correlated visual models, not verified map-lane identity」、
UI 文案统一带「· 非定位确认」（`ArLaneChip.kt:169`）。

---

## 二、原理与数据流（分四层）

### 第 1 层：地图源（高德 13012）

`AmapBroadcastHandlers.handleDriveWayInfo()`（`AmapBroadcastHandlers.kt:1966-2070`）

```
am broadcast --ei KEY_TYPE 13012（extras 里是 JSON）
  → DRIVE_WAY_KEYS 取 JSON（数组或对象都兼容）
  → drive_way_enabled 必须为 true（false/未知 → clearLaneSource()）
  → drive_way_info / lanes / lane_info 取数组
  → drive_way_size ∈ 1..16 且 driveWayInfo.length() == driveWaySize（严格相等，否则整包丢弃）
  → 每道取：drive_way_lane_Back_icon（5 个候选键名）、isRecommended（4 个候选键名）、
            drive_way_number、drive_way_lane_Extended、trafficLaneExtendedNew、trafficLaneType
  → carrotManFields{laneCount, nLaneCount, laneInfoList}
```

`Back_icon → 方向` 是**独立编码**，不能复用导航机动 ICON 表（`AmapLaneDirection.kt:11`，0-12 + 15 共 13 种，
含「直行+左转」这类多方向组合）。转成 CP 转向码时按「掉头 > 左 > 右 > 直」取一个（`LaneTurnCode.kt:37-44`）。

**生效条件**：在导航中（`acceptNavigationData()` 且 `!isCruiseGuide()`）；高德确实推送了该路口车道图。

### 第 2 层：视觉源（AR 模型）

`LaneInference`（`vision/LaneInference.kt`，507 行）

```
WebRTC 解码线程 onFrame → frameTap 旁路（WebRtcRoadCameraPlayer.kt:516-528）
  → 只拷 I420 的 Y 平面（灰度）到复用 buffer，队列容量 1，忙则丢旧帧
推理线程（单线程 lane-infer）每 2 帧一次
  → native：缩放 + ROI 裁剪 + /255 → VAE runtime（高德 libVAE.so 里静态链接的 MNN）
  → 输出 roadOut → chedaoshuLeft 4 类 + chedaoshuRight 4 类
  → decodeLaneClass：argmax，要求全 finite、最大 > 1e-4、**最大值唯一**（并列即 -1）
  → lanesTotal = left + right - 1（都在 1..3 才算，否则 -1）
  → LaneResult 带 model / host / route / session / 双时钟
```

模型自适应用两路投票（`LaneInference.kt:459-470`）：
- 亮度 Y 均值：<52 记夜间票、>78 记白天票，52~78 迟滞不切换
- 单帧耗时中位数 >28ms 记慢票 → 降到 `multitask`（半分辨率）

### 第 3 层：几何源（7000 `modelV2`）

`measureLaneGeometry()`（`data/LaneGeometry.kt:58-184`）

```
modelV2.frame.values: laneLines[4] / laneLineProbs[4] / laneLineStds[4] / roadEdges[2] / roadEdgeStds[2] / frameAge
  → 门限：frameAge ∈ 0..2（20Hz 下约 100ms）、内侧两线 prob≥0.7 & std≤0.3
  → 在 x=5m 与 x=15m 两个截面取 y → 本道宽 = right - left（必须 left<0<right，不用 abs 掩盖交叉）
  → 本道宽须在 2.5~4.5m
  → 左右余宽 = 路缘线 - 内线（须 std≤0.3 且 ≥0）
  → 余宽 / 本道宽 = 宽度带数，必须整数偏差 ≤0.2，且 >0 时要外侧线也验证
  → 5m 与 15m 两个截面结果必须一致（宽度差 ≤0.4m），否则撤掉"第几带"
```

状态：`Missing / Expired / Invalid / Maneuvering / WidthOnly / Stabilizing / Consistent`。
**变道中（preLaneChange / laneChangeStarting / laneChangeFinishing）一律不给宽度**（`LaneGeometry.kt:37,63-65`），
这是对的——跨界时两条内线不代表同一车道。

### 第 4 层：融合与上行

`LaneFusionTracker`（`data/LaneFusion.kt`，311 行，本项目最"硬"的一段）

身份绑定：`(host, route, session, model)` 任一变化 → 全部 reset（`:148-156`）。
在此之上要求：

| 校验 | 内容 | 位置 |
|---|---|---|
| 新鲜度 | 视觉与模型都 ≤1200ms；视觉还要求 `receivedMonoMs`/`atMs` 都在窗口内 | `:159-163`, `:307` |
| 双时钟 | mono 与 wall 任一**回退** → 整体 reset | `:139-145` |
| 时钟偏斜 | model / lateralPlan / vision 三者收包时间差 ≤300ms | `:178-181`, `:309` |
| 帧序 | frameId 必须递增（32 位回绕安全） | `:287-291` |
| 数量自洽 | `lanesTotal == left + right - 1`，且 left/right ∈ 1..3 | `:172-173` |
| 几何自洽 | 几何算出的「左起第 M」与「总数」必须等于视觉候选，否则 Conflict | `:196` |
| 连续性 | 至少 **3 个递增帧、跨度 ≥600ms** 才从 Stabilizing 升到 Estimated | `:296-302` |
| 证据不重复 | 同一帧被 UI 轮询多次不算新证据 | `:271-285` |
| 边缘带 | 左右宽度带各自独立确认（3 次 / 600ms），AR 与几何冲突单独标记 | `:47-92`, `:216-248` |

只有 `Estimated` 才对外发布（`LaneCountSource.kt:90`）。其余 9 个状态都有明确中文文案（`ArLaneChip.kt:200-210`）。

上行 7714（`NaviStreamManager.kt:242-290`）：**地图车道图优先，模型只兜底**。
地图有图 → `laneSource="map"`、`lanes` 完整、`turnInfo` 发出；
地图缺图 → `laneSource="model"`、只发 `count` 与 `currentLane`，且 `knownLanes` 为空（不把模型数伪装成"可通行"）。
`turnInfo` 任一条不可识别 → 整组 null（`LaneTurnCode.kt:50-55`）。

---

## 三、显示在哪（三处，用途不同）

| 位置 | 组件 | 数据源 | 说明 |
|---|---|---|---|
| 前视画面底部居中 chip | `ArLaneChip`（`ui/components/ArLaneChip.kt`） | 融合结果（严格） | 点开有详情弹窗，长文案明确「非定位确认 · 非变道依据」 |
| 驾驶卡自车下方宽度条 | `LaneWidthStrip`（`LaneWidthStrip.kt:40`） | `laneGeometry`（`LaneGeometryTracker`，宽松） | 只显示 `左 | 本 | 右` 米数；不要求路线/路型，只要 7000 有 modelV2 |
| 驾驶卡顶部车道方向卡 | `AmapLaneCard`（`AmapLaneCard.kt:237`） | 高德 13012 | 画箭头 + 推荐道高亮，纯地图语义 |

三条链路故意不同门槛：宽度条宽松（几何可信就给），chip 严格（要号就必须三源一致），方向卡纯地图。

---

## 四、真正生效的硬条件（缺一即无输出）

按「最容易踩」排序：

1. **必须进实时页且画面 LIVE**（`RealTimePage.kt:295-300`：`foreground && !expanded && phase == LIVE`）
2. **必须有高德广播且 `state == Fresh`**，且 **`roadType ∈ {0, 6}`**（`LaneCountSource.kt:11-12`）—— ⚠️ 见 P0
3. 必须有 comma 设备 IP（`host` 非空）与实际 WebRTC 帧流
4. 视频帧 **rotation 必须为 0**（`LaneInference.kt:190-196` 旋转帧直接跳过）
5. 7000 `modelV2` 与 `lateralPlan` 必须新鲜（`frameAge ≤ 2`），内线 prob≥0.7 / std≤0.3
6. 不处于变道中；视觉左/右计数都在 1..3；总数自洽
7. 至少连续 3 个递增帧、跨度 ≥600ms

---

## 五、可以完善的地方（按价值排序，均未动代码）

### P0 — 模型被「高德路型」绑死，没开导航就完全不跑

证据链：
- `RealTimePage.kt:294` `laneRoadEnabled = laneFusion.state != RoadTypeBlocked`
- `:295-300` `DisposableEffect(..., laneRoadEnabled)` 里 `val active = ... && laneRoadEnabled`，只有 active 才 `beginSession()`
- `:301` `player.frameTap = sessionId?.let { ... }` —— **没有 session 就不挂 tap，连一行帧都不拷**
- `LaneCountSource.kt:11-12` `roadEligible` 要求 `state == Fresh`（高德广播新鲜）**且** `roadType == 0 || 6`

后果：**没开高德导航、或不在高速/快速路上时，AR 模型一次推理都不做**。车道数、chip 全部消失，
UI 只显示「当前道路未启用车道融合（仅高速/高架）」。而模型本身完全具备算「车道数」的能力，
它并不需要高德告诉它路型——需要的只是「当前第几道」这个**编号**在马路上没有地图锚点时不发布。

这和上次修的「地图显示必须开高德导航」是同一类设计：
**把「发布编号需要路型核准」误实现成「连原始计数都不许跑」**。

建议（两种粒度，都不动发送语义）：
- 最小：把 `roadEligible` 从「能否采集/推理」里摘出来，只保留在「能否发布 `current`/`Estimated`」上。
  `beginSession` 不再看 `laneRoadEnabled`，改为 `foreground && !expanded && phase == LIVE`。
  chip 在非高速时显示「车道：共 N 道（本车位置未核准）」，而不是整块消失。
- 完整：`LaneFusionState` 增加一个 `CountOnly`（有视觉计数、无编号），7714 仍只发 `count`、不发 `current`。

> 顺带一个安全提醒：**别把这当成"能自动变道了"**。`current` 仍然要求三源一致，本次建议只放开"总数"。

### P1 — 单侧到 3 类直接拒绝，实际只能确认到 3 车道

`LaneFusion.kt:195`：`if (left == MAX_CLASS || right == MAX_CLASS) return withdraw(Limited)`（`MAX_CLASS=3`，`:308`）。
含义：模型四分类的**最后一类没有核准是否饱和**，所以只要某侧答"3 类"就不发布。

但代价是：左 3 / 右 1 → 总数 3；左 1 / 右 3 → 3；**左 3 右 3 → 5 车道**永远拿不到。
国内四车道以上高速很常见，等于高速上经常掉回「模型计数范围未核准 · 车道号未知」。

建议：不放开编号，但可以放开**下限**发布。四分类第 3 类至少表示「≥3」，
所以 `总数 ≥ left + right - 1` 这个下界是可信的。可发布「≥N 道」而不是整条撤掉。
需要一次真机标定（拿 4/5 车道高速实测第 3 类的 argmax 分布 + 与高德 13012 的车道数对照）才能把「≥」变「=」。

### P1 — 旋转帧直接跳过，某些 camera 配置下永远不出数

`LaneInference.kt:190-196`：`if (frame.rotation != 0) { warn once; return }`。

而 `WebRtcRoadCameraPlayer.kt:512-513` 明明已经按 rotation 换算了宽高用于显示。
如果某个 `CAMERA_CANDIDATES` 出的是旋转流（comma 某些配置是 1080×1920 竖帧），车道识别就整块失效，
日志里只有一行 warn（还只打一次），非常难发现。

建议：`toI420` 之后按 rotation 做一次 90°/180°/270° 拷贝旋转（Y 平面，成本 ~2ms），
或至少在 chip 上把「帧旋转未支持」显示出来，而不是静默跳过。

### P2 — 13012 的整包严格性，两个常见情况会全丢

- `drive_way_size` 必须**严格等于**数组长度（`AmapBroadcastHandlers.kt:2024`）。
  高德某些版本 `drive_way_size` 是「路口的车道总数」而数组只下发当前方向相关部分，一不等就整包丢。
- `LaneTurnCode.all()` **任一条不可识别就整组作废**（`LaneTurnCode.kt:50-55`）。
  只要有一条 `Back_icon` 是空串或未列出的值（如 13/14），`turnInfo` 全不发。

当前这两条都是「宁可不错」的保守选择，不算 bug；但如果 CP 侧将来真的按 `turn_info` 画箭头，
建议改成「逐条降级」：未知那条填 `STRAIGHT` 或单独发 `unknown` 掩码，而不是整组不发。

### P2 — 帧旁路的内存拷贝偏重

`onRoadFrame` 每 2 帧做一次 `toI420()` + `System.arraycopy` 等价操作，1280×720 时约 0.9MB/次，
15fps → ~13MB/s（含 `duplicate().get()`）。功能上没问题（队列容量 1 已丢掉积压），
但如果将来提高帧率或分辨率，这里会先成为瓶颈。可直接从 `I420Buffer.dataY` 按 stride 读取并让 native 接 stride，
省掉一次全帧拷贝（native 侧已经在用 stride 参数）。

### P3 — 门槛判定散在三处，容易再次漂移

同一个「能不能用车道几何」的判断存在三份：
- `LaneCountSource.kt:11` `isLaneGeometryRoadEligible`
- `RealTimePage.kt:294` `laneRoadEnabled`（复用上面的 state 判断）
- `MainActivityCore.kt:217-224` 把结果塞回 `ModelLaneCountSource.observe(eligibleRoad=...)`

一旦有人改其中一处，另外两处会静默不一致。建议收成一个函数 + 一个「为什么被挡」的枚举
（`NoBroadcast / NotHighway / NoFrame / NoTelemetry / Maneuvering`），chip 直接显示原因。

### P3 — 真正的「车道定位」缺最后一块：模型不输出车道线像素

`LaneResult` 现在只有 4 类计数，`masks/lines` 已按需求移除（`LaneResult.kt:8-9`）。
所以「我在画面里第几条车道」只能靠 7000 的 `roadEdges` 距离带到米级，AR 只能校验数量。
如果哪天要做「车道级定位/高精地图匹配」，前提是：
模型额外输出车道线折线（像素）→ 与 `laneLines` 做单应/H矩阵融合 → 得到横向米偏移 → 与地图 lane id 关联。
这条链路目前完全不存在，属能力缺口而非 bug。

---

## 六、测试现状

| 测试 | 覆盖 |
|---|---|
| `data/LaneFusionTest.kt` | 融合状态机（数量自洽、帧序、回退、变道、边缘带） |
| `data/LaneGeometryTest.kt` | 几何测量（门限、5m/15m 一致性、宽度带） |
| `navigation/AmapLaneDirectionTest.kt` | Back_icon 13 种编码 |
| `vision/LaneCountObservationTest.kt` | 观察会话/新鲜度 |
| `ui/components/AmapLaneCardTest.kt` | 车道卡渲染 |
| `ui/components/LaneFusionChipTest.kt` | chip 文案与冲突态 |
| `androidTest/AmapLaneBroadcastTest.kt` | 13012 广播真机链路 |

缺口：`LaneInference`（推理调度、模型自适应、旋转跳过、帧池）**没有任何测试**；
`LaneTurnCode.all()` 的整组作废规则也没有测试。

---

## 七、如果要动手，建议顺序

1. **P0（半天）**：把 `beginSession` 的路型门槛摘掉，只保留在编号发布上。收益最大、语义不变、可立即真机验证。
2. **P1 旋转帧**（半天）：旋转支持或至少显式提示，避免"换个 camera 就静默不出数"。
3. **P1 四分类饱和**（1 天 + 一次实车标定）：先做「≥N 道」的下界发布，标定后再转精确值。
4. **P3 门槛收口**（半天）：三处判定合一 + chip 显示具体原因，为前几步铺路。

> 以上都没有改动代码。需要动手时按上面顺序做，每一步都能独立验证、独立回滚。
