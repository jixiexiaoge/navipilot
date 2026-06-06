package com.example.navipilot.ui.components

/**
 * Navigation provider selection shown in the main UI.
 *
 * Note: This is the app-level mode (Amap head unit vs Tencent vs embedded, etc),
 * not the Tencent SDK's `com.tencent.navix.api.model.NavMode`.
 */
enum class NavMode {
    AMAP_AUTO,
    TENCENT,
    GOOGLE,
}
