# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Navipilot (CP搭子) 是一款 Android 智能导航辅助应用，与 comma3/openpilot 设备联动，通过 UDP/TCP/HTTP 协议发送导航数据至 openpilot 设备辅助自动驾驶，同时提供驾驶行为评分。

**版本**：v260516 (versionCode: 260516)
**包名**：com.example.carrotamap / com.example.navipilot

---

## 构建命令

```bash
# Debug 构建（输出到 app/build/outputs/apk/debug/）
./gradlew assembleDebug

# Release 构建（需在 local.properties 配置签名密钥）
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行单测并过滤特定测试
./gradlew test --tests "com.example.carrotamap.GeoUtilsTest"

# 运行 instrumented test
./gradlew connectedAndroidTest

# 清理构建
./gradlew clean

# 代码检查
./gradlew detekt
```

**ABI 配置**：通过 `navipilot.abis` 属性指定编译架构（默认 `arm64-v8a,armeabi-v7a`）：
```bash
./gradlew assembleDebug -Pnavipilot.abis="arm64-v8a"
```

---

## 本地配置

`local.properties`（已在 .gitignore，勿提交）：

| 属性 | 说明 |
|------|------|
| `sdk.dir` | Android SDK 路径 |
| `AMAP_WEB_KEY` / `AMAP_WEB_SECRET` | 高德 Web 服务 REST API Key（可选，用于搜索兜底） |

高德 **Android** Key 绑定 SHA1 + 包名，写在 `AndroidManifest.xml` 的 `com.amap.api.v2.apikey`，与 Web Key 无关。

---

## 核心架构模式

### 1. 协调器模式（MainActivity 四文件拆分）

`MainActivity*.kt` 采用协调器模式拆分，实现关注点分离：

```
MainActivity.kt          # 入口，协调生命周期
├── MainActivityCore.kt   # 核心业务逻辑与状态管理（MVVM ViewModel 层）
├── MainActivityUI.kt    # Compose UI 组件（View 层）
└── MainActivityLifecycle.kt  # 生命周期与初始化
```

### 2. Channel 背压控制（防止 20Hz 广播 OOM）

高德广播频率约 20 Hz，直接处理会导致内存溢出。通过 `Channel<Intent>(Channel.BUFFERED)` 实现背压：

```kotlin
private val intentChannel = Channel<Intent>(Channel.BUFFERED) // 容量 64

override fun onReceive(context: Context?, intent: Intent?) {
    intentChannel.trySend(intent) // 非阻塞，满时丢弃旧数据
}

// 单协程顺序处理，防止内存溢出
receiverScope.launch {
    for (intent in intentChannel) {
        processIntent(intent)
    }
}
```

### 3. 三模式导航互斥

防止多个导航源同时更新状态导致数据冲突：

```kotlin
when (activeNavMode.value) {
    "AMAP" -> processAmapBroadcast(intent)
    "GOOGLE" -> processGoogleNavCallback(event)
    "TENCENT" -> processTencentNavCallback(event)
    else -> return // 跳过
}
```

### 4. 坐标系统适配器

高德/腾讯使用 GCJ-02，Google/OSM 使用 WGS-84。内部统一存储 WGS-84，边界转换：

```kotlin
object CoordinateConverter {
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double>
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double>
}
```

### 5. 三帧防抖决策

传感器噪声导致误触发超车，需要连续 3 帧都满足条件：

```kotlin
private val recentDecisions = ArrayDeque<Boolean>(3)

fun shouldOvertake(): Boolean {
    recentDecisions.addLast(checkConditions())
    if (recentDecisions.size > 3) recentDecisions.removeFirst()
    return recentDecisions.size == 3 && recentDecisions.all { it }
}
```

---

## 核心模块

```
com.example.carrotamap/
├── CarrotManDataModels.kt      # 数据模型（UDP 7706/7705 协议定义）
├── CarrotParamClient.kt        # HTTP 7000 — comma3 参数读写 REST API
├── CarrotManNetworkClient.kt   # UDP/TCP 导航数据发送
├── AmapBroadcastManager.kt     # 高德车机版广播接收
├── XiaogeDataReceiver.kt       # 设备数据接收（UDP 7705）
├── AutoOvertakeManager.kt      # 自动超车辅助决策
├── DrivingScoreEngine.kt       # 驾驶评分引擎（五维评分）
│
├── navigation/
│   ├── OsmNavigationManager.kt     # OSM 导航模式
│   ├── TencentNaviManager.kt       # 腾讯导航 SDK 模式
│   ├── AmapNavDataBridge.kt        # 高德导航数据桥接
│   ├── GoogleNavManager.kt         # Google Navigation SDK 管理器
│   └── GoogleNavDataBridge.kt      # Google 导航数据桥接到 CarrotManFields
│
├── ui/components/
│   ├── OsmMapView.kt           # OSM 地图组件
│   ├── MapSearchService.kt     # 统一地点搜索（高德SDK > 腾讯 > Photon）
│   ├── NavModeSwitcher.kt      # 导航模式切换
│   ├── GoogleNavPage.kt        # Google NavigationView 嵌入式导航页面
│   └── LedMatrixManager.kt     # LED 点阵屏控制（蓝牙）
│
├── scoring/                     # 驾驶评分系统
└── di/AppModule.kt             # Koin 依赖注入
```

---

## 关键数据流

```
导航数据源（高德/腾讯/OSM/Google）
    ↓ 广播/SDK 回调
广播/SDK 管理器 (AmapBroadcastManager, GoogleNavManager, TencentNaviManager)
    ↓ 更新中央状态
MutableState<CarrotManFields> (SSOT 单一数据源)
    ↓ 订阅状态
├── NetworkManager → CarrotManNetworkClient → UDP 7706 / TCP 7709 → comma3
└── Compose UI（响应式渲染）

comma3 设备 → XiaogeDataReceiver（UDP 7705）→ AutoOvertakeManager → ZMQ 7710
```

---

## 通信协议

| 端口/协议 | 方向 | 用途 |
|----------|------|------|
| **UDP 7706** | → comma3 | 实时导航数据（GPS、限速、TBT、电子眼） |
| **TCP 7709** | → comma3 | 路线规划成功后的路线点坐标 |
| **UDP 7705** | ← comma3 | 接收设备状态（车速、巡航状态） |
| **HTTP 7000** | ← comma3 | 参数读写 REST API (`/api/param_set`, `/api/params_bulk`) |
| **ZMQ 7710** | → comma3 | 控制命令（超车变道指令） |

---

## 导航模式

应用支持四种导航模式，通过 `NavModeSwitcher` 切换：

| 模式 | 坐标系 | 集成方式 | 成本 |
|------|--------|----------|------|
| **AMAP（默认）** | GCJ-02 | 广播接收器 | 免费 |
| **TENCENT** | GCJ-02 | 腾讯导航 SDK v7.5.0 | 需授权 |
| **OSM** | WGS-84 | OpenStreetMap + MapLibre GL | 免费 |
| **GOOGLE** | WGS-84 | Google Navigation SDK | 需 API Key |

---

## 依赖技术

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **地图**：MapLibre（OSM）、高德合并 JAR（导航+搜索+定位）、腾讯导航 SDK
- **网络**：OkHttp、Kotlin Coroutines、ZeroMQ (JeroMQ)
- **依赖注入**：Koin
- **安全**：EncryptedSharedPreferences
- **ML**：Google ML Kit 车道检测