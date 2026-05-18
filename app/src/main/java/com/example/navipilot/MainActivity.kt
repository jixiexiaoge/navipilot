package com.example.navipilot

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.navipilot.ui.theme.NavipilotTheme

/**
 * MainActivity - 应用主入口和协调器
 * 经过重构，现在只负责协调各个模块，具体功能委托给专门的类
 * 
 * 拆分后的架构：
 * - MainActivityCore: 核心业务逻辑和状态管理
 * - MainActivityUI: UI组件和界面逻辑
 * - MainActivityLifecycle: 生命周期管理和初始化流程
 */
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = AppConstants.Logging.MAIN_ACTIVITY_TAG
    }

    // ===============================
    // 核心组件实例
    // ===============================

    private lateinit var core: MainActivityCore
    private lateinit var ui: MainActivityUI
    private lateinit var lifecycleManager: MainActivityLifecycle

    // ===============================
    // Activity生命周期
    // ===============================
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 禁用System.out输出，避免UDP发送时的调试日志
        System.setOut(object : java.io.PrintStream(System.out) {
            override fun println(x: String?) {
                // 过滤掉DatagramSocket相关的调试输出
                if (x?.contains("DatagramSocket") == true && 
                    x.contains("DatagramPacket") && 
                    x.contains("ipAddress")) {
                    return // 不输出这些调试信息
                }
                super.println(x)
            }
        })

        Log.i(TAG, "🚀 MainActivity启动 - 协调器模式")

        // 初始化核心组件
        initializeComponents()
        
        // 设置UI
        setupUI()
        
        // 处理生命周期
        lifecycleManager.onCreate(savedInstanceState)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleManager.onNewIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        lifecycleManager.onPause()
    }

    override fun onResume() {
        super.onResume()
        lifecycleManager.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleManager.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 让 OnBackPressedDispatcher 处理（Compose BackHandler 依赖此机制）
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ===============================
    // 组件初始化
    // ===============================
    
    /**
     * 初始化所有核心组件
     */
    private fun initializeComponents() {
        try {
            Log.i(TAG, "🔧 初始化核心组件...")
            
            // 1. 初始化核心逻辑组件
            core = MainActivityCore(this, this)
            Log.i(TAG, "✅ MainActivityCore初始化完成")
            
            // 2. 初始化UI组件
            ui = MainActivityUI(core)
            Log.i(TAG, "✅ MainActivityUI初始化完成")
            
            // 3. 初始化生命周期管理组件
            lifecycleManager = MainActivityLifecycle(this, core)
            Log.i(TAG, "✅ MainActivityLifecycle初始化完成")
            
            Log.i(TAG, "🎉 所有核心组件初始化完成")
            
                        } catch (e: Exception) {
            Log.e(TAG, "❌ 组件初始化失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 设置用户界面
     */
    private fun setupUI() {
        setContent {
            NavipilotTheme {
                ui.SetupUserInterface()
            }
        }
    }

    // ===============================
    // 公共接口方法（供外部调用）
    // ===============================
    
    /**
     * 获取核心组件实例（供其他模块访问）
     */
    fun getCore(): MainActivityCore = core
    
    /**
     * 获取UI组件实例（供其他模块访问）
     */
    fun getUI(): MainActivityUI = ui
    
    /**
     * 获取生命周期管理组件实例（供其他模块访问）
     */
    fun getLifecycle(): MainActivityLifecycle = lifecycleManager
}