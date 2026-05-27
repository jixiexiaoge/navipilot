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

private const val LED_RENDER_TAG = "LedMatrixRender"

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
            Log.e(LED_RENDER_TAG, "🎨 '$ch' 渲染异常: ${e.message}")
            null
        }
    }

    fun renderTextToColumnMajor(text: String): List<ByteArray> {
        return text.mapNotNull { renderCharToColumnMajor(it)?.second }
    }
}

/**
 * BLE LED点阵屏管理器 — iPixel Color 协议
 *
 * 基于 BLE HCI 抓包逆向分析 iPixel Color App 的通信协议。
 * Service 0x00FA, Char 0xFA02(Write) / 0xFA03(Notify)
 * 数据帧: 29字节帧头 + N个字符块(0x80+RGB3+W1+H1+bitmap32)
 */
class LedMatrixManager(private val context: Context) {

    companion object {
        private const val TAG = "LedMatrix"

        // iPixel BLE UUID
        val IPIXEL_SERVICE_UUID: UUID = UUID.fromString("000000fa-0000-1000-8000-00805f9b34fb")
        val IPIXEL_TX_CHAR_UUID: UUID = UUID.fromString("0000fa02-0000-1000-8000-00805f9b34fb")
        val IPIXEL_RX_CHAR_UUID: UUID = UUID.fromString("0000fa03-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 备用 NUS
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 5_000L
        private const val PREFERRED_MTU = 512
        private const val PREF_NAME = "CarrotAmap"
        private const val PREF_LED_ADDRESS = "led_device_address"
        private const val PREF_LED_NAME = "led_device_name"

        // 应用级单例 — BLE连接不随Activity生命周期销毁
        @Volatile
        private var instance: LedMatrixManager? = null

        fun getInstance(context: Context): LedMatrixManager {
            return instance ?: synchronized(this) {
                instance ?: LedMatrixManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SENDING, ERROR }

    // 动画效果 — 对应 iPixel 协议 byte[19]
    enum class AnimationType(val code: Int) {
        STATIC(0), SCROLL_LEFT(1), SCROLL_RIGHT(2), BREATHE(6), LASER(8)
    }

    /**
     * LED 显示指令 — 文字发送或蓝线图案
     */
    sealed class DisplayCommand {
        data class Text(val text: String, val color: Int?, val animation: AnimationType) : DisplayCommand()
        data object BlueLine : DisplayCommand()
    }

    /**
     * LED 当前显示状态
     * 用于实时预览组件
     */
    data class DisplayState(
        val text: String = "",
        val color: ComposeColor = ComposeColor.White,
        val animCode: Int = 0,
        val bitmapData: List<ByteArray> = emptyList()  // 每字符的32字节点阵数据
    )

    // 实时显示状态流
    private val _currentDisplayState = MutableStateFlow(DisplayState())
    val currentDisplayState: StateFlow<DisplayState> = _currentDisplayState.asStateFlow()

    enum class ProtocolMode { IPIXEL, NUS_GENERIC }

    var state: State = State.IDLE; private set
    var stateMessage: String = ""; private set
    var onStateChanged: ((State, String) -> Unit)? = null

    val scannedDevices = mutableListOf<BluetoothDevice>()
    var onDevicesUpdated: (() -> Unit)? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var scanning = false
    @Volatile private var currentMtu = 23
    @Volatile private var handshakeDone = false
    private var protocolMode: ProtocolMode = ProtocolMode.IPIXEL
    @Volatile private var autoConnecting = false
    private var pendingInitText: String? = null

    // BLE 写入队列 — 防止并发写入触发 GATT_CONGESTED，前一包 onCharacteristicWrite 确认后才发下一包
    private val writeQueue = ArrayDeque<ByteArray>()
    @Volatile private var writeInFlight = false

    // 自动重连状态
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectAttempts = 0
    private val MAX_AUTO_RECONNECT = 3
    private val reconnectDelays = longArrayOf(2_000L, 5_000L, 10_000L)

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private fun updateState(newState: State, msg: String = "") {
        state = newState; stateMessage = msg
        handler.post { onStateChanged?.invoke(newState, msg) }
    }

    // ===== 权限 =====

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // ===== 设备记忆 =====

    fun getSavedDeviceAddress(): String? = prefs.getString(PREF_LED_ADDRESS, null)

    private fun saveDevice(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null }
        prefs.edit().putString(PREF_LED_ADDRESS, device.address)
            .putString(PREF_LED_NAME, name ?: device.address).apply()
    }

    fun clearSavedDevice() {
        prefs.edit().remove(PREF_LED_ADDRESS).remove(PREF_LED_NAME).apply()
    }

    // ===== 自动连接 =====

    /**
     * 尝试自动连接上次保存的 LED 设备。
     * 成功连接后发送 initText；未找到保存设备或扫描超时则回调 onNotFound。
     */
    @SuppressLint("MissingPermission")
    fun autoConnect(initText: String? = null, onNotFound: (() -> Unit)? = null) {
        // 已连接：直接发送初始文字，不重复扫描
        if (state == State.CONNECTED || state == State.SENDING) {
            if (initText != null) sendText(initText)
            return
        }
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress == null) {
            onNotFound?.invoke()
            return
        }
        if (!hasPermissions() || !isBluetoothEnabled()) {
            onNotFound?.invoke()
            return
        }
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

    // ===== 扫描 =====

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

    // ===== 连接 =====

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan(); disconnect(); handshakeDone = false
        saveDevice(device)
        lastConnectedDevice = device
        autoReconnectAttempts = 0
        val name = try { device.name } catch (_: Exception) { device.address }
        updateState(State.CONNECTING, "连接 $name...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        // 手动断开：不自动重连
        lastConnectedDevice = null
        autoReconnectAttempts = MAX_AUTO_RECONNECT
        handler.removeCallbacksAndMessages(null)
        synchronized(writeQueue) { writeQueue.clear() }
        writeInFlight = false
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null; txCharacteristic = null; rxCharacteristic = null; handshakeDone = false
        updateState(State.IDLE, "已断开")
    }

    // ===== GATT =====

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    autoReconnectAttempts = 0
                    gatt?.requestMtu(PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txCharacteristic = null; rxCharacteristic = null; handshakeDone = false
                    synchronized(writeQueue) { writeQueue.clear() }
                    writeInFlight = false
                    updateState(State.IDLE, "连接已断开")
                    // 自动重连（限次数，指数退避）
                    val device = lastConnectedDevice
                    if (device != null && autoReconnectAttempts < MAX_AUTO_RECONNECT) {
                        val delay = reconnectDelays.getOrElse(autoReconnectAttempts) { reconnectDelays.last() }
                        autoReconnectAttempts++
                        Log.i(TAG, "🔄 ${delay / 1000}s 后自动重连 (第 $autoReconnectAttempts/$MAX_AUTO_RECONNECT 次)")
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

            val ipixelService = gatt?.getService(IPIXEL_SERVICE_UUID)
            if (ipixelService != null) {
                txCharacteristic = ipixelService.getCharacteristic(IPIXEL_TX_CHAR_UUID)
                rxCharacteristic = ipixelService.getCharacteristic(IPIXEL_RX_CHAR_UUID)
                protocolMode = ProtocolMode.IPIXEL

                // 启用 Notification
                rxCharacteristic?.let { rx ->
                    gatt.setCharacteristicNotification(rx, true)
                    rx.getDescriptor(CCCD_UUID)?.let { cccd ->
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
                if (txCharacteristic != null) handler.postDelayed({ sendHandshake() }, 300)
                else updateState(State.ERROR, "未找到TX(0xFA02)")
                return
            }

            // NUS 回退
            val nusService = gatt?.getService(NUS_SERVICE_UUID)
            if (nusService != null) {
                txCharacteristic = nusService.getCharacteristic(NUS_TX_CHAR_UUID)
                protocolMode = ProtocolMode.NUS_GENERIC
                if (txCharacteristic != null) {
                    welcomeShown = false  // 重连后重新显示欢迎语
                    updateState(State.CONNECTED, "已连接(NUS)")
                    handshakeDone = true
                } else updateState(State.ERROR, "未找到NUS TX")
                return
            }
            updateState(State.ERROR, "未找到可用服务")
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
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleNotification(value) }
    }

    private fun handleNotification(value: ByteArray) {
        if (value.size >= 4 && value[2] == 0x01.toByte() && value[3] == 0x80.toByte() && !handshakeDone) {
            handshakeDone = true; Log.i(TAG, "✅ 握手确认")
            handler.postDelayed({ sendDeviceQuery() }, 100)
        }
    }

    // ===== iPixel 协议命令 =====

    @SuppressLint("MissingPermission")
    private fun sendHandshake() {
        // 抓包: 08 00 01 80 15 01 XX 01
        writeToDevice(byteArrayOf(0x08, 0x00, 0x01, 0x80.toByte(), 0x15, 0x01, 0x16, 0x01))
        updateState(State.CONNECTING, "握手中...")
        handler.postDelayed({
            if (!handshakeDone) { handshakeDone = true; updateState(State.CONNECTED, "已连接(iPixel)") }
        }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun sendDeviceQuery() {
        writeToDevice(byteArrayOf(0x04, 0x00, 0x05, 0x80.toByte()))
        handler.postDelayed({
            writeToDevice(byteArrayOf(0x05, 0x00, 0x12, 0x80.toByte(), 0x02))
            welcomeShown = false  // 重连后重新显示欢迎语
            updateState(State.CONNECTED, "已连接(iPixel)")
            // 自动连接成功后发送初始文字
            pendingInitText?.let { text ->
                pendingInitText = null
                handler.postDelayed({ sendText(text) }, 500)
            }
        }, 200)
    }

    @SuppressLint("MissingPermission")
    private fun sendSelectPage(page: Int) {
        writeToDevice(byteArrayOf(0x07, 0x00, 0x08, 0x80.toByte(), 0x01, 0x00, page.toByte()))
    }

    /**
     * 构建完整 iPixel 数据帧（含 CRC32 content_hash）。
     *
     * 帧格式（29B 帧头 + N×字符块）：
     *   [0:2]  totalLen LE16          [2:4]  0x0001（帧类型）
     *   [4]    0x00                   [5:7]  payloadLen LE16
     *   [7]    0x00                   [8:13] content_hash (CRC32 of [15..end])
     *   [14]   page=0x65              [15]   charCount
     *   [16:20] params                [20]   0x50 'P'
     *   [21]   colorMode              [22:25] fg_rgb       [25:29] bg
     *
     * @param charPayload  已打包的字符块（每字符 38B：0x80+RGB+W+H+bitmap32）
     * @param charCount    字符数量（与 charPayload.size/38 一致）
     * @param animCode     动画代码（[AnimationType.code]）
     * @param r/g/b        前景 RGB（0~255）
     * @param isColor      true=彩色模式，false=白色模式
     * @return 完整帧字节数组（含 CRC32）
     */
    private fun buildIPixelFrame(
        charPayload: ByteArray, charCount: Int,
        animCode: Int, r: Int, g: Int, b: Int, isColor: Boolean
    ): ByteArray {
        val headerSize = 29
        val totalLen = headerSize + charPayload.size
        val payloadLen = totalLen - 15
        val frame = ByteArray(totalLen)
        // [0:2] totalLen LE16
        frame[0] = (totalLen and 0xFF).toByte()
        frame[1] = ((totalLen shr 8) and 0xFF).toByte()
        // [2:4] 帧类型 0x0001
        frame[2] = 0x00; frame[3] = 0x01
        // [4] 0x00, [5:7] payloadLen LE16
        frame[4] = 0x00
        frame[5] = (payloadLen and 0xFF).toByte()
        frame[6] = ((payloadLen shr 8) and 0xFF).toByte()
        // [7] 0x00, [8:13] content_hash（先清零，后填充 CRC32）
        // [14] page=0x65, [15] charCount
        frame[14] = 0x65.toByte(); frame[15] = charCount.toByte()
        // [16:20] params: 00 01 01 animCode
        frame[16] = 0x00; frame[17] = 0x01; frame[18] = 0x01; frame[19] = animCode.toByte()
        // [20] 'P', [21] colorMode, [22:25] fg_rgb, [25:29] bg
        frame[20] = 0x50; frame[21] = if (isColor) 0x01 else 0x00
        frame[22] = r.toByte(); frame[23] = g.toByte(); frame[24] = b.toByte()
        System.arraycopy(charPayload, 0, frame, headerSize, charPayload.size)
        // CRC32(frame[15..end]) → [9:13]，[8] 固定 0x00
        val crc = java.util.zip.CRC32(); crc.update(frame, 15, frame.size - 15)
        val hash = crc.value.toInt()
        frame[9] = (hash and 0xFF).toByte()
        frame[10] = ((hash shr 8) and 0xFF).toByte()
        frame[11] = ((hash shr 16) and 0xFF).toByte()
        frame[12] = ((hash shr 24) and 0xFF).toByte()
        return frame
    }

    private fun isReadyToSend(): Boolean {
        return state == State.CONNECTED || state == State.SENDING
    }

    private fun updatePreviewDisplayState(text: String, color: Int?, animation: AnimationType) {
        if (text.isBlank()) return
        val effectiveAnim = if (text.length > 4 && animation == AnimationType.STATIC) {
            AnimationType.SCROLL_LEFT
        } else {
            animation
        }
        val charBitmaps = LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
        val displayColor = if (color != null) ComposeColor(color) else ComposeColor.White
        _currentDisplayState.value = DisplayState(
            text = text,
            color = displayColor,
            animCode = effectiveAnim.code,
            bitmapData = charBitmaps
        )
    }

    // ===== 发送文字 =====

    fun sendText(text: String, color: Int? = null, animation: AnimationType = AnimationType.STATIC) {
        if (text.isBlank()) return
        if (!isReadyToSend()) {
            updatePreviewDisplayState(text, color, animation)
            return
        }

        // 超过4个字符自动滚动（屏幕 64px / 16px = 4字符），从右向左滚动
        val effectiveAnim = if (text.length > 4 && animation == AnimationType.STATIC) AnimationType.SCROLL_LEFT else animation
        updateState(State.SENDING, "发送: $text")
        updatePreviewDisplayState(text, color, effectiveAnim)

        if (protocolMode == ProtocolMode.IPIXEL) {
            sendIPixelFrame(text, color, effectiveAnim.code)
        } else {
            val bitmapData = renderTextToBitmapRowMajor(text)
            val packet = buildNusPacket(bitmapData, color)
            writeToDevice(packet)
            handler.postDelayed({ updateState(State.CONNECTED, "已发送: $text") }, 200)
        }
    }

    /**
     * iPixel 文字帧发送：将文本渲染为 16×16 列优先位图，用 [buildIPixelFrame] 组装后写入设备。
     *
     * 帧格式（29B 帧头 + N×字符块）参见 [buildIPixelFrame]。
     * 字符块：0x80 + RGB(3) + W(1) + H(1) + bitmap(32B, 16列×2字节) = 38B
     */
    @SuppressLint("MissingPermission")
    private fun sendIPixelFrame(text: String, color: Int?, animCode: Int) {
        // 不清屏 — 通过正确的 content_hash (CRC32) 让设备识别新帧并直接覆盖
        sendSelectPage(0x65)

        handler.postDelayed({
            val charBitmaps = mutableListOf<Triple<Char, Int, ByteArray>>()
            for (ch in text) {
                val rendered = LedMatrixBitmapRenderer.renderCharToColumnMajor(ch)
                if (rendered != null) charBitmaps.add(Triple(ch, rendered.first, rendered.second))
                else Log.w(TAG, "⚠️ '$ch' 渲染失败")
            }
            if (charBitmaps.isEmpty()) {
                Log.e(TAG, "❌ 无可渲染字符: \"$text\"")
                updateState(State.CONNECTED, "无法渲染: $text"); return@postDelayed
            }

            val charCount = charBitmaps.size
            val isColor = color != null
            val r = if (isColor) Color.red(color!!) else 0xFF
            val g = if (isColor) Color.green(color!!) else 0xFF
            val b = if (isColor) Color.blue(color!!) else 0xFF

            // 字符块打包：每字符 38B = 0x80(1) + RGB(3) + W(1) + H(1) + bitmap(32)
            val charPayload = ByteArray(charCount * 38)
            for (i in 0 until charCount) {
                val (_, w, colBitmap) = charBitmaps[i]
                val off = i * 38
                charPayload[off] = 0x80.toByte()
                charPayload[off + 1] = r.toByte(); charPayload[off + 2] = g.toByte(); charPayload[off + 3] = b.toByte()
                charPayload[off + 4] = w.toByte(); charPayload[off + 5] = 16
                System.arraycopy(colBitmap, 0, charPayload, off + 6, 32)
            }

            val frame = buildIPixelFrame(charPayload, charCount, animCode, r, g, b, isColor)
            Log.i(TAG, "📤 iPixel帧: ${frame.size}B, ${charCount}字, anim=$animCode")
            writeToDevice(frame)
            handler.postDelayed({ updateState(State.CONNECTED, "已发送: $text") }, 300)
        }, 200)
    }

    // ===== 字符渲染 (Canvas) =====

    /**
     * 判断字符是否为 CJK（中日韩）全角字符
     * CJK 字符使用 16×16 像素，ASCII/半角字符使用 8×16 像素
     */
    // ===== 清屏 =====

    @SuppressLint("MissingPermission")
    fun clearScreen() {
        if (protocolMode == ProtocolMode.IPIXEL) {
            // 抓包: 04 00 03 80 = 清屏命令
            writeToDevice(byteArrayOf(0x04, 0x00, 0x03, 0x80.toByte()))
        } else {
            writeToDevice(ByteArray(4 + 16 * 8).also { it[0] = 0xAA.toByte(); it[1] = 0x55; it[2] = 64; it[3] = 16 })
        }
    }

    // ===== 调试：蓝色横线（智驾小蓝灯效果）=====

    /**
     * 发送蓝色横线效果到 LED 点阵屏
     * 16x64 的屏幕，点亮中间 6 行（行 5-10），宽度 64 列
     * 效果：中间一条蓝色横线（智驾小蓝灯）
     */
    @SuppressLint("MissingPermission")
    fun sendDebugBlueLine() {
        if (!isReadyToSend()) {
            Log.w(TAG, "设备未就绪，无法发送调试图案")
            return
        }
        if (protocolMode != ProtocolMode.IPIXEL) {
            Log.w(TAG, "NUS 模式不支持调试图案")
            return
        }

        sendSelectPage(0x65)
        handler.postDelayed({
            // 16×16 列优先位图：中间 6 行（行5~10）点亮，形成横线
            // 行 5-7 → upper byte bit5~7；行 8-10 → lower byte bit0~2
            val colBitmap = ByteArray(32)
            val upperMask = 0b11100000  // bit5,6,7 → 行5-7
            val lowerMask = 0b00000111  // bit0,1,2 → 行8-10
            for (col in 0 until 16) {
                colBitmap[col * 2] = upperMask.toByte()
                colBitmap[col * 2 + 1] = lowerMask.toByte()
            }
            // 设备摆放方向与 iPixel 列优先坐标系相差 90°，需旋转修正
            val rotatedBitmap = rotateColumnMajor16x16Clockwise(colBitmap)

            // 4 个字符块 × 16列 = 64列（全屏宽），每块 38B
            val charCount = 4
            val r = 51; val g = 136; val b = 255  // 蓝色 #3388FF
            val charPayload = ByteArray(charCount * 38)
            for (i in 0 until charCount) {
                val off = i * 38
                charPayload[off] = 0x80.toByte()
                charPayload[off + 1] = r.toByte(); charPayload[off + 2] = g.toByte(); charPayload[off + 3] = b.toByte()
                charPayload[off + 4] = 16; charPayload[off + 5] = 16
                System.arraycopy(rotatedBitmap, 0, charPayload, off + 6, 32)
            }

            val frame = buildIPixelFrame(charPayload, charCount, AnimationType.STATIC.code, r, g, b, true)
            Log.i(TAG, "📤 调试蓝色横线: ${frame.size}B")
            updateState(State.SENDING, "发送蓝线...")
            writeToDevice(frame)
            handler.postDelayed({ updateState(State.CONNECTED, "已发送蓝线") }, 300)
        }, 200)
    }

    // ===== 位图转换 =====

    /**
     * iPixel 16x16 列优先位图顺时针旋转 90°。
     * bitmap[col*2] / bitmap[col*2+1] 分别代表该列的上8行/下8行（bit0 对应 row0）。
     */
    private fun rotateColumnMajor16x16Clockwise(bitmap: ByteArray): ByteArray {
        require(bitmap.size == 32) { "Expected 16x16 column-major bitmap (32 bytes), got ${bitmap.size}" }

        val size = 16
        val src = Array(size) { BooleanArray(size) }
        for (col in 0 until size) {
            val upper = bitmap[col * 2].toInt() and 0xFF
            val lower = bitmap[col * 2 + 1].toInt() and 0xFF
            for (row in 0 until 8) src[row][col] = ((upper shr row) and 1) == 1
            for (row in 8 until 16) src[row][col] = ((lower shr (row - 8)) and 1) == 1
        }

        val rotated = Array(size) { BooleanArray(size) }
        for (row in 0 until size) {
            for (col in 0 until size) {
                rotated[row][col] = src[size - 1 - col][row]
            }
        }

        val out = ByteArray(32)
        for (col in 0 until size) {
            var upper = 0
            var lower = 0
            for (row in 0 until size) {
                if (rotated[row][col]) {
                    if (row < 8) upper = upper or (1 shl row)
                    else lower = lower or (1 shl (row - 8))
                }
            }
            out[col * 2] = upper.toByte()
            out[col * 2 + 1] = lower.toByte()
        }
        return out
    }

    // ===== NUS 回退渲染 =====

    private fun renderTextToBitmapRowMajor(text: String): ByteArray {
        val charWidth = 16; val height = 16
        val totalWidth = text.length * charWidth
        val bytesPerRow = (totalWidth + 7) / 8
        val data = ByteArray(bytesPerRow * height)
        var xOffset = 0
        for (ch in text) {
            val hzk = lookupHzk16(ch)
            if (hzk != null) {
                for (row in 0 until height) {
                    for (col in 0 until charWidth) {
                        val srcByte = row * 2 + col / 8
                        val srcBit = 7 - (col % 8)
                        if (srcByte < hzk.size && (hzk[srcByte].toInt() shr srcBit) and 1 == 1) {
                            setBitInData(data, bytesPerRow, row, xOffset + col)
                        }
                    }
                }
            }
            xOffset += charWidth
        }
        return data
    }

    private fun setBitInData(data: ByteArray, bytesPerRow: Int, row: Int, col: Int) {
        val idx = row * bytesPerRow + col / 8
        val bit = 7 - (col % 8)
        if (idx < data.size) data[idx] = (data[idx].toInt() or (1 shl bit)).toByte()
    }

    private fun buildNusPacket(bitmapData: ByteArray, color: Int?): ByteArray {
        val totalWidth = bitmapData.size / 2 // 粗略估算
        val header = byteArrayOf(0xAA.toByte(), 0x55, totalWidth.coerceAtMost(255).toByte(), 16)
        return header + bitmapData
    }

    // ===== BLE 写入 =====

    /**
     * 将数据切分并加入写入队列，由 [drainWriteQueue] 按序发送。
     * 相比旧版固定 30ms 间隔方案，此版本在 onCharacteristicWrite 确认后才发送下一包，
     * 彻底避免 GATT_CONGESTED (0x8f) 错误。
     */
    private fun writeToDevice(data: ByteArray) {
        if (bluetoothGatt == null || txCharacteristic == null) return
        val chunkSize = (currentMtu - 3).coerceAtLeast(20)
        var offset = 0
        var chunkCount = 0
        synchronized(writeQueue) {
            while (offset < data.size) {
                val end = (offset + chunkSize).coerceAtMost(data.size)
                writeQueue.addLast(data.copyOfRange(offset, end))
                offset = end; chunkCount++
            }
        }
        if (chunkCount > 1) Log.i(TAG, "分包入队: ${chunkCount}包, ${data.size}B")
        drainWriteQueue()
    }

    /** 从写入队列取下一包发送；仅在无包在途时执行（流控核心）。 */
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // iPixel 使用 WRITE_TYPE_NO_RESPONSE (0x52)
                val writeType = if (protocolMode == ProtocolMode.IPIXEL)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = if (protocolMode == ProtocolMode.IPIXEL)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入异常: ${e.message}")
        }
    }

    // ===== HZK16 字库 =====

    private val hzk16Data: ByteArray? by lazy {
        try {
            context.assets.open("hzk16.dat").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "HZK16 加载失败: ${e.message}"); null
        }
    }

    /**
     * 查找 HZK16 字库中的字符位图
     * GB2312 编码: 区码=byte1-0xA0, 位码=byte2-0xA0
     * 偏移 = ((区码-1)*94 + (位码-1)) * 32
     */
    private fun lookupHzk16(char: Char): ByteArray? {
        val data = hzk16Data ?: return null
        try {
            val gb = String(charArrayOf(char)).toByteArray(charset("GB2312"))
            if (gb.size != 2) return null
            val b1 = gb[0].toInt() and 0xFF
            val b2 = gb[1].toInt() and 0xFF
            val quCode = b1 - 0xA0
            val weiCode = b2 - 0xA0
            if (quCode < 1 || quCode > 94 || weiCode < 1 || weiCode > 94) return null
            val offset = ((quCode - 1) * 94 + (weiCode - 1)) * 32
            if (offset + 32 > data.size) return null
            val bitmap = data.copyOfRange(offset, offset + 32)
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ '$char' 查找失败: ${e.message}")
            return null
        }
    }

    // ===== 自动化显示引擎 =====

    /**
     * LED 自动化显示数据快照
     * 由外部定时传入，引擎根据优先级决定显示内容
     */
    data class AutoDisplayData(
        // 系统状态 (7705)
        val isOnroad: Boolean = false,
        val isNavigating: Boolean = false,
        val active: Boolean = false,
        val xState: Int = 0,
        // 车速
        val vEgoKph: Int = 0,
        // 测速 (7706)
        val nSdiDist: Int = 0,
        val nSdiSpeedLimit: Int = 0,
        val nSdiType: Int = -1,
        // 转弯 (7706)
        val nTBTDist: Int = 0,
        val nTBTTurnType: Int = -1,
        val szTBTMainText: String = "",
        // 交通灯 (来自 comma3 7705 端口)
        val trafficState: Int = 0,
        // 道路限速
        val nRoadLimitSpeed: Int = 0,
        // 感知 (7711)
        val leftBlindspot: Boolean = false,
        val rightBlindspot: Boolean = false,
        val leadDistance: Float = 0f,
        val leadProb: Float = 0f,
        // 减速检测
        val steeringAngleDeg: Float = 0f,
        // 新增字段
        val nGoPosDist: Int = 0,                // 剩余距离 m
        // ATC 类型 (弯道减速)
        val atcType: String = "",
        val vTurnSpeed: Double = 0.0,            // 弯道建议速度
    )

    // xTurnInfo 映射 (与 carrot_serv.py 一致)
    // 1=左转, 2=右转, 3=左变道, 4=右变道, 5=环岛, 6=收费站, 7=掉头, 8=到达
    private fun turnTypeToXTurnInfo(nTBTTurnType: Int): Int {
        return when (nTBTTurnType) {
            12, 16 -> 1        // 左转/急左转
            13, 19 -> 2        // 右转/急右转
            102, 105, 112, 115, 7, 44, 17, 75, 76, 118 -> 3  // 左变道/左分叉
            101, 104, 111, 114, 6, 43, 73, 74, 123, 124, 117 -> 4  // 右变道/右分叉
            131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142 -> 5  // 环岛
            153, 154, 249 -> 6 // 收费站
            14 -> 7            // 掉头
            201 -> 8           // 到达
            else -> -1
        }
    }

    // 自动显示状态
    private var lastAutoText: String = ""
    private var lastAutoColor: Int? = null
    private var lastAutoAnim: AnimationType = AnimationType.STATIC
    private var lastAutoSendTime: Long = 0
    private var lastDisplayIsBlueLine: Boolean = false  // 当前显示是否为蓝线图案
    private var speedHistory = mutableListOf<Int>()
    private var autoDisplayEnabled: Boolean = true
    private var stoppedSince: Long = 0L
    private var welcomeShown: Boolean = false  // 欢迎语只显示一次

    /** 启用/禁用自动显示 */
    fun setAutoDisplayEnabled(enabled: Boolean) {
        autoDisplayEnabled = enabled
    }

    /**
     * 自动化显示核心逻辑 — 根据车辆/导航数据决定 LED 显示内容
     * 由外部每 800ms 调用一次
     */
    fun updateAutoDisplay(data: AutoDisplayData) {
        if (!autoDisplayEnabled) return

        // 更新速度历史（用于减速检测）
        speedHistory.add(data.vEgoKph)
        if (speedHistory.size > 6) speedHistory.removeAt(0)

        // 更新停车计时
        val now = System.currentTimeMillis()
        if (data.vEgoKph == 0 && data.isOnroad) {
            if (stoppedSince == 0L) stoppedSince = now
        } else {
            stoppedSince = 0L
        }

        // 按优先级决定显示内容
        val command = resolveDisplay(data)

        // 防抖: 相同内容不重复发送，但至少每10秒刷新一次
        val isSame = when (command) {
            is DisplayCommand.Text -> command.text == lastAutoText && command.color == lastAutoColor && command.animation == lastAutoAnim
            is DisplayCommand.BlueLine -> lastDisplayIsBlueLine
        }
        if (isSame && (now - lastAutoSendTime) < 10_000) {
            return
        }

        // 发送到 LED
        when (command) {
            is DisplayCommand.Text -> {
                if (command.text.isNotBlank()) {
                    lastAutoText = command.text
                    lastAutoColor = command.color
                    lastAutoAnim = command.animation
                    lastDisplayIsBlueLine = false
                    lastAutoSendTime = now
                    updatePreviewDisplayState(command.text, command.color, command.animation)
                    if (isReadyToSend()) {
                        sendText(command.text, command.color, command.animation)
                    }
                }
            }
            is DisplayCommand.BlueLine -> {
                lastDisplayIsBlueLine = true
                lastAutoSendTime = now
                if (isReadyToSend()) {
                    Log.i(TAG, "📤 自动模式: 发送蓝线")
                    sendDebugBlueLine()
                }
            }
        }
    }

    /**
     * 优先级决策引擎 (v5)
     *
     * P0   注意避让（盲区）          P1   限速{N}（测速 ≤200m，红/黄）
     * P1.5 测速预警（201~500m，黄）  P2   红灯停（车速=0）
     * P3   绿灯行（车速=0）          P4   停车等待（车速=0超3秒）
     * P5   正在减速                  P6   弯道减速
     * P7   正在左转/右转（方向盘）   P8~P15 TBT转弯导航
     * P15.5 即将到达（剩余≤200m）   P16  智驾蓝灯（智驾跟车/巡航）
     * P17  地图领航（导航中）        P18  智驾蓝灯（车道保持）
     * P19  限速提醒（道路限速）      P20  欢迎语（仅首次连接后）
     */
    private fun resolveDisplay(d: AutoDisplayData): DisplayCommand {
        val now = System.currentTimeMillis()

        // 未上路且未导航 → 欢迎语（仅首次连接后显示一次）
        if (!d.isOnroad && !d.isNavigating) {
            if (!welcomeShown) {
                welcomeShown = true
                return DisplayCommand.Text("CP搭子 Carrot Pilot智驾领航外挂", 0xFF33CCFF.toInt(), AnimationType.SCROLL_LEFT)
            }
            // 已显示过欢迎语，保持上次内容或静默
            return DisplayCommand.Text(lastAutoText.ifEmpty { "CP搭子" }, 0xFF33CCFF.toInt(), AnimationType.STATIC)
        }

        val hasLead = d.leadDistance in 1f..30f && d.leadProb > 0.5f

        // === P0: 注意避让 — 盲区警告 (红色呼吸) ===
        if (d.leftBlindspot || d.rightBlindspot) {
            return DisplayCommand.Text("注意避让", 0xFFFF3333.toInt(), AnimationType.BREATHE)
        }

        // === P1: 限速{N} — 测速近距离 ≤200m (红/黄) ===
        if (d.nSdiDist in 20..200 && d.nSdiSpeedLimit > 0 && d.nSdiType >= 0) {
            val over = d.vEgoKph > d.nSdiSpeedLimit
            val color = if (over) 0xFFFF0000.toInt() else 0xFFFFAA00.toInt()
            return DisplayCommand.Text("限速${d.nSdiSpeedLimit}", color, if (over) AnimationType.BREATHE else AnimationType.STATIC)
        }

        // === P1.5: 测速预警 — 远距离 201~500m (黄静态，提前感知) ===
        if (d.nSdiDist in 201..500 && d.nSdiSpeedLimit > 0 && d.nSdiType >= 0) {
            return DisplayCommand.Text("限速${d.nSdiSpeedLimit}", 0xFFFFDD00.toInt(), AnimationType.STATIC)
        }

        // === P2: 红灯停 — 车速=0且无前车时 (红) ===
        if (d.vEgoKph == 0 && d.trafficState == 1 && !hasLead) {
            return DisplayCommand.Text("红灯停", 0xFFFF0000.toInt(), AnimationType.STATIC)
        }

        // === P3: 绿灯行 — 车速=0且无前车时 (绿) ===
        if (d.vEgoKph == 0 && d.trafficState == 2 && !hasLead) {
            return DisplayCommand.Text("绿灯行", 0xFF00FF00.toInt(), AnimationType.STATIC)
        }

        // === P4: 停车等待 — 车速=0超过3秒 (红) ===
        if (d.vEgoKph == 0 && d.isOnroad && stoppedSince > 0 && (now - stoppedSince) > 3000) {
            return DisplayCommand.Text("停车等待", 0xFFFF4444.toInt(), AnimationType.STATIC)
        }

        // === P5: 正在减速 (橙) ===
        if (isDecelerating(d.vEgoKph)) {
            return DisplayCommand.Text("正在减速", 0xFFFF6600.toInt(), AnimationType.STATIC)
        }

        // === P6: 弯道减速 (橙) ===
        if ((d.atcType.isNotEmpty() && d.atcType != "none") ||
            (d.vTurnSpeed > 0 && d.vEgoKph > 20 && d.vTurnSpeed < d.vEgoKph - 5)) {
            return DisplayCommand.Text("弯道减速", 0xFFFF8800.toInt(), AnimationType.STATIC)
        }

        // === P7: 正在左转/右转 — 方向盘大角度 ===
        // steeringAngleDeg: 正值=左转, 负值=右转（驾驶员视角）
        // 90°阈值：城市行驶中 60° 过于敏感，90° 更符合实际转弯感知
        if (d.vEgoKph > 10 && kotlin.math.abs(d.steeringAngleDeg) > 90) {
            return if (d.steeringAngleDeg > 0) {
                DisplayCommand.Text("正在左转", 0xFF00CCFF.toInt(), AnimationType.STATIC)
            } else {
                DisplayCommand.Text("正在右转", 0xFF33FF66.toInt(), AnimationType.STATIC)
            }
        }

        // === P8~P15: TBT 转弯导航指令 ===
        if (d.nTBTDist in 5..300 && d.nTBTTurnType > 0) {
            val xTurn = turnTypeToXTurnInfo(d.nTBTTurnType)
            val result = resolveTurnDisplay(d, xTurn)
            if (result != null) return result
        }

        // === P15.5: 即将到达（剩余路程 ≤200m）===
        if (d.nGoPosDist in 1..200) {
            return DisplayCommand.Text("即将到达", 0xFF00FF00.toInt(), AnimationType.STATIC)
        }

        // === P16: 智驾蓝灯（智驾跟车/巡航）===
        if (d.isOnroad && d.active) {
            return DisplayCommand.BlueLine
        }

        // === P17: 地图领航 — 导航中 (紫) ===
        if (d.isNavigating) {
            return if (d.szTBTMainText.isNotBlank()) {
                // 超过4字才需要滚动（屏幕64px / 16px = 4字）
                val anim = if (d.szTBTMainText.length > 4) AnimationType.SCROLL_LEFT else AnimationType.STATIC
                DisplayCommand.Text(d.szTBTMainText, 0xFFAA66FF.toInt(), anim)
            } else {
                DisplayCommand.Text("地图领航", 0xFFAA66FF.toInt(), AnimationType.STATIC)
            }
        }

        // === P18: 智驾蓝灯（车道保持）|| 限速提醒 ===
        if (d.isOnroad) {
            if (d.nRoadLimitSpeed > 0 && d.vEgoKph > d.nRoadLimitSpeed - 5) {
                return DisplayCommand.Text("限速${d.nRoadLimitSpeed}", 0xFFFFFFFF.toInt(), AnimationType.STATIC)
            }
            return DisplayCommand.BlueLine
        }

        // === P19(默认): 保持上次内容 ===
        return DisplayCommand.Text(lastAutoText.ifEmpty { "CP搭子" }, 0xFF33CCFF.toInt(), AnimationType.STATIC)
    }

    /**
     * xTurn → 显示文字+颜色映射 (共享方法，避免重复)
     */
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

    /**
     * TBT 转弯指令解析 (P8~P15)
     * 近距离 5~150m: 显示动作 + szTBTMainText 滚动
     * 远距离 151~300m: 显示预告文字
     */
    private fun resolveTurnDisplay(d: AutoDisplayData, xTurn: Int): DisplayCommand? {
        val info = turnTypeToDisplayInfo(xTurn)

        // 近距离 5~150m
        if (d.nTBTDist in 5..150 && info != null) {
            val (text, color) = info
            return if (d.szTBTMainText.isNotBlank()) {
                DisplayCommand.Text("$text ${d.szTBTMainText}", color, AnimationType.SCROLL_LEFT)
            } else {
                DisplayCommand.Text(text, color, AnimationType.STATIC)
            }
        }

        // 远距离 151~300m
        if (d.nTBTDist in 151..300 && info != null) {
            val (text, color) = info
            return DisplayCommand.Text(text, color, AnimationType.STATIC)
        }

        return null
    }

    /**
     * 减速检测: 最近3秒内速度持续下降超过 8km/h 且当前速度>15km/h
     */
    private fun isDecelerating(currentSpeed: Int): Boolean {
        if (speedHistory.size < 4 || currentSpeed < 15) return false
        val oldest = speedHistory[0]
        val drop = oldest - currentSpeed
        if (drop < 8) return false
        for (i in 1 until speedHistory.size) {
            if (speedHistory[i] > speedHistory[i - 1] + 2) return false
        }
        return true
    }

    // ===== 生命周期 =====

    @SuppressLint("MissingPermission")
    fun destroy() {
        // 先阻止自动重连 + 撤销所有 handler 回调，再关闭 GATT
        // 避免 sendIPixelFrame/sendDebugBlueLine 的 200ms 延迟 lambda 向已关闭 GATT 写入
        lastConnectedDevice = null
        autoReconnectAttempts = MAX_AUTO_RECONNECT
        handler.removeCallbacksAndMessages(null)
        stopScan()
        synchronized(writeQueue) { writeQueue.clear() }
        writeInFlight = false
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null; txCharacteristic = null; rxCharacteristic = null; handshakeDone = false
        updateState(State.IDLE, "已销毁")
    }
}
