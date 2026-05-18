# Google 导航实现增强计划

## 问题诊断

### 编译错误根本原因
```
MainActivityUI.kt:564:49 Unresolved reference 'googleNavManager'
```

**原因分析：**
1. 代码中存在对 `core.googleNavManager` 的引用，但 `MainActivityCore` 中不存在该属性
2. 正确的架构：导航管理器应该在 `GoogleNavPage` Composable 内部通过 `remember {}` 创建（已正确实现）
3. **不应该**在 `MainActivityCore` 中添加 `googleNavManager` 属性

### 解决方案
**删除所有对 `core.googleNavManager` 的引用**

检查以下文件是否有错误引用：
- `MainActivityUI.kt`
- `MainActivityCore.kt`
- `MainActivity.kt`

正确的使用方式参考 `GoogleNavPage.kt` 第77行：
```kotlin
val navManager = remember { GoogleNavManager(context, carrotManFieldsState) }
```

---

## 功能差距分析

### 当前代码量对比
| 文件 | 行数 | 完成度 |
|------|------|--------|
| **AmapMobileNavPage.kt** | 1,397 | ✅ 功能齐全 |
| **TencentNavPage.kt** | 1,075 | ✅ 功能齐全 |
| **GoogleNavPage.kt** | 322 | ⚠️ 基础功能 (23%) |

### 核心功能对比

| 功能模块 | 高德 | 腾讯 | Google | 优先级 |
|---------|------|------|--------|--------|
| **监听器系统** |
| 位置更新 | ✅ AMapNaviListener | ✅ NavigatorDriveObserver | ❌ | P0 |
| 路况数据 | ✅ | ✅ | ❌ | P1 |
| 速度限制 | ✅ | ✅ | ❌ | P0 |
| 转向指引 | ✅ | ✅ | ⚠️ 部分 | P0 |
| 路径段变化 | ✅ | ✅ | ❌ | P1 |
| **数据桥接** |
| GPS 坐标 | ✅ | ✅ | ✅ | - |
| 剩余距离/时间 | ✅ | ✅ | ✅ | - |
| 转向类型/距离 | ✅ | ✅ | ⚠️ 部分 | P0 |
| 限速数据 | ✅ | ✅ | ❌ | P0 |
| 路线点序列 | ✅ TCP 7709 | ✅ TCP 7709 | ❌ | P0 |
| 车道信息 | ✅ | ✅ | ❌ | P2 |
| 电子眼 | ✅ | ✅ | N/A | - |
| **UI 功能** |
| 路线选择面板 | ✅ | ✅ | ❌ | P1 |
| 状态监控 Badge | ✅ | ✅ | ❌ | P1 |
| 快捷导航按钮 | ✅ | ✅ | ❌ | P1 |
| 模拟导航控制 | ✅ | ✅ | ⚠️ 基础 | P1 |
| 设置面板 | ✅ | ✅ | ❌ | P2 |

---

## 增强实施方案

### Phase 1: 修复编译错误 (P0)

#### 1.1 检查并删除错误引用
```bash
# 搜索所有 googleNavManager 引用
grep -rn "core\.googleNavManager" app/src/main/java/com/example/carrotamap/

# 应该只在 GoogleNavPage.kt 内部使用（作为 remember {} 创建的局部变量）
```

**确认：MainActivityCore 中不应该有：**
```kotlin
// ❌ 错误 - 不要添加
lateinit var googleNavManager: GoogleNavManager

// ❌ 错误 - 不要添加
var googleNavManager: GoogleNavManager? = null
```

### Phase 2: 增强监听器系统 (P0)

#### 2.1 GoogleNavManager - 新增监听器

在 `GoogleNavManager.kt` 的 `registerNavigationListeners()` 方法中添加：

```kotlin
// 📍 3. 位置更新监听器（1Hz 高频更新）
private var locationListener: Navigator.LocationListener? = null

fun registerLocationListener(nav: Navigator) {
    locationListener = Navigator.LocationListener { location ->
        dataBridge?.updateLocation(
            lat = location.latitude,
            lon = location.longitude,
            heading = location.bearing,
            speed = location.speed
        )
    }
    nav.addLocationListener(locationListener)
    Log.i(TAG, "✅ 已注册位置监听器")
}

// 🚦 4. 路况数据监听器
private var trafficDataListener: Navigator.TrafficDataListener? = null

fun registerTrafficListener(nav: Navigator) {
    trafficDataListener = Navigator.TrafficDataListener {
        // Google SDK 会在后台更新路况，前端自动显示
        Log.d(TAG, "路况数据已更新")
    }
    nav.setTrafficDataListener(trafficDataListener)
    Log.i(TAG, "✅ 已注册路况监听器")
}

// ⚡ 5. 超速监听器
private var speedingListener: Navigator.SpeedingUpdatedListener? = null

fun registerSpeedingListener(nav: Navigator) {
    speedingListener = Navigator.SpeedingUpdatedListener { speedingInfo ->
        val speedLimit = speedingInfo.speedLimit // m/s
        val currentSpeed = speedingInfo.currentSpeed // m/s

        dataBridge?.updateSpeedLimit(
            speedLimitKmh = (speedLimit * 3.6).toInt(),
            currentSpeedKmh = (currentSpeed * 3.6).toInt()
        )
    }
    nav.addSpeedingUpdatedListener(speedingListener)
    Log.i(TAG, "✅ 已注册超速监听器")
}

// 🛣️ 6. 路径段变化监听器
private var routeSegmentListener: Navigator.RouteSegmentChangedListener? = null

fun registerRouteSegmentListener(nav: Navigator) {
    routeSegmentListener = Navigator.RouteSegmentChangedListener {
        val segment = nav.currentRouteSegment
        segment?.let {
            val roadName = it.displayName ?: ""
            Log.d(TAG, "当前路段: $roadName")
            dataBridge?.updateCurrentRoad(roadName)
        }
    }
    nav.addRouteSegmentChangedListener(routeSegmentListener)
    Log.i(TAG, "✅ 已注册路径段监听器")
}
```

#### 2.2 GoogleNavDataBridge - 新增数据字段

在 `GoogleNavDataBridge.kt` 中添加：

```kotlin
// 更新限速信息
fun updateSpeedLimit(speedLimitKmh: Int, currentSpeedKmh: Int) {
    postFieldsMutate { s ->
        s.value = s.value.copy(
            nRoadLimitSpeed = speedLimitKmh,
            nPosSpeed = currentSpeedKmh.toDouble(),
            source_last = "google_nav"
        )
    }
}

// 更新当前道路名称
fun updateCurrentRoad(roadName: String) {
    postFieldsMutate { s ->
        s.value = s.value.copy(
            szCurRoad = roadName,
            source_last = "google_nav"
        )
    }
}
```

### Phase 3: 路线点提取与发送 (P0)

#### 3.1 提取路线坐标序列

在 `GoogleNavManager.kt` 中添加：

```kotlin
/**
 * 提取当前路线的所有坐标点（WGS-84）
 * 用于发送至 comma3 设备 (TCP 7709)
 */
fun extractRoutePoints(): List<Pair<Double, Double>> {
    val nav = navigator ?: return emptyList()

    return try {
        val routeSegments = nav.routeSegments
        val points = mutableListOf<Pair<Double, Double>>()

        routeSegments?.forEach { segment ->
            segment.latLngs?.forEach { latLng ->
                points.add(Pair(latLng.longitude, latLng.latitude))
            }
        }

        Log.i(TAG, "✅ 提取路线点: ${points.size} 个坐标")
        points
    } catch (e: Exception) {
        Log.e(TAG, "❌ 提取路线点失败: ${e.message}", e)
        emptyList()
    }
}

/**
 * 提取并发送路线点到 comma3 设备
 */
fun extractAndSendRoutePoints(networkClient: com.example.carrotamap.CarrotManNetworkClient?) {
    val points = extractRoutePoints()

    if (points.isNotEmpty()) {
        networkClient?.sendRoutePointsViaTcp(points)

        // 调试日志（前3个点）
        points.take(3).forEachIndexed { i, (lon, lat) ->
            Log.d(TAG, "[$i] lon=${"%.6f".format(lon)}, lat=${"%.6f".format(lat)}")
        }

        Log.i(TAG, "✅ 路线点已发送至设备 (TCP 7709): ${points.size}个点")
    }
}
```

#### 3.2 GoogleNavPage - 路线成功后自动发送

在 `GoogleNavPage.kt` 的路线成功回调中添加：

```kotlin
when (code) {
    RouteStatus.OK -> {
        Log.i(TAG, "✅ 路线规划成功")

        // 🆕 提取并发送路线点到 comma3
        navManager.extractAndSendRoutePoints(networkClient)

        // ... 现有代码 ...
    }
}
```

### Phase 4: UI 增强 (P1)

#### 4.1 状态监控 Badge（参考 TencentNavPage:908-930）

在 `GoogleNavPage.kt` 中添加：

```kotlin
// 设备连接状态卡片
if (deviceIP != null) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .align(Alignment.TopStart),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "设备: $deviceIP",
                color = Color.White,
                fontSize = 12.sp
            )
            val fields = carrotManFieldsState?.value
            Text(
                "速度: ${fields?.nPosSpeed?.toInt() ?: 0} km/h",
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                "限速: ${fields?.nRoadLimitSpeed ?: 0} km/h",
                color = if ((fields?.nPosSpeed ?: 0.0) > (fields?.nRoadLimitSpeed ?: 999))
                    Color.Red else Color.White,
                fontSize = 12.sp
            )
        }
    }
}
```

#### 4.2 快捷导航按钮（参考 TencentNavPage:756-843）

```kotlin
// 快捷功能按钮
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .align(Alignment.BottomCenter),
    horizontalArrangement = Arrangement.SpaceEvenly
) {
    // 一键回家
    FloatingActionButton(
        onClick = {
            // 从 SharedPreferences 读取家地址
            val homeCoords = getHomeCoordinates(context)
            navManager.startNavigation(
                destLat = homeCoords.first,
                destLon = homeCoords.second,
                destName = "家",
                simulate = simulateNav
            )
        },
        containerColor = MaterialTheme.colorScheme.primaryContainer
    ) {
        Icon(Icons.Default.Home, contentDescription = "回家")
    }

    // 一键去公司
    FloatingActionButton(
        onClick = {
            val workCoords = getWorkCoordinates(context)
            navManager.startNavigation(
                destLat = workCoords.first,
                destLon = workCoords.second,
                destName = "公司",
                simulate = simulateNav
            )
        },
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Icon(Icons.Default.Business, contentDescription = "去公司")
    }

    // 模拟导航切换
    FloatingActionButton(
        onClick = { simulateNav = !simulateNav },
        containerColor = if (simulateNav) Color.Green else Color.Gray
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "模拟导航")
    }
}
```

### Phase 5: 生命周期完善 (P2)

在 `GoogleNavPage.kt` 中添加配置变化处理：

```kotlin
// 配置变化处理
DisposableEffect(LocalConfiguration.current) {
    val config = LocalConfiguration.current
    navViewRef?.onConfigurationChanged(config)
    onDispose {}
}

// 内存优化
DisposableEffect(Unit) {
    onDispose {
        navViewRef?.onTrimMemory(
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        )
    }
}
```

---

## 实施检查清单

### ✅ 编译错误修复
- [ ] 搜索并删除所有 `core.googleNavManager` 引用
- [ ] 确认 GoogleNavPage 内部使用 `remember {}` 创建管理器
- [ ] 验证编译通过

### 🎯 核心功能增强 (P0)
- [ ] GoogleNavManager 新增 5 个监听器（位置、路况、超速、路段、转向）
- [ ] GoogleNavDataBridge 新增 `updateSpeedLimit()` 和 `updateCurrentRoad()`
- [ ] 实现 `extractRoutePoints()` 方法
- [ ] 实现 `extractAndSendRoutePoints()` 方法
- [ ] 路线成功后自动发送路线点到 TCP 7709

### 🎨 UI 增强 (P1)
- [ ] 添加设备状态监控 Badge
- [ ] 添加快捷导航按钮（回家/公司/模拟）
- [ ] 超速视觉警告（红色边框）

### 🏗️ 高级功能 (P2)
- [ ] 配置变化处理 (onConfigurationChanged)
- [ ] 内存优化 (onTrimMemory)
- [ ] 路线选择面板（多路线对比）
- [ ] 设置面板（算路偏好）

---

## 测试验证

### 单元测试
```bash
./gradlew test --tests "*GoogleNav*"
```

### 集成测试
1. 启动应用，切换到 Google 导航模式
2. 验证监听器注册成功（查看 Logcat `GoogleNavManager`）
3. 搜索目的地并开始导航
4. 验证路线点已发送到 TCP 7709（查看 `CarrotManNetworkClient`）
5. 验证 CarrotManFields 字段更新（限速、转向、剩余距离等）

### 设备联调
1. 连接 comma3 设备
2. 开始 Google 导航
3. 确认 openpilot 收到导航数据（UDP 7706）
4. 确认路线点显示在设备上（TCP 7709）

---

## 参考资料

### 官方文档
- [Google Navigation SDK for Android](https://developers.google.com/maps/documentation/navigation/android-sdk/overview)
- [Navigator API Reference](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/libraries/navigation/Navigator)

### 项目内参考
- `carrotcode/refer SDK and Demo code/navigation-sample/` - Google 官方示例代码
- `TencentNavPage.kt` - 腾讯导航完整实现（1075行）
- `AmapMobileNavPage.kt` - 高德导航完整实现（1397行）

---

## 预期效果

完成所有增强后，GoogleNavPage 代码量预计达到 **800-1000 行**，功能完整度达到 **85%**（腾讯/高德水平），核心功能100%对齐。

### 功能完整性对比（完成后）
| 模块 | 高德 | 腾讯 | Google (增强后) |
|------|------|------|-----------------|
| 监听器 | 50+ | 20+ | 8 ✅ |
| 数据桥接 | 100% | 100% | 90% ✅ |
| UI 功能 | 100% | 100% | 75% ✅ |
| 路线发送 | ✅ | ✅ | ✅ |
| comma3 联动 | ✅ | ✅ | ✅ |
