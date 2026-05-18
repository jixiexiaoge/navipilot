package com.example.navipilot

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.tencent.navix.api.NavigatorConfig
import com.tencent.navix.api.NavigatorZygote
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/**
 * 腾讯导航 SDK 必须在任意 [NavigatorZygote.navigator] 调用之前完成 init + start，
 * 且 [NavigatorConfig.setUserAgreedPrivacy] 为 true，否则会报
 * 「NavigatorZygote must init and start first」/ userAgreedPrivacy = false。
 *
 * 官方文档：https://lbs.qq.com/mobile/AndroidNavigationX/guide/quick_integrate
 */
object TencentNavSdkBootstrap {
    private const val TAG = "TencentNavSdkBootstrap"

    @Volatile
    private var started: Boolean = false

    /**
     * 幂等：已初始化则直接返回。应在用户已同意应用隐私后调用（与 [hasPrivacyConsent] 一致）。
     */
    @Synchronized
    fun ensureInitialized(application: Application) {
        if (started) return
        try {
            val key = application.packageManager
                .getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString("TencentMapSDK")
                .orEmpty()
            if (key.isBlank()) {
                Log.w(TAG, "AndroidManifest 中 TencentMapSDK 为空，跳过腾讯导航初始化")
                return
            }
            val deviceId = Settings.Secure.getString(
                application.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            // 地图基础库须先于导航 SDK 启动，否则鉴权链路里 Beacon 会报 AppKey == null（官方隐私页要求）
            TencentMapInitializer.setAgreePrivacy(application, true)
            TencentMapInitializer.start(application)

            val config = NavigatorConfig.builder()
                .setUserAgreedPrivacy(true)
                .setDeviceId(deviceId)
                .setDeviceModel(Build.MODEL)
                .setDeveloperKey(key)
                .build()

            NavigatorZygote.with(application).init(config)
            NavigatorZygote.with(application).start()
            started = true
            Log.i(TAG, "NavigatorZygote init + start 成功")
        } catch (e: Exception) {
            Log.e(TAG, "NavigatorZygote 初始化失败: ${e.message}", e)
        }
    }
}
