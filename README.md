# Navipilot (CP搭子) - 智能导航辅助应用

<div align="center">

[![Version](https://img.shields.io/badge/version-v260516-blue.svg)](https://github.com/navipilot/CPlink/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-orange.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1-purple.svg)](https://kotlinlang.org)

</div>

## 🌟 项目简介

Navipilot (CP搭子) 是一款专为 **comma3/openpilot** 设备打造的 Android 智能导航辅助应用，实现手机导航与自动驾驶系统的深度融合。

### 核心价值

- **📡 多源导航集成**：支持高德车机版（免费）、高德手机 SDK、Google Navigation SDK、腾讯导航 SDK，满足不同场景需求
- **🚗 自动驾驶辅助**：实时同步导航数据至 openpilot，提供路线规划、转弯提醒、限速信息、电子眼预警
- **🤖 智能超车系统**：基于 ML Kit 的自动超车决策，支持三帧防抖、TBT 方向偏好、车道检测
- **📊 驾驶评分系统**：五维评分引擎（平稳性、预判力、接管依赖、节能、NOO稳定度），生成驾驶报告
- **🎛️ 设备远程管理**：SSH 连接管理、模型下载上传、参数配置、条件实验模式

### 项目信息

- **版本**：v260516 (versionCode: 260516)
- **包名**：`com.example.navipilot`
- **最低要求**：Android 8.0 (API 26)
- **目标平台**：Android 14 (API 35)
- **架构支持**：arm64-v8a（默认），可选 armeabi-v7a

---

## 📋 目录

- [快速开始](#-快速开始)
- [核心功能](#-核心功能)
- [技术架构](#-技术架构)
- [导航模式](#-导航模式)
- [通信协议](#-通信协议)
- [构建说明](#-构建说明)
- [项目结构](#-项目结构)
- [依赖技术](#-依赖技术)
- [贡献指南](#-贡献指南)
- [开源协议](#-开源协议)

---

## 🚀 快速开始

### 前置条件

1. **Android 设备**：Android 8.0+ 手机或平板
2. **comma3 设备**：运行 openpilot 的 comma3 设备（在同一局域网）
3. **导航 App**（可选）：
   - 高德地图车机版（免费，推荐）
   - 或内置高德手机 SDK / Google Navigation SDK

### 安装步骤

1. **下载 APK**：从 [Releases](https://github.com/navipilot/CPlink/releases) 下载最新版本
2. **安装应用**：允许"未知来源"安装
3. **首次启动**：
   - 授予位置、蓝牙、通知等权限
   - 浏览新手引导（5 页）了解核心功能
4. **连接设备**：
   - 应用自动发现局域网内的 comma3 设备（mDNS）
   - 或手动输入设备 IP 地址
5. **开始导航**：
   - 使用高德车机版或内置导航开始导航
   - 应用自动将导航数据发送至 comma3

---

## ✨ 核心功能

### 1. 多源导航系统

支持 **4 种导航模式**，满足不同场景需求：

#### 🗺️ 高德车机版（AMAP）— 默认推荐

**工作原理**：监听高德地图车机版 App 的系统广播，零成本获取导航数据。

**特性**：
- ✅ **完全免费**：无需 API Key 或 SDK 集成
- ✅ **零配置**：安装高德车机版即可使用
- ✅ **数据准确**：官方 App 导航，路况实时
- ✅ **省电节能**：被动接收广播，不消耗额外资源

**限制**：
- ❌ 需要安装高德地图车机版 App
- ❌ 单向通信，无法控制导航行为
- ❌ 坐标需 GCJ-02 → WGS-84 转换

#### 📱 高德手机 SDK（AMAP_MOBILE）

**工作原理**：集成高德导航 SDK（AMapNaviView），提供完整导航体验。

**特性**：
- ✅ **功能完整**：路口大图、车道引导、电子眼播报、实景路口大图
- ✅ **算路策略**：避拥堵、避高速、避收费、高速优先
- ✅ **多路线选择**：同时显示 3 条推荐路线
- ✅ **模拟导航**：Debug 模式支持 5x 速度模拟

**限制**：
- ❌ SDK 体积较大（~50MB）
- ❌ 需要高德开发者 Key（免费）

#### 🌐 Google Navigation SDK

**工作原理**：集成 Google Maps Navigation SDK，使用官方 NavigationView。

**特性**：
- ✅ **国际化**：海外地图数据准确
- ✅ **WGS-84 原生**：无需坐标转换
- ✅ **高精度**：Google 地图精度高
- ✅ **完整功能**：3D 地图、车道引导、实时路况

**限制**：
- ❌ 需要 Google Cloud API Key（有免费额度）
- ❌ 国内地图数据受限

#### 🚀 腾讯导航 SDK（TENCENT）

**工作原理**：集成腾讯导航 SDK，完整实现。

**特性**：
- ✅ **国内优化**：针对中国地图和路况优化
- ✅ **官方支持**：腾讯官方 SDK
- ✅ **功能全面**：实时路况、车道引导、电子眼

**限制**：
- ❌ 需要腾讯开发者授权

---

### 2. 智能超车辅助系统

基于 **ML Kit** 的多模式超车决策引擎。

#### 超车模式

| 模式 | 说明 | 触发方式 |
|------|------|---------|
| **禁止超车**（Mode 0） | 完全关闭超车功能 | - |
| **拨杆超车**（Mode 1） | 用户手动拨杆触发 | 用户操作 |
| **自动超车**（Mode 2） | 系统自动检测并执行 | AI 决策 |

#### 驾驶风格自适应

三种驾驶风格，自动调整超车参数：

| 风格 | 速度差阈值 | 跟车距离 | 冷却时间 |
|------|-----------|---------|---------|
| **保守型** | 15 km/h | 60m | 30s |
| **标准型** | 10 km/h | 50m | 20s |
| **激进型** | 7 km/h | 40m | 10s |

#### 决策流水线

```
1. 先决条件检查（速度、曲率、转向角）
    ↓
2. 前车检测（modelV2.lead0）
    ↓
3. 邻道安全检测（ML Kit 视觉识别）
    ↓
4. 三帧防抖验证（连续 3 帧满足条件）
    ↓
5. TBT 方向偏好（出口避让）
    ↓
6. 2.5s 延迟执行
    ↓
7. 20s 冷却期
```

---

### 3. 驾驶评分系统

**五维评分引擎**，全面评估驾驶水平：

| 维度 | 权重 | 计算依据 |
|------|------|----------|
| **平稳性**（Smoothness） | 30% | 急加速/急刹车/急转弯次数 |
| **预判力**（Prediction） | 25% | 速度波动 + 提前减速行为 |
| **接管依赖**（Intervention） | 20% | 每 100km 接管次数（区分主动/被动） |
| **节能**（Eco） | 15% | 巡航比例 + 速度经济性（60-90km/h 最佳） |
| **NOO 稳定度**（Stability） | 10% | 自动驾驶使用时长/距离占比 |

#### 驾驶风格标签

- 🧘 **平稳型**：急加减速 < 0.3 次/km，速度 50-100km/h
- 🔥 **激进型**：急加减速 > 2 次/km
- 🏙️ **城市型**：平均速度 < 35km/h
- 🛣️ **高速巡航型**：平均速度 > 90km/h
- 🚗 **均衡型**：其他情况

#### 成就系统

8 项成就含进度追踪：

- 🚀 **首次出发**：完成第一次驾驶记录
- 🛣️ **百公里达人**：累计行驶 100 公里
- 🏆 **千里驾驶员**：累计行驶 1000 公里
- 🎯 **稳如泰山**：连续 50km 无接管
- 👑 **NOO 专家**：连续 100km 无接管
- 🧘 **平稳大师**：10 次行程平稳评分 ≥90
- 🤖 **智驾先锋**：NOO 累计 500 公里
- 🌿 **节能达人**：连续 5 次行程节能评分 ≥85

---

### 4. LED 点阵屏支持

**逆向工程 iPixel Color 协议**，实现蓝牙 LED 设备控制。

#### 显示能力

- **像素**：16×16 点阵，支持 CJK 中文字符
- **宽度**：64 像素（最多 4 字符静态显示，超出自动滚动）
- **动画效果**：静态、左/右滚动、呼吸、激光

#### 20 级优先级自动显示引擎

| 优先级 | 触发条件 | 显示内容示例 | 颜色 |
|--------|---------|-------------|------|
| **P1** | 急减速（3 秒降 >8km/h） | ⚠️ `减速` | 红 |
| **P2** | 到达目的地（<50m） | `到达` | 绿 |
| **P3** | 电子眼/测速 | `前方测速 120` | 黄 |
| **P4** | 弯道减速 | `弯道减速` | 橙 |
| **P5** | 急转弯（>60°） | `正在左转` | 蓝 |
| **P6~P15** | TBT 转弯指令（5~300m） | `即将左转 解放路` | 按方向 |
| **P16** | 智驾跟车/巡航 | `智驾巡航` | 绿 |
| **P17** | 地图领航中 | `地图领航` | 紫 |
| **P18** | 车道保持 | `车道保持` | 蓝 |
| **P19** | 接近限速 | `限速120` | 白 |
| **P20** | 默认状态 | `CP搭子` | 青 |

---

### 5. 条件实验模式（CEM）

根据 **7 种驾驶条件** 自动切换 openpilot 实验/Chill 模式：

| 条件 | 说明 | 数据来源 | 推荐状态 |
|------|------|---------|---------|
| 1️⃣ **弯道检测** | ModelV2 曲率 > 阈值（默认 0.05） | TCP 7711 | ✅ 开 |
| 2️⃣ **前车检测** | 前车慢/停止（默认 80m 内） | lead0 数据 | ✅ 开 |
| 3️⃣ **低速条件** | 车速 < 阈值（有/无前车） | carState.vEgo | ✅ 开 |
| 4️⃣ **导航转弯** | TBT 距离 < 阈值（默认 200m） | 7705 JSON | ✅ 开 |
| 5️⃣ **测速点** | 电子眼距离 < 阈值（默认 300m） | 7705 JSON | ⬜ 关 |
| 6️⃣ **驾驶状态** | 停车中/已停车 | 7705 JSON | ⬜ 关 |
| 7️⃣ **巡航调速** | 限速变化时临时切换 | 7705 JSON | ⬜ 关 |

**配置界面**：`AutoSwitchExperimentPage.kt` 提供参数滑块 + 开关控制。

---

### 6. 设备管理与远程控制

#### SSH 连接管理

基于 **SSHJ** 库，提供完整 SSH 能力：

- **连接认证**：支持 RSA/ECDSA 私钥认证
- **远程命令**：`execCommand()` 执行 shell 命令
- **文件传输**：`uploadFile()` SCP 上传模型文件
- **设备控制**：`rebootDevice()` 远程重启 comma3
- **状态管理**：StateFlow 驱动 UI 响应

#### 模型下载与管理

`ModelSwitcherPage.kt` 提供 openpilot 驾驶模型的完整管理：

- **模型清单**：从 GitLab 拉取 JSON（含签名验证）
- **下载管理**：多文件并行下载 + 进度追踪
- **SSH 上传**：通过 SSH 上传至 comma3
- **本地管理**：列表展示已下载/未下载/下载中

#### HTTP 参数配置

`CarrotParamClient.kt` 提供毫秒级参数读写（HTTP 7000）：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/param_set` | POST | 设置参数：`{"name":"ExperimentalMode","value":1}` |
| `/api/params_bulk` | GET | 批量读取：`?names=ExperimentalMode,LongControlMode` |

---

### 7. 其他功能

- **🔍 设备发现**：mDNS/NSD 自动发现 comma3 设备
- **📍 停车位置记录**：自动记录停车坐标，支持步行导航找车
- **📖 新手引导**：首次启动 5 页引导（导航/超车/评分/模型/实验模式）
- **❓ 帮助中心**：内嵌 WebView，一键打开 comma3 Manager（http://deviceIP:7000）
- **🔒 隐私合规**：`PrivacyDialog.kt` 展示隐私声明，符合 GDPR 等法规

---

## 🏗️ 技术架构

### 核心架构模式

**协调器模式 + MVVM + Jetpack Compose**

MainActivity 采用协调器模式拆分，实现关注点分离：

```
MainActivity.kt          # 应用入口（协调器）
├── MainActivityCore.kt       # 核心业务逻辑 & 状态管理（ViewModel 层）
├── MainActivityUI.kt         # Compose UI 组件（View 层）
└── MainActivityLifecycle.kt  # 生命周期 & 初始化管理
```

**优势**：
- ✅ 清晰的关注点分离
- ✅ 可测试性（业务逻辑与 UI 解耦）
- ✅ 生命周期管理独立
- ✅ 支持并行开发

### 数据流架构

#### 主数据流（App → comma3）

```
导航数据源（高德/腾讯/Google）
    ↓ 广播/SDK 回调
广播/SDK 管理器 (AmapBroadcastManager, GoogleNavManager, TencentNaviManager)
    ↓ 更新中央状态
MutableState<CarrotManFields> (单一数据源 SSOT)
    ↓ 订阅状态
├── NetworkManager → CarrotManNetworkClient → UDP 7706 / TCP 7709 → comma3
└── Compose UI（响应式渲染）
```

#### 反向数据流（comma3 → App）

```
comma3 设备 (carrot_server.py)
    ↓ TCP 7711 (JSON 数据包)
XiaogeDataReceiver（解析 + 心跳 + 重连）
    ↓ XiaogeVehicleData
AutoOvertakeManager（ML 决策流水线）
    ↓ 变道指令
ZMQ 7710 → comma3（超车控制命令）
```

### 独特设计模式

#### 1. Channel 背压控制

**问题**：高德广播频率 ~20 Hz → 直接处理会 OOM

**解决方案**：
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

#### 2. 三模式导航互斥

防止多个导航源同时更新状态导致数据冲突：

```kotlin
when (activeNavMode.value) {
    "AMAP" -> processAmapBroadcast(intent)
    "GOOGLE" -> processGoogleNavCallback(event)
    "TENCENT" -> processTencentNavCallback(event)
    else -> return // 跳过
}
```

#### 3. 坐标系统适配器

高德/腾讯使用 GCJ-02，Google 使用 WGS-84。内部统一存储 WGS-84，边界转换：

```kotlin
object CoordinateConverter {
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double>
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double>
}
```

#### 4. 三帧防抖决策

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

## 🗺️ 导航模式

### 模式对比

| 导航模式 | 状态 | 坐标系 | 集成方式 | 成本 | 推荐场景 |
|---------|------|--------|----------|------|---------|
| **AMAP（高德车机版）** | ✅ 生产就绪 | GCJ-02 | 广播接收器 | 🆓 免费 | 日常使用（默认） |
| **AMAP_MOBILE（高德手机 SDK）** | ✅ 生产就绪 | GCJ-02 | AMapNaviView 内嵌 | 🆓 免费 | 完整导航体验 |
| **GOOGLE** | ✅ 生产就绪 | WGS-84 | Google Navigation SDK | 💰 需 API Key | 海外/高精度需求 |
| **TENCENT** | ✅ 完整实现 | GCJ-02 | 腾讯导航 SDK | 💰 需授权 | 国内商业场景 |

### 模式选择建议

| 场景 | 推荐模式 | 理由 |
|------|---------|------|
| **日常通勤（国内）** | AMAP | 免费、稳定、无需配置 |
| **完整导航功能** | AMAP_MOBILE | 路口大图、车道引导 |
| **商业车队管理** | TENCENT | 商业授权、功能全面 |
| **海外使用** | GOOGLE | 国际化、数据准确 |
| **高精度需求** | GOOGLE | WGS-84 原生、无坐标偏移 |

---

## 📡 通信协议

### 端口概览

| 端口/协议 | 方向 | 用途 | 频率 |
|----------|------|------|------|
| **UDP 7706** | → comma3 | 实时导航数据（GPS、限速、TBT、电子眼） | 5 Hz |
| **TCP 7709** | → comma3 | 路线规划成功后的路线点坐标 | 一次性 |
| **TCP 7711** | ← comma3 | 接收设备状态（carState、modelV2、controlsState） | 实时 |
| **HTTP 7000** | ↔ comma3 | 参数读写 REST API | 按需 |
| **ZMQ 7710** | → comma3 | 控制命令（超车变道指令） | 按需 |

### UDP 7706 - 导航数据

**数据包结构**（JSON 格式）：

```json
{
  "carrotIndex": 123,
  "epochTime": 1704067200,
  "timezone": "Asia/Shanghai",
  "goalPosX": 116.397128,
  "goalPosY": 39.916527,
  "szGoalName": "天安门",
  "nRoadLimitSpeed": 60,
  "nTBTDist": 200,
  "nTBTTurnType": 12,
  "szTBTMainText": "左转进入长安街",
  "nSdiType": 1,
  "nSdiSpeedLimit": 80,
  "nSdiDist": 500,
  "source_last": "AMAP"
}
```

**主要字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `vpPosPointLat/Lon` | Double | GPS 坐标（WGS-84） |
| `nRoadLimitSpeed` | Int | 道路限速（km/h） |
| `nTBTDist` | Int | 转弯距离（米） |
| `nTBTTurnType` | Int | 转弯类型（12=左转，13=右转等） |
| `szTBTMainText` | String | 转弯提示文本 |
| `nSdiType` | Int | 电子眼类型（1=测速，2=闯红灯等） |
| `nGoPosDist/Time` | Int | 剩余距离/时间 |

### TCP 7711 - 设备状态接收

**数据包结构**（JSON 格式）：

```json
{
  "data": {
    "carState": {
      "deviceIP": "192.168.43.1",
      "vEgo": 60.5,
      "vCruise": 90.0,
      "enabled": true
    },
    "modelV2": {
      "lead0": {
        "prob": 0.9,
        "x": 50.0,
        "v": 55.0
      },
      "laneLines": [...],
      "pathCurvature": 0.02
    },
    "controlsState": {
      "tbtDist": 200,
      "sdiDist": 500
    }
  }
}
```

**心跳机制**：
- **心跳间隔**：5 秒
- **超时检测**：4 秒无数据触发重连
- **重连策略**：指数退避（2s → 5s → 10s → 20s → 30s max）

---

## 🛠️ 构建说明

### 环境要求

- **JDK**：JDK 11+
- **Android Studio**：Arctic Fox (2020.3.1) 或更高版本
- **Gradle**：7.5+ (使用 Gradle Wrapper)
- **Kotlin**：2.1+

### 构建命令

```bash
# Debug 构建（输出到 app/build/outputs/apk/debug/）
./gradlew assembleDebug

# Release 构建（需在 local.properties 配置签名密钥）
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行 instrumented test
./gradlew connectedAndroidTest

# 清理构建
./gradlew clean

# 代码检查
./gradlew detekt
```

### ABI 配置

默认仅编译 **arm64-v8a**（减少 APK 体积 40-50%），可通过 `navipilot.abis` 属性指定：

```bash
# 仅编译 arm64-v8a（默认）
./gradlew assembleDebug

# 同时编译 armeabi-v7a（增加 APK 体积）
./gradlew assembleDebug -Pnavipilot.abis="arm64-v8a,armeabi-v7a"
```

### 本地配置

创建 `local.properties` 文件（**勿提交到 Git**）：

```properties
# Android SDK 路径（Android Studio 自动生成）
sdk.dir=/path/to/Android/sdk

# 高德 Web 服务 REST API Key（可选，用于搜索兜底）
AMAP_WEB_KEY=your_amap_web_key
AMAP_WEB_SECRET=your_amap_web_secret

# Google Maps API Key（用于 Google Navigation SDK 和 Places API）
MAPS_API_KEY=your_google_maps_api_key

# Release 签名配置（可选）
RELEASE_STORE_PASSWORD=your_keystore_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

**注意事项**：
- **高德 Android Key**：在 `AndroidManifest.xml` 的 `com.amap.api.v2.apikey` 配置，与 SHA1 + 包名绑定
- **高德 Web Key**：可选配置，用于搜索 API 的兜底方案（须为「Web 服务」类型 Key）
- **Google API Key**：在 [Google Cloud Console](https://console.cloud.google.com/) 创建，启用 Maps SDK 和 Places API

---

## 📁 项目结构

### 代码规模统计

| 模块 | 文件数 | 说明 |
|------|--------|------|
| **核心入口** | 7 | MainActivity 协调器拆分 |
| **网络通信** | 5 | UDP/TCP 发送、接收、参数读写 |
| **导航集成** | 8 | 高德/Google/腾讯导航桥接 |
| **决策系统** | 2 | 超车决策、条件实验管理 |
| **UI 组件** | 18 | 导航页面、LED 控制、模型管理等 |
| **数据与存储** | 5 | 偏好设置、SSH、下载管理 |
| **评分系统** | 3 | 五维评分引擎、数据采集 |
| **基础设施** | 6 | DI、加密存储、错误上报 |
| **其他** | 16 | 数据模型、常量、权限管理等 |
| **总计** | **70** | Kotlin 源文件 |

### 核心模块

```
com.example.navipilot/
├── [应用入口与协调]
│   ├── MainActivity.kt                  # 入口协调器
│   ├── MainActivityCore.kt             # 核心业务逻辑
│   ├── MainActivityUI.kt               # Compose UI 主组件
│   ├── MainActivityUIComponents.kt     # UI 组件拆分
│   ├── MainActivityLifecycle.kt        # 生命周期管理
│   ├── CarrotApplication.kt            # Application 初始化（Koin DI）
│   └── CarrotAmapForegroundService.kt  # 前台服务
│
├── [数据模型与状态]
│   ├── CarrotManDataModels.kt          # UDP/TCP 协议数据模型
│   └── CarrotManFields.kt              # 中央状态容器（SSOT）
│
├── [网络通信层]
│   ├── CarrotManNetworkClient.kt       # UDP 7706 + TCP 7709 发送
│   ├── XiaogeDataReceiver.kt           # TCP 7711 设备数据接收
│   ├── CarrotParamClient.kt            # HTTP 7000 参数读写
│   └── NetworkManager.kt               # 网络层统一编排
│
├── [导航集成层] navigation/
│   ├── AmapBroadcastManager.kt         # 高德车机版广播接收
│   ├── AmapBroadcastHandlers.kt        # 高德数据解析器
│   ├── AmapNavDataBridge.kt            # 高德 → CarrotManFields 桥接
│   ├── GoogleNavManager.kt             # Google Navigation SDK 管理
│   ├── GoogleNavDataBridge.kt          # Google → CarrotManFields 桥接
│   ├── TencentNavDataBridge.kt         # 腾讯导航数据桥接
│   ├── CoordinateConverter.kt          # GCJ-02 ↔ WGS-84 转换
│   ├── GeoUtils.kt                     # 地理计算工具
│   └── TurnTypeTextInference.kt        # 转向类型文本推断
│
├── [决策系统层]
│   ├── AutoOvertakeManager.kt          # ML 决策引擎（三帧防抖 + ML Kit）
│   └── ConditionalExperimentManager.kt # 条件实验模式管理器
│
├── [驾驶评分系统] scoring/
│   ├── DrivingScoreEngine.kt           # 五维评分引擎
│   ├── DrivingDataCollector.kt         # 数据采集器
│   └── DrivingSession.kt               # 驾驶会话数据模型
│
├── [UI 组件层] ui/components/
│   ├── GoogleNavPage.kt                # Google NavigationView 嵌入页
│   ├── AmapMobileNavPage.kt            # 高德手机 SDK 导航页
│   ├── TencentNavPage.kt               # 腾讯导航 SDK 页面
│   ├── OsmMapView.kt                   # OSM 地图组件（MapLibre GL）
│   ├── MapSearchService.kt             # 统一地点搜索
│   ├── LedMatrixManager.kt             # LED 点阵屏控制（20 级优先级）
│   ├── LedMatrixPreview.kt             # LED 预览组件
│   ├── ModelSwitcherPage.kt            # openpilot 驾驶模型管理
│   ├── AutoSwitchExperimentPage.kt     # 条件实验模式配置页
│   ├── ProfilePage.kt                  # 个人中心（评分概览）
│   ├── OnboardingScreen.kt             # 新手引导（5 页）
│   ├── HelpPage.kt                     # 帮助中心
│   ├── PrivacyDialog.kt                # 隐私声明对话框
│   └── Carrot7706JsonDebugOverlay.kt   # UDP 7706 数据调试层
│
├── [驾驶报告] ui/driving/
│   ├── DrivingReportScreen.kt          # 驾驶报告界面（五维雷达图）
│   └── DrivingReportShareImage.kt      # 分享图片生成
│
├── [设备发现] ui/discovery/
│   └── CommaDeviceDiscovery.kt         # comma3 设备发现（NSD/mDNS）
│
├── [数据与存储] data/
│   ├── PreferenceRepository.kt         # 偏好设置仓库
│   ├── ModelDownloadManager.kt         # 模型下载管理
│   ├── ModelDownloadState.kt           # 下载状态
│   └── SshConnectionManager.kt         # SSH 连接管理（SSHJ）
│
├── [依赖注入] di/
│   └── AppModule.kt                    # Koin 依赖注入模块
│
└── [其他]
    ├── DeviceManager.kt                # 设备生命周期管理
    ├── LocationSensorManager.kt        # GPS 定位传感器
    ├── PermissionManager.kt            # Android 权限管理
    └── Constants.kt                    # 全局常量定义
```

---

## 🔧 依赖技术

### 核心技术栈

- **语言**：Kotlin 2.1
- **UI 框架**：Jetpack Compose + Material 3
- **异步处理**：Kotlin Coroutines + Flow
- **依赖注入**：Koin 3.5.3
- **网络通信**：OkHttp + Gson
- **消息队列**：ZeroMQ (JeroMQ 0.6.0)

### 地图与导航

- **地图渲染**：MapLibre GL Native 11.8.0（OSM 瓦片）
- **高德地图**：合并 JAR（3D 地图 + 导航 + 搜索 + 定位）
- **Google 导航**：Google Navigation SDK 7.0.0
- **腾讯导航**：腾讯地图导航 SDK 7.5.0

### 数据存储

- **偏好设置**：SharedPreferences + DataStore Preferences
- **加密存储**：EncryptedSharedPreferences
- **文件传输**：SSHJ 0.38.0 + BouncyCastle 1.77

### 机器学习

- **Google ML Kit**：Object Detection（车道检测）

### 其他

- **日志**：Timber 5.0.1
- **媒体播放**：Media3 ExoPlayer 1.2.1
- **测试**：JUnit 5 + MockK 1.13.8 + Google Truth

---

## 🤝 贡献指南

我们欢迎所有形式的贡献！

### 贡献方式

1. **Fork 仓库**：点击右上角 Fork 按钮
2. **创建分支**：`git checkout -b feature/your-feature`
3. **提交代码**：`git commit -m "feat: add your feature"`
4. **推送分支**：`git push origin feature/your-feature`
5. **创建 Pull Request**：提交 PR 到 `main` 分支

### 代码规范

- **Kotlin**：遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- **Commit**：使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式
- **代码检查**：运行 `./gradlew detekt` 检查代码质量

### 提交类型

- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 文档更新
- `style`: 代码格式（不影响功能）
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具链相关

---

## 📄 开源协议

本项目采用 **MIT License** 开源协议。

### 协议说明

- ✅ 允许商业使用
- ✅ 允许修改和分发
- ✅ 允许私有使用
- ⚠️ 需保留版权声明
- ⚠️ 软件按"原样"提供，不提供任何保证

详见 [LICENSE](LICENSE) 文件。

---

## 📮 联系方式

- **Issue**：[GitHub Issues](https://github.com/navipilot/CPlink/issues)
- **讨论区**：[GitHub Discussions](https://github.com/navipilot/CPlink/discussions)
- **邮箱**：support@navipilot.com

---

## 🎯 路线图

### v2.7（规划中）

- [ ] 离线地图支持（MapLibre 离线瓦片）
- [ ] 行程回放功能（GPS 轨迹可视化）
- [ ] 语音播报增强（多语言、自定义 TTS）
- [ ] 夜间模式优化（日出日落自动切换）
- [ ] 多车协同（位置共享、车队路线同步）

### v2.8（未来计划）

- [ ] OBD 数据集成（实时油耗、故障码诊断）
- [ ] 云端数据同步（跨设备同步驾驶记录）
- [ ] 社区功能（路况众包、驾驶技巧分享）
- [ ] API 开放平台（RESTful API + Webhook）

---

## 🙏 致谢

感谢以下开源项目和组织：

- **[comma.ai](https://comma.ai/)**：openpilot 自动驾驶系统
- **[高德地图](https://lbs.amap.com/)**：导航 SDK 和地图服务
- **[Google Maps Platform](https://developers.google.com/maps)**：Navigation SDK
- **[OpenStreetMap](https://www.openstreetmap.org/)**：开源地图数据
- **[MapLibre](https://maplibre.org/)**：开源地图渲染引擎
- **[Kotlin](https://kotlinlang.org/)**：现代化编程语言
- **[Jetpack Compose](https://developer.android.com/jetpack/compose)**：声明式 UI 框架

---

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=navipilot/CPlink&type=Date)](https://star-history.com/#navipilot/CPlink&Date)

---

<div align="center">

**如果这个项目对你有帮助，请给我们一个 ⭐️ Star！**

Made with ❤️ by Navipilot Team

</div>
