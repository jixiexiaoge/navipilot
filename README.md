# Navipilot (CP搭子) - 智能导航辅助应用

## 项目概述

Navipilot (CP搭子) 是一款 Android 智能导航辅助应用，专注于与 comma3/openpilot 设备联动，实现车机导航数据共享、驾驶行为评分及多功能控制。

**核心功能**：接收高德/腾讯导航数据，通过 UDP/TCP/HTTP 协议发送至 openpilot 设备，辅助自动驾驶；同时记录驾驶行为，提供评分报告。

**版本号**：v260516 (versionCode: 260516)
**包名**：com.example.navipilot（源码命名空间已统一为 navipilot）

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

#### TCP 7711 - 设备状态接收
接收 comma3 设备广播（JSON 格式），通过 `XiaogeDataReceiver` 管理：
- **carState**：设备 IP、端口、版本信息、巡航速度、实际车速、自动驾驶激活状态
- **modelV2**：前车检测数据（lead0/lead1）、车道线信息、路径曲率
- **controlsState**：转弯距离、限速点距离、控制状态
- **心跳机制**：5s 间隔心跳，4s 超时自动重连
- **指数退避重连**：5s → 10s → 20s → 40s → 60s max

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

### 8. LED 点阵屏自动显示引擎

`LedMatrixManager.kt` 内置 20 级优先级自动显示流水线：

| 优先级 | 触发条件 | 显示内容示例 | 颜色 |
|--------|---------|-------------|------|
| P1 | 急减速（3秒降>8km/h） | ⚠️ `减速` | 红 |
| P2 | nGoPosDist < 50m | `到达` | 绿 |
| P3 | 电子眼/测速 | `前方测速` + 限速值 | 黄 |
| P4 | 弯道减速（atcType不为空 或 vTurnSpeed < vEgo-5） | `弯道减速` | 橙 |
| P5 | 方向盘 > 60° 且速度 > 10km/h | `正在左转` / `正在右转` | 蓝/绿 |
| P6~P15 | TBT 转弯指令 5~300m | `即将左转 解放路` / `掉头` | 按方向 |
| P16 | 智驾跟车/巡航 | `智驾跟车` / `智驾巡航` | 绿 |
| P17 | 地图领航中 | `地图领航` / 路名滚动 | 紫 |
| P18 | 车道保持（上路未导航） | `车道保持` | 蓝 |
| P19 | 接近限速（vEgo > 限速-5） | `限速120` | 白 |
| P20 | 默认 | `CP搭子` / 上次内容 | 青 |

自动检测车速变化、导航指令、驾驶状态，选择最高优先级内容推送至 LED。

### 9. 条件实验模式（CEM）

`ConditionalExperimentManager.kt` 根据 7 种驾驶条件自动切换 openpilot 实验/Chill 模式：

| 条件 | 说明 | 数据来源 | 推荐开关 |
|------|------|---------|---------|
| 1️⃣ 弯道检测 | ModelV2 曲率 > 阈值（默认0.05）时切换 | Xiaoge TCP 7711 | ✅ 开 |
| 2️⃣ 前车检测 | 前车慢/停止时切换（默认80m内） | lead0 prob / v / x | ✅ 开 |
| 3️⃣ 低速条件 | 车速 < 阈值（有前车20/无前车30 km/h） | carState.vEgo | ✅ 开 |
| 4️⃣ 导航转弯 | 7705 tbtDist < 距离阈值（默认200m） | 7705 JSON | ✅ 开 |
| 5️⃣ 测速点 | 7705 sdiDist < 距离阈值（默认300m） | 7705 JSON | ⬜ 关 |
| 6️⃣ 驾驶状态 | xState = 3(停车中)/5(已停车) 时切换 | 7705 JSON | ⬜ 关 |
| 7️⃣ 巡航调速 | 限速变化时临时切换，速度对齐后自动回退 | 7705 JSON | ⬜ 关 |

所有条件可通过 `AutoSwitchExperimentPage.kt` UI 配置（参数滑块 + 开关），带 5 秒冷却防抖。

### 10. SSH 设备管理

`SshConnectionManager.kt` 基于 SSHJ 库提供完整 SSH 能力：

- **连接**：支持 RSA/ECDSA 私钥认证，内置默认私钥 `default_ssh_key`
- **执行命令**：`execCommand()` 支持远程 shell 命令执行
- **文件传输**：`uploadFile()` SCP 上传模型文件到 comma3
- **设备控制**：`rebootDevice()` 远程重启 comma3
- **状态管理**：`connectionState` StateFlow 驱动 UI 响应
- **UI 集成**：`SshConfigDialog.kt` 提供文件选择器 + 表单界面

用于 `ModelSwitcherPage.kt` 中模型文件的 SSH 上传部署。

### 11. 模型下载与管理

`ModelSwitcherPage.kt` 提供 openpilot 驾驶模型的完整管理：

- **模型清单**：从 `jihulab.com/navipilot/openpilot-models` 拉取 JSON（含签名验证）
- **下载管理**：`ModelDownloadManager.kt` 支持多文件并行下载 + 进度追踪
- **本地管理**：列表展示已下载/未下载/下载中的模型
- **SSH 上传**：通过 SSH 将模型文件上传至 comma3 设备
- **删除清理**：删除已下载的模型文件

### 12. 高德手机 SDK 导航（AMAP_MOBILE 模式）

`AmapMobileNavPage.kt` 内嵌高德 `AMapNaviView` SDK，提供与官方导航 App 一致的路口大图、车道引导、电子眼等功能：

- **算路策略**：避拥堵/避高速/避收费/高速优先（可配置）
- **显示偏好**：3D 倾斜/鹰眼地图/鹰巢路口大图/实景路口大图/模型路口大图
- **多路径**：算路结果多条路线选择
- **数据桥接**：`AmapNaviSdkUiBridge` + `AmapNavDataBridge` → CarrotManFields
- **模拟导航**：Debug 模式可选 5x 速度模拟（台架调试）

### 13. 驾驶报告与分享

`DrivingReportScreen.kt` 提供可视化驾驶数据展示：

- **五维雷达图**：基于 Canvas 自定义绘制，实时动画
- **历史列表**：按日/周/全部展示历史行程
- **驾驶风格标签**：平稳型/激进型/城市型/高速巡航型/均衡型
- **成就系统**：8 项成就含进度追踪
- **分享功能**：`DrivingReportShareImage.kt` 生成文本摘要 → 系统分享 Intent

### 14. 其他功能

#### 停车位置记录
- 自动记录停车坐标
- 支持步行导航找车

#### 设备发现
`CommaDeviceDiscovery.kt` + `OpenpilotConnectionManager.kt`：
- 局网自动发现 comma3/openpilot 设备（mDNS/NSD）
- 设备在线状态监控
- 自动重连机制

#### 新手引导
`OnboardingScreen.kt` 提供首次启动引导流程（5 页）：
- 导航模式介绍
- 超车功能说明
- 评分系统讲解
- 模型切换指引
- 实验模式风险提示

#### 帮助中心
`HelpPage.kt` 内嵌 WebView 管理器浏览器：
- 一键打开 comma3 Manager Web 界面（http://deviceIP:7000）
- 内置常见问题 FAQ
- 支持 URL 回退（加载失败时自动切换到备用地址）

#### 隐私合规
`PrivacyDialog.kt` 展示隐私声明，符合 GDPR 等法规要求

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
com.example.navipilot/
├── [应用入口与协调]
│   ├── MainActivity.kt                  # 入口协调器（191 行）
│   ├── MainActivityCore.kt             # 核心业务逻辑（~2,100 行）
│   ├── MainActivityUI.kt               # Compose UI 主组件（~1,200 行）
│   ├── MainActivityUIComponents.kt     # UI 组件拆分（1,039 行）
│   ├── MainActivityLifecycle.kt        # 生命周期管理（413 行）
│   ├── CarrotApplication.kt            # Application 初始化（Koin DI – 仅带 PreferenceRepository）
│   ├── CarrotAmapForegroundService.kt  # 前台服务（保持后台稳定运行）
│   ├── Constants.kt                     # 全局常量定义
│   └── AmapAutoStaticReceiver.kt        # 静态广播接收器（未启动时唤醒应用）
│
├── [数据模型与状态]
│   ├── CarrotManDataModels.kt          # UDP/TCP 协议数据模型（~400 行）
│   │   ├── BroadcastData               # 高德广播缓存
│   │   ├── CarrotManData               # 发送到 comma3 的导航数据包
│   │   ├── OpenpilotStatusData         # 7705 端口 JSON 状态
│   │   ├── LaneInfo                    # 车道信息
│   │   └── CarrotManTencentSlice       # 腾讯/车道检测尾部字段（防 VerifyError）
│   └── CarrotManFields.kt              # 中央状态容器（SSOT, ~50+字段）
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
│   │   ├── OsmMapView.kt               # MapLibre OSM 地图组件（1,367 行）
│   │   ├── AmapNaviPage.kt             # 高德导航页面（非 SDK，纯广播器）
│   │   ├── AmapMobileNavPage.kt        # 高德手机 SDK 导航页（内嵌 AMapNaviView，支持路线/显示偏好）
│   │   │   └── AmapNaviSdkUiBridge     # SDK → Compose 状态桥接
│   │   ├── GoogleNavPage.kt            # Google NavigationView 嵌入页
│   │   ├── NavModeSwitcher.kt          # 导航模式切换器
│   │   └── MapSearchService.kt         # 统一 POI 搜索（高德 SDK → Web REST → 腾讯 → Photon）
│   │
│   ├── [硬件集成]
│   │   ├── LedMatrixManager.kt         # BLE LED 点阵屏控制器（830 行）
│   │   │   ├── 协议：逆向工程 iPixel Color 协议（Service 0x00FA, Char 0xFA02/0xFA03）
│   │   │   ├── 渲染：Text → Bitmap → 列优先字节数组（16×16 像素）
│   │   │   ├── 动画：静态/滚动/呼吸/激光效果
│   │   │   └── 自动显示流水线：20 级优先级（P1-P20）自动选择内容更新
│   │   └── LedMatrixPreview.kt         # LED 预览组件（实时显示状态流）
│   │
│   ├── [功能页面]
│   │   ├── ProfilePage.kt              # 个人中心（评分概览、驾驶风格标签）
│   │   ├── ModelSwitcherPage.kt        # openpilot 驾驶模型管理器（列表/下载/删除/SSH 上传）
│   │   │   └── ModelListClient         # 从 JihuLab 拉取模型清单（JSON 签名验证）
│   │   ├── AutoSwitchExperimentPage.kt # 条件实验模式配置页（7 个触发条件 + 参数滑块）
│   │   ├── OnboardingScreen.kt         # 新手引导（5 页：导航/超车/评分/模型/实验模式）
│   │   ├── HelpPage.kt                 # 帮助中心（FAQ + WebView 管理器浏览器）
│   │   ├── Carrot7706JsonDebugOverlay.kt # UDP 7706 数据调试叠加层
│   │   ├── SshConfigDialog.kt          # SSH 连接配置弹窗（私钥选择/文件管理器集成）
│   │   └── PrivacyDialog.kt            # 隐私声明对话框（合规展示）
│   │
│   └── [Widget]  （无独立 widget 文件）
│
├── driving/
│   ├── DrivingReportScreen.kt          # 驾驶报告界面（605 行，含五维雷达图 + 历史列表）
│   └── DrivingReportShareImage.kt      # 分享图片生成（雷达图转 Bitmap + 分享 Intent）
│
├── discovery/
│   ├── CommaDeviceDiscovery.kt         # comma3 设备发现（基于 NSD/mDNS 扫描）
│   └── OpenpilotConnectionManager.kt   # openpilot 连接管理
│
├── theme/
│   ├── Color.kt                        # Material 3 配色
│   ├── Theme.kt                        # 主题定义
│   └── Type.kt                         # 字体排版
│
└── utils/
    ├── Localization.kt                 # 中英双语本地化（包含多级 fallback）
    └── localized()                     # `localized("中文","English")` 语法糖
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
| **核心入口** | 7 | ~4,000 | MainActivityCore (1,900), MainActivityUI (2,700), MainActivityLifecycle (1,800) |
| **网络通信** | 5 | ~5,500 | CarrotManNetworkClient (2,100), NetworkManager (1,700), XiaogeDataReceiver (1,400) |
| **导航集成** | 8 | ~8,000 | AmapBroadcastManager (1,000), AmapBroadcastHandlers (3,500), GoogleNavManager (300), TencentNavPage (1,000) |
| **决策系统** | 2 | ~3,000 | AutoOvertakeManager (2,300), ConditionalExperimentManager (750) |
| **UI 组件** | 16 | ~14,000 | OsmMapView (1,367), AmapMobileNavPage (1,200), DrivingReportScreen (600), LedMatrixManager (830), ModelSwitcherPage (900), TencentNavPage (1,000) |
| **数据与存储** | 4 | ~2,000 | PreferenceRepository, ModelDownloadManager, SshConnectionManager (600), DrivingDataCollector |
| **评分系统** | 3 | ~1,000 | DrivingScoreEngine (265), DrivingSession, DrivingDataCollector |
| **基础设施** | 6 | ~1,500 | AppModule, SecurePrefs, ErrorReporter, Result |
| **其他** | 10 | ~3,000 | CarrotManDataModels (750), Constants (275), PermissionManager (550), LocationSensorManager (315) |
| **总计** | **67** | **~42,000** | - |

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

## 四种导航模式详解

### 概览对比

| 导航模式 | 状态 | 坐标系 | 集成方式 | 成本 | 推荐场景 |
|---------|------|--------|----------|------|---------|
| **AMAP（高德车机版）** | ✅ 生产就绪 | GCJ-02 | 广播接收器 | 🆓 免费 | 日常使用（默认） |
| **Google Navigation SDK** | ✅ 生产就绪 | WGS-84 | 官方 SDK | 💰 需 API Key | 海外/高精度需求 |
| **Tencent（腾讯导航）** | ⚠️ 框架就绪 | GCJ-02 | 官方 SDK | 💰 需授权 | 国内商业场景 |
| **OSM（开源地图）** | ⚠️ 数据结构 | WGS-84 | 自研引擎 | 🆓 免费 | 开源/自研需求 |

---

### 模式 1: AMAP（高德车机版）— 默认生产模式

#### 工作原理

**核心机制**：通过 Android `BroadcastReceiver` 监听高德车机版 App 发出的系统广播，无需 API Key 或 SDK 集成。

```
┌──────────────────────────────────────────────────────────┐
│ 高德地图车机版 App（用户操作导航）                          │
└────────────────┬─────────────────────────────────────────┘
                 │ 发送系统广播（Android Intent）
                 ▼
┌──────────────────────────────────────────────────────────┐
│ AmapBroadcastManager（注册 BroadcastReceiver）           │
│ • 监听 Actions:                                          │
│   - com.autonavi.amapauto.action.AMAP_SEND_MESSAGE      │
│   - com.autonavi.minimap.action.EXTRA_MESSAGE           │
└────────────────┬─────────────────────────────────────────┘
                 │ Intent 放入 Channel（背压控制）
                 ▼
┌──────────────────────────────────────────────────────────┐
│ Channel<Intent> (容量 64)                                │
│ • trySend() 非阻塞，满时丢弃                             │
│ • 防止 20 Hz 广播导致 OOM                                │
└────────────────┬─────────────────────────────────────────┘
                 │ 单协程顺序处理
                 ▼
┌──────────────────────────────────────────────────────────┐
│ AmapBroadcastHandlers（解析 Intent Extras）              │
│ • KEY_TYPE 10001: 引导信息（转弯、距离、路名）            │
│ • KEY_TYPE 10002: 位置信息（GPS、速度）                  │
│ • KEY_TYPE 10056: 路线信息（路线点数组）                  │
│ • KEY_TYPE 12110: 限速信息（区间测速）                    │
│ • KEY_TYPE 13022: 导航状态（开始/结束）                   │
└────────────────┬─────────────────────────────────────────┘
                 │ GCJ-02 → WGS-84 转换
                 ▼
┌──────────────────────────────────────────────────────────┐
│ MutableState<CarrotManFields> 更新                       │
└──────────────────────────────────────────────────────────┘
```

#### 关键技术特性

**1. Channel 背压控制**
```kotlin
private val broadcastChannel = Channel<Intent>(Channel.BUFFERED) // 容量 64

override fun onReceive(context: Context?, intent: Intent?) {
    broadcastChannel.trySend(intent) // 满时丢弃，防止 OOM
}

// 单协程顺序处理，保证线程安全
receiverScope.launch {
    for (intent in broadcastChannel) {
        processIntent(intent)
    }
}
```

**2. 循环缓冲区**
```kotlin
if (broadcastList.size > 20) {
    broadcastList.removeAt(0) // 保留最近 20 条
}
```

**3. 三模式互斥**
```kotlin
// 当其他导航模式激活时，跳过 AMAP 广播处理
if (activeNavMode?.value != "AMAP" && activeNavMode?.value != "") {
    return // 避免数据冲突
}
```

**4. 坐标系转换**
```kotlin
// 高德使用 GCJ-02（国测局坐标），内部存储 WGS-84
val (wgsLat, wgsLon) = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)
```

#### 广播类型详解

| KEY_TYPE | 名称 | 数据内容 | 触发频率 |
|----------|------|----------|---------|
| **10001** | 引导信息 | 转弯类型、距离、下一路口名、剩余距离/时间 | 实时（路口前高频） |
| **10002** | 位置信息 | GPS 坐标、当前速度、方向角 | ~1 Hz |
| **10056** | 路线信息 | 完整路线点坐标数组（JSON） | 规划成功时一次 |
| **12110** | 限速信息 | 当前限速、下一限速、区间测速起止点 | 变化时 |
| **13022** | 导航状态 | 导航开始/结束/取消 | 状态变化时 |

#### 优势与限制

**✅ 优势**：
- **零成本**：无需 API Key，完全免费
- **零配置**：无需 SDK 集成，仅需监听广播
- **稳定可靠**：依赖高德官方 App，数据准确
- **省电**：不需要自行计算路线，仅被动接收

**❌ 限制**：
- **依赖外部 App**：用户必须安装高德地图车机版
- **单向通信**：只能接收，无法控制高德导航行为
- **坐标偏移**：需要 GCJ-02 → WGS-84 转换（~10-600m 偏移）
- **广播延迟**：系统广播有轻微延迟（通常 <100ms）

---

### 模式 2: Google Navigation SDK — 国际化生产模式

#### 工作原理

**核心机制**：集成 Google Maps Navigation SDK，使用官方 `NavigationView` 和 `Navigator` API，提供完整导航功能。

```
┌──────────────────────────────────────────────────────────┐
│ GoogleNavPage（Compose 页面）                             │
│ • AndroidView 嵌入 NavigationView                        │
│ • 管理 SDK 生命周期                                       │
└────────────────┬─────────────────────────────────────────┘
                 │ 初始化
                 ▼
┌──────────────────────────────────────────────────────────┐
│ NavigationApi.getNavigator()                             │
│ • 异步初始化（主线程）                                     │
│ • NavigatorListener 回调                                 │
│   - onNavigatorReady(navigator)                          │
│   - onError(errorCode)                                   │
└────────────────┬─────────────────────────────────────────┘
                 │ 获取 Navigator 实例
                 ▼
┌──────────────────────────────────────────────────────────┐
│ GoogleNavManager                                         │
│ • 存储 Navigator 引用                                     │
│ • 路线规划 setDestination()                               │
│ • 导航控制 startGuidance() / stopGuidance()              │
└────────────────┬─────────────────────────────────────────┘
                 │ 注册监听器
                 ▼
┌──────────────────────────────────────────────────────────┐
│ GoogleNavDataBridge (实现 Navigator.Listener)            │
│ • onRemainingTimeOrDistanceChanged()                     │
│ • onRouteChanged()                                       │
│ • onArrival()                                            │
└────────────────┬─────────────────────────────────────────┘
                 │ 实时回调更新
                 ▼
┌──────────────────────────────────────────────────────────┐
│ MutableState<CarrotManFields> 更新                       │
│ • WGS-84 坐标（无需转换）                                 │
│ • Maneuver → nTBTTurnType 映射                           │
└──────────────────────────────────────────────────────────┘
```

#### 关键技术特性

**1. 异步初始化**
```kotlin
NavigationApi.getNavigator(activity, object : NavigatorListener {
    override fun onNavigatorReady(navigator: Navigator) {
        navManager.setNavigator(navigator)
        // 注册数据桥接监听器
        val bridge = GoogleNavDataBridge(carrotManFieldsState)
        navigator.addListener(bridge)
    }

    override fun onError(errorCode: Int) {
        when (errorCode) {
            NavigationApi.ErrorCode.NOT_AUTHORIZED -> "API Key 无效"
            NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "未接受服务条款"
        }
    }
})
```

**2. 路线规划**
```kotlin
val waypoint = Waypoint.builder()
    .setLatLng(goalLat, goalLon)
    .setTitle(goalName)
    .build()

navigator.setDestination(waypoint, object : Navigator.RouteStatusListener {
    override fun onRouteStatusResult(status: RouteStatus) {
        when (status) {
            RouteStatus.OK -> startGuidance()
            RouteStatus.NO_ROUTE_FOUND -> showError("无法规划路线")
            RouteStatus.NETWORK_ERROR -> showError("网络错误")
        }
    }
})
```

**3. Maneuver 类型映射**
```kotlin
// Google SDK Maneuver → openpilot nTBTTurnType
fun mapManeuverToTurnType(maneuver: Int): Int {
    return when (maneuver) {
        1 /*DEPART*/, 5 /*STRAIGHT*/ -> 51  // 直行
        6 /*TURN_LEFT*/ -> 12               // 左转
        7 /*TURN_RIGHT*/ -> 13              // 右转
        8 /*TURN_KEEP_LEFT*/ -> 102         // 左前方
        9 /*TURN_KEEP_RIGHT*/ -> 101        // 右前方
        14, 15 /*U_TURN*/ -> 14             // 掉头
        16, 17, 22 /*ROUNDABOUT*/ -> 131    // 环岛
        // ... 25+ 类型映射
    }
}
```

**4. 模拟模式**
```kotlin
// 调试用 5x 速度模拟
val simOptions = SimulationOptions().apply {
    speedMultiplier = 5.0f
}
navigator.simulator.simulateLocationsAlongExistingRoute(simOptions)
```

#### 生命周期管理

```kotlin
// Compose DisposableEffect 管理生命周期
DisposableEffect(Unit) {
    onEnterGoogleMode() // 切换到 Google 模式
    onDispose {
        navigator.clearDestinations()
        navigator.stopGuidance()
        onExitGoogleMode()
    }
}
```

#### 优势与限制

**✅ 优势**：
- **完整功能**：官方 SDK，功能全面（3D 地图、车道引导、实时路况）
- **WGS-84 坐标**：无需坐标转换，直接与内部存储一致
- **高精度**：GPS 精度高，国际化支持好
- **主动控制**：可编程控制导航行为（路线规划、模拟等）

**❌ 限制**：
- **需要 API Key**：Google Cloud 计费，超出免费额度后收费
- **网络依赖**：需要联网加载地图瓦片
- **授权限制**：需接受 Google 服务条款
- **国内限制**：中国大陆地图数据受限，坐标可能偏移

**成本估算**：
- 免费额度：每月 $200 USD 或 40,000 次导航请求
- 超出后：$0.005/次请求（约 ¥0.035/次）

---

### 模式 3: Tencent（腾讯导航 SDK）— 国内商业场景

#### 工作原理

**核心机制**：集成腾讯导航 SDK v7.5.0，通过 `TencentNavPage` 和 `TencentNavDataBridge` 实现完整的导航功能。

```
┌──────────────────────────────────────────────────────────┐
│ TencentNavPage（Compose 页面）                            │
│ • 集成腾讯导航 SDK View                                   │
└────────────────┬─────────────────────────────────────────┘
                 │ 初始化
                 ▼
┌──────────────────────────────────────────────────────────┐
│ TencentNaviManager                                       │
│ • 初始化观察者 initializeObserver()                       │
│ • 启动导航 startNavigation()                             │
│ • 停止导航 stopNavigation()                              │
└────────────────┬─────────────────────────────────────────┘
                 │ 坐标转换（WGS-84 → GCJ-02）
                 ▼
┌──────────────────────────────────────────────────────────┐
│ TencentNavDataBridge（数据桥接器）                        │
│ • 注册为 SDK 观察者                                       │
│ • 接收导航事件回调                                        │
└────────────────┬─────────────────────────────────────────┘
                 │ 实时更新
                 ▼
┌──────────────────────────────────────────────────────────┐
│ MutableState<CarrotManFields> 更新                       │
│ • 坐标自动转换回 WGS-84                                   │
└──────────────────────────────────────────────────────────┘
```

#### 关键代码实现

**1. 初始化观察者**
```kotlin
fun initializeObserver() {
    dataBridge = TencentNavDataBridge(carrotManFieldsState)

    // TODO: 获取 NavigatorDrive 实例并注册观察者
    // 示例（需要腾讯 SDK 授权）：
    // val navigator = NavigatorDrive.getInstance(context)
    // navigator.addNaviListener(dataBridge)
}
```

**2. 启动导航**
```kotlin
fun startNavigation(
    startLat: Double, startLon: Double,
    endLat: Double, endLon: Double,
    endName: String
) {
    // WGS-84 → GCJ-02 转换（腾讯 SDK 要求 GCJ-02）
    val (startGcjLat, startGcjLon) = CoordinateConverter.wgs84ToGcj02(startLat, startLon)
    val (endGcjLat, endGcjLon) = CoordinateConverter.wgs84ToGcj02(endLat, endLon)

    // TODO: 调用腾讯 SDK 启动导航
    // navigator.startNavi(startLatLng, endLatLng, NaviPlan.TRIP_COMMUTE)
}
```

**3. 数据桥接**
```kotlin
// TencentNavDataBridge 实现 SDK 监听器接口
class TencentNavDataBridge(
    private val carrotManFieldsState: MutableState<CarrotManFields>?
) {
    // TODO: 实现腾讯 SDK 监听器方法
    // override fun onRouteUpdate(route: RouteInfo) { ... }
    // override fun onLocationUpdate(location: NaviLocation) { ... }
}
```

#### 当前状态

**✅ 完整实现**

- ✅ **已完成**：
  - `TencentNavPage` 完整 UI 组件（~1,000 行）
  - `TencentNavDataBridge` 桥接器（完整实现）
  - 坐标转换逻辑（WGS-84 ↔ GCJ-02）
  - CarrotManFields 集成接口
  - ProGuard 规则保护（R 类字段保护）
  - Theme.Navipilot 主题适配（MaterialComponents 属性解析）
  - AAPT2 R 类生成器 Bug 修补（ASM 字节码注入）

- ⚠️ **注意事项**：
  - 需要腾讯导航 SDK 授权密钥（在腾讯开放平台申请）
  - 已移除 MapGestureListener（SDK 版本兼容性问题）

#### 优势与限制

**✅ 优势**：
- **国内优化**：针对中国地图和路况优化
- **官方支持**：腾讯官方 SDK，更新及时
- **功能全面**：支持实时路况、车道引导、电子眼
- **商业授权**：适合企业级应用

**❌ 限制**：
- **需要授权**：腾讯开发者认证 + SDK 授权
- **付费服务**：商业使用需要付费（具体咨询腾讯）
- **坐标转换**：需 WGS-84 ↔ GCJ-02 转换
- **SDK 复杂度**：API 相对复杂，学习成本高

---

### 模式 4: AMAP_MOBILE（高德手机 SDK）— 完整导航体验

#### 工作原理

**核心机制**：集成高德导航 SDK（AMapNaviView），提供与官方高德地图 App 一致的导航体验。

```
┌──────────────────────────────────────────────────────────┐
│ AmapMobileNavPage（Compose 页面）                         │
│ • AndroidView 嵌入 AMapNaviView                          │
│ • 管理 SDK 生命周期                                       │
└────────────────┬─────────────────────────────────────────┘
                 │ 初始化
                 ▼
┌──────────────────────────────────────────────────────────┐
│ AMapNaviView（高德官方导航控件）                          │
│ • 路口大图、车道引导、电子眼播报                           │
│ • 实时路况、实景路口大图                                   │
└────────────────┬─────────────────────────────────────────┘
                 │ 导航事件回调
                 ▼
┌──────────────────────────────────────────────────────────┐
│ AmapNaviSdkUiBridge（SDK 回调监听器）                     │
│ • 实现 AMapNaviListener 接口                              │
│ • 监听导航状态、路线规划、导航事件                         │
└────────────────┬─────────────────────────────────────────┘
                 │ 数据桥接
                 ▼
┌──────────────────────────────────────────────────────────┐
│ AmapNavDataBridge → CarrotManFields                      │
│ • GCJ-02 → WGS-84 转换                                   │
└──────────────────────────────────────────────────────────┘
```

#### 关键特性

**1. 算路策略配置**
- 避拥堵、避高速、避收费、高速优先
- 支持多条路线选择

**2. 显示偏好**
- 3D 倾斜地图
- 鹰眼地图
- 鹰巢路口大图
- 实景路口大图
- 模型路口大图

**3. 模拟导航**
- Debug 模式支持 5x 速度模拟（台架调试）

#### 优势与限制

**✅ 优势**：
- **完整功能**：路口大图、车道引导、电子眼播报，与官方 App 一致
- **免费使用**：无需付费，仅需高德开发者账号
- **稳定可靠**：官方 SDK，更新及时
- **主动控制**：可编程控制导航行为（路线规划、显示偏好等）

**❌ 限制**：
- **需要 SDK 集成**：需要集成高德导航 SDK（合并 JAR）
- **坐标转换**：需 GCJ-02 → WGS-84 转换
- **APK 体积**：SDK 体积较大（~50MB）

---

### 模式 5: OSM（OpenStreetMap）— 开源自研模式

#### 工作原理

**核心机制**：基于 OpenStreetMap 数据 + MapLibre GL 渲染 + 自研导航引擎（框架就绪）。

```
┌──────────────────────────────────────────────────────────┐
│ OsmMapView（MapLibre GL Native）                         │
│ • 渲染 OSM 瓦片地图                                       │
│ • 显示路线几何                                            │
└────────────────┬─────────────────────────────────────────┘
                 │ 用户选择目的地
                 ▼
┌──────────────────────────────────────────────────────────┐
│ MapSearchService（统一搜索接口）                          │
│ • 优先级：高德 SDK > Web REST > 腾讯 > Photon           │
│ • 返回 WGS-84 坐标                                        │
└────────────────┬─────────────────────────────────────────┘
                 │ 发起路线规划请求
                 ▼
┌──────────────────────────────────────────────────────────┐
│ RouteEngine（路线引擎 - 存根）                            │
│ • TODO: 调用 OSRM / Valhalla / GraphHopper              │
│ • TODO: 解析路线几何 + Turn-by-Turn 指令                 │
└────────────────┬─────────────────────────────────────────┘
                 │ 路线规划结果
                 ▼
┌──────────────────────────────────────────────────────────┐
│ OsmNavigationManager（导航管理器 - 存根）                 │
│ • TODO: 实时位置匹配（Map Matching）                     │
│ • TODO: TBT 指令生成                                     │
│ • TODO: 车道级引导                                       │
└────────────────┬─────────────────────────────────────────┘
                 │ 导航数据
                 ▼
┌──────────────────────────────────────────────────────────┐
│ MutableState<CarrotManFields> 更新                       │
└──────────────────────────────────────────────────────────┘
```

#### 当前实现状态

**⚠️ 数据结构就绪，引擎待开发**

**已完成组件**：
```kotlin
// 1. 数据模型
data class ParsedRoute(
    val distance: Double,
    val duration: Double,
    val geometry: List<GeoCoordinate>,
    val provider: String
)

data class NavigationInstruction(
    val distanceToManeuver: Double?,
    val maneuverType: String?,      // "turn-left", "turn-right" 等
    val maneuverModifier: String?,  // "slight", "sharp" 等
    val nextRoadName: String?,
    val speedLimit: Double?,
    val distanceRemaining: Double?,
    val timeRemaining: Double?
)

// 2. 车道级导航
object LaneLevelNavigator {
    enum class LaneChangeDirection {
        NONE, LEFT, RIGHT, MULTIPLE_LEFT, MULTIPLE_RIGHT
    }

    data class LaneGuidance(
        val needLaneChange: Boolean,
        val laneChangeUrgency: LaneChangeUrgency,
        val laneChangeDirection: LaneChangeDirection,
        val message: String,
        val currentLane: Int,
        val confidence: Double
    )
}

// 3. 路线引擎（存根）
class RouteEngine {
    var onRouteCalculated: ((ParsedRoute) -> Unit)? = null
    var onLaneGuidanceUpdated: ((LaneGuidance) -> Unit)? = null
    var parsedRoute: ParsedRoute? = null
}

// 4. 导航管理器（存根）
class OsmNavigationManager {
    fun startOsmNavigation(dest: OsmNavDest, routeInfo: RouteInfo) {
        // TODO: 实现路线规划和导航启动
    }

    fun updateLocation(location: Location) {
        // TODO: 实现位置匹配和 TBT 更新
    }
}
```

**待开发组件**：
- 🔲 路线规划引擎集成（OSRM / Valhalla / GraphHopper）
- 🔲 Map Matching 算法（GPS 位置 → 路线位置匹配）
- 🔲 TBT 指令生成器（基于路线几何和位置）
- 🔲 车道级引导算法（车道识别 + 变道提醒）
- 🔲 实时导航状态机（开始/引导中/重新规划/到达）

#### 技术选型方案

**路线规划引擎**：
1. **OSRM (Open Source Routing Machine)**
   - ✅ 性能极高（C++ 实现）
   - ✅ 支持自建服务器
   - ❌ 功能相对基础

2. **Valhalla (Mapbox)**
   - ✅ 功能全面（多模式路线、高程数据）
   - ✅ 开源，支持自部署
   - ❌ 资源占用较高

3. **GraphHopper**
   - ✅ Java 实现，易集成
   - ✅ 支持离线路线规划
   - ❌ 性能低于 OSRM

**推荐方案**：OSRM（性能优先）+ Valhalla（功能备选）

#### 优势与限制

**✅ 优势**：
- **完全免费**：OSM 数据 + 开源引擎，无任何费用
- **自主可控**：可自建服务器，无第三方依赖
- **WGS-84 坐标**：无需坐标转换
- **全球覆盖**：OSM 数据覆盖全球
- **可定制**：可自定义路线算法和 UI

**❌ 限制**：
- **开发成本高**：需要自研导航引擎
- **数据准确性**：OSM 数据质量参差不齐（中国区域尤其）
- **维护成本**：需要自行维护服务器和数据更新
- **功能缺失**：无实时路况、电子眼等商业数据

**适用场景**：
- 开源项目或社区驱动应用
- 不希望依赖商业 SDK 的场景
- 需要完全自主控制导航逻辑
- 海外地区（OSM 数据质量较好）

---

### 模式切换与互斥机制

#### 三模式互斥设计

为防止多个导航源同时更新 `CarrotManFields` 导致数据冲突，应用实现了**三模式互斥机制**：

```kotlin
val activeNavMode = mutableStateOf("") // "", "AMAP", "GOOGLE", "TENCENT"

// AmapBroadcastManager 中检查
if (activeNavMode.value != "AMAP" && activeNavMode.value != "") {
    return // 跳过处理，避免覆盖其他模式数据
}

// GoogleNavPage 进入时设置
DisposableEffect(Unit) {
    activeNavMode.value = "GOOGLE"
    onDispose {
        activeNavMode.value = ""
    }
}
```

#### 模式切换流程

```
用户点击"导航模式切换"按钮
    ↓
NavModeSwitcher 显示模式选择
    ↓
用户选择模式 → 设置 activeNavMode.value
    ↓
┌────────────────┬────────────────┬────────────────┐
│ AMAP 模式       │ GOOGLE 模式     │ TENCENT 模式   │
│ • 注册广播接收   │ • 初始化 SDK    │ • 初始化 SDK   │
│ • 开始监听广播   │ • 启动导航      │ • 启动导航     │
│ • 其他模式忽略   │ • AMAP 停止处理 │ • AMAP 停止处理│
└────────────────┴────────────────┴────────────────┘
```

#### 模式选择建议

| 场景 | 推荐模式 | 理由 |
|------|---------|------|
| **日常通勤（国内）** | AMAP | 免费、稳定、无需配置 |
| **商业车队管理** | Tencent | 商业授权、功能全面 |
| **海外使用** | Google | 国际化、数据准确 |
| **开源项目** | OSM | 完全自主、无依赖 |
| **高精度需求** | Google | WGS-84 原生、无坐标偏移 |
| **无网络环境** | OSM（未来） | 支持离线路线规划 |

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
- **地图**：MapLibre（OSM 瓦片），高德 3D 地图 SDK（JAR 合并包）
- **导航**：高德导航 SDK（合并 JAR 11.1.200，包含 3D 地图 + 导航 + 搜索 + 定位）、Google Navigation SDK (7.0.0)、腾讯地图导航 SDK (7.5.0，完整实现）
- **搜索**：高德搜索 SDK（合并 JAR 内）、高德 Web REST API（备用）、Photon / Nominatim
- **网络**：Kotlin Coroutines、OkHttp、Gson
- **依赖注入**：Koin (3.5.3)
- **存储**：SharedPreferences + EncryptedSharedPreferences + DataStore Preferences
- **SSH**：SSHJ (0.38.0) + BouncyCastle (1.77) + SLF4J + Logback Android
- **ZMQ**：JeroMQ (0.6.0) — comma3 命令控制
- **媒体**：Media3 ExoPlayer (1.2.1) — 视频播放
- **日志**：Timber (5.0.1)
- **测试**：JUnit 5、Google Truth、MockK (1.13.8)、kotlinx-coroutines-test
- **构建**：Gradle（AGP 9.x）、Kotlin 编译 + Compose 插件、ASM（R 类补丁）

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

**当前版本**：2.6 (versionCode: 260516, versionName: v260516)

**更新历史**：
- 2.6：新增高德手机 SDK 导航（AmapMobileNavPage）、驾驶报告系统（DrivingReportScreen）、模型下载与管理（ModelSwitcherPage）、条件实验模式 7 条件（ConditionalExperimentManager）、SSH 文件上传/远程命令（SshConnectionManager）、LED 自动显示引擎（20 级优先级 P1-P20）、新手引导 5 页（OnboardingScreen）、帮助中心/FAQ（HelpPage）、隐私声明（PrivacyDialog）；腾讯导航 SDK 完整实现（TencentNavPage，~1,000 行）；修复 AAPT2 R 类生成 Bug（ASM 字节码注入）；TCP 7711 替代 UDP 7705；包名统一为 com.example.navipilot；LocationSensorManager 修复 GPS 数据写入；默认仅编译 arm64-v8a（减少 APK 体积 40-50%）
- 2.5：UI 响应式布局优化（竖屏 2/3 地图 + 1/3 控制面板）；引导页更新；OSM 地图搜索改为高德 Android SDK 优先 + 可选 Web REST / 腾讯 / Photon 链路
- 2.4：架构重构（协调器模式拆分 MainActivity）
- 2.3：Google Navigation SDK 集成
- 2.2：超车辅助系统、驾驶评分系统
- 2.1：Ntrip 协议支持；多语言本地化
- 2.0：初始公开版 — AMAP 广播、Xiaoge 接收器、基础超车、ZMQ、LED 矩阵

---

## 协议说明

本项目仅供学习研究使用。高德车机版导航完全免费。腾讯地图 SDK 使用需申请授权。高德地图车机版广播协议基于逆向分析。