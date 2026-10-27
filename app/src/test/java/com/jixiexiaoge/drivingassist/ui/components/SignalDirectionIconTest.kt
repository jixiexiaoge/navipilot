package com.jixiexiaoge.drivingassist.ui.components

import com.jixiexiaoge.drivingassist.AmapIconSource
import com.jixiexiaoge.drivingassist.AmapManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 红绿灯胶囊方向码 → 导航动作 ICON 的映射。
 *
 * 胶囊箭头在 8.0.27 从手绘几何改为复用 [AmapManeuverIcon]：
 * 原来的手绘版头部过大（头宽占画布 56%），左/右转变实心三角、掉头两翼与横杆重合呈 M 形。
 * 这里锁两件事：① 方向码映射正确；② 映射出的 ICON 在动作表里真实存在
 * （否则 `maneuverGraphic` 会退化成 Unknown，画成圆圈感叹号）。
 */
class SignalDirectionIconTest {

    @Test
    fun trafficLightDirectionMapsToMatchingManeuverIcon() {
        assertEquals(2, signalDirectionIcon(1))   // 左转
        assertEquals(3, signalDirectionIcon(2))   // 右转
        assertEquals(8, signalDirectionIcon(3))   // 左掉头
        assertEquals(1, signalDirectionIcon(4))   // 直行
        assertEquals(19, signalDirectionIcon(5))  // 右掉头
    }

    @Test
    fun everyMappedIconResolvesToAKnownGraphic() {
        for (direction in 1..5) {
            val icon = signalDirectionIcon(direction)
            assertNotNull("dir=$direction 的 ICON $icon 不在动作表里", AmapManeuver.info(icon, AmapIconSource.AUTO_BROADCAST))
            val expected = when (direction) {
                1 -> ManeuverGraphic.Left
                2 -> ManeuverGraphic.Right
                3 -> ManeuverGraphic.ULeft
                4 -> ManeuverGraphic.Straight
                else -> ManeuverGraphic.URight
            }
            assertEquals("dir=$direction", expected, maneuverGraphic(icon))
        }
    }
}
