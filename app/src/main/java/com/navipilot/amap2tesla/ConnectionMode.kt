package com.navipilot.amap2tesla

import android.content.Context

enum class ConnectionMode {
    LAN,
    TESLA;

    companion object {
        private const val PREFS = "amap_projection_connection"
        private const val KEY_MODE = "mode"

        fun load(context: Context): ConnectionMode {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, LAN.name)
            return entries.firstOrNull { it.name == saved } ?: LAN
        }

        fun save(context: Context, mode: ConnectionMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name)
                .apply()
        }
    }
}
