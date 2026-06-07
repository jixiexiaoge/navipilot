package com.example.navipilot.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.CarrotManFields
import com.example.navipilot.CarrotManNetworkClient
import com.example.navipilot.ui.utils.localized
import org.json.JSONObject

private fun jsonScalarToDisplayString(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Number, is Boolean -> value.toString()
    else -> value.toString()
}

/**
 * 紧凑多列 7706 字段调试表 - 横屏优先，两列并排显示。
 */
@Composable
fun Carrot7706JsonDebugOverlay(
    fields: CarrotManFields,
    networkClient: CarrotManNetworkClient?,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)

    val pairs = remember(fields, networkClient) {
        runCatching {
            val obj: JSONObject = networkClient?.preview7706Json(fields)
                ?: CarrotManNetworkClient.build7706Payload(
                    fields,
                    packetCarrotIndex = if (fields.carrotIndex > 0L) fields.carrotIndex else 1L,
                )
            val sourceLast = fields.source_last
            val speedLimitSource = when (sourceLast) {
                "AMAP" -> "  (高德车机)"; "amap_mobile" -> "  (腾讯优先)"
                "TENCENT" -> "  (腾讯)"; "google_nav" -> "  (Google)"
                else -> ""
            }
            obj.keys().asSequence().sorted().map { key ->
                val value = jsonScalarToDisplayString(obj.opt(key))
                key to if (key == "nRoadLimitSpeed" && (obj.optInt(key, 0) > 0)) "$value$speedLimitSource" else value
            }.toList()
        }.getOrElse { e ->
            listOf("_exception" to (e.message ?: e.toString()))
        }
    }

    // 分成两列：偶数索引在左，奇数索引在右
    val leftCol = pairs.filterIndexed { i, _ -> i % 2 == 0 }
    val rightCol = pairs.filterIndexed { i, _ -> i % 2 == 1 }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = localized("7706 字段表", "7706 fields"),
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Text(
                        text = " (${pairs.size}字段 · 两列)",
                        color = Color(0xFF64748B), fontSize = 10.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (networkClient == null) {
                        Text(localized("未连接", "Offline"), color = Color(0xFFFBBF24), fontSize = 9.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(0.dp)) {
                        Icon(Icons.Default.Close, localized("关闭", "Close"), tint = Color(0xFF94A3B8), modifier = Modifier.padding(4.dp))
                    }
                }

                // 两列布局
                Row(modifier = Modifier.fillMaxSize().weight(1f).padding(top = 2.dp)) {
                    // 左侧列
                    ColumnList(pairs = leftCol, modifier = Modifier.weight(1f).fillMaxHeight())
                    // 分隔线
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF334155)).padding(horizontal = 2.dp))
                    // 右侧列
                    ColumnList(pairs = rightCol, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ColumnList(
    pairs: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    val keyColor = Color(0xFF94A3B8)
    val valColor = Color(0xFFE2E8F0)

    LazyColumn(
        modifier = modifier.padding(horizontal = 2.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(pairs) { _, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = key,
                    color = if (key == "_exception") Color(0xFFF87171) else keyColor,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.45f),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = value,
                    color = valColor,
                    fontSize = 9.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.55f),
                )
            }
            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.4f), thickness = 0.3.dp)
        }
    }
}
