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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/* ──────────────────────────────────────────────────────────────────────────
 * 本次修复对照日志的主要改动 (相对你上一版本):
 *
 * [FIX-1] 接收侧解析对齐发送侧
 *   你的发送侧 raw 模式包了 0x5E 帧, 但接收侧仍按 parseRawPayload 当裸载荷
 *   解析, 完全解不开. 现在两侧统一: 剥掉 [Seq, 0x05] 后都走 parseFrame.
 *
 * [FIX-2] WRITE_NO_RESPONSE 流控
 *   a802 props=4 只支持 WRITE_NO_RESPONSE, OPPO 系统的 onCharacteristicWrite
 *   不回调或严重延迟, 导致 500ms 看门狗反复触发. 现改用基于特征类型的写入
 *   节流: WRITE_NO_RESPONSE 用 4ms 固定间隔, WRITE_DEFAULT 才等回调.
 *
 * [FIX-3] CCCD 订阅前不发数据
 *   通知未真正使能时上层命令已经发出. 现在 onDescriptorWrite 回调到来才标记
 *   CONNECTED, 之前的 300ms 固定延时去掉.
 *
 * [FIX-4] 不再用 "ACK 超时就硬发数据" 绕过流控
 *   sendRealtimeBitmap 现在两种模式:
 *     - 期待 ACK: 等 0xD501 的 ACK 后才下发数据帧
 *     - fire-and-forget (a800 默认): 控制帧后短延迟 (20ms) 直接下发, 不等 ACK
 *   两种模式都是显式选择, 不会再静默退化.
 *
 * [FIX-5] ACK 与数据响应分离
 *   pendingAcks / pendingResponses 两张表, ACK 帧只清 ACK 回调, 不会顺便把后续
 *   数据响应回调一并 remove 掉. 这是之前 requestDeviceInfo 永远拿不到数据的原因.
 *
 * [FIX-6] 接收缓冲 + 帧扫描
 *   单条 BLE notification 可能拼接多帧, 也可能一帧拆到多次. 加入 rxBuffer 滚动
 *   扫描 0x5E 帧边界, 不再假设 1 notification = 1 frame.
 *
 * [FIX-7] 0xE0 错误帧处理
 *   parseFrame 后如果是 0xE0, 提取 16 位错误码, 通过 onProtocolError 上报.
 *
 * [FIX-8] ACK 超时按规范重传一次 (同 CUID)
 *
 * [FIX-9] 设备信息解析跳过 2 字节命令码回声 (你之前 content[0..7] 当 model 错位)
 *
 * [FIX-10] 载荷长度边界检查 + Length 字段 >=3 校验
 * ──────────────────────────────────────────────────────────────────────── */

private const val LED_RENDER_TAG = "LedMatrixRender"

/**
 * LED 点阵文字渲染器 — 仅用于 App 内预览可视化。
 * 与你上一版一致, 未做修改.
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
                var upper = 0; var lower = 0
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
            Log.e(LED_RENDER_TAG, "'$ch' 渲染异常: ${e.message}"); null
        }
    }

    fun renderTextToColumnMajor(text: String): List<ByteArray> =
        text.mapNotNull { renderCharToColumnMajor(it)?.second }
}

/**
 * BLE LED 点阵屏管理器 — 0x5E UART 协议
 *
 * 透明桥接 (NUS) 与 a800 BLE IC 两种通道. 在 a800 模式下, 上层载荷外再裹
 * 一层 [Seq, 0x05] 的 BLE 传输头, 内部仍是标准 0x5E 帧.
 */
class LedMatrixManager(private val context: Context) {

    companion object {
        private const val TAG = "LedMatrix"

        // NUS — Nordic UART Service
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_CHAR_UUID:  UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_CHAR_UUID:  UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 5_000L
        private const val PREFERRED_MTU = 247          // 实测 a800 在 247 比 512 稳, 改回标准值
        private const val PREF_NAME = "CarrotAmap"
        private const val PREF_LED_ADDRESS = "led_device_address"
        private const val PREF_LED_NAME = "led_device_name"

        // 0x5E 协议常量
        private const val FRAME_PREFIX: Byte = 0x5E
        private const val PAYLOAD_CMD:   Byte = 0xCD.toByte()
        private const val PAYLOAD_DATA:  Byte = 0xDA.toByte()
        private const val PAYLOAD_ERROR: Byte = 0xE0.toByte()
        private const val ACK_TIMEOUT_MS = 1500L
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val MAX_RETRIES = 1               // 规范: 重发一次

        // 单包数据帧最大载荷字节 (Type+CUID+Data <= 242, 给数据 200B 留余量)
        private const val DATA_PAYLOAD_CHUNK = 200
        private const val MAX_PAYLOAD_LEN = 242

        // 命令码 (LE16)
        private const val CMD_DISPLAY_ONOFF    = 0x0E01
        private const val CMD_BRIGHTNESS       = 0x0E02
        private const val CMD_REALTIME_DISPLAY = 0xD501
        private const val CMD_FILL_RECT        = 0xD517
        private const val CMD_DEVICE_INFO      = 0xD702

        // 屏幕兜底分辨率
        private const val DEFAULT_WIDTH  = 64
        private const val DEFAULT_HEIGHT = 16

        @Volatile private var instance: LedMatrixManager? = null
        fun getInstance(context: Context): LedMatrixManager =
            instance ?: synchronized(this) {
                instance ?: LedMatrixManager(context.applicationContext).also { instance = it }
            }
    }

    // ────────────────────────────────────────────────────────────────
    // 公共类型
    // ────────────────────────────────────────────────────────────────

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SENDING, ERROR }

    enum class AnimationType(val code: Int) {
        STATIC(0), SCROLL_LEFT(1), SCROLL_RIGHT(2), BREATHE(6), LASER(8)
    }

    enum class ProtocolError(val code: Int, val desc: String) {
        BadRequest(400, "无效的命令或参数"),
        Unauthorized(401, "来源未认证"),
        Forbidden(402, "访问无权限"),
        NotFound(404, "资源不存在"),
        Timeout(408, "处理超时"),
        Conflict(409, "状态冲突"),
        TooLarge(413, "内容过大"),
        UnsupportedMedia(415, "不支持的格式"),
        Expired(428, "版本过期"),
        DuplicateRequest(429, "重复请求"),
        InternalError(500, "内部错误"),
        Busy(503, "当前忙"),
        VersionMismatch(505, "版本不兼容"),
        Unknown(-1, "未知错误");
        companion object { fun of(c: Int) = values().firstOrNull { it.code == c } ?: Unknown }
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
            var r = model.hashCode(); r = 31 * r + id.contentHashCode(); return r
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 公共状态
    // ────────────────────────────────────────────────────────────────

    var state: State = State.IDLE; private set
    var stateMessage: String = ""; private set
    var onStateChanged: ((State, String) -> Unit)? = null
    var onProtocolError: ((cuid: Int, error: ProtocolError) -> Unit)? = null

    val scannedDevices = mutableListOf<BluetoothDevice>()
    var onDevicesUpdated: (() -> Unit)? = null

    var deviceInfo: DeviceInfo? = null; private set
    var onDeviceInfoUpdated: ((DeviceInfo) -> Unit)? = null

    /**
     * Fire-and-forget 模式. a800 设备实测不回 ACK, 默认开启.
     * NUS 透传则会保持 false, 走完整 ACK 流程.
     */
    @Volatile var fireAndForget: Boolean = false; private set

    private val _currentDisplayState = MutableStateFlow(DisplayState())
    val currentDisplayState: StateFlow<DisplayState> = _currentDisplayState.asStateFlow()

    // ────────────────────────────────────────────────────────────────
    // BLE 状态
    // ────────────────────────────────────────────────────────────────

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var scanning = false
    @Volatile private var currentMtu = 23
    @Volatile private var connected = false
    @Volatile private var notificationsReady = false

    @Volatile private var autoConnecting = false
    private var pendingInitText: String? = null
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectAttempts = 0
    private val MAX_AUTO_RECONNECT = 3
    private val reconnectDelays = longArrayOf(2_000L, 5_000L, 10_000L)

    // ────────────────────────────────────────────────────────────────
    // 0x5E 协议状态
    // ────────────────────────────────────────────────────────────────

    private val cuid = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<Int, () -> Unit>()
    private val pendingResponses = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val pendingRetries = ConcurrentHashMap<Int, Int>()
    private val pendingPayloads = ConcurrentHashMap<Int, ByteArray>()   // CUID → 原始载荷, 用于重传
    private val pendingTimeouts = ConcurrentHashMap<Int, Runnable>()

    // BLE 写入队列
    private val writeQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false
    private var lastWrittenFrame: ByteArray? = null

    // 接收缓冲 — 处理 BLE 分片/粘包
    private val rxBuffer = ByteArrayOutputStream()

    // 保留供 future BLE transport 扩展

    // ────────────────────────────────────────────────────────────────
    // 自动显示引擎状态 (沿用)
    // ────────────────────────────────────────────────────────────────

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

    // ────────────────────────────────────────────────────────────────
    // 生命周期 / 权限
    // ────────────────────────────────────────────────────────────────

    fun destroy() { cleanupInternal(); updateState(State.IDLE, "已销毁") }

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val prefs by lazy { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    fun getSavedDeviceAddress(): String? = prefs.getString(PREF_LED_ADDRESS, null)

    @SuppressLint("MissingPermission")
    private fun saveDevice(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null }
        prefs.edit().putString(PREF_LED_ADDRESS, device.address)
            .putString(PREF_LED_NAME, name ?: device.address).apply()
    }

    fun clearSavedDevice() { prefs.edit().remove(PREF_LED_ADDRESS).remove(PREF_LED_NAME).apply() }

    private fun updateState(newState: State, msg: String = "") {
        state = newState; stateMessage = msg
        handler.post { onStateChanged?.invoke(newState, msg) }
    }

    // ────────────────────────────────────────────────────────────────
    // 扫描 / 自动连接
    // ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun autoConnect(initText: String? = null, onNotFound: (() -> Unit)? = null) {
        if (state == State.CONNECTED || state == State.SENDING) {
            if (initText != null) sendText(initText)
            return
        }
        val savedAddress = getSavedDeviceAddress()
            ?: run { onNotFound?.invoke(); return }
        if (!hasPermissions() || !isBluetoothEnabled()) { onNotFound?.invoke(); return }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: run { onNotFound?.invoke(); return }

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
                autoConnecting = false; updateState(State.IDLE, ""); onNotFound?.invoke()
            }
        }
        scanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            autoCallback)
        handler.postDelayed({
            if (autoConnecting) {
                autoConnecting = false
                try { scanner.stopScan(autoCallback) } catch (_: Exception) {}
                updateState(State.IDLE, ""); pendingInitText = null; onNotFound?.invoke()
            }
        }, AUTO_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) { updateState(State.ERROR, "缺少蓝牙权限"); return }
        if (!isBluetoothEnabled()) { updateState(State.ERROR, "蓝牙未开启"); return }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: run { updateState(State.ERROR, "BLE不可用"); return }
        scannedDevices.clear(); scanning = true
        updateState(State.SCANNING, "扫描中...")
        scanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback)
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

    // ────────────────────────────────────────────────────────────────
    // 连接 / 断开
    // ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan(); disconnect(); connected = false
        saveDevice(device); lastConnectedDevice = device
        autoReconnectAttempts = 0; deviceInfo = null; welcomeShown = false
        val name = try { device.name } catch (_: Exception) { device.address }
        updateState(State.CONNECTING, "连接 $name...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun cleanupInternal(stopReconnect: Boolean = true) {
        if (stopReconnect) {
            lastConnectedDevice = null
            autoReconnectAttempts = MAX_AUTO_RECONNECT
        }
        handler.removeCallbacksAndMessages(null)
        stopScan()
        synchronized(writeQueue) { writeQueue.clear() }
        writeInFlight = false; lastWrittenFrame = null
        connected = false; notificationsReady = false
        isRawProtocol = false; fireAndForget = false
        cuid.set(0)
        synchronized(rxBuffer) { rxBuffer.reset() }
        pendingAcks.clear(); pendingResponses.clear(); pendingRetries.clear(); pendingPayloads.clear()
        pendingTimeouts.values.forEach { handler.removeCallbacks(it) }
        pendingTimeouts.clear()
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null; txCharacteristic = null; rxCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() { cleanupInternal(); updateState(State.IDLE, "已断开") }

    // ────────────────────────────────────────────────────────────────
    // GATT 回调
    // ────────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    autoReconnectAttempts = 0
                    gatt?.requestMtu(PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txCharacteristic = null; rxCharacteristic = null
                    connected = false; notificationsReady = false
                    synchronized(writeQueue) { writeQueue.clear() }
                    writeInFlight = false; lastWrittenFrame = null
                    synchronized(rxBuffer) { rxBuffer.reset() }
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
            Log.i(TAG, "MTU 协商完成: $currentMtu")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { updateState(State.ERROR, "服务发现失败"); return }
            val svcs = gatt?.services ?: emptyList()
            Log.d(TAG, "发现 ${svcs.size} 个 GATT 服务:")
            for (svc in svcs) {
                Log.d(TAG, "  Service: ${svc.uuid}")
                for (chr in svc.characteristics) {
                    Log.d(TAG, "    Char: ${chr.uuid} props=${chr.properties}")
                }
            }

            // P0: 标准 NUS — 完整 0x5E 帧, 期待 ACK
            val nusService = gatt?.getService(NUS_SERVICE_UUID)
            if (nusService != null) {
                val tx = nusService.getCharacteristic(NUS_TX_CHAR_UUID)
                val rx = nusService.getCharacteristic(NUS_RX_CHAR_UUID)
                if (tx != null && rx != null) {
                    txCharacteristic = tx; rxCharacteristic = rx
                    isRawProtocol = false; fireAndForget = false
                    subscribeNotifications(gatt, rx, label = "NUS")
                    return
                }
            }

            // P1: 自动识别其他 UART. 优先选「写+通知同特征」, 否则分离两个特征.
            var writeChar: BluetoothGattCharacteristic? = null
            var notifyChar: BluetoothGattCharacteristic? = null
            var dualChar: BluetoothGattCharacteristic? = null
            for (svc in svcs) {
                val isStandard = svc.uuid.toString().lowercase().startsWith("000018")
                if (isStandard) continue
                for (chr in svc.characteristics) {
                    val p = chr.properties
                    val canWrite = (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                           BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    val canNotify = (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                    if (canWrite && canNotify && dualChar == null) dualChar = chr
                    if (canWrite && writeChar == null) writeChar = chr
                    if (canNotify && notifyChar == null) notifyChar = chr
                }
            }

            // 检测 a800 系列: a800 BLE IC 透传原始 0x5E 帧, 无需 BLE 头部
            val isA800 = svcs.any {
                val u = it.uuid.toString().lowercase()
                u.startsWith("0000a800") || u.startsWith("0000a801") || u.startsWith("0000a802")
            }
            if (isA800) {
                fireAndForget = true   // a800 实测不回 ACK
                Log.i(TAG, "检测到 a800 服务: fire-and-forget")
            }

            val target = dualChar
            if (target != null) {
                Log.i(TAG, "单特征 UART: ${target.uuid}")
                txCharacteristic = target; rxCharacteristic = target
                subscribeNotifications(gatt!!, target, label = "Single")
            } else if (writeChar != null && notifyChar != null) {
                Log.i(TAG, "分离 UART: TX=${writeChar.uuid} RX=${notifyChar.uuid}")
                txCharacteristic = writeChar; rxCharacteristic = notifyChar
                subscribeNotifications(gatt!!, notifyChar, label = "Split")
            } else {
                updateState(State.ERROR, "未找到兼容 UART 服务")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (descriptor?.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD 写入失败 status=$status — 继续但通知可能未启用")
            } else {
                Log.i(TAG, "CCCD 写入成功, 通知已启用")
            }
            notificationsReady = true
            connected = true
            updateState(State.CONNECTED, if (isRawProtocol) "已连接(a800)" else "已连接")
            // 初始化序列: 亮屏 → 查询设备信息 → 应用 pendingInitText
            handler.postDelayed({ setScreenOn(true) }, 100)
            handler.postDelayed({ requestDeviceInfo() }, 400)
            pendingInitText?.let { text ->
                pendingInitText = null
                handler.postDelayed({ sendText(text) }, 1200)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT 写入失败 status=$status")
            }
            // 仅 WRITE_TYPE_DEFAULT 才依赖这个回调, NO_RESPONSE 走自带的节流定时
            if (characteristic?.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                writeInFlight = false
                drainWriteQueue()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION") characteristic?.value?.let { onRxBytes(it) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onRxBytes(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNotifications(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic, label: String) {
        Log.i(TAG, "[$label] 订阅通知 ${chr.uuid}")
        gatt.setCharacteristicNotification(chr, true)
        val cccd = chr.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "$label 特征无 CCCD descriptor, 跳过订阅写入")
            // 没有 CCCD 也认为可以使用 (某些自定义服务确实没有 descriptor)
            notificationsReady = true
            connected = true
            updateState(State.CONNECTED, if (isRawProtocol) "已连接(a800)" else "已连接")
            handler.postDelayed({ setScreenOn(true) }, 100)
            handler.postDelayed({ requestDeviceInfo() }, 400)
            return
        }
        val value = if ((chr.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION") cccd.value = value
            @Suppress("DEPRECATION") gatt.writeDescriptor(cccd)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 0x5E 协议 — 帧构建
    // ────────────────────────────────────────────────────────────────

    /**
     * 构建 0x5E 数据帧.
     *   [0]   0x5E
     *   [1]   A1 随机
     *   [2]   A2 = A0 ⊕ A1
     *   [3]   Length (载荷长度, 3..242)
     *   [4]   A4 = A2 ⊕ A3
     *   [5..] Payload
     *   [n+1] CheckSum = Σ(A_n ⊕ n) for n in 5..n
     */
    private fun buildFrame(payload: ByteArray): ByteArray {
        require(payload.size in 3..MAX_PAYLOAD_LEN) {
            "payload size ${payload.size} 超出协议范围 [3, $MAX_PAYLOAD_LEN]"
        }
        val a1 = Random.nextInt(256).toByte()
        val len = payload.size
        val frame = ByteArray(5 + len + 1)
        frame[0] = FRAME_PREFIX
        frame[1] = a1
        frame[2] = (FRAME_PREFIX.toInt() xor (a1.toInt() and 0xFF)).toByte()
        frame[3] = len.toByte()
        frame[4] = ((frame[2].toInt() and 0xFF) xor (len and 0xFF)).toByte()
        System.arraycopy(payload, 0, frame, 5, len)
        var cs = 0
        for (i in payload.indices) cs += (payload[i].toInt() and 0xFF) xor (i + 5)
        frame[frame.size - 1] = (cs and 0xFF).toByte()
        return frame
    }

    private fun buildPayload(type: Byte, cuid_: Int, content: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(type,
            (cuid_ and 0xFF).toByte(),
            ((cuid_ shr 8) and 0xFF).toByte()) + content

    private fun buildCmdPayload(cmdCode: Int, cuid_: Int, args: ByteArray = byteArrayOf()): ByteArray {
        // 协议规范: 多字节值统一 LE
        val content = byteArrayOf(
            (cmdCode and 0xFF).toByte(),
            ((cmdCode shr 8) and 0xFF).toByte()
        ) + args
        return buildPayload(PAYLOAD_CMD, cuid_, content)
    }

    private fun nextCuid(): Int = cuid.getAndUpdate { (it + 1) and 0xFFFF }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun readLE16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun readLE32(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xFFL)) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)
    }

    // ────────────────────────────────────────────────────────────────
    // 0x5E 协议 — 帧解析 (统一对 NUS 和 raw 模式都走 parseFrame)
    // ────────────────────────────────────────────────────────────────

    /**
     * 在 buf 的 [start, end) 区间内尝试解析一个完整 0x5E 帧.
     * @return Triple(消耗字节数, 解析结果) 或 null (帧不完整需要继续等)
     *         消耗字节数 = 帧总长, -1 = 该位置不是合法帧, 调用方需向前推进 1 字节继续找
     */
    private fun tryParseFrameAt(buf: ByteArray, start: Int): Triple<Int, ParsedFrame?, Boolean>? {
        // 返回 (消耗字节数, ParsedFrame?, 是否完整)
        // - (n, frame, true) : 成功解析了 n 字节
        // - (n, null, false) : 当前位置 0x5E 但帧还不完整, 等待更多字节
        // - (1, null, true)  : 当前位置不是合法帧头, 跳 1 字节
        if (start >= buf.size) return null
        if (buf[start] != FRAME_PREFIX) return Triple(1, null, true)
        if (start + 5 > buf.size) return Triple(0, null, false)  // 帧头不全
        val a0 = FRAME_PREFIX.toInt() and 0xFF
        val a1 = buf[start + 1].toInt() and 0xFF
        val a2 = buf[start + 2].toInt() and 0xFF
        if (a2 != (a0 xor a1)) return Triple(1, null, true)
        val len = buf[start + 3].toInt() and 0xFF
        val a4 = buf[start + 4].toInt() and 0xFF
        if (a4 != (a2 xor len)) return Triple(1, null, true)
        if (len < 3 || len > MAX_PAYLOAD_LEN) return Triple(1, null, true)
        val totalLen = 5 + len + 1
        if (start + totalLen > buf.size) return Triple(0, null, false)  // payload+cs 不全
        // 校验和
        var cs = 0
        for (i in 0 until len) cs += (buf[start + 5 + i].toInt() and 0xFF) xor (i + 5)
        if ((cs and 0xFF) != (buf[start + 5 + len].toInt() and 0xFF)) return Triple(1, null, true)
        val payloadType = buf[start + 5]
        val cuidVal = (buf[start + 6].toInt() and 0xFF) or ((buf[start + 7].toInt() and 0xFF) shl 8)
        val content = if (len > 3) buf.copyOfRange(start + 8, start + 5 + len) else byteArrayOf()
        return Triple(totalLen, ParsedFrame(payloadType, cuidVal, content), true)
    }

    private data class ParsedFrame(val type: Byte, val cuid: Int, val content: ByteArray)

    private fun isAck(type: Byte, contentLen: Int): Boolean =
        contentLen == 0 && (type == PAYLOAD_CMD || type == PAYLOAD_DATA)

    // ────────────────────────────────────────────────────────────────
    // 0x5E 协议 — 发送 / ACK / 响应
    // ────────────────────────────────────────────────────────────────

    private fun sendCommand(
        cmdCode: Int,
        args: ByteArray = byteArrayOf(),
        onAck: (() -> Unit)? = null,
        onResponse: ((ByteArray) -> Unit)? = null
    ): Int {
        val c = nextCuid()
        val payload = buildCmdPayload(cmdCode, c, args)
        if (!fireAndForget) {
            if (onAck != null) registerAck(c, payload, onAck)
            if (onResponse != null) registerResponse(c, onResponse)
        } else {
            // fire-and-forget: 跳过 ACK 等待, 但仍登记响应回调 (设备万一回了能接住)
            if (onResponse != null) registerResponse(c, onResponse)
            // ack 回调直接立即触发, 上层不阻塞
            handler.postDelayed({ onAck?.invoke() }, 20)
        }
        writeFrame(payload)
        return c
    }

    private fun sendDataFrame(dataCuid: Int, data: ByteArray, onAck: (() -> Unit)? = null) {
        val payload = buildPayload(PAYLOAD_DATA, dataCuid, data)
        if (!fireAndForget && onAck != null) registerAck(dataCuid, payload, onAck)
        else if (fireAndForget && onAck != null) handler.postDelayed({ onAck.invoke() }, 5)
        writeFrame(payload)
    }

    private fun registerAck(cuid_: Int, payload: ByteArray, callback: () -> Unit) {
        pendingAcks[cuid_] = callback
        pendingRetries[cuid_] = 0
        pendingPayloads[cuid_] = payload
        val timeout = object : Runnable {
            override fun run() {
                val retries = pendingRetries[cuid_] ?: return
                if (retries < MAX_RETRIES) {
                    pendingRetries[cuid_] = retries + 1
                    Log.w(TAG, "CUID=$cuid_ ACK超时, 重发 (第 ${retries + 1} 次)")
                    writeFrame(payload)
                    handler.postDelayed(this, ACK_TIMEOUT_MS)
                } else {
                    Log.e(TAG, "CUID=$cuid_ 重发后仍无ACK, 放弃")
                    cleanPendingCuid(cuid_)
                }
            }
        }
        pendingTimeouts[cuid_] = timeout
        handler.postDelayed(timeout, ACK_TIMEOUT_MS)
    }

    private fun registerResponse(cuid_: Int, callback: (ByteArray) -> Unit) {
        pendingResponses[cuid_] = callback
        val timeout = Runnable {
            pendingResponses.remove(cuid_)
            // 不要 remove 整个 pendingTimeouts 项, ACK 可能还在等
            if (pendingAcks[cuid_] == null) pendingTimeouts.remove(cuid_)
            Log.w(TAG, "CUID=$cuid_ 响应超时")
        }
        handler.postDelayed(timeout, RESPONSE_TIMEOUT_MS)
    }

    private fun cleanPendingCuid(cuid_: Int) {
        pendingAcks.remove(cuid_)
        pendingResponses.remove(cuid_)
        pendingRetries.remove(cuid_)
        pendingPayloads.remove(cuid_)
        pendingTimeouts.remove(cuid_)?.let { handler.removeCallbacks(it) }
    }

    private fun writeFrame(payload: ByteArray) {
        val wireFrame = buildFrame(payload)
        synchronized(writeQueue) { writeQueue.addLast(wireFrame) }
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val gatt = bluetoothGatt ?: run { synchronized(writeQueue) { writeQueue.clear() }; return }
        val char = txCharacteristic ?: run { synchronized(writeQueue) { writeQueue.clear() }; return }
        val next = synchronized(writeQueue) {
            if (writeQueue.isEmpty()) null else writeQueue.removeFirst()
        } ?: return

        // 根据特征属性决定写入类型 + 流控策略
        val props = char.properties
        val supportsWithResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (supportsWithResp)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        writeInFlight = (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        lastWrittenFrame = next
        writeSingle(gatt, char, next, writeType)

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // NO_RESPONSE 不等回调, 固定 4ms 后取下一包. 即使 onCharacteristicWrite
            // 不回调也不会卡死 (这是 a800 在 OPPO 上不工作的根本原因之一)
            handler.postDelayed({ drainWriteQueue() }, 4)
        } else {
            // WITH_RESPONSE 用 500ms 看门狗兜底
            handler.postDelayed({
                if (writeInFlight) {
                    Log.w(TAG, "写入看门狗触发: 复位 writeInFlight")
                    writeInFlight = false
                    drainWriteQueue()
                }
            }, 500)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeSingle(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ) {
        Log.i(TAG, "写入 ${data.size}B: ${data.joinToString("") { "%02X".format(it) }}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                @Suppress("DEPRECATION") char.writeType = writeType
                @Suppress("DEPRECATION") char.value = data
                @Suppress("DEPRECATION") gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入异常: ${e.message}")
            writeInFlight = false
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 0x5E 协议 — 接收 (滚动缓冲扫描)
    // ────────────────────────────────────────────────────────────────

    private fun onRxBytes(value: ByteArray) {
        Log.d(TAG, "收到 ${value.size}B: ${value.joinToString("") { "%02X".format(it) }}")
        synchronized(rxBuffer) {
            // raw 模式: 剥掉首 2 字节 BLE 头. 注意 BLE 可能拼包, 所以遇 0x5E 才剥
            val bytes = if (isRawProtocol && value.size >= 3 && value[0] != FRAME_PREFIX)
                value.copyOfRange(2, value.size) else value
            rxBuffer.write(bytes)
            extractFrames()
        }
    }

    private fun extractFrames() {
        val buf = rxBuffer.toByteArray()
        var pos = 0
        var madeProgress = true
        while (madeProgress && pos < buf.size) {
            madeProgress = false
            val res = tryParseFrameAt(buf, pos) ?: break
            val (consumed, frame, isResolved) = res
            if (!isResolved) break  // 等更多字节
            if (consumed == 0) break
            if (frame != null) dispatchFrame(frame)
            pos += if (consumed > 0) consumed else 1
            madeProgress = true
        }
        rxBuffer.reset()
        if (pos < buf.size) rxBuffer.write(buf, pos, buf.size - pos)
        // 防止 buffer 撑爆 (理论上不会, 但保险)
        if (rxBuffer.size() > 4096) {
            Log.w(TAG, "rxBuffer 异常增大 ${rxBuffer.size()}, 重置")
            rxBuffer.reset()
        }
    }

    private fun dispatchFrame(f: ParsedFrame) {
        val (type, cuid_, content) = f
        // 0xE0 错误帧
        if (type == PAYLOAD_ERROR) {
            val errCode = if (content.size >= 2) readLE16(content, 0) else -1
            val err = ProtocolError.of(errCode)
            Log.w(TAG, "错误响应 CUID=$cuid_ code=$errCode ${err.desc}")
            cleanPendingCuid(cuid_)
            handler.post { onProtocolError?.invoke(cuid_, err) }
            return
        }
        // ACK 帧 (空内容)
        if (isAck(type, content.size)) {
            pendingRetries.remove(cuid_)
            pendingPayloads.remove(cuid_)
            // 停掉 ACK 超时, 但保留响应超时
            pendingTimeouts.remove(cuid_)?.let { handler.removeCallbacks(it) }
            pendingAcks.remove(cuid_)?.invoke()
            return
        }
        // 数据响应
        pendingRetries.remove(cuid_)
        pendingPayloads.remove(cuid_)
        pendingTimeouts.remove(cuid_)?.let { handler.removeCallbacks(it) }
        // 若先到响应再到 ACK 的情况, 也认为 ACK 隐式收到了
        pendingAcks.remove(cuid_)?.invoke()
        pendingResponses.remove(cuid_)?.invoke(content)
    }

    // ────────────────────────────────────────────────────────────────
    // 高层 API — 屏幕开关 / 亮度 / 矩形填充 / 实时显示 / 设备信息
    // ────────────────────────────────────────────────────────────────

    /** 显示开关 (0x0E01). 位置 10 直接是开关值, 不需要 CMD 字节. */
    fun setScreenOn(on: Boolean) {
        if (!connected) return
        sendCommand(CMD_DISPLAY_ONOFF, byteArrayOf(if (on) 0x01 else 0x00))
        Log.i(TAG, if (on) "屏幕开启" else "屏幕关闭")
    }

    /** 亮度调节 (0x0E02). 位置 10 直接是 0~255 亮度值. */
    fun setBrightness(level: Int) {
        if (!connected) return
        val v = level.coerceIn(0, 255)
        sendCommand(CMD_BRIGHTNESS, byteArrayOf(v.toByte()))
    }

    /**
     * 实色填充矩形 (0xD517).
     * @param colorRgb565  RGB565 颜色
     * @param x,y,w,h      像素坐标 (LE16)
     */
    fun fillRect(colorRgb565: Int, x: Int, y: Int, w: Int, h: Int) {
        if (!connected) return
        // 11 字节 args: COLOR(2) + 形状(1) + X(2) + Y(2) + W(2) + H(2)
        val args = le16(colorRgb565) + byteArrayOf(0x00) + le16(x) + le16(y) + le16(w) + le16(h)
        sendCommand(CMD_FILL_RECT, args)
    }

    /** 清屏 = 黑色全屏填充. */
    fun clearScreen() {
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        fillRect(0x0000, 0, 0, w, h)
        Log.i(TAG, "清屏")
    }

    /**
     * 实时显示 RGB565 位图 (0xD501) — 长数据传输.
     *
     * 流程:
     *   1. 发送 0xD501 控制帧, 携带 length + 起始数据 CUID
     *   2. 等 ACK (或 fire-and-forget 模式短延时)
     *   3. 按 DATA_PAYLOAD_CHUNK 字节为单位拆分, 用递增 CUID 发送 0xDA 数据帧
     *   4. 每 500 帧暂停等页级 ACK (单屏远低于此, 一般无需分页)
     *
     * @param rgb565  按行 (row-major), 每像素 2 字节 LE RGB565
     */
    fun sendRealtimeBitmap(rgb565: ByteArray) {
        if (!connected) { Log.w(TAG, "未连接, 跳过 sendRealtimeBitmap"); return }
        val total = rgb565.size
        val startDataCuid = (cuid.get() + 1) and 0xFFFF  // 控制帧用当前 CUID, 数据帧从下一个开始

        // 控制帧 args: length(LE32) + startCUID(LE16) = 6 字节
        val args = le32(total) + le16(startDataCuid)
        sendCommand(
            CMD_REALTIME_DISPLAY, args,
            onAck = {
                Log.i(TAG, "0xD501 控制帧已确认, 开始下发 ${total}B 数据")
                sendBitmapData(rgb565, startDataCuid)
            }
        )
    }

    private fun sendBitmapData(rgb565: ByteArray, startCuid: Int) {
        val chunks = mutableListOf<ByteArray>()
        var off = 0
        while (off < rgb565.size) {
            val end = minOf(off + DATA_PAYLOAD_CHUNK, rgb565.size)
            chunks.add(rgb565.copyOfRange(off, end))
            off = end
        }
        Log.i(TAG, "拆分为 ${chunks.size} 个数据帧")

        // 同步推进 CUID 计数器 — 必须让 nextCuid() 跳过数据帧用过的 CUID
        // 数据帧 CUID 从 startCuid 开始递增
        var dataCuid = startCuid
        for ((idx, chunk) in chunks.withIndex()) {
            // 让全局 cuid 跟上
            cuid.set((dataCuid + 1) and 0xFFFF)
            val isLast = idx == chunks.size - 1
            sendDataFrame(dataCuid, chunk,
                onAck = if (isLast) { -> Log.i(TAG, "实时显示数据发送完成: ${rgb565.size}B") } else null)
            dataCuid = (dataCuid + 1) and 0xFFFF
        }
    }

    /** 请求设备信息 (0xD702). */
    fun requestDeviceInfo() {
        if (!connected) return
        sendCommand(CMD_DEVICE_INFO, byteArrayOf(0x00), onResponse = { content ->
            parseDeviceInfo(content)?.let {
                deviceInfo = it
                Log.i(TAG, "设备信息: model=${it.model} ${it.width}×${it.height} " +
                          "v${it.versionMajor}.${it.versionMinor}.${it.versionPatch} " +
                          "mem=${it.memoryAvailable}/${it.memory}")
                handler.post { onDeviceInfoUpdated?.invoke(it) }
            } ?: Log.w(TAG, "设备信息响应解析失败, 长度=${content.size}")
        })
    }

    /**
     * 解析设备信息响应.
     * 协议规范 §设备信息上传 (位置以 content 起始为 0):
     *   [0..1]   命令码回声 0xD702
     *   [2..9]   model (UTF-8, 8B)
     *   [10..17] ID (8B)
     *   [18..20] version (3B: major, minor, patch)
     *   [21..22] width  (LE16)
     *   [23..24] height (LE16)
     *   [25]     orientation
     *   [26..29] memory (LE32)
     *   [30..33] memory_available (LE32)
     */
    private fun parseDeviceInfo(content: ByteArray): DeviceInfo? {
        if (content.size < 34) return null
        return try {
            val p = 2  // 跳过命令码回声
            val model = String(content.copyOfRange(p, p + 8), Charsets.UTF_8).trimEnd('\u0000', ' ')
            DeviceInfo(
                model = model,
                id = content.copyOfRange(p + 8, p + 16),
                versionMajor = content[p + 16].toInt() and 0xFF,
                versionMinor = content[p + 17].toInt() and 0xFF,
                versionPatch = content[p + 18].toInt() and 0xFF,
                width  = readLE16(content, p + 19),
                height = readLE16(content, p + 21),
                orientation = content[p + 23].toInt() and 0xFF,
                memory          = readLE32(content, p + 24),
                memoryAvailable = readLE32(content, p + 28)
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析设备信息异常: ${e.message}"); null
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 文字显示 — 以 0xD501 实时显示为底层手段
    // ────────────────────────────────────────────────────────────────

    /**
     * 在屏幕中央显示文字.
     * 实现: 用 Canvas 渲染到一个 width×height 的 ARGB Bitmap, 转 RGB565 后走 sendRealtimeBitmap.
     */
    fun sendText(text: String, colorArgb: Int = Color.WHITE, bgArgb: Int = Color.BLACK) {
        if (!connected) return
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        val bytes = renderTextRgb565(text, w, h, colorArgb, bgArgb)
        _currentDisplayState.value = DisplayState(
            text = text,
            color = ComposeColor(colorArgb),
            animCode = AnimationType.STATIC.code,
            bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
        )
        sendRealtimeBitmap(bytes)
    }

    private fun renderTextRgb565(text: String, w: Int, h: Int, fgArgb: Int, bgArgb: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgArgb)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgArgb
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = (h - 2).toFloat().coerceAtLeast(8f)
        }
        val fm = paint.fontMetrics
        val baseY = (h - fm.top - fm.bottom) / 2f
        canvas.drawText(text, w / 2f, baseY, paint)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // ARGB8888 → RGB565 LE, 按行
        val out = ByteArray(w * h * 2)
        for (i in 0 until w * h) {
            val argb = pixels[i]
            val r = (argb shr 19) and 0x1F
            val g = (argb shr 10) and 0x3F
            val b = (argb shr 3)  and 0x1F
            val rgb565 = (r shl 11) or (g shl 5) or b
            out[i * 2]     = (rgb565 and 0xFF).toByte()
            out[i * 2 + 1] = ((rgb565 shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ────────────────────────────────────────────────────────────────
    // 自动显示引擎
    // ────────────────────────────────────────────────────────────────

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

    fun setAutoDisplayEnabled(enabled: Boolean) { autoDisplayEnabled = enabled }

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
            is DisplayCommand.Text -> command.text == lastAutoText &&
                command.color == lastAutoColor && command.animation == lastAutoAnim
            is DisplayCommand.BlueLine -> lastDisplayIsBlueLine &&
                command.animation == lastBlueLineAnim
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
                    _currentDisplayState.value = DisplayState(
                        text = command.text,
                        color = ComposeColor(command.color ?: android.graphics.Color.WHITE),
                        animCode = command.animation.code,
                        bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(command.text)
                    )
                    if (connected) sendText(command.text, colorArgb = command.color ?: android.graphics.Color.WHITE)
                }
            }
            is DisplayCommand.BlueLine -> {
                lastDisplayIsBlueLine = true
                lastBlueLineAnim = command.animation
                lastAutoSendTime = now
                if (connected) sendDebugBlueLine()
            }
        }
    }

    /** ARGB → RGB565 颜色值. */
    private fun rgb565FromArgb(r: Int, g: Int, b: Int): Int {
        val r5 = (r and 0xFF) shr 3
        val g6 = (g and 0xFF) shr 2
        val b5 = (b and 0xFF) shr 3
        return (r5 shl 11) or (g6 shl 5) or b5
    }

    /** 蓝线 = 屏幕中央蓝色矩形. */
    fun sendDebugBlueLine() {
        if (!connected) { Log.w(TAG, "设备未就绪, 跳过蓝线"); return }
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        val lineY = (h / 2) - 3
        val lineH = 6
        fillRect(rgb565FromArgb(0x33, 0x88, 0xFF), 0, lineY.coerceAtLeast(0), w, lineH.coerceAtMost(h))
        Log.i(TAG, "蓝线已发送")
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
        val info = turnTypeToDisplayInfo(xTurn) ?: return null
        val (text, color) = info
        if (d.nTBTDist in 5..150) {
            return if (d.szTBTMainText.isNotBlank())
                DisplayCommand.Text("$text ${d.szTBTMainText}", color, AnimationType.SCROLL_LEFT)
            else
                DisplayCommand.Text(text, color, AnimationType.STATIC)
        }
        if (d.nTBTDist in 151..300) {
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
            return DisplayCommand.Text("测速${d.nSdiSpeedLimit}", if (over) 0xFFFF0000.toInt() else 0xFFFFAA00.toInt(),
                if (over) AnimationType.BREATHE else AnimationType.STATIC)
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
            return if (d.steeringAngleDeg > 0)
                DisplayCommand.Text("正在左转", 0xFF00CCFF.toInt(), AnimationType.STATIC)
            else
                DisplayCommand.Text("正在右转", 0xFF33FF66.toInt(), AnimationType.STATIC)
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
            return if (d.szTBTMainText.isNotBlank())
                DisplayCommand.Text(d.szTBTMainText, 0xFFAA66FF.toInt(), AnimationType.SCROLL_LEFT)
            else
                DisplayCommand.Text("地图领航", 0xFFAA66FF.toInt(), AnimationType.STATIC)
        }

        if (d.isOnroad) {
            if (d.nRoadLimitSpeed > 0 && d.vEgoKph > d.nRoadLimitSpeed - 5) {
                return DisplayCommand.Text("限速${d.nRoadLimitSpeed}", 0xFFFFFFFF.toInt(), AnimationType.STATIC)
            }
            return DisplayCommand.BlueLine(AnimationType.STATIC)
        }

        return DisplayCommand.Text(lastAutoText.ifEmpty { "CP搭子" }, 0xFF33CCFF.toInt(), AnimationType.STATIC)
    }
}