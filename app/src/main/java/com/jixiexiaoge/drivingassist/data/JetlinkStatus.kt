package com.jixiexiaoge.drivingassist.data

import org.json.JSONObject

internal const val JETLINK_DISPLAY_TTL_MS = 3_000L
private const val MAX_REASON_LENGTH = 240

internal enum class JetlinkPhase {
    NoDevice, Querying, Waiting, Connecting, Loading, Ready, Active, Retrying,
    Disconnected, Updating, Blocked, Stopped, Unknown, Unavailable,
}

internal enum class JetlinkTone { Neutral, Pending, Ready, Active, Warning, Error }

internal data class JetlinkStatus(
    val label: String = "Jetlink",
    val phase: JetlinkPhase,
    val severity: String = "unknown",
    val fresh: Boolean = false,
    val reason: String = "",
) {
    val healthUnavailable: Boolean
        get() = label == "jetSON" && phase in setOf(JetlinkPhase.Ready, JetlinkPhase.Active) &&
            (!fresh || severity == "unknown")

    val tone: JetlinkTone
        get() = when {
            phase == JetlinkPhase.Unavailable -> JetlinkTone.Error
            severity == "error" -> JetlinkTone.Error
            phase in setOf(
                JetlinkPhase.Retrying, JetlinkPhase.Disconnected, JetlinkPhase.Blocked, JetlinkPhase.Stopped,
            ) ->
                JetlinkTone.Error
            severity == "warning" -> JetlinkTone.Warning
            healthUnavailable -> JetlinkTone.Warning
            phase == JetlinkPhase.Active -> JetlinkTone.Active
            phase == JetlinkPhase.Ready -> JetlinkTone.Ready
            phase in setOf(JetlinkPhase.Connecting, JetlinkPhase.Loading, JetlinkPhase.Updating) -> JetlinkTone.Pending
            else -> JetlinkTone.Neutral
        }

    val badgeText: String
        get() {
            val state = when (phase) {
                JetlinkPhase.NoDevice -> "NO COMMA"
                JetlinkPhase.Querying -> "CHECK"
                JetlinkPhase.Waiting -> "WAIT"
                JetlinkPhase.Connecting -> "LINK"
                JetlinkPhase.Loading -> "LOAD"
                JetlinkPhase.Ready -> "READY"
                JetlinkPhase.Active -> null
                JetlinkPhase.Retrying -> "RETRY"
                JetlinkPhase.Disconnected -> "OFF"
                JetlinkPhase.Updating -> "UPDATE"
                JetlinkPhase.Blocked -> "BLOCKED"
                JetlinkPhase.Stopped -> "STOP"
                JetlinkPhase.Unknown -> "?"
                JetlinkPhase.Unavailable -> "NO DATA"
            }
            val health = when {
                severity == "error" -> "ERROR"
                severity == "warning" -> "WARN"
                healthUnavailable -> "DATA?"
                else -> null
            }
            return listOfNotNull(label, state, health).joinToString(" ")
        }
}

internal fun parseJetlinkStatus(json: String): JetlinkStatus {
    val root = JSONObject(json)
    require(root.has("jetlink")) { "Missing Jetlink status" }
    if (root.isNull("jetlink")) return JetlinkStatus(phase = JetlinkPhase.Waiting)
    val source = root.getJSONObject("jetlink")
    val state = source.get("state") as? String ?: error("Invalid Jetlink state")
    val active = source.get("active") as? Boolean ?: error("Invalid Jetlink active flag")
    val fresh = source.get("fresh") as? Boolean ?: error("Invalid Jetlink freshness")
    val label = (source.opt("label") as? String)?.takeIf {
        it in setOf("jetSON", "MAC", "iOS", "Android")
    } ?: "Jetlink"
    val phase = jetlinkPhase(state, active)
    return JetlinkStatus(
        label, phase,
        (source.opt("severity") as? String)?.takeIf { it in setOf("ok", "warning", "error") } ?: "unknown",
        fresh, (source.opt("reason") as? String).orEmpty().take(MAX_REASON_LENGTH),
    )
}

private fun jetlinkPhase(state: String, active: Boolean): JetlinkPhase = when (state) {
    "connecting" -> JetlinkPhase.Connecting
    "loading" -> JetlinkPhase.Loading
    "ready" -> if (active) JetlinkPhase.Active else JetlinkPhase.Ready
    "retrying" -> JetlinkPhase.Retrying
    "waiting" -> JetlinkPhase.Waiting
    "disconnected" -> JetlinkPhase.Disconnected
    "updating" -> JetlinkPhase.Updating
    "blocked" -> JetlinkPhase.Blocked
    "stopped" -> JetlinkPhase.Stopped
    else -> JetlinkPhase.Unknown
}
