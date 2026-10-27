package com.jixiexiaoge.drivingassist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomaticCruiseSpeedControllerTest {
    @Test
    fun `recovery cannot bypass curve hold or raw limit and advances only after reported cruise`() {
        val controller = AutomaticCruiseSpeedController()
        val input = eligible(cruise = 60, limit = 100)
        assertThat(controller.update(input.copy(recoveryCapKph = 0), 0)).isNull()
        assertThat(controller.update(input.copy(recoveryCapKph = 0), 4000)).isNull()
        assertThat(controller.update(input.copy(recoveryCapKph = 90), 5000)).isNull()
        assertThat(controller.update(input.copy(recoveryCapKph = 90), 8000)).isEqualTo(70)
        controller.recordSent(70, 8000)
        assertThat(controller.update(input.copy(recoveryCapKph = 100), 9000)).isNull()
        val acknowledged = eligible(cruise = 70, limit = 80).copy(recoveryCapKph = 100)
        assertThat(controller.update(acknowledged, 10000)).isNull()
        assertThat(controller.update(acknowledged, 13000)).isEqualTo(80)
    }

    @Test
    fun `manual cruise adjustment cancels recovery until disengagement or a new route`() {
        val controller = AutomaticCruiseSpeedController()
        val input = eligible(cruise = 60, limit = 100).copy(recoveryCapKph = 80)
        controller.update(input, 0)
        assertThat(controller.update(input, 3000)).isEqualTo(70)
        controller.recordSent(70, 3000)
        val manual = input.copy(cruiseSetKph = 65)
        assertThat(controller.update(manual, 4000)).isNull()
        assertThat(controller.update(manual, 20000)).isNull()
        assertThat(controller.update(manual.copy(recoveryCapKph = null), 20500)).isNull()
        controller.update(manual.copy(routeGeneration = 2), 21000)
        assertThat(controller.update(manual.copy(routeGeneration = 2), 24000)).isEqualTo(75)
    }

    @Test
    fun `final reported setpoint completes recovery but intermediate setpoint does not`() {
        val controller = AutomaticCruiseSpeedController()
        val input = eligible(cruise = 60, limit = 80).copy(recoveryCapKph = 80)
        controller.update(input, 0)
        assertThat(controller.update(input, 3000)).isEqualTo(70)
        controller.recordSent(70, 3000)
        controller.update(input.copy(cruiseSetKph = 70), 4000)
        assertThat(controller.takeRecoveryCompletion()).isNull()
        assertThat(controller.update(input.copy(cruiseSetKph = 70), 7000)).isEqualTo(80)
        controller.recordSent(80, 7000)
        assertThat(controller.update(input.copy(cruiseSetKph = 80), 8000)).isNull()
        assertThat(controller.takeRecoveryCompletion()).isEqualTo(1)
    }

    @Test
    fun `sends road limit after eligible state remains stable for three seconds`() {
        val controller = AutomaticCruiseSpeedController()
        val input = eligible(cruise = 80, limit = 100)

        assertThat(controller.update(input, 0)).isNull()
        assertThat(controller.update(input, 2_999)).isNull()
        assertThat(controller.update(input, 3_000)).isEqualTo(100)
    }

    @Test
    fun `never sends when cruise is inactive limit is outside range or correction would lower speed`() {
        val controller = AutomaticCruiseSpeedController()

        assertThat(controller.update(eligible(cruise = 80, limit = 100, engaged = false), 0)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 59), 4_000)).isNull()
        assertThat(controller.update(eligible(cruise = 100, limit = 100), 8_000)).isNull()
        assertThat(controller.update(eligible(cruise = 110, limit = 100), 12_000)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 121), 16_000)).isNull()
    }

    @Test
    fun `limit or route changes restart the stability window`() {
        val controller = AutomaticCruiseSpeedController()

        assertThat(controller.update(eligible(cruise = 80, limit = 100, route = 1), 0)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 110, route = 1), 2_000)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 110, route = 2), 4_000)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 110, route = 2), 6_999)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 110, route = 2), 7_000)).isEqualTo(110)
    }

    @Test
    fun `accepted command is not repeated while telemetry is catching up`() {
        val controller = AutomaticCruiseSpeedController()
        val input = eligible(cruise = 80, limit = 100)

        assertThat(controller.update(input, 0)).isNull()
        assertThat(controller.update(input, 3_000)).isEqualTo(100)
        controller.recordSent(100, 3_000)
        assertThat(controller.update(input, 10_000)).isNull()
        assertThat(controller.update(input, 17_999)).isNull()
        assertThat(controller.update(input, 18_000)).isEqualTo(100)
    }

    @Test
    fun `higher stable limit can supersede a pending lower command`() {
        val controller = AutomaticCruiseSpeedController()

        assertThat(controller.update(eligible(cruise = 80, limit = 100), 0)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 100), 3_000)).isEqualTo(100)
        controller.recordSent(100, 3_000)
        assertThat(controller.update(eligible(cruise = 80, limit = 110), 4_000)).isNull()
        assertThat(controller.update(eligible(cruise = 80, limit = 110), 7_000)).isEqualTo(110)
    }

    private fun eligible(
        cruise: Int,
        limit: Int,
        route: Long = 1,
        engaged: Boolean = true,
    ) = AutomaticCruiseSpeedInput(engaged, cruise, limit, route)
}
