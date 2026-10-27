package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import org.json.JSONObject
import org.junit.Test

class NaviSourcePublicationTest : NaviStreamManagerTestFixture() {
    @Test
    fun `partial and malformed route snapshots withdraw rather than reusing shared siblings`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nGoPosDist = 999, nGoPosTime = 999)
        sources.updateRoute(AmapRouteSource(1000, 120, 5000, 1, now))
        manager.update(fields)
        val first = messages.last { it.first == "route" }.third as JSONObject
        assertThat(first.getInt("remain_distance_m")).isEqualTo(1000)
        assertThat(first.getInt("remain_time_sec")).isEqualTo(120)
        now++
        sources.updateRoute(AmapRouteSource(900, null, null, 1, now))
        manager.update(fields)
        assertThat(messages.last { it.first == "route" }.second).isFalse()
        sources.updateRoute(AmapRouteSource(800, 100, null, 2, ++now))
        manager.update(fields)
        assertThat(messages.last { it.first == "route" }.second).isFalse()
        sources.updateRoute(AmapRouteSource(0, 0, null, 1, ++now))
        manager.update(fields)
        val final = messages.last { it.first == "route" }.third as JSONObject
        assertThat(final.getInt("remain_distance_m")).isEqualTo(0)
        assertThat(final.getInt("remain_time_sec")).isEqualTo(0)
        assertThat(final.has("total_distance_m")).isFalse()
    }

    @Test
    fun `inactive map source cannot replace independent phone positioning`() {
        val manager = manager()
        sources.updatePosition(NaviPositionSample(30.0, 120.0, 20.0, 90.0, now, "Map road"), true)
        sources.updatePosition(NaviPositionSample(31.0, 121.0, 30.0, 180.0, now), false)
        manager.update(CarrotManFields(), amapSelected = false)
        val value = messages.last { it.first == "vehicle" }.third as JSONObject
        assertThat(value.getDouble("lat")).isEqualTo(31.0)
        assertThat(value.getString("road_name")).isEmpty()
        assertThat(value.getBoolean("virtual_gps")).isFalse()
    }

    @Test
    fun `map road name belongs to the actual position packet instead of a shared cache`() {
        val manager = manager()
        sources.updatePosition(NaviPositionSample(30.0, 120.0, 20.0, 0.0, now, "Source road"), true)
        manager.update(CarrotManFields(szPosRoadName = "Retained road"))
        assertThat((messages.last { it.first == "vehicle" }.third as JSONObject).getString("road_name"))
            .isEqualTo("Source road")
        now += 1000
        sources.updatePosition(NaviPositionSample(30.0, 120.0, null, null, now), true)
        manager.update(CarrotManFields(szPosRoadName = "Retained road"))
        val position = messages.last { it.first == "vehicle" }.third as JSONObject
        assertThat(position.getString("road_name")).isEmpty()
        assertThat(position.has("speed_kph")).isFalse()
    }

    @Test
    fun `sdi and section never borrow limits or block state from shared fields`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nSdiType = 8, nSdiSpeedLimit = 120, nSdiBlockType = 2,
            nSdiBlockSpeed = 120, nSdiBlockDist = 5000)
        sources.updateSdi(AmapSdiSource(8, 500, 60, 1, now), 1, now)
        sources.updateSection(300.0, null, null, now, speedLimitKph = 70, routeGeneration = 1)
        manager.update(fields)
        val speed = messages.last { it.first == "speed" }.third as JSONObject
        assertThat(speed.getJSONObject("sdi").getInt("speed_limit_kph")).isEqualTo(60)
        assertThat(speed.getJSONObject("sdi").has("block_type")).isFalse()
        assertThat(speed.getJSONObject("section").getInt("speed_limit_kph")).isEqualTo(70)
        assertThat(speed.getJSONObject("section").has("average_kph")).isFalse()
        now++
        sources.updateSdi(null, 1, now)
        manager.update(fields)
        val subset = messages.last { it.first == "speed" }.third as JSONObject
        assertThat(subset.has("sdi")).isFalse()
        assertThat(subset.getJSONObject("section").getDouble("remaining_distance_m")).isEqualTo(300.0)
        assertThat(timestamps.last { it.first == "speed" }.second).isEqualTo(1000)
        now += 2000
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
    }

    @Test
    fun `new control snapshots reject duplicate old receipts and clear on reset source change and rollback`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        sources.updateRoute(AmapRouteSource(1000, 100, null, 1, now))
        sources.updateSdi(AmapSdiSource(8, 500, 60, 1, now), 1, now)
        sources.updateSection(300.0, 40.0, null, now, speedLimitKph = 60, routeGeneration = 1)
        sources.updateRoute(AmapRouteSource(999, 99, null, 1, now))
        sources.updateSdi(null, 1, now - 1)
        sources.updateSection(null, null, null, now, routeGeneration = 1)
        assertThat(sources.snapshot().route!!.remainingDistanceM).isEqualTo(1000)
        assertThat(sources.snapshot().sdi).isNotNull()
        assertThat(sources.snapshot().section).isNotNull()
        manager.update(fields)
        manager.update(fields, amapSelected = false)
        assertThat(sources.snapshot().route).isNull()
        assertThat(sources.snapshot().sdi).isNull()
        assertThat(sources.snapshot().section).isNull()
        now += 10
        sources.updateRoute(AmapRouteSource(900, 90, null, 1, now))
        manager.update(fields)
        now--
        manager.update(fields)
        assertThat(sources.snapshot().route).isNull()
        assertThat(messages.last { it.first == "route" }.second).isFalse()
        sources.resetGuideDisplay(++now)
        assertThat(sources.snapshot().sdi).isNull()
    }
}
