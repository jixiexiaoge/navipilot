package com.navipilot.amap2tesla.video

import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

class LatestFrameStoreMetricsTest {
    private var now = 1_000L

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
    }

    @After
    fun tearDown() = unmockkStatic(SystemClock::class)

    @Test
    fun `source fps expires when frames stop arriving`() {
        // Given
        val store = LatestFrameStore()
        repeat(10) {
            now += 100
            store.publish(byteArrayOf(1))
        }
        assertThat(store.metrics().encodedFps).isEqualTo(10f)

        // When
        now += 1_001

        // Then
        assertThat(store.metrics().encodedFps).isEqualTo(0f)
        assertThat(store.metrics().frameAgeMillis).isEqualTo(1_001L)
    }

    @Test
    fun `clearing video removes source fps and frame age`() {
        // Given
        val store = LatestFrameStore()
        now += 1_000
        store.publish(byteArrayOf(1))

        // When
        store.clear()

        // Then
        assertThat(store.metrics().encodedFps).isEqualTo(0f)
        assertThat(store.metrics().frameAgeMillis).isEqualTo(-1L)
    }

    @Test
    fun `frame published at time zero still has a valid age`() {
        // Given
        now = 0
        val store = LatestFrameStore()
        store.publish(byteArrayOf(1))

        // When
        now = 5

        // Then
        assertThat(store.metrics().frameAgeMillis).isEqualTo(5L)
    }
}
