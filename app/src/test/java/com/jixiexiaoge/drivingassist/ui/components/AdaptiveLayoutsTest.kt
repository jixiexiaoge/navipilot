package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLayoutsTest {
    @Test
    fun `folded phone stays in one column`() {
        // Given
        val foldedWidth = 360f
        // When
        val expanded = hasRoomForTwoPanes(foldedWidth)
        // Then
        assertThat(expanded).isFalse()
    }

    @Test
    fun `expanded square phone uses two panes`() {
        // Given
        val expandedWidth = 696f
        // When
        val expanded = hasRoomForTwoPanes(expandedWidth)
        // Then
        assertThat(expanded).isTrue()
    }

    @Test
    fun `two pane boundary is six hundred usable dp`() {
        // Given / When / Then
        assertThat(hasRoomForTwoPanes(599.99f)).isFalse()
        assertThat(hasRoomForTwoPanes(600f)).isTrue()
    }

    @Test
    fun `large fonts keep panels from becoming cramped`() {
        // Given / When / Then
        assertThat(hasRoomForTwoPanes(720f, fontScale = 1.4f)).isFalse()
        assertThat(hasRoomForTwoPanes(840f, fontScale = 1.4f)).isTrue()
    }

    @Test
    fun `smaller fonts do not split a narrow phone into tiny panels`() {
        // Given / When / Then
        assertThat(hasRoomForTwoPanes(599f, fontScale = 0.85f)).isFalse()
    }

    @Test
    fun `map expansion qualifies only at the same effective dashboard boundary`() {
        // Given / When / Then
        assertThat(hasRoomForTwoPanes(1199f, fontScale = 2f)).isFalse()
        assertThat(hasRoomForTwoPanes(1200f, fontScale = 2f)).isTrue()
        assertThat(hasRoomForTwoPanes(1040f, fontScale = 1.75f)).isFalse()
        assertThat(hasRoomForTwoPanes(1040f, fontScale = 1f)).isTrue()
    }
}
