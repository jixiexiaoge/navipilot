package com.jixiexiaoge.drivingassist.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.navipilot.amap2tesla.ProjectionState
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.Surface800
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.theme.TextTertiary
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import timber.log.Timber

internal const val MAP_PORT_ATTEMPTS = 3

/**
 * 投屏页地址。
 *
 * **必须带 `embedded=1`**：不带时页面走 `/stream` 的 MJPEG 流，
 * Android WebView（Chromium）不支持 `multipart/x-mixed-replace`，画面永远黑屏；
 * 带上后页面改走 `/frame` 逐帧 JPEG，且不会去抢 viewport（不会把虚拟屏按小格重排）。
 */
internal fun mapProjectionUrl(port: Int): String = "http://127.0.0.1:$port/?embedded=1"

private const val RETRY_GAP_MS = 120L
private const val MAX_HEALTH_BYTES = 8192
private const val PORT_CHECK_INTERVAL_MS = 500L

private fun readHealth(connection: HttpURLConnection): JSONObject? {
    val body = connection.inputStream.use { input ->
        val buffer = ByteArray(MAX_HEALTH_BYTES + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        buffer.copyOf(count)
    }
    return body.takeIf { it.size <= MAX_HEALTH_BYTES }?.let { JSONObject(String(it, Charsets.UTF_8)) }
}

internal fun probeLocalMapService(port: Int, timeoutMs: Int = 600): Boolean {
    val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        val health = if (connection.responseCode == HttpURLConnection.HTTP_OK) readHealth(connection) else null
        health?.let {
            it.optString("service") == "navipilot-projection" &&
                it.optInt("schema_version") == 1 && it.opt("running") == true
        } ?: false
    } catch (error: java.io.IOException) {
        Timber.w(error, "Projection health unavailable on %d", port)
        false
    } catch (error: org.json.JSONException) {
        Timber.w(error, "Invalid projection health on %d", port)
        false
    } finally {
        connection.disconnect()
    }
}

private const val MAP_ASPECT_FALLBACK = 4f / 3f

internal data class MapProjectionViewport(val port: Int? = null, val aspect: Float = MAP_ASPECT_FALLBACK)

@Composable
internal fun rememberProjectionViewport(): MapProjectionViewport {
    val owner = LocalLifecycleOwner.current
    var viewport by remember(owner) { mutableStateOf(MapProjectionViewport()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    val port = ProjectionState.serverPort.takeIf { ProjectionState.running }
                    val width = ProjectionState.width
                    val height = ProjectionState.height
                    viewport = MapProjectionViewport(
                        port,
                        if (port != null && width > 0 && height > 0) {
                            width.toFloat() / height
                        } else MAP_ASPECT_FALLBACK,
                    )
                    delay(PORT_CHECK_INTERVAL_MS)
                }
            } finally {
                viewport = MapProjectionViewport()
            }
        }
    }
    return viewport
}

/** 面板状态机：待启动 → 探测中 → 已连上 | 都失败 */
private enum class MapBrowserPhase { Idle, Probing, Ready, Unavailable }

/**
 * 实时页左半屏的内嵌地图浏览器（7.9.60 起取代原 #17「道路引导卡」）。
 *
 * 行为按用户口径（8.0.26 修订）：
 * 1. **既没有高德广播、投屏服务也没开** → 显示「请启动高德地图车机版」，整块可点，点了跳投屏页；
 * 2. **高德广播新鲜 或 投屏服务已在跑** → 检查投屏服务发布的真实端口及健康身份，最多 3 次；
 * 3. 高德广播在但没有投屏服务（端口为空），或三次健康检查失败 → 显示投屏未开启提示。
 *
 * 关键点：投屏服务是否运行**独立于高德导航**。只开启投屏模式、高德没在导航时，
 * 这里也必须去连（原来只认高德广播，导致「开了投屏却看不到地图」）。
 *
 * 探测走 [probeLocalMapService]（HttpURLConnection），探到了才让 WebView 加载，
 * 避免 WebView 在失败时反复渲染系统错误页、把「服务没开」和「页面 404」混在一起。
 *
 * @param amapActive 是否应该去连投屏服务。实时页传
 *   `高德广播新鲜 || 投屏服务在跑`，两者任一成立都算「有画面可看」
 * @param onOpenProjection 点击提示时跳「投屏」页（由 MainActivity 切 Page.Projection）
 * @param probe 探测实现，测试可注入假实现
 */
@Composable
internal fun LocalMapBrowserPanel(
    amapActive: Boolean,
    onOpenProjection: () -> Unit,
    modifier: Modifier = Modifier,
    probe: (Int) -> Boolean = ::probeLocalMapService,
    serverPort: Int? = rememberProjectionViewport().port,
) {
    val totalAttempts = MAP_PORT_ATTEMPTS
    var phase by remember(amapActive, serverPort) {
        mutableStateOf(if (!amapActive) MapBrowserPhase.Idle else MapBrowserPhase.Probing)
    }
    var attempt by remember(amapActive, serverPort) { mutableIntStateOf(0) }
    val port = serverPort ?: 0
    // 测试会传新 lambda，避免把 probe 放进 effect key 导致探测循环重启
    val currentProbe by rememberUpdatedState(probe)

    LaunchedEffect(amapActive, serverPort, attempt) {
        if (!amapActive) return@LaunchedEffect
        if (serverPort == null || attempt >= totalAttempts) {
            phase = MapBrowserPhase.Unavailable
            return@LaunchedEffect
        }
        phase = MapBrowserPhase.Probing
        val alive = withContext(Dispatchers.IO) { currentProbe(serverPort) }
        if (!amapActive) return@LaunchedEffect
        if (alive) {
            phase = MapBrowserPhase.Ready
        } else {
            delay(RETRY_GAP_MS)
            attempt += 1
        }
    }

    Box(
        // 7.9.64：与右半屏实时画面同一个圆角半径，两格看起来是一对
        modifier.clip(RoundedCornerShape(MEDIA_CORNER_RADIUS)).background(Surface800)
            .testTag("local-map-browser"),
    ) {
        when (phase) {
            MapBrowserPhase.Idle -> MapBrowserNotice(
                title = localized("请启动高德地图车机版", "Start AMap Auto first"),
                detail = localized(
                    "接入高德广播后这里自动显示地图投屏 · 点击前往投屏页",
                    "Projection appears once AMap is broadcasting · tap to open projection",
                ),
                accent = true,
                onOpenProjection = onOpenProjection,
                tag = "map-browser-start-amap",
            )
            MapBrowserPhase.Probing -> MapBrowserNotice(
                title = localized("正在连接本地地图投屏…", "Connecting to local projection…"),
                detail = probeDetail(attempt, totalAttempts, port),
                accent = false,
                onOpenProjection = onOpenProjection,
                tag = "map-browser-probing",
            )
            MapBrowserPhase.Unavailable -> MapBrowserNotice(
                title = localized("当前地图投屏功能未开启，请检查！", "Map projection is off — please check"),
                detail = localized(
                    "投屏服务未启动或健康检查失败 · 点击前往投屏页",
                    "Projection is off or failed its health check · tap to open projection",
                ),
                accent = false,
                onOpenProjection = onOpenProjection,
                tag = "map-browser-unavailable",
            )
            MapBrowserPhase.Ready -> LocalMapWebView(
                url = mapProjectionUrl(port),
                onLoadFailed = {
                    phase = MapBrowserPhase.Probing
                    attempt = (attempt + 1).coerceAtMost(totalAttempts)
                },
            )
        }
    }
}

private fun probeDetail(attempt: Int, total: Int, port: Int): String {
    val round = attempt % MAP_PORT_ATTEMPTS + 1
    return localized(
        "127.0.0.1:$port · 第 $round/${MAP_PORT_ATTEMPTS} 次（共 $total 次）",
        "127.0.0.1:$port · attempt $round/$MAP_PORT_ATTEMPTS (of $total)",
    )
}

/** Idle / Probing / Unavailable 三态共用的居中提示块；整块可点去投屏页 */
@Composable
private fun MapBrowserNotice(
    title: String,
    detail: String,
    accent: Boolean,
    onOpenProjection: () -> Unit,
    tag: String,
) {
    Column(
        Modifier.fillMaxSize().clickable(onClick = onOpenProjection)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(50))
                .background((if (accent) Accent else TextTertiary).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (accent) "▶" else "!",
                color = if (accent) Accent else TextTertiary,
                fontSize = 13.sp,
            )
        }
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            detail,
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * 真正的内嵌浏览器。
 *
 * 黑屏修复历程（都在 `assets/amap2tesla_player.html` 的 `#screen` 上）：
 * 1. **URL 必须带 `?embedded=1`**（7.9.60）。投屏页默认走 `/stream`（MJPEG，
 *    `multipart/x-mixed-replace`），Android WebView 用的 Chromium **不支持 MJPEG**，
 *    `<img>` 永远不解码 → 整格黑屏（同页面在 Edge/桌面浏览器里正常，所以容易误判成网络问题）。
 *    带上 `embedded=1` 后页面改走 `/frame` 逐帧 JPEG（fetch + dataURL），WebView 完全支持；
 * 2. **`#screen` 必须 `position:fixed` 锚定视口**（7.9.63 定稿）。
 *    DevTools 实测本 WebView 里 `100vh` 恒等于 0、`html/body` 的 `height:100%` 链也解析成 0
 *    （`htmlCssH=0px` 而 ICB=230px 正常），文档流百分比高度全灭 → img 高度 0 → 黑屏；
 *    实验对比：`fixed + height:100%` = 337×230 ✓，`100vh`/`fixed+100vh` = 0 ✗。
 *    车机/桌面浏览器 fixed 同样标准，行为一致；
 * 3. 要开 `useWideViewPort` 让 meta viewport 生效，否则小格子里按默认 980px 排版。
 *
 * 另外：WebView 实例会复用，端口变化时必须在 `update` 里重新 loadUrl，
 * 否则 8080 失败回退 8081 时还停在旧页面。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LocalMapWebView(url: String, onLoadFailed: () -> Unit) {
    val fail by rememberUpdatedState(onLoadFailed)
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // viewport meta 生效 + 按内容宽度排版，小格子里画面才铺满
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    // 只给本地服务用：不允许 file:// 与跨域混排
                    allowFileAccess = false
                    allowContentAccess = false
                    // 页面自身是 http，内部只有同源 ws/fetch；不放宽混合内容
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                isLongClickable = false
                setOnLongClickListener { true }
                // 7.9.63：Compose AndroidView + clip 父级下 WebView 默认 LAYER_TYPE_NONE
                // 交给内部 tile 合成，帧更新跟不上会丢成黑块；显式压成 View 级硬件层，
                // 强制每帧 raster 完整上屏（配合 HTML 端 fixed 修复，双保险）。
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w("LocalMapBrowser", "Projection HTTP ${errorResponse?.statusCode}")
                            fail()
                        }
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w("LocalMapBrowser", "主框架加载失败 url=$url code=${error?.errorCode}")
                            fail()
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    // 页面把错误显示在自己的 #status 里；这里只把 JS console 落到 logcat 便于排障
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d("LocalMapBrowser", "console ${message.messageLevel()}: ${message.message()}")
                        return true
                    }
                }
                tag = url
                loadUrl(url)
            }
        },
        update = { view ->
            // 端口回退 / 重新探测后 url 变了：同一个 WebView 必须显式重载
            if (view.tag != url) {
                view.tag = url
                view.loadUrl(url)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
        modifier = Modifier.fillMaxSize().testTag("local-map-webview"),
    )
}
