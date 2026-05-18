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

### 模块划分

```
MainActivity.kt          # 应用入口（协调器模式）
    ├── MainActivityCore.kt       # 核心业务逻辑 & 状态管理
    ├── MainActivityUI.kt         # Compose UI 组件
    └── MainActivityLifecycle.kt  # 生命周期 & 初始化
```

### 目录结构

```
com.example.carrotamap/
├── MainActivity*.kt           # 入口与协调器
├── CarrotApplication.kt       # Application 初始化
├── CarrotManDataModels.kt     # 数据模型
├── CarrotManNetworkClient.kt  # UDP/TCP 网络通信
├── CarrotParamClient.kt       # HTTP 参数读写
├── NetworkManager.kt          # 网络管理
├── DeviceManager.kt           # 设备管理
├── LocationSensorManager.kt   # 定位传感器
├── AmapBroadcastManager.kt    # 高德广播接收
├── AmapBroadcastHandlers.kt   # 高德数据解析
├── XiaogeDataReceiver.kt      # 设备数据接收
├── AutoOvertakeManager.kt     # 自动超车辅助
├── ConditionalExperimentManager.kt  # 条件实验模式
├── PermissionManager.kt       # 权限管理
├── Constants.kt               # 常量定义
│
├── navigation/
│   ├── OsmNavigationManager.kt    # OSM 导航
│   ├── TencentNavDataBridge.kt     # 腾讯 SDK 桥接
│   └── ...                         # 导航辅助类
│
├── ui/
│   ├── components/
│   │   ├── OsmMapView.kt           # OSM 地图组件（含地点搜索 UI）
│   │   ├── MapSearchService.kt     # 统一地点搜索（高德 SDK/REST、腾讯、Photon 等）
│   │   ├── NavModeSwitcher.kt      # 模式切换器
│   │   ├── ProfilePage.kt          # 个人中心
│   │   ├── OpenpilotHUDPage.kt     # HUD 叠加层
│   │   ├── SSHTerminalPage.kt      # SSH 终端
│   │   ├── LedMatrixManager.kt     # LED 设备管理
│   │   └── ...
│   ├── driving/
│   │   ├── DrivingReportScreen.kt  # 驾驶报告
│   │   └── DrivingReportShareImage.kt
│   ├── discovery/
│   │   ├── CommaDeviceDiscovery.kt
│   │   └── OpenpilotConnectionManager.kt
│   ├── theme/                      # Material 主题
│   └── utils/                      # UI 工具类
│
├── scoring/
│   ├── DrivingScoreEngine.kt       # 评分引擎
│   ├── DrivingDataCollector.kt     # 数据采集
│   └── DrivingSession.kt           # 驾驶会话
│
├── core/                           # 核心工具
├── data/                           # 数据仓库
├── di/                             # Koin 依赖注入
└── utils/                          # 通用工具
```

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