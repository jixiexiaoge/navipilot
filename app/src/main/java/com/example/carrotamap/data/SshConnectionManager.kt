package com.example.carrotamap.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * SSH 连接状态
 */
enum class SshConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * SSH 连接信息
 */
data class SshConnectionInfo(
    val host: String,
    val port: Int,
    val username: String,
    val privateKeyPath: String
)

/**
 * SSH 连接管理器
 * 简化版本：管理连接状态，不包含实际 SSH 连接实现
 * 实际连接需要配置 SSH 库和文件选择器
 */
class SshConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "SshConnectionManager"
    }

    private val _connectionState = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    private val _connectionInfo = MutableStateFlow<SshConnectionInfo?>(null)
    val connectionInfo: StateFlow<SshConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // SSHJ SSH 客户端
    private var sshClient: SSHClient? = null

    val isConnected: Boolean
        get() = _connectionState.value == SshConnectionState.CONNECTED

    /**
     * 连接到 SSH 服务器
     */
    suspend fun connect(
        host: String,
        port: Int = 22,
        username: String = "comma",
        privateKeyUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = SshConnectionState.CONNECTING
            _errorMessage.value = null
            Log.i(TAG, "开始 SSH 连接: $host:$port")

            // 验证参数
            if (host.isBlank()) {
                throw Exception("IP 地址不能为空")
            }

            // 创建 SSH 客户端
            val ssh = SSHClient()

            // 注册 BouncyCastle 作为安全提供者（解决 X25519 算法问题）
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

            // 跳过主机密钥验证（适合开发/测试）
            ssh.addHostKeyVerifier(PromiscuousVerifier())

            // 连接
            ssh.connect(host, port)

            // 解析私钥 URI 获取 InputStream，并写入临时文件
            val keyInputStream = context.contentResolver.openInputStream(privateKeyUri)
                ?: throw Exception("无法读取私钥文件")

            // 创建临时文件存储私钥
            val tempKeyFile = File(context.cacheDir, "temp_private_key")
            FileOutputStream(tempKeyFile).use { fos ->
                keyInputStream.copyTo(fos)
            }
            keyInputStream.close()

            // 使用 SSHJ 加载私钥文件
            val keys: KeyProvider = ssh.loadKeys(tempKeyFile.absolutePath)
            ssh.authPublickey(username, keys)

            // 删除临时文件
            tempKeyFile.delete()

            // 保存连接
            sshClient = ssh
            _connectionState.value = SshConnectionState.CONNECTED
            _connectionInfo.value = SshConnectionInfo(host, port, username, privateKeyUri.toString())
            Log.i(TAG, "SSH 连接成功: $host")

            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = SshConnectionState.FAILED
            _errorMessage.value = e.message ?: "SSH 连接失败"
            Log.e(TAG, "SSH 连接失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 执行远程命令
     */
    suspend fun execCommand(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        val ssh = sshClient ?: return@withContext Result.failure(Exception("未连接 SSH"))

        try {
            val session: Session = ssh.startSession()
            try {
                val command = session.exec(cmd)
                val output = IOUtils.readFully(command.inputStream).toString()
                command.join(30, TimeUnit.SECONDS)
                val exitStatus = command.exitStatus
                Log.i(TAG, "命令执行完成: $cmd, exitStatus: $exitStatus")

                if (exitStatus == 0) {
                    Result.success(output)
                } else {
                    val errorOutput = IOUtils.readFully(command.errorStream).toString()
                    Result.failure(Exception("命令执行失败 (exit $exitStatus): $errorOutput"))
                }
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "命令执行异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 上传文件到远程服务器 via SCP
     */
    suspend fun uploadFile(localPath: String, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ssh = sshClient ?: return@withContext Result.failure(Exception("未连接 SSH"))

        try {
            Log.i(TAG, "开始上传文件: $localPath -> $remotePath")
            ssh.newSCPFileTransfer().upload(FileSystemFile(File(localPath)), remotePath)
            Log.i(TAG, "文件上传成功: $localPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "文件上传失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 断开 SSH 连接
     */
    fun disconnect() {
        try {
            sshClient?.disconnect()
            sshClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常: ${e.message}")
        }
        sshClient = null
        _connectionState.value = SshConnectionState.DISCONNECTED
        _connectionInfo.value = null
        Log.i(TAG, "SSH 连接已断开")
    }

    /**
     * 获取已连接的主机名
     */
    fun getConnectedHost(): String? {
        return _connectionInfo.value?.host
    }
}