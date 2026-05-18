package com.example.carrotamap

import com.example.carrotamap.core.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Result 封装类的单元测试
 * 验证错误处理框架的正确性
 */
class ResultTest {
    
    @Test
    fun `Success应该包含正确的数据`() {
        // Given
        val data = "test data"
        
        // When
        val result = Result.Success(data)
        
        // Then
        assertThat(result.data).isEqualTo(data)
    }
    
    @Test
    fun `Error应该包含异常和错误代码`() {
        // Given
        val exception = Exception("test error")
        val code = ErrorCode.NETWORK_TIMEOUT
        
        // When
        val result = Result.Error(exception, code)
        
        // Then
        assertThat(result.exception).isEqualTo(exception)
        assertThat(result.code).isEqualTo(code)
        assertThat(result.message).contains("test error")
    }
    
    @Test
    fun `runSafely应该捕获异常并返回Error`() {
        // When
        val result = runSafely(ErrorCode.DATA_PARSE_ERROR) {
            throw IllegalArgumentException("Invalid data")
        }
        
        // Then
        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.code).isEqualTo(ErrorCode.DATA_PARSE_ERROR)
        assertThat(error.exception).isInstanceOf(IllegalArgumentException::class.java)
    }
    
    @Test
    fun `runSafely应该在成功时返回Success`() {
        // When
        val result = runSafely {
            42
        }
        
        // Then
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo(42)
    }
    
    @Test
    fun `map应该转换Success的值`() {
        // Given
        val result = Result.Success(10)
        
        // When
        val mapped = result.map { it * 2 }
        
        // Then
        assertThat(mapped).isInstanceOf(Result.Success::class.java)
        assertThat((mapped as Result.Success).data).isEqualTo(20)
    }
    
    @Test
    fun `map不应该转换Error`() {
        // Given
        val error = Result.Error(Exception(), ErrorCode.UNKNOWN_ERROR)
        
        // When
        val mapped = error.map { it.toString() }
        
        // Then
        assertThat(mapped).isInstanceOf(Result.Error::class.java)
    }
    
    @Test
    fun `getOrDefault应该返回数据或默认值`() {
        // Given
        val success: Result<Int> = Result.Success(42)
        val error: Result<Int> = Result.Error(Exception(), ErrorCode.UNKNOWN_ERROR)
        
        // When & Then
        assertThat(success.getOrDefault(0)).isEqualTo(42)
        assertThat(error.getOrDefault(0)).isEqualTo(0)
    }
    
    @Test
    fun `getOrNull应该返回数据或null`() {
        // Given
        val success: Result<String> = Result.Success("data")
        val error: Result<String> = Result.Error(Exception(), ErrorCode.UNKNOWN_ERROR)
        
        // When & Then
        assertThat(success.getOrNull()).isEqualTo("data")
        assertThat(error.getOrNull()).isNull()
    }
    
    @Test
    fun `onSuccess应该在成功时执行操作`() {
        // Given
        var executed = false
        val result = Result.Success(42)
        
        // When
        result.onSuccess { executed = true }
        
        // Then
        assertThat(executed).isTrue()
    }
    
    @Test
    fun `onSuccess不应该在失败时执行操作`() {
        // Given
        var executed = false
        val result: Result<Int> = Result.Error(Exception(), ErrorCode.UNKNOWN_ERROR)
        
        // When
        result.onSuccess { executed = true }
        
        // Then
        assertThat(executed).isFalse()
    }
    
    @Test
    fun `onError应该在失败时执行操作`() {
        // Given
        var executed = false
        val result: Result<Int> = Result.Error(Exception(), ErrorCode.NETWORK_TIMEOUT)
        
        // When
        result.onError { executed = true }
        
        // Then
        assertThat(executed).isTrue()
    }
}
