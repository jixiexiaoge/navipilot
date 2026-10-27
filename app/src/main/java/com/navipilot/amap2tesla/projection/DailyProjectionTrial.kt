package com.navipilot.amap2tesla.projection

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

internal object DailyProjectionTrial {
    const val DAILY_LIMIT_MILLIS = 45L * 60L * 1000L

    private const val PREFERENCES = "daily_projection_trial"
    private const val KEY_DATE = "date"
    private const val KEY_USED_MILLIS = "used_millis"
    private const val KEY_SESSION_STARTED_AT = "session_started_at"

    fun remainingMillis(context: Context): Long {
        val preferences = preferences(context)
        resetIfNeeded(preferences)
        val usedMillis = preferences.getLong(KEY_USED_MILLIS, 0L)
        val sessionStartedAt = preferences.getLong(KEY_SESSION_STARTED_AT, 0L)
        val activeMillis = if (sessionStartedAt > 0L) {
            (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        return (DAILY_LIMIT_MILLIS - usedMillis - activeMillis).coerceAtLeast(0L)
    }

    fun begin(context: Context): Long {
        val preferences = preferences(context)
        resetIfNeeded(preferences)
        finishActiveSession(preferences)
        val availableMillis = minOf(remainingMillis(context), millisUntilTomorrow())
        if (availableMillis > 0L) {
            preferences.edit()
                .putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
                .apply()
        }
        return availableMillis
    }

    fun finish(context: Context) {
        val preferences = preferences(context)
        resetIfNeeded(preferences)
        finishActiveSession(preferences)
    }

    private fun finishActiveSession(preferences: android.content.SharedPreferences) {
        val startedAt = preferences.getLong(KEY_SESSION_STARTED_AT, 0L)
        if (startedAt <= 0L) return

        val elapsedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val usedMillis = preferences.getLong(KEY_USED_MILLIS, 0L)
        preferences.edit()
            .putLong(KEY_USED_MILLIS, (usedMillis + elapsedMillis).coerceAtMost(DAILY_LIMIT_MILLIS))
            .remove(KEY_SESSION_STARTED_AT)
            .apply()
    }

    private fun resetIfNeeded(preferences: android.content.SharedPreferences) {
        val today = LocalDate.now().toString()
        if (preferences.getString(KEY_DATE, null) != today) {
            preferences.edit()
                .putString(KEY_DATE, today)
                .putLong(KEY_USED_MILLIS, 0L)
                .remove(KEY_SESSION_STARTED_AT)
                .apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun millisUntilTomorrow(): Long {
        val nextMidnight = LocalDate.now()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return (nextMidnight - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}
