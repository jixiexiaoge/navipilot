package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarrotTelemetryLabelsTest {
    @Test
    fun `navigation speed sources have distinct Chinese meanings`() {
        // Given / When / Then
        assertThat(speedReason("cam")).isEqualTo("电子眼约束")
        assertThat(speedReason("atc")).isEqualTo("当前转弯约束")
        assertThat(speedReason("atc2")).isEqualTo("下一转弯约束")
        assertThat(speedReason("route")).isEqualTo("路线曲率约束")
        assertThat(speedReason("gas")).isEqualTo("驾驶员加速覆盖")
        assertThat(speedReason("hda_section")).isEqualTo("车辆导航 · 区间测速约束")
        assertThat(speedReason("cam:v")).isEqualTo("车辆导航 · 电子眼约束")
        assertThat(speedReason("section:n")).isEqualTo("区间测速约束")
    }

    @Test
    fun `unknown reasons and event types remain explicit rather than guessing`() {
        // Given / When / Then
        assertThat(speedReason(null)).isEqualTo("来源未知")
        assertThat(speedReason("future")).isEqualTo("未知来源 future")
        assertThat(speedEventLabel(999)).isEqualTo("未知限速事件 #999")
        assertThat(speedEventLabel(1)).isEqualTo("固定测速")
        assertThat(speedEventLabel(2)).isEqualTo("区间测速起点")
        assertThat(speedEventLabel(3)).isEqualTo("区间测速终点")
        assertThat(speedEventLabel(22)).isEqualTo("减速带")
    }

    @Test
    fun `metric columns respect their content width and enlarged fonts`() {
        // Given / When / Then
        assertThat(telemetryMetricColumns(279.99f, 1f)).isEqualTo(1)
        assertThat(telemetryMetricColumns(280f, 1f)).isEqualTo(2)
        assertThat(telemetryMetricColumns(336f, 1.6f)).isEqualTo(1)
        assertThat(telemetryMetricColumns(448f, 1.6f)).isEqualTo(2)
        assertThat(telemetryMetricColumns(260f, 0.8f)).isEqualTo(1)
        assertThat(telemetryMetricColumns(419.99f, 1f)).isEqualTo(2)
        assertThat(telemetryMetricColumns(420f, 1f)).isEqualTo(3)
        assertThat(telemetryMetricColumns(500f, 1f)).isEqualTo(3)
        assertThat(telemetryMetricColumns(500f, 1.5f)).isEqualTo(2)
        assertThat(telemetryMetricColumns(500f, 2f)).isEqualTo(1)
        assertThat(telemetryMetricColumns(672f, 1.6f)).isEqualTo(3)
    }
}
