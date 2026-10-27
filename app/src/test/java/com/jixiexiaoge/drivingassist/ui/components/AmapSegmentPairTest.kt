package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmapSegmentPairTest {
    @Test
    fun `paired segments use equal two columns at effective width boundary`() {
        // Given / When / Then
        assertThat(segmentPairColumns(279.99f, 1f)).isEqualTo(1)
        assertThat(segmentPairColumns(280f, 1f)).isEqualTo(2)
        assertThat(segmentPairColumns(1040f, 1f)).isEqualTo(2)
    }

    @Test
    fun `large fonts fall back without shrinking text or adding a third column`() {
        // Given / When / Then
        assertThat(segmentPairColumns(420f, 2f)).isEqualTo(1)
        assertThat(segmentPairColumns(560f, 2f)).isEqualTo(2)
        assertThat(segmentPairColumns(840f, 2f)).isEqualTo(2)
    }

    @Test
    fun `small fonts never make narrow panels qualify for pairing`() {
        // Given / When / Then
        assertThat(segmentPairColumns(279f, 0.85f)).isEqualTo(1)
    }
}
