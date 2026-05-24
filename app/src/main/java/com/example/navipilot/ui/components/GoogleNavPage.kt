package com.example.navipilot.ui.components

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.LifecycleEventObserver
import com.example.navipilot.CarrotManFields
import com.example.navipilot.R
import com.example.navipilot.navigation.GoogleNavManager
import com.example.navipilot.ui.utils.localized
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator

private const val TAG = "GoogleNavPage"

/**
 * 路线全览 / 恢复跟车视图
 */
private fun toggleOverview(
    context: android.content.Context,
    navViewRef: NavigationView?,
    navigator: Navigator?,
    googleMapRef: GoogleMap?,
    currentlyOverview: Boolean,
    onResult: (Boolean) -> Unit
) {
    try {
        if (currentlyOverview) {
            // 恢复跟车视图 — 使用默认的跟随模式
            googleMapRef?.followMyLocation(GoogleMap.CameraPerspective.TILTED)
            onResult(false)
            Log.i(TAG, "🗺️ 恢复跟车视图")
        } else {
            // 全览路线 — 从 navigator 获取路线所有坐标点，计算 LatLngBounds 并 animate
            val routeSegments = navigator?.routeSegments
            if (routeSegments != null && routeSegments.isNotEmpty()) {
                val allPoints = mutableListOf<com.google.android.gms.maps.model.LatLng>()
                routeSegments.forEach { segment ->
                    segment.latLngs?.forEach { latLng ->
                        allPoints.add(latLng)
                    }
                }
                if (allPoints.isNotEmpty()) {
                    val boundsBuilder = LatLngBounds.builder()
                    allPoints.forEach { boundsBuilder.include(it) }
                    val bounds = boundsBuilder.build()
                    googleMapRef?.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds, 100)
                    )
                    onResult(true)
                    Log.i(TAG, "🗺️ 路线全览: ${allPoints.size} 个坐标点")
                } else {
                    Toast.makeText(context, "路线坐标为空", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "暂无路线数据", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "toggleOverview: ${e.message}")
        Toast.makeText(context, "全览操作失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Google 导航页面（官方 Navigation SDK）
 *
 * 功能：
 * - 使用 NavigationView 嵌入完整导航
 * - 使用 SDK 默认 UI 和行为
 * - 导航数据桥接到 CarrotManFields → Comma3
 *
 * Google Maps 使用 WGS-84 坐标系，与内部存储一致，无需坐标转换
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleNavPage(
    navManager: GoogleNavManager? = null,
    carrotManFieldsState: MutableState<CarrotManFields>?,
    goalLat: Double = 0.0,
    goalLon: Double = 0.0,
    goalName: String = "",
    currentLat: Double = 0.0,
    currentLon: Double = 0.0,
    networkClient: com.example.navipilot.CarrotManNetworkClient? = null,
    onEnterGoogleMode: () -> Unit = {},
    onExitGoogleMode: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownsNavManager = navManager == null

    // 三模互斥：进入页面时切换到 Google 模式，退出时恢复
    DisposableEffect(Unit) {
        onEnterGoogleMode()
        Log.i(TAG, "🔄 进入 Google 导航模式")
        onDispose {
            onExitGoogleMode()
            Log.i(TAG, "🔄 退出 Google 导航模式")
        }
    }

    // 屏幕常亮
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 创建/复用导航管理器（复用可避免重复初始化导致的几十秒等待）
    val resolvedNavManager = navManager ?: remember { GoogleNavManager(context, carrotManFieldsState) }

    var isNavStarted by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var navigator by remember { mutableStateOf<Navigator?>(null) }
    var arrivalListenerRegisteredForNav by remember { mutableStateOf<Navigator?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var navViewRef by remember { mutableStateOf<NavigationView?>(null) }
    var loadingMessage by remember { mutableStateOf("正在初始化 Google 导航...") }
    var navigatorInitialized by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf(false) }
    var isRoutePlanning by remember { mutableStateOf(false) }

    // 🆕 P0: 谷歌地图引用（用于视角控制、全览等）
    var googleMapRef by remember { mutableStateOf<GoogleMap?>(null) }

    // 🆕 P0: "更多"菜单 UI 状态
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var overviewNow by remember { mutableStateOf(false) }
    var showNightModeSheet by remember { mutableStateOf(false) }
    var showDisplaySheet by remember { mutableStateOf(false) }
    var showCameraSheet by remember { mutableStateOf(false) }
    var showStrategySheet by remember { mutableStateOf(false) }

    // 🆕 P0: NavigationView UI 控制开关状态
    var nightMode by remember { mutableStateOf(ForceNightMode.AUTO) }
    var navUiEnabled by remember { mutableStateOf(true) }
    var tripProgressBarEnabled by remember { mutableStateOf(false) }
    var myLocationEnabled by remember { mutableStateOf(true) }
    var cameraPerspective by remember { mutableStateOf(GoogleMap.CameraPerspective.TILTED) }

    // 🆕 P0: 到达监听器引用（用于 onDestroy 清理）
    var arrivalListener by remember { mutableStateOf<Navigator.ArrivalListener?>(null) }

    // 🔧 修复: Navigator 初始化必须在 navView 创建后立即同步执行
    // 参考官方 NavViewActivity.kt，initializeNavigationApi 在 onCreate 中调用，不使用协程等待
    // 使用 navigatorInitialized 标志防止重复初始化

    // 自动清除路线错误（30秒后）
    LaunchedEffect(routeError) {
        if (routeError != null) {
            delay(30_000)
            routeError = null
        }
    }

    var retryNavigation by remember { mutableStateOf(false) }

    // 导航初始化超时保护（60秒）
    LaunchedEffect(navigatorInitialized) {
        if (!navigatorInitialized) {
            delay(60_000)
            if (!isReady && routeError == null) {
                routeError = localized(
                    "导航服务初始化超时（60秒），请检查网络连接后重试",
                    "Navigation init timeout (60s). Check network and retry."
                )
                navigatorInitialized = false
            }
        }
    }
    LaunchedEffect(retryNavigation) {
        if (retryNavigation && goalLat != 0.0 && goalLon != 0.0) {
            retryNavigation = false
            routeError = null
            pendingNavigation = true
            isRoutePlanning = true
        }
    }

    LaunchedEffect(navViewRef, navigatorInitialized) {
        val view = navViewRef ?: return@LaunchedEffect
        if (navigatorInitialized) return@LaunchedEffect  // 防止重复初始化

        // 如果 Manager 已经持有 Navigator（例如从上次进入 Google 模式复用），直接复用避免长等待
        if (resolvedNavManager.isReady() && resolvedNavManager.navigator != null) {
            val nav = resolvedNavManager.navigator!!
            navigator = nav
            isReady = true
            navigatorInitialized = true

            if (arrivalListenerRegisteredForNav !== nav) {
                val listener = Navigator.ArrivalListener {
                    Log.i(TAG, "🏁 到达目的地")
                    nav.clearDestinations()
                    resolvedNavManager.stopNavigation()
                    isNavStarted = false
                    isRoutePlanning = false
                }
                nav.addArrivalListener(listener)
                arrivalListener = listener
                arrivalListenerRegisteredForNav = nav
                Log.i(TAG, "✅ 到达监听器已注册")
            }

            if (goalLat != 0.0 && goalLon != 0.0) {
                pendingNavigation = true
                Log.i(TAG, "📌 复用 Navigator：导航请求已排队")
            }
            Log.i(TAG, "✅ 复用已初始化的 Google Navigator")
            return@LaunchedEffect
        }

        navigatorInitialized = true
        Log.i(TAG, "✅ NavigationView 已准备好，开始初始化 Navigator...")

        NavigationApi.getNavigator(
            context as android.app.Activity,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(nav: Navigator) {
                    Log.i(TAG, "✅ Google Navigator 准备好了")
                    Log.i(TAG, "  目的地: ($goalLat, $goalLon) $goalName")
                    Log.i(TAG, "  起点: ($currentLat, $currentLon)")

                    navigator = nav
                    isReady = true

                    // 将 navigator 注入 navManager（会自动注册监听器和初始化数据桥接）
                    resolvedNavManager.setNavigator(nav)

                    // 设置任务移除行为
                    nav.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

                    // 注册到达监听器
                    if (arrivalListenerRegisteredForNav !== nav) {
                        val listener = Navigator.ArrivalListener {
                            Log.i(TAG, "🏁 到达目的地")
                            nav.clearDestinations()
                            resolvedNavManager.stopNavigation()
                            isNavStarted = false
                            isRoutePlanning = false
                        }
                        nav.addArrivalListener(listener)
                        arrivalListener = listener
                        arrivalListenerRegisteredForNav = nav
                    }

                    // 初始化地图相机（显示起点位置）
                    try {
                        val cameraLat = if (currentLat != 0.0) currentLat else goalLat
                        val cameraLon = if (currentLon != 0.0) currentLon else goalLon
                        if (cameraLat != 0.0 && cameraLon != 0.0) {
                            view.getMapAsync { googleMap ->
                                try {
                                    val position = LatLng(cameraLat, cameraLon)
                                    val cameraUpdate = CameraUpdateFactory.newLatLngZoom(position, 15f)
                                    googleMap.moveCamera(cameraUpdate)

                                    // 启用我的位置图层（需定位权限，否则崩溃）
                                    try {
                                        googleMap.isMyLocationEnabled = true
                                    } catch (e: SecurityException) {
                                        Log.w(TAG, "定位权限未授予，无法启用 MyLocation 图层")
                                    }

                                    googleMapRef = googleMap

                                    Log.i(TAG, "✅ 地图相机已初始化: ($cameraLat, $cameraLon)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "地图相机初始化失败: ${e.message}")
                                }
                            }
                        } else {
                            // 即使没有有效坐标也获取地图引用
                            view.getMapAsync { googleMap ->
                                googleMapRef = googleMap
                                Log.i(TAG, "✅ 地图引用已获取")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "获取地图实例失败: ${e.message}")
                    }

                    // 标记有待处理的导航请求（延迟到用户接受 ToS 后）
                    if (goalLat != 0.0 && goalLon != 0.0) {
                        pendingNavigation = true
                        Log.i(TAG, "📌 导航请求已排队，等待 ToS 接受...")
                    } else {
                        Log.w(TAG, "⚠️ 目的地坐标为 0，不自动开始导航")
                    }
                }

                override fun onError(errorCode: Int) {
                    val msg = when (errorCode) {
                        NavigationApi.ErrorCode.NOT_AUTHORIZED ->
                            "API Key 无效或未授权使用 Navigation API"
                        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED ->
                            "用户未接受导航服务条款，请同意导航条款后重试"
                        else -> "导航错误: $errorCode"
                    }
                    routeError = msg
                    isReady = false
                    navigatorInitialized = false  // 允许重试
                    isRoutePlanning = false
                    isNavStarted = false
                    Log.e(TAG, "❌ $msg")
                }
            }
        )
    }

    // 🔧 新增: 监听 Navigator 状态并在准备好后自动开始导航
    // 这会在用户接受 ToS 后触发（ToS 对话框关闭后 isReady 变为 true）
    LaunchedEffect(isReady, pendingNavigation) {
        if (!isReady || !pendingNavigation || isRoutePlanning || isNavStarted) return@LaunchedEffect

        Log.i(TAG, "🚀 Navigator 已就绪，开始规划路线...")
        pendingNavigation = false

        // 解析起点坐标
        val startLat = if (currentLat != 0.0) currentLat
                      else carrotManFieldsState?.value?.latitude ?: 0.0
        val startLon = if (currentLon != 0.0) currentLon
                      else carrotManFieldsState?.value?.longitude ?: 0.0

        isRoutePlanning = true

        // 开始导航（真实GPS导航 vs 模拟导航）
        // 仅在 Debug 构建时使用模拟，Release 构建使用真实 GPS
        resolvedNavManager.startNavigation(
            startLat = startLat,
            startLon = startLon,
            destLat = goalLat,
            destLon = goalLon,
            destName = goalName,
            simulate = false,  // 始终使用真实 GPS 导航（不使用模拟导航）
            onNavigationStarted = {
                isRoutePlanning = false
                isNavStarted = true
            },
            onRouteError = { error ->
                Log.e(TAG, "❌ 路线错误: $error")
                routeError = error
                isRoutePlanning = false
                isNavStarted = false
            },
            networkClient = networkClient  // 🆕 传递网络客户端用于发送路线点
        )
    }

    // 安全退出
    val safeBack: () -> Unit = {
        try {
            if (isRoutePlanning || isNavStarted) {
                resolvedNavManager.stopNavigation()
                isNavStarted = false
                isRoutePlanning = false
                Log.i(TAG, "🔙 返回前已停止导航")
            }
        } catch (e: Exception) {
            Log.w(TAG, "返回时停止导航异常: ${e.message}")
        }
        onBack()
    }

    BackHandler(enabled = true) { safeBack() }

    // 生命周期管理（遵循官方 NavViewActivity 顺序：onPause/Stop/Destroy 先 navView 再 super）
    // 同时处理 onConfigurationChanged、onTrimMemory 和到达监听器清理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val navView = navViewRef ?: return@LifecycleEventObserver
            when (event) {
                // ON_START/RESUME: 先 super 再 navView（官方示例顺序）
                Lifecycle.Event.ON_START -> navView.onStart()
                Lifecycle.Event.ON_RESUME -> navView.onResume()
                // ON_PAUSE/STOP/DESTROY: 先 navView 再 super（官方示例顺序）
                Lifecycle.Event.ON_PAUSE -> navView.onPause()
                Lifecycle.Event.ON_STOP -> navView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    // 1. 清理到达监听器（防止内存泄漏）
                    if (arrivalListener != null) {
                        try {
                            navigator?.removeArrivalListener(arrivalListener!!)
                            Log.i(TAG, "✅ 到达监听器已移除")
                        } catch (e: Exception) {
                            Log.w(TAG, "移除到达监听器失败: ${e.message}")
                        }
                        arrivalListener = null
                    }
                    navView.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                resolvedNavManager.stopNavigation()
                // 清理到达监听器
                if (arrivalListener != null) {
                    try {
                        navigator?.removeArrivalListener(arrivalListener!!)
                        Log.i(TAG, "✅ 到达监听器已移除(onDispose)")
                    } catch (e: Exception) {
                        Log.w(TAG, "移除到达监听器(onDispose)失败: ${e.message}")
                    }
                    arrivalListener = null
                }
                if (ownsNavManager) {
                    resolvedNavManager.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理导航资源异常: ${e.message}")
            }
        }
    }

    // 🆕 P1: 注册内存压力监听 — 通知 NavigationView 释放地图缓存
    DisposableEffect(Unit) {
        val app = context.applicationContext
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    Log.w(TAG, "TRIM_MEMORY_RUNNING_CRITICAL — 系统内存严重不足")
                }
            }
            override fun onLowMemory() {
                Log.w(TAG, "onLowMemory — 系统内存不足")
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        }
        app.registerComponentCallbacks(cb)
        onDispose { app.unregisterComponentCallbacks(cb) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 导航地图视图
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.layout_google_nav, null)

                val navView = view.findViewById<NavigationView>(
                    R.id.google_navigation_view
                )
                
                // 立即调用 onCreate（参考官方示例）
                navView.onCreate(null)
                // 默认开启导航头部信息栏
                navView.setHeaderEnabled(true)
                // 默认开启限速图标
                navView.setSpeedLimitIconEnabled(true)
                // 应用初始 UI 状态
                navView.setNavigationUiEnabled(navUiEnabled)
                navView.setTripProgressBarEnabled(tripProgressBarEnabled)
                navView.setForceNightMode(nightMode)
                navViewRef = navView
                
                Log.i(TAG, "✅ NavigationView 已创建并初始化")

                view
            },
            modifier = Modifier.fillMaxSize()
        )

        // 加载指示器（区分初始化和等待 ToS）
        if (!isReady || pendingNavigation || isRoutePlanning) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF4285F4),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            !isReady && !navigatorInitialized -> localized("正在初始化 Google 导航...", "Initializing Google Navigation...")
                            !isReady && navigatorInitialized -> localized("正在等待导航服务（可能需要几十秒，请接受服务条款）...", "Waiting for navigation service (may take a while, please accept ToS)...")
                            pendingNavigation || isRoutePlanning -> localized("正在规划路线...", "Planning route...")
                            else -> localized("正在加载 Google 导航...", "Loading Google Navigation...")
                        },
                        color = Color.White
                    )
                }
            }
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
                    Text(error, color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = { routeError = null; safeBack() }) {
                            Text(localized("返回", "Back"))
                        }
                        if (goalLat != 0.0 && goalLon != 0.0) {
                            Button(onClick = {
                                routeError = null
                                retryNavigation = true
                            }) {
                                Text(localized("重试", "Retry"))
                            }
                        }
                    }
                }
            }
        }

        // 🆕 更多菜单按钮（右上角）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        ) {
            IconButton(onClick = { overflowMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = localized("更多", "More")
                )
            }
            DropdownMenu(
                expanded = overflowMenuExpanded,
                onDismissRequest = { overflowMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (overviewNow) localized("恢复跟车视图", "Exit overview")
                            else localized("全览路线", "Route overview")
                        )
                    },
                    onClick = {
                        overflowMenuExpanded = false
                        toggleOverview(context, navViewRef, navigator, googleMapRef, overviewNow) { v -> overviewNow = v }
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            when (nightMode) {
                                ForceNightMode.AUTO -> "🌗 " + localized("夜间模式：自动", "Night mode: auto")
                                ForceNightMode.FORCE_DAY -> "☀️ " + localized("夜间模式：白天", "Night mode: day")
                                else -> "🌙 " + localized("夜间模式：黑夜", "Night mode: night")
                            }
                        )
                    },
                    onClick = {
                        overflowMenuExpanded = false
                        showNightModeSheet = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(localized("显示设置…", "Display settings…")) },
                    onClick = {
                        overflowMenuExpanded = false
                        showDisplaySheet = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(localized("相机视角…", "Camera perspective…")) },
                    onClick = {
                        overflowMenuExpanded = false
                        showCameraSheet = true
                    }
                )
                if (isNavStarted) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(localized("算路策略…", "Route strategy…")) },
                        onClick = {
                            overflowMenuExpanded = false
                            showStrategySheet = true
                        }
                    )
                }
            }
        }
    }

    // 🆕 夜间模式 BottomSheet
    if (showNightModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNightModeSheet = false }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    localized("夜间模式", "Night mode"),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    ForceNightMode.AUTO to localized("自动（跟随系统）", "Auto (follow system)"),
                    ForceNightMode.FORCE_DAY to localized("强制白天模式", "Force day mode"),
                    ForceNightMode.FORCE_NIGHT to localized("强制黑夜模式", "Force night mode")
                ).forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = nightMode == mode,
                            onClick = {
                                nightMode = mode
                                navViewRef?.setForceNightMode(mode)
                                showNightModeSheet = false
                                Log.i(TAG, "🌗 夜间模式已切换: $mode")
                            }
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    // 🆕 显示设置 BottomSheet
    if (showDisplaySheet) {
        var navUiDraft by remember(showDisplaySheet) { mutableStateOf(navUiEnabled) }
        var tripBarDraft by remember(showDisplaySheet) { mutableStateOf(tripProgressBarEnabled) }
        var myLocDraft by remember(showDisplaySheet) { mutableStateOf(myLocationEnabled) }
        ModalBottomSheet(
            onDismissRequest = { showDisplaySheet = false }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    localized("显示设置", "Display settings"),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    localized("注意：部分选项可能需要导航开始后才生效", "Note: Some options may apply after navigation starts"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = navUiDraft,
                        onCheckedChange = { navUiDraft = it }
                    )
                    Text(localized("导航 UI", "Navigation UI"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = tripBarDraft,
                        onCheckedChange = { tripBarDraft = it }
                    )
                    Text(localized("行程进度条（实验性）", "Trip progress bar (experimental)"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = myLocDraft,
                        onCheckedChange = { myLocDraft = it }
                    )
                    Text(localized("我的位置标记", "My location marker"))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDisplaySheet = false }) {
                        Text(localized("取消", "Cancel"))
                    }
                    TextButton(onClick = {
                        navUiEnabled = navUiDraft
                        tripProgressBarEnabled = tripBarDraft
                        myLocationEnabled = myLocDraft
                        navViewRef?.setNavigationUiEnabled(navUiDraft)
                        navViewRef?.setTripProgressBarEnabled(tripBarDraft)
                        googleMapRef?.isMyLocationEnabled = myLocDraft
                        showDisplaySheet = false
                        Log.i(TAG, "✅ 显示设置已应用: navUi=$navUiDraft tripBar=$tripBarDraft myLoc=$myLocDraft")
                    }) {
                        Text(localized("应用", "Apply"))
                    }
                }
            }
        }
    }

    // 🆕 相机视角 BottomSheet
    if (showCameraSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCameraSheet = false }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    localized("相机视角", "Camera perspective"),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    GoogleMap.CameraPerspective.TILTED to localized("跟随：倾斜（默认）", "Following: Tilted (default)"),
                    GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP to localized("跟随：北向上", "Following: North up"),
                    GoogleMap.CameraPerspective.TOP_DOWN_HEADING_UP to localized("跟随：车头向上", "Following: Heading up")
                ).forEach { (perspective, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = cameraPerspective == perspective,
                            onClick = {
                                cameraPerspective = perspective
                                googleMapRef?.followMyLocation(perspective)
                                showCameraSheet = false
                                Log.i(TAG, "📷 相机视角已切换: $perspective")
                            }
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    // 🆕 算路策略 BottomSheet（仅导航中显示）
    if (showStrategySheet) {
        ModalBottomSheet(
            onDismissRequest = { showStrategySheet = false }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    localized("算路策略", "Route strategy"),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    localized("注意：Google Navigation SDK 不支持导航中动态切换策略，需重新算路。", "Note: Route strategy change requires re-routing."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showStrategySheet = false }) {
                        Text(localized("关闭", "Close"))
                    }
                }
            }
        }
    }
}
