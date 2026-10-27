@file:Suppress("FunctionNaming", "MagicNumber")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jixiexiaoge.drivingassist.data.JETLINK_DISPLAY_TTL_MS
import com.jixiexiaoge.drivingassist.data.JetlinkPhase
import com.jixiexiaoge.drivingassist.data.JetlinkStatus
import com.jixiexiaoge.drivingassist.data.JetlinkStatusClient
import com.jixiexiaoge.drivingassist.data.JetlinkTone
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import kotlin.math.max

private const val POLL_INTERVAL_MS = 2_000L
private const val EXPIRY_CHECK_MS = 250L
private const val NANOS_PER_MILLISECOND = 1_000_000L

@Composable
internal fun rememberJetlinkStatus(
    host: String?,
    client: JetlinkStatusClient = remember { JetlinkStatusClient() },
): JetlinkStatus {
    val owner = LocalLifecycleOwner.current
    var status by remember(host, owner, client) {
        mutableStateOf(JetlinkStatus(
            phase = if (host.isNullOrBlank()) JetlinkPhase.NoDevice else JetlinkPhase.Querying,
        ))
    }
    LaunchedEffect(host, owner, client) {
        if (host.isNullOrBlank()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            status = JetlinkStatus(phase = JetlinkPhase.Querying)
            var receivedAt: Long? = null
            launch {
                while (isActive) {
                    try {
                        status = client.read(host)
                        receivedAt = System.nanoTime() / NANOS_PER_MILLISECOND
                    } catch (e: IOException) {
                        Timber.w(e, "Jetlink status query failed")
                        status = JetlinkStatus(phase = JetlinkPhase.Unavailable)
                        receivedAt = null
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
            launch {
                while (isActive) {
                    if (receivedAt?.let {
                        System.nanoTime() / NANOS_PER_MILLISECOND - it >= JETLINK_DISPLAY_TTL_MS
                    } == true) {
                        status = JetlinkStatus(phase = JetlinkPhase.Unavailable)
                        receivedAt = null
                    }
                    delay(EXPIRY_CHECK_MS)
                }
            }
        }
    }
    return status
}

@Composable
internal fun JetlinkBadge(status: JetlinkStatus, modifier: Modifier = Modifier, scale: Float = 1f) {
    val color = jetlinkColor(status.tone)
    val labels = jetlinkLabels.getValue(status.phase)
    val phase = localized(labels.first, labels.second)
    val health = jetlinkHealthLabel(status)
    var details by remember { mutableStateOf(false) }
    Row(
        modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .border(max(1f, 1.5f * scale).dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = (11 * scale).dp, vertical = (4 * scale).dp)
            .testTag("jetlink-badge")
            .semantics { contentDescription = "${status.label} · $phase · ${health.orEmpty()} · ${status.reason}" }
            .clickable { details = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((5 * scale).dp),
    ) {
        if (health == null) Box(Modifier.size((12 * scale).dp).background(color, CircleShape))
        else Text("!", color = color, fontWeight = FontWeight.Bold,
            fontSize = (21 * scale).coerceAtLeast(8f).sp)
        Text(status.badgeText, modifier = Modifier.horizontalScroll(rememberScrollState()),
            color = color, fontSize = (21 * scale).coerceAtLeast(8f).sp,
            lineHeight = (21 * scale).coerceAtLeast(8f).sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(status.label) },
            text = { Text(listOfNotNull(phase, health, status.reason.takeIf { it.isNotBlank() })
                .joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { details = false }) { Text(localized("关闭", "Close")) }
            },
        )
    }
}

private fun jetlinkColor(tone: JetlinkTone): Color = when (tone) {
    JetlinkTone.Active -> Color(0xFF15803D)
    JetlinkTone.Ready -> Color(0xFF15803D)
    JetlinkTone.Pending, JetlinkTone.Warning -> Color(0xFFB45309)
    JetlinkTone.Error -> Color(0xFFDC2626)
    JetlinkTone.Neutral -> Color(0xFF777777)
}

private val jetlinkLabels = mapOf(
    JetlinkPhase.NoDevice to ("未选设备" to "No device"),
    JetlinkPhase.Querying to ("查询中" to "Querying"),
    JetlinkPhase.Waiting to ("等待设备" to "Waiting"),
    JetlinkPhase.Connecting to ("连接中" to "Connecting"),
    JetlinkPhase.Loading to ("准备模型" to "Loading"),
    JetlinkPhase.Ready to ("就绪 · 未使用" to "Ready · idle"),
    JetlinkPhase.Active to ("模型使用中" to "Model active"),
    JetlinkPhase.Retrying to ("重连中" to "Retrying"),
    JetlinkPhase.Disconnected to ("状态过期" to "Disconnected"),
    JetlinkPhase.Updating to ("更新中" to "Updating"),
    JetlinkPhase.Blocked to ("连接受阻" to "Blocked"),
    JetlinkPhase.Stopped to ("已停止" to "Stopped"),
    JetlinkPhase.Unknown to ("状态未知" to "Unknown"),
    JetlinkPhase.Unavailable to ("查询不可用" to "Unavailable"),
)

private fun jetlinkHealthLabel(status: JetlinkStatus): String? = when {
    status.severity == "error" -> localized("异常", "Error")
    status.severity == "warning" -> localized("警告", "Warning")
    status.healthUnavailable && !status.fresh ->
        localized("遥测未更新", "Telemetry stale")
    status.healthUnavailable ->
        localized("健康未知", "Health unknown")
    else -> null
}
