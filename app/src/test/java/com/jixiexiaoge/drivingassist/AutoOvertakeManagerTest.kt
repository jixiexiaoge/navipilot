package com.jixiexiaoge.drivingassist

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class AutoOvertakeManagerTest {
    private val network = mockk<NetworkManager>(relaxed = true)
    private lateinit var manager: AutoOvertakeManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>()
        every { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) } returns prefs
        manager = AutoOvertakeManager(context, network)
    }

    @After
    fun teardown() { unmockkStatic(Log::class) }

    @Test
    fun `empty data clears pending state without sending any command`() {
        // Given
        seedPendingState()
        // When
        assertThat(manager.update(null)).isNull()
        // Then
        assertCleared()
        verify(exactly = 0) { network.sendControlCommand(any(), any()) }
    }

    @Test
    fun `incomplete authorization cancels pending state before legacy lane inference`() {
        // Given
        seedPendingState()
        // When
        val status = manager.update(XiaogeVehicleData(sequence = 1, timestamp = 1.0,
            ip = null, carState = null, modelV2 = null, systemState = null), roadType = 0)
        // Then
        assertThat(status!!.canOvertake).isFalse()
        assertThat(status.currentLane).isEqualTo(0)
        assertThat(status.lastDirection).isNull()
        assertCleared()
        verify(exactly = 0) { network.sendControlCommand(any(), any()) }
    }

    @Test
    fun `rejected lane command does not record an overtake action`() {
        every { network.sendControlCommand("LANECHANGE", "LEFT") } returns false
        val method = manager.javaClass.getDeclaredMethod(
            "sendLaneChangeCommand", String::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionTime = manager.javaClass.getDeclaredField("lastOvertakeActionTime").apply { isAccessible = true }
        val before = actionTime.getLong(manager)
        assertThat(method.invoke(manager, "LEFT", false)).isEqualTo(false)
        assertThat(actionTime.getLong(manager)).isEqualTo(before)
    }

    private fun seedPendingState() {
        val type = AutoOvertakeManager::class.java
        val pendingType = type.declaredClasses.single { it.simpleName == "PendingLaneChange" }
        val pending = pendingType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("LEFT", 1000L)
        type.getDeclaredField("pendingLaneChange").apply { isAccessible = true }.set(manager, pending)
        type.getDeclaredField("lastOvertakeDirection").apply { isAccessible = true }.set(manager, "LEFT")
        type.getDeclaredField("debounceCounter").apply { isAccessible = true }.setInt(manager, 3)
        type.getDeclaredField("originalLaneIndex").apply { isAccessible = true }.setInt(manager, 2)
    }

    private fun assertCleared() {
        val type = AutoOvertakeManager::class.java
        for (name in listOf("pendingLaneChange", "lastOvertakeDirection")) {
            assertThat(type.getDeclaredField(name).apply { isAccessible = true }.get(manager)).isNull()
        }
        for (name in listOf("debounceCounter", "originalLaneIndex")) {
            assertThat(type.getDeclaredField(name).apply { isAccessible = true }.getInt(manager)).isEqualTo(0)
        }
    }
}
