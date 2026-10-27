@file:Suppress("FunctionNaming")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.runtime.Composable
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot

@Composable
internal fun NavigationAndLimitsDetails(snapshot: DashboardSnapshot) {
    DashboardPanelGroup(
        snapshot,
        "导航与限速",
        listOf("result", "navigation", "navi-events", "navi-signal"),
    )
}
