package com.example.navipilot.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.roundToInt

internal object LedMatrixBitmapRenderer {

    private const val FONT_FILE = "fonts/HZK16C"

    private var hzk16Data: ByteArray? = null
    private var hzk16Loaded = false

    /**
     * 加载 HZK16 字模文件
     * HZK16: 16x16 点阵字模, GB2312 编码
     * 文件大小: 261696 字节 (94区 × 94位 × 32字节/汉字)
     */
    private fun loadHZK16(context: Context): ByteArray? {
        if (hzk16Loaded) return hzk16Data
        hzk16Loaded = true
        return try {
            context.assets.open(FONT_FILE).use { input ->
                hzk16Data = input.readBytes()
                Log.i("LedMatrix-Render", "HZK16C 加载成功: ${hzk16Data?.size} 字节")
                hzk16Data
            }
        } catch (e: Exception) {
            Log.e("LedMatrix-Render", "HZK16C 加载失败: ${e.message}")
            null
        }
    }

    /**
     * 获取 GB2312 编码的汉字字模偏移量
     * @param high 高字节 (0xA1-0xF7)
     * @param low 低字节 (0xA1-0xFE)
     * @return 偏移量，失败返回 -1
     */
    private fun getGB2312Offset(high: Int, low: Int): Int {
        val row = high - 0xA1
        val col = low - 0xA1
        if (row < 0 || row >= 94 || col < 0 || col >= 94) return -1
        return (row * 94 + col) * 32
    }

    /**
     * 将 Unicode 字符转换为 HZK16 文件中的 GB2312 偏移量。
     * 使用 Android 内置的 GB2312/GBK Charset 做转换，无需硬编码映射表。
     * @return 偏移量，转换失败返回 null
     */
    private fun getCharOffset(char: Char): Int? {
        val code = char.code
        if (code < 0x80) return null  // ASCII - HZK16 没有 ASCII 字模
        return try {
            val gbkBytes = char.toString().toByteArray(Charset.forName("GB2312"))
            if (gbkBytes.size >= 2) {
                getGB2312Offset(gbkBytes[0].toInt() and 0xFF, gbkBytes[1].toInt() and 0xFF)
            } else null
        } catch (_: Exception) {
            Log.w("LedMatrix-Render", "GB2312 编码失败: '$char' U+${code.toString(16)}")
            null
        }
    }

    // ASCII 字符使用系统字体渲染，参见 getGlyphFromASCII()

    /**
     * HZK16 行优先转列优先
     * HZK16: 每行2字节(16列), 16行 = 32字节
     * 输出: 列优先, 每列2字节(16行), 16列 = 32字节
     *
     * HZK16 bit layout (每字节):
     * bit 7 = 列0, bit 6 = 列1, ..., bit 0 = 列7
     *
     * 输出格式 (列优先):
     * 每列: [lower byte (rows 0-7)] [upper byte (rows 8-15)]
     * bit 0 = row 0, bit 1 = row 1, ...
     */
    private fun hzk16RowMajorToColumnMajor(hzk16Bytes: ByteArray): ByteArray {
        val result = ByteArray(32)
        for (col in 0 until 16) {
            var lower = 0  // rows 0-7
            var upper = 0  // rows 8-15
            for (row in 0 until 16) {
                // HZK16 row-major: row k, byte j contains columns 0-7 (j=0) or 8-15 (j=1)
                val byteIdx = row * 2 + (if (col < 8) 0 else 1)
                val bitIdx = 7 - (col % 8)  // HZK16 bit 7 = column 0
                val bit = if (byteIdx < hzk16Bytes.size) {
                    (hzk16Bytes[byteIdx].toInt() shr bitIdx) and 1
                } else 0
                if (bit == 1) {
                    if (row < 8) {
                        lower = lower or (1 shl row)  // our bit 0 = row 0
                    } else {
                        upper = upper or (1 shl (row - 8))
                    }
                }
            }
            result[col * 2] = lower.toByte()
            result[col * 2 + 1] = upper.toByte()
        }
        return result
    }

    /**
     * 从 HZK16 获取字符的点阵数据
     * @param char 汉字字符
     * @param context Android Context (用于加载字体文件)
     * @return 32字节列优先点阵数据，或 null 如果获取失败
     */
    private fun getGlyphFromHZK16(char: Char, context: Context): ByteArray? {
        val hzkData = loadHZK16(context) ?: return null
        val offset = getCharOffset(char) ?: return null
        if (offset < 0 || offset + 32 > hzkData.size) {
            Log.w("LedMatrix-Render", "HZK16 查找失败: '$char' (offset=$offset, size=${hzkData.size})")
            return null
        }
        val hzk16Bytes = hzkData.copyOfRange(offset, offset + 32)
        Log.d("LedMatrix-Render", "HZK16 '$char' offset=$offset raw=${hzk16Bytes.joinToString { "%02X".format(it) }}")
        return hzk16RowMajorToColumnMajor(hzk16Bytes)
    }

    /**
     * 使用 Android 系统字体渲染 ASCII 字符为 16x16 列优先点阵数据
     * Monospace 等宽字体，自动支持所有 ASCII 可打印字符和标点
     */
    private fun getGlyphFromASCII(char: Char): ByteArray? {
        if (char.code > 0x7F) return null
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            typeface = Typeface.MONOSPACE
            isAntiAlias = false
        }
        val bmp = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(android.graphics.Color.BLACK) }
        val fm = paint.fontMetrics
        val totalH = fm.descent - fm.ascent
        val y = (32f - totalH) / 2f - fm.ascent
        canvas.drawText(char.toString(), 0f, y, paint)

        // 8x16 字符直接读取，不扩展
        val result = ByteArray(32)
        val srcStart = 0  // 字符在 bitmap 列 0-7

        for (col in 0 until 16) {
            val srcCol = if (col < 8) srcStart + col else srcStart + (col - 8)
            var lower = 0
            var upper = 0
            for (row in 0 until 16) {
                val px = bmp.getPixel(srcCol, row + 8)
                val lum = ((px shr 16) and 0xFF).coerceAtLeast((px shr 8) and 0xFF).coerceAtLeast(px and 0xFF)
                val bit = if (lum > 0x80) 1 else 0
                if (bit == 1) {
                    if (row < 8) {
                        lower = lower or (1 shl row)
                    } else {
                        upper = upper or (1 shl (row - 8))
                    }
                }
            }
            result[col * 2] = lower.toByte()
            result[col * 2 + 1] = upper.toByte()
        }
        bmp.recycle()
        return result
    }

    /**
     * 将列优先点阵数据转换为 IntArray (ARGB) 用于预览
     */
    private fun glyphToIntArray(glyph: ByteArray): IntArray {
        val result = IntArray(256) { Color.BLACK }
        for (col in 0 until 16) {
            val lower = glyph[col * 2].toInt() and 0xFF
            val upper = glyph[col * 2 + 1].toInt() and 0xFF
            for (row in 0 until 16) {
                val bit = if (row < 8) {
                    (lower shr row) and 1
                } else {
                    (upper shr (row - 8)) and 1
                }
                result[row * 16 + col] = if (bit == 1) Color.WHITE else Color.BLACK
            }
        }
        return result
    }

    /**
     * 交换左右半屏列：将左8列(col 0-7)与右8列(col 8-15)互换位置
     * 匹配设备实际像素排列顺序
     */
    private fun swapLeftRightHalves(glyph32: ByteArray): ByteArray {
        val result = ByteArray(32)
        for (col in 0 until 16) {
            val srcCol = if (col < 8) col + 8 else col - 8
            result[col * 2] = glyph32[srcCol * 2]
            result[col * 2 + 1] = glyph32[srcCol * 2 + 1]
        }
        return result
    }

    /**
     * 逆时针旋转90度
     * 原像素(col, row) → 新位置(row, 15-col)
     * 使得字符在LED屏幕物理方向上显示正确
     */
    private fun rotate90CCW(glyph32: ByteArray): ByteArray {
        val result = ByteArray(32)
        for (newCol in 0 until 16) {
            var lower = 0  // rows 0-7
            var upper = 0  // rows 8-15
            for (newRow in 0 until 16) {
                // 90° CCW: new(col,row) = old(15-row, col)
                val oldCol = 15 - newRow
                val oldRow = newCol
                val oldLower = glyph32[oldCol * 2].toInt() and 0xFF
                val oldUpper = glyph32[oldCol * 2 + 1].toInt() and 0xFF
                val bit = if (oldRow < 8) {
                    (oldLower shr oldRow) and 1
                } else {
                    (oldUpper shr (oldRow - 8)) and 1
                }
                if (bit == 1) {
                    if (newRow < 8) {
                        lower = lower or (1 shl newRow)
                    } else {
                        upper = upper or (1 shl (newRow - 8))
                    }
                }
            }
            result[newCol * 2] = lower.toByte()
            result[newCol * 2 + 1] = upper.toByte()
        }
        return result
    }

    /**
     * 将列优先点阵数据做屏幕显示修正：左右半屏交换 + 逆时针旋转90度
     */
    private fun fixGlyphForDisplay(glyph: ByteArray): ByteArray {
        val swapped = swapLeftRightHalves(glyph)
        return rotate90CCW(swapped)
    }

    /**
     * 渲染文本为列优先的点阵数据
     * 使用 HZK16C 字模 + ASCII 备选
     */
    fun renderTextToColumnMajor(text: String, context: Context): List<ByteArray> =
        text.mapNotNull { char ->
            val glyph = when {
                char.code < 0x80 -> {
                    Log.i("LedMatrix-Render", "ASCII: '$char'")
                    getGlyphFromASCII(char)
                }
                char.code >= 0x4E00 -> {
                    Log.i("LedMatrix-Render", "HZK16: '$char'")
                    getGlyphFromHZK16(char, context)?.also { glyph ->
                        Log.d("LedMatrix-Render", "  转换后=${glyph.joinToString { "%02X".format(it) }}")
                    }
                }
                else -> {
                    Log.w("LedMatrix-Render", "不支持的字符: '$char' (0x${char.code.toString(16)})")
                    null
                }
            }
            glyph?.let { fixGlyphForDisplay(it) } ?: run {
                // 使用方块代替无法渲染的字符
                Log.w("LedMatrix-Render", "使用占位符: '$char'")
                ByteArray(32) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x00.toByte() }
            }
        }

    fun renderTextToPanelRects(
        text: String,
        panelWidth: Int,
        panelHeight: Int,
        context: Context
    ): List<LedRect> {
        val glyphs = renderTextToColumnMajor(text, context)
        if (glyphs.isEmpty()) return emptyList()
        val normalizedText = text.trim()
        val startX = ((panelWidth - normalizedText.length * 16) / 2).coerceAtLeast(0)

        return buildList {
            normalizedText.forEachIndexed { index, _ ->
                val pixels = glyphToIntArray(glyphs.getOrNull(index) ?: return@forEachIndexed)
                val cellOffsetX = startX + index * 16
                for (y in 0 until panelHeight.coerceAtMost(16)) {
                    var x = 0
                    while (x < 16) {
                        val alpha = (pixels[y * 16 + x] ushr 24) and 0xFF
                        if (alpha < 0x60) {
                            x += 1
                            continue
                        }
                        val rectStartX = x
                        while (x < 16) {
                            val currentAlpha = (pixels[y * 16 + x] ushr 24) and 0xFF
                            if (currentAlpha < 0x60) break
                            x += 1
                        }
                        add(LedRect(x = cellOffsetX + rectStartX, y = y, width = x - rectStartX, height = 1))
                    }
                }
            }
        }
    }
}

internal data class LedRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

class LedMatrixManager(private val context: Context) {

    companion object {
        private const val TAG = "LedMatrix"
        private const val PREF_NAME = "CarrotAmap"
        private const val PREF_LED_ADDRESS = "led_device_address"
        private const val PREF_LED_NAME = "led_device_name"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val AUTO_SCAN_TIMEOUT_MS = 5_000L
        private const val SERVICE_UUID = "0000a800-0000-1000-8000-00805f9b34fb"
        private const val TX_UUID = "0000a802-0000-1000-8000-00805f9b34fb"
        private const val RX_UUID = "0000a801-0000-1000-8000-00805f9b34fb"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PAYLOAD_CMD: Byte = 0xCD.toByte()
        private const val PAYLOAD_DATA: Byte = 0xDA.toByte()
        private const val PAYLOAD_ERROR: Byte = 0xE0.toByte()
        private const val CMD_PLAY_CONTROL = 0x0A01
        private const val CMD_ASSET_DOWNLOAD = 0xD001
        private const val CMD_DEVICE_STATE = 0xC001
        private const val CMD_DEVICE_STATE_NOTIFY = 0xC081
        private const val CMD_DISPLAY_ONOFF_LEGACY = 0x0E01
        private const val CMD_BRIGHTNESS_LEGACY = 0x0E02
        private const val CMD_PROGRAM_EDIT = 0xED01
        private const val CMD_FILL_RECT = 0xD517
        private const val CMD_SYSTEM_CTRL = 0xC055
        private const val DEVICE_STATE_PLAYBACK = 0x01
        private const val CUSTOM_TEXT_LIMIT = 64  // HZK16C 支持最多约 256 个汉字
        private const val TEXT_PROGRAM_GROUP = 0
        private const val TEXT_PROGRAM_INDEX = 0
        private const val TEXT_ASSET_DATA_CHUNK = 146 // 每帧原始资产分片大小（官方抓包 DATA 分帧 153B 含头）
        // 资产上传后立即发送 ED01（无需等待超时），此常量已不再使用

        const val ANIM_STATIC = 0x00
        const val ANIM_LEFT = 0x23
        const val ANIM_RIGHT = 0x21
        const val ANIM_BLINK = 0x42

        private const val PANEL_WIDTH = 96
        private const val PANEL_HEIGHT = 16
        private val TEXT_ASSET_PREFIX = byteArrayOf(
            // 17-byte header (00 0C + 15 zeros)
            0x00, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00,
            // 6B embedded UUID at offset 17 (first 4B of asset UUID + 00 00)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // 54 00 at offset 23
            0x54, 0x00,
            // var1 + var2 at offset 25 (set in code)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Sub-elements at offset 33 (matching official exactly)
            0x02, 0x00, 0x02, 0x00, 0x02, 0x00, 0x03, 0x00,  // anim at [40]
            0x02, 0x00, 0x11, 0x05, 0x02, 0x00, 0x10, 0x03,
            0x02, 0x00, 0x12, 0x03, 0x02, 0x00, 0x20, 0x00,
            // Color element at offset 57: 03 00 21 [color_lo] [color_hi]
            // （02 00属于文本段头部，不在前缀中）
            0x03, 0x00, 0x21,
            0x00, 0x00
        )
        private val TEXT_ASSET_POST_TEXT = byteArrayOf(0x02, 0x00, 0x2B, 0x10, 0x02, 0x00, 0x2C, 0x01)

        @Volatile private var instance: LedMatrixManager? = null

        fun getInstance(context: Context): LedMatrixManager =
            instance ?: synchronized(this) {
                instance ?: LedMatrixManager(context.applicationContext).also { instance = it }
            }
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SENDING, ERROR }

    data class DisplayState(
        val text: String = "",
        val color: ComposeColor = ComposeColor.White,
        val animCode: Int = ANIM_STATIC,
        val bitmapData: List<ByteArray> = emptyList()
    )

    data class DeviceInfo(
        val model: String = "a800",
        val width: Int = PANEL_WIDTH,
        val height: Int = PANEL_HEIGHT
    )

    data class AutoDisplayData(
        val isOnroad: Boolean = false,
        val isNavigating: Boolean = false,
        val active: Boolean = false,
        val xState: Int = 0,
        val vEgoKph: Int = 0,
        val nSdiDist: Int = 0,
        val nSdiSpeedLimit: Int = 0,
        val nSdiType: Int = 0,
        val nTBTDist: Int = 0,
        val nTBTTurnType: Int = 0,
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
        val vTurnSpeed: Double = 0.0
    )

    private sealed interface DoodleContent {
        data object None : DoodleContent

        data class Text(
            val text: String,
            val color: ComposeColor,
            val colorRgb565: Int,
            val animCode: Int,
            val assetUuid: ByteArray,
            val assetBytes: ByteArray
        ) : DoodleContent
    }

    var state: State = State.IDLE
        private set
    var stateMessage: String = ""
        private set
    var onStateChanged: ((State, String) -> Unit)? = null

    val scannedDevices = mutableListOf<BluetoothDevice>()
    var onDevicesUpdated: (() -> Unit)? = null

    var deviceInfo: DeviceInfo? = null
        private set
    var onDeviceInfoUpdated: ((DeviceInfo) -> Unit)? = null
    var onReadyToDisplay: (() -> Unit)? = null

    private val _currentDisplayState = MutableStateFlow(DisplayState())
    val currentDisplayState: StateFlow<DisplayState> = _currentDisplayState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val prefs by lazy { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var scanning = false
    private var isRefreshingDisplay = false
    private var seqCounter = 0
    private var cuidCounter = 0
    private var displayGeneration = 0
    private var notifyBuffer = byteArrayOf()
    private var currentDoodleContent: DoodleContent = DoodleContent.None
    private var pendingTextAssetUuid: ByteArray? = null
    private var pendingTextAssetGeneration = 0
    @Volatile private var textUploadInProgress = false

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            updateState(State.ERROR, "缺少蓝牙权限")
            return
        }
        if (!isBluetoothEnabled()) {
            updateState(State.ERROR, "蓝牙未开启")
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            updateState(State.ERROR, "BLE不可用")
            return
        }
        scannedDevices.clear()
        scanning = true
        updateState(State.SCANNING, "扫描中...")
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        handler.postDelayed({ if (scanning) stopScan() }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (state == State.SCANNING) {
            updateState(State.IDLE, "扫描完成")
        }
    }

    @SuppressLint("MissingPermission")
    fun autoConnect(initText: String? = null, onNotFound: (() -> Unit)? = null) {
        if (state == State.CONNECTED || state == State.SENDING) {
            return
        }
        val savedAddress = prefs.getString(PREF_LED_ADDRESS, null)
        if (savedAddress.isNullOrBlank()) {
            onNotFound?.invoke()
            return
        }
        if (!hasPermissions() || !isBluetoothEnabled()) {
            onNotFound?.invoke()
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onNotFound?.invoke()
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                if (device.address == savedAddress) {
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }
                    connect(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                updateState(State.ERROR, "自动连接失败: $errorCode")
                onNotFound?.invoke()
            }
        }
        updateState(State.SCANNING, "自动连接...")
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback
        )
        handler.postDelayed({
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
            if (state == State.SCANNING) {
                updateState(State.IDLE, "")
                onNotFound?.invoke()
            }
        }, AUTO_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        cleanupGatt()
        saveDevice(device)
        deviceInfo = null
        txCharacteristic = null
        rxCharacteristic = null
        seqCounter = 0
        cuidCounter = 0
        notifyBuffer = byteArrayOf()
        updateState(State.CONNECTING, "连接 ${device.name ?: device.address}...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        cleanupGatt()
        updateState(State.IDLE, "已断开")
    }

    fun getSavedDeviceAddress(): String? = prefs.getString(PREF_LED_ADDRESS, null)

    fun destroy() {
        cleanupGatt()
        onStateChanged = null
        onDevicesUpdated = null
        onDeviceInfoUpdated = null
        onReadyToDisplay = null
        updateState(State.IDLE, "已销毁")
    }

    fun updateAutoDisplay(data: AutoDisplayData) {
    // 本版本只保留官方文本资产上传与节目播放流程，自动显示逻辑已停用。
    }

    fun sendCustomText(text: String, color: ComposeColor, animCode: Int = ANIM_STATIC): Boolean {
        val normalizedText = text.trim().take(CUSTOM_TEXT_LIMIT)
        if (normalizedText.isBlank()) {
            updateState(State.ERROR, "请输入文字")
            return false
        }
        if (bluetoothGatt == null || txCharacteristic == null) {
            updateState(State.ERROR, "请先连接LED点阵屏")
            return false
        }

        val glyphs = LedMatrixBitmapRenderer.renderTextToColumnMajor(normalizedText, context)
        if (glyphs.size != normalizedText.length || glyphs.isEmpty()) {
            updateState(State.ERROR, "文字渲染失败")
            return false
        }

        val assetUuid = createAssetUuid()
        val assetBytes = buildOfficialTextAsset(
            text = normalizedText,
            colorRgb565 = composeColorToRgb565(color),
            animCode = animCode,
            assetUuid = assetUuid,
            glyphs = glyphs
        )

        currentDoodleContent = DoodleContent.Text(
            text = normalizedText,
            color = color,
            colorRgb565 = composeColorToRgb565(color),
            animCode = animCode,
            assetUuid = assetUuid,
            assetBytes = assetBytes
        )
        uploadTextAsset(currentDoodleContent as DoodleContent.Text, "上传自定义节目", force = true)
        return true
    }

    /**
     * 调试发送：用官方抓包精确字形数据发送"你好你好"，完全匹配官方App协议格式。
     * 字形数据/颜色/动画均提取自 bt_hci_20260605_084652_d.cfa Asset 2 (UUID=D6D138100030)。
     * 协议流程: 0A01锁定 → D001下载(376B) → DATA分帧(160B+108B) → DATA_ACK → ED01编辑 → 0A01播放
     */
    fun sendDebugOfficialText(): Boolean {
        if (bluetoothGatt == null || txCharacteristic == null) {
            updateState(State.ERROR, "请先连接LED点阵屏")
            return false
        }
        val text = "你好你好"
        val normalizedText = text.trim().take(CUSTOM_TEXT_LIMIT)

        // 官方"你"字像素数据 (32B = 16列×2B, 列优先, [lower][upper])
        val niPixel = byteArrayOf(
            0x18, 0xC0.toByte(), 0x18, 0xC0.toByte(), 0x18, 0xFC.toByte(), 0x31, 0xFE.toByte(),
            0x31, 0x86.toByte(), 0x33, 0x0E, 0x36, 0x30, 0x70, 0x30,
            0xF1.toByte(), 0xBC.toByte(), 0x31, 0xB6.toByte(), 0x33, 0x36, 0x33, 0x32,
            0x36, 0x30, 0x30, 0x30, 0x30, 0xF0.toByte(), 0x30, 0x60
        )
        // 官方"好"字像素数据 (32B)
        val haoPixel = byteArrayOf(
            0x18, 0xFE.toByte(), 0x18, 0xFE.toByte(), 0x18, 0x06, 0x18, 0x0C,
            0xFE.toByte(), 0x18, 0xFE.toByte(), 0x30, 0x36, 0x30, 0x36, 0xFE.toByte(),
            0x36, 0xFE.toByte(), 0x6C.toByte(), 0x30, 0x3C, 0x30, 0x18, 0x30,
            0x3C, 0x30, 0x66.toByte(), 0x30, 0xC6.toByte(), 0xF0.toByte(), 0x00, 0x60
        )

        val assetUuid = createAssetUuid()
        val colorRgb565 = 0xFD20  // 橙色 orange (与官方Asset 2一致)
        val animCode = ANIM_STATIC

        val assetBytes = buildOfficialTextAsset(
            text = normalizedText,
            colorRgb565 = colorRgb565,
            animCode = animCode,
            assetUuid = assetUuid,
            glyphs = listOf(niPixel, haoPixel, niPixel, haoPixel)  // 你好你好 (4字=2组)
        )

        currentDoodleContent = DoodleContent.Text(
            text = normalizedText,
            color = ComposeColor(0xFFFFA500.toInt()),
            colorRgb565 = colorRgb565,
            animCode = animCode,
            assetUuid = assetUuid,
            assetBytes = assetBytes
        )
        uploadTextAsset(currentDoodleContent as DoodleContent.Text, "调试发送(官方字形)", force = true)
        Log.i(TAG, "调试发送: 你好你好 橙色(0xFD20) 静态 官方精确字形")
        return true
    }

    /**
     * 调试发送：用 Android 渲染字形，但使用完整的 16 列像素数据 (render_w=16, perGlyphSize=40)。
     * 用于排查协议期望的是否是 16 列而非 14 列。
     */
    fun sendDebug16Cols(): Boolean {
        if (bluetoothGatt == null || txCharacteristic == null) {
            updateState(State.ERROR, "请先连接LED点阵屏")
            return false
        }
        val text = "你好"
        val normalizedText = text.trim().take(CUSTOM_TEXT_LIMIT)
        val glyphs = LedMatrixBitmapRenderer.renderTextToColumnMajor(normalizedText, context)
        if (glyphs.size != normalizedText.length || glyphs.isEmpty()) {
            updateState(State.ERROR, "文字渲染失败")
            return false
        }

        val assetUuid = createAssetUuid()
        val colorRgb565 = 0x07FF
        val animCode = ANIM_STATIC

        // Build asset with all 16 columns (perGlyphSize=40)
        val assetBytes = buildTextAsset16Cols(
            text = normalizedText,
            colorRgb565 = colorRgb565,
            animCode = animCode,
            assetUuid = assetUuid,
            glyphs = glyphs
        )

        currentDoodleContent = DoodleContent.Text(
            text = normalizedText,
            color = ComposeColor(0xFF00FFFF.toInt()),
            colorRgb565 = colorRgb565,
            animCode = animCode,
            assetUuid = assetUuid,
            assetBytes = assetBytes
        )
        uploadTextAsset(currentDoodleContent as DoodleContent.Text, "调试发送(16列)", force = true)
        Log.i(TAG, "调试发送: 你好 青色 16列像素数据")
        return true
    }

    /**
     * 备选资产构建：使用 16 列像素数据 (render_w=16, perGlyphSize=40)。
     */
    private fun buildTextAsset16Cols(
        text: String,
        colorRgb565: Int,
        animCode: Int,
        assetUuid: ByteArray,
        glyphs: List<ByteArray>
    ): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val glyphCount = glyphs.size
        Log.i(TAG, "build16Cols: text='$text' glyphCount=$glyphCount")

        val textSectionSize = 23 + utf8.size  // 无分隔字节
        val gcBytes = when {
            glyphCount <= 0xFF -> byteArrayOf(0, glyphCount.toByte())  // BE16, 最少2B
            glyphCount <= 0xFFFF -> byteArrayOf(
                ((glyphCount shr 8) and 0xFF).toByte(),
                (glyphCount and 0xFF).toByte()
            )
            else -> byteArrayOf(
                ((glyphCount shr 16) and 0xFF).toByte(),
                ((glyphCount shr 8) and 0xFF).toByte(),
                (glyphCount and 0xFF).toByte()
            )
        }
        Log.i(TAG, "  build16Cols gcBytes(${gcBytes.size}B): ${gcBytes.joinToString(""){"%02X".format(it)}} (gc=$glyphCount)")
        val varintSize = gcBytes.size
        val perGlyphSize = 36  // cell_w(2) + cell_h(2) + pixel(32B)
        val xPosTableSize = glyphCount * 4
        val glyphDataSize = glyphCount * perGlyphSize
        val payloadSize = TEXT_ASSET_PREFIX.size + textSectionSize + 1 + varintSize + 1 + xPosTableSize + glyphDataSize
        val assetSize = 7 + payloadSize

        val var2 = 50 + utf8.size + varintSize  // var2 = xpos_start - 37
        val var1 = assetSize - 32

        val prefix = TEXT_ASSET_PREFIX.copyOf()
        assetUuid.copyInto(prefix, destinationOffset = 17, startIndex = 0, endIndex = 4)
        le32(var1).copyInto(prefix, 25)
        le32(var2).copyInto(prefix, 29)
        prefix[40] = animCode.toByte()
        prefix[60] = (colorRgb565 and 0xFF).toByte()
        prefix[61] = ((colorRgb565 shr 8) and 0xFF).toByte()

        val payload = ByteArrayOutputStream()
        payload.write(prefix)
        payload.write(byteArrayOf(0x02, 0x00, 0x22, 0x00, 0x02, 0x00, 0x23, 0x02))
        payload.write(byteArrayOf(0x02, 0x00, 0x24, 0x00))
        payload.write(le16(utf8.size + 1))
        payload.write(byteArrayOf(0x2A))
        payload.write(utf8)
        payload.write(byteArrayOf(0x02, 0x00, 0x2B, 0x10))              // spacing (4B)
        payload.write(byteArrayOf(0x02, 0x00, 0x2C, 0x01))              // alignment (4B)
        payload.write(byteArrayOf(0x00))                                 // separator (1B)
        payload.write(gcBytes)                                            // glyphCount (最小BE编码)
        payload.write(byteArrayOf(0x00))                                 // pad (1B)

        val entriesStart = payload.size() + xPosTableSize

        glyphs.indices.forEach { index ->
            payload.write(le32(entriesStart + index * perGlyphSize - 17))
        }
        glyphs.forEach { glyph ->
            // 官方格式: cell_w(LE16, 2B) + cell_h(LE16, 2B) + 列优先位图(32B) = 36B
            val cellW = calcCellWidth(glyph).coerceIn(1, 16)
            payload.write(le16(cellW))
            payload.write(le16(16))
            payload.write(glyph, 0, 32) // 完整 32B (16列×2B)
        }

        val raw = ByteArrayOutputStream()
        raw.write(assetUuid)
        raw.write(0x01)
        raw.write(payload.toByteArray())
        return raw.toByteArray()
    }

    private fun updateState(newState: State, message: String) {
        state = newState
        stateMessage = message
        handler.post { onStateChanged?.invoke(newState, message) }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        displayGeneration += 1
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
        notifyBuffer = byteArrayOf()
        isRefreshingDisplay = false
        textUploadInProgress = false
        pendingTextAssetUuid = null
    }

    @SuppressLint("MissingPermission")
    private fun saveDevice(device: BluetoothDevice) {
        prefs.edit()
            .putString(PREF_LED_ADDRESS, device.address)
            .putString(PREF_LED_NAME, device.name ?: device.address)
            .apply()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (scannedDevices.none { it.address == device.address }) {
                scannedDevices.add(device)
                handler.post { onDevicesUpdated?.invoke() }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateState(State.ERROR, "扫描失败: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "连接状态异常 status=$status newState=$newState")
                cleanupGatt()
                updateState(State.ERROR, "连接失败: $status")
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestMtu(247)
                    } else {
                        gatt.discoverServices()
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    cleanupGatt()
                    updateState(State.IDLE, "连接已断开")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateState(State.ERROR, "发现服务失败: $status")
                return
            }
            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            val tx = service?.getCharacteristic(UUID.fromString(TX_UUID))
            val rx = service?.getCharacteristic(UUID.fromString(RX_UUID))
            if (service == null || tx == null || rx == null) {
                updateState(State.ERROR, "未找到 a800 LED 服务")
                return
            }
            txCharacteristic = tx
            rxCharacteristic = rx
            writeType = if ((tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            gatt.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onNotificationsEnabled()
                return
            }
            val enableValue = if ((rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enableValue)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "通知订阅成功")
                onNotificationsEnabled()
            } else {
                updateState(State.ERROR, "通知订阅失败: $status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleNotification(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }
    }

    private fun onNotificationsEnabled() {
        val info = DeviceInfo()
        deviceInfo = info
        handler.post { onDeviceInfoUpdated?.invoke(info) }
        updateState(State.CONNECTED, "已连接")
        sendInitialDisplaySequence()
    }

    private fun sendInitialDisplaySequence() {
        if (bluetoothGatt == null || txCharacteristic == null) return
        val generation = ++displayGeneration
        isRefreshingDisplay = true
        updateState(State.SENDING, "初始化屏幕...")

        // 仅基础初始化（开机、亮度、显示），不再进入涂鸦模式
        // 用户发送文字时走完整的资产上传流程
        scheduleDisplayCommand(generation, 0L) { sendPowerOn() }
        scheduleDisplayCommand(generation, 120L) { sendLegacyBrightness(0xFF) }
        scheduleDisplayCommand(generation, 220L) { sendLegacyScreenOn(true) }
        scheduleDisplayCommand(generation, 300L) {
            isRefreshingDisplay = false
            updateState(State.CONNECTED, "已连接，可发送文字")
        }
    }

    private fun refreshCurrentDoodleContent(reason: String, force: Boolean = false) {
        if (currentDoodleContent is DoodleContent.Text || currentDoodleContent is DoodleContent.None) return
        renderCurrentDoodleContent(
            reason = reason,
            includePowerOn = false,
            force = force
        )
    }

    private fun renderCurrentDoodleContent(
        reason: String,
        includePowerOn: Boolean,
        force: Boolean
    ) {
        if (bluetoothGatt == null || txCharacteristic == null) return
        if (isRefreshingDisplay && !force) return

        val content = currentDoodleContent
        val generation = ++displayGeneration
        isRefreshingDisplay = true
        if (!includePowerOn) {
            Log.i(TAG, "重新抢占涂鸦模式: $reason")
        }

        if (content is DoodleContent.None) return

        val shouldRedrawText = content !is DoodleContent.Text || includePowerOn || reason == "上传自定义节目" || reason == "检测到设备切回节目轮播"

        updateState(
            State.SENDING,
            when (content) {
                is DoodleContent.Text -> if (shouldRedrawText) "上传节目中..." else "保持文字静态显示..."
                else -> return
            }
        )

        var delayMs = 0L
        if (includePowerOn) {
            scheduleDisplayCommand(generation, delayMs) { sendPowerOn() }
            delayMs += 120L
            scheduleDisplayCommand(generation, delayMs) { sendLegacyBrightness(0xFF) }
            delayMs += 100L
        }

        scheduleDisplayCommand(generation, delayMs) { sendLegacyScreenOn(true) }
        delayMs += 80L
        scheduleDisplayCommand(generation, delayMs) { sendDoodleMode() }
        delayMs += 80L

        when (content) {
            is DoodleContent.Text -> {
                if (includePowerOn) {
                    scheduleDisplayCommand(generation, delayMs) { sendPlaybackAllLoop() }
                    delayMs += 120L
                    scheduleDisplayCommand(generation, delayMs) { sendPlaybackSingleLoop() }
                    delayMs += 100L
                }
                if (shouldRedrawText) {
                    scheduleDisplayCommand(generation, delayMs) {
                        uploadTextAsset(content, reason, force = true)
                    }
                }
            }
            else -> return
        }

        scheduleDisplayCommand(generation, delayMs) {
            _currentDisplayState.value = when (content) {
                is DoodleContent.Text -> DisplayState(
                    text = content.text,
                    color = content.color,
                    animCode = content.animCode,
                    bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(content.text, context)
                )
            }
            onReadyToDisplay?.invoke()
            isRefreshingDisplay = false
            updateState(
                State.CONNECTED,
                when (content) {
                    is DoodleContent.Text -> "自定义文字已显示"
                    else -> "涂鸦模式已开启"
                }
            )
        }
    }

    private fun scheduleDisplayCommand(generation: Int, delayMs: Long, action: () -> Unit) {
        handler.postDelayed({
            if (generation != displayGeneration) return@postDelayed
            if (bluetoothGatt == null || txCharacteristic == null) return@postDelayed
            action()
        }, delayMs)
    }

    private fun handleNotification(value: ByteArray) {
        Log.i(TAG, "收到通知: ${value.joinToString("") { "%02X".format(it) }}")
        notifyBuffer += value
        var offset = 0

        while (notifyBuffer.size - offset >= 2) {
            val payloadLength = notifyBuffer[offset + 1].toInt() and 0xFF
            val frameLength = payloadLength + 2
            if (payloadLength < 3 || notifyBuffer.size - offset < frameLength) {
                break
            }

            val payload = notifyBuffer.copyOfRange(offset + 2, offset + frameLength)
            handleNotificationPayload(payload)
            offset += frameLength
        }

        notifyBuffer = if (offset == 0) {
            notifyBuffer
        } else if (offset >= notifyBuffer.size) {
            byteArrayOf()
        } else {
            notifyBuffer.copyOfRange(offset, notifyBuffer.size)
        }
    }

    private fun handleNotificationPayload(payload: ByteArray) {
        val payloadType = payload[0]
        if (payloadType == PAYLOAD_ERROR && payload.size >= 5) {
            val errorCode = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
            Log.w(TAG, "设备返回错误码: $errorCode")
            return
        }
        if (payloadType != PAYLOAD_CMD || payload.size < 5) return
        val command = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
        if (command != CMD_DEVICE_STATE_NOTIFY || payload.size < 8) return
        val notifyCmd = payload[5].toInt() and 0xFF
        val deviceState = payload[6].toInt() and 0xFF
        val notifiedUuid = if (payload.size >= 15) payload.copyOfRange(9, 15) else null
        val pendingUuid = pendingTextAssetUuid
        if (notifyCmd == 0x80 && pendingUuid != null && notifiedUuid != null && notifiedUuid.contentEquals(pendingUuid)) {
            onTextAssetUploaded()
            return
        }
        if (notifyCmd == 0x80 && deviceState == DEVICE_STATE_PLAYBACK) {
            val activeText = currentDoodleContent as? DoodleContent.Text
            if (activeText != null) {
                if (notifiedUuid == null || !notifiedUuid.contentEquals(activeText.assetUuid)) {
                    replayUploadedText("检测到设备切回其他节目")
                }
            }
        }
    }

    private fun sendPowerOn() {
        sendCommand(CMD_DEVICE_STATE, byteArrayOf(0x01, 0xFF.toByte()))
    }

    private fun sendDoodleMode() {
        sendCommand(CMD_DEVICE_STATE, byteArrayOf(0x01, 0x03))
    }

    private fun sendLegacyScreenOn(on: Boolean) {
        sendCommand(CMD_DISPLAY_ONOFF_LEGACY, byteArrayOf(if (on) 0x01 else 0x00))
    }

    private fun sendLegacyBrightness(level: Int) {
        sendCommand(CMD_BRIGHTNESS_LEGACY, byteArrayOf(level.coerceIn(0, 0xFF).toByte()))
    }

    private fun sendPlaybackSingleLoop() {
        sendCommand(
            CMD_PLAY_CONTROL,
            byteArrayOf(0x01, 0x53, 0x01, 0x00, 0x00)  // 官方格式: [play_once][single][program_idx=1][group=0][pad]
        )
        Log.i(TAG, "已设置单循环")
    }

    private fun sendPlaybackAllLoop() {
        sendCommand(CMD_PLAY_CONTROL, byteArrayOf(0x01, 0x41, 0x00))  // 官方格式: [play_once][all_loop][pad]
        Log.i(TAG, "已设置全部循环播放")
    }

    private fun uploadTextAsset(content: DoodleContent.Text, reason: String, force: Boolean) {
        if (bluetoothGatt == null || txCharacteristic == null) return
        if (isRefreshingDisplay && !force) return
        displayGeneration += 1
        val generation = displayGeneration
        isRefreshingDisplay = true
        textUploadInProgress = true
        pendingTextAssetUuid = content.assetUuid
        pendingTextAssetGeneration = generation

        Log.i(TAG, "按官方资产流程发送文本: $reason")
        updateState(State.SENDING, "上传节目中...")

        // 1) 0A01: 锁定空节目（group=0, prog=0），准备资产上传
        scheduleDisplayCommand(generation, 0L) {
            sendCommand(CMD_PLAY_CONTROL, byteArrayOf(0x01, 0x53, 0x00, 0x00, 0x00))
        }
        val rawAsset = content.assetBytes  // 原始资产: UUID(6) + type(1) + payload(N)
        var d001Cuid = 0

        // 2) D001: 开始资产下载，start_cuid = D001 自身的 cuid
        scheduleDisplayCommand(generation, 100L) {
            d001Cuid = cuidCounter  // 在 sendCommand 消费 cuid 前快照
            sendCommand(
                command = CMD_ASSET_DOWNLOAD,
                args = le32(rawAsset.size) + le16(d001Cuid) + content.assetUuid
            )
        }

        val chunkSize = TEXT_ASSET_DATA_CHUNK  // 每帧原始资产分片大小

        var delayMs = 260L
        var dataCuidOffset = 1
        // 3) DATA 分帧：直接发送原始资产的分片（官方协议不添加额外头，第1帧自然包含 UUID+type）
        rawAsset.toList().chunked(chunkSize).forEach { chunkList ->
            val offset = dataCuidOffset
            val chunkBytes = chunkList.toByteArray()
            scheduleDisplayCommand(generation, delayMs) {
                sendDataFrame(d001Cuid + offset, chunkBytes)
            }
            delayMs += 120L
            dataCuidOffset += 1
        }
        // 4) ACK (cuid=0, 空内容)
        val ackDelay = delayMs
        scheduleDisplayCommand(generation, ackDelay) { sendDataFrame(0, byteArrayOf()) }
        // 官方流程：DATA ACK 后直接发送 ED01 节目编辑命令（无需等待 C081 通知）
        // 抓包验证(bt_hci_20260605_084652_d.cfa):
        //   D001 → DATA ×N → DATA ACK → ED01(节目编辑) → C081(解码完成通知) → 0A01(切换播放)
        scheduleDisplayCommand(generation, ackDelay + 200L) { onTextAssetUploaded() }
    }

    private fun onTextAssetUploaded() {
        val activeText = currentDoodleContent as? DoodleContent.Text ?: return
        pendingTextAssetUuid = null
        textUploadInProgress = false
        val generation = pendingTextAssetGeneration
        scheduleDisplayCommand(generation, 0L) {
            sendCommand(
                command = CMD_PROGRAM_EDIT,
                args = byteArrayOf(TEXT_PROGRAM_GROUP.toByte(), TEXT_PROGRAM_INDEX.toByte(), 0x01) +
                    byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
                    activeText.assetUuid
            )
            Log.i(TAG, "已编辑节目: 组$TEXT_PROGRAM_GROUP 节目$TEXT_PROGRAM_INDEX")
        }
        scheduleDisplayCommand(generation, 120L) {
            sendPlaybackSingleLoop()
        }
        scheduleDisplayCommand(generation, 300L) {
            _currentDisplayState.value = DisplayState(
                text = activeText.text,
                color = activeText.color,
                animCode = activeText.animCode,
                bitmapData = LedMatrixBitmapRenderer.renderTextToColumnMajor(activeText.text, context)
            )
            onReadyToDisplay?.invoke()
            isRefreshingDisplay = false
            updateState(State.CONNECTED, "节目已加载")
        }
    }

    private fun replayUploadedText(reason: String) {
        val activeText = currentDoodleContent as? DoodleContent.Text ?: return
        Log.i(TAG, "重新播放自定义文字: $reason")
        uploadTextAsset(activeText, reason, force = true)
    }

    private fun sendPanelFill(colorRgb565: Int, x: Int, y: Int, width: Int, height: Int) {
        val args = le16(colorRgb565) +
            byteArrayOf(0x00) +
            le16(x) +
            le16(y) +
            le16(width) +
            le16(height)
        sendCommand(CMD_FILL_RECT, args)
    }

    /**
     * 在屏幕中间点亮蓝色灯带（第6-10列）
     */
    fun fillBlueStrip() {
        if (bluetoothGatt == null || txCharacteristic == null) {
            updateState(State.ERROR, "请先连接LED点阵屏")
            return
        }
        val blue565 = 0x001F  // RGB565 Blue
        sendPanelFill(blue565, 6, 0, 5, 16)
        Log.i(TAG, "已发送蓝色灯带: cols 6-10")
    }

    /**
     * 格式化 Flash (C055 01 FE)，最彻底的恢复方式。
     * 执行后设备所有数据被清除，需重新发送文字。
     */
    fun formatFlash() {
        sendCommand(CMD_SYSTEM_CTRL, byteArrayOf(0x01, 0xFE.toByte()))
        Log.i(TAG, "已发送格式化 Flash 命令")
    }

    private fun sendDataFrame(cuid: Int, content: ByteArray) {
        val payload = byteArrayOf(PAYLOAD_DATA) + le16(cuid) + content
        writeRawFrame(payload)
    }

    private fun sendCommand(command: Int, args: ByteArray) {
        val payload = byteArrayOf(PAYLOAD_CMD) + le16(nextCuid()) + le16(command) + args
        writeRawFrame(payload)
    }

    @SuppressLint("MissingPermission")
    private fun writeRawFrame(payload: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val tx = txCharacteristic ?: return
        val frame = byteArrayOf(nextSeq(), payload.size.toByte()) + payload
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(tx, frame, writeType)
            } else {
                @Suppress("DEPRECATION")
                tx.writeType = writeType
                @Suppress("DEPRECATION")
                tx.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(tx)
            }
            Log.i(TAG, "写入 ${frame.size}B: ${frame.joinToString("") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "写入失败: ${e.message}", e)
            updateState(State.ERROR, "发送失败")
        }
    }

    private fun nextSeq(): Byte {
        val value = seqCounter and 0xFF
        seqCounter = (seqCounter + 1) and 0xFF
        return value.toByte()
    }

    private fun nextCuid(): Int {
        val value = cuidCounter and 0xFFFF
        cuidCounter = (cuidCounter + 1) and 0xFFFF
        return value
    }

    private fun createAssetUuid(): ByteArray {
        val cuid = nextCuid()
        return byteArrayOf(
            ((System.currentTimeMillis() shr 16) and 0xFF).toByte(),
            ((System.currentTimeMillis() shr 8) and 0xFF).toByte(),
            (System.currentTimeMillis() and 0xFF).toByte(),
            (cuid and 0xFF).toByte(),
            ((cuid shr 8) and 0xFF).toByte(),
            0x30
        )
    }

    /**
     * 从 32B 列优先位图数据计算实际字符宽度。
     * 从右侧向左扫描空列，第一个非空列的索引+1 即为宽度。
     */
    private fun calcCellWidth(glyph32: ByteArray): Int {
        for (col in (0 until 16).reversed()) {
            val lower = glyph32[col * 2].toInt() and 0xFF
            val upper = glyph32[col * 2 + 1].toInt() and 0xFF
            if (upper != 0 || lower != 0) {
                return (col + 1).coerceIn(1, 16)
            }
        }
        return 1  // 全空列（空格等）
    }

    /** 交换glyph每列的字节顺序: [upper][lower] → [lower][upper] */
    private fun swapGlyphByteOrder(raw: ByteArray): ByteArray {
        require(raw.size == 32)
        val result = ByteArray(32)
        for (col in 0 until 16) {
            // 原始: [col_hi][col_lo], 目标: [col_lo][col_hi]
            result[col * 2] = raw[col * 2 + 1]  // lower
            result[col * 2 + 1] = raw[col * 2]   // upper
        }
        return result
    }

    /**
     * 计算 render_w（实际渲染宽度）。
     * render_w = 像素数据中从 col 1 开始最右侧非空列的索引。
     * 像素数据固定为 14 列(col 1..14)，render_w 告诉设备实际字符占几列。
     * 中文=14, 窄字符=更小。
     */
    private fun calcRenderWidth(glyph32: ByteArray): Int {
        for (col in (1 until 15).reversed()) {  // 只查 cols 1..14
            val upper = glyph32[col * 2].toInt() and 0xFF
            val lower = glyph32[col * 2 + 1].toInt() and 0xFF
            if (upper != 0 || lower != 0) {
                return col.coerceIn(1, 14)
            }
        }
        return 1
    }

    private fun buildOfficialTextAsset(
        text: String,
        colorRgb565: Int,
        animCode: Int,
        assetUuid: ByteArray,
        glyphs: List<ByteArray>
    ): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val glyphCount = glyphs.size
        Log.i(TAG, "buildAsset: text='$text' utf8.size=${utf8.size} utf8=${utf8.joinToString("") { "%02X".format(it) }} glyphCount=$glyphCount")

        // Pre-calculate sizes for var1/var2
        val textSectionSize = 23 + utf8.size  // 23B = 8(text headers) + 4(02 00 24 00) + 02(len) + 01(2A) + 04(spacing) + 04(alignment)
        // 最小大端编码: big-endian, 最少 2 字节
        val gcBytes = when {
            glyphCount <= 0xFF -> byteArrayOf(0, glyphCount.toByte())  // BE16, 最少2B
            glyphCount <= 0xFFFF -> byteArrayOf(
                ((glyphCount shr 8) and 0xFF).toByte(),
                (glyphCount and 0xFF).toByte()
            )
            else -> byteArrayOf(
                ((glyphCount shr 16) and 0xFF).toByte(),
                ((glyphCount shr 8) and 0xFF).toByte(),
                (glyphCount and 0xFF).toByte()
            )
        }
        Log.i(TAG, "  gcBytes(${gcBytes.size}B): ${gcBytes.joinToString(""){"%02X".format(it)}} (gc=$glyphCount)")
        val varintSize = gcBytes.size  // 最小BE编码字节数
        val perGlyphSize = 36  // cell_w(2) + cell_h(2) + pixel(32B)
        val glyphDataSize = glyphCount * perGlyphSize
        val xPosTableSize = glyphCount * 4
        val payloadSize = TEXT_ASSET_PREFIX.size + textSectionSize + 1 + varintSize + 1 + xPosTableSize + glyphDataSize
        val assetSize = 7 + payloadSize  // UUID(6) + type(1) + payload

        val var2 = 50 + utf8.size + varintSize  // var2 = xpos_start - 37 = (62 + textSectionSize + 1 + varintSize + 1) - 37
        val var1 = assetSize - 32  // 官方固定偏移32

        // Build prefix with dynamic values (17-byte header)
        val prefix = TEXT_ASSET_PREFIX.copyOf()
        assetUuid.copyInto(prefix, destinationOffset = 17, startIndex = 0, endIndex = 4)
        le32(var1).copyInto(prefix, 25)
        le32(var2).copyInto(prefix, 29)
        prefix[40] = animCode.toByte()
        prefix[60] = (colorRgb565 and 0xFF).toByte()           // RGB565 低字节 (LE)
        prefix[61] = ((colorRgb565 shr 8) and 0xFF).toByte()   // RGB565 高字节 (LE)

        val payload = ByteArrayOutputStream()
        payload.write(prefix)
        // Official text section format
        payload.write(byteArrayOf(0x02, 0x00, 0x22, 0x00, 0x02, 0x00, 0x23, 0x02))  // text block header (8B)
        payload.write(byteArrayOf(0x02, 0x00, 0x24, 0x00))              // text content marker (4B)
        payload.write(le16(utf8.size + 1))                               // text content length (2B)
        payload.write(byteArrayOf(0x2A))                                 // marker byte (1B)
        payload.write(utf8)                                               // UTF-8 text (N)
        payload.write(byteArrayOf(0x02, 0x00, 0x2B, 0x10))              // spacing (4B)
        payload.write(byteArrayOf(0x02, 0x00, 0x2C, 0x01))              // alignment (4B)
        payload.write(byteArrayOf(0x00))                                 // separator (1B)
        payload.write(gcBytes)                                            // glyphCount (最小BE编码)
        payload.write(byteArrayOf(0x00))                                 // pad (1B)

        val entriesStart = payload.size() + xPosTableSize
        glyphs.indices.forEach { index ->
            payload.write(le32(entriesStart + index * perGlyphSize - 17))
        }
        glyphs.forEachIndexed { index, glyph ->
            // 官方格式: cell_w(LE16, 2B) + cell_h(LE16, 2B) + 列优先位图(32B) = 36B
            // 无 fmt/render_w 字段（官方抓包确认）
            val cellW = calcCellWidth(glyph).coerceIn(1, 16)
            payload.write(le16(cellW))
            payload.write(le16(16))
            payload.write(glyph)  // 完整 32B (16列×2B)
        }

        val raw = ByteArrayOutputStream()
        raw.write(assetUuid)
        raw.write(0x01)
        raw.write(payload.toByteArray())
        return raw.toByteArray()
    }

    private fun composeColorToRgb565(color: ComposeColor): Int {
        val red = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val green = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val blue = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        return ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)
    }

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}
