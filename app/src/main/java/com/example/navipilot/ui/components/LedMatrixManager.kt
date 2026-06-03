package com.example.navipilot.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LED_RENDER_TAG = "LedMatrixRender"

/**
 * LED 点阵文字渲染器 — 仅用于 App 内预览可视化。
 * 独立于实际通信协议，产生列优先 16-px 点阵数据供 [LedMatrixPreview] 消费。
 */
internal object LedMatrixBitmapRenderer {
    private fun isCJKChar(ch: Char): Boolean {
        return Character.UnicodeBlock.of(ch)?.let {
            it == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            it == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            it == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            it == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            it == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            it == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            it == Character.UnicodeBlock.HIRAGANA ||
            it == Character.UnicodeBlock.KATAKANA
        } ?: false
    }

    fun renderCharToColumnMajor(ch: Char): Pair<Int, ByteArray>? {
        return try {
            val size = 16
            val isCJK = isCJKChar(ch)

            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
                textSize = if (isCJK) 15f else 14f
            }

            val fm = paint.fontMetrics
            val textY = (size - fm.top - fm.bottom) / 2f
            canvas.drawText(ch.toString(), size / 2f, textY, paint)

            val pixels = IntArray(size * size)
            bmp.getPixels(pixels, 0, size, 0, 0, size, size)
            bmp.recycle()

            val original = Array(size) { row ->
                BooleanArray(size) { col -> (pixels[row * size + col] ushr 24) > 128 }
            }
            val rotated = Array(size) { newRow ->
                BooleanArray(size) { newCol -> original[newCol][newRow] }
            }

            val colBitmap = ByteArray(32)
            for (col in 0 until 16) {
                var upper = 0
                var lower = 0
                for (row in 0 until size) {
                    if (rotated[row][col]) {
                        if (row < 8) upper = upper or (1 shl row)
                        else lower = lower or (1 shl (row - 8))
                    }
                }
                colBitmap[col * 2] = upper.toByte()
                colBitmap[col * 2 + 1] = lower.toByte()
            }

            val nonZero = colBitmap.count { it.toInt() != 0 }
            if (nonZero > 0) Pair(16, colBitmap) else null
        } catch (e: Exception) {
            Log.e(LED_RENDER_TAG, "'$ch' 渲染异常: ${e.message}")
            null
        }
    }

    fun renderTextToColumnMajor(text: String): List<ByteArray> {
        return text.mapNotNull { renderCharToColumnMajor(it)?.second }
    }
}

/**
 * BLE LED 点阵屏管理器 — 新 0x5E UART 协议
 *
 * 通过 BLE NUS（Nordic UART Service）将 0x5E 帧格式数据发送至 BLE IC，
 * BLE IC 通过 UART 转发至屏幕。支持 ACK/CUID 流控制、设备信息查询、
 * 实时显示（RGB565 全屏位图）、矩形填充、亮度/开关控制等。
 *
 * 公共 API 与旧版 iPixel 协议兼容，调用方无需修改。
 */
class LedMatrixManager(private val context: Context) {

    companion object {
        private const val TAG = "LedMatrix"

        // NUS（Nordic UART Service）— BLE 透明桥接
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 5_000L
        private const val PREFERRED_MTU = 512
        private const val PREF_NAME = "CarrotAmap"
        private const val PREF_LED_ADDRESS = "led_device_address"
        private const val PREF_LED_NAME = "led_device_name"

        // 0x5E 协议常量
        private const val FRAME_PREFIX: Byte = 0x5E
        private const val PAYLOAD_CMD: Byte = 0xCD.toByte()
        private const val PAYLOAD_DATA: Byte = 0xDA.toByte()
        private const val PAYLOAD_ERROR: Byte = 0xE0.toByte()
        private const val ACK_TIMEOUT_MS = 1500L
        private const val MAX_RETRIES = 2

        // 命令码（LE16）
        private const val CMD_DISPLAY_ONOFF = 0x0E01
        private const val CMD_BRIGHTNESS = 0x0E02
        private const val CMD_REALTIME_DISPLAY = 0xD501
        private const val CMD_FILL_RECT = 0xD517
        private const val CMD_DEVICE_INFO = 0xD702

        // 默认分辨率（设备信息未获取时的兜底值）
        private const val DEFAULT_WIDTH = 64
        private const val DEFAULT_HEIGHT = 16

        @Volatile
        private var instance: LedMatrixManager? = null

        fun getInstance(context: Context): LedMatrixManager {
            return instance ?: synchronized(this) {
                instance ?: LedMatrixManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ====================================================================
    // 公共类型
    // ====================================================================

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SENDING, ERROR }

    enum class AnimationType(val code: Int) {
        STATIC(0), SCROLL_LEFT(1), SCROLL_RIGHT(2), BREATHE(6), LASER(8)
    }

    sealed class DisplayCommand {
        data class Text(val text: String, val color: Int?, val animation: AnimationType) : DisplayCommand()
        data class BlueLine(val animation: AnimationType = AnimationType.STATIC) : DisplayCommand()
    }

    data class DisplayState(
        val text: String = "",
        val color: ComposeColor = ComposeColor.White,
        val animCode: Int = 0,
        val bitmapData: List<ByteArray> = emptyList()
    )

    data class DeviceInfo(
        val model: String = "",
        val id: ByteArray = ByteArray(8),
        val versionMajor: Int = 0,
        val versionMinor: Int = 0,
        val versionPatch: Int = 0,
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
        val orientation: Int = 0,
        val memory: Long = 0,
        val memoryAvailable: Long = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeviceInfo) return false
            return model == other.model && id.contentEquals(other.id) &&
                versionMajor == other.versionMajor && versionMinor == other.versionMinor &&
                versionPatch == other.versionPatch && width == other.width &&
                height == other.height && orientation == other.orientation &&
                memory == other.memory && memoryAvailable == other.memoryAvailable
        }
        override fun hashCode(): Int {
            var result = model.hashCode()
            result = 31 * result + id.contentHashCode()
            return result
        }
    }

    // ====================================================================
    // 公共状态
    // ====================================================================

    var state: State = State.IDLE; private set
    var stateMessage: String = ""; private set
    var onStateChanged: ((State, String) -> Unit)? = null

    val scannedDevices = mutableListOf<BluetoothDevice>()
    var onDevicesUpdated: (() -> Unit)? = null

    /** 缓存的设备信息，连接后自动获取 */
    var deviceInfo: DeviceInfo? = null; private set

    private val _currentDisplayState = MutableStateFlow(DisplayState())
    val currentDisplayState: StateFlow<DisplayState> = _currentDisplayState.asStateFlow()

    // ====================================================================
    // BLE 状态
    // ====================================================================

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var scanning = false
    @Volatile private var currentMtu = 23
    @Volatile private var connected = false

    // 自动连接
    @Volatile private var autoConnecting = false
    private var pendingInitText: String? = null
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectAttempts = 0
    private val MAX_AUTO_RECONNECT = 3
    private val reconnectDelays = longArrayOf(2_000L, 5_000L, 10_000L)

    // ====================================================================
    // 0x5E 协议状态
    // ====================================================================

    @Volatile private var cuid = 0
    /** CUID → 响应回调（发送命令后等待回复） */
    private val pendingResponses = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    /** CUID → 超时 Runnable */
    private val pendingTimeouts = ConcurrentHashMap<Int, Runnable>()

    // BLE 写入队列 — 防并发，onCharacteristicWrite 确认后才发下一包
    private val writeQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false

    // 发送序列号 — 递增后旧的 postDelayed 回调自动忽略
    @Volatile private var sendGeneration = 0

    // ====================================================================
    // 自动显示引擎状态
    // ====================================================================

    private var lastAutoText: String = ""
    private var lastAutoColor: Int? = null
    private var lastAutoAnim: AnimationType = AnimationType.STATIC
    private var lastAutoSendTime: Long = 0
    private var lastDisplayIsBlueLine: Boolean = false
    private var lastBlueLineAnim: AnimationType = AnimationType.STATIC
    private var speedHistory = mutableListOf<Int>()
    private var autoDisplayEnabled: Boolean = true
    private var stoppedSince: Long = 0L
    private var welcomeShown: Boolean = false

    // ====================================================================
    // 生命周期
    // ====================================================================

    fun destroy() {
        cleanupInternal()
        updateState(State.IDLE, "已销毁")
    }

    // ====================================================================
    // 权限 & 蓝牙状态
    // ====================================================================

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSavedDeviceAddress(): String? = prefs.getString(PREF_LED_ADDRESS, null)

    private fun saveDevice(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null }
        prefs.edit().putString(PREF_LED_ADDRESS, device.address)
            .putString(PREF_LED_NAME, name ?: device.address).apply()
    }

    fun clearSavedDevice() {
        prefs.edit().remove(PREF_LED_ADDRESS).remove(PREF_LED_NAME).apply()
    }

    private fun updateState(newState: State, msg: String = "") {
        state = newState; stateMessage = msg
        handler.post { onStateChanged?.invoke(newState, msg) }
    }

    // ====================================================================
    // 自动连接
    // ====================================================================

    @SuppressLint("MissingPermission")
    fun autoConnect(initText: String? = null, onNotFound: (() -> Unit)? = null) {
        if (state == State.CONNECTED || state == State.SENDING) {
            if (initText != null) sendText(initText)
            return
        }
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress == null) { onNotFound?.invoke(); return }
        if (!hasPermissions() || !isBluetoothEnabled()) { onNotFound?.invoke(); return }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run { onNotFound?.invoke(); return }

        autoConnecting = true
        pendingInitText = initText
        val savedName = prefs.getString(PREF_LED_NAME, savedAddress)
        updateState(State.SCANNING, "自动连接 $savedName...")

        val autoCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (device.address == savedAddress) {
                        try { scanner.stopScan(this) } catch (_: Exception) {}
                        autoConnecting = false
                        connect(device)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                autoConnecting = false
                updateState(State.IDLE, "")
                onNotFound?.invoke()
            }
        }

        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), autoCallback)
        handler.postDelayed({
            if (autoConnecting) {
                autoConnecting = false
                try { scanner.stopScan(autoCallback) } catch (_: Exception) {}
                updateState(State.IDLE, "")
                pendingInitText = null
                onNotFound?.invoke()
            }
        }, AUTO_SCAN_TIMEOUT_MS)
    }

    // ====================================================================
    // 扫描
    // ====================================================================

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) { updateState(State.ERROR, "缺少蓝牙权限"); return }
        if (!isBluetoothEnabled()) { updateState(State.ERROR, "蓝牙未开启"); return }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run { updateState(State.ERROR, "BLE不可用"); return }
        scannedDevices.clear(); scanning = true
        updateState(State.SCANNING, "扫描中...")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed({ if (scanning) stopScan() }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return; scanning = false
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (state == State.SCANNING) updateState(State.IDLE, "扫描完成，发现${scannedDevices.size}个设备")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = try { device.name } catch (_: Exception) { null }
                if (name != null && scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                    handler.post { onDevicesUpdated?.invoke() }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; updateState(State.ERROR, "扫描失败: $errorCode") }
    }

    // ====================================================================
    // 连接 / 断开
    // ====================================================================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan(); disconnect(); connected = false
        saveDevice(device)
        lastConnectedDevice = device
        autoReconnectAttempts = 0
        deviceInfo = null
        welcomeShown = false
        val name = try { device.name } catch (_: Exception) { device.address }
        updateState(State.CONNECTING, "连接 $name...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
    }

    private fun cleanupInternal(stopReconnect: Boolean = true) {
        if (stopReconnect) {
            lastConnectedDevice = null
            autoReconnectAttempts = MAX_AUTO_RECONNECT
        }
        handler.removeCallbacksAndMessages(null)
        stopScan()
        synchronized(writeQueue) { writeQueue.clear() }
        writeInFlight = false
        connected = false
        pendingResponses.clear()
        pendingTimeouts.values.forEach { handler.removeCallbacks(it) }
        pendingTimeouts.clear()
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null; txCharacteristic = null; rxCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cleanupInternal()
        updateState(State.IDLE, "已断开")
    }

    // ====================================================================
    // GATT 回调
    // ====================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    autoReconnectAttempts = 0
                    gatt?.requestMtu(PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txCharacteristic = null; rxCharacteristic = null; connected = false
                    synchronized(writeQueue) { writeQueue.clear() }
                    writeInFlight = false
                    updateState(State.IDLE, "连接已断开")
                    val device = lastConnectedDevice
                    if (device != null && autoReconnectAttempts < MAX_AUTO_RECONNECT) {
                        val delay = reconnectDelays.getOrElse(autoReconnectAttempts) { reconnectDelays.last() }
                        autoReconnectAttempts++
                        Log.i(TAG, "${delay / 1000}s 后自动重连 (第 $autoReconnectAttempts/$MAX_AUTO_RECONNECT 次)")
                        handler.postDelayed({ connect(device) }, delay)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { updateState(State.ERROR, "服务发现失败"); return }

            // 仅 NUS（不再支持 iPixel 0x00FA）
            val nusService = gatt?.getService(NUS_SERVICE_UUID)
            if (nusService != null) {
                txCharacteristic = nusService.getCharacteristic(NUS_TX_CHAR_UUID)
                rxCharacteristic = nusService.getCharacteristic(NUS_RX_CHAR_UUID)
                if (rxCharacteristic != null) {
                    gatt.setCharacteristicNotification(rxCharacteristic, true)
                    rxCharacteristic?.getDescriptor(CCCD_UUID)?.let { cccd ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }
                if (txCharacteristic != null) {
                    connected = true
                    updateState(State.CONNECTED, "已连接(NUS)")
                    // 连接成功后查询设备信息
                    handler.postDelayed({ requestDeviceInfo() }, 300)
                    // 发送初始文字
                    pendingInitText?.let { text ->
                        pendingInitText = null
                        handler.postDelayed({ sendText(text) }, 1000)
                    }
                } else updateState(State.ERROR, "未找到NUS TX")
            } else {
                updateState(State.ERROR, "未找到NUS服务")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "写入失败: status=$status")
            writeInFlight = false
            drainWriteQueue()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION") characteristic?.value?.let { handleNotification(it) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }
    }

    // ====================================================================
    // 0x5E 协议 — 帧构建
    // ====================================================================

    /**
     * 构建 0x5E 数据帧。
     *
     * 帧格式：
     *   [0:1]  0x5E 固定前缀
     *   [1]    A1 随机数
     *   [2]    A0 ⊕ A1 异或验证
     *   [3]    Length 载荷长度
     *   [4]    A2 ⊕ A3 异或验证
     *   [5..n] Payload 载荷
     *   [n+1]  CheckSum = Σ(A_n ⊕ n) & 0xFF
     */
    private fun buildFrame(payload: ByteArray): ByteArray {
        val a1 = ((Math.random() * 256).toInt() and 0xFF).toByte()
        val len = payload.size
        val frameSize = 5 + len + 1
        val frame = ByteArray(frameSize)

        frame[0] = FRAME_PREFIX
        frame[1] = a1
        frame[2] = (FRAME_PREFIX.toInt() xor (a1.toInt() and 0xFF)).toByte()
        frame[3] = len.toByte()
        frame[4] = ((frame[2].toInt() and 0xFF) xor (len and 0xFF)).toByte()
        System.arraycopy(payload, 0, frame, 5, len)

        var checksum = 0
        for (i in payload.indices) {
            checksum += (payload[i].toInt() and 0xFF) xor (i + 5)
        }
        frame[frameSize - 1] = (checksum and 0xFF).toByte()
        return frame
    }

    /**
     * 构建命令载荷。
     * @param type    PAYLOAD_CMD (0xCD) 或 PAYLOAD_DATA (0xDA)
     * @param cuid_   当前 CUID
     * @param content 命令内容
     */
    private fun buildPayload(type: Byte, cuid_: Int, content: ByteArray = byteArrayOf()): ByteArray {
        return byteArrayOf(type, (cuid_ and 0xFF).toByte(), ((cuid_ shr 8) and 0xFF).toByte()) + content
    }

    /** 构建命令类型载荷（含 16 位命令码）。 */
    private fun buildCmdPayload(cmdCode: Int, cuid_: Int, args: ByteArray = byteArrayOf()): ByteArray {
        val content = byteArrayOf(
            (cmdCode and 0xFF).toByte(),
            ((cmdCode shr 8) and 0xFF).toByte()
        ) + args
        return buildPayload(PAYLOAD_CMD, cuid_, content)
    }

    /** 下一个 CUID（16 位循环递增）。 */
    private fun nextCuid(): Int {
        val result = cuid
        cuid = (cuid + 1) and 0xFFFF
        return result
    }

    /** Int → LE16 */
    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /** Int → LE32 */
    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    // ====================================================================
    // 0x5E 协议 — 帧解析
    // ====================================================================

    /**
     * 验证并解析 0x5E 响应帧。
     * @return Pair(载荷类型, CUID, 载荷内容) 或 null（无效帧）
     */
    private fun parseResponseFrame(frame: ByteArray): Triple<Byte, Int, ByteArray>? {
        if (frame.size < 6) return null                     // 至少帧头(5) + 类型(1) + 校验(1)
        if (frame[0] != FRAME_PREFIX) return null            // 前缀校验
        val a1 = frame[1].toInt() and 0xFF
        if (frame[2].toInt() != (FRAME_PREFIX.toInt() xor a1)) return null  // A2 = A0 ⊕ A1
        val len = frame[3].toInt() and 0xFF
        if (frame.size < 5 + len + 1) return null            // 长度不足
        if (frame[4].toInt() != ((frame[2].toInt() and 0xFF) xor len)) return null  // A4 = A2 ⊕ A3

        // 验证校验和
        var expectedCs = 0
        for (i in 0 until len) {
            expectedCs += (frame[5 + i].toInt() and 0xFF) xor (i + 5)
        }
        if ((frame[5 + len].toInt() and 0xFF) != (expectedCs and 0xFF)) return null

        val payloadType = frame[5]
        val cuidRaw = ((frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8))
        val content = if (len > 3) frame.copyOfRange(8, 5 + len) else byteArrayOf()
        return Triple(payloadType, cuidRaw, content)
    }

    /** 判断是否为 ACK 帧（载荷只有类型 + CUID，无内容）。 */
    private fun isAck(type: Byte, contentLen: Int): Boolean {
        return contentLen == 0 && (type == PAYLOAD_CMD || type == PAYLOAD_DATA)
    }

    // ====================================================================
    // 0x5E 协议 — 发送与 ACK 处理
    // ====================================================================

    /**
     * 发送命令并注册响应回调。
     * @param content  载荷内容（不含类型和 CUID，buildCmdPayload 内部处理）
     * @param onResponse  收到响应时的回调（payload content bytes），null = fire-and-forget
     */
    private fun sendCommand(cmdCode: Int, args: ByteArray = byteArrayOf(), onResponse: ((ByteArray) -> Unit)? = null) {
        val cuid = nextCuid()
        val payload = buildCmdPayload(cmdCode, cuid, args)
        if (onResponse != null) {
            registerPendingResponse(cuid, onResponse)
        }
        writeFrame(payload)
    }

    /**
     * 发送数据帧（0xDA 类型）。
     * @param dataCuid  数据帧 CUID
     * @param data      数据内容
     * @param onAck     收到 ACK 后的回调（可选）
     */
    private fun sendDataFrame(dataCuid: Int, data: ByteArray, onAck: (() -> Unit)? = null) {
        val payload = buildPayload(PAYLOAD_DATA, dataCuid, data)
        if (onAck != null) {
            registerPendingResponse(dataCuid) { onAck() }
        }
        writeFrame(payload)
    }

    private fun registerPendingResponse(cuid: Int, callback: (ByteArray) -> Unit) {
        pendingResponses[cuid] = callback
        val timeout = Runnable {
            pendingResponses.remove(cuid)
            pendingTimeouts.remove(cuid)
            Log.w(TAG, "CUID=$cuid 响应超时")
        }
        pendingTimeouts[cuid] = timeout
        handler.postDelayed(timeout, ACK_TIMEOUT_MS)
    }

    /**
     * 将载荷写入 BLE 队列。
     */
    private fun writeFrame(payload: ByteArray) {
        val frame = buildFrame(payload)
        synchronized(writeQueue) {
            writeQueue.addLast(frame)
        }
        drainWriteQueue()
    }

    /** 从 BLE 写入队列取下一包发送。 */
    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val gatt = bluetoothGatt ?: run { synchronized(writeQueue) { writeQueue.clear() }; return }
        val char = txCharacteristic ?: run { synchronized(writeQueue) { writeQueue.clear() }; return }
        val next = synchronized(writeQueue) { if (writeQueue.isEmpty()) null else writeQueue.removeFirst() }
        if (next != null) {
            writeInFlight = true
            writeSingle(gatt, char, next)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeSingle(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
        try {
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入异常: ${e.message}")
            writeInFlight = false
            drainWriteQueue()
        }
    }

    // ====================================================================
    // 0x5E 协议 — 通知处理
    // ====================================================================

    private fun handleNotification(value: ByteArray) {
        val parsed = parseResponseFrame(value) ?: run {
            Log.w(TAG, "收到无效帧: ${value.size}B")
            return
        }
        val (type, cuid_, content) = parsed

        // ACK 处理
        if (isAck(type, content.size)) {
            val callback = pendingResponses.remove(cuid_)
            pendingTimeouts.remove(cuid_)?.let { handler.removeCallbacks(it) }
            if (callback != null) {
                callback(byteArrayOf())  // 空内容 = ACK
            }
            return
        }

        // 命令响应（非 ACK）
        val callback = pendingResponses.remove(cuid_)
        pendingTimeouts.remove(cuid_)?.let { handler.removeCallbacks(it) }
        if (callback != null) {
            callback(content)
        }
    }

    // ====================================================================
    // 协议命令 — 设备信息
    // ====================================================================

    /** 请求设备信息（0xD702）。响应中包含分辨率、型号等。 */
    private fun requestDeviceInfo() {
        val cuid = nextCuid()
        val payload = buildCmdPayload(CMD_DEVICE_INFO, cuid, byteArrayOf(0x00))  // CMD=0x00 获取
        registerPendingResponse(cuid) { content ->
            if (content.size >= 26) {
                try {
                    val modelBytes = content.copyOfRange(0, 8)
                    val model = String(modelBytes, Charsets.UTF_8).trimEnd(' ', ' ')
                    val id = content.copyOfRange(8, 16)
                    val verMajor = content[16].toInt() and 0xFF
                    val verMinor = content[17].toInt() and 0xFF
                    val verPatch = content[18].toInt() and 0xFF
                    val w = ((content[19].toInt() and 0xFF) or ((content[20].toInt() and 0xFF) shl 8))
                    val h = ((content[21].toInt() and 0xFF) or ((content[22].toInt() and 0xFF) shl 8))
                    val orient = content[23].toInt() and 0xFF
                    val mem = ((content[24].toInt() and 0xFF).toLong() or
                               ((content[25].toInt() and 0xFF).toLong() shl 8) or
                               ((content[26].toInt() and 0xFF).toLong() shl 16) or
                               ((content[27].toInt() and 0xFF).toLong() shl 24))
                    val memAvail = if (content.size >= 32) {
                        ((content[28].toInt() and 0xFF).toLong() or
                         ((content[29].toInt() and 0xFF).toLong() shl 8) or
                         ((content[30].toInt() and 0xFF).toLong() shl 16) or
                         ((content[31].toInt() and 0xFF).toLong() shl 24))
                    } else 0L
                    deviceInfo = DeviceInfo(model, id, verMajor, verMinor, verPatch, w, h, orient, mem, memAvail)
                    Log.i(TAG, "设备信息: $model ${w}x$h v$verMajor.$verMinor.$verPatch")
                } catch (e: Exception) {
                    Log.w(TAG, "设备信息解析失败: ${e.message}")
                    deviceInfo = DeviceInfo()
                }
            } else {
                Log.w(TAG, "设备信息响应长度不足: ${content.size}B")
                deviceInfo = DeviceInfo()
            }
        }
        writeFrame(payload)
    }

    // ====================================================================
    // 协议命令 — 实时显示（长数据传输）
    // ====================================================================

    /**
     * 通过 0xD501 长数据传输将 RGB565 像素数据发送至屏幕。
     *
     * 流程：
     *   1. 发送 0xD501 初始化帧（含总长度 + 起始 CUID）
     *   2. 等待 ACK
     *   3. 分帧发送像素数据（0xDA 类型，CUID 递增）
     *   4. 完成
     */
    private fun sendRealTimeDisplay(rgb565Data: ByteArray) {
        if (!connected || txCharacteristic == null) return
        val gen = ++sendGeneration
        val startCUID = (nextCuid() + 1) and 0xFFFF  // 数据帧起始 CUID
        val initCUID = nextCuid()

        // 构建初始化帧
        val initArgs = le32(rgb565Data.size) + le16(startCUID)
        val initPayload = buildCmdPayload(CMD_REALTIME_DISPLAY, initCUID, initArgs)

        registerPendingResponse(initCUID) {
            // ACK 收到，开始发送数据帧
            if (gen != sendGeneration) return@registerPendingResponse
            sendDataChunks(rgb565Data, startCUID, gen)
        }
        writeFrame(initPayload)
    }

    /** 分帧发送 RGB565 像素数据（0xDA 类型，每帧 ~200 字节载荷）。 */
    private fun sendDataChunks(data: ByteArray, startCUID: Int, gen: Int) {
        val chunkSize = 200
        var offset = 0
        var dataCuid = startCUID

        while (offset < data.size && gen == sendGeneration) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            sendDataFrame(dataCuid, chunk)
            dataCuid = (dataCuid + 1) and 0xFFFF
            offset = end
        }
        if (gen == sendGeneration) {
            Log.i(TAG, "实时显示数据发送完成: ${data.size}B")
        }
    }

    // ====================================================================
    // 协议命令 — 实色填充（0xD517）
    // ====================================================================

    /**
     * 填充矩形区域。
     * @param color16 RGB565 16 位颜色值
     * @param x,y,w,h 矩形坐标与尺寸
     */
    private fun sendFillRect(color16: Int, x: Int, y: Int, w: Int, h: Int) {
        val args = le16(color16) + byteArrayOf(0x00) +  // color + shape=矩形
            le16(x) + le16(y) + le16(w) + le16(h)
        sendCommand(CMD_FILL_RECT, args)
    }

    // ====================================================================
    // 协议命令 — 显示开关 / 亮度
    // ====================================================================

    /** 设置屏幕开关（0x0E01）。 */
    fun setScreenOn(on: Boolean) {
        sendCommand(CMD_DISPLAY_ONOFF, byteArrayOf(if (on) 0x01 else 0x00))
        Log.i(TAG, if (on) "屏幕开启" else "屏幕关闭")
    }

    /** 设置屏幕亮度（0x0E02），0~255。 */
    fun setBrightness(value: Int) {
        val v = value.coerceIn(0, 255)
        sendCommand(CMD_BRIGHTNESS, byteArrayOf(v.toByte()))
        Log.i(TAG, "亮度设为 $v")
    }

    // ====================================================================
    // 公共 API — 发送文字
    // ====================================================================

    fun sendText(text: String, color: Int? = null, animation: AnimationType = AnimationType.STATIC) {
        if (text.isBlank()) return
        val effectiveAnim = if (text.length > 4 && animation == AnimationType.STATIC) AnimationType.SCROLL_LEFT else animation
        updatePreviewDisplayState(text, color, effectiveAnim)

        if (!connected || txCharacteristic == null) return
        updateState(State.SENDING, "发送: $text")

        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        val rgb565 = renderTextToRGB565(text, w, h, color)
        if (rgb565 != null) {
            sendRealTimeDisplay(rgb565)
        }
        handler.postDelayed({ updateState(State.CONNECTED, "已发送: $text") }, 500)
    }

    // ====================================================================
    // 公共 API — 蓝线 / 清屏
    // ====================================================================

    /**
     * 发送蓝色横线效果（智驾小蓝灯）。
     * 使用 0xD517 在屏幕中间位置绘制蓝色矩形条。
     */
    fun sendDebugBlueLine(animation: AnimationType = AnimationType.STATIC) {
        if (!connected || txCharacteristic == null) {
            Log.w(TAG, "设备未就绪")
            return
        }
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        val lineY = (h / 2) - 3
        val lineH = 6
        // #3388FF → RGB565
        val rgb565 = rgb565FromArgb(0x33, 0x88, 0xFF)
        updateState(State.SENDING, "发送蓝线...")
        sendFillRect(rgb565, 0, lineY.coerceAtLeast(0), w, lineH.coerceAtMost(h))
        handler.postDelayed({
            if (state == State.SENDING) updateState(State.CONNECTED, "已发送蓝线")
        }, 300)
    }

    /** 清屏（填充全黑）。 */
    fun clearScreen() {
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        sendFillRect(0x0000, 0, 0, w, h)
        Log.i(TAG, "清屏")
    }

    // ====================================================================
    // 渲染 — RGB565 位图生成
    // ====================================================================

    /** ARGB → RGB565 公式 */
    private fun rgb565FromArgb(r: Int, g: Int, b: Int): Int {
        val r5 = (r and 0xFF) shr 3
        val g6 = (g and 0xFF) shr 2
        val b5 = (b and 0xFF) shr 3
        return (r5 shl 11) or (g6 shl 5) or b5
    }

    /**
     * 将文字渲染为 RGB565 像素缓冲区（行优先）。
     * @return RGB565 ByteArray（LE16 每像素），null 渲染失败
     */
    private fun renderTextToRGB565(text: String, width: Int, height: Int, colorArgb: Int?): ByteArray? {
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // 黑色背景
            canvas.drawColor(Color.BLACK)

            val textColor = if (colorArgb != null) colorArgb else Color.WHITE
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = textColor
                typeface = Typeface.MONOSPACE
                // 根据屏幕宽度自适应字号
                val maxCharWidth = width / text.length.coerceAtLeast(1)
                textSize = (maxCharWidth * 1.5f).coerceAtMost(height * 0.9f)
                textAlign = Paint.Align.CENTER
            }

            val fm = paint.fontMetrics
            val textY = (height - fm.top - fm.bottom) / 2f
            canvas.drawText(text, width / 2f, textY, paint)

            // 提取像素 → RGB565
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            bmp.recycle()

            val result = ByteArray(width * height * 2)
            var idx = 0
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val rgb565 = rgb565FromArgb(r, g, b)
                result[idx++] = (rgb565 and 0xFF).toByte()       // 低字节
                result[idx++] = ((rgb565 shr 8) and 0xFF).toByte() // 高字节
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "RGB565 渲染失败: ${e.message}")
            null
        }
    }

    // ====================================================================
    // 预览状态更新
    // ====================================================================

    private fun updatePreviewDisplayState(text: String, color: Int?, animation: AnimationType) {
        if (text.isBlank()) return
        val charBitmaps = LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
        val displayColor = if (color != null) ComposeColor(color) else ComposeColor.White
        _currentDisplayState.value = DisplayState(
            text = text,
            color = displayColor,
            animCode = animation.code,
            bitmapData = charBitmaps
        )
    }

    // ====================================================================
    // 自动显示引擎
    // ====================================================================

    data class AutoDisplayData(
        val isOnroad: Boolean = false,
        val isNavigating: Boolean = false,
        val active: Boolean = false,
        val xState: Int = 0,
        val vEgoKph: Int = 0,
        val nSdiDist: Int = 0,
        val nSdiSpeedLimit: Int = 0,
        val nSdiType: Int = -1,
        val nTBTDist: Int = 0,
        val nTBTTurnType: Int = -1,
        val szTBTMainText: String = "",
        val trafficState: Int = 0,
        val nRoadLimitSpeed: Int = 0,
        val leftBlindspot: Boolean = false,
        val rightBlindspot: Boolean = false,
        val leadDistance: Float = 0f,
        val leadProb: Float = 0f,
        val steeringAngleDeg: Float = 0f,
        val nGoPosDist: Int = 0,
        val atcType: String = "",
        val vTurnSpeed: Double = 0.0,
    )

    fun setAutoDisplayEnabled(enabled: Boolean) {
        autoDisplayEnabled = enabled
    }

    fun updateAutoDisplay(data: AutoDisplayData) {
        if (!autoDisplayEnabled) return

        speedHistory.add(data.vEgoKph)
        if (speedHistory.size > 6) speedHistory.removeAt(0)

        val now = System.currentTimeMillis()
        if (data.vEgoKph == 0 && data.isOnroad) {
            if (stoppedSince == 0L) stoppedSince = now
        } else {
            stoppedSince = 0L
        }

        val command = resolveDisplay(data)

        val isSame = when (command) {
            is DisplayCommand.Text -> command.text == lastAutoText && command.color == lastAutoColor && command.animation == lastAutoAnim
            is DisplayCommand.BlueLine -> lastDisplayIsBlueLine && command.animation == lastBlueLineAnim
        }
        if (isSame && (now - lastAutoSendTime) < 10_000) return

        when (command) {
            is DisplayCommand.Text -> {
                if (command.text.isNotBlank()) {
                    lastAutoText = command.text
                    lastAutoColor = command.color
                    lastAutoAnim = command.animation
                    lastDisplayIsBlueLine = false
                    lastAutoSendTime = now
                    updatePreviewDisplayState(command.text, command.color, command.animation)
                    if (connected) sendText(command.text, command.color, command.animation)
                }
            }
            is DisplayCommand.BlueLine -> {
                lastDisplayIsBlueLine = true
                lastBlueLineAnim = command.animation
                lastAutoSendTime = now
                if (connected) sendDebugBlueLine(command.animation)
            }
        }
    }

    private fun turnTypeToXTurnInfo(nTBTTurnType: Int): Int {
        return when (nTBTTurnType) {
            12, 16 -> 1
            13, 19 -> 2
            102, 105, 112, 115, 7, 44, 17, 75, 76, 118 -> 3
            101, 104, 111, 114, 6, 43, 73, 74, 123, 124, 117 -> 4
            131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142 -> 5
            153, 154, 249 -> 6
            14 -> 7
            201 -> 8
            else -> -1
        }
    }

    private fun resolveDisplay(d: AutoDisplayData): DisplayCommand {
        val now = System.currentTimeMillis()

        if (!d.isOnroad && !d.isNavigating) {
            if (!welcomeShown) {
                welcomeShown = true
                return DisplayCommand.Text("CP搭子 Carrot Pilot智驾领航外挂", 0xFF33CCFF.toInt(), AnimationType.SCROLL_LEFT)
            }
            if (now - lastAutoSendTime > 30_000) {
                return DisplayCommand.Text("CP搭子", 0xFF33CCFF.toInt(), AnimationType.STATIC)
            }
            return DisplayCommand.Text(lastAutoText.ifEmpty { "CP搭子" }, 0xFF33CCFF.toInt(), AnimationType.STATIC)
        }

        val hasLead = d.leadDistance in 1f..30f && d.leadProb > 0.5f

        if (d.leftBlindspot || d.rightBlindspot) {
            return DisplayCommand.Text("注意避让", 0xFFFF3333.toInt(), AnimationType.BREATHE)
        }

        if (d.nSdiDist in 20..200 && d.nSdiSpeedLimit > 0 && d.nSdiType >= 0) {
            val over = d.vEgoKph > d.nSdiSpeedLimit
            val color = if (over) 0xFFFF0000.toInt() else 0xFFFFAA00.toInt()
            return DisplayCommand.Text("测速${d.nSdiSpeedLimit}", color, if (over) AnimationType.BREATHE else AnimationType.STATIC)
        }

        if (d.nSdiDist in 201..500 && d.nSdiSpeedLimit > 0 && d.nSdiType >= 0) {
            return DisplayCommand.Text("测速${d.nSdiSpeedLimit}", 0xFFFFDD00.toInt(), AnimationType.STATIC)
        }

        if (d.vEgoKph == 0 && d.trafficState == 1 && !hasLead) {
            return DisplayCommand.Text("红灯停", 0xFFFF0000.toInt(), AnimationType.STATIC)
        }
        if (d.vEgoKph == 0 && d.trafficState == 2 && !hasLead) {
            return DisplayCommand.Text("绿灯行", 0xFF00FF00.toInt(), AnimationType.STATIC)
        }

        if (d.vEgoKph == 0 && d.isOnroad && stoppedSince > 0 && (now - stoppedSince) > 3000) {
            return DisplayCommand.Text("停车等待", 0xFFFF4444.toInt(), AnimationType.STATIC)
        }

        if (isDecelerating(d.vEgoKph)) {
            return DisplayCommand.Text("正在减速", 0xFFFF6600.toInt(), AnimationType.STATIC)
        }
        if ((d.atcType.isNotEmpty() && d.atcType != "none") ||
            (d.vTurnSpeed > 0 && d.vEgoKph > 20 && d.vTurnSpeed < d.vEgoKph - 5)) {
            return DisplayCommand.Text("弯道减速", 0xFFFF8800.toInt(), AnimationType.STATIC)
        }
        if (d.vEgoKph > 10 && kotlin.math.abs(d.steeringAngleDeg) > 90) {
            return if (d.steeringAngleDeg > 0) {
                DisplayCommand.Text("正在左转", 0xFF00CCFF.toInt(), AnimationType.STATIC)
            } else {
                DisplayCommand.Text("正在右转", 0xFF33FF66.toInt(), AnimationType.STATIC)
            }
        }

        if (d.nTBTDist in 5..300 && d.nTBTTurnType > 0) {
            val xTurn = turnTypeToXTurnInfo(d.nTBTTurnType)
            val result = resolveTurnDisplay(d, xTurn)
            if (result != null) return result
        }

        if (d.nGoPosDist in 1..200) {
            return DisplayCommand.Text("即将到达", 0xFF00FF00.toInt(), AnimationType.STATIC)
        }

        if (d.isOnroad && d.active) {
            return DisplayCommand.BlueLine(AnimationType.BREATHE)
        }

        if (d.isNavigating) {
            return if (d.szTBTMainText.isNotBlank()) {
                val anim = if (d.szTBTMainText.length > 4) AnimationType.SCROLL_LEFT else AnimationType.STATIC
                DisplayCommand.Text(d.szTBTMainText, 0xFFAA66FF.toInt(), anim)
            } else {
                DisplayCommand.Text("地图领航", 0xFFAA66FF.toInt(), AnimationType.STATIC)
            }
        }

        if (d.isOnroad) {
            if (d.nRoadLimitSpeed > 0 && d.vEgoKph > d.nRoadLimitSpeed - 5) {
                return DisplayCommand.Text("限速${d.nRoadLimitSpeed}", 0xFFFFFFFF.toInt(), AnimationType.STATIC)
            }
            return DisplayCommand.BlueLine(AnimationType.STATIC)
        }

        return DisplayCommand.Text(lastAutoText.ifEmpty { "CP搭子" }, 0xFF33CCFF.toInt(), AnimationType.STATIC)
    }

    private fun turnTypeToDisplayInfo(xTurn: Int): Pair<String, Int>? {
        return when (xTurn) {
            7 -> "掉头" to 0xFFFF8800.toInt()
            1 -> "即将左转" to 0xFF00CCFF.toInt()
            2 -> "即将右转" to 0xFF33FF66.toInt()
            3 -> "向左变道" to 0xFF00CCFF.toInt()
            4 -> "向右变道" to 0xFF33FF66.toInt()
            5 -> "进入环岛" to 0xFFCC66FF.toInt()
            8 -> "即将到达" to 0xFF00FF00.toInt()
            else -> null
        }
    }

    private fun resolveTurnDisplay(d: AutoDisplayData, xTurn: Int): DisplayCommand? {
        val info = turnTypeToDisplayInfo(xTurn)
        if (d.nTBTDist in 5..150 && info != null) {
            val (text, color) = info
            return if (d.szTBTMainText.isNotBlank()) {
                DisplayCommand.Text("$text ${d.szTBTMainText}", color, AnimationType.SCROLL_LEFT)
            } else {
                DisplayCommand.Text(text, color, AnimationType.STATIC)
            }
        }
        if (d.nTBTDist in 151..300 && info != null) {
            val (text, color) = info
            return DisplayCommand.Text(text, color, AnimationType.STATIC)
        }
        return null
    }

    private fun isDecelerating(currentSpeed: Int): Boolean {
        if (speedHistory.size < 4 || currentSpeed < 15) return false
        val oldest = speedHistory.first()
        val drop = oldest - currentSpeed
        if (drop < 8) return false
        return currentSpeed <= speedHistory.drop(1).minOrNull() ?: currentSpeed
    }
}
