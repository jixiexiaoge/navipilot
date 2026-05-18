package com.example.carrotamap.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.carrotamap.CarrotManFields
import com.example.carrotamap.CarrotManNetworkClient
import com.example.carrotamap.ui.utils.localized
import org.json.JSONObject

/** 将 JSON 单元格格式化为单行展示（7706 包体均为标量） */
private fun jsonScalarToDisplayString(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Number, is Boolean -> value.toString()
    else -> value.toString()
}

/**
 * 全屏调试：以紧凑表格展示与 UDP 7706 即将发送正文一致的字段（键 / 值）。
 */
@Composable
fun Carrot7706JsonDebugOverlay(
    fields: CarrotManFields,
    networkClient: CarrotManNetworkClient?,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)

    val rows = remember(fields, networkClient) {
        runCatching {
            val obj: JSONObject = networkClient?.preview7706Json(fields)
                ?: CarrotManNetworkClient.build7706Payload(
                    fields,
                    packetCarrotIndex = if (fields.carrotIndex > 0L) fields.carrotIndex else 1L,
                )
            obj.keys().asSequence().sorted().map { key ->
                key to jsonScalarToDisplayString(obj.opt(key))
            }.toList()
        }.getOrElse { e ->
            listOf("_exception" to (e.message ?: e.toString()))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = localized("7706 字段表", "7706 fields"),
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = localized("关闭", "Close"),
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                Text(
                    text = localized(
                        "序号=下一包预览；未连接时用占位序号。",
                        "Index = next-packet preview; placeholder if offline.",
                    ),
                    color = Color(0xFF64748B),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp, end = 2.dp),
                )
                if (networkClient == null) {
                    Text(
                        text = localized("未连接", "Offline"),
                        color = Color(0xFFFBBF24),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                    )
                }

                val headerBg = Color(0xFF1E293B)
                val rowDivider = Color(0xFF334155)
                val keyColor = Color(0xFF94A3B8)
                val valColor = Color(0xFFE2E8F0)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(headerBg)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = localized("字段", "Key"),
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.34f),
                            )
                            Text(
                                text = localized("值", "Value"),
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.66f),
                            )
                        }
                        HorizontalDivider(color = rowDivider, thickness = 0.5.dp)
                    }
                    items(rows, key = { it.first }) { (key, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = key,
                                color = if (key == "_exception") Color(0xFFF87171) else keyColor,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.34f),
                            )
                            Text(
                                text = value,
                                color = valColor,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(0.66f),
                            )
                        }
                        HorizontalDivider(color = rowDivider.copy(alpha = 0.55f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
