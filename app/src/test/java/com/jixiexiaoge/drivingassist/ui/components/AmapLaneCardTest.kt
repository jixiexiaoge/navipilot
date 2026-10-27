package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.R
import org.junit.Test

class AmapLaneCardTest {
    @Test
    fun `every referenced image code has a graphic without expanding route advice`() {
        val codes = (0..48).toList() + listOf(56, 62, 71, 85, 88, 89)
        codes.forEach { assertThat(laneIconShape("$it")).isNotNull() }
        assertThat(laneIconShape("999")).isNull()
        assertThat(laneIconShape("")).isNull()
    }

    @Test
    fun `white aliases and highlighted combinations preserve all visual branches`() {
        (0..12).forEach { assertThat(laneIconShape("${it + 15}")).isEqualTo(laneIconShape("$it")) }
        assertThat(laneIconShape("30")).isEqualTo(LaneIconShape("SL", "S"))
        assertThat(laneIconShape("31")).isEqualTo(LaneIconShape("SL", "L"))
        assertThat(laneIconShape("71")).isEqualTo(LaneIconShape("SLU", "S"))
        assertThat(laneIconShape("85")?.special).isEqualTo("潮汐")
        assertThat(laneIconShape("88")?.special).isEqualTo("专用")
        assertThat(laneIconShape("89")?.special).isEqualTo("未划线")
    }

    /**
     * 移植自高德的素材必须覆盖全部 13 种基础方向码，否则车道卡会出现空格子。
     * 同时校验「别名」与「变体」也都能落到素材上。
     */
    @Test
    fun `all basic lane codes resolve to an original amap asset`() {
        (0..12).forEach { code ->
            assertThat(laneCardAsset("$code")).isNotNull()
            // 15..27 是高德另一套等价编码，必须落到同一张图
            assertThat(laneCardAsset("${code + 15}")).isEqualTo(laneCardAsset("$code"))
        }
        (30..48).forEach { assertThat(laneCardAsset("$it")).isNotNull() }
        assertThat(laneCardAsset("56")).isNotNull()
        assertThat(laneCardAsset("71")).isNotNull()
    }

    @Test
    fun `each direction keeps its own graphic and special lanes stay special`() {
        // 直行 / 直行左转 / 直行右转 / 左转右转 / 左直右 必须是不同的图
        val distinct = listOf(
            laneCardAsset("0"), laneCardAsset("2"), laneCardAsset("4"),
            laneCardAsset("6"), laneCardAsset("7"),
        )
        assertThat(distinct.toSet()).hasSize(5)
        assertThat(laneCardAsset("85")).isEqualTo(R.drawable.navistate_landfront_150)
        assertThat(laneCardAsset("88")).isEqualTo(R.drawable.navistate_landfront_15)
        // 未划线 / 可变没有对应素材，返回 null（宁可不画，也不编造方向）
        assertThat(laneCardAsset("89")).isNull()
        assertThat(laneCardAsset("62")).isNull()
        assertThat(laneCardAsset("")).isNull()
        assertThat(laneCardAsset("999")).isNull()
    }

    /** 文字别名（某些车端直接下发英文串）也要能出图 */
    @Test
    fun `textual direction aliases resolve too`() {
        listOf(
            "straight", "left", "right", "straight_left", "straight_right",
            "uturn_left", "uturn_right", "left_right", "straight_left_right",
            "straight_uturn_left", "straight_uturn_right", "left_uturn_left",
            "right_uturn_right",
        ).forEach { assertThat(laneCardAsset(it)).isNotNull() }
    }

    @Test
    fun `speed warning uses actual values and never considers missing data safe`() {
        assertThat(exceedsDisplayedLimit(80.0, 80)).isFalse()
        assertThat(exceedsDisplayedLimit(80.1, 80)).isTrue()
        assertThat(exceedsDisplayedLimit(0.0, 80)).isFalse()
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 301.0).forEach {
            assertThat(exceedsDisplayedLimit(it, 80)).isNull()
        }
        listOf(null, 0, 250).forEach { assertThat(exceedsDisplayedLimit(80.0, it)).isNull() }
    }
}
