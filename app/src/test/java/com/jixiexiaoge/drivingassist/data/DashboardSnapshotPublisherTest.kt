package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSnapshotPublisherTest {
    @Test
    fun `burst replaces pending work and discards an old target result`() = runTest {
        var input = DashboardInputs(CompactTelemetry(observedAtMs = 1), emptyList(), null)
        val started = mutableListOf<Long>()
        val published = mutableListOf<DashboardSnapshot>()
        val release = CompletableDeferred<Unit>()
        val publisher = DashboardSnapshotPublisher(backgroundScope, capture = { input },
            computationDispatcher = StandardTestDispatcher(testScheduler), build = {
            val generation = requireNotNull(it.telemetry).observedAtMs
            started.add(generation)
            if (generation == 1L) release.await()
            DashboardSnapshot(emptyList(), schemaVersion = generation.toInt())
        }, publish = { published.add(it) })
        publisher.refresh()
        runCurrent()
        repeat(100) {
            input = input.copy(telemetry = CompactTelemetry(observedAtMs = it + 2L))
            publisher.refresh()
        }
        release.complete(Unit)
        runCurrent()
        assertThat(started).containsExactly(1L, 101L).inOrder()
        assertThat(published.map { it.schemaVersion }).containsExactly(101)
    }

    @Test
    fun `owner cancellation prevents an in flight result publishing`() = runTest {
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<DashboardSnapshot>()
        val publisher = DashboardSnapshotPublisher(backgroundScope,
            computationDispatcher = StandardTestDispatcher(testScheduler), build = {
            release.await()
            DashboardSnapshot(emptyList())
        }, publish = { published.add(it) })
        publisher.refresh()
        runCurrent()
        backgroundScope.cancel()
        release.complete(Unit)
        publisher.refresh()
        runCurrent()
        assertThat(published).isEmpty()
    }

    @Test
    fun `captured inputs do not borrow a later target or refresh original deadlines`() {
        DashboardStore.clear()
        try {
            DashboardStore.telemetry = CompactTelemetryStore().accept(listOf(
                CompactFrame("carState", 1, mapOf("vEgo" to 10f)),
            ), 1000)
            val input = DashboardStore.capture()
            DashboardStore.clear()
            val fresh = input.snapshot(1100).panels.first { it.id == "vehicle" }
            assertThat(fresh.metrics.first { it.id == "speed" }.value).isEqualTo("36.0")
            val expired = input.snapshot(1500).panels.first { it.id == "vehicle" }
            assertThat(expired.status).isEqualTo("expired")
            assertThat(expired.metrics.first { it.id == "speed" }.value).isEqualTo("未知")
            assertThat(DashboardStore.snapshot(1100).panels.first { it.id == "vehicle" }.status)
                .isEqualTo("missing")
        } finally {
            DashboardStore.clear()
        }
    }
}
