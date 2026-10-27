# CPdazi 功能模块系统性分析

- 项目：`C:/AIcode/CPdazi`（包名 `com.jixiexiaoge.drivingassist.debug`，当前 versionName 8.0.21 / versionCode 26102406）
- 分析方式：**只读**，未修改任何源码
- 分析时间：2026-10-25
- 规模：Kotlin 主代码 **33,742 行 / 100 个文件**（另有 native `cpp/` 与 assets 模型、JS 播放页）

## 0. 本次分析的核对状态（先看这个）

| 项目 | 结果 |
|---|---|
| 编译 `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 单测 `:app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL（无失败用例） |
| 工作区是否干净 | ❌ **62 个文件未提交**（`+7840 / −9196`），且 `AmapManeuver.kt` 处于「索引已删除、磁盘仍在」的不一致状态 |
| 改动热点 | 车道融合链路（`LaneFusion` / `LaneGeometry` / `LaneCountSource` / `LaneInference` / `ArLaneChip`）今天仍在改 |
| CI | ❌ 无 GitHub Actions；`detekt` 已接入但无流水线执行 |

> 结论先说：**代码质量在「单模块内部」是合格的（大量纯函数 + 可注入时钟 + 500+ 单测），问题集中在「模块之间」——职责重叠、状态双写、主线程承载数据搬运、以及大量已实现但未接线的能力。**

## 1. 模块规模与职责地图

| 模块 | 规模 | 职责 | 关键类 |
|---|---|---|---|
| 根包（Activity/网络/决策） | 25 文件 · 15,817 行 | 应用骨架、车端网络、命令下发、决策控制 | `MainActivityUI`(1575) `CarrotManNetworkClient`(1749) `AutoOvertakeManager`(1664) `NetworkManager`(1282) `MainActivityCore`(1134) `MainActivityLifecycle`(1055) |
| `ui/` | 7,746 行 | Compose 页面与组件、主题 | `DriveViewCard` / `RealTimePage` / `LocalMapBrowser` / `NaviOverviewCards` |
| `navigation/` | 17 文件 · 3,111 行 | 高德导航状态归一化 + 上行 Carrot Navi v2 | `NaviSourceTracker` / `NaviStreamManager` / `NaviWebSocketV2Client`(25.9KB) / `AmapNavigationDisplay` |
| `data/` | 15 文件 · 2,695 行 | 车端遥测解码、看板投影、车道融合、旁路客户端 | `CompactStateDecoder` / `CompactTelemetry` / `DashboardSnapshot` / `LaneFusion` / `LaneGeometry` / `XiaogeBridgeClient` |
| `com.navipilot.amap2tesla/` | 11 文件 · 2,324 行 | 地图投屏（虚拟屏 + HTTP/WS 服务）与会员 | `ProjectionService` / `LocalProjectionServer` / `MjpegEncoder` / `PrivilegedDisplayService` |
| `vision/` | 4 文件 · 788 行 | MNN 车道数推理（复用高德 `libVAE.so`） | `LaneInference` / `LaneNative`(JNI) / `LaneResult` |
| `camera/` | 1 文件 · 575 行 | WebRTC 5001 前视画面 | `WebRtcRoadCameraPlayer` / `WebRtcSession` |
| `core/` + `utils/` | 5 文件 · 651 行 | Result / ErrorReporter / SecurePrefs / 网络工具 | — |
| `voice/` | **空目录** | 预留，无实现 | — |

页面路由只有 5 个（`MainActivityCore.kt:57-70`）：`CpFunctions`（首页，内含「实时/控制」两 Tab）、`Projection`、`Membership`、`Experiment`、`SmartSpeed`。

## 2. 七条对外数据通道（这是理解全局的钥匙）

| # | 通道 | 端点 | 方向 | 用途 | 客户端 |
|---|---|---|---|---|---|
| 1 | UDP 发现/状态 | 7705 收、7706 发 | 车端 → App / App → 车端 | 设备发现 + 20Hz 状态 + 心跳/命令（`LANECHANGE`/`SPEED`/`SPEED_SET`） | `CarrotManNetworkClient` |
| 2 | HTTP 7000 | `/api/param_set`、`/api/settings/snapshot`、`/api/heartbeat_status` | 双向 | 参数读写、主动发现 | `CarrotParamClient` / `NetworkManager` |
| 3 | WS 7000 | `/ws/compact_state` | 车端 → App | **主遥测**（CVS1/CVB1 二进制，250ms 级） | `CarrotWsClient` → `CompactStateDecoder` |
| 4 | TCP 7711 | 4B 长度 + JSON，20Hz | 车端 → App | 小鸽桥：红绿灯相位、停止线 | `XiaogeBridgeClient` |
| 5 | WS 7714 | `/api/navi/ws/v2/*` | App → 车端 | 导航状态上行（13 条流） | `NaviWebSocketV2Client` |
| 6 | WebRTC 5001 | `POST /stream` 换 SDP | 车端 → App | 长焦前视画面（video tag `road`） | `WebRtcRoadCameraPlayer` |
| 7 | HTTP 客户端 | `:8080-8099` | 本机回环 | 地图投屏页 + `/control` WS 指针注入 | `LocalProjectionServer` |

另有 Jetlink 徽标轮询 `GET /api/egpu/model`、会员服务 HTTP（备份地址硬编码 `http://76.13.180.238:8000`）。

## 3. 三条主数据流

**A. 导航（高德 → 车端）**
```
高德车机广播 AUTONAVI_STANDARD_BROADCAST_SEND (KEY_TYPE 10001/13012/60073/...)
  → AmapBroadcastManager.onReceive (:133) → Channel(:323) → 主线程处理器(:236)
  → AmapBroadcastHandlers.handleXxx (:403 / :1161 / :1969)
      ├─ 写 CarrotManFields（旧 7706/UI 用途，:705-876）
      └─ 写 NaviSourceTracker（新 7714 用途，:899-956 / :2080）
  → NaviStreamManager.update (200ms, 主线程) → 13 条流节流 → NaviWebSocketV2Client.sendJsonData
```

**B. 遥测（车端 → UI）**
```
WS 7000 二进制帧 → CompactStateDecoder.decode（CVS1 单帧 / CVB1 批量，越界即整批丢弃）
  → CompactTelemetryStore.accept（序号回绕判定、过期分级 500/750/1500/3000ms）
  → 纯函数投影：CarrotDisplaySnapshot / VehicleObservationSnapshot / DashboardRoad / DrivingDisplay
  → buildDashboardSnapshot（panel 列表）→ DashboardStore → Compose state → 驾驶卡与看板
```

**C. 感知（画面 → 车道数）**
```
WebRTC 解码线程 → toI420() → LaneInference 队列(容量1，忙则丢帧) → lane-infer 线程(frameSkip=2)
  → JNI: liblanenet.so dlopen 高德 libVAE.so + dlsym 13 个 MNN 符号
  → chidaoshuLeft/Right argmax → LaneResult
  → LaneFusion（host+route+session 三匹配 + 双时钟 ≤1200ms + 与 modelV2 偏差 ≤300ms）→ 车道数上行
```

## 4. 逐模块评述

### 4.1 应用骨架（MainActivity 四件套）
- 分工清晰：`MainActivity`(157) 纯转发 → `Core`(业务状态+命令) / `Lifecycle`(初始化+巡检循环) / `UI`(页面)。
- **没有 ViewModel、没有 DI**（Koin 已注释）：状态全是普通类上的 `mutableStateOf`，`Core` 与 `Lifecycle` 靠 `lateinit + isInitialized` 互探 → 时序脆弱、无法单测。
- `configChanges` 已覆盖旋转，**但 uiMode/locale 变化仍会重建** → core 全丢、前台服务 stop/start 抖动；`savedInstanceState` 参数未被使用。

### 4.2 高德导航链路（最大、最成熟）
- 优点：**5 态显示机 + routeGeneration 代次校验 + 源优先级（map>phone）+ 撤销流不排队**，这套设计比多数车机 App 严谨。
- 问题：每个字段**双写两套状态**（`CarrotManFields` 与 `NaviSourceTracker`），新鲜度/路由校验规则各写一遍（`NaviStreamManager.kt:132-203` vs `AmapNavigationDisplay.kt:84-148`），已出现「旧车道图配新时钟」事故（代码注释自述）。
- `AmapBroadcastHandlers.kt` 2,423 行，单方法 `handleGuideInfo` 565 行；广播 action/包名字符串散落多处未进 `Constants`。

### 4.3 车端网络（CarrotManNetworkClient + NetworkManager）
- 双 UDP（7705 收 / 7706 发）+ 心跳 1s + 错误 3 次智能恢复，可用性设计到位。
- 问题：`CarrotManNetworkClient.kt:1436-1748` 约 **313 行是永久关闭的 7709/7712/7713 死代码**；`NetworkManager.kt:834-991` 三个重复的 `HttpURLConnection` helper；`sendSettingsToComma3` 等 5 个方法复用同一模板；端口常量 `Constants` 与类内各写一份（`Constants.kt:126-127` 只有 7705/7706，7711/7714/5001/8080 全部硬编码）。

### 4.4 遥测与看板（data/）
- 本层是全项目**工程质量最高**的区域：解码器严格校验（越界/非有限浮点/坏 UTF-8 → 整批不落地）、`accept(frames, nowMs)` 时钟可注入、投影层是纯函数，单测 100+ 例。
- 问题：`Map<String, Any?>` 穿透到投影层，到处 `as? Number`；`DashboardPanel.status` 用字符串枚举。

### 4.5 决策控制（三条链路互不感知）
| 模块 | 输出 | 现状 |
|---|---|---|
| `AutoOvertakeManager`(1664) | `LANECHANGE` | ⚠️ **已实现但未接线**：只在 `MainActivityLifecycle.kt:790` 构造、`Core.kt:1080` 清理，全仓无 `.update()` 调用点 |
| `SmartSpeedLimiter`(299) | 改写 `nRoadLimitSpeed` 字段 | 生效（随 7706 上报） |
| `AutomaticCruiseSpeedController`(90) | `SPEED` 命令 | 生效（只升不降，250ms 轮询） |
| `ConditionalExperimentManager`(596) | `/api/param_set` | 生效（7 条件 / 500ms 轮询 / 5s 冷却） |

- **没有冲突仲裁**：唯一串行点是 `NetworkManager.commandInFlight` 的全局 CAS 单锁（非队列，后被拒直接丢）。`SPEED` 持锁期间 `LANECHANGE` 会被静默丢弃。
- `SmartSpeedLimiter` 修正后的限速值（如匝道 60）与 `AutomaticCruiseSpeedController` 使用的原始 `sources.roadSpeedLimit` 可能不一致。

### 4.6 视频与投屏
- 5001：HTTP 单次 SDP 交换（非 WS 信令），RECV_ONLY，三道超时闸（ICE 1200ms / 轨道 3s / 首帧 6s），`carrot_vision_busy(409)` 单独归 BUSY，退避 500ms→20s **但无重试上限**。
- 投屏：Shizuku 绑屏 → `VirtualDisplay` 960×720@280dpi/30fps → ImageReader → JPEG q60；`/stream` MJPEG + `/frame` 单帧 + `/control` WS；端口 8080 起连试 20 个。
- 问题：`AndroidView.onRelease` 先 `release()` 而解码线程仍可能回调 → native 崩溃/黑屏窗口；30fps 下每帧 `toByteArray()` + 拼 header 双份分配；`ProjectionState.running` 与服务实际状态可能脱节。

### 4.7 视觉感知
- 亮点：**不打包官方 MNN**，运行期 dlopen 高德 `libVAE.so` 转发 13 个符号，绕开 MNN 3.x 加载 int8 模型 `Create execution error: 518`。
- 问题：`frame.rotation != 0` 时**直接 return 且只警告一次**（`LaneInference.kt:190-196`）→ 画面正常但车道数永久不更新，用户无感；`sharedResult/sharedStatus/activeSession` 是 companion 级全局单例，多页面互相覆盖；`assets/models` 下 3 个模型（vlaneDay 1.8MB / vlaneNight 3.4MB / multitask 575KB）随包体积走。

### 4.8 UI 层
- 结构合理：实时页区分「固定区」（驾驶卡 + 导航行，因 SurfaceView 不吃父级裁剪，不能进滚动区）与「滚动区」。
- 问题：`MainActivityUI.kt` 1,575 行（77.9KB）；**11 个 Composable 无任何调用点**（`AmapHeaderCard`、`CommaConnectionBadge`、`DrivingHudCard`、`NoaSignalTile`、`NavStatusPanel`、`RoadInfoCard`、`LaneBlindspotPanel`、`LaneIndicator`、`DrivingStatusPanel`、`TrafficSignalMetric`、`DashboardTrafficAction`）；`localized()` 恒返回中文，英文分支是死代码。

## 5. 风险清单

### P0（影响稳定性/正确性，建议优先）
1. **主线程承载数据搬运**：`lifecycleScope = Dispatchers.Main`（`MainActivityLifecycle.kt:39`），Navi v2 推送循环 200ms 一次在主线程跑（`:956-968`，`NaviStreamManager.CHECK_INTERVAL_MS=200`），而 `NaviStreamManager` 全域 `@Synchronized`，锁内做 JSON 构造 + socket 入队；同时 WS 每 250ms `publish`（`CarrotWsClient.kt:101-106`）驱动主线程 4Hz 重建 `buildDashboardSnapshot`。→ 卡顿/掉帧/ANR 面。
2. **自动超车「以为在跑其实没跑」**：`AutoOvertakeManager.update()` 无调用点，安全相关的能力长期处于未激活状态而无显式提示。
3. **7711 掉帧即断连**：`soTimeout = DATA_TIMEOUT_MS = 1500ms`（`XiaogeBridgeClient.kt:47,205`），20Hz 流只要卡 1.5s 就断连 → 清世界量 → UI 闪「无灯」。
4. **工作区基线不一致**：62 文件未提交、`AmapManeuver.kt` 索引/磁盘冲突 → 任何回归对比都不可信。

### P1（可维护性/一致性）
5. 端口与协议常量化缺失：7711/7714/5001/8080 硬编码，`Constants` 只覆盖一半。
6. 状态双写（`CarrotManFields` ↔ `NaviSourceTracker`）+ valid 规则双实现。
7. 死代码：11 个 Composable、网络层 313 行死端口、`NetworkManager` 重复 HTTP helper。
8. 清理路径不可控：`onDestroy` 里新建匿名 `CoroutineScope(Dispatchers.IO)` 做清理且不持有 Job；`CarrotAmapForegroundService` 随 Activity 启停，保活目标失效。
9. 全局单例：`LaneInference` companion 状态、`DashboardStore`、`SoundPool` — 多实例/重建时互相污染。

### P2（安全/工程化）
10. `app/release.keystore` **已入库被 git 跟踪**；`GITHUB_CLIENT_ID` 有硬编码兜底；会员备份服务器明文 IP 硬编码；`usesCleartextTraffic=true` + 全放开 network config。
11. 无 CI、无 lint/detekt 门禁；`deploy_release.bat` 版本号写死 `v260530`，与 gradle 的 8.0.21 不一致。
12. 权限降级过于安静：缺核心权限只 `Log.e`，不阻断不提示 → 表现为「能打开但没数据」；`sensor.compass required=true` + `minSdk 31` 会挡掉一批车机/平板。

## 6. 测试覆盖地图

- 78 个 Kotlin 测试文件（57 单测 + 21 instrumented）+ 2 个 Node 测试（播放页/dashboard JS）。
- **覆盖良好**：navigation 15 文件（`NaviStreamManagerTest` 35 例、`AmapNavigationDisplayTest` 28 例）、data 14 文件（`CarrotDisplaySnapshotTest` 21 例、`LaneFusionTest` 14 例）、ui 26 文件。
- **完全空白**：`MainActivityLifecycle`、`PermissionManager`、`ConditionalExperimentManager`、`LocationSensorManager`、`CarrotWsClient`（协商/超时/重连状态机）、`ProjectionService`、`LaneInference`（队列/投票/线程退出）、`MjpegEncoder`。
- 即「启动编排 / 权限降级 / 实验自动切换 / 投屏生命周期」四条最易回归的链路裸奔。

## 7. 建议路线（不改代码的决策建议）

**第 0 步（今天就能做）**：把工作区改动整理成一个基线提交（或明确分支），确认 `AmapManeuver.kt` 该留该删。否则后续所有重构都在流沙上。

**第 1 阶段 · 止血（1–2 天）**
- 主线程卸荷：Navi v2 推送循环挪到 `Dispatchers.Default`，`NaviStreamManager` 锁内只做状态合并、JSON 构造与入队移出锁；`compactState` 收集改 `flowOn(Dispatchers.Default)`，快照重建按 250ms 节流再去重。
- 7711 把 `soTimeout` 与 `DATA_TIMEOUT_MS` 解耦（建议 5s 级），避免瞬时卡顿误判断连。
- 给自动超车一个显式开关（或在 UI 标注「未启用」），消除「以为在跑」的认知风险。

**第 2 阶段 · 收口（1 周）**
- 常量收口：新建 `AppPorts` 统一 7705/7706/7000/7711/7714/5001/8080-8099，替换散落硬编码。
- 单一数据源：`NaviSourceTracker` 作为唯一真相，`CarrotManFields` 降级为派生投影；valid 规则抽成一份共享函数供 7714 与 UI 共用。
- 死代码清理（保留 git 历史，不必留注释坟场）。

**第 3 阶段 · 补网**
- 补 4 条空白链路的状态机单测（先做可注入时钟/端口的接口抽取，再测）。
- 上最小 CI：`testDebugUnitTest + detekt + assembleRelease` 三连。
- 安全整改：keystore 出库、备份地址进 `local.properties`/`BuildConfig`、release 开启 minify 并核对 keep 规则。

## 8. 附：与既有认知的两处修正

1. 子代理报告「`showAdvancedDialog` 只写不读、高级功能弹窗不会弹出」——**不成立**。当前代码在 `MainActivityUI.kt:448` 调用了 `MainActivityUIComponents.AdvancedFunctionsContent`，弹窗链路是通的。
2. 子代理报告「`LaneCountSource.estimate` 7 实参 vs `LaneFusionTracker.update` 6 形参，疑似编译不一致」——**已不成立**。当前工作区 `:app:compileDebugKotlin` BUILD SUCCESSFUL，该文件今天 11:52 有改动，签名已对齐。
