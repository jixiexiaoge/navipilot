package com.example.navipilot.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.ui.utils.localized

@Composable
fun FullscreenBrowserDialog(onDismiss: () -> Unit, url: String, title: String, fallbackUrl: String? = null) {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var hasFallenBack by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        webView?.let { wv -> if (wv.canGoBack()) wv.goBack() else onDismiss() } ?: onDismiss()
                    }) {
                        Icon(
                            imageVector = if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = if (canGoBack) localized("返回", "Back") else localized("关闭", "Close"),
                            tint = Color(0xFF1E293B)
                        )
                    }
                    Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { webView?.let { wv -> if (wv.canGoForward()) wv.goForward() } }, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = localized("前进", "Forward"), tint = if (canGoForward) Color(0xFF1E293B) else Color(0xFF94A3B8))
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = localized("刷新", "Refresh"), tint = Color(0xFF1E293B))
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView = this
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                }
                                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    if (!hasFallenBack && fallbackUrl != null && request?.isForMainFrame == true) {
                                        hasFallenBack = true
                                        view?.loadUrl(fallbackUrl)
                                    }
                                }
                            }
                            settings.apply {
                                javaScriptEnabled = true; domStorageEnabled = true
                                loadWithOverviewMode = true; useWideViewPort = true
                                builtInZoomControls = true; displayZoomControls = false
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                @Suppress("DEPRECATION")
                                databaseEnabled = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                textZoom = 100; loadsImagesAutomatically = true; blockNetworkImage = false
                            }
                            isVerticalScrollBarEnabled = true; isHorizontalScrollBarEnabled = true
                            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
                            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
                            overScrollMode = android.view.View.OVER_SCROLL_ALWAYS
                            isNestedScrollingEnabled = true; setScrollContainer(true); isLongClickable = true
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize().weight(1f)
                )
            }
        }
    }
}
