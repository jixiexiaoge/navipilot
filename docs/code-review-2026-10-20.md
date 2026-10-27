# CP搭子 代码审查问题清单（只读审查，未改动任何代码）

> **修复状态（2026-10-20 20:57，v7.9.34 / 26102034）**
>
> 本清单的 P0 与 P1 已按用户要求修复完毕，**按指示忽略 5 条**：投屏控制通道无鉴权(P0-4)、
> 硬编码后门解锁码(P0-5)、device_token 明文 HTTP(P0-6)、release.keystore 入库(P0-7)、
> targetSdk 35(P0-10)。
>
> - 已修 P0：1 2 3 8 9；已修 P1：11–32 全部（详见每条的「✅ 已修」标注）
> - 2 条为**部分修复**：P1-23（导出接收器无法验证发送方，只能压影响面）、P1-28（重组优化只做了一半）
> - P1-30 已闭环：用户查证确认高德 `CUR_SPEED` / `SPEED` 默认就是 **km/h**，无需换算；
>   代码已收敛到单一入口 `amapSpeedKph()`
> - 验证：`compileDebugKotlin` / `testDebugUnitTest`（354 通过 0 失败）/ `assembleDebug` 全部通过

- 审查时间：2026-10-20
- 代码版本：`versionCode 26102033` / `versionName 7.9.33`
- 范围：`app/src/main`（99 个 kt，约 3.18 万行）+ `app/src/main/cpp` + Manifest + 构建脚本 + 依赖
- 方式：四路并行只读审查（生命周期/并发 · 协议数据流 · UI/Compose · 安全构建），关键结论已逐条 grep + 读源码复核
- 说明：本次**未修改任何源文件**。下文每条均已确认；无法证实的标「待确认」。

统计：**P0 10 条 · P1 22 条 · P2 26 条 · 死代码 30+ 处**

---

## 一、P0 — 必修（崩溃 / 卡死 / 可被外部利用 / 数据错误）

### 1. 广播里用外部传入的 `mode` 直接索引定长数组 → 越界崩溃
- `MainActivityCore.kt:437-440`：`modeNames = arrayOf(3 项)`，`modeNames[mode]`，`mode` 来自 `intent.getIntExtra("mode", 0)`
- `MainActivityCore.kt:461-464`：4 项数组，同样问题
- **触发**：`CHANGE_SPEED_MODE` / `CHANGE_AUTO_TURN_CONTROL` 广播带 `mode>=3`（或负数）→ `ArrayIndexOutOfBoundsException` 发生在 `BroadcastReceiver.onReceive`，**进程直接崩溃**，无兜底
- **修法**：`modeNames.getOrElse(mode) { "未知($mode)" }`，并在非法时 `return` 不下发车端

### 2. `overtake_mode` 未做范围校验即索引 3 元素数组 → 打开控制页即崩
- `MainActivityUIComponents.kt:154`（读 prefs）→ `:203` `overtakeModeColors[overtakeMode]` → `:207` `overtakeModeNames[overtakeMode]`
- 写入方 `:193` 有 `% 3` 归一化，但**首次组合读到的原始值未 `coerceIn`**
- **触发**：prefs 里 `overtake_mode` 为 3 / -1（旧版残留、备份恢复、其它组件写入）→ 组合期崩溃
- **修法**：`prefs.getInt("overtake_mode", 1).coerceIn(0, 2)`

### 3. 投屏 `/stream` 在无帧时忙等 → 打满一个 CPU 核
- `LocalProjectionServer.kt:288-290`：`while (offset >= current.size) { frameStore.awaitAfter(sequence, 5_000) ?: continue }`
- `LatestFrameStore.kt:40-46`：`awaitAfter` 只在 `sequence <= previousSequence` 时 `wait()`；`frame == null` 时直接 `return null`
- **链路**：`MjpegInputStream.sequence` 初始 `-1`，store 的 `sequence` 从 0 起 → 永不进入 `wait()`；且 `clear()`（`:27-34`）**不重置 `sequence`**。只要 `frame == null`（服务刚启动、虚拟屏重建后），`continue` 立刻再来一次 → **无任何退避的紧循环**
- **触发**：每次 `ProjectionService.installDisplay()` 里的 `frameStore.clear()` 之后、以及首帧到达前，所有已连接的 `/stream` 客户端
- **修法**：`awaitAfter` 改为 `while (frame == null) wait(timeout)`，或 `read()` 在返回 null 时 `Thread.sleep(50)`

### 4. 投屏控制通道完全无鉴权 + 绑定 `0.0.0.0` → 同网可注入触摸/按键
- `LocalProjectionServer.kt:26` `NanoWSD("0.0.0.0", activePort)`；`:214-219` 端口 8080–8099
- `:103-105` `openWebSocket` 无 Origin / Token / 路径校验（已核对 `nanohttpd-websocket:2.3.1` 源码：`NanoWSD.serve()` 对**任意 URI** 的 WS 请求都直接 `openWebSocket`）
- `:152-190`：`viewport` 分支有 `isOwner` 判断，**但 `pointer`（触摸注入）和 `key`（按键）分支完全没有所有权校验**
- **后果**：同 WiFi / 车机热点内任意设备可 ① 实时看投屏画面（`/stream`）；② 以 shell 权限向虚拟屏注入触摸按键；③ 读 `/dashboard` 全量车辆遥测
- **修法**：`pointer`/`key` 分支前统一校验 `isOwner`；绑定改 `127.0.0.1`（Tesla 模式走 VPN 转发）；WS 握手校验 Origin + 一次性 token

### 5. 硬编码后门解锁码，会员付费可绕过
- `ProjectionMembershipClient.kt:45` `private const val UNLOCK_CODE = "xiaoge"`；`:47-61` 命中即写 `projection_permanent_unlock=true` 并返回 `isLifetime = true`
- **后果**：`strings` 即可提取，任何人输入即得终身会员
- **修法**：删除客户端校验，改服务端兑换接口；本地判定改为短 TTL 缓存 + 每次联网校验

### 6. 会员 `device_token` 走明文 HTTP 发往硬编码公网裸 IP
- `ProjectionMembershipClient.kt:265` `BACKUP_PUBLIC_BASE_URL = "http://76.13.180.238:8000"`，`:266` 并入候选列表，`:226` 以 `Authorization: Bearer` 发送长期有效的 `device_token`
- **放大项**：Manifest `:77` `usesCleartextTraffic="true"` + `res/xml/network_security_config.xml:4` `<base-config cleartextTrafficPermitted="true">` 双重全局放行
- **后果**：链路上任一跳可嗅探并永久劫持设备会员身份
- **修法**：删除明文降级链路；NSC 改 `<base-config cleartextTrafficPermitted="false">` + 仅对 comma3 局域网段 `<domain-config>` 放行

### 7. 发布签名私钥 `app/release.keystore` 已提交进 Git
- `git ls-files` 确认 tracked（自 `9e37322` Initial commit 起），文件为真实密钥库（ASN.1 `3082 0ad2 0201 03`）
- **加重情节**：`app/build.gradle.kts:156` 显式 `disable += "PackagedPrivateKey"`，主动关掉了专门抓这个问题的 Lint 检查
- **后果**：仓库一旦外泄，任何人可用你的身份签名恶意 APK 冒充更新；从历史清除需重写全部提交
- **修法**：轮换密钥 → `git filter-repo --path app/release.keystore --invert-paths` → `.gitignore` 补 `*.keystore/*.jks/*.p12` → 删除 `PackagedPrivateKey` 禁用
- 注：若该密钥从未对外发布过，风险等级可降为 P1

### 8. `sdi.section_type` 被塞进「米」值 → 字段 100% 错误
- `AmapBroadcastHandlers.kt:813`（10001 路径写 `INTERVAL_DISTANCE`，区间总长/米）、`:1219`（12110 路径写 `START_DISTANCE`，已行驶/米）
- → `NaviStreamManager.kt:179` `sdiSectionType = fields.nSdiSection` → `NaviDataModels.kt:205` `put("section_type", ...)`
- 字段本义见 `CarrotManDataModels.kt:92`「区间测速 **ID**」，接收端语义是**小枚举量**（CP 侧激活时自己写 `1`）
- **后果**：CP 收到 `section_type=3000` 要么当无效丢弃，要么命中未知分支
- **修法**：无枚举定义时整键省略（与 `sdi_secondary.section_type` 现做法一致）；距离改放 `block_distance_m` / `remaining_distance_m`

### 9. 未识别的高德 ICON 原码被原样透传为 CP 转向码（码域碰撞）
- `AmapManeuver.kt:88-89`：`info(icon, source)?.carrotTurnType ?: icon` —— 不认识就回退到**原码**
- 已实测的语义冲突：`AmapManeuver.kt:33`（6→17「左后方」）在 CP 侧是 `("fork","left",3)`；`:48-49`（21→133 环岛左转 / 22→139 环岛右转）与 CP 实测 `133=rotary-right`、`139=rotary-left` **左右完全相反**
- **后果**：比"未知"更危险——CP 不会报 invalid，而是当成**另一个合法动作**显示，仪表提示与实际相反
- **修法**：未知码一律不发（撤销 `guidance_current` 或省略 `turn_type`）；环岛/左后方映射先取真实广播样本核准 AMap 原码语义再改

### 10. `targetSdk = 35` 已低于 Google Play 门槛（仅当上架）
- `app/build.gradle.kts:44` `targetSdk = 35`、`:23` `compileSdk = 35`
- 已核实政策：自 **2026-08-31** 起新应用与更新须以 **Android 16（API 36）** 为目标；延期最多至 **2026-11-01**（距今 12 天）
- 若仅自用/侧载分发，此项不适用，可跳过

---

## 二、P1 — 应修（泄漏 / 逻辑缺陷 / 数据安全）

| # | 问题 | 位置 | 后果 / 触发 | 修法 |
|---|---|---|---|---|
| 11 | **`cleanupManagers()` 定义了从未调用 → 7711 TCP 泄漏** | `MainActivityCore.kt:1011-1039`（已确认全仓库仅定义处一处）；`MainActivityLifecycle.kt:169-287` onDestroy 清了 location/network/device/navi/naviV2/overtake，**唯独没有 `xiaogeBridge.close()`** | Activity 销毁后 7711 socket + 无限重连协程 + `DashboardStore` 进程级状态全部残留；车机反复重建会叠加多条 7711 连接 | onDestroy 补 `core.cleanupManagers()` 或至少 `core.xiaogeBridge?.close()` |
| 12 | 7711 重连竞态：旧 loop 的 `finally` 会关掉**新** socket | `XiaogeBridgeClient.kt:126-135`（`restart()`）、`:144-155`（`runLoop` try/finally） | `socket` 是普通字段无保护；`readFrames()` 阻塞读不可被协程取消（要等 `soTimeout` 3s），旧 loop 醒来时 `socket` 已被新连接替换 → 关掉新连接、清空 `_world`。表现为**重连后闪断 / 灯态间歇失效** | 每个 loop 持有局部 socket 引用；`socket`/`disposed` 加 `@Volatile` 或统一锁内读写 |
| 13 | 7711 完全不校验 `version` / `sequence` / `timestamp` | `XiaogeBridgeClient.kt:188-199` | 叠加 #12 的双 socket 场景，旧帧可覆盖新帧；车端 1s 窗口内重复推送无法识别 | 记录 `sequence`，回退/重复帧丢弃 |
| 14 | Compose `mutableStateOf` 在 IO / Timer 线程写入 | `AmapBroadcastManager.kt:211,215,262-264`；`MainActivityLifecycle.kt:355,702-707`；`MainActivityCore.kt:1047`（由 `:916` 的 `Timer("MemoryMonitor")` 触发） | Snapshot State 只保证主线程写安全；跨线程写会丢更新、破坏快照，极端抛 `IllegalStateException` | 写状态前统一 `withContext(Dispatchers.Main)` |
| 15 | `onPause` 主线程同步 `commit()` 写盘 | `DeviceManager.kt:107`（同文件 `:49/:94` 用的是 `apply()`，明显笔误）；调用点 `MainActivityLifecycle.kt:105` | 卡在 Activity 切换关键路径的同步 fsync，典型 ANR 触发点 | 改 `apply()` 或整体挪 IO |
| 16 | 停止线有效距离阈值三处不一致（125 / 126.99 / 127） | `SignalWorldSource.kt:34`（≤125）、`XiaogeBridgeClient.kt:62`（≤126.99）、`DashboardSnapshot.kt:79`（<127） | 125.01–126.99 m 区间内，卡片说"可见"、7714 合成判定"超距退回高德"，两边结论相反 | 统一为单一常量 `MAX_VALID_DISTANCE_M` + 显式 `SENTINEL=127` |
| 17 | `traffic_signal` 的 `source_timestamp_ms` 沿用**已过期**的高德 receipt | `NaviStreamManager.kt:88-90, 201-205, 304`；`AmapBroadcastHandlers.kt:1498` 无条件 `naviSources.update("traffic_signal")` | 相位实际来自 7711 车端时，却带上旧的墙钟时间；CP 按 `stale_timeout_ms` 判定可能**把车端红灯整条丢掉**。违反项目自定契约「未导航时 `source_timestamp_ms` 只能取发送时刻」 | `signalSource == null` 时显式 `sourceReceipts["traffic_signal"] = now` |
| 18 | `NaviStreamManager.stop()` 不发撤销 | `NaviStreamManager.kt:60-65` 只 `sent.clear()` | 隧道/地库断连后 CP 一直持有断开前的限速/引导/车道 | `stop()` 对所有已 present 的流补发一次 revoke，或明确由 `stale_timeout_ms` 兜底 |
| 19 | 区间测速缺剩余距离时仍发 `section.active = true` | `NaviStreamManager.kt:163,182-189`（`sectionValid` 只要求 fresh + `nSdiBlockType==2`）；`AmapBroadcastHandlers.kt:2066-2078` 只在 `hasExtra("END_DISTANCE")` 时给值 | CP 解析成 `active=true, remaining=0` | `sectionActive` 增加「剩余距离存在且 >0」前置条件 |
| 20 | 普通 SDI 继承上一区间残留字段 | `AmapBroadcastHandlers.kt:818-833`（`else -> 保留之前的状态`）、`:839-843` | CP 遇到 `block_type ∈ {2,3}` 会改用 `block_distance` 按区间处理；先过区间再遇普通测速即误判 | 非区间路径显式复位 `nSdiBlockType=-1`、距离为 0 |
| 21 | 12110 仅凭「键存在」判定进入区间中 | `AmapBroadcastHandlers.kt:1153-1156, 1176, 1299-1301`（`hasStartDistance \|\| hasEndDistance \|\| hasIntervalDistance`） | 展示层做了严格数值校验，**发送侧没有** → 空/0/负值也会置 `nSdiBlockType=2` | 发送侧与展示侧共用同一「数值有效」判定函数 |
| 22 | `lane_current` 源时钟与数据分离刷新 | `AmapBroadcastHandlers.kt:1998-2002`（先刷时钟再 `updateLaneCurrent`）；`NaviSourceTracker.kt:105-114`（`acceptControlReceipt` 可能拒绝） | 车道图被拒但时钟已刷成 now → **旧车道图配新时钟**，被当当前路口图继续上发 | 时钟刷新必须在数据被接受之后 |
| 23 | 导出广播接收器用 `INTERNET`（normal 级）权限保护，形同虚设 | `AndroidManifest.xml:138-152` `AmapAutoStaticReceiver` `exported=true` + `permission="android.permission.INTERNET"`；`AmapBroadcastManager.kt:194-199` `RECEIVER_EXPORTED` 无 permission | `INTERNET` 声明即得，等于无保护。任意 App 可伪造 8 个 action 的 extras → 污染 7714 上行的限速/转向/距离，或强制拉起 Activity | 改自定义 `signature` 级权限，或动态注册 + `RECEIVER_NOT_EXPORTED` |
| 24 | `allowBackup=true` 且备份规则全空 → 用户数据全量上云 | `AndroidManifest.xml:67`；`res/xml/backup_rules.xml:8-13`（include/exclude 全注释掉）；`data_extraction_rules.xml:7-18`（空 + TODO） | 明文 SP 中的 GPS 坐标（`vpPosPointLat/Lon`）、设备 ID 随 Google 备份上传，属敏感个人信息 | 补 `<exclude domain="sharedpref" path="*"/>` 或至少排除 `CarrotAmap`/`device_prefs` |
| 25 | 声明了但代码中无对应实现的权限 | `AndroidManifest.xml:18` `BIND_ACCESSIBILITY_SERVICE`（无 AccessibilityService 实现）、`:58` `REQUEST_INSTALL_PACKAGES`（无安装调用）、`:22` `DISABLE_KEYGUARD`（Android 9+ 已废弃）、`:45-46` `CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE`（只读 WifiManager） | Play 额外审核风险 + 权限未最小化 | 删除四项；`ACCESS_BACKGROUND_LOCATION`（`:38`）若确需则备好 Play 声明 |
| 26 | `SecurePrefs` 加密存储静默降级为明文 | `core/SecurePrefs.kt:30-33` catch 后回退普通 `SharedPreferences`，调用方无法感知 | 本项目依赖 Shizuku → root/定制 ROM 占比高，Keystore 初始化失败概率不低；会员 `device_token` 就存在这里 | 降级时不写敏感键、返回"不可用"；至少上报事件而非仅 `Log.e` |
| 27 | 投屏页组合期内执行主线程阻塞 IPC，2Hz 驱动 | `MainActivityUI.kt:1573-1588`：`Shizuku.pingBinder()`（同步 Binder）+ `getPackageInfo()`（跨进程包查询）**写在组合体里**；由 `:1566-1571` `LaunchedEffect(Unit){ while(isActive){ delay(500); refreshTick++ } }` 驱动，且**无 `repeatOnLifecycle`** | 进投屏页即掉帧，Shizuku 未响应时可能 ANR；退后台仍在跑 | `produceState` + `repeatOnLifecycle(STARTED)` + `Dispatchers.IO` 采样 |
| 28 | 20Hz 车端数据直连 Composable，整棵子树每秒重组约 20 次 | `MainActivityUI.kt:373`（`toDisplaySnapshot()` 无 remember）、`:307-329`（构造完整 `VehicleData` 对象图）、`ui/components/AutoSwitchExperimentPage.kt:38,161-168`（顶层读 7 个 20Hz 字段）、`AmapNavigationOverview.kt:74-90` 每 500ms 无条件写 5 个 state | 车机低端 SoC 上掉帧的主因；`DriveViewCard` 的 1000+ 行 Canvas 随之重绘 | 高频值用 `derivedStateOf` 收敛；快照在 VM 层 `stateIn`；下推到真正消费的叶子 |
| 29 | `DriveViewCard` 把 dp 推导值当 sp 用 → fontScale≥1.3 溢出压盖 | `DriveViewCard.kt:114`（`u = maxWidth/DESIGN_W`，纯 dp 比例）、`:280` `scaled()`、`:293/295/319/381/410/520` 等 `.sp` | 卡片是 `aspectRatio` **固定高度**且注释明确「刻意不裁剪」；车机字体普遍偏大 → 速度/挡位/方向盘/限速牌叠在一起 | `heightIn(min=)` 替代固定 `aspectRatio`，或覆写 `LocalDensity` 把 `fontScale` 钳到 1.15 |
| 30 | ~~高德速度单位未取证~~ **已确认 km/h** | 手机侧 `LocationSensorManager.kt:250` 是 `Location.speed`(m/s)×3.6；高德 `CUR_SPEED`/`SPEED` 经查证默认即 km/h | 无需换算。已统一到 `AmapBroadcastHandlers.amapSpeedKph()` 单一入口，避免再次两处不一致 | ✅ 已确认并收敛 |
| 31 | Tesla VPN 未 `addRoute()` → 默认 `0.0.0.0/0` 把整机流量吸进无人读取的 fd | `TeslaVirtualIpService.kt:45-51`（仅 `addAddress(CLIENT_ADDRESS,32)` + `setMtu`，从不读 fd，`:`-`retireActiveVpn` 前 150ms 窗口） | 每次启用 Tesla 模式整机网络黑洞数秒；期间本 App 具备全流量观测能力 | 显式 `addRoute(CLIENT_ADDRESS,32)` + `addDisallowedApplication(packageName)` |
| 32 | JSON 用字符串拼接构造，用户输入可注入 | `ProjectionMembershipClient.kt:84`（`"""{"plan_code":"$planCode"...}"""`）、`:132`（`"""{"$key":"${value.trim()}"}"""`） | `recover_code` 是用户键盘输入，含 `"` 或 `\` 即破坏 JSON 或注入字段 | 改用 `org.json.JSONObject` 构造 |

---

## 三、P2 — 可选（健壮性 / 规范 / 可维护性）

**并发与资源**
- `NetworkManager.kt:68,433,446`：`onDeviceIPUpdatedCallbacks` 无锁 `mutableList`，IO 线程 add / 主线程遍历 → 可能 `ConcurrentModificationException`，打断 IP 变更通知链（WS/Navi v2/7711 三通道都不重连）。改 `CopyOnWriteArrayList`
- `MainActivityLifecycle.kt:188`：onDestroy 里 `CoroutineScope(Dispatchers.IO)` 无句柄、从不 cancel，且异步清理可能与重建后的 `DatagramSocket(7705)` 抢端口 → `BindException` 导致 UDP 通道启动失败
- `LocationSensorManager.kt:153-162`：`cleanup()` 中两个 `lateinit` 一起 try，前者未初始化时抛异常被吞 → `sensorManager.unregisterListener` 永不执行（3 个传感器 60Hz 持续耗电）。拆成两个 try + `::x.isInitialized`
- `CarrotManNetworkClient.kt:1366-1377`（TCP 未 finally 关）、`:1395-1408`（半开 socket）、`:1618-1640`（`sendHttpPost` 异常路径不 disconnect）
- `ConditionalExperimentManager.kt:53-65`：`isExperimentalModeActive`/`lastSwitchTime` 等在 `Dispatchers.Default`（500ms 一轮）写、UI 线程读，无 `@Volatile`；`:117,192,198` 每轮读 20+ 次 SP
- `AutoOvertakeManager.kt:84-91` companion：`soundPool` 是类级静态，第二次 `cleanup()` 会对已释放对象再 `release()`
- `WebRtcRoadCameraPlayer.kt:210-235`：`EglBase` 永不 `release()`；`:191-196` `close()` 只停 dispatcher，在途 `play()` 会 `RejectedExecutionException`
- `CarrotManNetworkClient.kt:597-614`：`discoveryHosts()` 每 5s 枚举整个 C 段 254 个 IP 逐个 HTTP 探测，无缓存无并发上限
- `LatestFrameStore.kt:40-46`：`wait()` 无 `while` 循环，虚假唤醒会重复推同一帧

**协议与数据**
- 两个同名 `CHECK_INTERVAL_MS`：`NaviStreamManager.kt:23`=200（实际使用） vs `NaviDataModels.kt:25`=1000（无人引用），改错不生效
- `NaviWebSocketV2Client.kt:382` `applyManifest` 清空 `streamSequences` → 同 session 内 manifest 重发时 sequence 回退到 1
- `AmapBroadcastHandlers.kt:1840-1843` `haversineDistance`：`sqrt(1-a)` 在 `a` 略 >1 时得 NaN → 目的地判定退化。加 `coerceIn(0.0,1.0)`
- `AmapBroadcastHandlers.kt:952-953`：10065 用 `getFloatExtra` 读 `SPEED`/`BEARING`，同方法内进 7714 的用类型宽容的 `displayNumber()` → 类型不符时静默归零，两处口径不一致
- 道路类型两套映射输出不同：`AmapBroadcastHandlers.kt:2163-2172`（0/6→10，其余→6）vs `AmapNaviSourceData.kt:16-21`（0/6→1，1–5/7–9→6，其余 null，这是 7714 真实出口）
- `CarrotManDataModels.kt:63` 注释仍写「44 个 UDP 字段」，实际只序列化 5+3 个，注释严重滞后
- `DashboardJson.kt:8-24`：`/dashboard` 只序列化 `schemaVersion`+`panels`，`DashboardStore` 里为 7711 停车线/灯色做的计算在 HTTP 侧是死数据
- `DailyProjectionTrial.kt:28-57`：进程被杀时会话计时不结算，下次 `begin()` 一次性计入（有 `coerceAtMost` 兜底）
- `LocalProjectionServer.kt:214` `SERVER_READ_TIMEOUT_MS = 0` → `setSoTimeout(0)` 无限阻塞读，配合 0.0.0.0 暴露，慢连接可耗尽线程
- 「7000」编号与实现不符：`CarrotWsClient`/`NetworkManager` 的 7000 是车机设备侧，`LocalProjectionServer` 实际是 8080 起
- 7711 调试快照把单调时钟与墙钟混在同一 JSON（`XiaogeBridgeClient.kt:95` vs `NaviStreamManager.kt:351/360`），现场排障易误读。建议键名区分 `*_mono_ms` / `*_wall_ms`

**UI**
- 点击热区 <48dp：`MainActivityUI.kt:1172-1177` 连接徽章≈28dp（**7706 调试面板唯一入口**）、`Carrot7706JsonDebugOverlay.kt:170/249-251`（筛选 chip ≈26-28dp）、`MainActivityUI.kt:1147` 标题 ≈24dp
- `MainActivityUI.kt:1455-1458`：「车道与状态」渲染成蓝色可点样式但**不可点**（误导性 affordance）
- Modifier 顺序：`MainActivityUI.kt:1174-1177/1381-1387`、`:420`、`Carrot7706JsonDebugOverlay.kt:170/249-251` 均为 `.clickable{}.padding()` → 热区不含 padding 且涟漪被 background 覆盖（应为 `.background().clickable()`）
- 默认值伪装成真实数据：`MainActivityUI.kt:720+748`（无记录时把当前限速当「最近策略输出」）、`:925`/`:1908`（`expiresAt` 为 null 时渲染「会员有效至 **null**」）、`:864-871`（`MapDestinationCapsul` 两回调硬编码 `DestinationNavigationResult(true,...)`，广播失败仍弹成功 Toast）、`:2175`（`roadName.take(2)`，"沪渝高速"→"沪渝"）、`AutoSwitchExperimentPage.kt:359-361`（`1.0/0.0f` → `2147483647`）
- `MainActivityUI.kt:153-158`：`BackHandler(enabled=true)` 在默认页什么都不做 → 返回键完全失效且无退出出口
- `DriveViewCard.kt:249-254`：固定 `0xFFFFFFFF` 白底 + 黑字，占实时页上半屏，夜间眩光
- 无障碍：`MainActivityUI.kt:2406-2407` 盲区用 `⚠`/`●` 字形 + 红绿**颜色**编码，色盲无法区分、TalkBack 读不出；`DriveViewCard.kt:123-125` Canvas 无 `semantics`
- 硬编码中文未走 `localized()`：`MainActivityUI.kt` 122 处 `Text(` 中 42 处是字面量（智能调速页、会员页、投屏面板、帮助/会员对话框全覆盖）。`ui/utils/Localization.kt` 目前是恒返回中文的桩，接 i18n 时全漏
- `MainActivityUIComponents.kt:586-633` 与 `MainActivityCore.kt:863-906` 两套 `sendXxxNavigationToAmap`，实现不同（Components 版会先拉起 App 并延迟 320ms），**UI 调用的是较差的 Core 版**
- `AdaptiveLayouts.kt:107-116`：手工用 dp 宽度反推字号再乘 fontScale，下限 **4.5sp**（自述底线是 10sp），fontScale 1.3 时约 5.85sp，IP 地址不可读
- `CarrotTelemetryCards.kt:549-557`：`width(58.dp)` 固定框 + `TextOverflow.Clip`，字号大时数字从中间被切
- 格式化未统一 `Locale.ROOT`：`CarrotTelemetryCards.kt:549`、`AmapNavigationOverview.kt:126/156/239/283` 用 `"%.1f".format()`（默认 Locale），de/fr 下显示「12,5」；`ProjectionMapPreview.kt:189` 用了 `Locale.ROOT`
- 未标 `@Immutable`：`DashboardSnapshot.panels → DashboardMetric`、`CarrotTelemetryCards.Metric` 全链路 List 不稳定 → `DriveViewCard`/`RealTimePage`/`DashboardOverview` 无法跳过重组
- 组合体内 O(n·m) 扫描：`AmapNavigationOverview.kt:258-266`、`DashboardOverview.kt:72/85`、`MainActivityUIComponents.kt:171-187`
- `remember` 未用 `rememberSaveable`：`MainActivityUI.kt:110-111`（调试页签）、`Carrot7706JsonDebugOverlay.kt:110-112`（筛选）、`RealTimePage.kt:95`（车道模型选择）→ 旋屏丢状态
- `RealTimePage.kt:134-146`：在组合体内声明 `@Composable fun CameraSlot(...)`，每次重组产生新函数引用 → slot 变化无法跳过

**构建与配置**
- `build.gradle.kts:132-137`：`-Xno-call-assertions/-Xno-param-assertions/-Xno-receiver-assertions` 全局关闭 Kotlin 断言 → 本会快速失败的 NPE 变成静默错误状态。涉及车辆参数下发，安全边际变低
- `build.gradle.kts:109-118/182-186`：`pickFirsts += "**/libc++_shared.so"` 全局通配，静默吞掉任何来源的 STL 版本冲突（项目同时打进 `libVAE.so`/`libbase_utils.so`/`libthird_party_libs.so`，且 CMake 侧又用 `c++_static` 规避 → 冲突真实存在）
- `build.gradle.kts:243`：`androidx.security:security-crypto:1.1.0-alpha06` alpha 版承载全部 token 加密（有 1.1.0 正式版）
- `build.gradle.kts:248`：`nanohttpd-websocket:2.3.1` 是该项目的**最后一个版本，多年未维护**，无补丁通道，却正被用作 0.0.0.0 监听的服务端
- `build.gradle.kts:220`：`io.github.webrtc-sdk:android:150.7871.01` 非官方发布通道（官方为 `org.webrtc:google-webrtc`），内含无法审计的 native `.so`，且 `proguard-rules.pro:17-22` 全量 keep
- `build.gradle.kts:54`：`GITHUB_CLIENT_ID` 默认值 `Ov23ctaOHfi...` 硬编码在脚本里（注释声称"不硬编码"），且全仓无 `BuildConfig.GITHUB_CLIENT_ID` 引用 → 死代码
- `build.gradle.kts:73-84`：release 签名缺配置时静默用空串 + 回退到仓库内的 `release.keystore`；无 CI 配置（`.github/workflows` 不存在），干净检出必失败
- `CarrotApplication.kt:100`：`Timber.plant(DebugTree())` **无 `BuildConfig.DEBUG` 判断**，Release 同样种植，仅靠 proguard `-assumenosideeffects` 兜底
- `security-config.pro:5-9`：日志剥离只覆盖 `d/v/i`，`Log.w`/`Log.e` 保留 —— 而敏感内容恰在 `w/e`：`AmapBroadcastHandlers.kt:988/1588`（lat/lon、目的地）、`MainActivityCore.kt:750`（goalLat/Lon）、`SecurePrefs.kt:31`
- `settings.gradle.kts`：`oss.sonatype.org` 已停服/重定向，应移除或改 `central.sonatype.com`
- `build.gradle.kts:1-6`：遗留未使用的 `java.util.jar.*` / `org.objectweb.asm.*` import
- `.gitignore`：**无 `*.keystore`/`*.jks`/`*.p12`/`*.pem`**（P0-7 的直接原因），`build/` 只忽略根目录未含 `app/build/`，无 `*.apk`/`*.aab`/`release/`
- `config/detekt/detekt-config.yml:2` `maxIssues: 10` 允许存量；`LargeClass 600`/`LongMethod 60` 阈值偏大（`AmapBroadcastHandlers.kt` 已 2315 行）
- `deploy_release.bat:11` 硬编码 `VERSION=v260530`，与实际 `7.9.33` 脱节，易发错包
- `local.properties:8-11`：store 与 key **同密码**，明文存于项目根
- `CarrotAmapForegroundService.kt:222-225`：`updateDataProcessedCount()` 全仓无调用，通知恒为 0；`onStartCommand` 返回 `START_STICKY` + `ACTION_STOP_SERVICE` 走 `stopSelf()` → 系统重启后可能绕过用户的"停止"

---

## 四、死代码（已 grep 全仓库确认无引用）

**A 类 — main + test + androidTest 全无调用**
- `MainActivityCore.cleanupManagers()`（**这条不是死代码，是漏调用，见 P1-11**）
- `ui/components/SpeedRingButton.kt` 整个文件
- `MainActivityUI.kt`：`NavStatusPanel`(2147)、`RoadInfoCard`(2201)、`LaneBlindspotPanel`(2255)、`LaneIndicator`(2413)、`DrivingStatusPanel`(2472)、`StatusCard`(2563)
- `ui/theme/Theme.kt`：`AppSpacing`(48)、`AppRadius`(57)、`LocalAppSpacing`(63)、`LocalAppRadius`(64)
- `MainActivityCore`：`launchAmapMobile`(835)、`getDeviceIPSafely`(321)、`getLocationSensorManagerOrNull`(960)、`getPermissionManagerOrNull`(967)、`getNetworkManagerOrNull`(974)、`getDeviceManagerOrNull`(981)、`updateUIMessage`(1064，函数体只有一句 Log)
- `MainActivityUIComponents`：`startSimulatedNavigation`(638)、`sendHomeNavigationToAmap`(586)/`sendCompanyNavigationToAmap`(612)（UI 实际调用的是 Core 版）
- `DashboardOverview.visibleValue`(157)
- `NaviDataModels.NoaIntentData`(411-508)
- `AmapBroadcastHandlers.destinationCache`(1531/1807，只写不读；且存的是**未转换的 GCJ-02**，与 `goalPosX/Y` 的 WGS-84 混用，一旦启用即坐标系错误)

**B 类 — 仅靠 androidTest 维持"活着"，生产链路跑不到**（「车辆」页删除后遗留）
`DrivingHudCard`(314 行)、`VehicleDetailsGroup`、`CarrotReceivedStatusCard`、`CarrotJourneySummary`、`CarrotLaneChangeStatus`、`CarrotVehiclePlanCard`、`CarrotBehaviorCard`、`PerceptionQualityCard`、`DrivingModeVehicleCard`、`DashboardOverview`、`AdaptiveHomeOverview`、`OvertakeStatusCard(data, showObservations)` 重载
> `MainActivityUI.kt:86-89` 有 3 个 **未使用的 import** 指向其中几个

**C 类 — 死参数 / 死状态**
- `MainActivityUI.kt:219-226`：`HomePage` 的 `wsConnected`、`wsDataTimeout`、`onPageChange` 体内零引用（用的是 `core.wsDataTimeout.value` 和就地构造的 lambda）
- `MainActivityUI.kt:228` `scrollState`（Column 没有 verticalScroll）、`:231-232` `isDataCardExpanded`/`isVideoExpanded`、`:239-245` `positionMode`、`:248-257` `currentNavStartLat/Lon`
- `MainActivityUI.kt:358-359/369`：`cruiseSetSpeed`、`carCruiseSpeed`、`vehicleData` **三个参数体内一次都没用**，但调用方每次重组仍构造完整 `VehicleData` 对象图（`:307-329`）→ 性能 + 认知双重负担
- **整条死链**：`HomePage:234 showAdvancedDialog`（只写不读）→ `:300 onShowAdvancedDialog` → `HomeControlPanel:362` → `:417 onCruiseSetClick` → 无消费者。**「高阶功能对话框」在当前代码里已不存在**，但参数链还挂着，注释 `:233` 仍写着该状态
- `MainActivityUIComponents.kt:121 onExperimentClick`、`:146-152 speedControlMode`/`isSpeedModeLoading`、`:482 displayMultiplier`（恒 1f）与 `unit`（恒 ""，`:550` 分支永不执行）
- `AdaptiveLayouts.kt:129 boardHeader`（唯一调用方未传）
- 常量重复定义：`AMAP_AUTO_PKG`(UIComponents:46) vs `AMAP_AUTO_PACKAGE`(MainActivityUI:2631)

**D 类 — 门后的整条链路**
`AmapBroadcastHandlers.kt:44` `LEGACY_NAVI_SEND_ENABLED = false` 门后的 7709/7712/7713 整套发送链（`CarrotManNetworkClient.kt:1335-1499`、`buildRgdataJson` 等）—— 已明确「导航仅使用 7714」，建议整体删除或加 `@Deprecated`

---

## 五、测试缺口（51 单测 + 16 仪器测试）

0 测试引用的关键类：`ConditionalExperimentManager`（500ms 一轮写状态）、`LaneInference`（MNN 推理主链路）、`ProjectionMembershipClient`（会员/付费）、`LocalProjectionServer`（仅 1 处引用）
其它覆盖偏薄：`MainActivityUI`(2)、`MainActivityCore`(2)、`XiaogeBridgeClient`(2)、`AutoOvertakeManager`(2)
> 本次审查发现的多数 P0/P1（越界崩溃、忙等循环、阈值不一致、单位未取证）都属于**用一个单测就能钉住**的类型，建议补测优先于补文档。

---

## 六、建议修复顺序（按 风险 × 成本 排序）

**第一批（改几行就能消除崩溃/卡死，建议今天就做）**
1. #1 #2 数组越界（4 处 `getOrElse` / `coerceIn`）
2. #3 `/stream` 忙等（加一次退避等待）
3. #11 onDestroy 补 `xiaogeBridge.close()`（一行）
4. #15 `commit()` → `apply()`（一个词）
5. #16 停止线阈值统一成一个常量

**第二批（安全，影响外部可控性）**
6. #4 投屏鉴权（绑 127.0.0.1 + `pointer`/`key` 补 `isOwner` 校验）
7. #5 #6 删后门口令 + 删明文 HTTP 降级
8. #7 轮换签名密钥并清 Git 历史（若曾对外发布）
9. #23 导出接收器改 `RECEIVER_NOT_EXPORTED` 或自定义 signature 权限
10. #24 备份排除规则

**第三批（数据正确性，需实车样本佐证）**
11. #8 `sdi.section_type` 停止塞米值
12. #9 未知 ICON 不再透传 + 核准环岛/左后方映射
13. #17 `traffic_signal` 时钟与 amap 解耦
14. #19 #20 #21 #22 区间/车道四个发送侧校验
15. #30 抓真实广播取证高德速度量纲

**第四批（性能与体验）**
16. #27 #28 组合期 IPC 与重组风暴
17. #29 fontScale 溢出
18. 清理 C/D 类死代码与三个未使用参数

**上架前才需要**
19. #10 `targetSdk` 升到 36（若上 Play，2026-11-01 是最后期限）
