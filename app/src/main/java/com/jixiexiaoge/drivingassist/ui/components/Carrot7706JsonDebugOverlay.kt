package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.CarrotManNetworkClient
import com.jixiexiaoge.drivingassist.AmapBroadcastManager
import com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient
import com.jixiexiaoge.drivingassist.navigation.NaviStreamManager
import com.jixiexiaoge.drivingassist.navigation.NaviV2Constants
import com.jixiexiaoge.drivingassist.navigation.NaviWebSocketV2Client
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

private const val DEBUG_REFRESH_MS = 500L

/** 单条 JSON 流在面板里的展示行 */
private data class StreamRow(
    val name: String,
    val present: Boolean,
    val ageMs: Long,
    val reason: String,
    val sequence: Long,
    val value: String,
)

private data class NavigationDebugSnapshot(
    val udp: JSONObject,
    val command: JSONObject?,
    val client: JSONObject?,
    val streams: JSONObject?,
    val broadcasts: List<AmapBroadcastManager.DiagnosticPacket>,
    val bridge: JSONObject?,
)

@Composable
fun Carrot7706JsonDebugOverlay(
    fields: CarrotManFields,
    networkClient: CarrotManNetworkClient?,
    v2Client: NaviWebSocketV2Client? = null,
    streamManager: NaviStreamManager? = null,
    broadcastManager: AmapBroadcastManager? = null,
    bridge: XiaogeBridgeClient? = null,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    DisposableEffect(broadcastManager) {
        broadcastManager?.setDiagnosticsEnabled(true)
        onDispose { broadcastManager?.setDiagnosticsEnabled(false) }
    }
    val currentFields by rememberUpdatedState(fields)
    val snapshot by produceState<NavigationDebugSnapshot?>(
        null, networkClient, v2Client, streamManager, bridge,
    ) {
        while (isActive) {
            value = NavigationDebugSnapshot(
                udp = networkClient?.preview7706Json(currentFields)
                    ?: CarrotManNetworkClient.build7706Payload(
                        currentFields, packetCarrotIndex = currentFields.carrotIndex.coerceAtLeast(1L),
                    ),
                command = networkClient?.lastCommandJson(),
                client = v2Client?.debugSnapshot(),
                streams = streamManager?.debugSnapshot(),
                broadcasts = broadcastManager?.diagnosticSnapshot().orEmpty(),
                bridge = bridge?.debugSnapshot(),
            )
            delay(DEBUG_REFRESH_MS)
        }
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    var broadcastFilter by remember { mutableIntStateOf(0) }
    var streamFilter by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DebugTab("7706 UDP", selectedTab == 0) { selectedTab = 0 }
                    Spacer(Modifier.width(8.dp))
                    DebugTab("7714 Navi v2", selectedTab == 1) { selectedTab = 1 }
                    Spacer(Modifier.width(8.dp))
                    DebugTab("7711 世界", selectedTab == 2) { selectedTab = 2 }
                    Spacer(Modifier.width(8.dp))
                    DebugTab("高德输入", selectedTab == 3) { selectedTab = 3 }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, localized("关闭", "Close"), tint = Color.White)
                    }
                }
                HorizontalDivider(color = Color(0xFF334155))
                val data = snapshot
                when (selectedTab) {
                    0 -> {
                        Text(
                            localized(
                                "仅时间、命令和目的地；最近发送正文 / 未发送时预览",
                                "Time, commands and destination only; last sent packet / unsent preview",
                            ),
                            color = Color(0xFF94A3B8), fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        val packet = data?.udp
                        val rows = packet?.keys()?.asSequence()?.sorted()?.map { key ->
                            key to (packet.opt(key)?.toString() ?: "null")
                        }?.toList().orEmpty()
                        val commandRows = data?.command?.let { command ->
                            listOf(
                                localized("最近命令（仅本机发送，非车端回执）", "Last command (local send only, not an ACK)") to
                                    command.toString(),
                            )
                        }.orEmpty()
                        DebugFields(commandRows + rows)
                    }

                    1 -> {
                        NaviV2Tab(data, streamFilter) { streamFilter = it }
                    }

                    2 -> {
                        BridgeTab(data?.bridge)
                    }

                    else -> {
                        Text(
                            "仅此页面打开时记录，最多100包；每包48字段/总8192字符，字段2048字符，显示截取，不用于协议发送。",
                            color = Color(0xFF94A3B8), fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0, 10001, 60073, 13012, 10019).forEach { type ->
                                Text(
                                    if (type == 0) "全部" else "$type",
                                    color = if (broadcastFilter == type) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { broadcastFilter = type }.padding(6.dp),
                                )
                            }
                        }
                        DebugFields(data?.broadcasts.orEmpty().filter {
                            broadcastFilter == 0 || it.keyType == broadcastFilter
                        }.mapIndexed { index, packet ->
                            "$index · 处理时刻 ${packet.processedAtMs} · KEY_TYPE=${packet.keyType}" to
                                "${packet.outcome}\n" + packet.fields.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                        })
                    }
                }
            }
        }
    }
}

/** 7714：连接 + 会话 + 13 条 JSON 流的 present/age/seq/value（含撤销原因） */
@Composable
private fun NaviV2Tab(data: NavigationDebugSnapshot?, filter: Int, onFilter: (Int) -> Unit) {
    val client = data?.client
    val rows = remember(data?.streams, client) { buildStreamRows(data?.streams, client) }
    val present = rows.count { it.present }
    val absent = rows.count { !it.present && it.reason.isNotEmpty() }
    val never = rows.count { !it.present && it.reason.isEmpty() }

    Text(
        "${client?.optString("state")?.takeIf { it.isNotEmpty() } ?: "—"} · " +
            "${client?.optString("target")?.takeIf { it.isNotEmpty() } ?: "—"}:" +
            "${client?.optInt("port", 0)?.takeIf { it > 0 } ?: "—"} · " +
            "ready=${client?.optBoolean("ready") ?: false}",
        color = Color(0xFF93C5FD), fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "session: ${client?.optString("sessionId", "—") ?: "—"} · " +
            "rev ${client?.optInt("revision", -1)?.takeIf { it >= 0 } ?: "—"} · " +
            "协商流 ${client?.optInt("streamCount", 0)} · " +
            "控制失败 ${client?.optInt("controlFailCount", 0)} · " +
            "discovery=${client?.optBoolean("discoveryReceived") ?: false}",
        color = Color(0xFF94A3B8), fontSize = 12.sp,
    )
    Text(
        localized("发送快照，不代表carrot已接收确认", "Send snapshot, not a carrot receipt acknowledgement") +
            " · " + localized("有数据", "present") + " $present / " +
            localized("撤销", "withdrawn") + " $absent / " +
            localized("未发送", "never sent") + " $never",
        color = Color(0xFF94A3B8), fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    data?.streams?.optJSONObject("signalMerge")?.let { merge ->
        val carLamp = merge.optInt("carLamp", 0)
        val distance = merge.optDouble("carDistance", Double.NaN)
        val valid = merge.optBoolean("carDistanceValid")
        val maxDistance = merge.optDouble("maxDistanceM", 125.0).toInt()
        Text(
            localized("灯态合成", "Signal merge") + " · 7711 " + lampName(carLamp) + " / " +
                (if (distance.isFinite()) "%.1f m".format(distance) else "null") +
                (if (valid) " ✓" else " ✗") + " · " +
                localized("采用", "chosen") + " ${merge.optString("chosen", "none")}" +
                " (≤$maxDistance m)",
            color = when (merge.optString("chosen", "none")) {
                "car" -> Color(0xFF4ADE80)
                "amap" -> Color(0xFFFBBF24)
                else -> Color(0xFF94A3B8)
            },
            fontSize = 12.sp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            0 to localized("全部 13", "All 13"),
            1 to localized("有数据 $present", "Present $present"),
            2 to localized("无数据 ${absent + never}", "Absent ${absent + never}"),
        ).forEach { (mode, label) ->
            Text(
                label,
                color = if (filter == mode) Color.White else Color(0xFF94A3B8),
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (filter == mode) Color(0xFF1E3A5F) else Color(0xFF1E293B))
                    .clickable { onFilter(mode) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    val shown = when (filter) {
        1 -> rows.filter { it.present }
        2 -> rows.filter { !it.present }
        else -> rows
    }
    DebugFields(shown.map { row -> streamHeader(row) to streamBody(row) })
}

private fun buildStreamRows(streams: JSONObject?, client: JSONObject?): List<StreamRow> {
    val sequences = client?.optJSONObject("sequences")
    return NaviV2Constants.ENABLED_JSON_STREAMS.map { name ->
        val stream = streams?.optJSONObject(name)
        val present = stream?.optBoolean("present") == true
        val raw = stream?.opt("value")
        val value = when {
            !present || raw == null || raw == JSONObject.NULL -> ""
            else -> raw.toString().let { if (it.length > 3000) it.take(3000) + "…" else it }
        }
        StreamRow(
            name = name,
            present = present,
            ageMs = stream?.optLong("ageMs", -1) ?: -1L,
            reason = stream?.optString("reason").orEmpty().takeIf { it.isNotEmpty() && it != "null" }.orEmpty(),
            sequence = sequences?.optLong(name, -1L) ?: -1L,
            value = value,
        )
    }
}

private fun streamHeader(row: StreamRow): String {
    val state = when {
        row.present && row.ageMs in 0..5000 -> "present"
        row.present -> "present · 旧 ${row.ageMs}ms"
        else -> "absent"
    }
    val age = if (row.present && row.ageMs >= 0) " · ${row.ageMs}ms" else ""
    val seq = if (row.sequence >= 0) " · seq ${row.sequence}" else ""
    return "${row.name} · $state$age$seq"
}

private fun streamBody(row: StreamRow): String = when {
    row.present -> row.value.ifEmpty { "—" }
    row.reason.isNotEmpty() -> "${localized("撤销", "withdrawn")} · ${row.reason}"
    else -> localized("未发送", "never sent")
}

/** 7711：车端 xiaoge_data.py 世界量（红绿灯 / 停止线） */
@Composable
private fun BridgeTab(bridge: JSONObject?) {
    val connected = bridge?.optBoolean("connected") ?: false
    val host = bridge?.optString("host").orEmpty()
    val port = bridge?.optInt("port", 0)
    Text(
        "connected=$connected · ${host.ifEmpty { "—" }}:$port · ${localized("20Hz 推送", "20Hz push")}",
        color = if (connected) Color(0xFF86EFAC) else Color(0xFF94A3B8), fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    val world = bridge?.optJSONObject("world")
    if (world == null) {
        Text(
            localized(
                "无世界数据（未连接，或 1.5s 内无新帧；卡片不沿用过期灯色）",
                "No world data (disconnected, or no frame within 1.5s; card does not reuse stale lamp)",
            ),
            color = Color(0xFF94A3B8), fontSize = 12.sp,
        )
        return
    }
    val color = world.optInt("trafficLightColor", 0)
    val distance = world.optDouble("stopLineDist", Double.NaN)
    val receivedAt = world.optLong("receivedAtMs", 0)
    val age = (System.nanoTime() / 1_000_000 - receivedAt).coerceAtLeast(0)
    DebugFields(
        listOf(
            "trafficLightColor" to "$color · ${lampName(color)}",
            "stopLineDist" to if (distance.isFinite()) "%.1f m".format(distance) else "null（车端未给）",
            "派生可见性" to
                "hasLamp=${world.optBoolean("hasLamp")} · " +
                "lampVisible=${world.optBoolean("lampVisible")} · " +
                "stopLineVisible=${world.optBoolean("stopLineVisible")}",
            "帧龄" to "${age}ms（receivedAtMs=$receivedAt）",
        ),
    )
}

/** 车端约定：1 红 / 2 绿 / 3 黄（dashpilot TL_COLOR_MAP，与高德 3=左转绿不同） */
private fun lampName(color: Int): String = when (color) {
    1 -> "红"
    2 -> "绿"
    3 -> "黄"
    0 -> "无灯"
    else -> "未知($color)"
}

@Composable
private fun DebugTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label, color = if (selected) Color.White else Color(0xFF94A3B8), fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF1E3A5F) else Color(0xFF1E293B))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun DebugFields(fields: List<Pair<String, String>>) {
    if (fields.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(localized("等待数据", "Waiting for data"), color = Color(0xFF94A3B8))
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(fields, key = { it.first }) { (name, value) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(name, color = Color(0xFF93C5FD), fontSize = 13.sp)
                    Text(value, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = Color(0xFF334155))
                }
            }
        }
    }
}
