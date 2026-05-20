package com.example.navipilot.ui.components

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.example.navipilot.BuildConfig
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "MapSearchService"

// ==================== 数据模型 ====================

/** 搜索结果 */
data class SearchResult(val name: String, val address: String, val lon: Double, val lat: Double)

/** 搜索响应（含使用的服务名称） */
data class SearchResponse(val results: List<SearchResult>, val serviceName: String)

// ==================== 腾讯地图配置 ====================

private const val TENCENT_MAP_KEY = "2R4BZ-TJTC5-SWOIX-IKYMY-KVGE3-QVB3U"
private const val TENCENT_MAP_SK = "AXDG3pLv5fVCxTbvWwwXVHsd1Ch4CLfU"

// ==================== 工具函数 ====================

/** 判断坐标是否在中国境内（粗略矩形范围） */
fun isInChina(lat: Double, lon: Double): Boolean {
    return lat in 3.86..53.55 && lon in 73.66..135.05
}

/** 计算腾讯地图 API 签名（GET 方法）：md5(requestPath + "?" + sortedParams + SK） */
private fun calcTencentSig(path: String, params: Map<String, String>): String {
    val sortedQuery = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    val raw = "$path?$sortedQuery$TENCENT_MAP_SK"
    val md5 = java.security.MessageDigest.getInstance("MD5")
    return md5.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** GCJ-02 转 WGS-84（腾讯/高德坐标 → OSM 坐标） */
fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
    val a = 6378245.0
    val ee = 0.00669342162296594323
    fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }
    fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
    val dx = gcjLon - 105.0
    val dy = gcjLat - 35.0
    var dLat = transformLat(dx, dy)
    var dLon = transformLon(dx, dy)
    val radLat = gcjLat / 180.0 * Math.PI
    var magic = Math.sin(radLat)
    magic = 1 - ee * magic * magic
    val sqrtMagic = Math.sqrt(magic)
    dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
    dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
    return Pair(gcjLat - dLat, gcjLon - dLon)
}

/** 高德 Web 服务数字签名：MD5(按 key 升序拼接的 key=value&... + 安全密钥)，小写十六进制 */
private fun calcAmapSig(params: Map<String, String>, secret: String): String {
    val sorted = TreeMap(params).entries.joinToString("&") { "${it.key}=${it.value}" }
    val raw = sorted + secret
    val md5 = java.security.MessageDigest.getInstance("MD5")
    return md5.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

// ==================== 搜索服务 ====================

private val searchHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/** 腾讯地图关键词搜索（Suggestion API，返回 WGS-84 坐标，含 sig 签名） */
private suspend fun searchPlacesTencent(query: String, lat: Double, lon: Double): List<SearchResult> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val path = "/ws/place/v1/suggestion/"
            val locationStr = "$lat,$lon"
            val params = mapOf(
                "keyword" to query,
                "location" to locationStr,
                "policy" to "11",
                "page_size" to "8",
                "key" to TENCENT_MAP_KEY
            )
            val sig = calcTencentSig(path, params)
            val encodedKeyword = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://apis.map.qq.com$path" +
                    "?keyword=$encodedKeyword" +
                    "&location=$locationStr" +
                    "&policy=11" +
                    "&page_size=8" +
                    "&key=$TENCENT_MAP_KEY" +
                    "&sig=$sig"
            Log.d(TAG, "腾讯地图搜索: keyword=$query")
            val response = searchHttpClient.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string()
            if (body != null) {
                val json = JSONObject(body)
                val status = json.optInt("status", -1)
                if (status == 0) {
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val title = item.optString("title", "")
                            val address = item.optString("address", "")
                            val loc = item.optJSONObject("location")
                            if (loc != null && title.isNotEmpty()) {
                                val gcjLat = loc.optDouble("lat", 0.0)
                                val gcjLon = loc.optDouble("lng", 0.0)
                                if (gcjLat != 0.0 && gcjLon != 0.0) {
                                    val (wgsLat, wgsLon) = gcj02ToWgs84(gcjLat, gcjLon)
                                    results.add(SearchResult(title, address, wgsLon, wgsLat))
                                }
                            }
                        }
                    }
                    Log.i(TAG, "腾讯地图搜索成功: ${results.size}条结果")
                } else {
                    Log.w(TAG, "腾讯地图搜索失败: status=$status, msg=${json.optString("message")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "腾讯地图搜索异常: ${e.message}")
        }
        results
    }

/**
 * 高德 Android 搜索 SDK「输入提示」（使用 Manifest 中 com.amap.api.v2.apikey，避免 JS/Web Key 调 REST 报 10009）。
 * 同步接口在 IO 线程执行；坐标 GCJ-02 → WGS-84。
 */
private suspend fun searchPlacesAmapSdk(
    context: Context,
    query: String,
    biasGcjLat: Double?,
    biasGcjLon: Double?,
): List<SearchResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    try {
        val inputQuery = InputtipsQuery(query, "")
        inputQuery.setCityLimit(false)
        if (biasGcjLat != null && biasGcjLon != null &&
            biasGcjLat != 0.0 && biasGcjLon != 0.0
        ) {
            // LatLonPoint(纬度, 经度)
            inputQuery.setLocation(LatLonPoint(biasGcjLat, biasGcjLon))
        }
        val inputtips = Inputtips(context.applicationContext, inputQuery)
        Log.d(TAG, "高德地图搜索(SDK): keyword=$query")
        val tips: List<Tip> = inputtips.requestInputtips()
        for (tip in tips) {
            val title = tip.name?.trim().orEmpty()
            if (title.isEmpty()) continue
            val point = tip.point ?: continue
            val gcjLat = point.latitude
            val gcjLon = point.longitude
            if (gcjLat == 0.0 && gcjLon == 0.0) continue
            val district = tip.district.orEmpty()
            val addrPart = tip.address.orEmpty()
            val address = when {
                addrPart.isNotEmpty() -> addrPart
                district.isNotEmpty() -> district
                else -> ""
            }
            val (wgsLat, wgsLon) = gcj02ToWgs84(gcjLat, gcjLon)
            results.add(SearchResult(title, address, wgsLon, wgsLat))
        }
        Log.i(TAG, "高德 SDK 搜索成功: ${results.size}条结果")
    } catch (e: AMapException) {
        Log.w(TAG, "高德 SDK 输入提示失败: ${e.message}, errorCode=${e.errorCode}")
    } catch (e: Exception) {
        Log.w(TAG, "高德 SDK 输入提示异常: ${e.message}")
    }
    results
}

/**
 * 高德 REST 输入提示（需「Web服务」类型 Key；JS API Key 会返回 USERKEY_PLAT_NOMATCH / 10009）。
 * 仅作无 Context 或 SDK 无结果时的兜底。
 */
private suspend fun searchPlacesAmapRest(
    query: String,
    biasGcjLat: Double?,
    biasGcjLon: Double?,
): List<SearchResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    val key = BuildConfig.AMAP_WEB_KEY
    val secret = BuildConfig.AMAP_WEB_SECRET
    if (key.isBlank()) {
        Log.d(TAG, "高德 Web Key 未配置，跳过 REST 兜底")
        return@withContext results
    }
    try {
        val params = TreeMap<String, String>()
        params["keywords"] = query
        params["key"] = key
        if (biasGcjLat != null && biasGcjLon != null &&
            biasGcjLat != 0.0 && biasGcjLon != 0.0
        ) {
            params["location"] = "${biasGcjLon},${biasGcjLat}"
        }
        val urlBuilder = ("https://restapi.amap.com/v3/assistant/inputtips").toHttpUrlOrNull()!!.newBuilder()
        for ((k, v) in params) {
            urlBuilder.addQueryParameter(k, v)
        }
        if (secret.isNotBlank()) {
            urlBuilder.addQueryParameter("sig", calcAmapSig(params, secret))
        }
        val url = urlBuilder.build().toString()
        Log.d(TAG, "高德地图搜索(REST): keyword=$query")
        val response = searchHttpClient.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body?.string()
        if (body != null) {
            val json = JSONObject(body)
            val ok = json.optString("status") == "1" || json.optInt("status", 0) == 1
            if (ok) {
                val tips = json.optJSONArray("tips")
                if (tips != null) {
                    for (i in 0 until tips.length()) {
                        val tip = tips.getJSONObject(i)
                        val title = tip.optString("name", "")
                        val district = tip.optString("district", "")
                        val addr = tip.optString("address", "")
                        val address = when {
                            addr.isNotEmpty() -> addr
                            district.isNotEmpty() -> district
                            else -> ""
                        }
                        val locStr = tip.optString("location", "")
                        if (title.isEmpty() || locStr.isBlank()) continue
                        val parts = locStr.split(",")
                        if (parts.size < 2) continue
                        val gcjLon = parts[0].trim().toDoubleOrNull() ?: continue
                        val gcjLat = parts[1].trim().toDoubleOrNull() ?: continue
                        if (gcjLat == 0.0 && gcjLon == 0.0) continue
                        val (wgsLat, wgsLon) = gcj02ToWgs84(gcjLat, gcjLon)
                        results.add(SearchResult(title, address, wgsLon, wgsLat))
                    }
                }
                Log.i(TAG, "高德 REST 搜索成功: ${results.size}条结果")
            } else {
                val infocode = json.optString("infocode")
                Log.w(
                    TAG,
                    "高德 REST 失败: status=${json.optString("status")}, info=${json.optString("info")}, infocode=$infocode"
                )
                if (infocode == "10009") {
                    Log.w(
                        TAG,
                        "提示：10009=Key 与平台不匹配。App 内已优先用 Android SDK（AndroidManifest 的 apikey）；REST 需单独申请「Web服务」Key。"
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "高德 REST 搜索异常: ${e.message}")
    }
    results
}

/** 高德：优先 Android 搜索 SDK（Manifest Key），失败再尝试 Web REST（可选） */
private suspend fun searchPlacesAmap(
    androidContext: Context?,
    query: String,
    biasGcjLat: Double?,
    biasGcjLon: Double?,
): List<SearchResult> {
    if (androidContext != null) {
        val sdk = searchPlacesAmapSdk(androidContext, query, biasGcjLat, biasGcjLon)
        if (sdk.isNotEmpty()) return sdk
    }
    return searchPlacesAmapRest(query, biasGcjLat, biasGcjLon)
}

/** 谷歌地图 Places API 搜索（海外用，返回 WGS-84 坐标） */
private suspend fun searchPlacesGoogle(query: String, lat: Double?, lon: Double?): List<SearchResult> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        val apiKey = BuildConfig.GOOGLE_PLACES_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "Google Places API Key 未配置，跳过谷歌搜索")
            return@withContext results
        }
        try {
            val urlBuilder = StringBuilder()
                .append("https://maps.googleapis.com/maps/api/place/textsearch/json")
                .append("?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                .append("&key=$apiKey")
                .append("&language=zh-CN")
            if (lat != null && lon != null) {
                urlBuilder.append("&location=$lat,$lon")
                urlBuilder.append("&radius=50000")
            }
            Log.d(TAG, "谷歌地图搜索: keyword=$query")
            val body = searchHttpClient.newCall(
                Request.Builder().url(urlBuilder.toString()).build()
            ).execute().body?.string()
            if (body != null) {
                val json = JSONObject(body)
                val status = json.optString("status", "")
                if (status == "OK") {
                    val allResults = json.optJSONArray("results")
                    if (allResults != null) {
                        for (i in 0 until allResults.length()) {
                            val item = allResults.getJSONObject(i)
                            val name = item.optString("name", "")
                            val address = item.optString("formatted_address", "")
                            val geometry = item.optJSONObject("geometry") ?: continue
                            val location = geometry.optJSONObject("location") ?: continue
                            val gLat = location.optDouble("lat", 0.0)
                            val gLon = location.optDouble("lng", 0.0)
                            if (name.isNotEmpty() && gLat != 0.0 && gLon != 0.0) {
                                results.add(SearchResult(name, address, gLon, gLat))
                            }
                        }
                    }
                    Log.i(TAG, "谷歌地图搜索成功: ${results.size}条结果")
                } else {
                    val errMsg = json.optString("error_message", "")
                    Log.w(TAG, "谷歌地图搜索失败: status=$status, error=$errMsg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "谷歌地图搜索异常: ${e.message}")
        }
        results
    }

/** 可手动选择的搜索模式 */
enum class SearchProvider(val label: String) {
    AUTO("默认"),
    GAODE("高德"),
    TENCENT("腾讯"),
    GOOGLE("谷歌")
}

/**
 * 统一搜索入口：
 * 1. 用户显式指定引擎 → 始终直接调用指定引擎
 * 2. AUTO（国内且有定位）→ 高德（Android 搜索 SDK，失败则 REST）→ 腾讯
 * 3. AUTO（海外或无有效 proximity）→ 谷歌 Places API
 *
 * @param androidContext 用于高德 SDK 输入提示；传 null 时高德仅尝试 REST（易遇 10009）
 */
suspend fun searchPlaces(
    token: String,
    query: String,
    proximity: String,
    mapServiceType: String = "OSM",
    preferredProvider: SearchProvider = SearchProvider.AUTO,
    androidContext: Context? = null,
): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    var serviceName = "高德地图"

    val proxParts = proximity.split(",")
    val proxLon = proxParts.getOrNull(0)?.toDoubleOrNull()
    val proxLat = proxParts.getOrNull(1)?.toDoubleOrNull()

    // ===== 用户指定了搜索引擎 → 直接调用 =====
    if (preferredProvider != SearchProvider.AUTO) {
        val providerResults = when (preferredProvider) {
            SearchProvider.GAODE -> {
                serviceName = "高德地图"
                if (proxLat != null && proxLon != null && isInChina(proxLat, proxLon)) {
                    val gcj = com.example.navipilot.navigation.CoordinateConverter.wgs84ToGcj02(proxLat, proxLon)
                    searchPlacesAmap(androidContext, query, gcj.first, gcj.second)
                } else {
                    searchPlacesAmap(androidContext, query, null, null)
                }
            }
            SearchProvider.TENCENT -> {
                serviceName = "腾讯地图"
                if (proxLat != null && proxLon != null) {
                    val gcjCoords = if (isInChina(proxLat, proxLon)) {
                        com.example.navipilot.navigation.CoordinateConverter.wgs84ToGcj02(proxLat, proxLon)
                    } else {
                        Pair(proxLat, proxLon)
                    }
                    searchPlacesTencent(query, gcjCoords.first, gcjCoords.second)
                } else {
                    emptyList()
                }
            }
            SearchProvider.GOOGLE -> {
                serviceName = "谷歌地图"
                searchPlacesGoogle(query, proxLat, proxLon)
            }
            SearchProvider.AUTO -> emptyList()
        }
        results.addAll(providerResults)
        return@withContext SearchResponse(
            results.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
            serviceName
        )
    }

    // ===== AUTO 模式 =====
    if (proxLat != null && proxLon != null && isInChina(proxLat, proxLon)) {
        // 国内：高德优先 → 腾讯兜底
        val gcjCoords = com.example.navipilot.navigation.CoordinateConverter.wgs84ToGcj02(proxLat, proxLon)
        val amapResults = searchPlacesAmap(androidContext, query, gcjCoords.first, gcjCoords.second)
        if (amapResults.isNotEmpty()) {
            return@withContext SearchResponse(
                amapResults.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
                "高德地图"
            )
        }
        Log.w(TAG, "高德地图无结果，尝试腾讯")
        val tencentResults = searchPlacesTencent(query, gcjCoords.first, gcjCoords.second)
        if (tencentResults.isNotEmpty()) {
            return@withContext SearchResponse(
                tencentResults.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
                "腾讯地图"
            )
        }
        Log.w(TAG, "高德、腾讯均无结果")
    } else {
        // 海外或无定位：谷歌搜索
        Log.d(TAG, "海外/AUTO 模式，调用谷歌搜索")
        val googleResults = searchPlacesGoogle(query, proxLat, proxLon)
        if (googleResults.isNotEmpty()) {
            return@withContext SearchResponse(
                googleResults.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
                "谷歌地图"
            )
        }
        Log.w(TAG, "谷歌搜索无结果")
    }

    SearchResponse(emptyList(), "")
}
