package com.jixiexiaoge.drivingassist.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProjectionPlayerFitTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var server: NanoHTTPD? = null
    private var browser: WebView? = null

    @After
    fun tearDown() {
        compose.runOnUiThread {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
        }
        server?.stop()
    }

    @Test
    fun completeMapAndTouchCoordinatesFollowWideAndTallWebViewWithoutViewportOwnership() {
        val html = compose.activity.assets.open("amap2tesla_player.html")
            .bufferedReader().use { it.readText() }
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val jpeg = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val fixture = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", html)
                "/health" -> newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    """{"running":true,"width":960,"height":720,"targetFps":1}""",
                )
                "/frame" -> newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong(),
                )
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
        server = fixture
        fixture.start()
        val wide = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.width(if (wide.value) 320.dp else 200.dp)
                .height(if (wide.value) 180.dp else 320.dp)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).also {
                        browser = it
                        it.settings.javaScriptEnabled = true
                        it.webViewClient = WebViewClient()
                        it.loadUrl("http://127.0.0.1:${fixture.listeningPort}/?embedded=1")
                    }
                })
            }
        }
        assertCompleteFrame(wide = true)
        compose.runOnIdle { wide.value = false }
        assertCompleteFrame(wide = false)
    }

    private fun assertCompleteFrame(wide: Boolean) {
        val result = AtomicReference<JSONObject?>()
        compose.waitUntil(15_000) {
            compose.runOnUiThread {
                browser?.evaluateJavascript(
                    """
                    (() => {
                      const image = document.getElementById('screen');
                      if (!image || !image.naturalWidth) return null;
                      const r = image.getBoundingClientRect();
                      if (${if (wide) "r.width <= r.height" else "r.width >= r.height"}) return null;
                      const scale = Math.min(r.width / image.naturalWidth, r.height / image.naturalHeight);
                      const width = image.naturalWidth * scale;
                      const height = image.naturalHeight * scale;
                      const left = r.left + (r.width - width) / 2;
                      const top = r.top + (r.height - height) / 2;
                      return {
                        fit: getComputedStyle(image).objectFit,
                        top: normalizedPoint({clientX:left,clientY:top}),
                        bottom: normalizedPoint({clientX:left+width,clientY:top+height}),
                        visible: left >= 0 && top >= 0 &&
                          left+width <= innerWidth+1 && top+height <= innerHeight+1,
                        fullscreen: !!document.fullscreenElement,
                        embedded: embeddedPreview,
                        sourceWidth: image.naturalWidth,
                        sourceHeight: image.naturalHeight
                      };
                    })()
                    """.trimIndent(),
                ) { value -> if (value != "null") result.set(JSONObject(value)) }
            }
            result.get() != null
        }
        val actual = checkNotNull(result.get())
        assertEquals("contain", actual.getString("fit"))
        assertTrue("完整地图应在可见区域内: $actual", actual.getBoolean("visible"))
        assertEquals(960, actual.getInt("sourceWidth"))
        assertEquals(720, actual.getInt("sourceHeight"))
        assertEquals(0.0, actual.getJSONObject("top").getDouble("x"), 0.001)
        assertEquals(0.0, actual.getJSONObject("top").getDouble("y"), 0.001)
        assertEquals(1.0, actual.getJSONObject("bottom").getDouble("x"), 0.001)
        assertEquals(1.0, actual.getJSONObject("bottom").getDouble("y"), 0.001)
        assertTrue(actual.getBoolean("embedded"))
        assertTrue(!actual.getBoolean("fullscreen"))
    }
}
