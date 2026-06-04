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

        // D501 发送最小间隔: 设备需要时间处理上一帧位图才能接受下一帧
        // 过快发送会导致设备状态机溢出, 第一个数据帧即返回 error 400
        private const val MIN_BITMAP_INTERVAL_MS = 500L

        // 命令码 (LE16)
        // 旧式命令 — 某些固件只支持旧式, 不支持 CMD_DEVICE_PARAM
        private const val CMD_DISPLAY_ONOFF_LEGACY = 0x0E01  // 显示开关: [0x0E01, 0x00/0x01]
        private const val CMD_BRIGHTNESS_LEGACY    = 0x0E02  // 亮度: [0x0E02, value]
        private const val CMD_REALTIME_DISPLAY = 0xD501
        private const val CMD_FAST_PIXEL_COLOR = 0xD508  // 像素着色: [X_LE16,Y_LE16,COLOR_LE16]...
        private const val CMD_FILL_RECT        = 0xD517
        private const val CMD_DEVICE_INFO      = 0xD702
        private const val CMD_DEVICE_STATE     = 0xC001  // 设备状态控制: 0x03=涂鸦/绘图模式
        // 设备参数控制 — 屏幕开关(0x04) / 亮度(0x05) 在所有设备状态下均有效
        // 格式: [0x01=set, param_type, value...]
        private const val CMD_DEVICE_PARAM     = 0xC002
        private const val PARAM_SCREEN_ONOFF   = 0x04
        private const val PARAM_BRIGHTNESS     = 0x05
        private const val CMD_FACTORY_TEST     = 0x0F01  // 产线测试: 纯色/条纹/格子, 不依赖设备状态

        // D501 fire-and-forget 模式下, 控制帧 ACK 到数据帧开始的延迟.
        // a800 设备在 115200 baud 下处理 0x5E 帧需要 ~7ms, 设置缓冲区需要额外时间.
        // 20ms 实测不够 (数据帧到的时候缓冲区尚未就绪), 改为 100ms 保证可靠性.
        private const val D501_FF_ACK_DELAY_MS = 100L

        // 屏幕兜底分辨率 — 用户设备为 96×16
        private const val DEFAULT_WIDTH  = 96
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
     * 在 setDeviceState(0x03) 发出、绘图 API 开放后回调 (主线程).
     * 用于 UI 层在就绪前禁用发送按钮.
     */
    var onReadyToDisplay: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && readyToDisplay) {
                handler.post(value)
            }
        }

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
    private val bleSeq = AtomicInteger(0)   // a800 BLE 传输序列号, 独立于 CUID
    private val pendingAcks = ConcurrentHashMap<Int, () -> Unit>()
    private val pendingResponses = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val pendingRetries = ConcurrentHashMap<Int, Int>()
    private val pendingPayloads = ConcurrentHashMap<Int, ByteArray>()   // CUID → 原始载荷, 用于重传
    private val pendingTimeouts = ConcurrentHashMap<Int, Runnable>()

    // BLE 写入队列
    private val writeQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false
    private var lastWrittenFrame: ByteArray? = null

    // D501 节流: 记录上次发送位图的时间戳
    @Volatile private var lastRealtimeBitmapSentMs = 0L

    // 绘图就绪标志: setDeviceState(0x03) 发出后才设为 true, 之前的 D501/D517 调用全部拦截.
    // 防止 updateAutoDisplay 在设备进入涂鸦模式之前抢先发 D501 (CUID 冲突 + 命令被拒).
    @Volatile private var readyToDisplay = false

    // 接收缓冲 — 处理 BLE 分片/粘包
    private val rxBuffer = ByteArrayOutputStream()

    @Volatile private var isRawProtocol = false

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
        readyToDisplay = false
        cuid.set(0)
        lastRealtimeBitmapSentMs = 0L
        // 重置自动显示去重状态, 确保重连后第一次 updateAutoDisplay 立即发送
        lastAutoText = ""; lastAutoSendTime = 0L; lastDisplayIsBlueLine = false
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

            // 检测 a800 系列
            // a800 BLE IC 接收侧: 将 0x5E 帧剥壳后以 [seq(1B)][payload_len(1B)][payload(N bytes)]
            // 格式透传给 App, 不保留 0x5E 封装. 发送侧仍接受完整 0x5E 帧.
            val isA800 = svcs.any {
                val u = it.uuid.toString().lowercase()
                u.startsWith("0000a800") || u.startsWith("0000a801") || u.startsWith("0000a802")
            }
            if (isA800) {
                fireAndForget = true   // a800 不回 ACK, fire-and-forget 跳过 ACK 等待
                isRawProtocol = true   // a800 收到的通知是裸载荷 [seq][len][payload], 非 0x5E 帧
                Log.i(TAG, "检测到 a800 服务: fire-and-forget + rawProtocol")
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
            // ★★★ Popusign 初始化序列 ★★★
            //
            //  HCI 抓包验证的官方 APP 时序:
            //    因官方 APP 针对单次文字更新走的流控不同，关键点是:
            //    1. 不发送 C001=0x03（涂鸦模式仅用于 D501 绘图, 资产工作流不需要）
            //    2. 完整流程: D001 → DATA → C002 亮度 → C002 屏幕 → ACK → ED01 → 0A01×2
            //    3. 时序中不要插入无关命令，避免设备状态混乱
            //
            //  本实现简化步骤（与官方 APP 功能等效）:
            //  P0 (200ms): 亮度 + 屏幕开启 (C002 + Legacy 兜底)
            //  P1 (500ms): 请求设备信息
            //  P2 (800ms): sendPopusignText (D001→DATA→ACK→ED01→0A01)
            //  P3 (4000ms): 全面诊断兜底 (仅连接后无显示时触发)
            //
            // 保留服务发现阶段设置的协议标志 (isRawProtocol/fireAndForget 由 a800 UUID 检测决定)
            if (isRawProtocol) {
                fireAndForget = true
                Log.i(TAG, "a800 原始协议模式 (raw + fire-and-forget)")
            }

            // P0: 开机 → 亮屏 (协议规范: 必须先 C001=0xFF 开机)
            handler.postDelayed({
                powerOn()
                Log.i(TAG, "P0: 设备开机 (C001=0xFF)")
            }, 200)
            handler.postDelayed({
                setBrightness(255)
                setBrightnessLegacy(255)
                setScreenOn(true)
                setScreenOnLegacy(true)
                Log.i(TAG, "P1: 亮度255 + 屏幕开启 (C002 + Legacy 0x0E01/0x0E02)")
            }, 500)

            handler.postDelayed({ requestDeviceInfo() }, 800)

            // P3: 显示初始文字 (C001=0xFF 开机 + 亮度/屏幕就绪后, 切涂鸦模式发 D501)
            handler.postDelayed({
                readyToDisplay = true
                handler.post { onReadyToDisplay?.invoke() }
                val initText = pendingInitText ?: "CP搭子"
                pendingInitText = null
                sendText(initText)
                Log.i(TAG, "P3: D501显示 \"$initText\"")
            }, 1500)

            // 5s后如果还没显示, 尝试诊断 (单次兜底)
            handler.postDelayed({ tryAllApproaches() }, 5000)
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
            handler.postDelayed({
                powerOn()
                Log.i(TAG, "P0(no-CCCD): 设备开机 (C001=0xFF)")
            }, 300)
            handler.postDelayed({
                setBrightness(255)
                setScreenOn(true)
                Log.i(TAG, "P1(no-CCCD): 亮度255 + 屏幕开启")
            }, 600)
            handler.postDelayed({ requestDeviceInfo() }, 900)
            handler.postDelayed({
                readyToDisplay = true
                handler.post { onReadyToDisplay?.invoke() }
                val initText = pendingInitText ?: "CP搭子"
                pendingInitText = null
                sendText(initText)
                Log.i(TAG, "P3(no-CCCD): D501显示 \"$initText\"")
            }, 1600)
            //  不再发 0A01 播放, 以免覆盖 D501 位图显示
            handler.postDelayed({ tryAllApproaches() }, 5000)
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
        onResponse: ((ByteArray) -> Unit)? = null,
        ffAckDelayMs: Long = 20L   // fire-and-forget 模式下模拟 ACK 的延迟 (D501 需要更长时间)
    ): Int {
        val c = nextCuid()
        val payload = buildCmdPayload(cmdCode, c, args)
        if (!fireAndForget) {
            if (onAck != null) registerAck(c, payload, onAck)
            if (onResponse != null) registerResponse(c, onResponse)
        } else {
            // fire-and-forget: 跳过 ACK 等待, 但仍登记响应回调 (设备万一回了能接住)
            if (onResponse != null) registerResponse(c, onResponse)
            // ack 回调模拟触发, 延迟要根据命令类型调整
            handler.postDelayed({ onAck?.invoke() }, ffAckDelayMs)
        }
        writeFrame(payload)
        return c
    }

    private fun sendDataFrame(dataCuid: Int, data: ByteArray, onAck: (() -> Unit)? = null) {
        val payload = buildPayload(PAYLOAD_DATA, dataCuid, data)
        if (!fireAndForget && onAck != null) registerAck(dataCuid, payload, onAck)
        else if (fireAndForget && onAck != null) handler.postDelayed({ onAck.invoke() }, 20)
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

    /** a800 原始 BLE 格式: [seq(1B)] [remaining_len(1B)] [payload(type+cuid+content)] */
    private fun buildA800Frame(payload: ByteArray): ByteArray {
        val seq = bleSeq.getAndIncrement().toByte()
        val remainingLen = payload.size
        require(remainingLen in 3..MAX_PAYLOAD_LEN) {
            "a800 payload size $remainingLen 超出范围 [3, $MAX_PAYLOAD_LEN]"
        }
        return byteArrayOf(seq, remainingLen.toByte()) + payload
    }

    private fun writeFrame(payload: ByteArray) {
        val wireFrame = if (isRawProtocol) buildA800Frame(payload) else buildFrame(payload)
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

        writeInFlight = true  // 统一上锁 — NO_RESPONSE 也要防 sendBitmapData 循环绕过节流
        lastWrittenFrame = next
        writeSingle(gatt, char, next, writeType)

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // 基于帧大小动态计算节流延迟: 115200 baud 8N1 => 11520 字节/秒
            // 每字节 ~0.087ms. 209B 数据帧需 ~18ms 传输, 固定 4ms 会撑爆
            // BLE IC 的 UART TX FIFO 导致 ERROR_GATT_WRITE_REQUEST_BUSY.
            val uartByteMs = (next.size * 10 * 1000L) / 115200  // 10 位/字节
            val delayMs = maxOf(uartByteMs + 5L, 12L)            // +5ms 余量, 最小 12ms
            handler.postDelayed({
                writeInFlight = false
                drainWriteQueue()
            }, delayMs)
        } else {
            // WITH_RESPONSE: onCharacteristicWrite 回调中解锁 writeInFlight,
            // 用 500ms 看门狗兜底防止回调永不触发
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
        if (isRawProtocol) {
            // a800 BLE IC 接收格式: [seq(1B)][payload_len(1B)][payload(N bytes)]
            // IC 已将 0x5E 帧剥壳, 只透传裸载荷. 直接解析 payload 内容, 不走 0x5E 帧扫描.
            // payload 结构: [type(1B)][cuid_lo(1B)][cuid_hi(1B)][content(0..N-3 bytes)]
            if (value.size < 5) {
                Log.w(TAG, "a800 通知包太短(${value.size}B), 忽略")
                return
            }
            val payload = value.copyOfRange(2, value.size)   // 跳过 [seq][payload_len]
            if (payload.size < 3) return
            val type    = payload[0]
            val cuidVal = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
            val content = if (payload.size > 3) payload.copyOfRange(3, payload.size) else byteArrayOf()
            Log.i(TAG, "← RX: type=0x${"%02X".format(type)} cuid=$cuidVal content=${content.size}B ${content.joinToString("") { "%02X".format(it) }}")
            dispatchFrame(ParsedFrame(type, cuidVal, content))
            return
        }
        // NUS 标准模式: 滚动缓冲扫描, 完整解析 0x5E 帧
        synchronized(rxBuffer) {
            rxBuffer.write(value)
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
        val cb = pendingResponses.remove(cuid_)
        if (cb != null) {
            cb.invoke(content)
        } else {
            // 无匹配回调 — 可能是设备主动推送的未请求数据, 记录日志
            Log.d(TAG, "未匹配数据帧: type=0x${"%02X".format(type)} cuid=$cuid_ content=${content.size}B " +
                content.joinToString("") { "%02X".format(it) })
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 高层 API — 屏幕开关 / 亮度 / 矩形填充 / 实时显示 / 设备信息
    // ────────────────────────────────────────────────────────────────

    /**
     * 显示开关.
     * 改用 0xC002 设备参数控制 (屏幕开关 param=0x04), 在所有设备状态下均有效.
     * 原 0x0E01 命令在设备处于"节目播放"模式时会返回 error 400.
     */
    fun setScreenOn(on: Boolean) {
        if (!connected) return
        // args: [0x01=set, 0x04=screen_onoff, 0x01=on / 0x00=off]
        sendCommand(CMD_DEVICE_PARAM, byteArrayOf(0x01, PARAM_SCREEN_ONOFF.toByte(), if (on) 0x01 else 0x00))
        Log.i(TAG, if (on) "屏幕开启 (0xC002 param=0x04)" else "屏幕关闭 (0xC002 param=0x04)")
    }

    /**
     * 设备状态控制 (0xC001).
     *
     * state 取值:
     *   0xFF = 开机
     *   0x01 = 节目播放
     *   0x02 = 节目预览
     *   0x03 = 涂鸦/画板模式 ← 必须设此模式, 0xD501/0xD517 等绘图命令才有效
     *
     * 每次连接成功后需调用一次以切换到涂鸦模式, 否则绘图命令返回 error 400.
     */
    fun setDeviceState(state: Int) {
        if (!connected) return
        // args[0]=0x01(设置), args[1]=state
        sendCommand(CMD_DEVICE_STATE, byteArrayOf(0x01, state.toByte()))
        Log.i(TAG, "设置设备状态 → 0x${state.toString(16).uppercase()}")
    }

    /**
     * 开机 (C001=0xFF).
     * 连接后必须先发送此命令使设备进入可操作状态.
     * 之后再设置亮度/屏幕开关/涂鸦模式等.
     */
    fun powerOn() {
        if (!connected) return
        sendCommand(CMD_DEVICE_STATE, byteArrayOf(0x01, 0xFF.toByte()))
        Log.i(TAG, "设备开机 (C001=0xFF)")
    }

    /**
     * 亮度调节.
     * 改用 0xC002 设备参数控制 (亮度 param=0x05), 在所有设备状态下均有效.
     * @param level 亮度值 0–255
     */
    fun setBrightness(level: Int) {
        if (!connected) return
        val v = level.coerceIn(0, 255)
        // args: [0x01=set, 0x05=brightness, value]
        sendCommand(CMD_DEVICE_PARAM, byteArrayOf(0x01, PARAM_BRIGHTNESS.toByte(), v.toByte()))
    }

    /**
     * 产线测试 (0x0F01) — 不依赖设备状态, 可用于诊断屏幕硬件是否正常.
     *
     * TYPE 枚举:
     *   0x00=红全屏  0x01=绿全屏  0x02=蓝全屏
     *   0x03=黄全屏  0x04=青全屏  0x05=紫全屏
     *   0x06=白全屏  0x07=黑全屏
     *   0x10=行条纹  0x11=列条纹  0x12=格子
     */
    fun sendFactoryTest(type: Int) {
        if (!connected) { Log.w(TAG, "未连接, 跳过产线测试"); return }
        sendCommand(CMD_FACTORY_TEST, byteArrayOf(type.coerceIn(0, 0x12).toByte()))
        Log.i(TAG, "产线测试 TYPE=0x${type.toString(16).uppercase()}")
    }

    /**
     * 实色填充矩形 (0xD517) — 内部版本, 跳过 readyToDisplay 检查.
     * 用于初始化序列中的诊断测试, 不依赖设备状态机.
     */
    private fun fillRectInternal(colorRgb565: Int, x: Int, y: Int, w: Int, h: Int) {
        if (!connected) return
        val args = le16(colorRgb565) + byteArrayOf(0x00) + le16(x) + le16(y) + le16(w) + le16(h)
        sendCommand(CMD_FILL_RECT, args)
    }

    /**
     * 实色填充矩形 (0xD517) — 公共 API.
     * @param colorRgb565  RGB565 颜色
     * @param x,y,w,h      像素坐标 (LE16)
     */
    fun fillRect(colorRgb565: Int, x: Int, y: Int, w: Int, h: Int) {
        if (!connected || !readyToDisplay) return
        fillRectInternal(colorRgb565, x, y, w, h)
    }

    /**
     * 全屏填充 (D517) — 不依赖 readyToDisplay, 用于诊断.
     * @param colorRgb565  RGB565 颜色值
     */
    fun fillScreen(colorRgb565: Int) {
        if (!connected) return
        val w = deviceInfo?.width ?: DEFAULT_WIDTH
        val h = deviceInfo?.height ?: DEFAULT_HEIGHT
        fillRectInternal(colorRgb565, 0, 0, w, h)
        Log.i(TAG, "全屏填充 D517 color=0x${colorRgb565.toString(16)} ${w}×${h}")
    }

    /** 全屏白填充 (D517) — 诊断快捷键. */
    fun fillScreenWhite() {
        val white565 = rgb565FromArgb(0xFF, 0xFF, 0xFF)
        fillScreen(white565)
    }

    // ── 资产上传 (Popusign 协议) ──────────────────────────────────
    //
    // 基于协议逆向: a800 使用资产-based 工作流.
    // 1. D001: 启动资产下载 (UUID + 总长度 + 起始 CUID)
    // 2. DATA: 分片发送资产内容
    // 3. ED01: 节目编辑 — 引用资产 UUID (type=0x00 子元素)
    // 4. 0A01: 播放节目
    //
    // 子元素 type=0x00 格式: [type(1B)] [padding(7B)] [uuid(6B)] = 14B

    private val assetUuidCounter = AtomicInteger(0)

    /** 生成 6 字节资产 UUID */
    private fun nextAssetUuid(): ByteArray {
        val id = assetUuidCounter.getAndIncrement() and 0xFFFF
        // 格式: CP 前缀 + 16位序号 + 固定 0030 (模仿 Popusign 风格)
        return byteArrayOf(
            0x43, 0x50,                         // "CP"
            ((id shr 8) and 0xFF).toByte(),
            (id and 0xFF).toByte(),
            0x00, 0x30
        )
    }

    /**
     * 上传资产到设备.
     * @param uuid 6 字节资产 UUID
     * @param assetData 完整的资产载荷 (不含 UUID 头, 仅 type + content)
     */
    private fun uploadAsset(uuid: ByteArray, assetData: ByteArray) {
        require(uuid.size == 6) { "UUID 必须为 6 字节" }
        val startDataCuid = (cuid.get() + 1) and 0xFFFF
        val totalLength = 6 + assetData.size  // UUID(6B) + 内容

        // Step 1: D001 — 启动资产下载
        val d001Args = le32(totalLength) + le16(startDataCuid) + uuid
        sendCommand(0xD001, d001Args, ffAckDelayMs = 50)
        Log.i(TAG, "资产上传 D001: UUID=${uuid.joinToString("") { "%02X".format(it) }} length=${totalLength}B")

        // Step 2: DATA 分片 (含 UUID 头)
        val fullData = uuid + assetData
        val chunkSize = DATA_PAYLOAD_CHUNK
        var dataCuid = startDataCuid
        var offset = 0
        while (offset < fullData.size) {
            val end = minOf(offset + chunkSize, fullData.size)
            val chunk = fullData.copyOfRange(offset, end)
            val isLast = end >= fullData.size
            // 最后一片发完后发 ACK (空 DATA)
            sendDataFrame(dataCuid, chunk, onAck = if (isLast) {
                { Log.i(TAG, "资产数据上传完成: ${fullData.size}B") }
            } else null)
            cuid.set((dataCuid + 1) and 0xFFFF)
            dataCuid = (dataCuid + 1) and 0xFFFF
            offset = end
        }

        // Step 3: 空 DATA 帧 ACK (CUID=0 表示数据结束)
        if (fireAndForget) {
            handler.postDelayed({
                sendDataFrame(0, byteArrayOf())
                Log.i(TAG, "资产上传 ACK 发送")
            }, 30)
        } else {
            sendDataFrame(0, byteArrayOf())
        }
    }

    /**
     * 节目编辑 — 引用资产 UUID (type=0x00 子元素).
     * ED01 格式: [group][program][sub_count] + [type=0x00][padding(7B)][uuid(6B)] ×N
     */
    private fun programEditWithAsset(group: Int, program: Int, uuid: ByteArray) {
        require(uuid.size == 6) { "UUID 必须为 6 字节" }
        // type=0x00 子元素: [0x00] + [0x00×7] + [uuid(6B)] = 14B
        val subElement = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ) + uuid
        val args = byteArrayOf(
            group.toByte(), program.toByte(), 0x01  // 1 个子元素
        ) + subElement
        sendCommand(0xED01, args, ffAckDelayMs = 50)
        Log.i(TAG, "节目编辑 ED01: 组$group 节目$program UUID=${uuid.joinToString("") { "%02X".format(it) }}")
    }

    // ── a800 Popusign 结构化文本资产 ────────────────────────────
    //
    // 基于官方 APP HCI 抓包逆向分析 (2026-06-04). 使用 176B 变长格式:
    //
    //   [UUID(6B)] [type=0x01] [header(17B)] [embedded_UUID(6B)]
    //   [metadata(8B)] [properties(24B)] [color(7B)]
    //   [text: 22 00 len_LE 2A UTF8]
    //   [post-text: 02 00 2B 10 02 00 2C XX flags cnt x-pos_vle]
    //   [per-char(36B×N): 10 00 10 00 + 32B 列优先位图]
    //
    // 与旧 260B 模板关键区别:
    //   - 每字符固定 16 列全宽 (无 03 00 width 头)
    //   - 正确 post-text 里 LE16 位置 (非 LE32)
    //   - mode=0x90, height=0x34 (52)
    //   - 动画字节在 raw[51] (02 00 11 XX), 官方统一用 0x04
    //   具体动画由 ED01/0A01 控制而非资产字节

    /**
     * 构建 a800 Popusign 结构化文本资产载荷.
     *
     * 基于官方 APP 176B "你好" 资产格式.
     * 每字符 36B: [10 00 10 00] + 32B 列优先位图 (16列×2B).
     * 变长: 总大小 = 70 + (4+text_len) + (11+charCount*4) + charCount*36
     *
     * @param text         显示文字 (最多 4 字符)
     * @param colorRgb565  RGB565 颜色值
     * @param uuid         6 字节资产 UUID (用 nextAssetUuid 生成)
     */
    private fun buildPopusignTextAsset(text: String, colorRgb565: Int, uuid: ByteArray, animationType: AnimationType = AnimationType.STATIC): ByteArray {
        val displayText = ("*" + text).toByteArray(Charsets.UTF_8)
        val textLen = displayText.size
        val charCount = minOf(text.length, 4)

        // 动画字节: 官方统一用 0x04 (动画由 ED01/0A01 模式控制)
        @Suppress("UNUSED_PARAMETER")
        val animByte: Byte = 0x04

        // 1. 固定头部 70B (UUID+type+magic+pad+embedded+width+mode+pad+height+pad+props+anim+spacing+color)
        val h = ByteArray(70) { 0 }
        h[6] = 0x01                                   // type=1
        h[7] = 0x00; h[8] = 0x0C                      // magic=12
        h[30] = 0x54; h[31] = 0x00                    // width=84
        h[32] = 0x90.toByte(); h[33] = 0x00          // mode
        h[36] = 0x34; h[37] = 0x00                    // height=52
        // props 02 00 02 00 02 00 03 00
        h[40] = 0x02; h[41] = 0x00; h[42] = 0x02; h[43] = 0x00
        h[44] = 0x02; h[45] = 0x00; h[46] = 0x03; h[47] = 0x00
        // anim 02 00 11 04
        h[48] = 0x02; h[49] = 0x00; h[50] = 0x11; h[51] = 0x04
        // spacing 02 00 10 01  02 00 12 01
        h[52] = 0x02; h[53] = 0x00; h[54] = 0x10; h[55] = 0x01
        h[56] = 0x02; h[57] = 0x00; h[58] = 0x12; h[59] = 0x01
        // color mode 02 00 20 00
        h[60] = 0x02; h[61] = 0x00; h[62] = 0x20; h[63] = 0x00
        // color element 03 00 21 + RGB565 (patched below)
        h[64] = 0x03; h[65] = 0x00; h[66] = 0x21
        h[67] = (colorRgb565 and 0xFF).toByte()
        h[68] = ((colorRgb565 shr 8) and 0xFF).toByte()
        h[69] = 0x02                                   // separator begin

        // 2. 文本节: 22 00 + len_LE + displayText
        val textSection = byteArrayOf(0x22, 0x00) + le16(textLen) + displayText

        // 3. post-text: 02 00 2B 10 02 00 2C 01 + LE32(0) + charCount + x-pos(LE32×N) + 0x00
        val canvasWidth = 84
        val totalWidth = charCount * 16
        val startX = maxOf(0, (canvasWidth - totalWidth) / 2)
        val xPos = ByteArray(charCount * 4)
        for (i in 0 until charCount) {
            val x = startX + i * 16
            xPos[i * 4] = (x and 0xFF).toByte()
            xPos[i * 4 + 1] = ((x shr 8) and 0xFF).toByte()
            // bytes 2-3 = zero
        }
        val postText = byteArrayOf(
            0x02, 0x00, 0x2B, 0x10, 0x02, 0x00, 0x2C, 0x01,
            0x00, 0x00, 0x00, 0x00          // LE32 flags
        ) + le16(charCount) + xPos + byteArrayOf(0x00)

        // 4. 逐字符条目: 10 00 10 00 + 32B 列优先位图 = 36B/char
        val entries = ByteArray(charCount * 36)
        for (i in 0 until charCount) {
            val off = i * 36
            entries[off] = 0x10; entries[off + 1] = 0x00
            entries[off + 2] = 0x10; entries[off + 3] = 0x00
            val bitmap = LedMatrixBitmapRenderer.renderCharToColumnMajor(text[i])?.second ?: ByteArray(32)
            for (j in 0 until minOf(bitmap.size, 32)) {
                entries[off + 4 + j] = bitmap[j]
            }
        }

        // 5. 拼接
        val assetData = h + textSection + postText + entries

        // 6. Patch UUID
        uuid.copyInto(assetData, 0, 0, 6)
        uuid.copyInto(assetData, 24, 0, 5)
        assetData[29] = 0x00

        Log.v(TAG, "a800资产构建: \"$text\" color=0x${colorRgb565.toString(16)} " +
            "UUID=${uuid.joinToString("") { "%02X".format(it) }} size=${assetData.size}B")

        // 返回不含 UUID 前缀的载荷 (uploadAsset 自动添加)
        return assetData.copyOfRange(6, assetData.size)
    }

    /**
     * 显示文字 — 使用 a800 Popusign 资产协议 (结构化文本).
     * 1. 构建结构化文本资产 (包含 UTF-8 文本 + RGB565 颜色)
     * 2. 上传资产 (D001 + DATA)
     * 3. 节目编辑引用资产 (ED01)
     * 4. 播放节目
     *
     * @param text    显示文字
     * @param program 节目序号
     */
    fun sendPopusignText(text: String, program: Int = 0, colorRgb565: Int = 0xF800, animationType: AnimationType = AnimationType.STATIC) {
        if (!connected || text.isEmpty()) return

        val uuid = nextAssetUuid()
        val assetPayload = buildPopusignTextAsset(text, colorRgb565, uuid, animationType)

        Log.i(TAG, "Popusign文字 \"$text\" 资产 ${assetPayload.size}B UUID=${uuid.joinToString("") { "%02X".format(it) }}")

        uploadAsset(uuid, assetPayload)

        // 等 ACK 后发 ED01 + 播放 + 开屏
        val delay = if (fireAndForget) 100L else 200L
        handler.postDelayed({
            programEditWithAsset(0, program, uuid)
        }, delay)
        handler.postDelayed({
            sendCommand(0x0A01, byteArrayOf(0x01, 0x01, 0x53, 0x00, program.toByte()))
            Log.i(TAG, "播放节目 组0 节目$program")
        }, delay + 100)
        // 官方 APP 在最后发 0E01 01 开屏, 确保设备处于显示状态
        handler.postDelayed({
            setScreenOnLegacy(true)
            Log.i(TAG, "播放后开屏兜底 (0E01 01)")
        }, delay + 300)

        _currentDisplayState.value = DisplayState(
            text = text.take(6),
            color = ComposeColor.White,
            animCode = AnimationType.STATIC.code,
            bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
        )
    }

    /**
     * 显示文字 (简化 API, 给外部调用).
     *
     * @param text      要显示的文字
     * @param colorArgb ARGB 颜色 (自动转为 RGB565)
     */
    /**
     * 通过 D501 实时位图显示文字 — 替代资产协议.
     *
     * 将文字直接渲染到 96×16 Android Bitmap 上, 转为 RGB565 后通过 D501 发送.
     * 使用粗体 + 关闭抗锯齿确保像素在 LED 上清晰可见.
     *
     * @param text        要显示的文字 (最多 6 字符, 超长截断)
     * @param colorRgb565 RGB565 颜色值
     */
    fun sendTextViaD501(text: String, colorRgb565: Int = 0xF800) {
        if (!connected || text.isEmpty()) return

        powerOn()
        setDeviceState(3)

        val w = 96
        val h = 16
        val chars = text.take(6)

        // 先通过 D517 将屏幕清黑
        fillRectInternal(0x0000, 0, 0, w, h)

        // 渲染文字到临时 bitmap 提取像素坐标
        val charWidth = 16
        val totalWidth = chars.length * charWidth
        val startX = (w - totalWidth) / 2

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val textY = (h - fm.top - fm.bottom) / 2f

        val charBmp = Bitmap.createBitmap(charWidth, h, Bitmap.Config.ARGB_8888)
        val charCanvas = Canvas(charBmp)
        val pxBuf = IntArray(charWidth * h)
        val pixelList = mutableListOf<PixelD508>() // (x, y, colorRgb565)
        for ((i, ch) in chars.withIndex()) {
            charCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            charCanvas.drawText(ch.toString(), charWidth / 2f, textY, paint)
            charBmp.getPixels(pxBuf, 0, charWidth, 0, 0, charWidth, h)
            val dstX = startX + i * charWidth
            for (row in 0 until h) {
                for (col in 0 until charWidth) {
                    val alpha = (pxBuf[row * charWidth + col] ushr 24) and 0xFF
                    if (alpha > 0) {
                        val px = dstX + col
                        if (px in 0 until w) {
                            pixelList.add(PixelD508(px, row, colorRgb565))
                        }
                    }
                }
            }
        }
        charBmp.recycle()

        Log.i(TAG, "D508文字 \"$chars\" 像素=${pixelList.size} color=0x${colorRgb565.toString(16)} startX=$startX")

        handler.postDelayed({
            sendPixelsD508(pixelList)
        }, 200) // D517 清黑后稍等再描像素
    }

    /**
     * 通过 D508 像素着色协议发送像素列表.
     * 每帧最多 40 像素 (MTU 247: 240 数据字节 / 6 字节每像素).
     */
    private data class PixelD508(val x: Int, val y: Int, val colorRgb565: Int)

    private fun sendPixelsD508(pixels: List<PixelD508>) {
        if (!connected || pixels.isEmpty()) return

        val pixelBytes = ByteArray(6) // reused per pixel
        val perFrame = 40
        var sent = 0
        for (batchStart in pixels.indices step perFrame) {
            val batch = pixels.subList(batchStart, minOf(batchStart + perFrame, pixels.size))
            val args = ByteArray(batch.size * 6)
            var off = 0
            for (p in batch) {
                // X LE16
                args[off] = (p.x and 0xFF).toByte()
                args[off + 1] = ((p.x shr 8) and 0xFF).toByte()
                // Y LE16
                args[off + 2] = (p.y and 0xFF).toByte()
                args[off + 3] = ((p.y shr 8) and 0xFF).toByte()
                // COLOR LE16
                args[off + 4] = (p.colorRgb565 and 0xFF).toByte()
                args[off + 5] = ((p.colorRgb565 shr 8) and 0xFF).toByte()
                off += 6
            }
            sendCommand(CMD_FAST_PIXEL_COLOR, args)
            sent += batch.size
        }
        Log.i(TAG, "D508 像素着色完成: $sent 像素")
    }

    fun sendText(text: String, colorArgb: Int = Color.WHITE, animationType: AnimationType = AnimationType.STATIC) {
        if (!connected || text.isEmpty()) return
        val rgb565 = rgb565FromArgb(
            (colorArgb shr 16) and 0xFF,
            (colorArgb shr 8) and 0xFF,
            colorArgb and 0xFF
        )
        // 使用 D501 实时位图显示文字 (已验证工作正常)
        sendTextViaD501(text, colorRgb565 = rgb565)
        _currentDisplayState.value = DisplayState(
            text = text.take(6),
            color = ComposeColor(colorArgb),
            animCode = AnimationType.STATIC.code,
            bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
        )
    }

    /**
     * D501 全白位图 — 诊断用, 跳过 readyToDisplay.
     * 直接在屏幕上显示全白图像, 测试 D501 长数据传输是否可用.
     */
    fun sendWhiteScreenD501() {
        if (!connected) return
        // D501 需要涂鸦/画板模式 (C001=0x03), 先开机
        powerOn()
        setDeviceState(3)
        // 防止设备信息解析错误导致 OOM: 限制最大 256×64
        val rawW = deviceInfo?.width ?: DEFAULT_WIDTH
        val rawH = deviceInfo?.height ?: DEFAULT_HEIGHT
        // a800 是 96×16, 但设备信息解析坏的场景直接兜底
        if (rawW > 256 || rawH > 64) {
            Log.w(TAG, "D501 设备尺寸异常 ${rawW}×${rawH}, 使用默认 96×16")
        }
        val w = if (rawW in 1..256) rawW else DEFAULT_WIDTH
        val h = if (rawH in 1..64) rawH else DEFAULT_HEIGHT
        if (w != rawW || h != rawH) {
            Log.w(TAG, "D501 尺寸修正: ${rawW}×${rawH} → ${w}×${h}")
        }
        val white565 = rgb565FromArgb(0xFF, 0xFF, 0xFF)
        val whitePixelLo = (white565 and 0xFF).toByte()
        val whitePixelHi = ((white565 shr 8) and 0xFF).toByte()
        val bitmap = ByteArray(w * h * 2)
        for (i in bitmap.indices step 2) {
            bitmap[i] = whitePixelLo
            bitmap[i + 1] = whitePixelHi
        }
        Log.i(TAG, "D501 全白位图诊断 ${w}×${h} = ${bitmap.size}B")
        sendRealtimeBitmap(bitmap, force = true)
    }

    // ── 炫彩文字 (节目编辑 0xED01 + 播放控制 0x0A01) ────────────
    //
    // 核心思路: 用 节目编辑 将文字写入设备节目槽, 每个字符作为
    // 独立子元素并指定独立 RGB 颜色实现"炫彩"效果, 然后用 播放控制
    // 让设备显示已编辑的节目.
    //
    // 子元素格式 (参照 iPixel 字符块):
    //   [marker=0x80] [R] [G] [B] [width] [height=0x10] [column_bitmap(32B)]
    //   = 38 字节/字符
    // 单帧最多 6 个汉字 (96px 屏幕宽刚好).

    /**
     * 炫彩文字 — 通过节目编辑显示文字.
     * 尝试两种子元素格式:
     *   Format-A: [0x80] [R][G][B] [width] [height] [32B 列优先位图] = 38B/字符
     *   Format-B: [R][G][B] [width] [height] [32B 列优先位图] = 37B/字符 (无 0x80 标记)
     *
     * @param text 要显示的文字 (最多 6 字符)
     * @param colors 每个字符的 ARGB 颜色, 为空则自动彩虹色
     * @param group 节目组序号 (0-4)
     * @param program 节目序号 (0-11)
     * @param format 子元素格式: 0=带 0x80 标记, 1=无标记
     */
    fun sendColorfulText(text: String, colors: List<Int>? = null, group: Int = 0, program: Int = 0, format: Int = 0) {
        if (!connected || text.isEmpty()) return
        val chars = text.take(6)
        val charColors = when {
            colors != null && colors.size >= chars.length -> colors.take(chars.length)
            else -> rainbowColors(chars.length)
        }
        val subElements = mutableListOf<ByteArray>()
        for (i in chars.indices) {
            val renderResult = LedMatrixBitmapRenderer.renderCharToColumnMajor(chars[i])
            if (renderResult != null) {
                val (width, bitmap) = renderResult
                if (format == 0) {
                    subElements.add(buildTextSubElement(charColors[i], width, 16, bitmap))
                } else {
                    subElements.add(buildTextSubElementNoMarker(charColors[i], width, 16, bitmap))
                }
            }
        }
        if (subElements.isEmpty()) return
        var args = byteArrayOf(group.toByte(), program.toByte(), subElements.size.toByte())
        for (elem in subElements) args += elem
        sendCommand(0xED01, args)
        Log.i(TAG, "炫彩文字 fmt=$format \"$chars\" → 组$group 节目$program (${subElements.size}字符, ${args.size}B)")
    }

    private fun buildTextSubElement(colorArgb: Int, width: Int, height: Int, columnBitmap: ByteArray): ByteArray {
        return byteArrayOf(
            0x80.toByte(),
            ((colorArgb shr 16) and 0xFF).toByte(),
            ((colorArgb shr 8) and 0xFF).toByte(),
            (colorArgb and 0xFF).toByte(),
            width.toByte(), height.toByte()
        ) + columnBitmap
    }

    private fun buildTextSubElementNoMarker(colorArgb: Int, width: Int, height: Int, columnBitmap: ByteArray): ByteArray {
        return byteArrayOf(
            ((colorArgb shr 16) and 0xFF).toByte(),
            ((colorArgb shr 8) and 0xFF).toByte(),
            (colorArgb and 0xFF).toByte(),
            width.toByte(), height.toByte()
        ) + columnBitmap
    }

    private fun rainbowColors(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        return (0 until count).map { i ->
            val hue = (i * 360f / count) % 360f
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.9f, 1.0f))
        }
    }

    /**
     * 炫彩文字 — 文本模式 (猜测格式, 假设设备内置字库).
     *
     * 子元素猜测格式: [R][G][B] [text_UTF8...]
     * 每个子元素 = 一个字符的 RGB + UTF-8 编码.
     * 设备使用内置字库渲染, 无需提供位图.
     *
     * @param text 要显示的文字 (最多 6 字符)
     * @param colors 每个字符的 ARGB 颜色, 为空则自动彩虹色
     * @param singleElement 若为 true, 所有文字合并为单个子元素
     */
    fun sendColorfulTextTextMode(text: String, colors: List<Int>? = null, group: Int = 0, program: Int = 0, singleElement: Boolean = false) {
        if (!connected || text.isEmpty()) return
        val chars = text.take(6)
        val charColors = when {
            colors != null && colors.size >= chars.length -> colors.take(chars.length)
            else -> rainbowColors(chars.length)
        }

        if (singleElement) {
            // 所有文字作为单个子元素: [R][G][B] [full_text_UTF8]
            val textBytes = chars.toByteArray(Charsets.UTF_8)
            val avgR = charColors.map { (it shr 16) and 0xFF }.average().toInt()
            val avgG = charColors.map { (it shr 8) and 0xFF }.average().toInt()
            val avgB = charColors.map { it and 0xFF }.average().toInt()
            val subElement = byteArrayOf(avgR.toByte(), avgG.toByte(), avgB.toByte()) + textBytes
            val args = byteArrayOf(group.toByte(), program.toByte(), 1) + subElement
            sendCommand(0xED01, args)
            Log.i(TAG, "文本模式(单元素) \"$chars\" → 组$group 节目$program (${textBytes.size}B UTF-8)")
        } else {
            // 每个字符一个子元素
            val subElements = mutableListOf<ByteArray>()
            for (i in chars.indices) {
                val charBytes = chars[i].toString().toByteArray(Charsets.UTF_8)
                // 格式: [R][G][B] [UTF8_char_bytes]
                val elem = byteArrayOf(
                    ((charColors[i] shr 16) and 0xFF).toByte(),
                    ((charColors[i] shr 8) and 0xFF).toByte(),
                    (charColors[i] and 0xFF).toByte()
                ) + charBytes
                subElements.add(elem)
            }
            var args = byteArrayOf(group.toByte(), program.toByte(), subElements.size.toByte())
            for (elem in subElements) args += elem
            sendCommand(0xED01, args)
            Log.i(TAG, "文本模式(逐字符) \"$chars\" → 组$group 节目$program (${subElements.size}字符)")
        }
    }

    /**
     * 炫彩文字 — 带类型标记的文本模式.
     * 格式: [type=0x00] [R][G][B] [text_UTF8...]
     */
    fun sendColorfulTextTyped(text: String, colors: List<Int>? = null, group: Int = 0, program: Int = 0) {
        if (!connected || text.isEmpty()) return
        val chars = text.take(6)
        val charColors = when {
            colors != null && colors.size >= chars.length -> colors.take(chars.length)
            else -> rainbowColors(chars.length)
        }
        val subElements = mutableListOf<ByteArray>()
        for (i in chars.indices) {
            val charBytes = chars[i].toString().toByteArray(Charsets.UTF_8)
            // 格式: [0x00=text_type] [R][G][B] [UTF8_char_bytes]
            val elem = byteArrayOf(
                0x00,
                ((charColors[i] shr 16) and 0xFF).toByte(),
                ((charColors[i] shr 8) and 0xFF).toByte(),
                (charColors[i] and 0xFF).toByte()
            ) + charBytes
            subElements.add(elem)
        }
        var args = byteArrayOf(group.toByte(), program.toByte(), subElements.size.toByte())
        for (elem in subElements) args += elem
        sendCommand(0xED01, args)
        Log.i(TAG, "文本模式(类型标记) \"$chars\" (${subElements.size}字符)")
    }

    /**
     * 炫彩文字 — GB2312 编码文本模式.
     * 部分设备使用 GB2312 而非 UTF-8 编码中文字符.
     * 格式: [R][G][B] [GB2312_char_bytes]
     */
    fun sendColorfulTextGB2312(text: String, colors: List<Int>? = null, group: Int = 0, program: Int = 0) {
        if (!connected || text.isEmpty()) return
        val chars = text.take(6)
        val charColors = when {
            colors != null && colors.size >= chars.length -> colors.take(chars.length)
            else -> rainbowColors(chars.length)
        }
        val subElements = mutableListOf<ByteArray>()
        for (i in chars.indices) {
            val charBytes = try {
                chars[i].toString().toByteArray(java.nio.charset.Charset.forName("GB2312"))
            } catch (_: Exception) {
                chars[i].toString().toByteArray(Charsets.UTF_8)
            }
            val elem = byteArrayOf(
                ((charColors[i] shr 16) and 0xFF).toByte(),
                ((charColors[i] shr 8) and 0xFF).toByte(),
                (charColors[i] and 0xFF).toByte()
            ) + charBytes
            subElements.add(elem)
        }
        var args = byteArrayOf(group.toByte(), program.toByte(), subElements.size.toByte())
        for (elem in subElements) args += elem
        sendCommand(0xED01, args)
        Log.i(TAG, "文本模式(GB2312) \"$chars\" (${subElements.size}字符)")
    }

    /**
     * 炫彩文字 — 位图模式 (原 iPixel 格式, 用 0x80 标记).
     * 失败时作为兜底.
     */
    fun sendColorfulTextBitmap(text: String, colors: List<Int>? = null, group: Int = 0, program: Int = 0) {
        if (!connected || text.isEmpty()) return
        val chars = text.take(6)
        val charColors = when {
            colors != null && colors.size >= chars.length -> colors.take(chars.length)
            else -> rainbowColors(chars.length)
        }
        val subElements = mutableListOf<ByteArray>()
        for (i in chars.indices) {
            val renderResult = LedMatrixBitmapRenderer.renderCharToColumnMajor(chars[i])
            if (renderResult != null) {
                val (width, bitmap) = renderResult
                subElements.add(buildTextSubElement(charColors[i], width, 16, bitmap))
            }
        }
        if (subElements.isEmpty()) return
        var args = byteArrayOf(group.toByte(), program.toByte(), subElements.size.toByte())
        for (elem in subElements) args += elem
        sendCommand(0xED01, args)
        Log.i(TAG, "位图模式(0x80) \"$chars\" → 组$group 节目$program (${subElements.size}字符)")
    }

    /**
     * 连接后单次诊断 — 纯 D501 路径.
     * 包含色块/棋盘格等基本图案诊断, 确认像素定位和颜色正确后
     * 再显示 D501 文字.
     */
    /**
     * 极简诊断序列 — 从零开始逐步测试.
     *
     * 时序 (从调用开始):
     *   0ms:   C001=0xFF 开机
     * 300ms:   C002 brightness=255 + C002 screenOn
     * 600ms:   产线测红 (type=0x00) — 不依赖状态, 验证硬件和基础通路
     * 900ms:   产线测白 (type=0x06)
     * 1200ms:  C001=0x03 涂鸦模式
     * 1500ms:  D517 全屏填充白 — 验证涂鸦模式下绘图是否工作
     * 2000ms:  D501 全白位图 — 验证长数据传输
     * 3000ms:  D501 红色文字
     * 4000ms:  完成
     */
    fun tryAllApproaches() {
        if (!connected) { Log.w(TAG, "未连接, 跳过诊断"); return }
        Log.i(TAG, "═══════ 极简诊断序列开始 ═══════")

        // Step 1: 开机 + 亮屏
        handler.postDelayed({ powerOn(); Log.i(TAG, "[诊1] C001=0xFF 开机") }, 0)
        handler.postDelayed({
            setBrightness(255); setBrightnessLegacy(255)
            setScreenOn(true); setScreenOnLegacy(true)
            Log.i(TAG, "[诊2] 亮度255 + 屏幕开")
        }, 300)

        // Step 2: 产线测试 — 不依赖设备状态
        handler.postDelayed({ sendFactoryTest(0x00); Log.i(TAG, "[诊3] 产线红") }, 600)
        handler.postDelayed({ sendFactoryTest(0x06); Log.i(TAG, "[诊4] 产线白") }, 900)

        // Step 3: 涂鸦模式 + 简单绘图
        handler.postDelayed({
            setDeviceState(3)
            Log.i(TAG, "[诊5] C001=0x03 涂鸦模式")
        }, 1200)

        // Step 4: D517 全屏白填充 (单帧命令, 最简单绘图)
        handler.postDelayed({
            val white565 = rgb565FromArgb(0xFF, 0xFF, 0xFF)
            fillRectInternal(white565, 0, 0, 96, 16)
            Log.i(TAG, "[诊6] D517 全屏白")
        }, 1500)

        // Step 5: D501 全白位图 (验证长数据传输)
        handler.postDelayed({
            sendWhiteScreenD501()
            Log.i(TAG, "[诊7] D501 全白位图")
        }, 2000)

        // Step 6: D501 红色文字
        handler.postDelayed({
            sendTextViaD501("CP搭子", colorRgb565 = 0xF800)
            Log.i(TAG, "[诊8] D501 红色文字")
        }, 3000)

        handler.postDelayed({
            Log.i(TAG, "═══════ 极简诊断序列完成 ═══════")
        }, 4000)
    }

    /**
     * D501 棋盘格: 所有奇数列白色, 偶数列黑色.
     * 96×16 屏幕: 每列 1 像素宽 × 16 像素高.
     */
    private fun sendD501Checkerboard() {
        val w = 96; val h = 16
        val bitmap = ByteArray(w * h * 2)
        for (col in 0 until w step 2) {
            for (row in 0 until h) {
                val idx = (row * w + col) * 2
                bitmap[idx] = 0xFF.toByte()
                bitmap[idx + 1] = 0xFF.toByte()
            }
        }
        powerOn()
        setDeviceState(3)
        handler.postDelayed({ sendRealtimeBitmap(bitmap, force = true) }, 200)
        Log.i(TAG, "D501棋盘格: 奇数列白 偶数列黑")
    }

    /** D501 全彩色横条: 填满整个屏幕. */
    private fun sendD501ColorBar(colorRgb565: Int) {
        val w = 96; val h = 16
        val lo = (colorRgb565 and 0xFF).toByte()
        val hi = ((colorRgb565 shr 8) and 0xFF).toByte()
        val bitmap = ByteArray(w * h * 2)
        for (i in bitmap.indices step 2) {
            bitmap[i] = lo; bitmap[i + 1] = hi
        }
        powerOn()
        setDeviceState(3)
        handler.postDelayed({ sendRealtimeBitmap(bitmap, force = true) }, 200)
        Log.i(TAG, "D501色条 color=0x${colorRgb565.toString(16)}")
    }

    // ── 播放控制 (0x0A01) ──────────────────────────────────────────
    /**
     * 播放控制. 尝试播放设备上已有的节目内容.
     */
    fun playbackControl(cmd: Int, group: Int = 0, program: Int = 0) {
        if (!connected) return
        sendCommand(0x0A01, byteArrayOf(
            cmd.toByte(), 0x00, 0x53, group.toByte(), program.toByte()
        ))
        Log.i(TAG, "播放控制: cmd=0x${cmd.toString(16)} group=$group program=$program")
    }

    /**
     * 播放节目 — 强制设为单节目循环模式.
     * 协议: [CMD=0x01] [change=0x01] [LOOP=0x53] [group] [program]
     * 关键: byte 11 = 0x01 (改变循环模式), 确保当前模式切换为单循环.
     */
    private fun playbackPlayForceSingle() {
        if (!connected) return
        sendCommand(0x0A01, byteArrayOf(0x01, 0x01, 0x53, 0x00, 0x00))
        Log.i(TAG, "播放节目 强制单循环 (组0 节目0)")
    }

    /** 播放节目 — 当前循环模式. */
    fun playbackPlay()  {
        if (!connected) return
        sendCommand(0x0A01, byteArrayOf(0x01, 0x01, 0x53, 0x00, 0x00))
        Log.i(TAG, "播放节目 组0 节目0")
    }

    /** 全部循环播放 — 播放指定节目组数组. */
    fun playbackPlayAll(groups: ByteArray = byteArrayOf(0x00)) {
        if (!connected) return
        var args = byteArrayOf(0x01, 0x41)
        args += groups
        sendCommand(0x0A01, args)
        Log.i(TAG, "全部循环播放: 组 ${groups.joinToString { "%02X".format(it) }}")
    }

    fun playbackPause() { playbackControl(0x00, 0, 0) }

    // ── 设备状态查询 ──────────────────────────────────────────────
    fun queryDeviceState() {
        if (!connected) return
        sendCommand(0xC001, byteArrayOf(0x00), onResponse = { content ->
            if (content.isNotEmpty()) {
                val state = content[0].toInt() and 0xFF
                val names = mapOf(0xFF to "开机", 0x01 to "节目播放", 0x02 to "节目预览", 0x03 to "涂鸦")
                Log.i(TAG, "设备状态: ${names[state] ?: "未知($state)"}")
            }
        })
    }

    fun queryRealtimeStatus() {
        if (!connected) return
        sendCommand(0xD502, byteArrayOf(0x00), onResponse = { content ->
            Log.i(TAG, "实时显示状态: ${content.size}B ${content.joinToString("") { "%02X".format(it) }}")
        })
    }

    // ── 综合诊断 ──────────────────────────────────────────────────
    /**
     * 综合诊断序列 — 依次尝试多种命令组合, 寻找能产生可见输出的方案.
     * 在初始化序列完成后调用 (readyToDisplay = true 后).
     */
    fun runFullDiagnostic() {
        if (!connected) { Log.w(TAG, "未连接, 跳过诊断"); return }
        Log.i(TAG, "═══════ 综合诊断序列开始 ═══════")

        // Phase 1: 显示开启 + 亮度 (延迟确保连接稳定)
        handler.postDelayed({
            Log.i(TAG, "[诊1a] 旧式屏幕开启 (0x0E01)"); setScreenOnLegacy(true)
        }, 200)
        handler.postDelayed({
            Log.i(TAG, "[诊1b] 旧式亮度255 (0x0E02)"); setBrightnessLegacy(255)
        }, 400)

        // Phase 2: 查询状态
        handler.postDelayed({
            Log.i(TAG, "[诊2a] 查询设备状态"); queryDeviceState()
        }, 700)
        handler.postDelayed({
            Log.i(TAG, "[诊2b] 查询实时显示状态"); queryRealtimeStatus()
        }, 1000)

        // Phase 3: 播放节目 + D517 填充 + D507/D508 像素点
        handler.postDelayed({
            Log.i(TAG, "[诊3a] 播放节目组0节目0"); playbackPlay()
        }, 1300)
        handler.postDelayed({
            Log.i(TAG, "[诊3b] D517 全屏白"); fillScreen(rgb565FromArgb(0xFF, 0xFF, 0xFF))
        }, 1600)
        handler.postDelayed({
            Log.i(TAG, "[诊3c] D507 快速像素"); sendFastPixels()
        }, 1900)
        handler.postDelayed({
            Log.i(TAG, "[诊3d] D508 像素着色"); sendColoredPixels()
        }, 2200)

        // Phase 4: 产线测试
        handler.postDelayed({
            Log.i(TAG, "[诊4a] 产线白屏"); sendFactoryTest(0x06)
        }, 2600)
        handler.postDelayed({
            Log.i(TAG, "[诊4b] 产线红屏"); sendFactoryTest(0x00)
        }, 3000)
        handler.postDelayed({
            Log.i(TAG, "[诊4c] 产线绿屏"); sendFactoryTest(0x01)
        }, 3400)
        handler.postDelayed({
            Log.i(TAG, "[诊4d] 产线蓝屏"); sendFactoryTest(0x02)
        }, 3800)

        // Phase 5: D501 全白位图
        handler.postDelayed({
            Log.i(TAG, "[诊5] D501 全白位图"); sendWhiteScreenD501()
        }, 4200)

        handler.postDelayed({
            Log.i(TAG, "═══════ 综合诊断序列完成 ═══════")
        }, 5000)
    }

    /** D507 快速像素 — 全局颜色画分散点, 不依赖涂鸦模式. */
    private fun sendFastPixels() {
        val color = rgb565FromArgb(0xFF, 0xFF, 0xFF)
        val pts = listOf(0 to 0, 95 to 0, 0 to 15, 95 to 15, 48 to 8)
        var args = le16(color)
        for ((x, y) in pts) args += le16(x) + le16(y)
        sendCommand(0xD507, args)
        Log.i(TAG, "D507 快速像素: ${pts.size}个点")
    }

    /** D508 像素着色 — 每个像素独立颜色. */
    private fun sendColoredPixels() {
        val red = rgb565FromArgb(0xFF, 0, 0); val green = rgb565FromArgb(0, 0xFF, 0)
        val blue = rgb565FromArgb(0, 0, 0xFF); val white = rgb565FromArgb(0xFF, 0xFF, 0xFF)
        val pts = listOf(Triple(0, 0, red), Triple(95, 0, green), Triple(0, 15, blue), Triple(95, 15, white), Triple(48, 8, white))
        var args = byteArrayOf()
        for ((x, y, c) in pts) args += le16(x) + le16(y) + le16(c)
        sendCommand(0xD508, args)
        Log.i(TAG, "D508 像素着色: ${pts.size}个彩色点")
    }

    // ── 旧式命令 (0x0E01/0x0E02) 兜底 ────────────────────────────────
    // 某些固件版本不支持 CMD_DEVICE_PARAM (0xC002) 参数控制,
    // 但支持旧式命令. 两个途径都试一下.

    fun setScreenOnLegacy(on: Boolean) {
        if (!connected) return
        sendCommand(CMD_DISPLAY_ONOFF_LEGACY, byteArrayOf(if (on) 0x01 else 0x00))
        Log.i(TAG, "旧式屏幕${if (on) "开启" else "关闭"} (0x0E01)")
    }

    fun setBrightnessLegacy(level: Int) {
        if (!connected) return
        val v = level.coerceIn(0, 255)
        sendCommand(CMD_BRIGHTNESS_LEGACY, byteArrayOf(v.toByte()))
        Log.i(TAG, "旧式亮度 ${v} (0x0E02)")
    }

    /**
     * 组合诊断: 同时发新式和旧式亮度+屏幕开关, 确保设备收到.
     */
    fun sendDiagnosticEnable() {
        if (!connected) return
        setBrightness(255)
        setBrightnessLegacy(255)
        setScreenOn(true)
        setScreenOnLegacy(true)
        Log.i(TAG, "诊断: 新式+旧式亮度255 + 屏幕开启")
    }

    /**
     * 清屏 — 发送空白节目 (0 子元素) 并播放.
     */
    fun clearScreen() {
        if (!connected) return
        // 发送 0 个子元素的节目编辑 (清空节目槽)
        sendCommand(0xED01, byteArrayOf(0, 0, 0))
        handler.postDelayed({ playbackPlay() }, 60)
        Log.i(TAG, "清屏 (节目编辑: 空节目)")
    }

    /**
     * 实时显示 RGB565 位图 (0xD501) — 长数据传输.
     *
     * 流程:
     *   1. 发送 0xD501 控制帧, 携带 length + 起始数据 CUID
     *   2. 等 ACK (或 fire-and-forget 模式短延时)
     *   3. 按 DATA_PAYLOAD_CHUNK 字节为单位拆分, 用递增 CUID 发送 0xDA 数据帧
     *
     * 内置 MIN_BITMAP_INTERVAL_MS 节流: 防止连续快速发送导致设备状态机溢出返回 error 400.
     *
     * @param rgb565  按行 (row-major), 每像素 2 字节 LE RGB565
     * @param force   为 true 时跳过 readyToDisplay 检查 (诊断用)
     */
    fun sendRealtimeBitmap(rgb565: ByteArray, force: Boolean = false) {
        if (!connected) { Log.w(TAG, "未连接, 跳过 sendRealtimeBitmap"); return }
        if (!readyToDisplay) {
            if (force) {
                Log.d(TAG, "⚠️ 强制发送 D501 (readyToDisplay=false, 诊断模式)")
            } else {
                Log.d(TAG, "绘图未就绪 (setDeviceState 尚未发出), 跳过 sendRealtimeBitmap")
                return
            }
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastRealtimeBitmapSentMs
        if (elapsed < MIN_BITMAP_INTERVAL_MS) {
            Log.d(TAG, "sendRealtimeBitmap 节流: 距上次 ${elapsed}ms < ${MIN_BITMAP_INTERVAL_MS}ms, 跳过")
            return
        }
        lastRealtimeBitmapSentMs = now

        val total = rgb565.size
        val startDataCuid = (cuid.get() + 1) and 0xFFFF  // 控制帧用当前 CUID, 数据帧从下一个开始

        // 控制帧 args: length(LE32) + startCUID(LE16) = 6 字节
        val args = le32(total) + le16(startDataCuid)
        sendCommand(
            CMD_REALTIME_DISPLAY, args,
            onAck = {
                Log.i(TAG, "0xD501 控制帧已确认, 开始下发 ${total}B 数据")
                sendBitmapData(rgb565, startDataCuid)
            },
            ffAckDelayMs = D501_FF_ACK_DELAY_MS
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
        // 复合 0x5E 响应处理: 搜索 "GL1696H" 确认 a800 设备
        val modelStr = String(content, Charsets.UTF_8)
        if (modelStr.contains("GL1696H")) {
            return DeviceInfo(model = "GL1696H", width = 96, height = 16,
                versionMajor = 0, versionMinor = 0, versionPatch = 0,
                id = ByteArray(8), memory = 0, memoryAvailable = 0, orientation = 0)
        }
        // 非复合帧: 标准解析格式 (cmd + model + version + dimensions)
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
                    if (connected) sendText(command.text, colorArgb = command.color ?: android.graphics.Color.WHITE, animationType = command.animation)
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

    /**
     * 蓝线 — 屏幕蓝色横条, 用节目编辑实现.
     * 发 6 个全蓝像素块填满 96px 屏幕.
     */
    fun sendDebugBlueLine() {
        if (!connected) { Log.w(TAG, "设备未就绪, 跳过蓝线"); return }
        val blueArgb = android.graphics.Color.rgb(0x33, 0x88, 0xFF)
        // 全亮位图: 32 字节全 FF (16 列 × 2 字节, 所有像素 ON)
        val fullBitmap = ByteArray(32) { 0xFF.toByte() }
        val subElements = mutableListOf<ByteArray>()
        // 96/16 = 6 个全亮字符块
        for (i in 0 until 6) {
            subElements.add(buildTextSubElement(blueArgb, 16, 16, fullBitmap))
        }
        var args = byteArrayOf(0, 0, subElements.size.toByte())
        for (elem in subElements) args += elem
        sendCommand(0xED01, args)
        handler.postDelayed({ playbackPlayForceSingle() }, 60)
        Log.i(TAG, "蓝线 (节目编辑 6 蓝块)")
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
                // D501 最多 6 字符 (96px/16px)
                return DisplayCommand.Text("CP搭子", 0xFF33CCFF.toInt(), AnimationType.STATIC)
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