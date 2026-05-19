package com.example.navipilot.ui.utils

import java.util.Locale

/**
 * Returns localized text based on current system locale.
 *
 * The project frequently inlines CN/EN strings. This helper keeps call sites small and consistent.
 */
fun localized(chinese: String, english: String): String {
    val language = Locale.getDefault().language.lowercase(Locale.ROOT)
    val useChinese = language.startsWith("zh")
    return when {
        useChinese && chinese.isNotBlank() -> chinese
        !useChinese && english.isNotBlank() -> english
        chinese.isNotBlank() -> chinese
        else -> english
    }
}

