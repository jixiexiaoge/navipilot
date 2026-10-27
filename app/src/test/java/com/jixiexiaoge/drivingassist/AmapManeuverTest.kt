package com.jixiexiaoge.drivingassist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmapManeuverTest {
    @Test
    fun `AMap icon eight is a U-turn`() {
        val maneuver = AmapManeuver.info(8, AmapIconSource.AUTO_BROADCAST)

        assertThat(maneuver?.labelZh).isEqualTo("掉头")
        assertThat(maneuver?.labelEn).isEqualTo("U-turn")
        assertThat(maneuver?.carrotTurnType).isEqualTo(14)
    }

    @Test
    fun `unknown AMap icon is withheld instead of leaked as a carrot code`() {
        assertThat(AmapManeuver.info(999, AmapIconSource.AUTO_BROADCAST)).isNull()
        // 两侧码域重叠：把高德原码透传过去，CP 会当成「另一个合法动作」显示，
        // 比未知更危险。未知一律 -1（上游据此撤销 guidance_current）。
        assertThat(AmapManeuver.toCarrotTurnType(999, AmapIconSource.AUTO_BROADCAST)).isEqualTo(-1)
    }

    @Test
    fun `roundabout left and right follow the CP-verified code table`() {
        // CP 侧实测 133 = rotary right、139 = rotary left
        assertThat(AmapManeuver.toCarrotTurnType(21, AmapIconSource.AUTO_BROADCAST)).isEqualTo(139)
        assertThat(AmapManeuver.toCarrotTurnType(22, AmapIconSource.AUTO_BROADCAST)).isEqualTo(133)
    }

    @Test
    fun `mobile SDK uses its documented high-numbered icon table`() {
        val maneuver = AmapManeuver.info(34, AmapIconSource.MOBILE_SDK)

        assertThat(maneuver?.labelZh).isEqualTo("掉头")
        assertThat(maneuver?.carrotTurnType).isEqualTo(14)
        assertThat(AmapManeuver.info(8, AmapIconSource.MOBILE_SDK)).isNull()
    }

    @Test
    fun `verified segment action four means keep right`() {
        val action = AmapManeuver.segmentAssistantActionInfo(4)

        assertThat(action?.labelZh).isEqualTo("靠右行驶")
        assertThat(action?.labelEn).isEqualTo("Keep right")
        assertThat(AmapManeuver.segmentAssistantActionInfo(5)).isNull()
    }

    @Test
    fun `generic bearings are not ramp or fork instructions on the wire`() {
        for (icon in listOf(4, 5, 65, 24, 28)) {
            assertThat(AmapManeuver.info(icon, AmapIconSource.AUTO_BROADCAST)).isNotNull()
            assertThat(AmapManeuver.toCarrotTurnType(icon, AmapIconSource.AUTO_BROADCAST)).isEqualTo(-1)
        }
        assertThat(AmapManeuver.toCarrotTurnType(6, AmapIconSource.AUTO_BROADCAST)).isEqualTo(16)
        assertThat(AmapManeuver.toCarrotTurnType(7, AmapIconSource.AUTO_BROADCAST)).isEqualTo(19)
        assertThat(AmapManeuver.toCarrotTurnType(66, AmapIconSource.AUTO_BROADCAST)).isEqualTo(101)
    }
}
