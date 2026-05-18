package com.example.carrotamap.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.LifecycleEventObserver
import com.example.carrotamap.CarrotManFields
import com.example.carrotamap.R
import com.example.carrotamap.navigation.GoogleNavManager
import com.example.carrotamap.ui.utils.localized
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.NavigationView

private const val TAG = "GoogleNavPage"

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
@SuppressLint("MissingPermission")
@Composable
fun GoogleNavPage(
    navManager: GoogleNavManager? = null,
    carrotManFieldsState: MutableState<CarrotManFields>?,
    goalLat: Double = 0.0,
    goalLon: Double = 0.0,
    goalName: String = "",
    currentLat: Double = 0.0,
    currentLon: Double = 0.0,
    networkClient: com.example.carrotamap.CarrotManNetworkClient? = null,
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

    // 🔧 修复: Navigator 初始化必须在 navView 创建后立即同步执行
    // 参考官方 NavViewActivity.kt，initializeNavigationApi 在 onCreate 中调用，不使用协程等待
    // 使用 navigatorInitialized 标志防止重复初始化
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
                nav.addArrivalListener {
                    Log.i(TAG, "🏁 到达目的地")
                    nav.clearDestinations()
                    resolvedNavManager.stopNavigation()
                    isNavStarted = false
                    isRoutePlanning = false
                }
                arrivalListenerRegisteredForNav = nav
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
                        nav.addArrivalListener {
                            Log.i(TAG, "🏁 到达目的地")
                            nav.clearDestinations()
                            resolvedNavManager.stopNavigation()
                            isNavStarted = false
                            isRoutePlanning = false
                        }
                        arrivalListenerRegisteredForNav = nav
                    }

                    // 初始化地图相机（显示起点位置）
                    try {
                        val cameraLat = if (currentLat != 0.0) currentLat else goalLat
                        val cameraLon = if (currentLon != 0.0) currentLon else goalLon
                        if (cameraLat != 0.0 && cameraLon != 0.0) {
                            view.getMapAsync { googleMap ->
                                try {
                                    val position = com.google.android.gms.maps.model.LatLng(cameraLat, cameraLon)
                                    val cameraUpdate = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(position, 15f)
                                    googleMap.moveCamera(cameraUpdate)

                                    // 启用我的位置图层
                                    googleMap.isMyLocationEnabled = true

                                    Log.i(TAG, "✅ 地图相机已初始化: ($cameraLat, $cameraLon)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "地图相机初始化失败: ${e.message}")
                                }
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

        // 开始导航（使用真实路线或模拟）
        resolvedNavManager.startNavigation(
            startLat = startLat,
            startLon = startLon,
            destLat = goalLat,
            destLon = goalLon,
            destName = goalName,
            simulate = true,  // Debug 模式下使用模拟
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
                Lifecycle.Event.ON_DESTROY -> navView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                resolvedNavManager.stopNavigation()
                if (ownsNavManager) {
                    resolvedNavManager.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理导航资源异常: ${e.message}")
            }
        }
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
                            !isReady && navigatorInitialized -> localized("正在加载导航服务（可能需要几十秒）...", "Loading navigation service (may take a while)...")
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
                    Button(onClick = { routeError = null; safeBack() }) {
                        Text(localized("返回", "Back"))
                    }
                }
            }
        }
    }
}
