package com.example.navipilot.data

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

        init {
            // 注册 BouncyCastle 作为安全提供者（解决 X25519 算法问题）
            // 必须在创建 SSHClient 之前注册，且只需注册一次
            try {
                java.security.Security.removeProvider("BC")  // 先移除避免重复
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                Log.i(TAG, "BouncyCastle 安全提供者已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册 BouncyCastle 失败: ${e.message}")
            }
        }
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
     * 格式化 OpenSSH 私钥：确保 Base64 内容每 64 个字符一行
     * 修复粘贴或导入时丢失换行符或格式错误的问题
     *
     * 策略：始终清理并重新格式化 Base64 内容，确保符合 OpenSSH 规范
     */
    private fun formatOpenSshPrivateKey(keyContent: String): String {
        val trimmedContent = keyContent.trim()

        // 检查是否包含 OpenSSH 私钥标记
        val beginMarker = "-----BEGIN OPENSSH PRIVATE KEY-----"
        val endMarker = "-----END OPENSSH PRIVATE KEY-----"

        if (!trimmedContent.contains(beginMarker) || !trimmedContent.contains(endMarker)) {
            // 不是 OpenSSH 格式，返回原内容（可能是 RSA 等其他格式）
            return trimmedContent
        }

        // 提取 Base64 内容（去除 BEGIN/END 标记）
        val base64Content = trimmedContent
            .substringAfter(beginMarker)
            .substringBefore(endMarker)
            .trim()

        // 如果 Base64 内容为空，返回原内容
        if (base64Content.isEmpty()) {
            return trimmedContent
        }

        // 清理所有空白字符（空格、换行、制表符等）
        // 这样可以处理各种格式错误：错误的换行位置、多余空格等
        val cleanedBase64 = base64Content.replace(Regex("\\s"), "")

        Log.d(TAG, "原始 Base64 长度: ${base64Content.length}, 清理后长度: ${cleanedBase64.length}")

        // 验证 Base64 字符的合法性
        if (!cleanedBase64.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            Log.e(TAG, "Base64 内容包含非法字符")
            // 返回原内容，让后续的加载失败并给出明确错误
            return trimmedContent
        }

        // 重新格式化：每 64 个字符一行（OpenSSH 标准格式）
        val formattedBase64 = cleanedBase64.chunked(64).joinToString("\n")
        Log.d(TAG, "Base64 已重新格式化为 ${formattedBase64.lines().size} 行")

        // 重新组装私钥
        return "$beginMarker\n$formattedBase64\n$endMarker"
    }

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

            // 跳过主机密钥验证（适合开发/测试）
            ssh.addHostKeyVerifier(PromiscuousVerifier())

            // 配置超时 - 防止连接卡死
            ssh.connectTimeout = 30000  // 30秒连接超时
            ssh.timeout = 15000         // 15秒读写超时
            Log.d(TAG, "SSH 客户端超时配置: connect=30s, read/write=15s")

            // 连接
            Log.d(TAG, "正在建立 TCP 连接...")
            ssh.connect(host, port)
            Log.d(TAG, "TCP 连接已建立，开始密钥交换...")

            // 配置 keepalive 以检测死连接（连接建立后）
            ssh.connection.keepAlive.keepAliveInterval = 10  // 每 10 秒发送 keepalive
            Log.d(TAG, "已配置 keepalive 间隔: 10s")

            // 解析私钥 URI 获取 InputStream，并写入临时文件
            val keyInputStream = context.contentResolver.openInputStream(privateKeyUri)
                ?: throw Exception("无法读取私钥文件")

            // 创建临时文件存储私钥
            val tempKeyFile = File(context.cacheDir, "temp_private_key")
            FileOutputStream(tempKeyFile).use { fos ->
                keyInputStream.copyTo(fos)
            }
            keyInputStream.close()

            // 读取私钥内容进行调试和修复
            val keyContent = tempKeyFile.readText()
            Log.d(TAG, "私钥文件长度: ${keyContent.length} 字符")
            Log.d(TAG, "私钥文件前100字符: ${keyContent.take(100)}")

            // 检查是否是 PuTTY 格式 (.ppk)，SSHJ 不支持
            if (keyContent.contains("PuTTY") || keyContent.contains("PPK")) {
                tempKeyFile.delete()
                throw Exception("不支持 PuTTY 格式 (.ppk) 私钥。请使用 OpenSSH 格式私钥（以 '-----BEGIN OPENSSH PRIVATE KEY-----' 或 '-----BEGIN RSA PRIVATE KEY-----' 开头）")
            }

            // 检查是否是有效的私钥格式
            if (!keyContent.contains("-----BEGIN") || !keyContent.contains("PRIVATE KEY")) {
                tempKeyFile.delete()
                throw Exception("无效的私钥格式。请确保使用 OpenSSH 格式私钥")
            }

            // 修复 OpenSSH 私钥格式：清理并重新格式化 Base64 内容
            val cleanedKeyContent = formatOpenSshPrivateKey(keyContent)
            if (cleanedKeyContent != keyContent) {
                tempKeyFile.writeText(cleanedKeyContent)
                Log.d(TAG, "私钥已清理并重新格式化")
            } else {
                Log.d(TAG, "私钥格式无需修改")
            }

            // 使用 SSHJ 加载私钥文件
            Log.d(TAG, "正在加载私钥文件...")
            val keys: KeyProvider = ssh.loadKeys(tempKeyFile.absolutePath)
            Log.d(TAG, "私钥加载成功，开始公钥认证...")
            ssh.authPublickey(username, keys)
            Log.d(TAG, "公钥认证成功")

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
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            
            // 提供更友好的错误提示
            val friendlyMessage = when {
                e.message?.contains("incorrect ending byte") == true ->
                    "私钥格式错误。请确保使用 OpenSSH 格式私钥（非 PuTTY .ppk 格式），且 Base64 编码正确"
                e.message?.contains("Auth fail") == true ->
                    "认证失败。请检查用户名和私钥是否匹配"
                e.message?.contains("Connection refused") == true ->
                    "连接被拒绝。请检查 IP 地址和端口是否正确，以及 SSH 服务是否运行"
                e.message?.contains("timeout") == true || e.message?.contains("timed out") == true ->
                    "连接超时。请检查网络连接、IP 地址是否正确，以及设备是否可达"
                e.message?.contains("Network is unreachable") == true ->
                    "网络不可达。请检查设备网络连接和 IP 地址"
                e.message?.contains("No route to host") == true ->
                    "无法到达主机。请检查 IP 地址是否正确"
                else -> e.message ?: "SSH 连接失败"
            }
            _errorMessage.value = friendlyMessage
            
            Result.failure(Exception(friendlyMessage))
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