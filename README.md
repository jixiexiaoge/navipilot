# Navipilot (CP搭子) - 智能导航辅助应用

## 项目概述

Navipilot (CP搭子) 是一款 Android 智能导航辅助应用，专注于与 comma3/openpilot 设备联动，实现车机导航数据共享、驾驶行为评分及多功能控制。

**核心功能**：接收高德/腾讯导航数据，通过 UDP/TCP/HTTP 协议发送至 openpilot 设备，辅助自动驾驶；同时记录驾驶行为，提供评分报告。

**版本号**：v260308 (versionCode: 260308)

---

## 核心特性

### 1. 多模式导航系统

#### 高德车机版（AMAP 模式）
- **默认导航方式**：完全免费，支持车道级引导、实时路况、电子眼播报
- 通过广播接收器 `AmapBroadcastManager` + `AmapBroadcastHandlers` 接收高德地图车机版数据
- 支持静态广播接收器 `amapAutoStaticReceiver`，即使应用未启动也能响应导航广播

#### 腾讯导航 SDK（TENCENT 模式）
- 集成腾讯地图导航 SDK (v7.5.0)
- `TencentNavDataBridge` 桥接导航数据
- `TencentNaviManager` 管理导航生命周期

#### OSM 导航（OSM 模式）
- 使用 OpenStreetMap + MapLibre GL 渲染引擎 (v11.8.0)
- `OsmMapView` 组件提供地图显示和交互
- `OsmNavigationManager` 处理路线规划和导航

#### 统一地点搜索
`MapSearchService.kt` 提供多源搜索能力：
- **高德 Android 搜索 SDK**：使用 Manifest 中的 `com.amap.api.v2.apikey`
- **高德 Web REST API**：需配置「Web 服务」类型 Key（可选）
- **腾讯地图建议接口**
- **Photon / Nominatim**：免费兜底方案

### 2. 智能超车辅助系统

`AutoOvertakeManager.kt` 提供多模式超车决策：

#### 超车模式
- **禁止超车（Mode 0）**：不执行任何超车操作
- **拨杆超车（Mode 1）**：需要用户手动拨杆触发
- **自动超车（Mode 2）**：系统自动检测并执行超车

#### 驾驶风格自适应
三种驾驶风格，自动调整超车参数：
- **保守型**：需要更大速度差（15km/h），更长冷却时间（30s）
- **标准型**：默认参数
- **激进型**：较小速度差即可（7km/h），较短冷却时间（10s）

#### 智能决策机制
- 3帧防抖验证，防止误判
- TBT 方向偏好（根据转向点距离动态抑制反向变道）
- 出口车道提醒（高速/快速路接近出口时提示靠右）
- 返回原车道策略（超车完成后自动返回）

#### ML Kit 车道检测
- 使用 ML Kit Object Detection 检测左右车道车辆
- 目标车道有车时自动取消变道计划

### 3. 驾驶评分系统

`DrivingScoreEngine.kt` 提供五维评分：

| 维度 | 权重 | 计算依据 |
|------|------|----------|
| 平稳性（Smoothness） | 30% | 急加速/急刹车/急转弯次数 |
| 预判力（Prediction） | 25% | 速度波动 + 提前减速行为 |
| 接管依赖（Intervention） | 20% | 每100km接管次数（区分主动/被动） |
| 节能（Eco） | 15% | 巡航比例 + 速度经济性（最佳60-90km/h） |
| NOO 稳定度（Stability） | 10% | NOO 使用时长/距离占比 |

#### 驾驶风格标签
- 🧘 平稳型：急加减速 < 0.3次/km，速度 50-100km/h
- 🔥 激进型：急加减速 > 2次/km
- 🏙️ 城市型：平均速度 < 35km/h
- 🛣️ 高速巡航型：平均速度 > 90km/h
- 🚗 均衡型：其他情况

#### 成就系统
8项成就含进度追踪：
- 🚀 首次出发：完成第一次驾驶记录
- 🛣️ 百公里达人：累计行驶100公里
- 🏆 千里驾驶员：累计行驶1000公里
- 🎯 稳如泰山：连续50km无接管
- 👑 NOO专家：连续100km无接管
- 🧘 平稳大师：10次行程平稳评分≥90
- 🤖 智驾先锋：NOO累计500公里
- 🌿 节能达人：连续5次行程节能评分≥85

### 4. 模型切换
- 支持多种驾驶模型切换
- 通过 `CarrotParamClient` HTTP API 读写 comma3 设备参数
- 可选：AutoPilot、Comfort、Sport 等模式

### 5. 条件实验模式

`ConditionalExperimentManager.kt` 提供实验性功能：
- 高级 NOA（Navigation on Autopilot）
- 自动泊车等前瞻特性
- ⚠️ 实验模式风险由用户自行承担

### 6. openpilot 设备通信

#### UDP 7706 - 导航数据发送
实时发送导航数据（默认 100ms 间隔，可配置）：
- GPS 坐标（WGS-84）、方向角、速度
- 道路限速、电子眼信息
- TBT 转弯导航指令
- 路线剩余距离/时间
- NOA 增强信息（出口、环岛、服务区等）

#### TCP 7709 - 路线点发送
路线规划成功后发送路线点坐标，用于 comma3 弯道限速计算

#### UDP 7705 - 设备状态接收
接收 comma3 设备广播（JSON 格式）：
- 设备 IP、端口、版本信息
- 巡航速度、实际车速
- 自动驾驶激活状态
- 转弯距离、限速点距离

#### HTTP 7000 - 参数读写 REST API
`CarrotParamClient.kt` 提供毫秒级参数读写：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/param_set` | POST | 设置参数：`{"name":"ExperimentalMode","value":1}` |
| `/api/params_bulk` | GET | 批量读取：`?names=ExperimentalMode,LongControlMode` |

#### ZMQ 7710 - 命令控制端口
用于发送控制命令（如超车变道指令）

### 7. LED 点阵屏支持

`LedMatrixManager.kt` 提供蓝牙 LED 设备控制：

#### iPixel Color 协议（基于逆向分析）
- Service UUID: `000000fa-0000-1000-8000-00805f9b34fb`
- TX Characteristic: `0xFA02` (Write)
- RX Characteristic: `0xFA03` (Notify)

#### 显示能力
- 16×16 像素点阵（支持 CJK 中文字符）
- 64 像素宽度（最多 4 字符静态显示，超出自动滚动）
- 支持自定义颜色和动画效果：
  - 静态显示
  - 左/右滚动
  - 呼吸效果
  - 激光效果

#### 实时显示内容
- 车速、导航指令、电子眼信息
- 车道信息、TBT 提示

### 8. 其他功能

#### 停车位置记录
- 自动记录停车坐标
- 支持步行导航找车

#### 设备发现
`CommaDeviceDiscovery.kt` + `OpenpilotConnectionManager.kt`：
- 局网自动发现 comma3/openpilot 设备
- 设备在线状态监控
- 自动重连机制

#### 新手引导
`OnboardingScreen.kt` 提供首次启动引导流程：
- 导航模式介绍
- 超车功能说明
- 评分系统讲解
- 模型切换指引
- 实验模式风险提示

---

## 技术架构

### 核心架构模式

**协调器模式 + MVVM + Jetpack Compose**

应用采用协调器模式拆分 MainActivity，实现关注点分离：

```
MainActivity.kt          # 应用入口（协调器）
    ├── MainActivityCore.kt       # 核心业务逻辑 & 状态管理（MVVM ViewModel 层）
    ├── MainActivityUI.kt         # Compose UI 组件（View 层）
    └── MainActivityLifecycle.kt  # 生命周期 & 初始化管理
```

**优势**：
- ✅ 清晰的关注点分离
- ✅ 可测试性（业务逻辑与 UI 解耦）
- ✅ 生命周期管理独立
- ✅ 支持并行开发

### 详细模块结构

#### 📦 核心层（Core Layer）

```
com.example.carrotamap/
├── [应用入口与协调]
│   ├── MainActivity.kt                  # 入口协调器（191 行）
│   ├── MainActivityCore.kt             # 核心业务逻辑（1,247 行）
│   ├── MainActivityUI.kt               # Compose UI 组件（879 行）
│   ├── MainActivityLifecycle.kt        # 生命周期管理（413 行）
│   └── CarrotApplication.kt            # Application 初始化（Koin DI）
│
├── [数据模型与状态]
│   ├── CarrotManDataModels.kt          # UDP/TCP 协议数据模型（232 行）
│   └── CarrotManFields.kt              # 中央状态容器（SSOT）
│
├── [网络通信层]
│   ├── CarrotManNetworkClient.kt       # UDP 7706 + TCP 7709 发送（578 行）
│   ├── XiaogeDataReceiver.kt           # TCP 7711 设备数据接收（945 行）
│   │   └── 功能：JSON 解析、心跳、自动重连、超时检测
│   ├── CarrotParamClient.kt            # HTTP 7000 参数读写 REST API（134 行）
│   └── NetworkManager.kt               # 网络层统一编排器
│
├── [设备管理]
│   ├── DeviceManager.kt                # 设备生命周期管理
│   ├── LocationSensorManager.kt        # GPS 定位传感器
│   └── PermissionManager.kt            # Android 权限管理
```

#### 🗺️ 导航集成层（Navigation Layer）

```
navigation/
├── [多源导航管理]
│   ├── AmapBroadcastManager.kt         # 高德车机版广播接收器（632 行）
│   │   ├── 功能：Channel 背压控制、三模式互斥、循环缓冲区
│   │   └── 支持：KEY_TYPE 10001/10002/13022/10056/12110
│   ├── AmapBroadcastHandlers.kt        # 高德数据解析器（478 行）
│   ├── AmapNavDataBridge.kt            # 高德 → CarrotManFields 桥接
│   │
│   ├── TencentNaviManager.kt           # 腾讯导航 SDK 管理（117 行）
│   ├── TencentNavDataBridge.kt         # 腾讯 → CarrotManFields 桥接
│   │
│   ├── OsmNavigationManager.kt         # OSM 导航管理器（68 行，框架）
│   ├── OsmNavModels.kt                 # OSM 数据模型
│   │
│   ├── GoogleNavManager.kt             # Google Navigation SDK 管理（304 行）
│   └── GoogleNavDataBridge.kt          # Google → CarrotManFields 桥接
│
├── [坐标系统]
│   ├── CoordinateConverter.kt          # GCJ-02 ↔ WGS-84 转换
│   └── GeoUtils.kt                     # 地理计算工具
│
└── [导航辅助]
    └── TurnTypeTextInference.kt        # 转向类型文本推断
```

#### 🤖 决策系统层（Decision Layer）

```
├── [智能超车系统]
│   └── AutoOvertakeManager.kt          # ML 决策引擎（1,649 行）
│       ├── 决策流水线：
│       │   ├── 1️⃣ 先决条件检查（速度、曲率、转向角）
│       │   ├── 2️⃣ 前车检测（modelV2.lead0）
│       │   ├── 3️⃣ 邻道安全检测（ML Kit 视觉识别）
│       │   ├── 4️⃣ 三帧防抖验证
│       │   ├── 5️⃣ TBT 方向偏好（出口避让）
│       │   ├── 6️⃣ 2.5s 延迟执行
│       │   └── 7️⃣ 20s 冷却期
│       ├── 自适应参数系统：
│       │   ├── 保守型（60km/h, 40m, 400m）
│       │   ├── 标准型（50km/h, 50m, 300m）
│       │   └── 激进型（40km/h, 60m, 200m）
│       └── 集成：Google ML Kit + SoundPool
│
└── [驾驶评分系统]
    └── scoring/
        ├── DrivingScoreEngine.kt       # 五维评分引擎（265 行）
        │   ├── 平稳性（30%）：急加减速/急转弯次数
        │   ├── 预判力（25%）：速度波动 + 提前减速
        │   ├── 接管依赖（20%）：每 100km 接管次数
        │   ├── 节能（15%）：巡航比例 + 速度经济性
        │   └── NOO 稳定度（10%）：自动驾驶使用占比
        ├── DrivingDataCollector.kt     # 数据采集器
        └── DrivingSession.kt            # 驾驶会话数据模型
```

#### 🎨 UI 组件层（UI Layer）

```
ui/
├── components/
│   ├── [导航界面]
│   │   ├── OsmMapView.kt               # MapLibre OSM 地图组件（824 行）
│   │   ├── AmapNaviPage.kt             # 高德导航页面
│   │   ├── AmapMobileNavPage.kt        # 高德手机版导航页
│   │   ├── TencentNavPage.kt           # 腾讯导航页面
│   │   ├── GoogleNavPage.kt            # Google NavigationView 嵌入页
│   │   ├── NavModeSwitcher.kt          # 导航模式切换器
│   │   └── MapSearchService.kt         # 统一 POI 搜索（高德 SDK/Web REST/腾讯/Photon）
│   │
│   ├── [硬件集成]
│   │   ├── LedMatrixManager.kt         # BLE LED 点阵屏控制器（300+ 行）
│   │   │   ├── 协议：逆向工程 iPixel Color 协议
│   │   │   ├── 渲染：Text → Bitmap → 列优先字节数组
│   │   │   └── 动画：静态/滚动/呼吸/激光效果
│   │   └── LedMatrixPreview.kt         # LED 预览组件
│   │
│   ├── [功能页面]
│   │   ├── ProfilePage.kt              # 个人中心
│   │   ├── ModelSwitcherPage.kt        # 驾驶模型切换
│   │   ├── AutoSwitchExperimentPage.kt # 条件实验模式
│   │   ├── OnboardingScreen.kt         # 新手引导
│   │   ├── HelpPage.kt                 # 帮助页面
│   │   ├── Carrot7706JsonDebugOverlay.kt # 调试叠加层
│   │   ├── SshConfigDialog.kt          # SSH 配置对话框
│   │   └── PrivacyDialog.kt            # 隐私声明对话框
│   │
│   └── [Widget]
│       └── TencentNavWidgets.kt        # 腾讯导航小部件
│
├── driving/
│   ├── DrivingReportScreen.kt          # 驾驶报告界面（605 行）
│   └── DrivingReportShareImage.kt      # 分享图片生成
│
├── discovery/
│   ├── CommaDeviceDiscovery.kt         # comma3 设备发现（局域网扫描）
│   └── OpenpilotConnectionManager.kt   # openpilot 连接管理
│
├── theme/
│   ├── Color.kt                        # Material 3 配色
│   ├── Theme.kt                        # 主题定义
│   └── Type.kt                         # 字体排版
│
└── utils/
    └── LocaleUtils.kt                  # 本地化工具
```

#### 🔧 基础设施层（Infrastructure Layer）

```
├── core/
│   ├── AppAnalytics.kt                 # 应用分析（埋点）
│   ├── ErrorReporter.kt                # 错误上报
│   ├── Result.kt                       # 统一结果封装（Success/Failure）
│   └── SecurePrefs.kt                  # 加密 SharedPreferences
│
├── data/
│   ├── PreferenceRepository.kt         # 偏好设置仓库
│   ├── ModelDownloadManager.kt         # 模型下载管理
│   ├── ModelDownloadState.kt           # 下载状态
│   └── SshConnectionManager.kt         # SSH 连接管理（JSch）
│
├── di/
│   └── AppModule.kt                    # Koin 依赖注入模块
│       ├── single { } — 单例（网络客户端、管理器）
│       └── factory { } — 工厂（IP 相关客户端）
│
└── utils/
    ├── CoordinatePreferences.kt        # 坐标偏好设置
    └── NetworkPerformanceUtils.kt      # 网络性能监控
```

### 代码规模统计

| 模块 | 文件数 | 总行数（估算） | 关键组件行数 |
|------|--------|---------------|-------------|
| **核心入口** | 5 | ~2,730 | MainActivityCore (1,247) |
| **网络通信** | 4 | ~1,657 | XiaogeDataReceiver (945) |
| **导航集成** | 10 | ~2,500 | AmapBroadcastManager (632), GoogleNavManager (304) |
| **决策系统** | 4 | ~2,179 | AutoOvertakeManager (1,649) |
| **UI 组件** | 23 | ~7,500+ | OsmMapView (824), DrivingReportScreen (605) |
| **基础设施** | 10 | ~1,800 | - |
| **其他** | 14 | ~4,750 | - |
| **总计** | **70+** | **~23,116** | - |

---

## 数据流与通信模式

### 主数据流（App → comma3）

```
┌─────────────────────────────────────────────────────────────────┐
│ 导航数据源（高德/腾讯/OSM/Google）                                │
└────────────────────┬────────────────────────────────────────────┘
                     │ 广播/SDK 回调
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 广播/SDK 管理器                                                   │
│ (AmapBroadcastManager, GoogleNavManager, TencentNaviManager)   │
└────────────────────┬────────────────────────────────────────────┘
                     │ 更新中央状态
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ MutableState<CarrotManFields> (单一数据源 SSOT)                 │
└────────────┬───────────────────────────────┬────────────────────┘
             │ 订阅状态                       │ 订阅状态
             ▼                               ▼
┌────────────────────────────┐  ┌───────────────────────────────┐
│ NetworkManager (网络编排器) │  │ Compose UI（响应式渲染）       │
└────────────┬───────────────┘  └───────────────────────────────┘
             │ 协调发送
             ▼
┌─────────────────────────────────────────────────────────────────┐
│ CarrotManNetworkClient                                          │
│ • UDP 7706 (导航数据：GPS、TBT、限速、电子眼) - 5 Hz           │
│ • TCP 7709 (路线几何点) - 规划成功后一次性发送                 │
└─────────────────────────────────────────────────────────────────┘
             │ 网络传输
             ▼
┌─────────────────────────────────────────────────────────────────┐
│ comma3/openpilot 设备                                            │
└─────────────────────────────────────────────────────────────────┘
```

### 反向数据流（comma3 → App）

```
┌─────────────────────────────────────────────────────────────────┐
│ comma3/openpilot Python 后端 (carrot_server.py)                 │
└────────────────────┬────────────────────────────────────────────┘
                     │ TCP 7711 (JSON 数据包)
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ XiaogeDataReceiver                                              │
│ • JSON 解析（carState, modelV2, controlsState）                │
│ • 心跳机制（5s 间隔）                                           │
│ • 超时检测（4s 无数据触发重连）                                 │
│ • 指数退避重连（5s → 10s → 20s → 40s → 60s max）              │
└────────────────────┬────────────────────────────────────────────┘
                     │ XiaogeVehicleData
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ AutoOvertakeManager（ML 决策流水线）                            │
│ • 先决条件检查                                                   │
│ • 前车检测 (modelV2.lead0)                                      │
│ • 邻道安全检测 (ML Kit)                                         │
│ • 三帧防抖                                                       │
│ • TBT 方向偏好                                                   │
└────────────────────┬────────────────────────────────────────────┘
                     │ 变道指令
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ ZMQ 7710 → comma3（超车控制命令）                                │
└─────────────────────────────────────────────────────────────────┘
```

### 状态管理模式

#### 1. **中央状态容器（SSOT）**
```kotlin
// 不可变数据类 + MutableState
data class CarrotManFields(
    val vpPosPointLat: Double = 0.0,
    val vpPosPointLon: Double = 0.0,
    val szRoadName: String = "",
    val nRemainDist: Int = 0,
    // ... 50+ 字段
)

val state = mutableStateOf(CarrotManFields())
```

**优势**：
- 单向数据流
- 可预测的状态变化
- 时间旅行调试
- Compose 自动响应式更新

#### 2. **Coroutine Channel 背压控制**
```kotlin
// AmapBroadcastManager 中的关键实现
private val intentChannel = Channel<Intent>(Channel.BUFFERED)

// 广播接收器仅将 Intent 放入 Channel
override fun onReceive(context: Context?, intent: Intent?) {
    intentChannel.trySend(intent) // 非阻塞，满时丢弃
}

// 单个协程顺序处理，防止内存溢出
launch {
    for (intent in intentChannel) {
        processIntent(intent) // 顺序处理
    }
}
```

**解决问题**：
- 高德广播频率 ~20 Hz，直接处理会 OOM
- Channel 缓冲区固定 64，满时丢弃旧数据
- 单协程顺序处理，保证线程安全

#### 3. **三模式导航互斥**
```kotlin
// 防止多个导航源同时更新状态
when (activeNavMode.value) {
    "AMAP" -> // 仅处理高德广播
    "GOOGLE" -> // 仅处理 Google SDK 回调
    "TENCENT" -> // 仅处理腾讯 SDK 回调
    else -> // 跳过广播
}
```

#### 4. **AtomicBoolean 连接状态**
```kotlin
// XiaogeDataReceiver 中的线程安全标志
private val isTcpConnected = AtomicBoolean(false)

// 无锁读写
if (isTcpConnected.get()) { /* ... */ }
isTcpConnected.set(true)
```

---

## 独特设计模式

### 1. **协调器模式（MainActivity 四文件拆分）**

**模式来源**：iOS Coordinator Pattern
**实现**：
```
MainActivity.kt           # 入口，委托生命周期事件
    ├── MainActivityCore.kt        # 业务逻辑 + 状态管理（ViewModel 层）
    ├── MainActivityUI.kt          # Compose UI（View 层）
    └── MainActivityLifecycle.kt   # 初始化 + 资源清理
```

**优势**：
- 单一职责原则（SRP）
- 可测试性（Core 可独立测试）
- 并行开发（UI 和逻辑解耦）
- 代码审查友好（小文件）

### 2. **Broadcast Channel 背压控制**

**问题**：高德广播 20 Hz → OOM
**解决方案**：
- `Channel<Intent>` 容量 64
- `trySend()` 非阻塞，满时丢弃
- 单协程顺序处理
- 循环缓冲区（保留最近 20 条）

**代码**：
```kotlin
private val broadcastList = mutableStateListOf<BroadcastData>()
if (broadcastList.size > 20) {
    broadcastList.removeAt(0) // 丢弃最旧数据
}
```

### 3. **自适应参数系统（策略模式变体）**

**问题**：固定阈值不适应不同驾驶风格
**解决方案**：
```kotlin
enum class DrivingStyle {
    CONSERVATIVE, STANDARD, AGGRESSIVE
}

fun getAdaptiveParameter(key: String, default: Float): Float {
    val multiplier = when (currentStyle) {
        CONSERVATIVE -> 0.8f
        STANDARD -> 1.0f
        AGGRESSIVE -> 1.2f
    }
    return baseParams[key] * multiplier
}
```

**应用**：
- 超车速度阈值
- 前车距离判断
- 转弯点避让距离

### 4. **坐标系统适配器**

**问题**：高德/腾讯用 GCJ-02，Google/OSM 用 WGS-84
**解决方案**：
```kotlin
// 内部统一存储 WGS-84
object CoordinateConverter {
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double>
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double>
}

// 导航管理器在边界转换
class AmapBroadcastManager {
    private fun processLocation(gcjLat: Double, gcjLon: Double) {
        val (wgsLat, wgsLon) = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)
        updateState(wgsLat, wgsLon)
    }
}
```

### 5. **三帧防抖决策**

**问题**：传感器噪声导致误触发超车
**解决方案**：
```kotlin
private val recentDecisions = ArrayDeque<Boolean>(3)

fun shouldOvertake(): Boolean {
    val currentDecision = checkConditions()
    recentDecisions.addLast(currentDecision)
    if (recentDecisions.size > 3) recentDecisions.removeFirst()

    // 需要连续 3 帧都满足条件
    return recentDecisions.size == 3 && recentDecisions.all { it }
}
```

### 6. **逆向工程 BLE 协议**

**成就**：无官方 SDK，通过 HCI 抓包完全复现协议
**实现**：
```kotlin
// iPixel Color 协议包结构
// ┌──────┬──────┬──────┬──────┬─────────────┬──────────┐
// │ 0xFA │ 0x02 │ CMD  │ LEN  │   PAYLOAD   │ CHECKSUM │
// └──────┴──────┴──────┴──────┴─────────────┴──────────┘

fun buildPacket(text: String): ByteArray {
    val bitmap = renderTextToBitmap(text)
    val columnMajor = bitmapToColumnMajor(bitmap) // 16x16 → 32 字节
    val checksum = columnMajor.fold(0) { acc, b -> acc xor b.toInt() }
    return byteArrayOf(0xFA.toByte(), 0x02, CMD_DISPLAY, 32, *columnMajor, checksum.toByte())
}
```

### 7. **穷举式 When 语句**

**模式**：所有 `when` 表达式处理所有 enum 分支
**优势**：
- 编译时安全
- 添加新 case 时编译器强制更新所有 when
- 无 `else` 分支，避免遗漏

**示例**：
```kotlin
when (broadcastKeyType) {
    10001 -> handleGuideInfo()
    10002 -> handleLocationInfo()
    10056 -> handleRouteInfo()
    12110 -> handleSpeedLimit()
    13022 -> handleNavigationStatus()
    // 15 个分支，无 else
}
```

### 8. **JSON-Based IPC（TCP 7711）**

**独特性**：openpilot 通常用 Cereal/Capnproto 二进制协议
**本项目**：Python ↔ Android 用 JSON
**权衡**：
- ✅ 人类可读，易调试
- ✅ 跨语言无需代码生成
- ❌ 带宽略高（~2x）
- ❌ 解析稍慢（可接受，5 Hz 更新）

---

## 通信协议

### UDP 7706 - 导航数据

主要字段（完整协议见源码 `CarrotManDataModels.kt`）：

| 字段 | 说明 |
|------|------|
| latitude/longitude | GPS 坐标 |
| heading/speed | 方向角/速度 |
| nRoadLimitSpeed | 道路限速 |
| nGoPosDist/Time | 剩余距离/时间 |
| nTBTDist/TurnType | TBT 转弯信息 |
| nSdiType/Dist/SpeedLimit | 电子眼信息 |
| source_last | 数据来源标识 |

### TCP 7709 - 路线点

路线规划成功后发送路线点（WGS-84 坐标），用于 comma3 弯道限速计算。

### HTTP 7000 - 参数读写

| 接口 | 说明 |
|------|------|
| GET /api/params/:name | 读取参数 |
| POST /api/params/:name | 写入参数 |

---

## 依赖技术

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **地图**：MapLibre（OSM 瓦片）、OSMDroid
- **导航 / 搜索**：高德合并 SDK JAR（3D 地图 + 导航 + **搜索** + 定位）、腾讯地图导航 SDK
- **网络**：Kotlin Coroutines、OkHttp
- **依赖注入**：Koin
- **存储**：SharedPreferences + EncryptedSharedPreferences

---

## 构建说明

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

### 本地配置（`local.properties`）

以下内容**勿提交到 Git**（仓库已忽略 `local.properties`）。按需填写：

| 属性 | 说明 |
|------|------|
| `sdk.dir` | Android SDK 路径（Android Studio 可自动生成） |
| `GITHUB_CLIENT_ID` | GitHub OAuth（可选，亦有 `build.gradle` 默认值） |
| `AMAP_WEB_KEY` / `AMAP_WEB_SECRET` | **可选**：仅当需要 **高德 Web 服务 REST** 兜底时使用；须为控制台 **「Web 服务」** 类型 Key。**JS API / 仅浏览器端 Key 调用 `restapi.amap.com` 会返回 `USERKEY_PLAT_NOMATCH`（10009）。** App 内默认搜索走 **高德 Android 搜索 SDK**，使用 **Manifest** 中的 `com.amap.api.v2.apikey`，与 JS/Web Key 无关。 |
| `RELEASE_*` | Release 签名（见 `app/build.gradle.kts`） |

高德 **Android** Key 与 **SHA1 + 包名** 在 [高德控制台](https://console.amap.com/) 绑定，并写入 `app/src/main/AndroidManifest.xml` 的 `com.amap.api.v2.apikey`。

---

## 版本信息

**当前版本**：2.6

**更新历史**：
- 2.6：引导页更新（导航/超车/评分/模型/实验模式）、移除试用功能；OSM 地图搜索改为高德 Android SDK 优先 + 可选 Web REST / 腾讯 / Photon 链路
- 2.5：UI 响应式布局优化（竖屏 2/3 地图 + 1/3 控制面板）
- 2.4：架构重构（协调器模式拆分 MainActivity）
- 2.3：腾讯导航 SDK 集成
- 2.2：驾驶评分系统
- 2.1：LED 点阵屏支持

---

## 协议说明

本项目仅供学习研究使用。高德车机版导航完全免费。腾讯地图 SDK 使用需申请授权。高德地图车机版广播协议基于逆向分析。
#   C P l i n k  
 