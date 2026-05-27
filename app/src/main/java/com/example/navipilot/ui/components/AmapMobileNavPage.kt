package com.example.navipilot.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapException
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.ParallelRoadListener
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapCarInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviLatLng
import com.example.navipilot.BuildConfig
import com.example.navipilot.CarrotManFields
import com.example.navipilot.R
import com.example.navipilot.navigation.AmapNavDataBridge
import com.example.navipilot.navigation.CameraOverlay
import com.example.navipilot.navigation.CoordinateConverter
import com.example.navipilot.ui.utils.localized
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AmapMobileNavPage"

/**
 * SDK 内嵌「设置」与 Compose 状态桥接：[AndroidView] factory 只执行一次，用 [SideEffect] 每帧更新回调。
 */
private class AmapNaviSdkUiBridge {
    var onOpenSettings: () -> Unit = {}
}

/** 与 [SavedStateRegistry] 配合，用于横竖屏等配置变更时恢复 [AMapNaviView] 状态 */
private const val SAVED_STATE_KEY_NAVI_VIEW = "AmapMobileNaviView"

/**
 * Debug 下可改为 true：使用模拟导航（无 GPS 台架调试用）。Release 不受影响。
 */
private const val AMAP_MOBILE_USE_EMULATOR_IN_DEBUG = false

private const val MAP_ADDRESS_PREFS = "map_addresses"
private const val KEY_PLATE_NUMBER = "plate_number"

private const val AMAP_MOBILE_NAVI_PREFS = "amap_mobile_navi_prefs"
private const val KEY_DISP_TILT_3D = "disp_tilt_3d"
private const val KEY_DISP_EAGLE = "disp_eagle"
private const val KEY_DISP_EYRIE_CROSS = "disp_eyrie_cross"
private const val KEY_DISP_REAL_CROSS = "disp_real_cross"
private const val KEY_DISP_MODE_CROSS = "disp_mode_cross"
private const val KEY_ROUTE_AVOID_CONGESTION = "route_avoid_congestion"
private const val KEY_ROUTE_AVOID_HIGHWAY = "route_avoid_highway"
private const val KEY_ROUTE_AVOID_COST = "route_avoid_cost"
private const val KEY_ROUTE_HIGHWAY_PRIORITY = "route_highway_priority"

/** 自研 UI：导航地图显示偏好（持久化） */
private data class AmapMobileDisplayPrefs(
    val tilt3d: Boolean = false,
    val eagleMap: Boolean = false,
    val eyrieCross: Boolean = false,
    val realCross: Boolean = false,
    val modeCross: Boolean = false
)

/** 自研 UI：算路策略偏好（持久化） */
private data class AmapMobileRoutePrefs(
    val avoidCongestion: Boolean = true,
    val avoidHighway: Boolean = false,
    val avoidCost: Boolean = false,
    val highwayPriority: Boolean = false
)

private data class RouteBrief(
    val routeId: Int,
    val allLengthM: Int,
    val allTimeSec: Int
)

private fun readDisplayPrefs(ctx: Context): AmapMobileDisplayPrefs {
    val sp = ctx.getSharedPreferences(AMAP_MOBILE_NAVI_PREFS, Context.MODE_PRIVATE)
    return AmapMobileDisplayPrefs(
        tilt3d = sp.getBoolean(KEY_DISP_TILT_3D, false),
        eagleMap = sp.getBoolean(KEY_DISP_EAGLE, false),
        eyrieCross = sp.getBoolean(KEY_DISP_EYRIE_CROSS, false),
        realCross = sp.getBoolean(KEY_DISP_REAL_CROSS, false),
        modeCross = sp.getBoolean(KEY_DISP_MODE_CROSS, false)
    )
}

private fun writeDisplayPrefs(ctx: Context, p: AmapMobileDisplayPrefs) {
    ctx.getSharedPreferences(AMAP_MOBILE_NAVI_PREFS, Context.MODE_PRIVATE).edit().apply {
        putBoolean(KEY_DISP_TILT_3D, p.tilt3d)
        putBoolean(KEY_DISP_EAGLE, p.eagleMap)
        putBoolean(KEY_DISP_EYRIE_CROSS, p.eyrieCross)
        putBoolean(KEY_DISP_REAL_CROSS, p.realCross)
        putBoolean(KEY_DISP_MODE_CROSS, p.modeCross)
        apply()
    }
}

private fun readRoutePrefs(ctx: Context): AmapMobileRoutePrefs {
    val sp = ctx.getSharedPreferences(AMAP_MOBILE_NAVI_PREFS, Context.MODE_PRIVATE)
    return AmapMobileRoutePrefs(
        avoidCongestion = sp.getBoolean(KEY_ROUTE_AVOID_CONGESTION, true),
        avoidHighway = sp.getBoolean(KEY_ROUTE_AVOID_HIGHWAY, false),
        avoidCost = sp.getBoolean(KEY_ROUTE_AVOID_COST, false),
        highwayPriority = sp.getBoolean(KEY_ROUTE_HIGHWAY_PRIORITY, false)
    )
}

private fun writeRoutePrefs(ctx: Context, p: AmapMobileRoutePrefs) {
    ctx.getSharedPreferences(AMAP_MOBILE_NAVI_PREFS, Context.MODE_PRIVATE).edit().apply {
        putBoolean(KEY_ROUTE_AVOID_CONGESTION, p.avoidCongestion)
        putBoolean(KEY_ROUTE_AVOID_HIGHWAY, p.avoidHighway)
        putBoolean(KEY_ROUTE_AVOID_COST, p.avoidCost)
        putBoolean(KEY_ROUTE_HIGHWAY_PRIORITY, p.highwayPriority)
        apply()
    }
}

private fun formatRouteBriefLine(b: RouteBrief): String {
    if (b.allLengthM <= 0 && b.allTimeSec <= 0) return ""
    val km = b.allLengthM / 1000
    val m = b.allLengthM % 1000
    val dist = if (km > 0) "${km}.${(m / 100)}km" else "${b.allLengthM}m"
    val min = b.allTimeSec / 60
    return "$dist · ${min}min"
}

private fun buildRouteBriefs(navi: AMapNavi?, routeIds: IntArray?): List<RouteBrief> {
    if (navi == null) return emptyList()
    val paths: Map<Int, AMapNaviPath>? = try {
        @Suppress("DEPRECATION")
        navi.naviPaths
    } catch (e: Exception) {
        Log.w(TAG, "getNaviPaths: ${e.message}")
        null
    }
    if (!paths.isNullOrEmpty()) {
        return paths.map { (id, path) ->
            val len = try {
                path.allLength
            } catch (_: Exception) {
                0
            }
            val t = try {
                path.allTime
            } catch (_: Exception) {
                0
            }
            RouteBrief(id, len, t)
        }.sortedBy { it.routeId }
    }
    if (routeIds != null && routeIds.isNotEmpty()) {
        return routeIds.map { RouteBrief(it, 0, 0) }
    }
    return emptyList()
}

private fun resolveDrivingStrategy(navi: AMapNavi, prefs: AmapMobileRoutePrefs): Int {
    return try {
        // 第 5 个参数 true：多备选路线（与官方注释一致）
        navi.strategyConvert(
            prefs.avoidCongestion,
            prefs.avoidHighway,
            prefs.avoidCost,
            prefs.highwayPriority,
            true
        )
    } catch (e: Exception) {
        Log.w(TAG, "strategyConvert: ${e.message}")
        PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
    }
}

private fun validateRoutePrefs(p: AmapMobileRoutePrefs): String? {
    if (p.avoidHighway && p.highwayPriority) {
        return localized("不能同时「不走高速」与「高速优先」", "Cannot combine avoid highway and highway priority")
    }
    if (p.avoidCost && p.highwayPriority) {
        return localized("不能同时「避免收费」与「高速优先」", "Cannot combine avoid tolls and highway priority")
    }
    return null
}

/**
 * 构建 [AMapNaviView] 初始选项：显式车头向上、关闭易崩溃的内置策略面板。
 */
private fun buildNaviViewOptions(display: AmapMobileDisplayPrefs): AMapNaviViewOptions =
    AMapNaviViewOptions().apply {
        try {
            setNaviMode(AMapNaviView.CAR_UP_MODE)
        } catch (e: Exception) {
            Log.w(TAG, "setNaviMode(options): ${e.message}")
        }
        setEyrieCrossDisplay(display.eyrieCross)
        setModeCrossDisplayShow(display.modeCross)
        setEagleMapVisible(display.eagleMap)
        setRealCrossDisplayShow(display.realCross)
        setTilt(if (display.tilt3d) 30 else 0)
        try {
            setShowRouteStrategyPreferencePanel(false)
        } catch (e: Exception) {
            Log.w(TAG, "setShowRouteStrategyPreferencePanel: ${e.message}")
        }
        try {
            setRouteListButtonShow(false)
        } catch (e: Exception) {
            Log.w(TAG, "setRouteListButtonShow: ${e.message}")
        }
        try {
            setLayoutVisible(true)
        } catch (e: Exception) {
            Log.w(TAG, "setLayoutVisible: ${e.message}")
        }
        try {
            setAutoDisplayOverview(false)
        } catch (e: Exception) {
            Log.w(TAG, "setAutoDisplayOverview: ${e.message}")
        }
    }

/** 将显示偏好应用到已有 View（主线程 post 内调用） */
private fun applyDisplayPrefsToNaviView(
    naviView: AMapNaviView,
    display: AmapMobileDisplayPrefs,
    onFail: (String) -> Unit
) {
    try {
        val opts = naviView.viewOptions ?: AMapNaviViewOptions()
        try {
            opts.setShowRouteStrategyPreferencePanel(false)
        } catch (_: Exception) {
        }
        try {
            opts.setRouteListButtonShow(false)
        } catch (_: Exception) {
        }
        try {
            opts.setLayoutVisible(true)
        } catch (_: Exception) {
        }
        try {
            opts.setAutoDisplayOverview(false)
        } catch (_: Exception) {
        }
        try {
            opts.setNaviMode(AMapNaviView.CAR_UP_MODE)
        } catch (_: Exception) {
        }
        opts.setEyrieCrossDisplay(display.eyrieCross)
        opts.setModeCrossDisplayShow(display.modeCross)
        opts.setEagleMapVisible(display.eagleMap)
        opts.setRealCrossDisplayShow(display.realCross)
        opts.setTilt(if (display.tilt3d) 30 else 0)
        naviView.setViewOptions(opts)
    } catch (e: Exception) {
        Log.w(TAG, "applyDisplayPrefsToNaviView: ${e.message}", e)
        onFail(localized("部分显示选项当前 Key 不支持，已跳过", "Some display options are not supported for this key"))
    }
    try {
        naviView.setNaviMode(AMapNaviView.CAR_UP_MODE)
    } catch (_: Exception) {
    }
}

/**
 * 强制车头向上：与 [buildNaviViewOptions] 一致；SDK 在 inflate、算路、startNavi、[onResume] 后可能仍保持北向，需在多个时机重复应用。
 */
private fun applyCarUpNaviMode(naviView: AMapNaviView) {
    try {
        naviView.setNaviMode(AMapNaviView.CAR_UP_MODE)
    } catch (e: Exception) {
        Log.w(TAG, "setNaviMode(CAR_UP): ${e.message}")
    }
    try {
        val opts = naviView.viewOptions ?: AMapNaviViewOptions()
        opts.setNaviMode(AMapNaviView.CAR_UP_MODE)
        naviView.setViewOptions(opts)
    } catch (e: Exception) {
        Log.w(TAG, "applyCarUpNaviMode viewOptions: ${e.message}")
    }
}

private fun applyNavMap2DOnly(amap: AMap?) {
    if (amap == null) return
    try {
        amap.showBuildings(false)
        amap.showIndoorMap(false)
        amap.mapType = AMap.MAP_TYPE_NAVI
    } catch (e: Exception) {
        Log.w(TAG, "applyNavMap2DOnly: ${e.message}")
    }
}

private fun resolveStartWgs84(currentLat: Double, currentLon: Double, fields: CarrotManFields?): Pair<Double, Double> {
    if (currentLat != 0.0 && currentLon != 0.0) return currentLat to currentLon
    val f = fields ?: return 0.0 to 0.0
    if (f.latitude != 0.0 && f.longitude != 0.0) return f.latitude to f.longitude
    if (f.vpPosPointLat != 0.0 && f.vpPosPointLon != 0.0) return f.vpPosPointLat to f.vpPosPointLon
    return 0.0 to 0.0
}

private fun readPlateForRouting(context: Context): String {
    return context.getSharedPreferences(MAP_ADDRESS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PLATE_NUMBER, null)
        ?.trim()
        .orEmpty()
}

/** 从 [Context] 链向上查找 [Activity]，供高德 View 使用稳定 UI 上下文。 */
private tailrec fun findActivityContext(c: Context): Context? {
    if (c is Activity) return c
    if (c is ContextWrapper) return findActivityContext(c.baseContext)
    return null
}

/**
 * 用于 [key] 的经纬度字符串：保留 4 位小数（约 10m 级），避免 GPS 抖动导致整页 [key] 重建、导航 View 反复 onCreate 闪烁。
 */
private fun stableLatLonKey(lat: Double, lon: Double): String {
    val f = 10_000.0
    val rl = kotlin.math.round(lat * f) / f
    val rn = kotlin.math.round(lon * f) / f
    return "$rl,$rn"
}

/**
 * 高德 [AMapNaviView] 需要带 MaterialComponents 主题的 Context（见 themes.xml 说明）。
 * Manifest 已为 Activity 配置 [R.style.Theme_Navipilot] 时，**直接使用 Activity**，不要再套一层
 * [ContextThemeWrapper]：否则高德内部 [PluginContext] inflate 导航面板时，部分 drawable 的
 * `?attr` 在 [Resources.getDrawable] 无 Theme 路径下解析失败（如 amap_navi_above_shadow），引发异常与反复重组。
 * 仅当拿不到 Activity 时退回 ThemeWrapper + 中间层 [base]。
 */
private fun contextForAmapNaviView(base: Context): Context {
    val act = findActivityContext(base)
    if (act != null) return act
    return ContextThemeWrapper(base, R.style.Theme_Navipilot)
}

/**
 * 高德手机导航（SDK 内嵌 [AMapNaviView]），数据经 [AmapNavDataBridge] 写入 [CarrotManFields]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmapMobileNavPage(
    carrotManFieldsState: MutableState<CarrotManFields>?,
    goalLat: Double,
    goalLon: Double,
    goalName: String,
    currentLat: Double,
    currentLon: Double,
    networkClient: com.example.navipilot.CarrotManNetworkClient? = null,
    onEnterAmapMobileMode: () -> Unit = {},
    onExitAmapMobileMode: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appCtx = context.applicationContext
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current
    val scope = rememberCoroutineScope()

    val wgsStart = resolveStartWgs84(currentLat, currentLon, carrotManFieldsState?.value)
    val routeRebuildKey = stableLatLonKey(goalLat, goalLon)
    // GCJ-02 坐标缓存（供独立算路使用，在 factory lambda 内赋值）
    var cachedGcjStart by remember { mutableStateOf(0.0 to 0.0) }
    var cachedGcjGoal by remember { mutableStateOf(0.0 to 0.0) }

    DisposableEffect(Unit) {
        onEnterAmapMobileMode()
        
        // 🔧 优化3: Activity级别屏幕常亮控制
        val activity = findActivityContext(context) as? Activity
        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "✅ Activity屏幕常亮已启用")
        } catch (e: Exception) {
            Log.w(TAG, "启用Activity屏幕常亮失败: ${e.message}")
        }
        
        onDispose {
            onExitAmapMobileMode()
            // 恢复原始屏幕标志
            try {
                if (activity != null) {
                    activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.i(TAG, "✅ Activity屏幕常亮已恢复")
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复Activity屏幕标志失败: ${e.message}")
            }
        }
    }

    val dataBridge = remember { AmapNavDataBridge(carrotManFieldsState, context.applicationContext) }

    // 释放 dataBridge 持有的 TTS 引擎（setUseInnerVoice=false 时由桥接器持有）
    DisposableEffect(dataBridge) {
        onDispose {
            dataBridge.destroyTts()
        }
    }

    val isNavigatingActive = carrotManFieldsState?.let { st ->
        val fields by st
        fields.isNavigating
    } ?: false

    key(routeRebuildKey) {
        val restoredNaviBundle = remember(savedStateRegistryOwner) {
            savedStateRegistryOwner.savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE_KEY_NAVI_VIEW)
        }

        var naviViewHolder by remember { mutableStateOf<AMapNaviView?>(null) }
        var aMapNaviHolder by remember { mutableStateOf<AMapNavi?>(null) }
        var multiRouteIds by remember { mutableStateOf<IntArray?>(null) }
        var routeBriefs by remember { mutableStateOf<List<RouteBrief>>(emptyList()) }
        var displayPrefsVersion by remember { mutableStateOf(0) }
        var naviMapMode by remember { mutableStateOf(AMapNaviView.CAR_UP_MODE) }
        var overviewNow by remember { mutableStateOf(false) }
        var crossBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var modeCrossBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

        var showRouteListSheet by remember { mutableStateOf(false) }
        var showStrategySheet by remember { mutableStateOf(false) }
        var showDisplaySheet by remember { mutableStateOf(false) }
        // 🆕 独立算路状态
        var showIndependentRouteSheet by remember { mutableStateOf(false) }
        var independentRouteReady by remember { mutableStateOf(false) }
        var independentRouteInfo by remember { mutableStateOf("") }
        // 🆕 电子眼覆盖层
        var cameraOverlay by remember { mutableStateOf<CameraOverlay?>(null) }
        val sdkSettingsBridge = remember(routeRebuildKey) { AmapNaviSdkUiBridge() }

        val strategySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val displaySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        val routeListSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // 🆕 独立算路
        val independentRouteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        var overflowMenuExpanded by remember { mutableStateOf(false) }

        val toast: (String) -> Unit = { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }

        // displayPrefs 状态：在 key(routeRebuildKey) 重建时从磁盘读取，之后由 displayPrefsVersion 递增触发 update 重新应用
        val displayPrefs by remember { mutableStateOf(readDisplayPrefs(appCtx)) }
        val displayPrefsRef = rememberUpdatedState(displayPrefs)

        DisposableEffect(dataBridge, routeRebuildKey) {
            dataBridge.onMultiRouteIds = { ids -> multiRouteIds = ids }
            onDispose {
                dataBridge.onMultiRouteIds = null
            }
        }

        // B4 fix: naviPaths @Deprecated API 在 onCalculateRouteSuccess 立即调用可能返回空，
        // 改用 Handler.post 将 buildRouteBriefs 延迟到下一帧执行，确保 naviPaths 已就绪
        DisposableEffect(dataBridge, routeRebuildKey, aMapNaviHolder) {
            dataBridge.onCalculateRouteResultDetail = { result ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    routeBriefs = buildRouteBriefs(aMapNaviHolder, result?.routeid)
                }
            }
            onDispose {
                dataBridge.onCalculateRouteResultDetail = null
            }
        }

        // F2: 路口放大图回调 - 获取 AMapNaviCross Bitmap 并更新 UI 状态
        DisposableEffect(dataBridge) {
            dataBridge.onCrossBitmap = { bitmap ->
                crossBitmap = bitmap
            }
            onDispose {
                dataBridge.onCrossBitmap = null
                crossBitmap = null
            }
        }

        // F4: 模型路口大图回调 - 获取 AMapModelCross Bitmap 并更新 UI 状态
        DisposableEffect(dataBridge) {
            dataBridge.onModeCrossBitmap = { bitmap ->
                modeCrossBitmap = bitmap
            }
            onDispose {
                dataBridge.onModeCrossBitmap = null
                modeCrossBitmap = null
            }
        }



        val parallelRoadListener = remember(carrotManFieldsState) {
            object : ParallelRoadListener {
                private var lastEl: Int? = null
                private var lastMs: Int? = null

                override fun notifyParallelRoad(status: AMapNaviParallelRoadStatus?) {
                    if (status == null) return
                    val el = try {
                        status.getmElevatedRoadStatusFlag()
                    } catch (_: Exception) {
                        -1
                    }
                    val ms = try {
                        status.getmParallelRoadStatusFlag()
                    } catch (_: Exception) {
                        -1
                    }
                    if (lastEl == el && lastMs == ms) return
                    lastEl = el
                    lastMs = ms
                    Log.d(TAG, "并行路/高架: elevated=$el mainSide=$ms")
                    val st = carrotManFieldsState ?: return
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        st.value = st.value.copy(
                            amapParallelElevatedFlag = el,
                            amapParallelMainSideFlag = ms,
                            source_last = "amap_mobile"
                        )
                    }
                }
            }
        }

        // B3 fix: ParallelRoadListener 的注册和移除都在同一个 DisposableEffect 中管理，
        // 确保 key 变化重建时旧 listener 被正确移除，避免在 AMapNavi 单例中积累
        DisposableEffect(aMapNaviHolder, parallelRoadListener) {
            aMapNaviHolder?.addParallelRoadListener(parallelRoadListener)
            onDispose {
                try {
                    aMapNaviHolder?.removeParallelRoadListener(parallelRoadListener)
                } catch (_: Exception) {
                    // ignore if already removed
                }
            }
        }

        val routeNavStarted = remember { AtomicBoolean(false) }

        val safeBack: () -> Unit = {
            // 高德部分回调在非主线程；Compose 状态必须在主线程更新，否则 onBack 可能不生效，界面仍停在导航页。
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    aMapNaviHolder?.stopNavi()
                    routeNavStarted.set(false)
                } catch (e: Exception) {
                    Log.w(TAG, "stopNavi: ${e.message}")
                }
                // onNavigationStopped 由 onExitAmapMobileMode 内部调用，避免重复
                try {
                    onBack()
                } catch (e: Exception) {
                    Log.e(TAG, "onBack: ${e.message}", e)
                }
            }
        }

        BackHandler(enabled = true) { safeBack() }

        DisposableEffect(lifecycleOwner, savedStateRegistryOwner) {
            val observer = LifecycleEventObserver { _, event ->
                val v = naviViewHolder ?: return@LifecycleEventObserver
                when (event) {
                    // AMapNaviView 有 onResume/onPause，无 onStart/onStop
                    Lifecycle.Event.ON_RESUME -> {
                        v.onResume()
                        v.post {
                            applyCarUpNaviMode(v)
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> v.onPause()
                    // onDestroy 在 onDispose 中调用
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)

            val savedStateProvider = SavedStateRegistry.SavedStateProvider {
                Bundle().apply {
                    naviViewHolder?.onSaveInstanceState(this)
                }
            }
            savedStateRegistryOwner.savedStateRegistry.registerSavedStateProvider(
                SAVED_STATE_KEY_NAVI_VIEW,
                savedStateProvider
            )

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                savedStateRegistryOwner.savedStateRegistry.unregisterSavedStateProvider(SAVED_STATE_KEY_NAVI_VIEW)
                try {
                    naviViewHolder?.onDestroy()
                    aMapNaviHolder?.stopNavi()
                    aMapNaviHolder?.removeAMapNaviListener(dataBridge)
                    AMapNavi.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "释放高德导航: ${e.message}", e)
                }
                naviViewHolder = null
                aMapNaviHolder = null
                dataBridge.onRouteCalculated = null
                dataBridge.resetSessionState()
                // onNavigationStopped 由 onExitAmapMobileMode 内部调用，避免重复
            }
        }

        fun postToast(msg: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }

        SideEffect {
            sdkSettingsBridge.onOpenSettings = {
                showDisplaySheet = true
                postToast(
                    localized(
                        "Compose 宿主下内置设置面板由「显示设置」替代；算路策略在「更多」菜单。",
                        "Built-in settings are replaced by Display sheet; route strategy is under More."
                    )
                )
            }
        }

        fun toggleOverview() {
            val v = naviViewHolder ?: return
            v.post {
                try {
                    if (v.isRouteOverviewNow) {
                        v.recoverLockMode()
                    } else {
                        v.displayOverview()
                    }
                    overviewNow = v.isRouteOverviewNow
                } catch (e: Exception) {
                    Log.w(TAG, "overview: ${e.message}")
                    postToast(localized("全览操作失败", "Overview action failed"))
                }
            }
        }

        fun setNaviHeadingMode(mode: Int) {
            val v = naviViewHolder ?: return
            v.post {
                try {
                    v.setNaviMode(mode)
                    val opts = v.viewOptions
                    if (opts != null) {
                        try {
                            opts.setNaviMode(mode)
                            v.setViewOptions(opts)
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setNaviMode: ${e.message}")
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = create@{ ctx ->
                    val naviView = AMapNaviView(contextForAmapNaviView(ctx), buildNaviViewOptions(displayPrefs))
                    naviView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    val naviBundle = restoredNaviBundle ?: Bundle()
                    val hostActivity = findActivityContext(ctx) as? Activity
                    if (hostActivity != null) {
                        try {
                            naviView.onCreate(naviBundle, hostActivity, AmapPageType.NAVI)
                        } catch (e: Exception) {
                            Log.w(TAG, "onCreate(bundle, Activity, NAVI) 失败，降级单参: ${e.message}")
                            naviView.onCreate(naviBundle)
                        }
                    } else {
                        Log.w(
                            TAG,
                            "findActivityContext 未找到 Activity，使用单参 onCreate（内嵌导航 UI 可能受限）"
                        )
                        naviView.onCreate(naviBundle)
                    }
                    naviView.post {
                        applyNavMap2DOnly(naviView.map)
                        applyCarUpNaviMode(naviView)
                    }
                    naviView.setAMapNaviViewListener(object : AMapNaviViewListener {
                        override fun onNaviSetting() {
                            naviView.post {
                                try {
                                    sdkSettingsBridge.onOpenSettings()
                                } catch (e: Exception) {
                                    Log.w(TAG, "onNaviSetting: ${e.message}")
                                }
                            }
                        }
                        override fun onNaviCancel() {
                            safeBack()
                        }

                        override fun onNaviMapMode(mode: Int) {
                            naviMapMode = mode
                            Log.i(TAG, "onNaviMapMode=$mode (0车头 1北向)")
                            val st = carrotManFieldsState ?: return
                            st.value = st.value.copy(
                                amapNaviMapMode = if (mode == AMapNaviView.NORTH_UP_MODE) 1 else 0,
                                source_last = "amap_mobile"
                            )
                        }

                        override fun onNaviTurnClick() {
                            val st = carrotManFieldsState ?: return
                            val cur = st.value
                            val turnText = cur.szTBTMainText.ifBlank { "转向提示" }
                            Toast.makeText(ctx, "当前转向: $turnText", Toast.LENGTH_SHORT).show()
                        }
                        override fun onNextRoadClick() {
                            Toast.makeText(ctx, "下一道路点击", Toast.LENGTH_SHORT).show()
                        }
                        override fun onScanViewButtonClick() {}
                        override fun onLockMap(var1: Boolean) {
                            Log.i(TAG, "地图锁定状态: $var1")
                        }
                        override fun onNaviViewLoaded() {
                            naviView.post {
                                applyCarUpNaviMode(naviView)
                                naviMapMode = AMapNaviView.CAR_UP_MODE
                            }
                        }
                        override fun onMapTypeChanged(var1: Int) {}
                        override fun onNaviViewShowMode(var1: Int) {}
                        override fun onStopSpeaking() {}
                        override fun onViewTypeChanged(var1: AmapPageType?) {}
                        override fun onAMapNaviViewExit() {
                            safeBack()
                        }

                        override fun onStrategyChanged(var1: Int) {}
                        override fun onBroadcastModeChanged(var1: Int) {}
                        override fun onDayAndNightModeChanged(var1: Int) {}
                        override fun onScaleAutoChanged(var1: Boolean) {}
                        override fun onListenToVoiceDuringCallChanged(var1: Boolean) {}
                        override fun onControlMusicVolumeModeChanged(var1: Int) {}
                        override fun onEagleChanged(var1: Boolean) {}
                        override fun onNaviRouteHighlightChange(var1: Long, var2: Int) {}
                        override fun onNaviBackClick(): Boolean {
                            safeBack()
                            return true
                        }
                    })
                    try {
                        val (wgsSLat, wgsSLon) = wgsStart
                        val (gcjGLat, gcjGLon) = if (goalLat != 0.0 && goalLon != 0.0) {
                            CoordinateConverter.wgs84ToGcj02(goalLat, goalLon)
                        } else {
                            0.0 to 0.0
                        }

                        val coordsInvalid = gcjGLat == 0.0 && gcjGLon == 0.0
                        val startInvalid = wgsSLat == 0.0 || wgsSLon == 0.0
                        if (coordsInvalid) {
                            Log.w(TAG, "终点坐标无效，但继续初始化地图（GPS 定位后可正常导航）")
                        } else if (startInvalid) {
                            Log.w(TAG, "起点无效（等待 GPS），但继续初始化地图")
                        }

                        // 即使坐标无效也继续初始化地图，否则 AMapNaviView 空白
                        // GPS 定位后 onLocationChange 会更新位置，算路在 onInitNaviSuccess 后由 pendingCalculateRoute 触发

                        val navi = AMapNavi.getInstance(appCtx)
                        aMapNaviHolder = navi
                        naviViewHolder = naviView
                        dataBridge.navi = navi // 🆕 赋 navi 引用，使 P4 道路等级推断限速生效

                        dataBridge.resetSessionState()
                        dataBridge.showUserMessage = { msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                        dataBridge.onRouteCalculated = routeStartLambda@{
                            if (routeNavStarted.getAndSet(true)) return@routeStartLambda
                            try {
                                // 🔧 修复：先显示路线全览，让用户看到完整路线（参考腾讯地图效果）
                                naviView.post {
                                    try {
                                        // 显示路线全览
                                        naviView.displayOverview()
                                        overviewNow = true
                                        Log.i(TAG, "✅ 路线全览已显示，用户可查看完整路线")

                                        // 应用车头向上模式
                                        applyCarUpNaviMode(naviView)
                                        naviMapMode = AMapNaviView.CAR_UP_MODE
                                    } catch (e: Exception) {
                                        Log.w(TAG, "显示路线全览失败: ${e.message}")
                                    }
                                }

                                // 🆕 电子眼覆盖层 — 从 camera 收听回调获取最近摄像头（getCameraInfo 在 11.1.200 中不存在）
                                try {
                                    // NaviDemo 参考：使用 updateCameraInfo 回调数据，由 CameraOverlay 绘制
                                    if (cameraOverlay != null) {
                                        Log.i(TAG, "✅ 电子眼覆盖层已就绪，等待摄像头数据回调")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "摄像头覆盖层: ${e.message}")
                                }

                                // 延迟启动导航，让用户先看到路线（2秒后自动开始）
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    try {
                                        val naviType = if (BuildConfig.DEBUG && AMAP_MOBILE_USE_EMULATOR_IN_DEBUG) {
                                            NaviType.EMULATOR
                                        } else {
                                            NaviType.GPS
                                        }
                                        if (naviType == NaviType.EMULATOR) {
                                            navi.setEmulatorNaviSpeed(75)
                                        }
                                        val ok = navi.startNavi(naviType)
                                        Log.i(TAG, "startNavi($naviType)=$ok")
                                        if (ok) {
                                            // 提取路线点并发送至 comma3 (TCP 7709)
                                            val client = networkClient
                                            if (client != null) {
                                                try {
                                                    val points = dataBridge.extractRoutePointsFromAmap(navi)
                                                    if (points.isNotEmpty()) {
                                                        client.sendRoutePointsViaTcp(points)
                                                        Log.i(TAG, "✅ 高德路线点已发送至 comma3: ${points.size}个点")
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "路线点发送失败: ${e.message}")
                                                }
                                            } else {
                                                Log.w(TAG, "⚠️ networkClient 为 null，跳过路线点发送")
                                            }
                                            naviView.post {
                                                try {
                                                    // 导航开始后，恢复跟车视图
                                                    if (naviView.isRouteOverviewNow) {
                                                        naviView.recoverLockMode()
                                                    }
                                                    overviewNow = naviView.isRouteOverviewNow
                                                    applyCarUpNaviMode(naviView)
                                                    naviMapMode = AMapNaviView.CAR_UP_MODE
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "post-startNavi recoverLockMode: ${e.message}")
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "startNavi 失败: ${e.message}", e)
                                        routeNavStarted.set(false)
                                        Toast.makeText(
                                            ctx,
                                            localized("无法开始导航", "Cannot start navigation"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }, 2000) // 2秒后自动开始导航
                            } catch (e: Exception) {
                                Log.e(TAG, "路线显示失败: ${e.message}", e)
                                routeNavStarted.set(false)
                                Toast.makeText(
                                    ctx,
                                    localized("无法显示路线", "Cannot display route"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        val (gcjSLat, gcjSLon) = CoordinateConverter.wgs84ToGcj02(wgsSLat, wgsSLon)
                        cachedGcjStart = gcjSLat to gcjSLon
                        cachedGcjGoal = gcjGLat to gcjGLon
                        val sList = ArrayList<NaviLatLng>()
                        val eList = ArrayList<NaviLatLng>()
                        sList.add(NaviLatLng(gcjSLat, gcjSLon))
                        eList.add(NaviLatLng(gcjGLat, gcjGLon))
                        val wayEmpty: ArrayList<NaviLatLng> = ArrayList()

                        dataBridge.pendingCalculateRoute = {
                            // 起点无效时跳过算路，等待 GPS 定位
                            if (wgsSLat == 0.0 || wgsSLon == 0.0) {
                                Log.w(TAG, "起点无效，等待 GPS 定位后重算")
                            } else {
                                // 终点无效时用起点作为目的地（保持算路流程，地图正常显示）
                                val endLat = if (gcjGLat == 0.0 && gcjGLon == 0.0) gcjSLat else gcjGLat
                                val endLon = if (gcjGLat == 0.0 && gcjGLon == 0.0) gcjSLon else gcjGLon
                                try {
                                    navi.setIsNaviTravelView(false)
                                } catch (e: Exception) {
                                    Log.w(TAG, "setIsNaviTravelView: ${e.message}")
                                }
                                val plate = readPlateForRouting(ctx)
                                if (plate.isNotBlank()) {
                                    try {
                                        val carInfo = AMapCarInfo()
                                        carInfo.carNumber = plate
                                        carInfo.setRestriction(true)
                                        navi.setCarInfo(carInfo)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "setCarInfo: ${e.message}")
                                    }
                                }
                                val prefs = readRoutePrefs(ctx)
                                val strategy = resolveDrivingStrategy(navi, prefs)
                                val sListNew = ArrayList<NaviLatLng>()
                                val eListNew = ArrayList<NaviLatLng>()
                                sListNew.add(NaviLatLng(gcjSLat, gcjSLon))
                                eListNew.add(NaviLatLng(endLat, endLon))
                                val wayEmpty: ArrayList<NaviLatLng> = ArrayList()
                                val ok = navi.calculateDriveRoute(sListNew, eListNew, wayEmpty, strategy)
                                Log.i(TAG, "算路: ok=$ok ($gcjSLat,$gcjSLon)->($endLat,$endLon) strategy=$strategy")
                            }
                        }

                        // false = 导航文字交给 dataBridge.onGetNavigationText 处理（可提取限速）
                        // true  = 摄像头提示音仍由 SDK 内部播放（无需应用层干预）
                        navi.setUseInnerVoice(false, true)
                        navi.setEmulatorNaviSpeed(75)  // 与 NaviDemo BaseActivity 一致
                        
                        // 🔧 优化1: 设置屏幕常亮（导航时保持屏幕亮起）
                        try {
                            navi.getNaviSetting()?.setScreenAlwaysBright(true)
                            Log.i(TAG, "✅ 已启用导航时屏幕常亮")
                        } catch (e: Exception) {
                            Log.w(TAG, "setScreenAlwaysBright: ${e.message}")
                        }
                        
                        // 🔧 优化2: 设置导航视图为非旅游模式（驾车导航）
                        try {
                            navi.setIsNaviTravelView(false)
                        } catch (e: Exception) {
                            Log.w(TAG, "setIsNaviTravelView(init): ${e.message}")
                        }
                        
                        // ParallelRoadListener — 主辅路状态（注册给 bridge 统一管理）
                        try {
                            navi.addParallelRoadListener(dataBridge)
                            Log.i(TAG, "✅ ParallelRoadListener 已注册")
                        } catch (e: Exception) {
                            Log.w(TAG, "addParallelRoadListener: ${e.message}")
                        }
                        
                        // 🆕 电子眼覆盖层初始化
                        try {
                            cameraOverlay = CameraOverlay(naviView.map)
                            Log.i(TAG, "✅ 电子眼覆盖层已初始化")
                        } catch (e: Exception) {
                            Log.w(TAG, "CameraOverlay 初始化失败: ${e.message}")
                        }
                        
                        // 🆕 路线点击切换 — 地图路线点击打开备选路线面板
                        try {
                            val amap = naviView.map
                            if (amap != null) {
                                amap.setOnPolylineClickListener { polyline ->
                                    // SDK 11.1.200 简化处理: 直接打开备选路线面板
                                    postToast(localized("点击路线切换", "Switch route"))
                                    showRouteListSheet = true
                                    true
                                }
                                Log.i(TAG, "✅ Polyline 点击监听已注册")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "注册路线点击监听失败: ${e.message}")
                        }
                        
                        // ParallelRoadListener 由独立的 DisposableEffect 管理，避免重复注册
                        navi.addAMapNaviListener(dataBridge)
                    } catch (e: AMapException) {
                        Log.e(TAG, "高德异常: ${e.errorMessage}", e)
                        Toast.makeText(ctx, localized("高德导航初始化失败", "AMap init failed"), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "高德初始化失败: ${e.message}", e)
                    }
                    naviView
                },
                modifier = Modifier.fillMaxSize(),
                update = { naviView ->
                    naviView.post {
                        applyDisplayPrefsToNaviView(naviView, displayPrefsRef.value) { msg ->
                            postToast(msg)
                        }
                    }
                }
            )

            // F2: 路口放大图 - 当 SDK 触发 showCross 时显示
            crossBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { android.widget.ImageView(context).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(bmp)
                        }},
                        update = { it.setImageBitmap(bmp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.7f))
                    )
                }
            }

            // F4: 模型路口大图 - 当 SDK 触发 showModeCross 时显示（3D 复杂路口）
            modeCrossBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { android.widget.ImageView(context).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(bmp)
                        }},
                        update = { it.setImageBitmap(bmp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.85f))
                    )
                }
            }



            // 与 NaviDemo activity_basic_navi 一致：地图区尽量只保留 SDK 自带控件；扩展能力收进「更多」菜单，避免铺满自定义按钮。
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
                                if (overviewNow) {
                                    localized("恢复跟车视图", "Exit overview")
                                } else {
                                    localized("全览路线", "Route overview")
                                }
                            )
                        },
                        onClick = {
                            overflowMenuExpanded = false
                            toggleOverview()
                        }
                    )
                    val routes = multiRouteIds
                    if (routes != null && routes.size > 1) {
                        DropdownMenuItem(
                            text = { Text(localized("备选路线…", "Alternative routes…")) },
                            onClick = {
                                overflowMenuExpanded = false
                                showRouteListSheet = true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(localized("算路策略…", "Route planning…")) },
                        onClick = {
                            overflowMenuExpanded = false
                            showStrategySheet = true
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
                        text = { Text(localized("车头向上", "Heading up")) },
                        onClick = {
                            overflowMenuExpanded = false
                            setNaviHeadingMode(AMapNaviView.CAR_UP_MODE)
                        },
                        enabled = naviMapMode != AMapNaviView.CAR_UP_MODE
                    )
                    DropdownMenuItem(
                        text = { Text(localized("地图北向上", "North up")) },
                        onClick = {
                            overflowMenuExpanded = false
                            setNaviHeadingMode(AMapNaviView.NORTH_UP_MODE)
                        },
                        enabled = naviMapMode != AMapNaviView.NORTH_UP_MODE
                    )
                    DropdownMenuItem(
                        text = { Text(localized("独立算路…", "Independent route…")) },
                        onClick = {
                            overflowMenuExpanded = false
                            showIndependentRouteSheet = true
                        }
                    )
                    if (isNavigatingActive) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(localized("主路 / 桥上", "Main / elevated")) },
                            onClick = {
                                overflowMenuExpanded = false
                                try {
                                    aMapNaviHolder?.switchParallelRoad(1)
                                } catch (e: Exception) {
                                    Log.w(TAG, "switchParallelRoad(1): ${e.message}")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(localized("辅路 / 桥下", "Side / under")) },
                            onClick = {
                                overflowMenuExpanded = false
                                try {
                                    aMapNaviHolder?.switchParallelRoad(2)
                                } catch (e: Exception) {
                                    Log.w(TAG, "switchParallelRoad(2): ${e.message}")
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showRouteListSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRouteListSheet = false },
                sheetState = routeListSheetState
            ) {
                val ids = multiRouteIds
                // IntArray? 没有 isNullOrEmpty()，需显式判空再用 map
                val list = if (ids != null && ids.isNotEmpty()) {
                    val byId = routeBriefs.associateBy { it.routeId }
                    ids.map { rid -> byId[rid] ?: RouteBrief(rid, 0, 0) }
                } else {
                    routeBriefs
                }
                Text(
                    localized("备选路线", "Alternative routes"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                    items(list, key = { it.routeId }) { item ->
                        TextButton(
                            onClick = {
                                try {
                                    aMapNaviHolder?.selectRouteId(item.routeId)
                                } catch (e: Exception) {
                                    Log.w(TAG, "selectRouteId: ${e.message}")
                                }
                                scope.launch { routeListSheetState.hide() }.invokeOnCompletion {
                                    showRouteListSheet = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    localized("路线 ID ${item.routeId}", "Route id ${item.routeId}"),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                val line = formatRouteBriefLine(item)
                                if (line.isNotBlank()) {
                                    Text(line, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        localized("暂无路线数据，请等待算路完成", "No routes yet"),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (showStrategySheet) {
            ModalBottomSheet(
                onDismissRequest = { showStrategySheet = false },
                sheetState = strategySheetState
            ) {
                var draft by remember(showStrategySheet) {
                    mutableStateOf(readRoutePrefs(context))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(
                        localized("路径策略（自研，不打开 SDK 内置面板）", "Route strategy (custom UI)"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.avoidCongestion,
                            onCheckedChange = { draft = draft.copy(avoidCongestion = it) }
                        )
                        Text(localized("躲避拥堵", "Avoid congestion"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.avoidHighway,
                            onCheckedChange = { draft = draft.copy(avoidHighway = it) }
                        )
                        Text(localized("不走高速", "Avoid motorways"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.avoidCost,
                            onCheckedChange = { draft = draft.copy(avoidCost = it) }
                        )
                        Text(localized("避免收费", "Avoid tolls"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.highwayPriority,
                            onCheckedChange = { draft = draft.copy(highwayPriority = it) }
                        )
                        Text(localized("高速优先", "Motorway priority"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showStrategySheet = false }) {
                            Text(localized("取消", "Cancel"))
                        }
                        TextButton(
                            onClick = {
                                val err = validateRoutePrefs(draft)
                                if (err != null) {
                                    toast(err)
                                    return@TextButton
                                }
                                writeRoutePrefs(context, draft)
                                val navi = aMapNaviHolder
                                if (navi != null && isNavigatingActive) {
                                    try {
                                        val st = resolveDrivingStrategy(navi, draft)
                                        val ok = navi.reCalculateRoute(st)
                                        if (!ok) {
                                            toast(localized("重新算路未接受", "Re-route not accepted"))
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "reCalculateRoute: ${e.message}", e)
                                        toast(localized("重新算路失败", "Re-route failed"))
                                    }
                                }
                                scope.launch { strategySheetState.hide() }.invokeOnCompletion {
                                    showStrategySheet = false
                                }
                            }
                        ) {
                            Text(localized("应用并重算", "Apply & recalc"))
                        }
                    }
                }
            }
        }

        if (showDisplaySheet) {
            ModalBottomSheet(
                onDismissRequest = { showDisplaySheet = false },
                sheetState = displaySheetState
            ) {
                var draft by remember(showDisplaySheet) {
                    mutableStateOf(readDisplayPrefs(context))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(
                        localized("导航显示", "Navigation display"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.tilt3d,
                            onCheckedChange = { draft = draft.copy(tilt3d = it) }
                        )
                        Text(localized("3D 倾角（约 30°）", "3D tilt (~30°)"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.eagleMap,
                            onCheckedChange = { draft = draft.copy(eagleMap = it) }
                        )
                        Text(localized("鹰眼小地图", "Eagle eye map"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.eyrieCross,
                            onCheckedChange = { draft = draft.copy(eyrieCross = it) }
                        )
                        Text(localized("鹰巢路口大图", "Eyrie cross display"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.realCross,
                            onCheckedChange = { draft = draft.copy(realCross = it) }
                        )
                        Text(localized("实景路口大图", "Real cross display"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.modeCross,
                            onCheckedChange = { draft = draft.copy(modeCross = it) }
                        )
                        Text(localized("模型路口大图", "Mode cross display"))
                    }
                    Text(
                        localized(
                            "若当前 Key 无权限，应用时可能失败；以 Toast 提示为准。",
                            "Options may fail without console entitlements; watch toasts."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDisplaySheet = false }) {
                            Text(localized("取消", "Cancel"))
                        }
                        TextButton(
                            onClick = {
                                val v = naviViewHolder
                                if (v == null) {
                                    showDisplaySheet = false
                                    return@TextButton
                                }
                                v.post {
                                    try {
                                        applyDisplayPrefsToNaviView(v, draft) { msg -> postToast(msg) }
                                        writeDisplayPrefs(context, draft)
                                        displayPrefsVersion++
                                        scope.launch {
                                            displaySheetState.hide()
                                        }.invokeOnCompletion {
                                            showDisplaySheet = false
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "display prefs apply: ${e.message}")
                                        postToast(
                                            localized(
                                                "应用显示选项失败",
                                                "Failed to apply display options"
                                            )
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(localized("应用", "Apply"))
                        }
                    }
                }
            }
        }

        // 🆕 独立算路面板
        if (showIndependentRouteSheet) {
            ModalBottomSheet(
                onDismissRequest = { showIndependentRouteSheet = false },
                sheetState = independentRouteSheetState
            ) {
                var draftStrategy by remember(showIndependentRouteSheet) {
                    mutableStateOf(readRoutePrefs(context))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(
                        localized("独立算路（不影响当前导航）", "Independent route (no effect on current nav)"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        localized(
                            "在不中断当前导航的前提下额外计算一条路线，确认后切换导航。",
                            "Calculate an additional route without interrupting current navigation. Confirm to switch."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    // 算路策略
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draftStrategy.avoidCongestion,
                            onCheckedChange = { draftStrategy = draftStrategy.copy(avoidCongestion = it) }
                        )
                        Text(localized("躲避拥堵", "Avoid congestion"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draftStrategy.avoidHighway,
                            onCheckedChange = { draftStrategy = draftStrategy.copy(avoidHighway = it) }
                        )
                        Text(localized("不走高速", "Avoid motorways"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draftStrategy.avoidCost,
                            onCheckedChange = { draftStrategy = draftStrategy.copy(avoidCost = it) }
                        )
                        Text(localized("避免收费", "Avoid tolls"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draftStrategy.highwayPriority,
                            onCheckedChange = { draftStrategy = draftStrategy.copy(highwayPriority = it) }
                        )
                        Text(localized("高速优先", "Motorway priority"))
                    }
                    Spacer(Modifier.height(12.dp))

                    if (independentRouteReady) {
                        Text(
                            localized("独立路线就绪", "Independent route ready"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            independentRouteInfo,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showIndependentRouteSheet = false }) {
                            Text(localized("取消", "Cancel"))
                        }
                        TextButton(
                            onClick = {
                                val err = validateRoutePrefs(draftStrategy)
                                if (err != null) {
                                    toast(err)
                                    return@TextButton
                                }
                                val navi = aMapNaviHolder ?: run {
                                    toast(localized("导航未初始化", "Navigation not initialized"))
                                    return@TextButton
                                }
                                try {
                                    val st = resolveDrivingStrategy(navi, draftStrategy)
                                    val (gcjSLat, gcjSLon) = cachedGcjStart
                                    val (gcjGLat, gcjGLon) = cachedGcjGoal
                                    val endLatGcj = if (gcjGLat == 0.0 && gcjGLon == 0.0) gcjSLat else gcjGLat
                                    val endLonGcj = if (gcjGLat == 0.0 && gcjGLon == 0.0) gcjSLon else gcjGLon
                                    // 独立算路 — 使用 calculateDriveRoute（AMapNaviPoint 在 11.1.200 中不可用）
                                    navi.calculateDriveRoute(
                                        ArrayList<NaviLatLng>().apply { add(NaviLatLng(gcjSLat, gcjSLon)) },
                                        ArrayList<NaviLatLng>().apply { add(NaviLatLng(endLatGcj, endLonGcj)) },
                                        java.util.ArrayList<NaviLatLng>(),
                                        st
                                    )
                                    navi.setMultipleRouteNaviMode(true)
                                    postToast(localized("正在独立算路…", "Calculating independent route…"))
                                } catch (e: Exception) {
                                    Log.e(TAG, "独立算路异常: ${e.message}", e)
                                    postToast(localized("独立算路异常: ${e.message}", "Independent route error: ${e.message}"))
                                }
                            }
                        ) {
                            Text(localized("计算独立路线", "Calculate"))
                        }
                        if (independentRouteReady) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    try {
                                        val navi = aMapNaviHolder ?: return@TextButton
                                        val naviType = if (BuildConfig.DEBUG && AMAP_MOBILE_USE_EMULATOR_IN_DEBUG) {
                                            NaviType.EMULATOR
                                        } else {
                                            NaviType.GPS
                                        }
                                        val (gcjSLat, gcjSLon) = cachedGcjStart
                                        val (gcjGLat, gcjGLon) = cachedGcjGoal
                                        navi.stopNavi()
                                        routeNavStarted.set(false)
                                        // 用当前导航参数重新算路（独立算路结果需要通过正常 startNavi 激活）
                                        // 此处切换策略为重算触发路线变更
                                        val st = resolveDrivingStrategy(navi, draftStrategy)
                                        navi.calculateDriveRoute(
                                            ArrayList<NaviLatLng>().apply { add(NaviLatLng(gcjSLat, gcjSLon)) },
                                            ArrayList<NaviLatLng>().apply { add(NaviLatLng(gcjGLat, gcjGLon)) },
                                            java.util.ArrayList<NaviLatLng>(),
                                            st
                                        )
                                        showIndependentRouteSheet = false
                                    } catch (e: Exception) {
                                        Log.e(TAG, "切换独立路线失败: ${e.message}", e)
                                        postToast(localized("切换失败: ${e.message}", "Switch failed: ${e.message}"))
                                    }
                                }
                            ) {
                                Text(localized("使用此路线导航", "Use this route"))
                            }
                        }
                    }
                }
            }
        }
    }
}
