package com.example.carrotamap.core

/**
 * 统一的结果封装类
 * 用于替代 try-catch 进行类型安全的错误处理
 * 
 * 使用示例:
 * ```kotlin
 * suspend fun fetchData(): Result<Data> = runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
 *     apiService.getData()
 * }
 * 
 * when (val result = fetchData()) {
 *     is Result.Success -> println(result.data)
 *     is Result.Error -> println(result.message)
 *     is Result.Loading -> showLoading()
 * }
 * ```
 */
sealed class Result<out T> {
    /**
     * 成功结果
     * @param data 返回的数据
     */
    data class Success<T>(val data: T) : Result<T>()
    
    /**
     * 错误结果
     * @param exception 异常对象
     * @param code 错误代码
     * @param message 错误消息
     */
    data class Error(
        val exception: Exception,
        val code: ErrorCode,
        val message: String = exception.message ?: "Unknown error"
    ) : Result<Nothing>()
    
    /**
     * 加载中状态
     */
    object Loading : Result<Nothing>()
}

/**
 * 错误代码枚举
 * 用于分类和识别不同类型的错误
 */
enum class ErrorCode(val code: Int, val description: String) {
    // 网络错误 (1000-1999)
    NETWORK_TIMEOUT(1001, "网络超时"),
    NETWORK_CONNECTION_FAILED(1002, "网络连接失败"),
    NETWORK_NO_INTERNET(1003, "无网络连接"),
    NETWORK_UNKNOWN(1099, "未知网络错误"),
    
    // 数据错误 (2000-2999)
    DATA_PARSE_ERROR(2001, "数据解析失败"),
    DATA_INVALID(2002, "数据无效"),
    DATA_NOT_FOUND(2003, "数据不存在"),
    
    // GPS/定位错误 (3000-3999)
    GPS_PERMISSION_DENIED(3001, "GPS权限被拒绝"),
    GPS_NOT_AVAILABLE(3002, "GPS不可用"),
    GPS_ACCURACY_LOW(3003, "GPS精度过低"),
    
    // 业务错误 (4000-4999)
    AMAP_BROADCAST_ERROR(4001, "高德广播接收失败"),
    OPENPILOT_CONNECTION_ERROR(4002, "OpenPilot连接失败"),
    XIAOGE_CONNECTION_ERROR(4003, "小鸽数据连接失败"),
    OVERTAKE_CONDITION_NOT_MET(4004, "超车条件不满足"),
    
    // 系统错误 (5000-5999)
    PERMISSION_DENIED(5001, "权限被拒绝"),
    SERVICE_NOT_AVAILABLE(5002, "服务不可用"),
    UNKNOWN_ERROR(5000, "未知错误")
}

/**
 * 扩展函数：安全执行代码块
 * 将可能抛出异常的代码转换为 Result 类型
 * 
 * @param errorCode 默认错误代码
 * @param block 要执行的代码块
 * @return Result 封装的结果
 */
inline fun <T> runSafely(
    errorCode: ErrorCode = ErrorCode.UNKNOWN_ERROR,
    block: () -> T
): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e, errorCode, e.message ?: errorCode.description)
    }
}

/**
 * 扩展函数：映射 Result 的成功值
 */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> {
    return when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
        is Result.Loading -> this
    }
}

/**
 * 扩展函数：在成功时执行操作
 */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) {
        action(data)
    }
    return this
}

/**
 * 扩展函数：在失败时执行操作
 */
inline fun <T> Result<T>.onError(action: (Result.Error) -> Unit): Result<T> {
    if (this is Result.Error) {
        action(this)
    }
    return this
}

/**
 * 扩展函数：获取数据或默认值
 */
fun <T> Result<T>.getOrDefault(default: T): T {
    return when (this) {
        is Result.Success -> data
        else -> default
    }
}

/**
 * 扩展函数：获取数据或null
 */
fun <T> Result<T>.getOrNull(): T? {
    return when (this) {
        is Result.Success -> data
        else -> null
    }
}
