package com.jixiexiaoge.drivingassist

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Test
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactFrame

class SmartSpeedLimiterTest {
    @Test
    fun `one hundred slows at eight hundred meters in ten kph steps to sixty`() {
        // Given
        val limiter = createLimiter()
        val input = rampInput(remainingDistanceMeters = 900, currentOutputLimit = 100)

        // When
        val outputs = listOf(900, 801, 800, 600, 400, 200, 0).map {
            limiter.update(input.copy(remainingDistanceMeters = it)).outputLimit
        }

        // Then
        assertThat(outputs).containsExactly(100, 100, 90, 80, 70, 60, 60).inOrder()
    }

    @Test
    fun `first ramp notice at five hundred meters distributes the four reductions`() {
        val limiter = createLimiter()
        val input = rampInput(remainingDistanceMeters = 500, currentOutputLimit = 100)
        val outputs = listOf(500, 375, 250, 125, 0).map {
            limiter.update(input.copy(remainingDistanceMeters = it)).outputLimit
        }
        assertThat(outputs).containsExactly(90, 80, 70, 60, 60).inOrder()
    }

    @Test
    fun `eighty and one hundred twenty reach sixty before the ramp`() {
        for (limit in listOf(80, 100, 120)) {
            val limiter = createLimiter()
            val input = rampInput(rawLimit = limit, correctedLimit = limit,
                remainingDistanceMeters = 900, currentOutputLimit = limit)
            var previous = limit
            for (distance in 900 downTo 0) {
                val output = limiter.update(input.copy(
                    remainingDistanceMeters = distance, currentOutputLimit = previous,
                )).outputLimit
                assertThat(output % 10).isEqualTo(0)
                assertThat(previous - output).isIn(listOf(0, 10))
                assertThat(output).isAtLeast(60)
                if (distance > 800) assertThat(output).isEqualTo(limit)
                if (distance == 800) assertThat(output).isEqualTo(limit - 10)
                if (distance == 1) assertThat(output).isEqualTo(60)
                previous = output
            }
        }
    }

    @Test
    fun `distance jump never changes a limit by more than ten per update`() {
        val limiter = createLimiter()
        val input = rampInput(remainingDistanceMeters = 800, currentOutputLimit = 120)
        assertThat(limiter.update(input).outputLimit).isEqualTo(110)
        val outputs = List(5) {
            limiter.update(input.copy(remainingDistanceMeters = 0)).outputLimit
        }
        assertThat(outputs).containsExactly(100, 90, 80, 70, 60).inOrder()
    }

    @Test
    fun `distance jitter never reaccelerates exit deceleration`() {
        val limiter = createLimiter()
        val input = rampInput(remainingDistanceMeters = 800, currentOutputLimit = 100)
        assertThat(limiter.update(input).outputLimit).isEqualTo(90)
        assertThat(limiter.update(input.copy(remainingDistanceMeters = 850)).outputLimit).isEqualTo(90)
        assertThat(limiter.update(input.copy(remainingDistanceMeters = 600)).outputLimit).isEqualTo(80)
        assertThat(limiter.update(input.copy(remainingDistanceMeters = 650)).outputLimit).isEqualTo(80)
    }

    @Test
    fun `up ramp uses ten kph steps only to the reported mainline limit`() {
        var now = 0L
        val limiter = createLimiter(now = { now })
        val input = rampInput(remainingDistanceMeters = 800,
            tbtText = "匝道 进入高速", currentOutputLimit = 60)
        val outputs = (0..5).map {
            now = it * 3_000L
            limiter.update(input.copy(receivedAtMs = now, remainingDistanceMeters = 4000)).outputLimit
        }
        assertThat(outputs).containsExactly(60, 70, 80, 90, 100, 100).inOrder()
    }

    @Test
    fun `no mainline limit does not fabricate acceleration to eighty`() {
        var now = 0L
        val limiter = createLimiter(now = { now })
        val input = rampInput(rawLimit = 40, correctedLimit = 60,
            remainingDistanceMeters = 800, tbtText = "匝道", currentOutputLimit = 60).copy(receivedAtMs = now)
        assertThat(limiter.update(input).outputLimit).isEqualTo(60)
        now = 1000
        val waiting = limiter.update(input.copy(receivedAtMs = now, remainingDistanceMeters = 600))
        assertThat(waiting.outputLimit).isEqualTo(60)
        val mainline = input.copy(rawLimit = 100, correctedLimit = 100, remainingDistanceMeters = 600,
            tbtText = "沪渝高速 主路")
        val outputs = (0..5).map {
            now = 2000 + it * 3000L
            limiter.update(mainline.copy(receivedAtMs = now, remainingDistanceMeters = 5000)).outputLimit
        }
        assertThat(outputs).containsExactly(60, 70, 80, 90, 100, 100).inOrder()
    }

    @Test
    fun `curve holds acceleration but not exit deceleration`() {
        val up = createLimiter(curveEnabled = true).update(rampInput(remainingDistanceMeters = 300,
            tbtText = "匝道 进入高速", currentOutputLimit = 60))
        assertThat(up.outputLimit).isEqualTo(60)
        assertThat(up.transitionType).isEqualTo(SmartSpeedTransitionType.CurveHold)
        val limiter = createLimiter()
        var limit = 120
        for (distance in 800 downTo 300) {
            val result = limiter.update(rampInput(
                rawLimit = 120, correctedLimit = 120, remainingDistanceMeters = distance,
                currentOutputLimit = limit))
            assertThat(result.transitionType).isEqualTo(SmartSpeedTransitionType.MainlineToRamp)
            limit = result.outputLimit
        }
        assertThat(limit).isEqualTo(80)
    }

    @Test
    fun `highway forty becomes sixty but urban forty is preserved`() {
        val input = rampInput(rawLimit = 40, correctedLimit = 40, remainingDistanceMeters = 900,
            tbtText = "沪渝高速", currentOutputLimit = 40)
        assertThat(createLimiter().update(input).outputLimit).isEqualTo(60)
        for (roadType in listOf(1, 3, 7, 8, null)) {
            assertThat(createLimiter().update(input.copy(roadType = roadType)).outputLimit).isEqualTo(40)
        }
    }

    @Test
    fun `reported ramp forty overrides an in progress exit step immediately`() {
        val limiter = createLimiter()
        val exit = rampInput(remainingDistanceMeters = 800, currentOutputLimit = 100)
        assertThat(limiter.update(exit).outputLimit).isEqualTo(90)
        val ramp = exit.copy(rawLimit = 40, correctedLimit = 40,
            remainingDistanceMeters = 600, currentOutputLimit = 90)
        assertThat(limiter.update(ramp).outputLimit).isEqualTo(60)
    }

    @Test
    fun `new entry maneuver replaces exit transition without distance reset`() {
        var now = 0L
        val limiter = createLimiter(now = { now })
        val exit = rampInput(remainingDistanceMeters = 200, currentOutputLimit = 80)
        assertThat(limiter.update(exit).outputLimit).isEqualTo(70)
        assertThat(limiter.update(exit.copy(remainingDistanceMeters = 100)).outputLimit).isEqualTo(60)
        val entry = exit.copy(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路",
            receivedAtMs = now)
        val result = limiter.update(entry)
        assertThat(result.transitionType).isEqualTo(SmartSpeedTransitionType.WaitingForConfirmation)
        assertThat(result.outputLimit).isEqualTo(60)
        now = 3000
        assertThat(limiter.update(entry.copy(receivedAtMs = now)).outputLimit).isEqualTo(70)
        now = 6000
        assertThat(limiter.update(entry.copy(receivedAtMs = now)).outputLimit).isEqualTo(80)
    }

    @Test
    fun `reset discards the previous exit transition`() {
        val limiter = createLimiter()
        val input = rampInput(remainingDistanceMeters = 800, currentOutputLimit = 100)
        limiter.update(input)
        limiter.reset()
        val output = limiter.update(input.copy(tbtText = "沪渝高速 直行"))
        assertThat(output.outputLimit).isEqualTo(100)
        assertThat(output.transitionType).isEqualTo(SmartSpeedTransitionType.None)
    }

    private fun rampInput(
        rawLimit: Int = 100, correctedLimit: Int = 100, roadType: Int? = 0,
        remainingDistanceMeters: Int, tbtText: String = "沪渝高速 出口",
        currentOutputLimit: Int,
    ) = SmartSpeedInput(rawLimit, correctedLimit, roadType, remainingDistanceMeters,
        tbtText, currentOutputLimit)

    private fun createLimiter(
        now: () -> Long = { 0L },
        curveEnabled: Boolean = false,
    ): SmartSpeedLimiter {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        val values = mutableMapOf<String, Any>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getBoolean(any(), any()) } answers {
            if (firstArg<String>() == SmartSpeedLimiter.KEY_CURVE_HOLD_ENABLED) curveEnabled
            else values[firstArg<String>()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<Int>(); editor
        }
        every { editor.putString(any(), any()) } answers {
            secondArg<String?>()?.let { values[firstArg<String>()] = it }; editor
        }
        every { editor.apply() } just Runs
        val context = mockk<Context>()
        every { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) } returns prefs
        return SmartSpeedLimiter(context, now)
    }

    @Test
    fun `fresh paired curvature gates recovery and expiry blocks commands immediately`() {
        var now = 0L
        val limiter = createLimiter({ now }, curveEnabled = true)
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
        limiter.update(input.copy(receivedAtMs = now))
        for (index in 1..24) {
            now = index * 250L
            limiter.updateCurve(curveTelemetry(now))
            limiter.update(input.copy(receivedAtMs = now, remainingDistanceMeters = 5000))
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(80)
        now += 501
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        limiter.updateCurve(curveTelemetry(now, curvature = 0.01f))
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        limiter.updateCurve(curveTelemetry(now + 1, curvature = Float.NaN))
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
    }

    @Test
    fun `duplicate navigation cannot advance recovery and route change discards candidate`() {
        var now = 0L
        val limiter = createLimiter({ now })
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
            .copy(receivedAtMs = 0, routeGeneration = 1)
        limiter.update(input)
        now = 3000
        assertThat(limiter.update(input).outputLimit).isEqualTo(60)
        assertThat(limiter.update(input.copy(receivedAtMs = 3000)).outputLimit).isEqualTo(70)
        now = 6000
        assertThat(limiter.update(input.copy(receivedAtMs = 6000)).outputLimit).isEqualTo(80)
        val next = limiter.update(input.copy(routeGeneration = 2, receivedAtMs = 6001, tbtText = "直行"))
        assertThat(next.outputLimit).isEqualTo(100)
        assertThat(limiter.automaticCruiseCap()).isNull()
    }

    @Test
    fun `missing curve timeout and lower target never release a stale high cap`() {
        var now = 0L
        val limiter = createLimiter({ now }, curveEnabled = true)
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
        for (index in 0..91) {
            now = index * 1000L
            assertThat(limiter.update(input.copy(receivedAtMs = now)).outputLimit).isEqualTo(60)
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        assertThat(limiter.update(input.copy(receivedAtMs = now + 1, rawLimit = 50, correctedLimit = 50))
            .outputLimit).isEqualTo(50)
    }

    @Test
    fun `eighty and one hundred twenty recover after a single ramp receipt without entry keywords`() {
        for (target in listOf(80, 100, 120)) {
            var now = 0L
            val limiter = createLimiter({ now })
            val ramp = rampInput(rawLimit = 40, correctedLimit = 40, remainingDistanceMeters = 50,
                currentOutputLimit = 100, tbtText = "连接道路").copy(receivedAtMs = now)
            assertThat(limiter.update(ramp).outputLimit).isEqualTo(60)
            for (index in 1..24) {
                now = index * 1000L
                val output = limiter.update(ramp.copy(rawLimit = target, correctedLimit = target,
                    receivedAtMs = now, remainingDistanceMeters = 5000, tbtText = "主路"))
                assertThat(output.outputLimit).isAtMost(target)
            }
            assertThat(limiter.automaticCruiseCap()).isEqualTo(target)
        }
    }

    @Test
    fun `duplicate or skewed curve samples cannot satisfy confirmation`() {
        var now = 0L
        val limiter = createLimiter({ now }, curveEnabled = true)
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
        limiter.update(input.copy(receivedAtMs = now))
        val frozen = curveTelemetry(0)
        for (index in 1..16) {
            now = index * 250L
            limiter.updateCurve(frozen)
            limiter.update(input.copy(receivedAtMs = now))
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        for (index in 1..16) {
            now += 250
            val paired = curveTelemetry(now)
            val skewed = paired.copy(services = paired.services + ("carState" to
                paired.services.getValue("carState").copy(receivedAtMs = now - 200)))
            limiter.updateCurve(skewed)
            limiter.update(input.copy(receivedAtMs = now))
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
    }

    @Test
    fun `partial limit withdraws target and source gap cannot bypass recovery restriction`() {
        var now = 0L
        val limiter = createLimiter({ now })
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
        limiter.update(input.copy(receivedAtMs = now))
        now = 3000
        assertThat(limiter.update(input.copy(receivedAtMs = now)).outputLimit).isEqualTo(70)
        now = 4000
        assertThat(limiter.update(input.copy(rawLimit = 0, correctedLimit = 0,
            receivedAtMs = now)).outputLimit).isEqualTo(0)
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        now = 5000
        val mainline = input.copy(receivedAtMs = now, tbtText = "主路")
        assertThat(limiter.update(mainline).outputLimit).isEqualTo(70)
        now = 8000
        assertThat(limiter.update(mainline.copy(receivedAtMs = now)).outputLimit).isEqualTo(80)
        now = 20000
        limiter.update(mainline.copy(receivedAtMs = now))
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
    }

    @Test
    fun `completed recovery releases its timeout only after matching route feedback`() {
        var now = 0L
        val limiter = createLimiter({ now })
        val input = rampInput(rawLimit = 80, correctedLimit = 80, remainingDistanceMeters = 50,
            currentOutputLimit = 60, tbtText = "进入主路").copy(routeGeneration = 2)
        for (index in 0..2) {
            now = index * 3000L
            limiter.update(input.copy(receivedAtMs = now))
        }
        limiter.completeRecovery(1)
        assertThat(limiter.automaticCruiseCap()).isEqualTo(80)
        limiter.completeRecovery(2)
        now = 100000
        assertThat(limiter.automaticCruiseCap()).isNull()
    }

    @Test
    fun `curve hysteresis cannot confirm from a short straight burst`() {
        var now = 0L
        val limiter = createLimiter({ now }, curveEnabled = true)
        val input = rampInput(remainingDistanceMeters = 100, currentOutputLimit = 60, tbtText = "进入主路")
        limiter.update(input.copy(receivedAtMs = now))
        for (index in 1..24) {
            now += 250
            limiter.updateCurve(curveTelemetry(now, if (index <= 3) 0f else 0.00075f))
            limiter.update(input.copy(receivedAtMs = now))
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
        repeat(24) {
            now += 250
            limiter.updateCurve(curveTelemetry(now))
            limiter.update(input.copy(receivedAtMs = now))
        }
        assertThat(limiter.automaticCruiseCap()).isEqualTo(80)
        now += 250
        limiter.updateCurve(curveTelemetry(now, 0.00075f))
        assertThat(limiter.automaticCruiseCap()).isEqualTo(80)
        now += 250
        limiter.updateCurve(curveTelemetry(now, 0.002f))
        assertThat(limiter.automaticCruiseCap()).isEqualTo(0)
    }

    private fun curveTelemetry(receipt: Long, curvature: Float = 0f): CompactTelemetry =
        CompactTelemetry(mapOf(
            "controlsState" to CompactServiceSample(
                CompactFrame("controlsState", 1, mapOf("curvature" to curvature)), receipt, receipt + 500),
            "carState" to CompactServiceSample(
                CompactFrame("carState", 1, mapOf("vEgo" to 20f)), receipt, receipt + 500),
        ), receipt)
}
