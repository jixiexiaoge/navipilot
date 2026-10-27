package com.jixiexiaoge.drivingassist.data

/** Display-only reasons. None of these states grants control authority. */
enum class AutomationObservation {
    Disconnected, Waiting, Expired, MissingAuthority,
}

internal fun resolveAutomationObservation(
    connected: Boolean,
    timedOut: Boolean,
    telemetry: CompactTelemetry?,
): AutomationObservation {
    val required = listOf("carState", "controlsState", "carControl", "modelV2", "lateralPlan", "selfdriveState")
    val expired = required.any { name ->
        telemetry?.services?.get(name)?.let {
            telemetry.observedAtMs < it.receivedAtMs || telemetry.observedAtMs >= it.expiresAtMs
        } == true
    }
    return when {
        !connected -> AutomationObservation.Disconnected
        timedOut || expired -> AutomationObservation.Expired
        required.any { telemetry?.services?.containsKey(it) != true } -> AutomationObservation.Waiting
        else -> AutomationObservation.MissingAuthority
    }
}
