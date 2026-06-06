# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Navipilot (CP搭子) 是一款 Android 智能导航辅助应用，与 comma3/openpilot 设备联动，通过 UDP/TCP/HTTP 协议发送导航数据至 openpilot 设备辅助自动驾驶，同时提供驾驶行为评分。

**版本**：v260530 (versionCode: 260530)
**包名**：com.example.navipilot
**最低 SDK**：26 (Android 8.0)，**目标 SDK**：35

---

## 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需在 local.properties 配置签名密钥）
./gradlew assembleRelease

# 运行所有单元测试
./gradlew test

# 运行单个测试类
./gradlew test --tests "com.example.navipilot.GeoUtilsTest"

# 运行特定测试方法
./gradlew test --tests "com.example.navipilot.GeoUtilsTest.distanceTo_samePoint_returnsZero"

# 运行 instrumented 测试
./gradlew connectedAndroidTest

# 代码检查
./gradlew detekt

# 清理
./gradlew clean
```

**ABI 配置**：默认仅 arm64-v8a，通过 `-Pnavipilot.abis="arm64-v8a,armeabi-v7a"` 覆盖。

---

## 测试规范

- **框架**：JUnit 5 + Google Truth (assertThat) + MockK
- **命名**：反引号方法名 + 蛇形风格，描述 Given_When_Then
- **模式**：方法内用 `// Given` / `// When` / `// Then` 注释分段
- **协程测试**：`kotlinx-coroutines-test`
- **文件位置**：`app/src/test/java/com/example/navipilot/`

---

## 构建注意事项

1. **gradle.properties 关键配置**：`org.gradle.jvmargs=-Xmx12288m`，`android.r8.maxHeapSize=16g`（腾讯导航 SDK 较大）
2. **Release 构建**：`isShrinkResources = false`（高德 JAR 的 final R class ids 与优化 shrinking 不兼容）
3. **ProGuard**：`proguard-rules.pro`（第三方 SDK 全部保留）+ `security-config.pro`（日志移除 + 混淆字典 `dictionary.txt`）
4. **AAPT2 R 类修补**：`patchRClass` Gradle 任务使用 ASM 将腾讯导航 SDK 的 `navix_*` 资源字段注入 `R.jar`，解决同名资源跨类型时的 `NoSuchFieldError`
5. **Google API Key**：通过 `MAPS_API_KEY` 注入 `AndroidManifest.xml` 的 `com.google.android.geo.API_KEY` 元数据
6. **Conflict exclusion**：排除 Google Play Services Maps/Location 传递依赖（Navigation SDK 已包含）
7. **Java 8 desugar**：`coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")` 启用（minSdk 26，但仍需要 desugar 某些 API）

---

## 核心架构模式

### 1. 协调器模式（MainActivity 五文件拆分）

```
MainActivity.kt              # 入口，协调生命周期
├── MainActivityCore.kt      # 核心业务逻辑 + 状态管理（ViewModel 层）
├── MainActivityUI.kt        # Compose UI 组件（View 层）
├── MainActivityUIComponents.kt  # UI 子组件拆分
└── MainActivityLifecycle.kt # 生命周期与初始化
```

### 2. MutableState 单一数据源（SSOT）

`carrotManFields` 是 `MutableState<CarrotManFields>`，所有导航数据桥接器通过 `postFieldsMutate` + `Handler(Looper.getMainLooper())` 确保写操作在主线程进行。数据模型用 `copy()` 不可变更新。

### 3. CarrotManFields 的 ART VerifyError 保护

数据类构造参数过多会导致真机 `VerifyError`（copy$default 校验失败）。解决方案：
- 腾讯/车道检测的「尾部」字段拆分到 `CarrotManTencentSlice` 嵌套 data class，通过 `withTencentSlice()` 扩展函数更新
- 非构造函数字段用 `@Transient var`（如 `isNightMode`、`isOffRoute`）

### 4. Channel 背压控制（防止 20Hz 广播 OOM）

```kotlin
private val intentChannel = Channel<Intent>(Channel.BUFFERED) // 容量 64
// 非阻塞发送，满时丢弃旧数据
intentChannel.trySend(intent)
// 单协程顺序处理
receiverScope.launch {
    for (intent in intentChannel) processIntent(intent)
}
```

### 5. 三模式导航互斥

```kotlin
when (activeNavMode.value) {
    "AMAP" -> processAmapBroadcast(intent)
    "GOOGLE" -> processGoogleNavCallback(event)
    "TENCENT" -> processTencentNavCallback(event)
    else -> return
}
```

### 6. 坐标系统适配器

高德/腾讯 → GCJ-02，Google/OSM → WGS-84。内部统一存储 WGS-84，通过 `CoordinateConverter` 边界转换（迭代法求精确逆变换，收敛阈值 1e-9）。

### 7. 三帧防抖决策

传感器噪声避免误触发：连续 3 帧满足条件才输出决策。

### 8. 数据桥接器模式

每个导航 SDK 有自己的 `*NavDataBridge`（如 `GoogleNavDataBridge`），负责**将 SDK 特有的导航事件/回调映射到 `CarrotManFields` 的 44 个 UDP 字段**，通过 `postFieldsMutate` 保证线程安全。

---

## 关键数据流

```
导航数据源（高德/腾讯/Google）
    ↓ 广播/SDK 回调
广播/SDK 管理器
    ↓ 更新中央状态
MutableState<CarrotManFields> (SSOT)
    ├── NetworkManager → CarrotManNetworkClient → UDP 7706 / TCP 7709 → comma3
    └── Compose UI（响应式渲染）

comma3 设备 → XiaogeDataReceiver (TCP 7711) → AutoOvertakeManager → ZMQ 7710
```

---

## 通信协议

| 端口/协议 | 方向 | 用途 |
|----------|------|------|
| **UDP 7706** | → comma3 | 实时导航数据（GPS、限速、TBT、电子眼），~5 Hz |
| **TCP 7709** | → comma3 | 路线规划完成后的路线点坐标 |
| **TCP 7711** | ← comma3 | 设备状态（carState、modelV2、controlsState JSON），5s 心跳 |
| **HTTP 7000** | ↔ comma3 | 参数读写 REST API |
| **ZMQ 7710** | → comma3 | 超车变道指令 |

**UDP 7706 负载**：44 字段 JSON，含基础通信(3)、GPS(5)、目的地(3)、限速(1/道路类别)、SDI 电子眼(7)、SDI Plus(6)、TBT 转弯(9)、剩余路程(3)、导航 GPS(4)、命令通道(2) 及内部辅助字段。

**TCP 7711 重连策略**：指数退避 2s → 5s → 10s → 20s → 30s max。

---

## 导航模式

| 模式 | 坐标系 | 集成方式 | 成本 | 状态 |
|------|--------|----------|------|------|
| **AMAP（高德车机版）** | GCJ-02 | 广播接收器 | 免费 | ✅ 生产就绪（默认） |
| **AMAP_MOBILE（高德手机 SDK）** | GCJ-02 | AMapNaviView 内嵌 | 免费 | ✅ 生产就绪 |
| **GOOGLE** | WGS-84 | Google Navigation SDK v7.0.0 | 需 API Key | ✅ 生产就绪 |
| **TENCENT** | GCJ-02 | 腾讯导航 SDK v7.5.0 | 需授权 | ✅ 完整实现 |
| **OSM** | WGS-84 | MapLibre GL | 免费 | ⚠️ 框架就绪 |

---

## 核心模块

```
com.example.navipilot/
├── MainActivity*.kt              # 入口 + 协调器（5 文件拆分）
├── CarrotManDataModels.kt        # UDP/TCP 协议数据模型 + CarrotManFields（中央状态容器 SSOT）
├── CarrotManNetworkClient.kt     # UDP 7706 + TCP 7709 发送
├── CarrotParamClient.kt          # HTTP 7000 参数读写
├── NetworkManager.kt             # 网络层统一编排
├── XiaogeDataReceiver.kt         # TCP 7711 设备数据接收
├── AmapBroadcastManager.kt       # 高德车机版广播接收
├── AmapBroadcastHandlers.kt      # 高德广播数据解析器
├── AutoOvertakeManager.kt        # 自动超车辅助决策
├── ConditionalExperimentManager.kt # 条件实验模式
├── TencentNavSdkBootstrap.kt     # 腾讯导航 SDK 初始化（幂等，必须在 NavigatorZygote 调用前执行）
├── DeviceManager.kt              # 设备生命周期管理
│
├── core/                         # 基础设施层
│   ├── Result.kt                 # 统一结果封装（sealed class: Success/Error/Loading）+ runSafely + ErrorCode 枚举
│   ├── SecurePrefs.kt            # 加密偏好设置
│   ├── AppAnalytics.kt           # 匿名使用分析（本地计数 + 24h 批量上报）
│   └── ErrorReporter.kt          # 错误上报
│
├── navigation/                   # 导航数据桥接（每模式一个 Bridge）
│   ├── AmapNavDataBridge.kt
│   ├── GoogleNavManager.kt       # Google Navigation SDK 管理
│   ├── GoogleNavDataBridge.kt
│   ├── GoogleNavInfoService.kt   # Google Navigation 导航信息服务
│   ├── TencentNavDataBridge.kt
│   ├── TencentPassiveSpeedMonitor.kt  # 腾讯 SDK 后台被动限速监控（自由行驶模式）
│   ├── CameraOverlay.kt          # 高德电子眼覆盖层（占位）
│   ├── LaneTypeIcon.kt           # 车道类型图标
│   ├── CoordinateConverter.kt    # GCJ-02 ↔ WGS-84
│   ├── GeoUtils.kt               # 地理计算工具
│   └── TurnTypeTextInference.kt  # 转向类型文本推断
│
├── ui/                           # UI 层
│   ├── theme/                    # 主题系统（固定深色车载主题，禁用 dynamicColor）
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── components/               # Compose UI 组件
│   │   ├── GoogleNavPage.kt      # Google NavigationView 内嵌
│   │   ├── TencentNavPage.kt     # 腾讯导航 SDK 页面
│   │   ├── OsmMapView.kt         # MapLibre GL 地图
│   │   ├── NavMode.kt            # 导航模式枚举（AMAP_AUTO/TMAP/AMAP_MOBILE/OSM/GOOGLE）
│   │   ├── MapSearchService.kt   # 统一地点搜索（高德SDK→Web REST→腾讯→Photon 兜底）
│   │   ├── FullscreenBrowserDialog.kt  # 全屏浏览器对话框
│   │   ├── Carrot7706JsonDebugOverlay.kt  # UDP 7706 数据调试层
│   │   ├── ModelSwitcherPage.kt  # openpilot 驾驶模型管理
│   │   ├── AutoSwitchExperimentPage.kt   # 条件实验模式配置页
│   │   ├── ProfilePage.kt        # 个人中心（评分概览）
│   │   ├── OnboardingScreen.kt   # 新手引导（5 页）
│   │   ├── HelpPage.kt           # 帮助中心
│   │   └── PrivacyDialog.kt      # 隐私声明对话框
│   ├── driving/                  # 驾驶报告
│   │   ├── DrivingReportScreen.kt       # 驾驶报告界面（五维雷达图）
│   │   └── DrivingReportShareImage.kt   # 分享图片生成
│   ├── discovery/
│   │   └── CommaDeviceDiscovery.kt      # comma3 设备发现（NSD/mDNS）
│   └── utils/Localization.kt    # 本地化工具
│
├── scoring/                      # 驾驶评分
│   ├── DrivingScoreEngine.kt     # 五维评分引擎（平稳/预判/接管/节能/NOO稳定度）
│   ├── DrivingDataCollector.kt   # 数据采集器
│   └── DrivingSession.kt        # 驾驶会话数据模型
│
├── data/                         # 数据层
│   ├── PreferenceRepository.kt   # 偏好设置仓库
│   ├── ModelDownloadManager.kt   # 模型下载管理
│   ├── ModelDownloadState.kt     # 下载状态模型
│   └── SshConnectionManager.kt   # SSH 连接管理（SSHJ）
│
├── utils/
│   ├── CoordinatePreferences.kt    # 坐标偏好存储
│   └── NetworkPerformanceUtils.kt  # 网络性能统计
│
├── di/AppModule.kt               # Koin DI
└── LocationSensorManager.kt      # GPS 定位传感器
```

---

## 辅助资源

```
web/                          # comma3 配套 Web 服务器（Flask）
├── app.py                    # REST API + Web 控制面板
├── templates/                # Jinja2 模板
└── database.db               # SQLite

carrotcode/                   # comma3 设备端参考代码 & 分析文档
├── comma3_simulator.py       # comma3 模拟器（测试用）
├── App发往Comma3数据字段清单.md  # UDP 7706 协议字段文档
├── iPixel_BLE协议分析报告.md   # LED 点阵屏 BLE 逆向协议
├── 代码分析.md / 映射关系.md    # 架构分析
├── refer SDK and Demo code/  # 腾讯/Google 导航 SDK Demo 参考代码
└── selfdrive/                # openpilot selfdrive 参考代码

config/detekt/detekt-config.yml  # detekt 代码检查配置
```

---

## 关键模式

### 1. Result 密封类统一错误处理 （core/Result.kt）

所有可能失败的异步操作通过 `Result<T>`（sealed class: Success/Error/Loading）+ `runSafely` 封装：

```kotlin
// 定义
inline fun <T> runSafely(errorCode: ErrorCode, block: () -> T): Result<T>

// 使用
val result = runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
    apiService.getData()
}
when (result) {
    is Result.Success -> handle(result.data)
    is Result.Error -> showError(result.message)
    is Result.Loading -> showLoading()
}
```

### 2. Kotlin 编译器标志 （app/build.gradle.kts）

```kotlin
kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += listOf(
        "-Xjvm-default=all",       // 启用 JVM 默认方法
        "-Xno-call-assertions",    // 减少编译时校验（防止较长 data class 的 VerifyError）
        "-Xno-param-assertions",
        "-Xno-receiver-assertions"
    )
}
```

### 3. AAPT2 R 类生成器 Bug 修补

`app/build.gradle.kts` 中的 `patchRClass` 任务使用 ASM 将腾讯导航 SDK 的 `navix_*` 资源字段注入 `R.jar`，解决同名资源跨类型时的 `NoSuchFieldError`。必须运行在 `process*Resources` 之后、`compile*Kotlin/Java` 之前。

---

## 依赖技术

| 类别 | 技术 |
|------|------|
| 语言/UI | Kotlin 2.1, Jetpack Compose + Material 3 |
| 异步 | Kotlin Coroutines + Flow + Channel |
| DI | Koin 3.5.3 |
| 网络 | OkHttp 4.12 + Gson + JeroMQ 0.6.0 |
| 地图 | MapLibre GL 11.8 (OSM), 高德合并 JAR, 腾讯导航 SDK 7.5.0, Google Navigation SDK 7.0.0 |
| SSH | SSHJ 0.38 + BouncyCastle 1.77 |
| 测试 | JUnit 5 + Google Truth + MockK |
| 存储 | EncryptedSharedPreferences + DataStore |
| 日志 | Timber 5.0.1 |
| 播放 | Media3 ExoPlayer 1.2.1 |
