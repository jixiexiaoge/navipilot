package com.jixiexiaoge.drivingassist.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardSnapshotPublisherDeviceTest {
    @Test
    fun productionBuilderPublishesWithOriginalExpiredServiceDeadlines() = runBlocking {
        val owner = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val completed = CompletableDeferred<DashboardSnapshot>()
        try {
            val old = CompactTelemetryStore().accept(listOf(
                CompactFrame("carState", 1, mapOf("vEgo" to 10f)),
            ), 1)
            val publisher = DashboardSnapshotPublisher(owner,
                capture = { DashboardInputs(old, emptyList(), null) },
                publish = { completed.complete(it) })
            publisher.refresh()
            val snapshot = withTimeout(5000) { completed.await() }
            val panel = snapshot.panels.first { it.id == "vehicle" }
            org.junit.Assert.assertEquals("expired", panel.status)
            org.junit.Assert.assertEquals("未知", panel.metrics.first { it.id == "speed" }.value)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun actualSnapshotBuildRunsOffMainButCaptureAndPublicationStayOnMain() = runBlocking {
        val owner = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val completed = CompletableDeferred<Unit>()
        try {
            val publisher = DashboardSnapshotPublisher(owner, capture = {
                assertTrue(Looper.myLooper() == Looper.getMainLooper())
                DashboardInputs(null, emptyList(), null)
            }, build = { input ->
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                input.snapshot(System.nanoTime() / 1_000_000)
            }, publish = {
                assertTrue(Looper.myLooper() == Looper.getMainLooper())
                assertTrue(it.panels.isNotEmpty())
                completed.complete(Unit)
            })
            publisher.refresh()
            withTimeout(5000) { completed.await() }
        } finally {
            owner.cancel()
        }
    }
}
