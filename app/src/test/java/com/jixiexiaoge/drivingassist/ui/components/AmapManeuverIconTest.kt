package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmapManeuverIconTest {
    @Test
    fun `navigation icons use verified maneuver mapping not lane Back icon meanings`() {
        assertThat(maneuverGraphic(2)).isEqualTo(ManeuverGraphic.Left)
        assertThat(maneuverGraphic(3)).isEqualTo(ManeuverGraphic.Right)
        assertThat(maneuverGraphic(4)).isEqualTo(ManeuverGraphic.KeepLeft)
        assertThat(maneuverGraphic(5)).isEqualTo(ManeuverGraphic.KeepRight)
        assertThat(maneuverGraphic(6)).isEqualTo(ManeuverGraphic.SharpLeft)
        assertThat(maneuverGraphic(7)).isEqualTo(ManeuverGraphic.SharpRight)
        assertThat(maneuverGraphic(8)).isEqualTo(ManeuverGraphic.ULeft)
        assertThat(maneuverGraphic(19)).isEqualTo(ManeuverGraphic.URight)
        assertThat(maneuverGraphic(1)).isEqualTo(ManeuverGraphic.Straight)
        assertThat(maneuverGraphic(11)).isEqualTo(ManeuverGraphic.Roundabout)
        assertThat(maneuverGraphic(15)).isEqualTo(ManeuverGraphic.Landmark)
        listOf(null, -1, 999, 48, 56, 89).forEach {
            assertThat(maneuverGraphic(it)).isEqualTo(ManeuverGraphic.Unknown)
        }
    }
}
