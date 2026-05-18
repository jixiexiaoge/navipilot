package com.example.navipilot.di

import android.content.Context
import com.example.navipilot.NetworkManager
import com.example.navipilot.CarrotManNetworkClient
import com.example.navipilot.AmapBroadcastManager
import com.example.navipilot.AutoOvertakeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin 依赖注入模块定义
 * 
 * 定义应用中各个组件的依赖关系和生命周期
 * 
 * 作用域说明：
 * - single { }: 单例，整个应用生命周期内只有一个实例
 * - factory { }: 工厂，每次请求都创建新实例
 * - scoped { }: 作用域，在特定作用域内是单例
 */
val appModule = module {
    
    // ===============================
    // 核心组件
    // ===============================
    
    /**
     * 应用级协程作用域
     * 使用 SupervisorJob 确保子协程失败不影响其他协程
     */
    single(qualifier = org.koin.core.qualifier.named("AppScope")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    
    // ===============================
    // 网络组件（暂时注释以确保编译通过）
    // ===============================
    
    /**
     * CarrotMan 网络客户端
     * 单例模式，管理与 Comma3 设备的 UDP 通信
     */
    // single {
    //     CarrotManNetworkClient(
    //         context = androidContext()
    //     )
    // }
    
    /**
     * CarrotManFields 状态
     * 单例模式，用于存储和共享导航数据
     */
    // single {
    //     androidx.compose.runtime.mutableStateOf(CarrotManFields())
    // }
    
    /**
     * 网络管理器
     * 单例模式，协调所有网络相关操作
     */
    // single {
    //     NetworkManager(
    //         context = androidContext(),
    //         carrotManFields = get()
    //     )
    // }
    
    // ===============================
    // 业务组件（暂时注释，避免编译错误）
    // ===============================
    
    // AmapBroadcastManager 和 AutoOvertakeManager 的构造函数签名需要确认后再添加
    
    /**
     * 高德地图广播管理器
     * 单例模式，处理高德地图的广播数据
     */
    // single {
    //     AmapBroadcastManager(
    //         context = androidContext()
    //     )
    // }
    
    /**
     * 自动超车管理器
     * 单例模式，管理自动超车逻辑
     */
    // single {
    //     AutoOvertakeManager(
    //         context = androidContext(),
    //         networkManager = get()
    //     )
    // }
    
    // ===============================
    // 数据组件
    // ===============================
    
    single { 
        com.example.navipilot.data.PreferenceRepository(androidContext())
    }
}

/**
 * 使用示例（在 Activity 或其他组件中）：
 * 
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     // 方式1：使用 by inject() 懒加载
 *     private val networkManager: NetworkManager by inject()
 *     
 *     // 方式2：使用 get() 立即获取
 *     private val amapManager = get<AmapBroadcastManager>()
 *     
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         // networkManager 和 amapManager 已经通过 Koin 注入
 *     }
 * }
 * ```
 * 
 * 或者继续使用旧方式（向后兼容）：
 * ```kotlin
 * val networkManager = NetworkManager(context, client, scope)
 * ```
 */
