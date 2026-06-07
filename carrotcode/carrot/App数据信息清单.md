# Navipilot App 数据信息清单

> **项目**: Navipilot (CP搭子)
> **日期**: 2026-06-07
> **目的**: 梳理 App 所有数据信息来源，包括从 comma3 设备获取的数据和 App 本身具备的数据

---

## 一、数据来源总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Navipilot App                                 │
│                                                                      │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐     │
│  │   手机传感器     │  │   导航 SDK       │  │   comma3 设备    │     │
│  │  (GPS/陀螺仪)   │  │ (高德/腾讯/Google)│  │   (TCP 7711)    │     │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘     │
│           │                    │                    │              │
│           └────────────────────┼────────────────────┘              │
│                                │                                    │
│                    ┌───────────┴───────────┐                        │
│                    │   CarrotManFields    │                        │
│                    │   (中央状态容器/SSOT) │                        │
│                    └───────────┬───────────┘                        │
│                                │                                    │
│           ┌────────────────────┼────────────────────┐               │
│           │                    │                    │               │
│           ▼                    ▼                    ▼               │
│    ┌─────────────┐     ┌─────────────┐     ┌─────────────┐         │
│    │ 发送至 comma3 │     │   Compose   │     │  驾驶评分   │         │
│    │  (UDP 7706)  │     │    UI      │     │  (本地存储) │         │
│    └─────────────┘     └─────────────┘     └─────────────┘         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 二、从 comma3 设备获取的数据

### 2.1 TCP 7711 — 小哥数据广播（车机 → App）

**数据类**: `XiaogeVehicleData`

| 字段 | 类型 | 说明 |
|------|------|------|
| `sequence` | Long | 序列号 |
| `timestamp` | Double | 时间戳 |
| `ip` | String? | 设备 IP |
| `receiveTime` | Long | 接收时间 |
| `carState` | `CarStateData`? | 车辆状态 |
| `modelV2` | `ModelV2Data`? | 模型输出 |
| `systemState` | `SystemStateData`? | 系统状态 |
| `overtakeStatus` | `OvertakeStatusData`? | 超车状态 |
| `tbtDist` | Int | TBT 距离 |

#### CarStateData（车辆状态）

| 字段 | 类型 | 说明 |
|------|------|------|
| `vEgo` | Float | 车辆速度 (m/s) |
| `steeringAngleDeg` | Float | 方向盘角度 (度) |
| `leftLatDist` | Float | 左侧横向距离 |
| `leftBlindspot` | Boolean | 左侧盲区 |
| `rightBlindspot` | Boolean | 右侧盲区 |

#### ModelV2Data（模型输出）

| 字段 | 类型 | 说明 |
|------|------|------|
| `lead0` | `LeadData`? | 本车道前车 |
| `leadLeft` | `SideLeadDataExtended`? | 左侧邻车 |
| `leadRight` | `SideLeadDataExtended`? | 右侧邻车 |
| `laneLineProbs` | `List<Float>` | 车道线概率 |
| `meta` | `MetaData`? | 元数据 |
| `curvature` | `CurvatureData`? | 曲率 |

#### LeadData（前车数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| `x` | Float | 相对距离 (m) |
| `y` | Float | 相对速度 (m/s) |
| `v` | Float | 速度 (m/s) |
| `prob` | Float | 存在概率 |

#### MetaData（元数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| `distanceToRoadEdgeLeft` | Float | 到左路边距离 |
| `distanceToRoadEdgeRight` | Float | 到右路边距离 |

#### CurvatureData（曲率）

| 字段 | 类型 | 说明 |
|------|------|------|
| `maxOrientationRate` | Float | 最大方向变化率 |

#### OvertakeStatusData（超车状态）

| 字段 | 类型 | 说明 |
|------|------|------|
| `statusText` | String | 状态文本 |
| `canOvertake` | Boolean | 是否可超车 |
| `cooldownRemaining` | Long? | 冷却剩余时间 |
| `lastDirection` | String? | 最后方向 |
| `blockingReason` | String? | 阻塞原因 |
| `currentLane` | Int | 当前车道 |
| `totalLanes` | Int | 总车道数 |
| `laneReminder` | String? | 车道提醒 |

#### SystemStateData（系统状态）

| 字段 | 类型 | 说明 |
|------|------|------|
| `enabled` | Boolean | 是否启用 |
| `active` | Boolean | 是否激活 |

---

### 2.2 UDP 7705 — 车机状态广播

**数据类**: `OpenpilotStatusData`

| 字段 | 类型 | 说明 |
|------|------|------|
| `carrot2` | String | Openpilot 版本 |
| `isOnroad` | Boolean | 是否在道路上 |
| `carrotRouteActive` | Boolean | 导航路线激活 |
| `ip` | String | 设备 IP |
| `port` | Int | 端口 |
| `logCarrot` | String | CarrotMan 日志 |
| `vCruiseKph` | Float | 巡航速度 (km/h) |
| `vEgoKph` | Int | 当前车速 (km/h) |
| `tbtDist` | Int | TBT 距离 |
| `sdiDist` | Int | SDI 距离 |
| `active` | Boolean | 自动驾驶激活 |
| `xState` | Int | 纵向控制状态 |
| `trafficState` | Int | 交通灯状态 |
| `carcruiseSpeed` | Float | 车辆巡航速度 |

---

### 2.3 HTTP 7000 — Web API 数据

**获取方式**: `CarrotParamClient` 通过 HTTP 请求

| 数据类型 | 获取方式 | 说明 |
|----------|----------|------|
| openpilot 参数 | `GET /api/params_bulk` | 批量参数读取 |
| 实时运行数据 | `GET /api/live_runtime` | 驾驶状态快照 |
| 设备信息 | `GET /api/device_network` | 网络/校准状态 |
| Dashcam 列表 | `GET /api/dashcam/routes` | 行程记录 |
| Git 状态 | `GET /api/tools/git_status` | 设备 Git 状态 |

---

## 三、App 本身具备的数据

### 3.1 手机传感器数据

**来源**: `LocationSensorManager`

| 数据 | 类型 | 说明 |
|------|------|------|
| **GPS 坐标** | Double | 经纬度 (WGS-84) |
| **GPS 速度** | Double | m/s |
| **航向角** | Double | 0-360 度（基于电子罗盘） |
| **GPS 精度** | Double | 米 |
| **加速度计** | Float[3] | X/Y/Z 轴加速度 |
| **磁力计** | Float[3] | X/Y/Z 轴磁场 |
| **旋转矢量** | Float[3] | 设备旋转 |

---

### 3.2 导航 SDK 数据

#### 高德车机版广播 (AmapBroadcastManager)

| 数据 | 字段 | 说明 |
|------|------|------|
| 广播类型 | `keyType` | KEY_TYPE 值 |
| 时间戳 | `timestamp` | 接收时间 |
| 原始数据 | `rawExtras` | 所有广播字段 |
| 解析内容 | `parsedContent` | 解析后文本 |

#### 高德手机 SDK (AmapNavDataBridge)

| 数据 | 字段 | 说明 |
|------|------|------|
| 导航模式 | `amapNaviMode` | 导航类型 |
| 路口放大图 | `amapSdkCrossVisible` | 是否显示 |
| 道路类型 | `roadType` | 道路类别 |
| 限速信息 | `nRoadLimitSpeed` | 限速值 |
| 车道信息 | `laneCount`, `laneInfoList` | 车道数/详情 |
| 转向信息 | `nTBTDist`, `nTBTTurnType` | 距离/类型 |
| 电子眼 | `nSdiType`, `nSdiSpeedLimit` | 类型/限速 |

#### 腾讯导航 SDK (TencentNavDataBridge)

| 数据 | 字段 | 说明 |
|------|------|------|
| 电子眼 | `tCameraType`, `tCameraDist`, `tCameraSpeedLimit` | 测速信息 |
| 红绿灯 | `trafficLightState`, `trafficLightDistance` | 状态/距离 |
| 交通拥堵 | `trafficJamAhead`, `trafficJamDistance` | 拥堵信息 |
| 路线点 | `tencentRoutePoints` | 路线坐标列表 |
| 目的地 | `curPointNum`, `curSegNum` | 当前路段 |
| 高速信息 | `tollEntranceName`, `tollExitName`, `tollFee` | 收费站 |
| GPS 信号 | `gpsSignalStatus` | 信号状态 |
| 弯道信息 | `roadGrade`, `roadKind` | 道路等级 |
| 车道车辆 | `leftLaneVehicle`, `rightLaneVehicle` | 邻道车辆 |

#### Google Navigation SDK (GoogleNavDataBridge)

| 数据 | 字段 | 说明 |
|------|------|------|
| 导航状态 | `isNavigating` | 是否导航中 |
| 路线数据 | `navInfoData` | TBT/电子眼/路况 |
| 当前位置 | `vpPosPointLat/Lon` | 导航坐标 |

---

### 3.3 CarrotManFields — 中央状态容器 (SSOT)

App 所有导航数据的中央存储，**共 44 个 UDP 字段** + 内部辅助字段

#### ① 发送给 comma3 的 44 个 UDP 字段

**基础通信 (3)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `carrotIndex` | Long | 数据包序号 |
| `epochTime` | Long | Unix 时间戳 |
| `timezone` | String | 时区 |

**GPS 定位 — 手机 GPS 回退 (5)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `latitude` | Double | GPS 纬度 (WGS-84) |
| `longitude` | Double | GPS 经度 (WGS-84) |
| `heading` | Double | 方向角 (0-360°) |
| `accuracy` | Double | GPS 精度 (米) |
| `gps_speed` | Double | GPS 速度 (m/s) |

**目的地 (3)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `goalPosX` | Double | 目标经度 |
| `goalPosY` | Double | 目标纬度 |
| `szGoalName` | String | 目标名称 |

**道路限速 (1+道路类别)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nRoadLimitSpeed` | Int | 道路限速 (km/h) |
| `roadcate` | Int | 道路类别 (0=高速, 8=地方) |
| `szPosRoadName` | String | 当前道路名称 |

**SDI 电子眼 (7)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nSdiType` | Int | SDI 类型 |
| `nSdiSpeedLimit` | Int | 测速限速 (km/h) |
| `nSdiDist` | Int | 到测速点距离 (m) |
| `nSdiSection` | Int | 区间测速 ID |
| `nSdiBlockType` | Int | 区间状态 |
| `nSdiBlockSpeed` | Int | 区间限速 |
| `nSdiBlockDist` | Int | 区间距离 |

**SDI Plus 扩展速度 (6)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nSdiPlusType` | Int | Plus 类型 |
| `nSdiPlusSpeedLimit` | Int | Plus 限速 |
| `nSdiPlusDist` | Int | Plus 距离 |
| `nSdiPlusBlockType` | Int | Plus 区间类型 |
| `nSdiPlusBlockSpeed` | Int | Plus 区间限速 |
| `nSdiPlusBlockDist` | Int | Plus 区间距离 |

**TBT 转弯导航 (9)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nTBTDist` | Int | 转弯距离 (m) |
| `nTBTTurnType` | Int | 转弯类型 |
| `szTBTMainText` | String | 主要指令文本 |
| `szNearDirName` | String | 近处方向名 |
| `szFarDirName` | String | 远处方向名 |
| `nTBTNextRoadWidth` | Int | 下一道路宽度 |
| `nTBTDistNext` | Int | 下一转弯距离 |
| `nTBTTurnTypeNext` | Int | 下一转弯类型 |
| `szTBTMainTextNext` | String | 下一转弯指令 |

**目的地剩余 (3)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nGoPosDist` | Int | 剩余距离 (m) |
| `nGoPosTime` | Int | 剩余时间 (s) |

**导航 GPS 位置 (4)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `vpPosPointLat` | Double | 导航纬度 |
| `vpPosPointLon` | Double | 导航经度 |
| `nPosAngle` | Double | 导航方向角 |
| `nPosSpeed` | Double | 导航速度 |

**命令通道 (2)**

| 字段 | 类型 | 说明 |
|------|------|------|
| `carrotCmd` | String | 命令类型 |
| `carrotArg` | String | 命令参数 |

---

#### ② 内部辅助字段（不发送至 comma3）

**导航状态**

| 字段 | 类型 | 说明 |
|------|------|------|
| `isNavigating` | Boolean | 是否正在导航 |
| `active_carrot` | Int | CarrotMan 激活状态 |
| `source_last` | String | 最后数据源 |
| `lastUpdateTime` | Long | 最后更新时间 |

**高德地图原始数据**

| 字段 | 类型 | 说明 |
|------|------|------|
| `amapIcon` | Int | 高德转弯图标 |
| `amapIconNext` | Int | 高德下一转弯图标 |
| `nAmapCameraType` | Int | 高德相机类型 |

**路况信息**

| 字段 | 类型 | 说明 |
|------|------|------|
| `trafficLevel` | Int | 拥堵等级 |
| `trafficDescription` | String | 拥堵描述 |

**车道信息**

| 字段 | 类型 | 说明 |
|------|------|------|
| `laneCount` | Int | 车道数量 |
| `laneInfoList` | `List<LaneInfo>` | 车道详情列表 |
| `nLaneCount` | Int | 车道数量 |

**出口与服务区**

| 字段 | 类型 | 说明 |
|------|------|------|
| `exitNameInfo` | String | 出口信息 |
| `sapaName` | String | 服务区名称 |
| `sapaDist` | Int | 服务区距离 |
| `sapaType` | Int | 服务区类型 |
| `sapaNum` | Int | 服务区编号 |

**航段辅助**

| 字段 | 类型 | 说明 |
|------|------|------|
| `segAssistantAction` | Int | 航段动作 |
| `curPointNum` | Int | 当前途径点 |
| `curSegNum` | Int | 当前路段 |

**红绿灯**

| 字段 | 类型 | 说明 |
|------|------|------|
| `trafficLightState` | Int | 红绿灯状态 |
| `trafficLightDistance` | Int | 红绿灯距离 |
| `trafficLightCountdown` | Int | 红灯倒计时 |

**态势信息**

| 字段 | 类型 | 说明 |
|------|------|------|
| `situationType` | Int | 态势类型 |
| `situationDistance` | Int | 态势距离 |
| `situationDescription` | String | 态势描述 |

**高德手机 SDK 状态**

| 字段 | 类型 | 说明 |
|------|------|------|
| `amapSdkCrossVisible` | Boolean | 路口放大图 |
| `amapSdkModeCrossVisible` | Boolean | 模式交叉视图 |
| `amapParallelElevatedFlag` | Int | 高架标志 |
| `amapParallelMainSideFlag` | Int | 主辅路标志 |
| `amapNaviMapMode` | Int | 导航地图模式 |

**高德车机广播红绿灯**

| 字段 | 类型 | 说明 |
|------|------|------|
| `amap_traffic_light_status` | Int | 红绿灯状态 |
| `amap_traffic_light_dir` | Int | 红绿灯方向 |
| `amap_green_light_last_second` | Int | 绿灯剩余秒数 |
| `amap_wait_round` | Int | 等待轮数 |

**ATC 弯道减速**

| 字段 | 类型 | 说明 |
|------|------|------|
| `atcType` | String | ATC 类型 |
| `vTurnSpeed` | Double | 弯道速度 |

---

#### ③ 腾讯 SDK / 车道检测嵌套 (CarrotManTencentSlice)

| 字段 | 类型 | 说明 |
|------|------|------|
| `tCameraType` | Int | 测速类型 |
| `tCameraDist` | Int | 测速距离 |
| `tCameraSpeedLimit` | Int | 测速限速 |
| `remainingTrafficLights` | Int | 剩余红绿灯 |
| `passedDistance` | Int | 已行驶距离 |
| `passedTime` | Int | 已行驶时间 |
| `isOnMainRoad` | Boolean | 是否在主路 |
| `canSwitchToMainRoad` | Boolean | 可否进入主路 |
| `canSwitchToSideRoad` | Boolean | 可否进入辅路 |
| `tencentRoutePoints` | `List<Pair<Double, Double>>` | 腾讯路线点 |
| `tencentRoutePointsReady` | Boolean | 路线就绪 |
| `trafficJamAhead` | Boolean | 前方拥堵 |
| `trafficJamDistance` | Int | 拥堵距离 |
| `trafficJamDuration` | Int | 拥堵持续时间 |
| `trafficJamStatus` | Int | 拥堵状态 |
| `tollEntranceName` | String | 入口收费站 |
| `tollExitName` | String | 出口收费站 |
| `tollFee` | Int | 通行费 |
| `gpsSignalStatus` | Int | GPS 信号状态 |
| `roadGrade` | Int | 道路等级 |
| `roadKind` | Int | 道路种类 |
| `tSdkIntersectionType` | Int | 交叉口类型 |
| `leftLaneVehicle` | Boolean | 左侧车道有车 |
| `rightLaneVehicle` | Boolean | 右侧车道有车 |

#### ④ 非构造参数字段 (@Transient)

| 字段 | 类型 | 说明 |
|------|------|------|
| `isNightMode` | Boolean | 是否夜间模式 |
| `isOffRoute` | Boolean | 是否偏航 |

---

### 3.4 驾驶评分数据

**来源**: `DrivingDataCollector` + `DrivingScoreEngine`

#### DrivingSession（驾驶会话）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 会话 ID |
| `startTime` | Long | 开始时间 |
| `endTime` | Long | 结束时间 |
| `totalDistance` | Float | 总里程 (km) |
| `nooDistance` | Float | NOO 里程 |
| `avgSpeed` | Float | 平均速度 |
| `maxSpeed` | Float | 最高速度 |

**五维评分**

| 字段 | 类型 | 说明 |
|------|------|------|
| `smoothnessScore` | Int | 平稳性得分 (30%) |
| `predictionScore` | Int | 预判力得分 (25%) |
| `interventionScore` | Int | 接管依赖得分 (20%) |
| `ecoScore` | Int | 节能得分 (15%) |
| `stabilityScore` | Int | NOO 稳定得分 (10%) |
| `totalScore` | Int | 总分 |

**异常事件统计**

| 字段 | 类型 | 说明 |
|------|------|------|
| `harshAccelCount` | Int | 急加速次数 |
| `harshBrakeCount` | Int | 急刹车次数 |
| `sharpTurnCount` | Int | 急转弯次数 |
| `interventionCount` | Int | 接管次数 |

**时间统计**

| 字段 | 类型 | 说明 |
|------|------|------|
| `cruiseTimeSeconds` | Int | 巡航时间 (秒) |
| `nooTimeSeconds` | Int | NOO 时间 (秒) |
| `totalTimeSeconds` | Int | 总时间 (秒) |
| `cruiseRatio` | Float | 巡航比例 |
| `nooRatio` | Float | NOO 比例 |

**预判统计**

| 字段 | 类型 | 说明 |
|------|------|------|
| `smoothBrakeBeforeCurveCount` | Int | 弯道前平滑减速次数 |
| `smoothDecelBeforeLimitCount` | Int | 限速前平滑减速次数 |

**驾驶风格**

| 字段 | 类型 | 说明 |
|------|------|------|
| `drivingStyle` | String | 驾驶风格标签 |

#### InterventionDetail（接管详情）

| 字段 | 类型 | 说明 |
|------|------|------|
| `timestamp` | Long | 时间戳 |
| `speed` | Float | 当时速度 |
| `leadDistance` | Float | 前车距离 |
| `roadType` | Int | 道路类型 |
| `reason` | String | 接管原因 |
| `isActive` | Boolean | 是否激活 |
| `roadName` | String | 道路名称 |

---

### 3.5 本地存储数据

**来源**: `PreferenceRepository` (SharedPreferences)

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `overtake_mode` | Int | 1 | 超车模式 (0=关闭, 1=拨杆, 2=自动) |
| `overtake_param_min_speed_kph` | Float | 65 | 最小超车速度 (km/h) |
| `overtake_param_speed_diff_kph` | Float | 20 | 超车速度差阈值 |
| `device_id` | String | "" | 设备 ID |
| `user_type` | Int | 0 | 用户类型 |

**坐标存储** (`CoordinatePreferences`)

| 字段 | 类型 | 说明 |
|------|------|------|
| `home_lat`, `home_lon` | Double | 家坐标 |
| `company_lat`, `company_lon` | Double | 公司坐标 |
| 精度 | String 存储 Double | 厘米级精度 |

---

## 四、数据流汇总表

| 数据类型 | 来源 | 去向 | 协议/格式 |
|----------|------|------|-----------|
| 导航数据 | 高德/腾讯/Google SDK | comma3 | UDP 7706 JSON |
| 路线点 | 高德/腾讯/Google SDK | comma3 | TCP 7709 二进制 |
| 车辆状态 | comma3 | App | TCP 7711 JSON |
| 车机状态 | comma3 | App | UDP 7705 JSON |
| 实时参数 | comma3 | App | HTTP 7000 |
| 驾驶评分 | App 传感器 | 本地存储 | SharedPreferences |
| 用户设置 | App | 本地存储 | SharedPreferences |
| 超车指令 | App | comma3 | ZMQ 7710 |

---

## 五、关键数据流图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            数据流向                                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  【手机传感器】                      【导航 SDK】                          │
│  ┌────────────┐                    ┌────────────┐                        │
│  │ GPS 坐标   │◄───────────────────│ 高德广播   │                        │
│  │ GPS 速度   │                    └──────┬─────┘                        │
│  │ 电子罗盘   │                    ┌──────┴─────┐                        │
│  │ 加速度计   │                    │ 高德手机SDK│                        │
│  └────────────┘                    └──────┬─────┘                        │
│         │                         ┌──────┴─────┐                        │
│         │                         │ 腾讯导航SDK│                        │
│         │                         └──────┬─────┘                        │
│         │                         ┌──────┴─────┐                        │
│         │                         │ Google Nav │                        │
│         │                         └──────┬─────┘                        │
│         │                              │                               │
│         │                    ┌──────────┴──────────┐                    │
│         │                    │  CarrotManFields    │                    │
│         │                    │   (中央状态容器)    │                    │
│         │                    └──────────┬──────────┘                    │
│         │                              │                               │
│  ┌──────┴──────────────────────────────┴──────┐                         │
│  │                   │                        │                         │
│  │                   ▼                        ▼                         │
│  │           ┌────────────┐           ┌────────────┐                    │
│  │           │  Compose   │           │  发送至    │                    │
│  │           │    UI      │           │  comma3    │                    │
│  │           └────────────┘           └─────┬─────┘                    │
│  │                                          │                          │
│  │                                   UDP 7706 / TCP 7709               │
│  │                                          │                          │
│  │                                          ▼                          │
│  │  ┌─────────────────────────────────────────────────────┐             │
│  │  │                    comma3 设备                       │             │
│  │  └─────────────────────────────────────────────────────┘             │
│  │                          │                                        │
│  │                    TCP 7711 / UDP 7705                              │
│  │                          │                                        │
│  │                          ▼                                        │
│  │                   ┌────────────┐                                   │
│  │                   │ Xiaoge     │                                   │
│  │                   │ VehicleData│                                   │
│  │                   └────────────┘                                   │
│  │                                                                   │
│  └──────────────────────────────────────────────────────────────────┘
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 六、数据完整性与一致性

| 数据类型 | 更新频率 | 防抖机制 | 数据融合策略 |
|----------|----------|----------|--------------|
| GPS 坐标 | ~1Hz | 无 | 手机GPS优先，覆盖车机GPS（3秒超时） |
| 限速信息 | ~5Hz | 连续6帧相同才采纳 | 取最大值 |
| 转向信息 | ~5Hz | 无 | 直接使用 |
| 车辆状态 | ~20Hz | 无 | 直接使用 |
| 驾驶评分 | 实时累计 | 无 | 会话结束计算 |