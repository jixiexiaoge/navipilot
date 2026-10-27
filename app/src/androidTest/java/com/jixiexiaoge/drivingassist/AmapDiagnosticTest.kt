package com.jixiexiaoge.drivingassist

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapDiagnosticTest {
    @Test
    fun diagnosticsAreOptInBoundedAndClearedWhenClosed() {
        val manager = AmapBroadcastManager(InstrumentationRegistry.getInstrumentation().targetContext,
            mutableStateOf(CarrotManFields()))
        val packet = Intent().putExtra("lightsData", "x".repeat(3000))
        manager.recordDiagnostic(packet, 60073, "test")
        assertTrue(manager.diagnosticSnapshot().isEmpty())
        manager.setDiagnosticsEnabled(true)
        repeat(105) { manager.recordDiagnostic(packet, it, "test") }
        val snapshot = manager.diagnosticSnapshot()
        assertEquals(100, snapshot.size)
        assertEquals(104, snapshot.first().keyType)
        assertEquals(5, snapshot.last().keyType)
        assertEquals("String: " + "x".repeat(2048), snapshot.first().fields["lightsData"])
        val manyFields = Intent()
        repeat(70) { manyFields.putExtra("field-$it", it) }
        manager.recordDiagnostic(manyFields, 10001, "test")
        assertEquals(48, manager.diagnosticSnapshot().first().fields.size)
        val large = Intent()
        repeat(48) { large.putExtra("field-$it", "x".repeat(5000)) }
        manager.recordDiagnostic(large, 60073, "test")
        assertTrue(manager.diagnosticSnapshot().first().fields.values.sumOf(String::length) <= 8192)
        manager.setDiagnosticsEnabled(false)
        assertTrue(manager.diagnosticSnapshot().isEmpty())
    }
}
