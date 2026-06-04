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
import java.util.UUID

internal object LedMatrixBitmapRenderer {
    fun renderTextToColumnMajor(text: String): List<ByteArray> =
        text.take(6).map { char ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
                textSize = 14f
            }
            val fm = paint.fontMetrics
            val textY = (16 - fm.top - fm.bottom) / 2f
            canvas.drawText(char.toString(), 8f, textY, paint)

            val pixels = IntArray(16 * 16)
            bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            bitmap.recycle()

            val result = ByteArray(32)
            for (col in 0 until 16) {
                var upper = 0
                var lower = 0
                for (row in 0 until 16) {
                    val alpha = (pixels[row * 16 + col] ushr 24) and 0xFF
                    if (alpha > 0x40) {
                        if (row < 8) upper = upper or (1 shl row) else lower = lower or (1 shl (row - 8))
                    }
                }
                result[col * 2] = upper.toByte()
                result[col * 2 + 1] = lower.toByte()
            }
            result
        }
}

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
        private const val CMD_DEVICE_STATE = 0xC001
        private const val CMD_DISPLAY_ONOFF_LEGACY = 0x0E01
        private const val CMD_BRIGHTNESS_LEGACY = 0x0E02
        private const val CMD_FILL_RECT = 0xD517

        private const val PANEL_WIDTH = 96
        private const val PANEL_HEIGHT = 16
        private const val BLUE_BAND_HEIGHT = 6
        private const val BLUE_BAND_Y = (PANEL_HEIGHT - BLUE_BAND_HEIGHT) / 2
        private const val BLUE_RGB565 = 0x001F

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
        val animCode: Int = 0,
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
    private var hasDrawnBlueBand = false
    private var seqCounter = 0
    private var cuidCounter = 0

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
        hasDrawnBlueBand = false
        deviceInfo = null
        txCharacteristic = null
        rxCharacteristic = null
        seqCounter = 0
        cuidCounter = 0
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
        // 本版本只保留“连接后点亮蓝色中带”功能，自动显示逻辑已停用。
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
        hasDrawnBlueBand = false
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
                onNotificationsEnabled()
            } else {
                updateState(State.ERROR, "通知订阅失败: $status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            Log.d(TAG, "收到通知: ${characteristic.value?.joinToString("") { "%02X".format(it) }}")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "收到通知: ${value.joinToString("") { "%02X".format(it) }}")
        }
    }

    private fun onNotificationsEnabled() {
        val info = DeviceInfo()
        deviceInfo = info
        handler.post { onDeviceInfoUpdated?.invoke(info) }
        updateState(State.CONNECTED, "已连接")
        sendBlueBandSequence()
    }

    private fun sendBlueBandSequence() {
        if (hasDrawnBlueBand) return
        hasDrawnBlueBand = true
        updateState(State.SENDING, "点亮蓝条中...")
        handler.postDelayed({ sendPowerOn() }, 0)
        handler.postDelayed({ sendLegacyBrightness(0xFF) }, 120)
        handler.postDelayed({ sendLegacyScreenOn(true) }, 220)
        handler.postDelayed({ sendDoodleMode() }, 360)
        handler.postDelayed({ sendCenterBlueBand() }, 520)
        handler.postDelayed({
            _currentDisplayState.value = DisplayState(
                text = "BLUE BAND",
                color = ComposeColor(0xFF3388FF),
                animCode = 0,
                bitmapData = emptyList()
            )
            onReadyToDisplay?.invoke()
            updateState(State.CONNECTED, "蓝色中带已点亮")
        }, 700)
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

    private fun sendCenterBlueBand() {
        val args = le16(BLUE_RGB565) +
            byteArrayOf(0x00) +
            le16(0) +
            le16(BLUE_BAND_Y) +
            le16(PANEL_WIDTH) +
            le16(BLUE_BAND_HEIGHT)
        sendCommand(CMD_FILL_RECT, args)
        Log.i(TAG, "已发送蓝色中带: 96x6 @ y=$BLUE_BAND_Y")
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

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}
