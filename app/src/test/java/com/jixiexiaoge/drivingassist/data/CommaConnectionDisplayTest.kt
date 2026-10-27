package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommaConnectionDisplayTest {
    @Test
    fun `discovery or handshake without fresh 7000 frames is never online`() {
        assertThat(display()).isEqualTo(CommaConnectionDisplay.Searching)
        assertThat(display(selected = true)).isEqualTo(CommaConnectionDisplay.Connecting)
        assertThat(display(ws = ConnectionState.CONNECTING)).isEqualTo(CommaConnectionDisplay.Connecting)
        assertThat(display(ws = ConnectionState.CONNECTED)).isEqualTo(CommaConnectionDisplay.Waiting)
        assertThat(display(running = false)).isEqualTo(CommaConnectionDisplay.Disconnected)
    }

    @Test
    fun `only connected nonexpired compact service data is online`() {
        assertThat(display(ws = ConnectionState.CONNECTED, compact = true)).isEqualTo(CommaConnectionDisplay.Compact)
        assertThat(display(compact = true)).isEqualTo(CommaConnectionDisplay.Searching)
        assertThat(display(ws = ConnectionState.CONNECTING, compact = true)).isEqualTo(CommaConnectionDisplay.Connecting)
    }

    @Test
    fun `timeout withdraws online state without another transport fallback`() {
        assertThat(display(ws = ConnectionState.CONNECTED, timeout = true, compact = true))
            .isEqualTo(CommaConnectionDisplay.Timeout)
        assertThat(display(timeout = true)).isEqualTo(CommaConnectionDisplay.Searching)
        assertThat(display(ws = ConnectionState.CONNECTING, timeout = true)).isEqualTo(CommaConnectionDisplay.Timeout)
    }

    private fun display(
        running: Boolean = true,
        selected: Boolean = false,
        ws: ConnectionState = ConnectionState.DISCONNECTED,
        timeout: Boolean = false,
        compact: Boolean = false,
    ) = resolveCommaConnectionDisplay(running, selected, ws, timeout, compact)
}
