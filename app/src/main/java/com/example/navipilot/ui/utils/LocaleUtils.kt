package com.example.navipilot.ui.utils

import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * 判断当前系统语言是否为中文（简体或繁体）
 * 同时检查 Locale.getDefault() 和系统 Configuration，确保准确
 */
fun isChinese(): Boolean {
    // 方法1: 检查 Locale.getDefault()
    val defaultLocale = Locale.getDefault()
    if (defaultLocale.language == "zh") return true

    // 方法2: 检查系统 Configuration 的 locale（更可靠）
    val config = Resources.getSystem().configuration
    val configLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.locales[0]
    } else {
        @Suppress("DEPRECATION")
        config.locale
    }
    return configLocale.language == "zh"
}

/**
 * 根据语言环境返回中文或英文文本
 */
fun localized(zh: String, en: String): String {
    return if (isChinese()) zh else en
}
