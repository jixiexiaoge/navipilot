# CPdazi 问题清单（2026-10-25，全项目扫描）

## 当前核查及修复范围（8.1.0）

下面原始扫描记录保留为历史，不代表所有项目仍成立，也不代表都达到P0。

| 原P0 | 当前处理 |
|---|---|
| 1 主线程数据搬运 | 看板捕获不可变输入，容量1合并刷新，Default后台单消费者构建、主线程发布；计算期间有更新则丢弃旧结果并重建，生命周期取消阻止旧结果回灌。compact客户端到期/兼容映射工作不再继承Main dispatcher。7714发送保留原串行锁和顺序，增加`Navipilot.7714.update` trace；看板增加`Navipilot.dashboard.snapshot` trace。不能把入队当同步网络等待，也不能用StateFlow的flowOn迁移collector。未宣称实车高负载已无掉帧。 |
| 2 自动超车反馈 | 8.0.25已修复配置/暂停常驻说明和CEM非运行状态，本轮继续保留完整授权门，不接回旧update。 |
| 3 7711超时 | 当前1500ms业务TTL及读取超时是失效保护契约，保留；新增半包超时后新连接完整分帧恢复回归，不通过延长超时掩盖过期。成功重连后重置退避，持续连接失败才增长至30s。 |
| 4 版本显示 | 桌面名称固定为CP搭子；关于页从BuildConfig显示版本名和版本码，Gradle为唯一版本源，本轮8.1.0。 |
| 5 公网传输与签名 | 移除公开套餐HTTP降级，会员专用客户端HTTPS-only并拒绝重定向，保持LAN HTTP/WS；签名文件保留本机、不换钥，仅停止Git跟踪并忽略。历史中的签名文件仍需按仓库访问范围独立评估，未清历史或轮换生产密钥。GitHub Client ID为公开标识，不按秘密删除。 |

### 8.1.0验证记录（2026-10-25）

- APK内核验：`8.1.0 / 26102509`，Debug包名`com.jixiexiaoge.drivingassist.debug`，
  桌面标签`CP搭子`。归档`artifacts\CPdazi-8.1.0-26102509-debug.apk`，
  SHA256：`89AFD4AB8DC6995BD2CB55DE76B3ED23054A2AACE80A246344EE0B3B446A06EC`。
- 72项定向单测通过：看板发布/原始TTL、compact解码/客户端生命周期、7714流、
  自动超车授权门、手动变道正文及会员HTTPS传输。`assembleDebug`、
  `assembleDebugAndroidTest`、`lintDebug`通过。
- detekt未全绿：前置报告2129项，本次工作区报告2138项；新增计数来自不属于本轮修改的
  `AmapManeuverIcon.kt`，本轮修改文件没有规则计数增长，不据此宣称全仓静态检查通过。
- **手机验证阻塞**：最终`adb devices -l`为空，未安装8.1.0、未核验手机已安装版本。
  新增的`DashboardSnapshotPublisherDeviceTest`、关于页版本用例、
  `XiaogeBridgeClientTest`半包重连用例仅编译，未在手机执行。
  手机重新连接并授权USB调试后仍需完成安装和这些仪器测试；不以测试APK构建替代运行。
- 后续补验：手机重新连接后，8.1.3/26102512已在折叠后的CPH2671安装并核验，
  24项手机仪器回归通过，其中包含上述看板线程/到期、关于页和7711半包恢复用例。
  此结果对应8.1.3，不能倒称8.1.0已安装或实车验证。

不以手机测试或发送入队证明车端采用、执行或自动能力可用。

## 原始静态扫描记录（以下P0建议不能直接照搬）

- 扫描方式：静态检查（detekt 全量）+ 编译警告 + 定向 grep + 人工核对关键路径
- 代码规模：Kotlin 33,742 行 / 100 文件；测试 78 文件
- **本轮实测事实**：`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 通过；detekt **2129 个加权问题 / 2129 条 error 级**（阈值未配置即全量报出）
- 严重度定义：P0 = 会造成卡顿/崩溃/数据错误或安全暴露；P1 = 隐性 bug 与维护陷阱；P2 = 质量债；P3 = 规范

---

## P0（建议本周处理）

### 1. 主线程承载数据搬运，且锁内做 JSON
- 位置：`MainActivityLifecycle.kt:39`（`lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)`）、`:956-968`（Navi v2 推送循环 200ms 一次，跑在主线程）、`NaviStreamManager.kt` 多个 `@Synchronized`（锁内构造 JSON 并 socket 入队）、`CarrotWsClient.kt:101-106`（250ms publish 驱动主线程 4Hz 重建看板快照）
- 影响：高速场景下卡顿/掉帧；与 OkHttp 回调线程争同一把锁，抖动不可控
- 建议：推送循环改 `Dispatchers.Default`；锁内只合状态，JSON 构造与入队移出锁；`compactState` 收集加 `flowOn(Default)`，快照重建按 250ms 节流 + 变更去重

### 2. 自动超车「已实现但未接线」，且 UI 会让人以为在跑
- 位置：`AutoOvertakeManager.kt` 1664 行仅被构造（`MainActivityLifecycle.kt:790`）与清理（`MainActivityCore.kt:1080`），**全仓无 `.update()` 调用**；`update()` 的闸门要求 `active==true && modelV2.meta!=null`，而 compact 适配器写死 false/null（`CompactTelemetry.kt:238-249`）
- 影响：安全相关能力长期静默未激活；`OvertakeStatusCard` 零引用、`overtakeStatus` 无消费者
- 建议：走「只读评估 + UI 常驻未启用原因」方案（详见 `docs/fix-auto-overtake-wiring.md`）。**不要**为了让它跑起来直接把 `compactVehicleDisplayOnly` 置 false

### 3. 7711 数据超时与 socket 超时混用，20Hz 流卡 1.5s 即断连
- 位置：`XiaogeBridgeClient.kt:47`（`DATA_TIMEOUT_MS = 1500L`）与 `:205`（`soTimeout = DATA_TIMEOUT_MS`）
- 影响：红绿灯/停止线闪断，UI 出现「无灯」跳变，并触发 2s→30s 退避重连
- 建议：`soTimeout` 与业务数据超时解耦（建议 5s 级），业务侧仍用 1.5s 判帧龄

### 4. 应用名/关于页版本号硬编码，显示已过时
- 位置：`app/src/main/res/values/strings.xml:2` 与 `values-zh-rCN/HK/TW` 各一处均为 `CP搭子 8.0.25`；而 `build.gradle.kts:46` 已是 `8.0.26`（真机 `dumpsys package` 也是 8.0.26）
- 影响：用户看到的版本与实际安装版本不一致，排障时容易误判（今天真机截图已实证）
- 建议：`app_name` 去掉版本号，关于页用 `BuildConfig.VERSION_NAME`

### 5. 凭据与密钥暴露面
- `app/release.keystore` **已被 git 跟踪**（`git ls-files` 命中）
- `build.gradle.kts:54`：`GITHUB_CLIENT_ID` 带硬编码兜底值
- `ProjectionMembershipClient.kt:279`：备份服务器明文 `http://76.13.180.238:8000`；`AndroidManifest.xml:77-78`：`usesCleartextTraffic=true` + 全放开 network config
- 建议（自用项目可降级处理，但至少）：keystore 移出版本库并轮换；兜底常量改为空并断言；备份地址进 `local.properties`

---

## P1（两周内）

### 6. 异常处理粗糙：一旦出错就静默
- 量化：`catch (Exception)`/`catch (_:` **192 处**，`runCatching` **35 处**，detekt `TooGenericExceptionCaught` 208、`SwallowedException` 22
- 典型：`AmapBroadcastManager.kt:556`、`AutoOvertakeManager.kt:241`
- 影响：坏帧/坏广播被吞，问题只能在现场复现时靠猜
- 建议：解析层统一抛「可诊断异常」并在 UI 显示计数；吞之前至少 `Log.w(tag, e)`

### 7. 端口/协议常量双写 + 死端口代码仍在
- `Constants.kt:126-127` 只有 7705/7706；7711/7714/5001/8080 全部硬编码在各自类里
- `CarrotManNetworkClient.kt:42-44` 的 7709/7712/7713 已由 `LEGACY_NAVI_SEND_ENABLED=false` 关闭，但代码到 `:1748` 仍在（约 313 行死代码）
- 建议：抽 `AppPorts` 单点定义，死端口整段删除（git 有历史）

### 8. 死代码与死参数
- 11 个 Composable 零调用点：`AmapHeaderCard`、`CommaConnectionBadge`、`DrivingHudCard`、`NoaSignalTile`、`NavStatusPanel`、`RoadInfoCard`、`LaneBlindspotPanel`、`LaneIndicator`、`DrivingStatusPanel`、`TrafficSignalMetric`、`DashboardTrafficAction`
- detekt：`UnusedPrivateProperty` 31、`UnusedParameter` 24、`UnusedPrivateMember` 9
- `voice/` 是空目录；`DriveViewCard.pip` 参数实时页从不传（画中画代码永不执行）
- 影响：读代码的人会被误导；IDE 补全里全是陷阱

### 9. 同一份数据双写两套状态，校验规则两套实现
- 每个高德字段既写 `CarrotManFields`（旧 7706/UI）又写 `NaviSourceTracker`（7714 上行）：`AmapBroadcastHandlers.kt:705-876` vs `:882-956`
- 新鲜度/路由代次判定重复：`NaviStreamManager.kt:132-203` vs `AmapNavigationDisplay.kt:84-148`
- 代码注释已记录过一次「旧车道图配新时钟」事故
- 建议：`NaviSourceTracker` 为唯一真相，`CarrotManFields` 降级为派生投影

### 10. 命令下发无仲裁（单锁非队列）
- `NetworkManager.kt:1101-1103`：`commandInFlight` 是 CAS 单锁，后到者直接返回 false（非排队）
- 影响：自动巡航发 `SPEED` 期间，用户/自动变道的 `LANECHANGE` 会被静默丢弃
- 建议：改成带优先级的串行队列（用户操作 > 自动），并回传「被拒」原因给 UI

### 11. Activity 巨类 + 无 ViewModel/DI + lateinit 互探
- `MainActivityUI` 1577 行、`MainActivityCore` 1137、`MainActivityLifecycle` 1055；`lateinit var` 14 处；Core 与 Lifecycle 靠 `isInitialized` 互探（`MainActivityCore.kt:997-1034`）
- 影响：无法单测、时序脆弱；uiMode/locale 变化会重建并让状态全丢（`configChanges` 未覆盖 uiMode）
- 建议：至少把「采集 + 推送」「命令下发」抽成不依赖 Activity 的类

### 12. 生命周期边界两处薄弱
- `MainActivityLifecycle.kt:188`：`onDestroy` 里新建**匿名** `CoroutineScope(Dispatchers.IO)` 做清理，不持有 Job，历史已出现 7711 连接叠加
- `CarrotAmapForegroundService` 随 Activity 启停（`onCreate` start / `onDestroy` stop），保活目标在 Activity 重建时失效

### 13. 视频编码热路径大量 `!!`
- `MjpegEncoder.kt:58-90` 连续 5 处 `!!`（`paddedBitmap` / `cropCanvas` / `croppedBitmap`），30fps 下每次都是 NPE 面
- `LaneGeometry.kt:176`、`NaviStreamManager.kt:184`、`NaviSourceTracker.kt:191` 也是 `!!`
- 建议：热路径改早返回 + 局部 val，不用 `!!`

### 14. 投屏状态的复位点分散
- `ProjectionState.running` 在 `ProjectionService.kt` 的 118/168/217/297/489/565/569 共 7 处写，服务异常死亡时无 finally 兜底
- 影响：UI 可能以为投屏还在跑，一直探一个死端口（表现为「连接中」卡住）

---

## P2（质量债，按性价比排）

| # | 问题 | 量化 / 位置 | 建议 |
|---|---|---|---|
| 15 | 魔法数字泛滥 | detekt `MagicNumber` **1129**（`AmapBroadcastHandlers.kt` 最多） | 先收口高频域（高德 KEY_TYPE、时间阈值、速度换算） |
| 16 | 超长行 | `MaxLineLength` **310** | 交给 ktlint 自动格式化 |
| 17 | 超大文件 | 6 个 >1000 行：`AmapBroadcastHandlers` 2423、`CarrotManNetworkClient` 1749、`AutoOvertakeManager` 1664、`DriveViewCard` 1579、`MainActivityUI` 1577、`NetworkManager` 1282 | 先拆纯函数与数据映射 |
| 18 | 长方法/高复杂度 | `LongMethod` 36（`handleGuideInfo` 419 行、`handleSpeedLimit` 149 行）、`CyclomaticComplexMethod` 58、`ReturnCount` 76、`NestedBlockDepth` 10 | 按 KEY_TYPE 拆成小处理器 |
| 19 | 类职责过载 | `AmapBroadcastHandlers` 55 个函数、`AmapBroadcastManager` 34 个 | 按广播类型分组 |
| 20 | 时间源混用 | `System.currentTimeMillis` 132 处 vs `elapsedRealtime` 7 处 | 业务超时统一 `elapsedRealtime`（不受系统改时间影响） |
| 21 | i18n 是假的 | `ui/utils/Localization.kt:7` 恒返回中文，英文参数全是死字符串（上百处） | 要么接 `strings.xml`，要么删掉英文参数 |
| 22 | 崩溃上报是空壳 | `core/ErrorReporter.kt:42` 只有 TODO + Log | 接 Crashlytics 或自建上报 |
| 23 | SecurePrefs 几乎没人用 | `core/SecurePrefs.kt` 只有自身引用 | 敏感字段（设备 IP、token）统一走它 |
| 24 | 编译警告 8 条 | 已废弃 `Icons.Filled.*`、`Notification.Builder.addAction`、`JSONObject.get`；`AmapBroadcastHandlers.kt:2026` **恒真条件**（`if (driveWayInfo != null)` 二次判空） | 逐条清（恒真条件属可删逻辑） |
| 25 | 测试空白区 | `MainActivityLifecycle`、`PermissionManager`、`ConditionalExperimentManager`、`LocationSensorManager`、`CarrotWsClient`、`ProjectionService`、`LaneInference`、`MjpegEncoder` 全无测试 | 先补可注入时钟/端口的接口，再测状态机 |
| 26 | 无 CI | `.github/` 无 workflow；`detekt` 已接入但从不执行；`scripts/` 空目录 | 最小流水线：test + detekt + assembleRelease |
| 27 | 发布脚本版本号写死 | `deploy_release.bat:9` 固定 `v260530` | 从 gradle 读版本 |
| 28 | 工作区脏 | 146 项未提交/未跟踪；`AmapManeuver.kt` 等处于「索引已删除、磁盘仍在」 | 先固化基线再重构 |

---

## P3（规范，可批量处理）

- `FunctionNaming` 75 条：Compose 的 PascalCase 函数名与默认规则冲突，应给 detekt 配白名单而不是改代码
- `WildcardImport` 20 条：`kotlinx.coroutines.*`、`java.net.*`
- `MaxLineLength` 之外的格式问题交给 ktlint；`detekt` 需要配 `baseline`，否则每次全量报 2129 条等于没有门禁

---

## 附：已核实的「不是问题」

- 编译与单测均通过（含今天新增的 `MapPaneVisibilityTest`）
- Release 已开 `isMinifyEnabled` / `isShrinkResources`，并有独立 `security-config.pro` 移除日志
- JSON 走 `org.json` 手写、无 Gson 反射，当前混淆风险低
- `AmapManeuver.kt` 的「索引删除但磁盘仍在」是暂存态，不是编译风险

---

## 建议的推进顺序

1. **先固化基线**（问题 28）——否则后面所有改动都无法二分定位
2. P0-1、P0-3、P0-4：三天内可完成，收益立刻可见（流畅度、不再闪断、版本显示正确）
3. P0-2：按 `docs/fix-auto-overtake-wiring.md` 的只读方案落地（半天）
4. P1-7/8/9：一轮「减负 PR」，删死代码 + 收口常量，风险低
5. 其它按性价比往下排
