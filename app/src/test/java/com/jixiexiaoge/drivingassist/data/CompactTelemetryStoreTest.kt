package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompactTelemetryStoreTest {
    @Test
    fun `duplicates older frames and half range ambiguity cannot renew service expiry`() {
        // Given
        val store = CompactTelemetryStore()
        val frame = CompactFrame("carState", 20, mapOf("vEgo" to 10f))
        store.accept(listOf(frame), 1000)
        // When / Then
        for (sequence in listOf(20, 19, (20 + 32768) and 0xffff)) {
            val snapshot = store.accept(listOf(frame.copy(sequence = sequence)), 1500)
            assertThat(snapshot.fresh("carState")).isNull()
            assertThat(snapshot.services.getValue("carState").receivedAtMs).isEqualTo(1000)
            assertThat(store.acceptedFramesLastBatch).isEqualTo(0)
            assertThat(store.rejectedFramesLastBatch).isEqualTo(1)
        }
    }

    @Test
    fun `sequence wrap services and connection resets are independent`() {
        // Given
        val store = CompactTelemetryStore()
        store.accept(listOf(CompactFrame("carState", 65535, emptyMap())), 1000)
        // When
        val snapshot = store.accept(listOf(CompactFrame("carState", 0, emptyMap()),
            CompactFrame("controlsState", 1, emptyMap())), 1100)
        // Then
        assertThat(snapshot.fresh("carState")!!.frame.sequence).isEqualTo(0)
        assertThat(snapshot.fresh("controlsState")).isNotNull()
        store.clear()
        assertThat(store.accept(listOf(CompactFrame("carState", 0, emptyMap())), 1200)
            .fresh("carState")).isNotNull()
    }

    @Test
    fun `new transport sequences cannot keep frozen model frames alive`() {
        // Given
        val store = CompactTelemetryStore()
        val model = CompactFrame("modelV2", 1, mapOf("frameId" to 100L))
        store.accept(listOf(model), 1000)
        // When
        val frozen = store.accept(listOf(model.copy(sequence = 2),
            CompactFrame("carState", 1, emptyMap())), 1750)
        // Then
        assertThat(frozen.fresh("modelV2")).isNull()
        assertThat(frozen.fresh("carState")).isNotNull()
        assertThat(store.rejectedFramesLastBatch).isEqualTo(1)
        assertThat(store.modelResetLastBatch).isFalse()
        assertThat(store.accept(listOf(model.copy(sequence = 3, values = mapOf("frameId" to 101L))), 1800)
            .fresh("modelV2")).isNotNull()
    }

    @Test
    fun `model frame rollback withdraws geometry and requests a new connection generation`() {
        // Given
        val store = CompactTelemetryStore()
        store.accept(listOf(CompactFrame("modelV2", 1, mapOf("frameId" to 100L))), 1000)
        // When / Then
        assertThat(store.accept(listOf(CompactFrame("modelV2", 2, mapOf("frameId" to 1L))), 1100)
            .fresh("modelV2")).isNull()
        assertThat(store.modelResetLastBatch).isTrue()
        store.clear()
        assertThat(store.accept(listOf(CompactFrame("modelV2", 1, mapOf("frameId" to 1L))), 1200)
            .fresh("modelV2")).isNotNull()
    }

    @Test
    fun `model frame wrap is valid progress`() {
        // Given
        val store = CompactTelemetryStore()
        store.accept(listOf(CompactFrame("modelV2", 1, mapOf("frameId" to 0xffffffffL))), 1000)
        // When / Then
        assertThat(store.accept(listOf(CompactFrame("modelV2", 2, mapOf("frameId" to 0L))), 1100)
            .fresh("modelV2")).isNotNull()
        assertThat(store.modelResetLastBatch).isFalse()
    }
}
