package com.jixiexiaoge.drivingassist

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class DrivingModeSelectionTest {
    private val saved = mutableMapOf<String, String>()
    private var auto = true
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()
    private val activity = mockk<ComponentActivity>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } answers { saved[firstArg<String>()] }
        every { prefs.getBoolean("automatic_driving_assistance_mode", true) } answers { auto }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            saved[firstArg<String>()] = secondArg<String>()
            editor
        }
        every { editor.putBoolean("automatic_driving_assistance_mode", any()) } answers {
            auto = secondArg()
            editor
        }
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `selection persists across core recreation and never writes vehicle commands or parameters`() {
        // Given
        val core = MainActivityCore(activity, context)
        val network = mockk<NetworkManager>(relaxed = true)
        val params = mockk<CarrotParamClient>(relaxed = true)
        val overtake = mockk<AutoOvertakeManager>(relaxed = true)
        core.networkManager = network
        core.carrotParamClient = params
        core.autoOvertakeManager = overtake
        // When / Then
        assertThat(core.drivingMode).isEqualTo(DrivingMode.HIGHWAY_EAP)
        listOf(DrivingMode.ELEVATED_NOA, DrivingMode.REGULAR_NOO, DrivingMode.HIGHWAY_EAP).forEach { mode ->
            core.selectDrivingMode(mode)
            assertThat(core.drivingMode).isEqualTo(mode)
            assertThat(MainActivityCore(activity, context).drivingMode).isEqualTo(mode)
            assertThat(core.compactVehicleDisplayOnly.value).isTrue()
            assertThat(core.activeNavMode.value).isEqualTo("AMAP")
        }
        verify(exactly = 3) { editor.apply() }
        verify(exactly = 3) { overtake.suspendForIncompleteTelemetry() }
        verify { network wasNot Called }
        verify { params wasNot Called }
        core.selectDrivingMode(DrivingMode.HIGHWAY_EAP)
        verify(exactly = 3) { editor.apply() }
    }

    @Test
    fun `automatic is default manual selection overrides it and reenable persists without vehicle writes`() {
        // Given
        val core = MainActivityCore(activity, context)
        val network = mockk<NetworkManager>(relaxed = true)
        val params = mockk<CarrotParamClient>(relaxed = true)
        core.networkManager = network
        core.carrotParamClient = params
        // When / Then
        assertThat(core.automaticDrivingMode).isTrue()
        assertThat(core.detectedDrivingMode).isNull()
        core.selectDrivingMode(DrivingMode.HIGHWAY_EAP)
        assertThat(core.automaticDrivingMode).isFalse()
        assertThat(MainActivityCore(activity, context).automaticDrivingMode).isFalse()
        core.enableAutomaticDrivingMode()
        assertThat(core.automaticDrivingMode).isTrue()
        assertThat(MainActivityCore(activity, context).automaticDrivingMode).isTrue()
        assertThat(core.detectedDrivingMode).isNull()
        core.enableAutomaticDrivingMode()
        verify(exactly = 2) { editor.apply() }
        verify { network wasNot Called }
        verify { params wasNot Called }
    }

    @Test
    fun `unknown preference logs rejection and does not engage any mode automatically`() {
        // Given
        saved["driving_assistance_mode"] = "fsd"
        // When
        val core = MainActivityCore(activity, context)
        // Then
        assertThat(core.drivingMode).isEqualTo(DrivingMode.HIGHWAY_EAP)
        assertThat(core.compactVehicleDisplayOnly.value).isTrue()
        verify(exactly = 1) { Log.w(any(), match<String> { it.contains("未知驾驶辅助模式配置") }) }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `corrupt mode preference does not prevent restoring the independent map source`() {
        // Given
        saved["user_selected_nav_mode"] = "BAIDU"
        every { prefs.getString("driving_assistance_mode", null) } throws ClassCastException("bad preference")
        // When
        val core = MainActivityCore(activity, context)
        // Then
        assertThat(core.userSelectedMode).isEqualTo("AMAP")
        assertThat(saved["user_selected_nav_mode"]).isEqualTo("AMAP")
        assertThat(core.drivingMode).isEqualTo(DrivingMode.HIGHWAY_EAP)
        verify(exactly = 1) { Log.w(any(), match<String> { it.contains("配置类型错误") }, any()) }
    }
}
