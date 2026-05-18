package com.example.carrotamap

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.example.carrotamap.core.AppAnalytics
import com.example.carrotamap.core.ErrorReporterInstance
import com.example.carrotamap.core.LocalErrorReporter
import com.amap.api.maps.MapsInitializer
import com.example.carrotamap.di.appModule
import com.example.carrotamap.ui.components.hasPrivacyConsent
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.maplibre.android.MapLibre
import com.example.carrotamap.TencentNavSdkBootstrap

/**
 * CarrotMap 应用程序类
 * 
 * 职责：
 * 1. 初始化 Koin 依赖注入框架
 * 2. 初始化全局配置（错误上报、日志等）
 * 3. 应用级别的生命周期管理
 * 
 * 注意：这是可选的升级路径，不影响现有功能
 * 旧代码可以继续使用直接实例化的方式
 */
class CarrotApplication : Application() {
    
    companion object {
        private const val TAG = "CarrotApplication"
        
        /**
         * Feature Flag: 是否启用新架构
         * 设置为 false 时，Koin 仅初始化但不强制使用
         */
        const val USE_DI_ARCHITECTURE = false
    }
    
    override fun onCreate() {
        super.onCreate()
        // MapLibre 单例尽早初始化，保证瓦片 HTTP 层能正确解析包名版 User-Agent，避免首屏地图请求异常
        MapLibre.getInstance(this)

        Log.i(TAG, "🚀 CarrotApplication 初始化...")
        
        // 🔧 修复验证：启用StrictMode（仅Debug版本）
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
        
        // 🔧 修复：迁移旧的Float格式坐标到高精度String格式
        migrateCoordinates()
        
        // 初始化 Timber 日志系统
        initializeTimber()
        
        // 初始化错误上报
        initializeErrorReporting()
        
        // 初始化匿名使用分析
        initializeAnalytics()
        
        // 初始化 Koin 依赖注入（可选）
        if (USE_DI_ARCHITECTURE) {
            initializeKoin()
        } else {
            Log.i(TAG, "⚠️ 依赖注入未启用（USE_DI_ARCHITECTURE = false）")
            Log.i(TAG, "   旧代码路径继续使用，新架构作为可选项")
        }
        
        // 高德隐私合规（官方要求在调用地图/导航 SDK 前调用）
        try {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        } catch (e: Exception) {
            Log.w(TAG, "高德隐私合规初始化失败: ${e.message}")
        }

        // 腾讯导航 SDK：须在使用 NavigatorZygote 前 init + start（见 TencentNavSdkBootstrap）
        if (hasPrivacyConsent(this)) {
            TencentNavSdkBootstrap.ensureInitialized(this)
        }

        Log.i(TAG, "✅ CarrotApplication 初始化完成")
    }
    
    /**
     * 🔧 修复验证：启用StrictMode检测潜在问题
     * 仅在Debug版本启用，不影响Release性能
     */
    private fun enableStrictMode() {
        try {
            // 线程策略：检测主线程上的耗时操作
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()      // 检测磁盘读取
                    .detectDiskWrites()     // 检测磁盘写入
                    .detectNetwork()        // 检测网络操作
                    .detectCustomSlowCalls() // 检测自定义慢调用
                    .penaltyLog()           // 输出到日志
                    .build()
            )
            
            // VM策略：检测内存泄漏和资源未关闭
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()    // 检测SQLite泄漏
                    .detectLeakedClosableObjects()   // 检测未关闭的资源
                    .detectActivityLeaks()           // 检测Activity泄漏
                    .detectLeakedRegistrationObjects() // 检测未注销的监听器
                    .penaltyLog()                    // 输出到日志
                    .build()
            )
            
            Log.i(TAG, "✅ StrictMode已启用（Debug模式）")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ StrictMode启用失败: ${e.message}")
        }
    }
    

    
    /**
     * 初始化 Timber 日志系统
     * Debug 版本输出详细日志，Release 版本只记录错误
     */
    private fun initializeTimber() {
        try {
            // 移除所有已有的 Tree
            timber.log.Timber.uprootAll()
            
            // Debug 版本：输出所有日志
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
            
            // 也可以添加自定义的 Release Tree
            // if (!BuildConfig.DEBUG) {
            //     Timber.plant(CrashReportingTree())
            // }
            
            timber.log.Timber.d("✅ Timber 日志系统初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Timber 初始化失败", e)
        }
    }
    
    /**
     * 初始化错误上报机制
     * 使用本地日志记录
     */
    private fun initializeErrorReporting() {
        try {
            ErrorReporterInstance.setReporter(LocalErrorReporter())
            Log.i(TAG, "✅ 错误上报器已初始化")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 错误上报器初始化失败", e)
        }
    }
    
    /**
     * 🔧 修复：迁移旧的Float格式坐标到高精度String格式
     * 
     * 问题：Float精度约7位有效数字，GPS坐标需要8位小数（厘米级）
     * 解决：一次性迁移所有Float坐标到String格式（Double精度）
     */
    private fun migrateCoordinates() {
        try {
            // 迁移地址坐标（一键回家/去公司）
            com.example.carrotamap.utils.CoordinatePreferences.migrateFloatCoordinates(
                context = this,
                prefsName = "map_addresses",
                keys = listOf("home_lat", "home_lon", "company_lat", "company_lon")
            )
            
            // 迁移虚拟定位点坐标
            com.example.carrotamap.utils.CoordinatePreferences.migrateFloatCoordinates(
                context = this,
                prefsName = "CarrotAmap",
                keys = listOf("vpPosPointLat", "vpPosPointLon")
            )
            
            com.example.carrotamap.utils.CoordinatePreferences.migrateFloatCoordinates(
                context = this,
                prefsName = "device_prefs",
                keys = listOf("vpPosPointLat", "vpPosPointLon")
            )
            
            Log.i(TAG, "✅ GPS坐标精度升级完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ GPS坐标迁移失败（不影响功能）", e)
        }
    }
    
    /**
     * 初始化匿名使用分析
     */
    private fun initializeAnalytics() {
        try {
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            AppAnalytics.init(this, deviceId)
            Log.i(TAG, "✅ 匿名分析已初始化")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 匿名分析初始化失败（不影响功能）: ${e.message}")
        }
    }

    /**
     * 初始化 Koin 依赖注入框架
     */
    private fun initializeKoin() {
        try {
            startKoin {
                // 日志级别（可以通过配置控制）
                androidLogger(Level.INFO)  // Release 版本可设为 Level.NONE
                
                // Android Context
                androidContext(this@CarrotApplication)
                
                // 加载模块
                modules(appModule)
            }
            
            Log.i(TAG, "✅ Koin 依赖注入初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Koin 初始化失败", e)
            // 不抛出异常，让应用继续运行（降级到旧架构）
        }
    }
}
