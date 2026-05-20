package com.example.navipilot.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.location.Location
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.navipilot.CarrotManNetworkClient
import com.example.navipilot.MainActivityUIComponents
import com.example.navipilot.navigation.OsmNavigationManager
import com.example.navipilot.navigation.OsmNavDest
import com.example.navipilot.navigation.RouteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.example.navipilot.ui.utils.localized

private const val TAG = "OsmMapView"
private const val DEFAULT_ZOOM = 16.0
private const val NAV_ZOOM = 17.5          // 导航中基础缩放
private const val NAV_INTERSECTION_ZOOM = 19.0 // 接近路口时最大缩放

/**
 * 获取完整中文深色导航样式 JSON (Dashy NavMap Dark)
 * 基于 OpenFreeMap 免费矢量瓦片（OpenMapTiles schema）
 * 包含：水系、绿地、建筑、道路、铁路、地名、POI 等完整图层
 * 标注优先使用 name 字段（中国境内即中文）
 * 无需 API key，完全免费
 */
private fun getDashyNavMapDarkStyleJson(): String {
    // 标注字段：优先 name（中国境内=中文），回退 name:nonlatin，再回退 name:latin
    val nameField = """["coalesce", ["get", "name"], ["get", "name:nonlatin"], ["get", "name:latin"]]"""
    return """
{
  "version": 8,
  "name": "中文导航 Dark",
  "sources": {
    "openmaptiles": {
      "type": "vector",
      "url": "https://tiles.openfreemap.org/planet"
    }
  },
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#1a1a2e" } },

    { "id": "water", "type": "fill", "source": "openmaptiles", "source-layer": "water",
      "paint": { "fill-color": "#191a3a" } },

    { "id": "waterway", "type": "line", "source": "openmaptiles", "source-layer": "waterway",
      "paint": { "line-color": "#191a3a", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.5, 14, 3] } },

    { "id": "landcover-grass", "type": "fill", "source": "openmaptiles", "source-layer": "landcover",
      "filter": ["==", "class", "grass"],
      "paint": { "fill-color": "#1e2d1e", "fill-opacity": 0.6 } },

    { "id": "landcover-wood", "type": "fill", "source": "openmaptiles", "source-layer": "landcover",
      "filter": ["==", "class", "wood"],
      "paint": { "fill-color": "#1a2b1a", "fill-opacity": 0.6 } },

    { "id": "landuse-residential", "type": "fill", "source": "openmaptiles", "source-layer": "landuse",
      "filter": ["in", "class", "residential", "suburb", "neighbourhood"],
      "paint": { "fill-color": "#1e2230", "fill-opacity": 0.4 } },

    { "id": "landuse-commercial", "type": "fill", "source": "openmaptiles", "source-layer": "landuse",
      "filter": ["in", "class", "commercial", "retail"],
      "paint": { "fill-color": "#252230", "fill-opacity": 0.3 } },

    { "id": "landuse-park", "type": "fill", "source": "openmaptiles", "source-layer": "landuse",
      "filter": ["in", "class", "park", "cemetery"],
      "paint": { "fill-color": "#1a2e1a", "fill-opacity": 0.5 } },

    { "id": "building", "type": "fill", "source": "openmaptiles", "source-layer": "building",
      "minzoom": 13,
      "paint": { "fill-color": "#252535", "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0.2, 16, 0.6] } },

    { "id": "building-outline", "type": "line", "source": "openmaptiles", "source-layer": "building",
      "minzoom": 14,
      "paint": { "line-color": "#2a2a40", "line-width": 0.5 } },

    { "id": "rail", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["==", "class", "rail"],
      "paint": { "line-color": "#3a3a50", "line-width": 1.5, "line-dasharray": [4, 2] } },

    { "id": "transit", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["==", "class", "transit"],
      "paint": { "line-color": "#4a3a60", "line-width": 1.5 } },

    { "id": "road-minor-casing", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "minor", "service", "track", "path"],
      "minzoom": 13,
      "paint": { "line-color": "#1a1f2e", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 3, 18, 10] } },

    { "id": "road-secondary-casing", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "secondary", "tertiary"],
      "paint": { "line-color": "#1e2535", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 2, 18, 14] } },

    { "id": "road-primary-casing", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["==", "class", "primary"],
      "paint": { "line-color": "#1f2835", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 3, 18, 16] } },

    { "id": "highway-casing", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "trunk", "motorway"],
      "paint": { "line-color": "#1f2835", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 3, 18, 20] } },

    { "id": "road-minor", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "minor", "service", "track", "path"],
      "minzoom": 13,
      "paint": { "line-color": "#2e3545", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 18, 7] } },

    { "id": "road-secondary", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "secondary", "tertiary"],
      "paint": { "line-color": "#38414e", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 18, 10] } },

    { "id": "road-primary", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["==", "class", "primary"],
      "paint": { "line-color": "#4a5568", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 1.5, 18, 12] } },

    { "id": "highway", "type": "line", "source": "openmaptiles", "source-layer": "transportation",
      "filter": ["in", "class", "trunk", "motorway"],
      "paint": { "line-color": "#746855", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1.5, 18, 16] } },

    { "id": "boundary-country", "type": "line", "source": "openmaptiles", "source-layer": "boundary",
      "filter": ["==", "admin_level", 2],
      "paint": { "line-color": "#6b5b7b", "line-width": 1.5, "line-dasharray": [6, 2] } },

    { "id": "boundary-province", "type": "line", "source": "openmaptiles", "source-layer": "boundary",
      "filter": ["==", "admin_level", 4],
      "paint": { "line-color": "#5b4b6b", "line-width": 1, "line-dasharray": [4, 2] } },

    { "id": "place-country", "type": "symbol", "source": "openmaptiles", "source-layer": "place",
      "filter": ["==", "class", "country"],
      "layout": { "text-field": $nameField, "text-font": ["Noto Sans Regular"], "text-size": 14 },
      "paint": { "text-color": "#d0d0e0", "text-halo-color": "#1a1a2e", "text-halo-width": 1.5 } },

    { "id": "place-city", "type": "symbol", "source": "openmaptiles", "source-layer": "place",
      "filter": ["in", "class", "city", "town"],
      "minzoom": 8,
      "layout": { "text-field": $nameField, "text-font": ["Noto Sans Regular"], "text-size": ["interpolate", ["linear"], ["zoom"], 8, 10, 14, 16] },
      "paint": { "text-color": "#c0c0d0", "text-halo-color": "#1a1a2e", "text-halo-width": 1.5 } },

    { "id": "place-village", "type": "symbol", "source": "openmaptiles", "source-layer": "place",
      "filter": ["in", "class", "village", "hamlet", "suburb", "neighbourhood"],
      "minzoom": 12,
      "layout": { "text-field": $nameField, "text-font": ["Noto Sans Regular"], "text-size": 11 },
      "paint": { "text-color": "#a0a0b0", "text-halo-color": "#1a1a2e", "text-halo-width": 1 } },

    { "id": "poi-label", "type": "symbol", "source": "openmaptiles", "source-layer": "poi",
      "minzoom": 14,
      "layout": { "text-field": $nameField, "text-font": ["Noto Sans Regular"], "text-size": 10, "text-anchor": "top", "text-offset": [0, 0.5] },
      "paint": { "text-color": "#9090a0", "text-halo-color": "#1a1a2e", "text-halo-width": 1 } },

    { "id": "road-label", "type": "symbol", "source": "openmaptiles", "source-layer": "transportation_name",
      "minzoom": 13,
      "layout": { "text-field": $nameField, "text-font": ["Noto Sans Regular"], "text-size": 10, "symbol-placement": "line", "text-rotation-alignment": "map" },
      "paint": { "text-color": "#8080a0", "text-halo-color": "#1a1a2e", "text-halo-width": 1.5 } }
  ]
}
    """.trimIndent()
}

private const val CAR_SOURCE = "car-src"
private const val CAR_LAYER = "car-layer"
private const val CAR_ICON = "car-icon"
private const val DEST_SOURCE = "dest-src"
private const val DEST_LAYER = "dest-layer"
private const val DEST_ICON = "dest-icon"
private const val ROUTE_SOURCE = "route-src"
private const val ROUTE_LAYER = "route-layer"
private const val ROUTE_OUTLINE = "route-outline"
private const val PARKED_SOURCE = "parked-src"
private const val PARKED_LAYER = "parked-layer"
private const val PARKED_ICON = "parked-icon"

// 搜索相关逻辑已拆分到 MapSearchService.kt

// ==================== 图标 ====================

private fun createCarArrowBitmap(): Bitmap {
    val s = 64; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(59, 130, 246); style = Paint.Style.FILL }
    val path = Path().apply { moveTo(s/2f,4f); lineTo(s-8f,s-8f); lineTo(s/2f,s-20f); lineTo(8f,s-8f); close() }
    c.drawPath(path, p); p.color = AndroidColor.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; c.drawPath(path, p)
    return bmp
}

private fun createDestPinBitmap(): Bitmap {
    val s = 48; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.FILL }
    c.drawCircle(s/2f, s/2f, s/2f-2f, p); p.color = AndroidColor.rgb(239, 68, 68); c.drawCircle(s/2f, s/2f, s/2f-6f, p)
    return bmp
}

// 🆕 停车位置图标（P图标，绿色表示停车）
private fun createParkedIconBitmap(): Bitmap {
    val s = 48; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(34, 197, 94); style = Paint.Style.FILL } // 绿色
    // 画P字母形状
    val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = s * 0.6f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    c.drawCircle(s/2f, s/2f, s/2f - 2f, p)
    c.drawText("P", s/2f, s/2f + s*0.2f, pText)
    return bmp
}

// ==================== 网络 ====================

private val httpClient = OkHttpClient()

/** 获取路线，返回 geometry JSON + 距离(m) + 时间(s) */
private suspend fun fetchRouteInfo(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): RouteInfo? =
    withContext(Dispatchers.IO) {
        try {
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
            Log.d(TAG, "📍 请求OSRM路线: from=($fromLon,$fromLat) to=($toLon,$toLat)")
            val body = httpClient.newCall(Request.Builder().url(osrmUrl).build()).execute().body?.string()
            if (body != null) {
                Log.d(TAG, "📍 OSRM响应: ${body.take(300)}")
                val osrmRoutes = JSONObject(body).optJSONArray("routes")
                if (osrmRoutes != null && osrmRoutes.length() > 0) {
                    val route = osrmRoutes.getJSONObject(0)
                    val geometry = route.getJSONObject("geometry")
                    val geojsonGeometry = "{\"type\":\"LineString\",\"coordinates\":${geometry.getJSONArray("coordinates").toString()}}"
                    Log.d(TAG, "✅ OSRM路线获取成功")
                    return@withContext RouteInfo(
                        route.optDouble("distance", 0.0).toInt(),
                        route.optDouble("duration", 0.0).toInt(),
                        geojsonGeometry
                    )
                }
            }
            Log.e(TAG, "❌ OSRM路线获取失败")
            null
        } catch (e: Exception) { Log.e(TAG, "获取路线失败: ${e.message}"); null }
    }

// ==================== 工具 ====================

private fun saveAddress(context: Context, key: String, lat: Double, lon: Double, name: String) {
    val prefs = context.getSharedPreferences("map_addresses", Context.MODE_PRIVATE)
    // 🔧 使用CoordinatePreferences保存高精度坐标（String格式）
    com.example.navipilot.utils.CoordinatePreferences.saveCoordinate(prefs, "${key}_lat", lat)
    com.example.navipilot.utils.CoordinatePreferences.saveCoordinate(prefs, "${key}_lon", lon)
    prefs.edit().putString("${key}_name", name).apply()
    Log.i(TAG, "✅ 已保存${if (key == "home") "家" else "公司"}地址: $name (高精度坐标)")
}

private fun clearAddress(context: Context, key: String) {
    context.getSharedPreferences("map_addresses", Context.MODE_PRIVATE).edit()
        .remove("${key}_lat").remove("${key}_lon").remove("${key}_name").apply()
    Log.i(TAG, "已清除${if (key == "home") "家" else "公司"}地址")
}

/**
 * 安全地解析路线几何 JSON，避免 MapLibre Native JNI 崩溃
 * 问题：OSRM 返回的 geometryJson 可能包含无效的 Feature（id 为 null）
 * 解决：从 JSON 提取坐标后手动创建 LineString，避免 Native 层解析问题
 */
private fun safeParseRouteGeometry(geometryJson: String): Feature? {
    if (geometryJson.isBlank()) {
        Log.w(TAG, "路线几何为空，跳过")
        return null
    }
    return try {
        val json = org.json.JSONObject(geometryJson)
        // 验证包含有效的坐标数据
        val coordinates = json.optJSONArray("coordinates")
        if (coordinates == null || coordinates.length() < 2) {
            Log.w(TAG, "路线坐标无效，跳过: ${coordinates?.length() ?: 0} 个点")
            return null
        }
        // 从 JSON 提取坐标，手动创建 LineString
        // 避免使用 LineString.fromJson() 触发 Native 层解析问题
        val points = mutableListOf<Point>()
        for (i in 0 until coordinates.length()) {
            val coord = coordinates.optJSONArray(i) ?: continue
            if (coord.length() >= 2) {
                val lon = coord.getDouble(0)
                val lat = coord.getDouble(1)
                points.add(Point.fromLngLat(lon, lat))
            }
        }
        if (points.size < 2) {
            Log.w(TAG, "有效坐标点不足: ${points.size} 个")
            return null
        }
        // 手动创建 LineString 和 Feature
        val lineString = LineString.fromLngLats(points)
        Feature.fromGeometry(lineString)
    } catch (e: Exception) {
        Log.e(TAG, "路线几何解析失败: ${e.message}")
        null
    }
}

private fun loadAddress(context: Context, key: String): OsmNavDest? {
    val prefs = context.getSharedPreferences("map_addresses", Context.MODE_PRIVATE)
    val name = prefs.getString("${key}_name", null) ?: return null
    // 🔧 修复：使用CoordinatePreferences读取坐标，避免Float精度损失
    val lat = com.example.navipilot.utils.CoordinatePreferences.getCoordinate(prefs, "${key}_lat", 0.0)
    val lon = com.example.navipilot.utils.CoordinatePreferences.getCoordinate(prefs, "${key}_lon", 0.0)
    return if (lat != 0.0 && lon != 0.0) OsmNavDest(name, lon, lat) else null
}

// ==================== 导航历史记录 ====================

private const val NAV_HISTORY_PREFS = "nav_history"
private const val NAV_HISTORY_MAX = 3

/** 保存导航历史（最多3条，去重，最新在前） */
private fun saveNavHistory(context: Context, name: String, lon: Double, lat: Double) {
    val prefs = context.getSharedPreferences(NAV_HISTORY_PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString("history_json", "[]") ?: "[]"
    val arr = try { org.json.JSONArray(existing) } catch (_: Exception) { org.json.JSONArray() }
    // 去重：移除同名或坐标极近的旧记录
    val filtered = org.json.JSONArray()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val oName = obj.optString("name", "")
        val oLat = obj.optDouble("lat", 0.0)
        val oLon = obj.optDouble("lon", 0.0)
        if (oName == name || (Math.abs(oLat - lat) < 0.0005 && Math.abs(oLon - lon) < 0.0005)) continue
        filtered.put(obj)
    }
    // 新记录插入头部
    val newArr = org.json.JSONArray()
    newArr.put(JSONObject().put("name", name).put("lon", lon).put("lat", lat))
    for (i in 0 until minOf(filtered.length(), NAV_HISTORY_MAX - 1)) {
        newArr.put(filtered.getJSONObject(i))
    }
    prefs.edit().putString("history_json", newArr.toString()).apply()
}

/** 读取最近导航历史 */
private fun loadNavHistory(context: Context): List<SearchResult> {
    val prefs = context.getSharedPreferences(NAV_HISTORY_PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString("history_json", "[]") ?: "[]"
    val arr = try { org.json.JSONArray(json) } catch (_: Exception) { return emptyList() }
    val results = mutableListOf<SearchResult>()
    for (i in 0 until minOf(arr.length(), NAV_HISTORY_MAX)) {
        val obj = arr.optJSONObject(i) ?: continue
        val name = obj.optString("name", "")
        val lon = obj.optDouble("lon", 0.0)
        val lat = obj.optDouble("lat", 0.0)
        if (name.isNotEmpty() && lon != 0.0 && lat != 0.0) {
            results.add(SearchResult(name, "", lon, lat))
        }
    }
    return results
}

/**
 * 速度圆环按钮（默认 36dp，与地图侧栏 FAB 协调；首页面板可传更小 diameter）
 */
@Composable
internal fun SpeedRingButton(
    value: Int,
    color: Color,
    onClick: () -> Unit,
    diameter: Dp = 36.dp,
    valueTextSize: TextUnit = 11.sp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = (diameter.value / 36f).coerceAtLeast(0.75f)
            val edgePad = 3.dp.toPx() * scale
            val strokeW = 3.dp.toPx() * scale
            val radius = size.minDimension / 2f - edgePad
            drawCircle(
                color = Color(0xFF1E293B).copy(alpha = 0.65f),
                radius = radius + edgePad,
                center = center
            )
            drawCircle(
                color = color,
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
        }
        Text(
            text = value.toString(),
            fontSize = valueTextSize,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// ==================== 主组件 ====================

@Composable
fun OsmMapView(
    latitude: Double,
    longitude: Double,
    bearing: Double,
    speedKmh: Double = 0.0, // 🆕 当前速度（km/h）
    isNavigating: Boolean = false,
    goalLon: Double = 0.0,
    goalLat: Double = 0.0,
    goalName: String = "",
    remainDist: Int = 0,
    remainTime: Int = 0,
    nextTurnDist: Int = 0,
    nextTurnType: Int = -1,
    nextTurnText: String = "",
    laneInfoList: List<com.example.navipilot.LaneInfo> = emptyList(), // 🆕 车道信息列表
    trafficState: Int = 0,
    leftSec: Int = 0,
    trafficLightDirection: Int = 0,
    isVideoExpanded: Boolean = true,
    onToggleVideo: () -> Unit = {},
    isDataCardExpanded: Boolean = true,
    onToggleDataCard: () -> Unit = {},
    onPageChange: (Int) -> Unit = {},
    /** 腾讯地图源：在主页地图槽内打开嵌入的腾讯导航（不再使用全屏 currentPage==12） */
    onOpenTencentEmbeddedNav: () -> Unit = {},
    /** 高德手机 SDK：在主页地图槽内打开嵌入导航 */
    onOpenAmapMobileEmbeddedNav: () -> Unit = {},
    /** Google 导航：在主页地图槽内打开嵌入导航 */
    onOpenGoogleEmbeddedNav: () -> Unit = {},
    cruiseSetSpeed: Int = 0,
    carCruiseSpeed: Int = 0,
    onBlueRingClick: () -> Unit = {},
    onGreenRingClick: () -> Unit = {},
    onHomeNavClick: () -> Unit = {},
    onCompanyNavClick: () -> Unit = {},
    userType: Int = 0,
    onShowAdvancedDialog: () -> Unit = {},
    isAutopilotActive: Boolean = false, // 自动驾驶激活状态（来自JSON active字段）
    mapServiceType: String = "OSM", // 当前地图服务类型
    networkClient: CarrotManNetworkClient? = null, // 🆕 网络客户端
    carrotManFieldsState: androidx.compose.runtime.MutableState<com.example.navipilot.CarrotManFields>? = null, // CarrotManFields 状态（OSM导航写入）
    activeNavMode: androidx.compose.runtime.MutableState<String>? = null, // 🆕 三模互斥
    xiaogeData: com.example.navipilot.XiaogeVehicleData? = null, // 🆕 车辆数据（用于车道分析）
    gpsAccuracy: Float = 0f, // 🆕 GPS精度（米）
    positionMode: String = "GPS", // 🆕 定位模式（GPS/网络定位等）
    parkedLocation: Pair<Double, Double>? = null, // 🆕 停车位置 (lat, lon)
    onNavigateToParked: () -> Unit = {}, // 🆕 找车按钮点击事件
    commaConnectionState: Int = 0, // 🆕 连接状态：0=未连接, 1=已连接, 2=异常
    // ===== 面板按钮触发器（父级递增 → OsmMapView 执行内部逻辑）=====
    searchShowTrigger: Int = 0,
    ledMatrixTrigger: Int = 0,
    homeNavTrigger: Int = 0,
    homeNavLongTrigger: Int = 0,
    companyNavTrigger: Int = 0,
    companyNavLongTrigger: Int = 0,
    // ===== 内部状态上报回调（OsmMapView → 父级，用于按钮外观）=====
    onLedConnectionChange: (Boolean) -> Unit = {},
    onHomeAddressChange: (Boolean) -> Unit = {},
    onCompanyAddressChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 协程/延迟回调里始终读到用户刚切换的最新导航源（避免 LaunchedEffect 闭包拿到旧的 OSM/AMAP）
    val mapNavMode by rememberUpdatedState(mapServiceType)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 屏幕方向检测
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > configuration.screenHeightDp

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var hasReceivedFirstLocation by remember { mutableStateOf(false) }

    // 搜索状态
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedResult by remember { mutableStateOf<SearchResult?>(null) }
    var searchServiceName by remember { mutableStateOf("") } // 当前搜索服务名称
    var selectedProvider by remember { mutableStateOf(SearchProvider.AUTO) } // 用户选择的搜索引擎

    // OSM 内部导航状态
    var osmNav by remember { mutableStateOf<OsmNavDest?>(null) }
    var osmRouteDist by remember { mutableStateOf(0) }
    var osmRouteTime by remember { mutableStateOf(0) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // 🆕 家/公司地址状态（提升到顶层，避免在 if 块内重组问题）
    var homeDest by remember { mutableStateOf<OsmNavDest?>(null) }
    var companyDest by remember { mutableStateOf<OsmNavDest?>(null) }

    // 🆕 OSM 导航管理器（remember 管理生命周期，Activity 重建时自动重建）
    val osmNavigationManager = remember(networkClient, carrotManFieldsState) {
        if (networkClient != null) {
            OsmNavigationManager(context, networkClient, carrotManFieldsState, activeNavMode).apply {
                // 设置路线计算完成回调
                routeEngine.onRouteCalculated = { parsed ->
                    Log.i(TAG, "✅ 路线计算完成: ${(parsed.distance/1000).toInt()}km, 提供者=${parsed.provider}")
                    osmRouteDist = parsed.distance.toInt()
                    osmRouteTime = parsed.duration.toInt()
                }
            }
        } else null
    }

    // 🆕 清理 OSM 导航管理器（Composable 离开组合树时释放资源）
    DisposableEffect(osmNavigationManager) {
        onDispose { osmNavigationManager?.cleanup() }
    }

    remember { MapLibre.getInstance(context.applicationContext) }

    // LED点阵屏状态
    var showLedMatrixDialog by remember { mutableStateOf(false) }
    var isLedConnected by remember { mutableStateOf(false) }
    var ledAutoConnectFailed by remember { mutableStateOf(false) }
    val ledManager = remember { LedMatrixManager.getInstance(context) }
    DisposableEffect(Unit) {
        ledManager.onStateChanged = { state, _ ->
            isLedConnected = (state == LedMatrixManager.State.CONNECTED || state == LedMatrixManager.State.SENDING)
        }
        // 仅在未连接时自动连接（单例可能已经连着了）
        if (ledManager.state == LedMatrixManager.State.IDLE) {
            ledManager.autoConnect(
                initText = "机械小鸽",
                onNotFound = { ledAutoConnectFailed = true }
            )
        } else {
            // 已连接，同步状态
            isLedConnected = (ledManager.state == LedMatrixManager.State.CONNECTED || ledManager.state == LedMatrixManager.State.SENDING)
        }
        onDispose {
            // 单例不销毁，仅清除回调避免泄漏
            ledManager.onStateChanged = null
        }
    }
    // 自动连接失败提示
    LaunchedEffect(ledAutoConnectFailed) {
        if (ledAutoConnectFailed) {
            ledAutoConnectFailed = false
            if (ledManager.getSavedDeviceAddress() != null) {
                android.widget.Toast.makeText(context,
                    localized("⚠️ LED屏未找到，请手动连接", "⚠️ LED not found, connect manually"),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 🤖 LED 自动化显示引擎已移至 MainActivityUI 顶层（不受页面切换影响）
    // 默认：OpenFreeMap 矢量（OSM 数据、免 Key）；国内 tile.openstreetmap.org 常不可用故不再默认直连官方栅格
    val dashyStyleJson = remember { getDashyNavMapDarkStyleJson() }

    // 判断是否有活跃导航（OSM内部 或 外部高德）
    val hasOsmNav = osmNav != null
    val hasExternalNav = isNavigating && goalLon != 0.0 && goalLat != 0.0
    val activeNavName = when {
        hasOsmNav -> osmNav!!.name
        hasExternalNav -> goalName
        else -> ""
    }
    val activeNavLon = if (hasOsmNav) osmNav!!.lon else goalLon
    val activeNavLat = if (hasOsmNav) osmNav!!.lat else goalLat
    
    // ===== 面板触发器监听（父级递增 → 执行内部逻辑）=====

    // 触发：显示搜索面板
    LaunchedEffect(searchShowTrigger) {
        if (searchShowTrigger > 0) showSearch = true
    }

    // 触发：显示 LED 对话框
    LaunchedEffect(ledMatrixTrigger) {
        if (ledMatrixTrigger > 0) showLedMatrixDialog = true
    }

    fun startExternalNavigation(destName: String, destLat: Double, destLon: Double): Boolean {
        return when (mapNavMode) {
            "AMAP" -> {
                val ok = MainActivityUIComponents.sendPoiNavigationToAmapAuto(context, destName, destLat, destLon)
                if (!ok) {
                    android.widget.Toast.makeText(context, localized("高德车机版导航启动失败", "AMap auto navigation failed"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 实际广播在拉起 amapauto 后延迟发送，见 MainActivityUIComponents 日志
                    Log.i(TAG, "已请求高德车机导航: $destName")
                }
                ok
            }
            "AMAP_MOBILE" -> {
                carrotManFieldsState?.let { state ->
                    state.value = state.value.copy(
                        goalPosX = destLon,
                        goalPosY = destLat,
                        szGoalName = destName,
                        isNavigating = true
                    )
                }
                onOpenAmapMobileEmbeddedNav()
                true
            }
            "TENCENT" -> {
                carrotManFieldsState?.let { state ->
                    state.value = state.value.copy(
                        goalPosX = destLon,
                        goalPosY = destLat,
                        szGoalName = destName,
                        isNavigating = true
                    )
                }
                onOpenTencentEmbeddedNav()
                true
            }
            "GOOGLE" -> {
                carrotManFieldsState?.let { state ->
                    state.value = state.value.copy(
                        goalPosX = destLon,
                        goalPosY = destLat,
                        szGoalName = destName,
                        isNavigating = true
                    )
                }
                onOpenGoogleEmbeddedNav()
                true
            }
            else -> false
        }
    }

    // 触发：家地址导航（点击）
    LaunchedEffect(homeNavTrigger) {
        if (homeNavTrigger > 0) {
            val currentHomeDest = loadAddress(context, "home")
            if (currentHomeDest == null) {
                android.widget.Toast.makeText(context, localized("请先设置家地址", "Please set home address first"), android.widget.Toast.LENGTH_SHORT).show()
                homeDest = null
                return@LaunchedEffect
            }
            homeDest = currentHomeDest
            when (mapNavMode) {
                "AMAP" -> {
                    onHomeNavClick()
                    saveNavHistory(context, currentHomeDest.name, currentHomeDest.lon, currentHomeDest.lat)
                }
                "OSM" -> {
                    if (longitude == 0.0 || latitude == 0.0) {
                        android.widget.Toast.makeText(context, localized("无法获取当前位置", "Cannot get current location"), android.widget.Toast.LENGTH_SHORT).show()
                    } else if (osmNavigationManager == null) {
                        android.widget.Toast.makeText(context, localized("导航服务未就绪", "Navigation service not ready"), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        isLoadingRoute = true
                        val dest = currentHomeDest
                        val info = fetchRouteInfo(longitude, latitude, dest.lon, dest.lat)
                        if (info != null) {
                            val routeInfo = RouteInfo(info.distanceM, info.durationS, info.geometryJson)
                            osmNav = dest; osmRouteDist = info.distanceM; osmRouteTime = info.durationS
                            try { mapRef?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(safeParseRouteGeometry(info.geometryJson)) }
                            catch (e: Exception) { Log.e(TAG, "路线失败: ${e.message}") }
                            osmNavigationManager?.startOsmNavigation(dest, routeInfo)
                            saveNavHistory(context, dest.name, dest.lon, dest.lat)
                        } else {
                            android.widget.Toast.makeText(context, localized("无法获取路线", "Cannot get route"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        isLoadingRoute = false
                    }
                }
                else -> {
                    if (startExternalNavigation(currentHomeDest.name, currentHomeDest.lat, currentHomeDest.lon)) {
                        saveNavHistory(context, currentHomeDest.name, currentHomeDest.lon, currentHomeDest.lat)
                    }
                }
            }
        }
    }

    // 触发：清除家地址（长按）
    LaunchedEffect(homeNavLongTrigger) {
        if (homeNavLongTrigger > 0) {
            clearAddress(context, "home")
            homeDest = null
            android.widget.Toast.makeText(context, localized("🗑️ 已清除家地址", "🗑️ Home address cleared"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 触发：公司地址导航（点击）
    LaunchedEffect(companyNavTrigger) {
        if (companyNavTrigger > 0) {
            val currentCompanyDest = loadAddress(context, "company")
            if (currentCompanyDest == null) {
                android.widget.Toast.makeText(context, localized("请先设置公司地址", "Please set work address first"), android.widget.Toast.LENGTH_SHORT).show()
                companyDest = null
                return@LaunchedEffect
            }
            companyDest = currentCompanyDest
            when (mapNavMode) {
                "AMAP" -> {
                    onCompanyNavClick()
                    saveNavHistory(context, currentCompanyDest.name, currentCompanyDest.lon, currentCompanyDest.lat)
                }
                "OSM" -> {
                    if (longitude == 0.0 || latitude == 0.0) {
                        android.widget.Toast.makeText(context, localized("无法获取当前位置", "Cannot get current location"), android.widget.Toast.LENGTH_SHORT).show()
                    } else if (osmNavigationManager == null) {
                        android.widget.Toast.makeText(context, localized("导航服务未就绪", "Navigation service not ready"), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        isLoadingRoute = true
                        val dest = currentCompanyDest
                        val info = fetchRouteInfo(longitude, latitude, dest.lon, dest.lat)
                        if (info != null) {
                            val routeInfo = RouteInfo(info.distanceM, info.durationS, info.geometryJson)
                            osmNav = dest; osmRouteDist = info.distanceM; osmRouteTime = info.durationS
                            try { mapRef?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(safeParseRouteGeometry(info.geometryJson)) }
                            catch (e: Exception) { Log.e(TAG, "路线失败: ${e.message}") }
                            osmNavigationManager?.startOsmNavigation(dest, routeInfo)
                            saveNavHistory(context, dest.name, dest.lon, dest.lat)
                        } else {
                            android.widget.Toast.makeText(context, localized("无法获取路线", "Cannot get route"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        isLoadingRoute = false
                    }
                }
                else -> {
                    if (startExternalNavigation(currentCompanyDest.name, currentCompanyDest.lat, currentCompanyDest.lon)) {
                        saveNavHistory(context, currentCompanyDest.name, currentCompanyDest.lon, currentCompanyDest.lat)
                    }
                }
            }
        }
    }

    // 触发：清除公司地址（长按）
    LaunchedEffect(companyNavLongTrigger) {
        if (companyNavLongTrigger > 0) {
            clearAddress(context, "company")
            companyDest = null
            android.widget.Toast.makeText(context, localized("🗑️ 已清除公司地址", "🗑️ Work address cleared"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 内部状态上报回调（用于面板按钮外观同步）=====
    LaunchedEffect(isLedConnected) { onLedConnectionChange(isLedConnected) }
    LaunchedEffect(homeDest) { onHomeAddressChange(homeDest != null) }
    LaunchedEffect(companyDest) { onCompanyAddressChange(companyDest != null) }

    // 🆕 更新 OSM 导航管理器的位置信息
    LaunchedEffect(latitude, longitude, bearing, speedKmh) {
        if (latitude != 0.0 && longitude != 0.0) {
            val location = Location("gps").apply {
                this.latitude = latitude
                this.longitude = longitude
                this.bearing = bearing.toFloat()
                this.speed = (speedKmh / 3.6).toFloat()  // km/h → m/s
                this.accuracy = 10f  // 默认精度
                this.time = System.currentTimeMillis()
            }
            osmNavigationManager?.updateLocation(location)
        }
    }

    // 车辆位置更新
    LaunchedEffect(latitude, longitude, bearing, isMapReady, isUserInteracting, selectedResult, hasOsmNav, hasExternalNav, nextTurnDist) {
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady || (latitude == 0.0 && longitude == 0.0)) return@LaunchedEffect
        try {
            map.style?.getSourceAs<GeoJsonSource>(CAR_SOURCE)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
            map.style?.getLayer(CAR_LAYER)?.setProperties(PropertyFactory.iconRotate(bearing.toFloat()))
        } catch (e: Exception) { Log.e(TAG, "更新位置失败: ${e.message}") }

        if ((!isUserInteracting && selectedResult == null) || !hasReceivedFirstLocation) {
            // 首次获取到有效位置时，强制居中（忽略 isUserInteracting）
            if (!hasReceivedFirstLocation) {
                hasReceivedFirstLocation = true
                Log.i(TAG, "📍 首次定位成功，强制居中: lat=$latitude, lon=$longitude")
            }
            // 动态缩放：导航中放大，接近路口进一步放大
            val isNavActive = hasOsmNav || hasExternalNav
            val turnDist = if (hasOsmNav) {
                osmNavigationManager?.getCurrentInstruction()?.distanceToManeuver?.toInt() ?: nextTurnDist
            } else nextTurnDist
            val zoom = when {
                !isNavActive -> DEFAULT_ZOOM
                // 接近路口：根据距离插值放大（500m→NAV_ZOOM, 50m→NAV_INTERSECTION_ZOOM）
                isNavActive && turnDist in 1..500 -> {
                    val t = ((500 - turnDist).coerceIn(0, 450)) / 450.0
                    NAV_ZOOM + t * (NAV_INTERSECTION_ZOOM - NAV_ZOOM)
                }
                else -> NAV_ZOOM
            }
            map.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).bearing(bearing).build()
            ), 1000)
        }
    }

    // 目的地标记
    LaunchedEffect(activeNavLon, activeNavLat, hasOsmNav, hasExternalNav, isMapReady, selectedResult) {
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val src = map.style?.getSourceAs<GeoJsonSource>(DEST_SOURCE) ?: return@LaunchedEffect
        val sel = selectedResult
        when {
            sel != null -> src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(sel.lon, sel.lat)))
            activeNavLon != 0.0 && activeNavLat != 0.0 -> src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(activeNavLon, activeNavLat)))
            else -> src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    // 🆕 停车位置标记（未连接comma3时才显示）
    LaunchedEffect(parkedLocation, isMapReady, commaConnectionState) {
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val src = map.style?.getSourceAs<GeoJsonSource>(PARKED_SOURCE) ?: return@LaunchedEffect
        val parked = parkedLocation
        // 连接comma3时不显示停车标记
        if (parked != null && commaConnectionState != 1) {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(parked.second, parked.first)))
            Log.i(TAG, "🅿️ 显示停车标记: ${parked.first}, ${parked.second}")
        } else {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    // 🆕 OSM 导航路线显示（使用 RouteEngine 的路线几何）
    LaunchedEffect(osmNav, isMapReady, latitude, longitude) {
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val src = map.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        val nav = osmNav
        if (nav == null || latitude == 0.0) {
            if (!hasExternalNav) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        
        // 等待 RouteEngine 计算完成
        isLoadingRoute = true
        delay(500) // 给RouteEngine一点时间计算
        
        // 使用 RouteEngine 的路线几何
        val parsed = osmNavigationManager?.routeEngine?.parsedRoute
        if (parsed != null && parsed.geometry.isNotEmpty()) {
            try {
                // 将 GeoCoordinate 列表转换为 MapLibre 的 Point 列表
                val points = parsed.geometry.map { coord ->
                    Point.fromLngLat(coord.longitude, coord.latitude)
                }
                src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
                osmRouteDist = parsed.distance.toInt()
                osmRouteTime = parsed.duration.toInt()
                isLoadingRoute = false
                Log.i(TAG, "✅ 路线已显示在地图上: ${points.size} 个点, 提供者=${parsed.provider}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 显示路线失败: ${e.message}", e)
                isLoadingRoute = false
            }
        } else {
            Log.w(TAG, "⚠️ 等待路线计算...")
        }
    }
    
    // 🆕 RouteEngine 路线更新时刷新地图显示
    LaunchedEffect(osmNavigationManager?.routeEngine?.parsedRoute) {
        val parsed = osmNavigationManager?.routeEngine?.parsedRoute ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val src = map.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        
        // 更新地图上的路线显示
        if (parsed.geometry.isNotEmpty()) {
            try {
                val points = parsed.geometry.map { coord ->
                    Point.fromLngLat(coord.longitude, coord.latitude)
                }
                src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
                Log.i(TAG, "🔄 路线已更新: ${(parsed.distance/1000).toInt()}km, 提供者=${parsed.provider}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 更新路线显示失败: ${e.message}", e)
            }
        }
    }

    // 根据 active 状态切换路线颜色：激活=绿色，未激活=蓝色
    LaunchedEffect(isAutopilotActive, isMapReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val lineColor = if (isAutopilotActive) AndroidColor.rgb(34, 197, 94) else AndroidColor.rgb(59, 130, 246)
        val outlineColor = if (isAutopilotActive) AndroidColor.rgb(21, 128, 61) else AndroidColor.rgb(30, 64, 175)
        map.style?.getLayer(ROUTE_LAYER)?.setProperties(PropertyFactory.lineColor(lineColor))
        map.style?.getLayer(ROUTE_OUTLINE)?.setProperties(PropertyFactory.lineColor(outlineColor))
    }

    // 生命周期
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            val mv = mapViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy(); else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = modifier.background(Color(0xFF0F172A))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).also { mv ->
                            mapViewRef = mv; mv.onCreate(null)
                            mv.getMapAsync { map ->
                                mapRef = map
                                map.addOnCameraMoveStartedListener { r ->
                                    if (r == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) isUserInteracting = true
                                }
                                map.addOnCameraIdleListener { mv.postDelayed({ isUserInteracting = false }, 2000) }
                                Log.i(TAG, "地图样式: OpenFreeMap 矢量 (OSM/免 Key)")
                                val styleBuilder = Style.Builder().fromJson(dashyStyleJson)
                                map.setStyle(styleBuilder) { style ->
                                    style.addImage(CAR_ICON, createCarArrowBitmap())
                                    style.addImage(DEST_ICON, createDestPinBitmap())
                                    style.addSource(GeoJsonSource(ROUTE_SOURCE))
                                    style.addLayer(LineLayer(ROUTE_OUTLINE, ROUTE_SOURCE).apply {
                                        setProperties(PropertyFactory.lineColor(AndroidColor.rgb(30,64,175)), PropertyFactory.lineWidth(8f),
                                            PropertyFactory.lineOpacity(0.8f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
                                    })
                                    style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).apply {
                                        setProperties(PropertyFactory.lineColor(AndroidColor.rgb(59,130,246)), PropertyFactory.lineWidth(5f),
                                            PropertyFactory.lineOpacity(0.9f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
                                    })
                                    style.addSource(GeoJsonSource(DEST_SOURCE))
                                    style.addLayer(SymbolLayer(DEST_LAYER, DEST_SOURCE).apply {
                                        setProperties(PropertyFactory.iconImage(DEST_ICON), PropertyFactory.iconSize(0.7f),
                                            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))
                                    })
                                    val pt = if (latitude != 0.0) Point.fromLngLat(longitude, latitude) else Point.fromLngLat(120.6285, 31.2033)
                                    // 使用 FeatureCollection 包装 Feature，避免 MapLibre Native JNI 崩溃
                                    style.addSource(GeoJsonSource(CAR_SOURCE, FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(pt)))))
                                    style.addLayer(SymbolLayer(CAR_LAYER, CAR_SOURCE).apply {
                                        setProperties(PropertyFactory.iconImage(CAR_ICON), PropertyFactory.iconSize(0.6f),
                                            PropertyFactory.iconRotate(bearing.toFloat()), PropertyFactory.iconAllowOverlap(true),
                                            PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconRotationAlignment("map"))
                                    })
                                    // 🆕 停车标记
                                    style.addImage(PARKED_ICON, createParkedIconBitmap())
                                    val parkedPt = parkedLocation?.let { Point.fromLngLat(it.second, it.first) }
                                    val parkedSource = if (parkedPt != null) {
                                        GeoJsonSource(PARKED_SOURCE, FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(parkedPt))))
                                    } else {
                                        GeoJsonSource(PARKED_SOURCE)
                                    }
                                    style.addSource(parkedSource)
                                    style.addLayer(SymbolLayer(PARKED_LAYER, PARKED_SOURCE).apply {
                                        setProperties(PropertyFactory.iconImage(PARKED_ICON), PropertyFactory.iconSize(0.7f),
                                            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))
                                    })
                                    if (latitude != 0.0) {
                                        map.cameraPosition = CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(DEFAULT_ZOOM).bearing(bearing).build()
                                    }
                                    isMapReady = true; Log.i(TAG, "地图初始化完成")
                                }
                                map.uiSettings.isCompassEnabled = false; map.uiSettings.isLogoEnabled = true; map.uiSettings.isAttributionEnabled = true
                            }
                        }
                    }
                )

                // ===== 搜索覆盖层 =====
                if (showSearch && selectedResult == null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                // 根据当前位置预判搜索服务
                                val expectedService = if (latitude != 0.0 && isInChina(latitude, longitude)) "高德地图" else "谷歌地图"
                                val placeholderText = if (searchServiceName.isNotEmpty()) {
                                    localized("搜索地点... ($searchServiceName)", "Search places... ($searchServiceName)")
                                } else {
                                    localized("搜索地点... ($expectedService)", "Search places... ($expectedService)")
                                }
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text(placeholderText, fontSize = 13.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            keyboardController?.hide()
                                            if (searchQuery.isNotBlank()) {
                                                isSearching = true
                                                scope.launch {
                                                    val prox = if (latitude != 0.0) "$longitude,$latitude" else "120.6285,31.2033"
                                                    val response = searchPlaces(
                                                        "",
                                                        searchQuery,
                                                        prox,
                                                        mapNavMode,
                                                        selectedProvider,
                                                        context.applicationContext
                                                    )
                                                    searchResults = response.results
                                                    searchServiceName = response.serviceName
                                                    isSearching = false
                                                }
                                            }
                                        }
                                    ),
                                    trailingIcon = {
                                        Row {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = ""; searchResults = emptyList(); selectedResult = null }) {
                                                    Icon(Icons.Default.Clear, localized("清除", "Clear"), modifier = Modifier.size(22.dp))
                                                }
                                            }
                                            IconButton(onClick = { showSearch = false; searchQuery = ""; searchResults = emptyList(); selectedResult = null }) {
                                                Icon(Icons.Default.Close, localized("关闭", "Close"), modifier = Modifier.size(22.dp))
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        if (isSearching) {
                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Search, localized("搜索", "Search"), modifier = Modifier.size(22.dp))
                                        }
                                    }
                                )
                                // 搜索引擎选择器
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SearchProvider.entries.forEach { provider ->
                                        FilterChip(
                                            selected = selectedProvider == provider,
                                            onClick = { selectedProvider = provider },
                                            label = { Text(provider.label, fontSize = 11.sp) },
                                            leadingIcon = if (selectedProvider == provider) {
                                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        // 最近导航历史（搜索框为空时显示）
                        if (searchQuery.isEmpty() && searchResults.isEmpty()) {
                            val navHistory = remember { loadNavHistory(context) }
                            if (navHistory.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                                    shape = RoundedCornerShape(12.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Text(
                                            localized("🕐 最近导航", "🕐 Recent"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                        navHistory.forEach { hist ->
                                            ListItem(
                                                headlineContent = {
                                                    Text(hist.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                },
                                                modifier = Modifier.clickable {
                                                    selectedResult = hist
                                                    keyboardController?.hide()
                                                    mapRef?.animateCamera(
                                                        CameraUpdateFactory.newCameraPosition(
                                                            CameraPosition.Builder().target(LatLng(hist.lat, hist.lon)).zoom(14.0).build()
                                                        ),
                                                        800
                                                    )
                                                }
                                            )
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                        }
                                    }
                                }
                            }
                        }
                        if (searchResults.isNotEmpty()) {
                            // 搜索服务标签
                            if (searchServiceName.isNotEmpty()) {
                                val (badgeBg, badgeFg) = when (searchServiceName) {
                                    "高德地图" -> Pair(Color(0xFFFF7D00).copy(alpha = 0.14f), Color(0xFFE65100))
                                    "腾讯地图" -> Pair(Color(0xFF10B981).copy(alpha = 0.15f), Color(0xFF059669))
                                    else -> Pair(Color(0xFF3B82F6).copy(alpha = 0.15f), Color(0xFF2563EB))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        color = badgeBg,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "🔍 $searchServiceName",
                                            fontSize = 10.sp,
                                            color = badgeFg,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            ) {
                                LazyColumn {
                                    items(searchResults) { result ->
                                        ListItem(
                                            headlineContent = {
                                                Text(result.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            },
                                            supportingContent = {
                                                if (result.address.isNotBlank()) {
                                                    Text(result.address, fontSize = 11.sp, maxLines = 1)
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                selectedResult = result
                                                searchResults = emptyList()
                                                keyboardController?.hide()
                                                mapRef?.animateCamera(
                                                    CameraUpdateFactory.newCameraPosition(
                                                        CameraPosition.Builder().target(LatLng(result.lat, result.lon)).zoom(14.0).build()
                                                    ),
                                                    800
                                                )
                                            }
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }
                }

                // ===== 选中地点操作面板 =====
                if (selectedResult != null) {
                    val sel = selectedResult!!
                    ElevatedCard(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sel.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Spacer(Modifier.height(2.dp))
                                    Text(sel.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                IconButton(onClick = {
                                    selectedResult = null; showSearch = false; searchQuery = ""
                                    // 清除目的地标记
                                    mapRef?.style?.getSourceAs<GeoJsonSource>(DEST_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                                }) {
                                    Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(20.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                            Spacer(Modifier.height(10.dp))

                            // 根据用户选择的导航模式显示不同按钮
                            val currentNavMode = mapNavMode

                            // 如果选择了外部导航模式（非 OSM），显示统一的「开始导航」按钮
                            if (currentNavMode != "OSM") {
                                Button(
                                    onClick = {
                                        val didNavigate = startExternalNavigation(sel.name, sel.lat, sel.lon)
                                        if (didNavigate) {
                                            saveNavHistory(context, sel.name, sel.lon, sel.lat)
                                            selectedResult = null
                                            showSearch = false
                                            searchQuery = ""
                                        }
                                    },
                                    enabled = true,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when (currentNavMode) {
                                            "AMAP" -> Color(0xFFFF6B00)
                                            "AMAP_MOBILE" -> Color(0xFFFF6B00)
                                            "TENCENT" -> Color(0xFF10B981)
                                            "GOOGLE" -> Color(0xFF4285F4)
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    ),
                                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 11.dp)
                                ) {
                                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = when (currentNavMode) {
                                            "AMAP" -> localized("开始导航（高德车机版）", "Start Navigation (AMap Auto)")
                                            "AMAP_MOBILE" -> localized("开始导航（高德手机版）", "Start Navigation (AMap Mobile)")
                                            "TENCENT" -> localized("开始导航（腾讯）", "Start Navigation (Tencent)")
                                            "GOOGLE" -> localized("开始导航（Google）", "Start Navigation (Google)")
                                            else -> localized("开始导航", "Start Navigation")
                                        },
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            // 仅在家/公司未设置时显示对应按钮
                            val homeAlreadySet = loadAddress(context, "home") != null
                            val companyAlreadySet = loadAddress(context, "company") != null
                            if (!homeAlreadySet || !companyAlreadySet) {
                                Spacer(Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!homeAlreadySet) {
                                        OutlinedButton(
                                            onClick = {
                                                saveAddress(context, "home", sel.lat, sel.lon, sel.name)
                                                homeDest = OsmNavDest(sel.name, sel.lon, sel.lat)  // 🔧 同步更新状态
                                                selectedResult = null; showSearch = false; searchQuery = ""
                                            },
                                            shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)
                                        ) { Text(localized("🏠 设为家", "🏠 Set Home"), fontSize = 12.sp) }
                                    }
                                    if (!companyAlreadySet) {
                                        OutlinedButton(
                                            onClick = {
                                                saveAddress(context, "company", sel.lat, sel.lon, sel.name)
                                                companyDest = OsmNavDest(sel.name, sel.lon, sel.lat)  // 🔧 同步更新状态
                                                selectedResult = null; showSearch = false; searchQuery = ""
                                            },
                                            shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)
                                        ) { Text(localized("🏢 设为公司", "🏢 Set Work"), fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }

                // 初始化：从 SharedPreferences 加载家/公司地址（用于状态上报回调）
                LaunchedEffect(Unit) {
                    homeDest = loadAddress(context, "home")
                    companyDest = loadAddress(context, "company")
                }
    }

    // LED点阵屏对话框
    if (showLedMatrixDialog) {
        LedMatrixDialog(
            onDismiss = { showLedMatrixDialog = false },
            ledManagerExternal = ledManager,
            onConnectionStateChanged = { connected -> isLedConnected = connected }
        )
    }
}

/** 导航信息栏数据（供外部浮层使用） */
data class OsmNavInfo(
    val name: String,
    val distM: Int,
    val timeS: Int,
    val hasOsmNav: Boolean,
    val hasExternalNav: Boolean
)
