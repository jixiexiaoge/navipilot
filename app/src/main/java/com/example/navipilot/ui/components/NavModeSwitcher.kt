package com.example.navipilot.ui.components

/**
 * 地图/导航源（弹窗单选）。应用内 OSM 地图仍用 [OSM] 与 [activeNavMode] 同步，未必出现在弹窗列表中。
 */
enum class NavMode {
    /** 高德地图车机版（com.autonavi.amapauto，广播联动） */
    AMAP_AUTO,
    /** 腾讯导航 */
    TMAP,
    /** 高德导航（手机 SDK 嵌入） */
    AMAP_MOBILE,
    /** 应用内 OpenStreetMap 地图 */
    OSM,
    /** Google 导航 */
    GOOGLE,
}
