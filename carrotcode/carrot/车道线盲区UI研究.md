# 车道线与盲区动态UI研究

> **项目**: Navipilot
> **日期**: 2026-06-07
> **目的**: 研究在App左上角增加车道线和盲区动态UI的可行性及实现方案

---

## 一、数据来源分析

### 1.1 可用数据总览

| 数据类型 | 来源 | 字段 | 说明 |
|----------|------|------|------|
| **车道线概率** | comma3 modelV2 | `laneLineProbs` (List<Float>) | 4条车道线概率 |
| **路缘距离** | comma3 modelV2.meta | `distanceToRoadEdgeLeft/Right` | 左右路缘距离 |
| **盲区状态** | comma3 carState | `leftBlindspot`, `rightBlindspot` | 左右盲区检测 |
| **横向距离** | comma3 carState | `leftLatDist` | 车辆相对车道中心距离 |
| **车道位置** | comma3 modelV2 | `laneLine` 结构 | 车道线位置数据 |
| **曲率** | comma3 modelV2.curvature | `maxOrientationRate` | 道路曲率 |

---

### 1.2 详细数据结构

#### XiaogeVehicleData 中的完整数据

```kotlin
data class XiaogeVehicleData(
    val sequence: Long,
    val timestamp: Double,
    val ip: String?,
    val receiveTime: Long = 0L,
    val carState: CarStateData?,      // 包含盲区
    val modelV2: ModelV2Data?,         // 包含车道线
    val systemState: SystemStateData?,
    val overtakeStatus: OvertakeStatusData? = null,
    val tbtDist: Int = 0
)

// 盲区数据
data class CarStateData(
    val vEgo: Float,                    // 车速 m/s
    val steeringAngleDeg: Float,       // 方向盘角度
    val leftLatDist: Float,            // 左侧横向距离（厘米）
    val leftBlindspot: Boolean,        // 左盲区
    val rightBlindspot: Boolean        // 右盲区
)

// 车道线数据
data class ModelV2Data(
    val lead0: LeadData?,              // 本车道前车
    val leadLeft: SideLeadDataExtended?, // 左邻车
    val leadRight: SideLeadDataExtended?, // 右邻车
    val laneLineProbs: List<Float>,   // 4条车道线概率
    val meta: MetaData?,              // 包含路缘距离
    val curvature: CurvatureData?     // 道路曲率
)

data class MetaData(
    val distanceToRoadEdgeLeft: Float = 0.0f,   // 左路缘距离
    val distanceToRoadEdgeRight: Float = 0.0f   // 右路缘距离
)
```

#### CarrotWsClient 中解析的数据

```kotlin
data class VehicleData(
    val carState: CarState? = null,
    val modelV2: ModelV2? = null,
    val controlsState: ControlsState? = null,
    val selfdriveState: SelfdriveState? = null,
    // ...
)

data class CarState(
    val vEgo: Float = 0f,           // m/s
    val steeringAngleDeg: Float = 0f,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
)

data class ModelV2(
    val leadX: Float = 0f,          // 前车距离 m
    val leadV: Float = 0f,          // 前车速度 m/s
    val leadProb: Float = 0f,       // 前车置信度
    val laneLineProbs: List<Float> = emptyList(),
    val leftDist: Float = 0f,       // 到左路边距离 ← 路缘距离
    val rightDist: Float = 0f,      // 到右路边距离 ← 路缘距离
)
```

**注意**: `CarrotWsClient` 当前解析的 `laneLineProbs` 和 `leftDist/rightDist` 在 `CerealDecoder.decodeModelV2` 中**尚未实现**（只解析了 lead 数据）。

---

### 1.3 Cereal 解码器扩展需求

当前的 `CerealDecoder.decodeModelV2` 需要扩展以支持车道线数据：

```kotlin
private fun decodeModelV2(r: CapnpReader): Map<String, Any?> {
    r.segment()

    // 现有字段
    val leadPtr = r.ptr(0)
    // ... leadX, leadV, leadProb 解析

    // 需要新增的车道线字段
    // laneLineProbs (list Float32 @3)
    // laneLine (list struct @4)
    // meta.distanceToRoadEdgeLeft (Float32 @0 in meta struct)
    // meta.distanceToRoadEdgeRight (Float32 @1 in meta struct)

    return mapOf(
        // ... existing fields
        "laneLineProbs" to decodeFloatList(r, 3),  // 需要实现
        "leftDist" to r.f32(...),  // meta.distanceToRoadEdgeLeft
        "rightDist" to r.f32(...), // meta.distanceToRoadEdgeRight
    )
}
```

---

## 二、车机端 Web UI 实现参考

### 2.1 carrot Web 的车道线可视化

**文件**: `carrotcode/carrot/web/js/realtime/home_drive.js`

```javascript
// 车道线和车辆渲染逻辑参考
function renderLaneLines(modelV2) {
    if (!modelV2.laneLineProbs) return;

    const probs = modelV2.laneLineProbs; // 4条车道线概率
    const laneLines = modelV2.laneLine;  // 车道线位置数据

    // 渲染车道线
    for (let i = 0; i < 4; i++) {
        if (probs[i] > 0.5) {
            // 绘制车道线
            drawLaneLine(laneLines[i], probs[i]);
        }
    }
}
```

### 2.2 可视化方案

**车道线可视化**:
```
     车道线概率 (laneLineProbs)
     ┌────────────────────────────────────────────────┐
     │  idx 0   idx 1   idx 2   idx 3                 │
     │  左车道线  本车道    本车道   右车道线            │
     │  左侧     左线      右线     右侧               │
     └────────────────────────────────────────────────┘
     概率值 0.0 ~ 1.0，颜色从灰色(不可见) → 绿色(高置信度)
```

**盲区可视化**:
```
     ┌────────────────────────────────────────┐
     │  [左盲区]     车辆图标      [右盲区]     │
     │  🔴红色=有车  🚗 车辆     🟢绿色=安全   │
     └────────────────────────────────────────┘
```

**路缘距离可视化**:
```
     左路缘距离          车辆          右路缘距离
     ◄──── 2.5m+ ────►   🚗   ◄──── 2.0m ────►
     安全变道空间       位置         需注意
```

---

## 三、UI 设计方案

### 3.1 布局位置

**推荐位置**: 左上角 `HomeControlPanel` 区域

```
┌─────────────────────────────────────────────────────────────────┐
│ ┌──────────┐                                  ┌──────────────┐  │
│ │ 车道+盲区 │  ← 新增UI区域                    │  搜索 家 公司 │  │
│ │ 动态面板 │                                  │              │  │
│ └──────────┘                                  └──────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│                      中央地图区域                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 UI 组件设计

#### 方案A: 简洁条形显示

```
┌─────────────────────────────────────────────────────────────┐
│  🟢左1.2m  ═══╗      🚗      ║═══  🔴右0.8m               │
│  盲区:安全   ║ 车辆位置 ║  盲区:有车                       │
│  车道线: 0.95 0.98 0.97 0.85                               │
└─────────────────────────────────────────────────────────────┘
```

#### 方案B: 图示化显示

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│     ╱                    ╲                    ╱             │
│    ╱   左车道线 95%       ╲                  ╱              │
│   ╱                       ╲   右车道线 87%  ╲              │
│   │                        ╲                │             │
│   │    [左盲区]  [右盲区]   │                │             │
│   │      🔴        🟢       │                │             │
│   │         ┌──────┐       │                │             │
│   │         │  🚗  │       │                │             │
│   │         └──────┘       │                │             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 组件规格

**LaneBlindspotPanel** 组件规格：

| 属性 | 值 |
|------|-----|
| 宽度 | 左侧区域 4份 |
| 高度 | 80dp (与速度圆环对齐) |
| 背景 | 半透明深色 (Color.Black.copy(alpha=0.5)) |
| 圆角 | 12dp |
| 内边距 | 8dp |

---

## 四、实现计划

### 4.1 步骤1: 扩展 CerealDecoder

在 `CarrotWsClient.kt` 中扩展 `decodeModelV2` 方法：

```kotlin
private fun decodeModelV2(r: CapnpReader): Map<String, Any?> {
    r.segment()

    // 解析 laneLineProbs (list Float32 @3)
    val laneLineProbs = decodeFloatList(r, 3)

    // 解析 meta struct (@5) 中的路缘距离
    val metaPtr = r.ptr(5)
    val leftDist = if (metaPtr > 0) {
        r.savePos()
        r.seek(metaPtr)
        val dist = r.f32(0)  // distanceToRoadEdgeLeft
        r.restorePos()
        dist
    } else 0f

    val rightDist = if (metaPtr > 0) {
        r.savePos()
        r.seek(metaPtr)
        val dist = r.f32(4)  // distanceToRoadEdgeRight
        r.restorePos()
        dist
    } else 0f

    return mapOf(
        "leadX" to leadX.toFloat(),
        "leadV" to leadV.toFloat(),
        "leadProb" to leadProb.toFloat(),
        "laneLineProbs" to laneLineProbs,
        "leftDist" to leftDist,
        "rightDist" to rightDist,
    )
}
```

### 4.2 步骤2: 更新数据模型

扩展 `VehicleData` 和 `ModelV2`：

```kotlin
data class VehicleData(
    // ... existing fields
    val laneData: LaneData? = null,
    val blindspotData: BlindspotData? = null,
)

data class LaneData(
    val laneLineProbs: List<Float>,  // 4条车道线概率
    val leftDist: Float,             // 左路缘距离 (m)
    val rightDist: Float,            // 右路缘距离 (m)
    val curvature: Float = 0f,       // 道路曲率
)

data class BlindspotData(
    val leftBlindspot: Boolean,
    val rightBlindspot: Boolean,
    val leftLatDist: Float,         // 横向距离 (m)
)
```

### 4.3 步骤3: 创建 UI 组件

新建 `LaneBlindspotPanel.kt`：

```kotlin
@Composable
fun LaneBlindspotPanel(
    modifier: Modifier = Modifier,
    laneData: LaneData?,
    blindspotData: BlindspotData?,
    carState: CarState?
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧: 盲区状态 + 路缘距离
        LaneInfoSection(
            laneData = laneData,
            blindspotData = blindspotData
        )

        // 中间: 车辆位置示意
        CarPositionIndicator(
            leftLatDist = carState?.leftLatDist ?: 0f
        )

        // 右侧: 车道线概率条
        LaneLineProbBars(
            probs = laneData?.laneLineProbs ?: emptyList()
        )
    }
}
```

### 4.4 步骤4: 集成到 HomeControlPanel

在 `MainActivityUI.kt` 的 `HomeControlPanel` 中添加：

```kotlin
@Composable
private fun HomeControlPanel(
    modifier: Modifier = Modifier,
    // ... existing params
    vehicleData: VehicleData?  // 新增
) {
    Column(modifier = modifier) {
        // 车道盲区面板 (新增)
        LaneBlindspotPanel(
            modifier = Modifier.fillMaxWidth(),
            laneData = vehicleData?.laneData,
            blindspotData = vehicleData?.blindspotData,
            carState = vehicleData?.carState
        )

        // 现有的速度和道路信息
        SpeedDisplayPanel(...)
        // ...
    }
}
```

---

## 五、数据流

```
comma3 设备
    │
    │ TCP 7711 / WebSocket 7000
    ▼
CarrotWsClient
    │ decodeModelV2() 扩展解析
    │ decodeCarState() 解析盲区
    ▼
VehicleData (laneData, blindspotData, carState)
    │
    ▼
HomeControlPanel → LaneBlindspotPanel
    │
    ▼
Compose UI 渲染
```

---

## 六、技术要点

### 6.1 Capnp 字段索引

| 字段 | 索引 | 类型 |
|------|------|------|
| modelV2.laneLineProbs | @3 | List(Float32) |
| modelV2.laneLine | @4 | List(struct) |
| modelV2.meta | @5 | struct |
| meta.distanceToRoadEdgeLeft | @0 | Float32 |
| meta.distanceToRoadEdgeRight | @1 | Float32 |
| carState.leftBlindspot | @42 | Bool |
| carState.rightBlindspot | @43 | Bool |
| carState.leftLatDist | @15 | Float64 |

### 6.2 UI 更新频率

- 车道线数据: 20Hz (与 modelV2 同步)
- 盲区数据: 20Hz (与 carState 同步)
- 建议 UI 刷新: 10Hz (避免过度渲染)

### 6.3 性能考虑

- 使用 `remember` 缓存车道线绘制结果
- 使用 `derivedStateOf` 减少不必要的重组
- 车道线概率变化小于 5% 时跳过 UI 更新

---

## 七、待验证事项

1. **Capnp 字段索引**: 需要在车机端实际抓包确认字段偏移
2. **盲区数据可用性**: 确认 `leftBlindspot`/`rightBlindspot` 是否在 carState 中
3. **车道线数据格式**: `laneLineProbs` 是 4 元素 List，确认索引对应关系
4. **数据延迟**: 从车机到 App 的延迟约 50-100ms，需考虑实时性