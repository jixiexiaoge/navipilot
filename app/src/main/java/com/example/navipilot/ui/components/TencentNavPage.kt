package com.example.navipilot.ui.components

import android.speech.tts.TextToSpeech
import android.util.Log

import android.view.LayoutInflater
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.navipilot.CarrotManFields
import com.example.navipilot.TencentNavSdkBootstrap
import com.example.navipilot.R
import com.example.navipilot.navigation.TencentNavDataBridge
import com.example.navipilot.navigation.CoordinateConverter
import com.example.navipilot.ui.utils.localized
import com.tencent.navix.api.NavigatorZygote
import com.tencent.navix.api.config.BitmapCreator
import com.tencent.navix.api.config.LocatorStyleConfig
import com.tencent.navix.api.config.RouteMarkerStyleConfig
import com.tencent.navix.api.config.SimulatorConfig
import com.tencent.navix.api.layer.NavigatorLayerRootDrive
import com.tencent.navix.api.layer.NavigatorViewStub
import com.tencent.navix.api.model.NavDriveRoute
import com.tencent.navix.api.model.NavEnlargedMapInfo
import com.tencent.navix.api.model.NavMode
import com.tencent.navix.api.model.NavRouteReqParam
import com.tencent.navix.api.model.NavSearchPoint
import com.tencent.navix.api.navigator.NavigatorDrive
import com.tencent.navix.api.plan.DriveRoutePlanOptions
import com.tencent.navix.api.plan.DriveRoutePlanRequestCallback
import com.tencent.navix.api.plan.RoutePlanRequester
import com.tencent.navix.tts.api.TTSPlayer
import com.tencent.navix.tts.DefaultTTSPlayer
import com.tencent.navix.ui.NavigatorLayerViewDrive
import com.tencent.navix.ui.api.config.EnlargedMapUIConfig
import com.tencent.navix.ui.api.config.NavMapVisionConfig
import com.tencent.navix.ui.api.config.UIComponentConfig

private const val TAG = "TencentNavPage"

/**
 * 算路起点 WGS84：与主页地图车位来源一致，避免仅 latitude/longitude 为 0 时用 (0,0) 触发腾讯「起终点参数错误」。
 */
private fun resolveStartWgs84ForTencentRoute(
    currentLat: Double,
    currentLon: Double,
    fields: CarrotManFields?
): Pair<Double, Double> {
    if (currentLat != 0.0 && currentLon != 0.0) return currentLat to currentLon
    val f = fields ?: return 0.0 to 0.0
    if (f.latitude != 0.0 && f.longitude != 0.0) return f.latitude to f.longitude
    if (f.vpPosPointLat != 0.0 && f.vpPosPointLon != 0.0) return f.vpPosPointLat to f.vpPosPointLon
    return 0.0 to 0.0
}



/**
 * 腾讯导航页面（官方SDK标准样式）
 *
 * 功能：
 * - NavigatorViewStub嵌入完整导航
 * - 使用SDK默认UI和行为
 * - 导航数据桥接到CarrotManFields → Comma3
 *
 * 不影响现有GNSS定位系统（GPS + SBAS + 双频 + 卡尔曼滤波）。
 */
@Composable
fun TencentNavPage(
    carrotManFieldsState: MutableState<CarrotManFields>?,
    goalLat: Double = 0.0,
    goalLon: Double = 0.0,
    goalName: String = "",
    currentLat: Double = 0.0,
    currentLon: Double = 0.0,
    networkClient: com.example.navipilot.CarrotManNetworkClient? = null,
    deviceIP: String? = null,               // 🆕 WebRTC 视频设备 IP
    simulateNav: Boolean = false,           // 🆕 模拟导航模式（调试用）
    simulateSpeed: Int = 55,                // 🆕 模拟导航速度 km/h
    onEnterTencentMode: () -> Unit = {},  // 三模互斥：进入腾讯模式回调
    onExitTencentMode: () -> Unit = {},   // 三模互斥：退出腾讯模式回调
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 🆕 三模互斥：进入页面时切换到腾讯模式，退出时恢复
    DisposableEffect(Unit) {
        onEnterTencentMode()
        Log.i(TAG, "🔄 进入腾讯导航模式")
        onDispose {
            onExitTencentMode()
            Log.i(TAG, "🔄 退出腾讯导航模式")
        }
    }

    // ===== 屏幕常亮：MainActivity已全局设置FLAG_KEEP_SCREEN_ON，此处无需重复管理 =====
    // 🔧 修复：移除onDispose中的clearFlags，避免退出导航页面后全局屏幕常亮被清除

    val composableScope = rememberCoroutineScope()

    // ===== 超速语音警告 =====
    val ttsEngine = remember { mutableStateOf<TextToSpeech?>(null) }
    var lastOverspeedAlertTime by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.i(TAG, "✅ 超速语音引擎初始化成功")
            }
        }
        ttsEngine.value = tts
        onDispose {
            tts.stop()
            tts.shutdown()
            ttsEngine.value = null
        }
    }
    // 监听超速状态，每15秒最多播报一次
    val fields = carrotManFieldsState?.value
    val currentSpeed = fields?.nPosSpeed ?: 0.0
    val speedLimit = fields?.nRoadLimitSpeed ?: 0
    LaunchedEffect(currentSpeed, speedLimit) {
        if (speedLimit > 0 && currentSpeed > speedLimit + 5) {
            val now = System.currentTimeMillis()
            if (now - lastOverspeedAlertTime > 15_000L) {
                lastOverspeedAlertTime = now
                val speedInt = currentSpeed.toInt()
                ttsEngine.value?.speak(
                    "当前车速${speedInt}，超速，请减速",
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "overspeed_${now}"
                )
                Log.w(TAG, "⚠️ 超速语音警告: ${speedInt}km/h > ${speedLimit}km/h")
            }
        }
    }

    val navigatorDrive = remember {
        // 与 Application 中初始化双保险：避免未冷启动过 Application 路径时直接崩溃
        TencentNavSdkBootstrap.ensureInitialized(context.applicationContext as android.app.Application)
        NavigatorZygote.with(context.applicationContext)
            .navigator(NavigatorDrive::class.java)
    }

    // 北斗定位优先设置
    LaunchedEffect(Unit) {
        try {
            val zygote = NavigatorZygote.with(context.applicationContext)
            val locationApi = zygote.javaClass.getMethod("locationApi").invoke(zygote)
            if (locationApi != null) {
                val beidouConst = locationApi.javaClass.getField("GNSS_SOURCE_BEIDOU_FIRST").getInt(null)
                locationApi.javaClass.getMethod("setGnssSource", Int::class.javaPrimitiveType)
                    .invoke(locationApi, beidouConst)
                Log.i(TAG, "✅ 北斗定位优先已设置")
            }
        } catch (e: Exception) {
            Log.w(TAG, "北斗定位优先设置失败（SDK版本不支持）: ${e.message}")
        }
    }

    // 外部GPS注入开关
    val useExternalGps = remember {
        context.getSharedPreferences("external_gps_config", android.content.Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
    }

    // 外部GPS数据注入（通过Simulator API）
    LaunchedEffect(useExternalGps) {
        if (!useExternalGps) return@LaunchedEffect
        try {
            val simConfigClass = Class.forName("com.tencent.navix.api.model.SimulatorConfig")
            val builderMethod = simConfigClass.getMethod("builder")
            val builder = builderMethod.invoke(null)
            val typeClass = Class.forName("com.tencent.navix.api.model.SimulatorConfig\$Type")
            val externalType = typeClass.enumConstants?.filterIsInstance<Enum<*>>()
                ?.find { it.name == "USE_EXTERNAL_LOCATIONS" }
            if (externalType != null && builder != null) {
                builder.javaClass.getMethod("setType", typeClass).invoke(builder, externalType)
                val config = builder.javaClass.getMethod("build").invoke(builder)
                navigatorDrive.javaClass.getMethod("setSimulatorConfig", simConfigClass)
                    .invoke(navigatorDrive, config)
                Log.i(TAG, "✅ 外部GPS注入模式已启用")
            } else {
                Log.w(TAG, "USE_EXTERNAL_LOCATIONS 不可用")
            }
        } catch (e: Exception) {
            Log.w(TAG, "外部GPS注入配置失败（SDK版本不支持）: ${e.message}")
        }
    }

    // 持续注入外部GPS位置到SDK
    LaunchedEffect(useExternalGps, currentLat, currentLon) {
        if (!useExternalGps || currentLat == 0.0 || currentLon == 0.0) return@LaunchedEffect
        try {
            val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(currentLat, currentLon)
            val gpsLocClass = Class.forName("com.tencent.navix.api.model.NavGpsLocation")
            val gpsLoc = gpsLocClass.getDeclaredConstructor().newInstance()
            gpsLocClass.getMethod("setLatitude", Double::class.javaPrimitiveType).invoke(gpsLoc, gcjLat)
            gpsLocClass.getMethod("setLongitude", Double::class.javaPrimitiveType).invoke(gpsLoc, gcjLon)
            gpsLocClass.getMethod("setTime", Long::class.javaPrimitiveType).invoke(gpsLoc, System.currentTimeMillis())
            val fields = carrotManFieldsState?.value
            if (fields != null) {
                if (fields.gps_speed > 0) {
                    gpsLocClass.getMethod("setSpeed", Float::class.javaPrimitiveType)
                        .invoke(gpsLoc, fields.gps_speed.toFloat())
                }
                if (fields.heading > 0) {
                    gpsLocClass.getMethod("setBearing", Float::class.javaPrimitiveType)
                        .invoke(gpsLoc, fields.heading.toFloat())
                }
                if (fields.accuracy > 0) {
                    gpsLocClass.getMethod("setAccuracy", Float::class.javaPrimitiveType)
                        .invoke(gpsLoc, fields.accuracy.toFloat())
                }
            }
            val simulator = navigatorDrive.javaClass.getMethod("getSimulator").invoke(navigatorDrive)
            if (simulator != null) {
                simulator.javaClass.getMethod("updateExternalLocation", gpsLocClass).invoke(simulator, gpsLoc)
            }
        } catch (e: Exception) {
            // 🔧 修复：记录GPS注入失败的详细信息，便于排查问题
            Log.e(TAG, "❌ GPS注入失败: ${e.javaClass.simpleName} - ${e.message}", e)
            Log.e(TAG, "   坐标: WGS84($currentLat, $currentLon)")
            // 注意：GPS注入失败不影响导航功能，SDK会使用系统GPS
        }
    }

    val dataBridge = remember { TencentNavDataBridge(carrotManFieldsState) }

    var layerRootDrive by remember { mutableStateOf<NavigatorLayerRootDrive?>(null) }
    var layerViewDrive by remember { mutableStateOf<NavigatorLayerViewDrive?>(null) }
    var isNavStarted by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var routeAlternatives by remember { mutableStateOf<List<NavDriveRoute>>(emptyList()) }
    var selectedRouteIndex by remember { mutableStateOf(0) }
    var showRouteSelection by remember { mutableStateOf(false) }
    var onRouteSelected by remember { mutableStateOf<(Int) -> Unit>({}) }
    var isSimulating by remember { mutableStateOf(false) }
    // showVideo 已移除

    // 读取车牌号（用于限行避开）
    val plateNumber = remember {
        context.getSharedPreferences("map_addresses", android.content.Context.MODE_PRIVATE)
            .getString("plate_number", "") ?: ""
    }

    // 构建限行避开选项（车牌号非空时启用）
    val routePlanOptions = remember(plateNumber) {
        if (plateNumber.isNotBlank()) {
            DriveRoutePlanOptions.Companion.newBuilder()
                .licenseNumber(plateNumber)
                .build()
        } else null
    }
    if (plateNumber.isNotBlank()) {
       //Log.i(TAG, "🚗 限行避开已启用，车牌号: $plateNumber")
    }

    // 夜间模式自动切换（根据时间判断）
    LaunchedEffect(layerRootDrive) {
        val root = layerRootDrive ?: return@LaunchedEffect
        try {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val isNight = hour < 6 || hour >= 19
            val dayNightClass = Class.forName("com.tencent.navix.api.model.NavDayNightMode")
            val allModes = dayNightClass.enumConstants?.filterIsInstance<Enum<*>>()
            val targetMode = if (isNight) {
                allModes?.find { it.name.contains("NIGHT", ignoreCase = true) || it.name.contains("Night") }
            } else {
                allModes?.find { it.name.contains("DAY", ignoreCase = true) || it.name.contains("Day") }
            }
            val autoMode = allModes?.find { it.name.contains("AUTO", ignoreCase = true) }
            val modeToSet = autoMode ?: targetMode
            if (modeToSet != null) {
                try {
                    root.javaClass.getMethod("setDayNightMode", dayNightClass)
                        .invoke(root, modeToSet)
                } catch (e1: Exception) {
                    // 🔧 修复：尝试使用接口类型（SDK版本兼容性）
                    Log.w(TAG, "日夜模式设置失败（尝试接口类型）: ${e1.message}")
                    try {
                        val ifaceType = dayNightClass.interfaces.firstOrNull() ?: dayNightClass
                        root.javaClass.getMethod("setDayNightMode", ifaceType)
                            .invoke(root, modeToSet)
                    } catch (e2: Exception) {
                        // 部分 SDK 版本无此反射签名，不影响导航；避免用 Log.e+堆栈误导为严重故障
                        Log.d(TAG, "日夜模式反射跳过（SDK 差异）: ${e2.message}")
                    }
                }
                Log.i(TAG, "🌙 日夜模式: ${modeToSet.name} (hour=$hour)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "日夜模式设置失败: ${e.message}")
        }
    }

    // 安全退出：停止导航 → 返回主页
    val safeBack: () -> Unit = {
        try {
            if (isNavStarted) {
                navigatorDrive.stopNavigation()
                isNavStarted = false
                // 🔧 修复：退出时关闭模拟器，防止下次真实导航仍走模拟
                if (isSimulating) {
                    try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                }
                isSimulating = false
                dataBridge.onNavigationStopped()
                Log.i(TAG, "🔙 返回前已停止导航")
            }
        } catch (e: Exception) {
            Log.w(TAG, "返回时停止导航异常: ${e.message}")
        }
        onBack()
    }

    // 处理系统返回键和全面屏手势返回
    BackHandler(enabled = true) { safeBack() }

    // 到达目的地自动停止导航
    val arrivalObserver = remember {
        object : com.tencent.navix.api.observer.SimpleNavigatorDriveObserver() {
            override fun onWillArriveDestination() {
                super.onWillArriveDestination()
                Log.i(TAG, "🏁 即将到达目的地，自动停止导航")
                navigatorDrive.stopNavigation()
                // 🔧 修复：到达时关闭模拟器
                if (isSimulating) {
                    try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                }
                isNavStarted = false
                isSimulating = false
                dataBridge.onNavigationStopped()
            }
        }
    }

    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> layerRootDrive?.onStart()
                Lifecycle.Event.ON_RESUME -> layerRootDrive?.onResume()
                Lifecycle.Event.ON_PAUSE -> layerRootDrive?.onPause()
                Lifecycle.Event.ON_STOP -> layerRootDrive?.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                navigatorDrive.unregisterObserver(dataBridge.driveObserver)
                navigatorDrive.unregisterObserver(arrivalObserver)
                layerViewDrive?.let { vl ->
                    @Suppress("UNCHECKED_CAST")
                    layerRootDrive?.removeViewLayer(
                        vl as com.tencent.navix.api.layer.NavigatorLayer<com.tencent.navix.api.navigator.NavigatorDrive>
                    )
                }
                navigatorDrive.unbindView(layerRootDrive)
                val tts = navigatorDrive.ttsPlayer
                if (tts is TTSPlayer) tts.stop()
                // 🔧 修复：页面销毁时关闭模拟器
                try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                navigatorDrive.stopNavigation()
                dataBridge.onNavigationStopped()
                layerRootDrive?.onDestroy()
            } catch (e: Exception) {
                Log.e(TAG, "清理导航资源异常: ${e.message}")
            }
        }
    }

    // 超速视觉警告（红色边框闪烁）
    val isOverSpeed = speedLimit > 0 && currentSpeed > speedLimit + 3
    val overspeedAlpha by animateFloatAsState(
        targetValue = if (isOverSpeed) 0.6f else 0f,
        animationSpec = if (isOverSpeed) infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ) else tween(300),
        label = "overspeed"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 导航地图视图
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.layout_tencent_nav, null)

                val viewStub = view.findViewById<NavigatorViewStub>(
                    R.id.tencent_navigator_view_stub
                )
                viewStub.setTravelMode(NavRouteReqParam.TravelMode.TravelModeDriving)
                viewStub.inflate()

                @Suppress("UNCHECKED_CAST")
                val root = viewStub.getNavigatorView() as NavigatorLayerRootDrive
                layerRootDrive = root

                // 自定义车标样式
                try {
                    root.setLocatorStyleConfig(
                        LocatorStyleConfig.builder()
                            .setCompassEnable(true)
                            .setDayLocatorStyle(
                                LocatorStyleConfig.LocatorStyle.builder()
                                    .setLocator(BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_locator_car))
                                    .setLocatorForWeakGps(BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_locator_car))
                                    .build()
                            )
                            .setNightLocatorStyle(
                                LocatorStyleConfig.LocatorStyle.builder()
                                    .setLocator(BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_locator_car_night))
                                    .setLocatorForWeakGps(BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_locator_car_night))
                                    .build()
                            )
                            .build()
                    )
                    Log.i(TAG, "✅ 自定义车标已设置")
                } catch (e: Exception) {
                    Log.w(TAG, "自定义车标设置失败: ${e.message}")
                }

                // 自定义起终点Marker
                try {
                    root.setRouteMarkerStyleConfig(
                        RouteMarkerStyleConfig.builder()
                            .setDefaultMakerOfStart(
                                BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_start),
                                BitmapCreator.NullBitmapCreator()
                            )
                            .setDefaultMarkerOfDest(
                                BitmapCreator.ResourceBitmapCreator(R.drawable.app_icon_finish),
                                BitmapCreator.NullBitmapCreator()
                            )
                            .build()
                    )
                    Log.i(TAG, "✅ 自定义Marker已设置")
                } catch (e: Exception) {
                    Log.w(TAG, "自定义Marker设置失败: ${e.message}")
                }

                // 建筑物3D效果
                try {
                    val mapApi = root.javaClass.getMethod("getMapApi").invoke(root)
                    if (mapApi != null) {
                        mapApi.javaClass.getMethod("setBuilding3dEffectEnable", Boolean::class.javaPrimitiveType)
                            .invoke(mapApi, true)
                        Log.i(TAG, "✅ 建筑物3D效果已启用")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "建筑物3D效果设置失败: ${e.message}")
                }

                // 添加默认UI面板（严格按照官方demo BaseNavActivity）
                // 使用 Activity 上下文创建（Theme.Navipilot → MaterialComponents），
                // 确保 SDK 内部 NavInfoView 等组件能解析到所需的主题属性
                var activityCtx: android.content.Context = ctx
                while (activityCtx is android.content.ContextWrapper && activityCtx !is android.app.Activity) {
                    activityCtx = activityCtx.baseContext
                }
                val viewLayer = NavigatorLayerViewDrive(activityCtx)
                layerViewDrive = viewLayer

                @Suppress("UNCHECKED_CAST")
                root.addViewLayer(
                    viewLayer as com.tencent.navix.api.layer.NavigatorLayer<com.tencent.navix.api.navigator.NavigatorDrive>
                )

                // 绑定导航器（严格按照官方demo BaseNavActivity）
                navigatorDrive.bindView(root)
                navigatorDrive.registerObserver(dataBridge.driveObserver)
                navigatorDrive.registerObserver(arrivalObserver)

                // 启用TTS语音播报
                try {
                    navigatorDrive.setTTSPlayer(DefaultTTSPlayer())
                    Log.i(TAG, "✅ TTS语音播报已启用")
                } catch (e: Exception) {
                    Log.w(TAG, "TTS初始化失败: ${e.message}")
                }

                // 如果有目的地坐标，自动算路
                if (goalLat != 0.0 && goalLon != 0.0) {

                    // WGS-84 → GCJ-02 坐标转换
                    val (gcjGoalLat, gcjGoalLon) = CoordinateConverter.wgs84ToGcj02(goalLat, goalLon)
                    val (wgsStartLat, wgsStartLon) = resolveStartWgs84ForTencentRoute(
                        currentLat, currentLon, carrotManFieldsState?.value
                    )
                    val startPoint = if (wgsStartLat != 0.0 && wgsStartLon != 0.0) {
                        val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(wgsStartLat, wgsStartLon)
                        NavSearchPoint(gcjLat, gcjLon)
                    } else {
                        NavSearchPoint(0.0, 0.0)
                    }

                    val routeReq = RoutePlanRequester.Companion.newBuilder(
                        NavRouteReqParam.TravelMode.TravelModeDriving
                    ).start(startPoint)
                    routeReq.end(NavSearchPoint(gcjGoalLat, gcjGoalLon))
                    if (routePlanOptions != null) routeReq.options(routePlanOptions)

                    // 模拟导航模式（仅 simulateNav=true 时启用，否则确保关闭）
                    if (simulateNav) {
                        navigatorDrive.simulator()
                            .setConfig(
                                SimulatorConfig.builder(SimulatorConfig.Type.SIMULATE_LOCATIONS_ALONG_ROUTE)
                                    .setSimulateSpeed(simulateSpeed)
                                    .build()
                            )
                            .setEnable(true)
                        Log.i(TAG, "🎮 模拟导航已启用: ${simulateSpeed}km/h")
                    } else {
                        // 🔧 修复：非模拟模式确保关闭模拟器（防止上次模拟残留）
                        try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                    }

                    // 启动路线选择的辅助函数
                    fun startNavigationWithRoute(route: NavDriveRoute, index: Int) {
                        try {
                            // 1. 提取路线点
                            dataBridge.extractRoutePoints(route)
                            
                            // 2. 检查数据状态
                            val f = carrotManFieldsState?.value
                            Log.d(TAG, "🔍 路线点状态检查:")
                            Log.d(TAG, "  - carrotManFieldsState: ${if (f != null) "有效" else "null"}")
                            Log.d(TAG, "  - tencentRoutePointsReady: ${f?.tencentSlice?.tencentRoutePointsReady ?: false}")
                            Log.d(TAG, "  - tencentRoutePoints.size: ${f?.tencentSlice?.tencentRoutePoints?.size ?: 0}")
                            Log.d(TAG, "  - networkClient: ${if (networkClient != null) "已连接" else "null"}")
                            
                            // 3. 发送路线点
                            if (f != null && f.tencentSlice?.tencentRoutePointsReady == true) {
                                if (networkClient != null) {
                                    // 🆕 调试：打印前3个点
                                    if (f.tencentSlice?.tencentRoutePoints?.isNotEmpty() == true) {
                                        Log.d(TAG, "🛣️ 前3个路线点 (WGS-84):")
                                        f.tencentSlice?.tencentRoutePoints?.take(3)?.forEachIndexed { i, (lon, lat) ->
                                            Log.d(TAG, "  [$i] lon=${"%.6f".format(lon)}, lat=${"%.6f".format(lat)}")
                                        }
                                    }
                                    
                                    networkClient?.sendRoutePointsViaTcp(f.tencentSlice?.tencentRoutePoints ?: emptyList())
                                    Log.i(TAG, "✅ 路线点已提取并发送: ${f.tencentSlice?.tencentRoutePoints?.size ?: 0}个点")
                                } else {
                                    Log.w(TAG, "⚠️ networkClient 为 null，无法发送路线点")
                                }
                            } else {
                                if (f == null) {
                                    Log.w(TAG, "⚠️ carrotManFieldsState 为 null")
                                } else if (f.tencentSlice?.tencentRoutePointsReady != true) {
                                    Log.w(TAG, "⚠️ tencentRoutePointsReady = false")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 路线点提取/发送失败: ${e.message}", e)
                        }
                        
                        // 4. 启动导航
                        navigatorDrive.startNavigation(route.routeId)
                        isNavStarted = true
                        isSimulating = false
                        showRouteSelection = false
                        Log.i(TAG, "✅ 导航已启动: 路线${index + 1} $goalName")
                    }

                    // 鉴权重试计数器
                    var authRetryCount = 0
                    val maxAuthRetries = 5
                    
                    fun doSearchRoute() {
                        navigatorDrive.searchRoute(
                            routeReq.build(),
                            DriveRoutePlanRequestCallback { navRoutePlan, error ->
                                if (error != null) {
                                    val errMsg = error.message ?: ""
                                    if (errMsg.contains("鉴权") && authRetryCount < maxAuthRetries) {
                                        authRetryCount++
                                        val delayMs = if (authRetryCount <= 2) 2000L else 3000L
                                        Log.w(TAG, "⏳ 鉴权未完成，${authRetryCount}/${maxAuthRetries} 次重试...")
                                        routeError = localized(
                                            "鉴权中，正在重试(${authRetryCount}/${maxAuthRetries})...",
                                            "Authenticating, retrying(${authRetryCount}/${maxAuthRetries})..."
                                        )
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            doSearchRoute()
                                        }, delayMs)
                                        return@DriveRoutePlanRequestCallback
                                    }
                                    routeError = if (errMsg.contains("鉴权")) {
                                        localized(
                                            "鉴权失败: 请检查腾讯地图Key是否已开通导航SDK权限",
                                            "Auth failed: Check Tencent Map Key Nav SDK permission"
                                        )
                                    } else {
                                        localized("算路失败: ${error.message}", "Route failed: ${error.message}")
                                    }
                                    Log.e(TAG, "❌ 算路失败: ${error.message}")
                                    return@DriveRoutePlanRequestCallback
                                }
                                routeError = null
                                @Suppress("DEPRECATION")
                                val routes = navRoutePlan?.routeDatas
                                if (!routes.isNullOrEmpty()) {
                                    routeAlternatives = routes
                                    selectedRouteIndex = 0
                                    Log.i(TAG, "📍 算路成功: ${routes.size}条路线")

                                    // 🆕 Item 9: 多路线时显示选择面板，单路线直接开始
                                    if (routes.size > 1) {
                                        showRouteSelection = true
                                        Log.i(TAG, "🗺️ ${routes.size}条路线可选，显示选择面板")
                                    } else {
                                        startNavigationWithRoute(routes[0], 0)
                                    }
                                }
                            }
                        )
                    }
                    // 保存startNavigationWithRoute引用供路线选择UI使用
                    onRouteSelected = { index ->
                        val routes = routeAlternatives
                        if (index in routes.indices) {
                            selectedRouteIndex = index
                            startNavigationWithRoute(routes[index], index)
                        }
                    }
                    doSearchRoute()
                } else if (currentLat != 0.0 && currentLon != 0.0) {
                    // 无目的地时，将地图中心移动到当前GPS位置
                    try {
                        val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(currentLat, currentLon)
                        val mapApi = root.javaClass.getMethod("getMapApi").invoke(root)
                        if (mapApi != null) {
                            val latLngClass = Class.forName("com.tencent.tencentmap.mapsdk.maps.model.LatLng")
                            val latLng = latLngClass.getConstructor(
                                Double::class.javaPrimitiveType,
                                Double::class.javaPrimitiveType
                            ).newInstance(gcjLat, gcjLon)
                            try {
                                val cameraUpdateClass = Class.forName("com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory")
                                val newLatLngZoom = cameraUpdateClass.getMethod(
                                    "newLatLngZoom",
                                    latLngClass,
                                    Float::class.javaPrimitiveType
                                ).invoke(null, latLng, 16f)
                                mapApi.javaClass.getMethod(
                                    "moveCamera",
                                    Class.forName("com.tencent.tencentmap.mapsdk.maps.CameraUpdate")
                                ).invoke(mapApi, newLatLngZoom)
                                Log.i(TAG, "📍 地图已定位到当前位置: $gcjLat, $gcjLon")
                            } catch (e2: Exception) {
                                try {
                                    mapApi.javaClass.getMethod("setCenter", latLngClass)
                                        .invoke(mapApi, latLng)
                                    Log.i(TAG, "📍 地图已定位到当前位置(setCenter): $gcjLat, $gcjLon")
                                } catch (e3: Exception) {
                                    Log.w(TAG, "地图定位失败: ${e3.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "无目的地时地图定位失败: ${e.message}")
                    }
                }

                view
            },
            modifier = Modifier.fillMaxSize()
        )

        // 🎮 底部快捷按钮行（未导航时显示）
        if (!isNavStarted && !showRouteSelection) {
            val addrPrefs = remember {
                context.getSharedPreferences("map_addresses", android.content.Context.MODE_PRIVATE)
            }
            
            // 🔧 修复：使用CoordinatePreferences读取坐标，避免Float精度损失
            val homeName = remember { addrPrefs.getString("home_name", null) }
            val homeLat = remember { 
                com.example.navipilot.utils.CoordinatePreferences.getCoordinate(addrPrefs, "home_lat", 0.0)
            }
            val homeLon = remember { 
                com.example.navipilot.utils.CoordinatePreferences.getCoordinate(addrPrefs, "home_lon", 0.0)
            }
            val companyName = remember { addrPrefs.getString("company_name", null) }
            val companyLat = remember { 
                com.example.navipilot.utils.CoordinatePreferences.getCoordinate(addrPrefs, "company_lat", 0.0)
            }
            val companyLon = remember { 
                com.example.navipilot.utils.CoordinatePreferences.getCoordinate(addrPrefs, "company_lon", 0.0)
            }
            val hasHome = homeName != null && homeLat != 0.0 && homeLon != 0.0
            val hasCompany = companyName != null && companyLat != 0.0 && companyLon != 0.0

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 🏠 一键回家
                if (hasHome) {
                    FloatingActionButton(
                        onClick = {
                            // 🔧 修复：真实导航前确保关闭模拟器
                            try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                            val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(homeLat, homeLon)
                            val (wgsSLat, wgsSLon) = resolveStartWgs84ForTencentRoute(
                                currentLat, currentLon, carrotManFieldsState?.value
                            )
                            val startPt = if (wgsSLat != 0.0 && wgsSLon != 0.0) {
                                val (sLat, sLon) = CoordinateConverter.wgs84ToGcj02(wgsSLat, wgsSLon)
                                NavSearchPoint(sLat, sLon)
                            } else NavSearchPoint(0.0, 0.0)
                            val req = RoutePlanRequester.Companion.newBuilder(
                                NavRouteReqParam.TravelMode.TravelModeDriving
                            ).start(startPt)
                            req.end(NavSearchPoint(gcjLat, gcjLon))
                            if (routePlanOptions != null) req.options(routePlanOptions)
                            navigatorDrive.searchRoute(req.build(),
                                DriveRoutePlanRequestCallback { plan, err ->
                                    if (err != null) {
                                        routeError = localized("回家算路失败: ${err.message}", "Home route failed: ${err.message}")
                                        return@DriveRoutePlanRequestCallback
                                    }
                                    @Suppress("DEPRECATION")
                                    val routes = plan?.routeDatas
                                    if (!routes.isNullOrEmpty()) {
                                        routeAlternatives = routes
                                        selectedRouteIndex = 0
                                        navigatorDrive.startNavigation(routes[0].routeId)
                                        isNavStarted = true
                                        isSimulating = false
                                        Log.i(TAG, "🏠 一键导航回家: $homeName")
                                    }
                                })
                        },
                        modifier = Modifier.size(40.dp),
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.85f),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(20.dp))
                    }
                }
                // 🏢 一键去公司
                if (hasCompany) {
                    FloatingActionButton(
                        onClick = {
                            // 🔧 修复：真实导航前确保关闭模拟器
                            try { navigatorDrive.simulator().setEnable(false) } catch (_: Exception) {}
                            val (gcjLat, gcjLon) = CoordinateConverter.wgs84ToGcj02(companyLat, companyLon)
                            val (wgsSLat, wgsSLon) = resolveStartWgs84ForTencentRoute(
                                currentLat, currentLon, carrotManFieldsState?.value
                            )
                            val startPt = if (wgsSLat != 0.0 && wgsSLon != 0.0) {
                                val (sLat, sLon) = CoordinateConverter.wgs84ToGcj02(wgsSLat, wgsSLon)
                                NavSearchPoint(sLat, sLon)
                            } else NavSearchPoint(0.0, 0.0)
                            val req = RoutePlanRequester.Companion.newBuilder(
                                NavRouteReqParam.TravelMode.TravelModeDriving
                            ).start(startPt)
                            req.end(NavSearchPoint(gcjLat, gcjLon))
                            if (routePlanOptions != null) req.options(routePlanOptions)
                            navigatorDrive.searchRoute(req.build(),
                                DriveRoutePlanRequestCallback { plan, err ->
                                    if (err != null) {
                                        routeError = localized("去公司算路失败: ${err.message}", "Work route failed: ${err.message}")
                                        return@DriveRoutePlanRequestCallback
                                    }
                                    @Suppress("DEPRECATION")
                                    val routes = plan?.routeDatas
                                    if (!routes.isNullOrEmpty()) {
                                        routeAlternatives = routes
                                        selectedRouteIndex = 0
                                        navigatorDrive.startNavigation(routes[0].routeId)
                                        isNavStarted = true
                                        isSimulating = false
                                        Log.i(TAG, "🏢 一键导航去公司: $companyName")
                                    }
                                })
                        },
                        modifier = Modifier.size(40.dp),
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.85f),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Business, contentDescription = "Work", modifier = Modifier.size(20.dp))
                    }
                }
                // 🎮 模拟导航
                FloatingActionButton(
                    onClick = {
                        val simDestLat = 39.9042
                        val simDestLon = 116.3912
                        val startPt = if (currentLat != 0.0 && currentLon != 0.0) {
                            val (sLat, sLon) = CoordinateConverter.wgs84ToGcj02(currentLat, currentLon)
                            NavSearchPoint(sLat, sLon)
                        } else {
                            NavSearchPoint(39.8950, 116.3220)
                        }
                        val req = RoutePlanRequester.Companion.newBuilder(
                            NavRouteReqParam.TravelMode.TravelModeDriving
                        ).start(startPt)
                        req.end(NavSearchPoint(simDestLat, simDestLon))
                        if (routePlanOptions != null) req.options(routePlanOptions)
                        routeError = localized("模拟算路中...", "Calculating sim route...")
                        navigatorDrive.searchRoute(req.build(),
                            DriveRoutePlanRequestCallback { plan, err ->
                                if (err != null) {
                                    routeError = localized("模拟算路失败: ${err.message}", "Sim route failed: ${err.message}")
                                    return@DriveRoutePlanRequestCallback
                                }
                                routeError = null
                                @Suppress("DEPRECATION")
                                val routes = plan?.routeDatas
                                if (!routes.isNullOrEmpty()) {
                                    routeAlternatives = routes
                                    selectedRouteIndex = 0
                                    navigatorDrive.simulator()
                                        .setConfig(
                                            SimulatorConfig
                                                .builder(SimulatorConfig.Type.SIMULATE_LOCATIONS_ALONG_ROUTE)
                                                .setSimulateSpeed(60)
                                                .build()
                                        )
                                        .setEnable(true)
                                    navigatorDrive.startNavigation(routes[0].routeId)
                                    isNavStarted = true
                                    isSimulating = true
                                    Log.i(TAG, "🎮 模拟导航已启动 → 北京天安门, 60km/h")
                                    
                                    // 🔍 5秒后运行诊断，检查数据完整性
                                    composableScope.launch {
                                        delay(5000)
                                        dataBridge.diagnoseSimulationData()
                                    }
                                }
                            })
                    },
                    modifier = Modifier.size(40.dp),
                    containerColor = Color(0xFFEF4444).copy(alpha = 0.85f),
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Simulate", modifier = Modifier.size(20.dp))
                }
            }
        }

        // 底部定位信息面板已移除

        // 视频功能已移除

        // 超速红色边框闪烁
        if (overspeedAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, Color(0xFFEF4444).copy(alpha = overspeedAlpha), RoundedCornerShape(0.dp))
            )
        }

        // 错误提示
        routeError?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                color = Color(0xFFEF4444).copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { routeError = null; safeBack() }) {
                        Text(localized("返回", "Back"))
                    }
                }
            }
        }

        // 路线选择面板
        if (showRouteSelection && routeAlternatives.size > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    // 标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (goalName.isNotBlank()) goalName
                                   else localized("${routeAlternatives.size}条路线", "${routeAlternatives.size} routes"),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            onClick = { showRouteSelection = false; safeBack() },
                            color = Color.Transparent,
                            shape = CircleShape
                        ) {
                            Text(
                                "✕",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 路线卡片
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        routeAlternatives.forEachIndexed { index, route ->
                            val isSelected = index == selectedRouteIndex
                            val distKm = route.distance / 1000.0
                            val distText = if (distKm >= 1) String.format("%.1fkm", distKm) else "${route.distance}m"
                            val tag = try { route.tag ?: "" } catch (_: Exception) { "" }
                            // 通过反射尝试获取预计时间(秒)
                            val durationSec = try {
                                route.javaClass.getMethod("getDuration").invoke(route) as? Int ?: 0
                            } catch (_: Exception) {
                                try {
                                    route.javaClass.getField("duration").getInt(route)
                                } catch (_: Exception) { 0 }
                            }
                            val timeText = when {
                                durationSec >= 3600 -> "${durationSec / 3600}h${(durationSec % 3600) / 60}min"
                                durationSec > 0 -> "${durationSec / 60}min"
                                else -> ""
                            }

                            val bgColor = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B)
                            val borderColor = if (isSelected) Color(0xFF10B981) else Color(0xFF334155)

                            Surface(
                                onClick = {
                                    selectedRouteIndex = index
                                    onRouteSelected(index)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                color = bgColor,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, borderColor
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // 标签名
                                    Text(
                                        text = if (tag.isNotEmpty()) tag else localized("方案${index + 1}", "Route ${index + 1}"),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    // 距离 + 时间
                                    Text(
                                        text = if (timeText.isNotEmpty()) "$distText · $timeText" else distText,
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
