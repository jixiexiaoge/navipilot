package com.example.navipilot.data

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        private const val MAX_LOG_CHARS = 800
        private const val DEFAULT_COMMAND_TIMEOUT_SEC = 30L

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

        private fun truncateForLog(value: String, maxChars: Int = MAX_LOG_CHARS): String {
            if (value.length <= maxChars) return value
            return value.take(maxChars) + "…(truncated)"
        }

        private fun isBenignStderrOnSuccess(errorOutput: String): Boolean {
            val lines = errorOutput
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) return true

            // tmux 在没有 server 的情况下会打印到 stderr，但通常不影响命令语义
            // 示例: "no server running on /tmp/tmux-1000/default"
            val tmuxNoServer = Regex("^no server running on /tmp/tmux-\\d+/.*$")
            return lines.all { tmuxNoServer.matches(it) }
        }

        private fun isLikelyRebootDisconnect(e: Exception): Boolean {
            val msg = (e.message ?: "").lowercase()
            return msg.contains("eof") ||
                msg.contains("connection reset") ||
                msg.contains("broken pipe") ||
                msg.contains("socket") && msg.contains("closed") ||
                msg.contains("disconnected")
        }
    }

    private val _connectionState = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    private val _connectionInfo = MutableStateFlow<SshConnectionInfo?>(null)
    val connectionInfo: StateFlow<SshConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _userLogs = MutableStateFlow<List<String>>(emptyList())
    val userLogs: StateFlow<List<String>> = _userLogs.asStateFlow()

    // SSHJ SSH 客户端
    private var sshClient: SSHClient? = null

    val isConnected: Boolean
        get() = _connectionState.value == SshConnectionState.CONNECTED

    fun clearUserLogs() {
        _userLogs.value = emptyList()
    }

    fun logToUser(message: String) {
        appendUserLog(message)
    }

    private fun appendUserLog(message: String) {
        val timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        val line = "[$timestamp] $message"
        Log.i(TAG, line)
        _userLogs.update { current ->
            val next = (current + line)
            if (next.size <= 200) next else next.takeLast(200)
        }
    }

    private fun appendUserLogChunked(
        title: String,
        content: String,
        chunkSize: Int = 700
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        appendUserLog(title)
        trimmed.chunked(chunkSize).forEach { chunk ->
            appendUserLog(chunk)
        }
    }

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
        var cleanedBase64 = base64Content.replace(Regex("\\s"), "")

        Log.d(TAG, "原始 Base64 长度: ${base64Content.length}, 清理后长度: ${cleanedBase64.length}")

        // 验证 Base64 字符的合法性
        if (!cleanedBase64.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            Log.e(TAG, "Base64 内容包含非法字符")
            // 返回原内容，让后续的加载失败并给出明确错误
            return trimmedContent
        }

        // 补齐 Base64 padding（容错：部分来源会省略末尾 '='）
        // base64 长度 mod 4 == 1 时无法通过 padding 修复，属于内容损坏
        when (val mod = cleanedBase64.length % 4) {
            0 -> Unit
            2 -> cleanedBase64 += "=="
            3 -> cleanedBase64 += "="
            else -> {
                Log.e(TAG, "Base64 长度无效（mod 4 = $mod），私钥内容可能已损坏")
                return trimmedContent
            }
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
        var tempKeyFile: File? = null
        try {
            _connectionState.value = SshConnectionState.CONNECTING
            _errorMessage.value = null
            appendUserLog("开始 SSH 连接: $host:$port")

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
            appendUserLog("SSH 超时配置: connect=30s, read/write=15s")

            // 连接
            appendUserLog("正在建立 TCP 连接...")
            ssh.connect(host, port)
            appendUserLog("TCP 连接已建立，开始密钥交换...")

            // 配置 keepalive 以检测死连接（连接建立后）
            ssh.connection.keepAlive.keepAliveInterval = 10  // 每 10 秒发送 keepalive
            appendUserLog("已配置 keepalive: 10s")

            // 解析私钥 URI 获取 InputStream，并写入临时文件
            val keyInputStream = context.contentResolver.openInputStream(privateKeyUri)
                ?: throw Exception("无法读取私钥文件")

            // 创建临时文件存储私钥
            tempKeyFile = File(context.cacheDir, "temp_private_key")
            FileOutputStream(tempKeyFile).use { fos ->
                keyInputStream.copyTo(fos)
            }
            keyInputStream.close()

            // 读取私钥内容进行调试和修复
            val keyContent = tempKeyFile.readText()
            Log.d(TAG, "私钥文件长度: ${keyContent.length} 字符")
            Log.d(TAG, "私钥文件首行: ${keyContent.lineSequence().firstOrNull().orEmpty()}")

            // 检查是否是 PuTTY 格式 (.ppk)，SSHJ 不支持
            if (keyContent.contains("PuTTY") || keyContent.contains("PPK")) {
                throw Exception("不支持 PuTTY 格式 (.ppk) 私钥。请使用 OpenSSH 格式私钥（以 '-----BEGIN OPENSSH PRIVATE KEY-----' 或 '-----BEGIN RSA PRIVATE KEY-----' 开头）")
            }

            // 检查是否是有效的私钥格式
            if (!keyContent.contains("-----BEGIN") || !keyContent.contains("PRIVATE KEY")) {
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
            appendUserLog("正在加载私钥文件...")
            val keys: KeyProvider = ssh.loadKeys(tempKeyFile.absolutePath)
            appendUserLog("私钥加载成功，开始公钥认证...")
            ssh.authPublickey(username, keys)
            appendUserLog("公钥认证成功")

            // 保存连接
            sshClient = ssh
            _connectionState.value = SshConnectionState.CONNECTED
            _connectionInfo.value = SshConnectionInfo(host, port, username, privateKeyUri.toString())
            appendUserLog("SSH 连接成功: $host")

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
            appendUserLog("SSH 连接失败: $friendlyMessage")
            
            Result.failure(Exception(friendlyMessage))
        } finally {
            try {
                tempKeyFile?.delete()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /**
     * 执行远程命令
     */
    suspend fun execCommand(cmd: String, timeoutSec: Long = DEFAULT_COMMAND_TIMEOUT_SEC): Result<String> =
        withContext(Dispatchers.IO) {
            val ssh = sshClient ?: return@withContext Result.failure(Exception("未连接 SSH"))

            try {
                appendUserLog("执行命令: $cmd")
                val session: Session = ssh.startSession()
                try {
                    val command = session.exec(cmd)
                    // SSHJ 的 join(timeout) 返回 void；通过 exitStatus 是否已产生来判断是否结束
                    command.join(timeoutSec, TimeUnit.SECONDS)
                    val exitStatus = command.exitStatus
                    val finished = exitStatus != null

                    val output =
                        if (finished) IOUtils.readFully(command.inputStream).toString(Charsets.UTF_8) else ""
                    val errorOutput =
                        if (finished) IOUtils.readFully(command.errorStream).toString(Charsets.UTF_8) else ""

                    Log.i(TAG, "命令执行完成: $cmd, finished=$finished, exitStatus=$exitStatus")

                    if (!finished) {
                        appendUserLog("命令超时: ${timeoutSec}s")
                        Result.failure(Exception("命令执行超时 (${timeoutSec}s): $cmd"))
                    } else if (exitStatus == 0) {
                        if (errorOutput.isNotBlank() && !isBenignStderrOnSuccess(errorOutput)) {
                            appendUserLog("stderr: ${truncateForLog(errorOutput)}")
                        }
                        appendUserLog("命令完成: exit 0")
                        Result.success(output)
                    } else {
                        if (errorOutput.isNotBlank()) {
                            appendUserLog("stderr: ${truncateForLog(errorOutput)}")
                        } else if (output.isNotBlank()) {
                            // 有些命令会把错误写到 stdout
                            appendUserLog("stdout: ${truncateForLog(output)}")
                        }
                        appendUserLog("命令失败: exit ${exitStatus ?: "?"}")
                        val details = buildString {
                            if (errorOutput.isNotBlank()) append(errorOutput)
                            if (errorOutput.isBlank() && output.isNotBlank()) append(output)
                        }
                        Result.failure(Exception("命令执行失败 (exit ${exitStatus ?: "?"}): ${truncateForLog(details)}"))
                    }
                } finally {
                    session.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "命令执行异常: ${e.message}")
                appendUserLog("命令异常: ${e.message ?: e.javaClass.simpleName}")
                Result.failure(e)
            }
        }

    /**
     * 执行上传后的清理和重新编译操作
     *
     * 在上传模型文件后执行以下操作：
     * 1. 切换到 /data/openpilot 目录
     * 2. 删除旧的元数据和模型编译文件
     * 3. 尝试禁用缓存强制重新编译 modeld（如果 scons 可用）
     *
     * 注意：部分 comma3 设备可能没有安装 scons，此时跳过编译步骤，
     * 重启后 openpilot 会自动检测并加载新模型。
     */
    suspend fun cleanAndRebuildModels(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            appendUserLog("开始清理旧模型文件...")

            // 1. 删除旧的元数据文件
            val cleanMetadata = execCommand(
                "cd /data/openpilot && rm -f selfdrive/modeld/models/*_metadata.pkl",
                timeoutSec = 30L
            )
            if (cleanMetadata.isFailure) {
                val error = cleanMetadata.exceptionOrNull()
                appendUserLog("清理元数据文件失败: ${error?.message}")
                // 继续执行，不中断流程（文件可能不存在）
            } else {
                appendUserLog("元数据文件清理完成")
            }

            // 2. 删除旧的 tinygrad 编译文件
            val cleanTinygrad = execCommand(
                "cd /data/openpilot && rm -f selfdrive/modeld/models/*_tinygrad.pkl*",
                timeoutSec = 30L
            )
            if (cleanTinygrad.isFailure) {
                val error = cleanTinygrad.exceptionOrNull()
                appendUserLog("清理 tinygrad 文件失败: ${error?.message}")
                // 继续执行，不中断流程（文件可能不存在）
            } else {
                appendUserLog("Tinygrad 文件清理完成")
            }

            // 3. 尝试重建 modeld（可选）
            // 说明：
            // - openpilot 在启动时会自动检测并加载新模型；本步骤仅用于“预热”/加速首次加载。
            // - 非交互式 SSH 会话的 PATH 可能不完整；优先 source launch_env.sh 再执行 scons。
            // - 若设备无编译环境（常见于量产/精简系统），则跳过该步骤，不影响模型生效。
            appendUserLog("尝试重建 modeld（可选，可能需要 1-3 分钟）...")
            val rebuildResult = execCommand(
                """
                bash -lc 'cd /data/openpilot || exit 1;
                  if [ -f ./launch_env.sh ]; then source ./launch_env.sh >/dev/null 2>&1 || true; fi;
                  if ! command -v scons >/dev/null 2>&1; then echo "__NAVIPILOT_NO_SCONS__"; exit 0; fi;
                  rm -f /tmp/navipilot_modeld_rebuild.log;
                  scons -j4 --cache-disable selfdrive/modeld/ >/tmp/navipilot_modeld_rebuild.log 2>&1;
                  scons_ec=$?;
                  echo "__NAVIPILOT_SCONS_EXIT__${scons_ec}";
                  tail -n 120 /tmp/navipilot_modeld_rebuild.log 2>/dev/null || true;
                  if grep -q "Traceback (most recent call last)" /tmp/navipilot_modeld_rebuild.log 2>/dev/null; then
                    echo "__NAVIPILOT_TRACEBACK__";
                    awk "/Traceback \\(most recent call last\\)/{p=1} p{print}" /tmp/navipilot_modeld_rebuild.log | tail -n 160 2>/dev/null || true;
                  fi;
                  exit 0'
                """.trimIndent(),
                timeoutSec = 300L
            )

            if (rebuildResult.isFailure) {
                val errorMsg = rebuildResult.exceptionOrNull()?.message ?: ""
                appendUserLog("modeld 重建失败: $errorMsg")
                appendUserLog("提示: 重启后 openpilot 仍会自动加载新模型")
                // 不返回失败，继续后续流程
            } else {
                val output = rebuildResult.getOrNull().orEmpty()
                val lines = output.lineSequence().toList()
                val hasNoScons = lines.any { it == "__NAVIPILOT_NO_SCONS__" }
                val sconsExitCode = lines.firstOrNull { it.startsWith("__NAVIPILOT_SCONS_EXIT__") }
                    ?.removePrefix("__NAVIPILOT_SCONS_EXIT__")
                    ?.trim()
                    ?.toIntOrNull()

                val (tailLines, tracebackLines) = run {
                    val tail = mutableListOf<String>()
                    val traceback = mutableListOf<String>()
                    var inTraceback = false
                    for (line in lines) {
                        when {
                            line == "__NAVIPILOT_TRACEBACK__" -> inTraceback = true
                            line.startsWith("__NAVIPILOT_") -> Unit
                            inTraceback -> traceback.add(line)
                            else -> tail.add(line)
                        }
                    }
                    tail to traceback
                }

                if (hasNoScons) {
                    appendUserLog("未检测到 scons，跳过 modeld 重建")
                    appendUserLog("提示: 重启后 openpilot 会自动检测并加载新模型")
                } else if (sconsExitCode == null) {
                    appendUserLog("modeld 预热结果未知（未返回 scons exit code），已跳过预热")
                    val tailText = tailLines.joinToString("\n").trim()
                    if (tailText.isNotBlank()) {
                        appendUserLogChunked("scons 输出（尾部）", tailText)
                    }
                    val tracebackText = tracebackLines.joinToString("\n").trim()
                    if (tracebackText.isNotBlank()) {
                        appendUserLogChunked("scons Traceback（尾部）", tracebackText)
                    }
                    appendUserLog("提示: 重启后 openpilot 仍会自动加载新模型")
                } else if (sconsExitCode != null && sconsExitCode != 0) {
                    appendUserLog("modeld 预热失败（scons exit=$sconsExitCode），已跳过预热")
                    val tailText = tailLines.joinToString("\n").trim()
                    if (tailText.isNotBlank()) {
                        appendUserLogChunked("scons 输出（尾部）", tailText)
                    }
                    val tracebackText = tracebackLines.joinToString("\n").trim()
                    if (tracebackText.isNotBlank()) {
                        appendUserLogChunked("scons Traceback（尾部）", tracebackText)
                    }
                    appendUserLog("提示: 重启后 openpilot 仍会自动加载新模型")
                } else {
                    appendUserLog("modeld 重建完成")
                }
            }

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "清理和重新编译失败: ${e.message}")
            appendUserLog("清理和重新编译失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 重启远程设备
     *
     * 说明：
     * - 部分设备需要 root 权限，直接 `reboot` 会返回 exit 1。
     * - 使用 `sudo -n` 避免阻塞等待密码（无权限时会快速失败并返回提示）。
     * - 重启命令成功后 SSH 会立即断开，无需尝试其他命令。
     */
    suspend fun rebootDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "sudo -n reboot",
            "sudo -n systemctl reboot",
            "sudo -n shutdown -r now",
            "reboot"
        )

        var lastError: Exception? = null
        for (cmd in commands) {
            // 检查连接状态，如果已断开说明上一个重启命令生效了
            if (sshClient?.isConnected == false) {
                appendUserLog("SSH 已断开，重启命令已生效")
                return@withContext Result.success(Unit)
            }

            val result = try {
                execCommand(cmd, timeoutSec = 15L)
            } catch (e: Exception) {
                // reboot 可能会导致 SSH 连接立即断开；把这类断开视为"可能已重启"
                if (isLikelyRebootDisconnect(e)) {
                    appendUserLog("重启命令已发送（连接断开）")
                    return@withContext Result.success(Unit)
                }
                Result.failure(e)
            }

            if (result.isSuccess) {
                appendUserLog("重启命令执行成功")
                return@withContext Result.success(Unit)
            }

            val error = result.exceptionOrNull() as? Exception
            lastError = error
        }

        Result.failure(lastError ?: Exception("重启失败"))
    }

    /**
     * 上传文件到远程服务器 via SCP
     */
    suspend fun uploadFile(localPath: String, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val ssh = sshClient ?: return@withContext Result.failure(Exception("未连接 SSH"))

        try {
            appendUserLog("开始上传: ${File(localPath).name} -> $remotePath")
            ssh.newSCPFileTransfer().upload(FileSystemFile(File(localPath)), remotePath)
            appendUserLog("上传成功: ${File(localPath).name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "文件上传失败: ${e.message}")
            appendUserLog("上传失败: ${e.message ?: e.javaClass.simpleName}")
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
        appendUserLog("SSH 连接已断开")
    }

    /**
     * 获取已连接的主机名
     */
    fun getConnectedHost(): String? {
        return _connectionInfo.value?.host
    }
}
