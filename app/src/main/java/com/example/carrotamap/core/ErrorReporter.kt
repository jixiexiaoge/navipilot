package com.example.carrotamap.core

import android.util.Log

/**
 * 错误上报接口
 * 用于将错误上报到监控平台（如 Firebase Crashlytics）
 * 
 * 当前实现为本地日志记录，后续可替换为真实的监控服务
 */
interface ErrorReporter {
    /**
     * 上报异常
     */
    fun reportException(exception: Exception, tag: String = "ErrorReporter")
    
    /**
     * 上报 Result.Error
     */
    fun reportError(error: Result.Error, tag: String = "ErrorReporter")
    
    /**
     * 记录自定义日志
     */
    fun log(message: String, tag: String = "ErrorReporter")
    
    /**
     * 设置用户标识
     */
    fun setUserId(userId: String)
    
    /**
     * 设置自定义键值对
     */
    fun setCustomKey(key: String, value: Any)
}

/**
 * 默认的错误上报实现
 * 使用 Android Log 进行本地记录
 * 
 * TODO: 后续集成 Firebase Crashlytics 后，替换此实现
 */
class LocalErrorReporter : ErrorReporter {
    private val customKeys = mutableMapOf<String, Any>()
    private var userId: String? = null
    
    override fun reportException(exception: Exception, tag: String) {
        Log.e(tag, "Exception reported: ${exception.message}", exception)
        logCustomKeys(tag)
    }
    
    override fun reportError(error: Result.Error, tag: String) {
        Log.e(tag, """
            Error reported:
            Code: ${error.code.code} - ${error.code.description}
            Message: ${error.message}
            Exception: ${error.exception.javaClass.simpleName}
        """.trimIndent(), error.exception)
        logCustomKeys(tag)
    }
    
    override fun log(message: String, tag: String) {
        Log.d(tag, message)
    }
    
    override fun setUserId(userId: String) {
        this.userId = userId
        Log.d("ErrorReporter", "User ID set: $userId")
    }
    
    override fun setCustomKey(key: String, value: Any) {
        customKeys[key] = value
    }
    
    private fun logCustomKeys(tag: String) {
        if (customKeys.isNotEmpty() || userId != null) {
            Log.d(tag, "Context: userId=$userId, customKeys=$customKeys")
        }
    }
}

/**
 * 全局错误上报器实例
 * 
 * 使用方式：
 * ```kotlin
 * ErrorReporterInstance.reporter.reportException(exception)
 * ```
 */
object ErrorReporterInstance {
    var reporter: ErrorReporter = LocalErrorReporter()
        private set
    
    /**
     * 设置自定义的错误上报器实现
     * 例如：集成 Firebase Crashlytics 后调用
     */
    fun setReporter(newReporter: ErrorReporter) {
        reporter = newReporter
    }
}
