package com.jixiexiaoge.drivingassist

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkCommandDeliveryTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `copies preserve requested direction despite shared field replacement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fields = mutableStateOf(CarrotManFields())
        val client = mockk<CarrotManNetworkClient>(relaxed = true)
        every { client.isConnected() } returns true
        every { client.getCurrentDevice() } returns CarrotManNetworkClient.DeviceInfo("127.0.0.1", 7000, "test")
        every { client.allocateCommandIndex() } returnsMany listOf(101L, 102L)
        val packets = mutableListOf<CarrotManFields>()
        val indices = mutableListOf<Long>()
        coEvery { client.sendCommandPacket(capture(packets), capture(indices), "127.0.0.1") } returns Unit
        val manager = NetworkManager(mockk<Context>(relaxed = true), fields)
        manager.javaClass.getDeclaredField("carrotNetworkClient").apply { isAccessible = true }.set(manager, client)
        try {
            assertThat(manager.sendControlCommand("LANECHANGE", "LEFT")).isTrue()
            runCurrent()
            fields.value = CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "RIGHT")
            assertThat(manager.sendControlCommand("LANECHANGE", "RIGHT")).isFalse()
            advanceUntilIdle()
            assertThat(packets.dropLast(1).map { it.carrotArg }.toSet()).containsExactly("LEFT")
            assertThat(packets.dropLast(1).map { it.carrotCmd }.toSet()).containsExactly("LANECHANGE")
            assertThat(indices.dropLast(1).toSet()).containsExactly(101L)
            assertThat(indices.last()).isEqualTo(102L)
            assertThat(packets.last().carrotCmd).isEmpty()
        } finally {
            manager.cleanup()
        }
    }

    @Test
    fun `asynchronous send failure is reported and stops remaining copies`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = mockk<CarrotManNetworkClient>(relaxed = true)
        every { client.isConnected() } returns true
        every { client.getCurrentDevice() } returns CarrotManNetworkClient.DeviceInfo("127.0.0.1", 7000, "test")
        var sends = 0
        coEvery { client.sendCommandPacket(any(), any(), any()) } answers {
            sends++
            throw IOException("target changed")
        }
        val manager = NetworkManager(mockk<Context>(relaxed = true), mutableStateOf(CarrotManFields()))
        manager.javaClass.getDeclaredField("carrotNetworkClient").apply { isAccessible = true }.set(manager, client)
        var failure: String? = null
        try {
            assertThat(manager.sendControlCommand("LANECHANGE", "LEFT") { failure = it }).isTrue()
            advanceUntilIdle()
            assertThat(failure).isEqualTo("target changed")
            assertThat(sends).isEqualTo(1)
        } finally {
            manager.cleanup()
        }
    }

    @Test
    fun `cancelling a partially sent request retires it without replaying an action`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = mockk<CarrotManNetworkClient>(relaxed = true)
        every { client.isConnected() } returns true
        every { client.getCurrentDevice() } returns CarrotManNetworkClient.DeviceInfo("127.0.0.1", 7000, "test")
        val packets = mutableListOf<CarrotManFields>()
        coEvery { client.sendCommandPacket(capture(packets), any(), "127.0.0.1") } returns Unit
        val manager = NetworkManager(mockk<Context>(relaxed = true), mutableStateOf(CarrotManFields()))
        manager.javaClass.getDeclaredField("carrotNetworkClient").apply { isAccessible = true }.set(manager, client)
        try {
            assertThat(manager.sendControlCommand("LANECHANGE", "RIGHT")).isTrue()
            runCurrent()
            val scope = manager.javaClass.getDeclaredField("managerScope").apply { isAccessible = true }
                .get(manager) as CoroutineScope
            scope.coroutineContext[Job]!!.children.single().cancel()
            advanceUntilIdle()
            assertThat(packets.map { it.carrotCmd }).containsExactly("LANECHANGE", "").inOrder()
            assertThat(packets.first().carrotArg).isEqualTo("RIGHT")
        } finally {
            manager.cleanup()
        }
    }
}
