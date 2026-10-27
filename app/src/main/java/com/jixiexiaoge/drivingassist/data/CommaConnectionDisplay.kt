package com.jixiexiaoge.drivingassist.data

internal enum class CommaConnectionDisplay(val state: Int) {
    Disconnected(0), Searching(0), Connecting(0), Waiting(0),
    Compact(1), Timeout(2),
}

internal fun resolveCommaConnectionDisplay(
    discoveryRunning: Boolean,
    deviceSelected: Boolean,
    compactState: ConnectionState,
    compactTimedOut: Boolean,
    compactFresh: Boolean,
): CommaConnectionDisplay {
    val wsFresh = compactState == ConnectionState.CONNECTED && !compactTimedOut && compactFresh
    return when {
        wsFresh -> CommaConnectionDisplay.Compact
        compactTimedOut && compactState != ConnectionState.DISCONNECTED -> CommaConnectionDisplay.Timeout
        compactState == ConnectionState.CONNECTED -> CommaConnectionDisplay.Waiting
        compactState == ConnectionState.CONNECTING || deviceSelected -> CommaConnectionDisplay.Connecting
        discoveryRunning -> CommaConnectionDisplay.Searching
        else -> CommaConnectionDisplay.Disconnected
    }
}
