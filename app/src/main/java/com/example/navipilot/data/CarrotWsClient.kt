package com.example.navipilot.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import okio.ByteString
import kotlin.math.min
import org.json.JSONObject

/**
 * Carrot WebSocket 客户端
 *
 * 连接到 comma3 设备上的 carrot server (端口 7000)，
 * 通过 /ws/raw_multiplex 获取实时 cereal 数据，
 * 通过 /ws/camera/road 获取摄像头画面。
 */
class CarrotWsClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "CarrotWsClient"
        private const val WS_RAW_PATH = "/ws/raw_multiplex"
        private const val WS_CAMERA_PATH = "/ws/camera/road"
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L
        private const val DATA_TIMEOUT_MS = 4000L

        /** 订阅的 cereal 服务列表 */
        val DEFAULT_SERVICES = listOf(
            "carState",
            "modelV2",
            "controlsState",
            "selfdriveState",
            "deviceState",
            "carrotMan",
            "gpsLocationExternal"
        )
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var dataWs: WebSocket? = null
    private var cameraWs: WebSocket? = null
    private var reconnectJob: Job? = null
    private var timeoutCheckJob: Job? = null

    /** 连接状态 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    /** 最后一次数据接收时间 */
    private var lastDataTimeMs = 0L

    /** 数据超时状态 */
    private val _isDataTimeout = MutableStateFlow(false)
    val isDataTimeout = _isDataTimeout.asStateFlow()

    /** 实时车辆数据（兼容 XiaogeVehicleData 结构） */
    private val _vehicleData = MutableStateFlow<VehicleData?>(null)
    val vehicleData = _vehicleData.asStateFlow()

    /** 设备状态 */
    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus = _deviceStatus.asStateFlow()

    /** 摄像头帧 */
    private val _cameraFrame = MutableStateFlow<CameraFrame?>(null)
    val cameraFrame = _cameraFrame.asStateFlow()

    /** 摄像头帧回调 */
    var onCameraFrame: ((CameraFrame) -> Unit)? = null

    // ===================== 公共 API =====================

    fun connect(host: String) {
        val cleanHost = host.trim().replace("http://", "").replace("https://", "").split("/").first()
        _connectionState.value = ConnectionState.CONNECTING

        // 数据 WebSocket
        val servicesParam = DEFAULT_SERVICES.joinToString(",")
        val dataUrl = "ws://$cleanHost:7000$WS_RAW_PATH?services=$servicesParam"
        Log.i(TAG, "🔌 连接数据 WebSocket: $dataUrl")
        dataWs = client.newWebSocket(Request.Builder().url(dataUrl).build(), DataWsListener())

        // 超时检查
        timeoutCheckJob?.cancel()
        timeoutCheckJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val timeout = (now - lastDataTimeMs) > DATA_TIMEOUT_MS && lastDataTimeMs > 0
                if (_isDataTimeout.value != timeout) {
                    _isDataTimeout.value = timeout
                }
            }
        }
    }

    fun connectCamera(host: String) {
        val cleanHost = host.trim().replace("http://", "").replace("https://", "").split("/").first()
        val camUrl = "ws://$cleanHost:7000$WS_CAMERA_PATH"
        Log.i(TAG, "📷 连接摄像头 WebSocket: $camUrl")
        cameraWs = client.newWebSocket(Request.Builder().url(camUrl).build(), CameraWsListener())
    }

    fun disconnect() {
        dataWs?.close(1000, "client closing")
        cameraWs?.close(1000, "client closing")
        reconnectJob?.cancel()
        timeoutCheckJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ===================== 数据监听器 =====================

    private inner class DataWsListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            _connectionState.value = ConnectionState.CONNECTED
            lastDataTimeMs = System.currentTimeMillis()
            Log.i(TAG, "✅ 数据 WebSocket 已连接")
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            lastDataTimeMs = System.currentTimeMillis()
            try {
                val frame = MultiplexFrame.decode(bytes.toByteArray())
                updateVehicleData(frame.service, frame.payload)
            } catch (e: Exception) {
                Log.w(TAG, "解析数据帧失败: ${e.message}")
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Hello JSON 和心跳，忽略
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, reason)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.i(TAG, "数据 WebSocket 已关闭: $reason")
            scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.w(TAG, "数据 WebSocket 失败: ${t.message}")
            scheduleReconnect()
        }
    }

    private inner class CameraWsListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "✅ 摄像头 WebSocket 已连接")
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            try {
                val frame = CameraWsFrame.decode(bytes.toByteArray())
                _cameraFrame.value = frame
                onCameraFrame?.invoke(frame)
            } catch (e: Exception) {
                Log.w(TAG, "解析摄像头帧失败: ${e.message}")
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Hello JSON
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "摄像头 WebSocket 已关闭: $reason")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "摄像头 WebSocket 失败: ${t.message}")
        }
    }

    // ===================== 重连逻辑 =====================

    private var reconnectAttempts = 0

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = min(
                RECONNECT_BASE_MS * (1 shl min(reconnectAttempts, 5)),
                RECONNECT_MAX_MS
            )
            reconnectAttempts++
            Log.i(TAG, "🔄 ${delay}ms 后重连 (第${reconnectAttempts}次)")
            delay(delay)
            // 重连由外部触发（需知道 host）
        }
    }

    fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    // ===================== 数据更新 =====================

    private fun updateVehicleData(service: String, payload: ByteArray) {
        val decoded = CerealDecoder.decode(service, payload) ?: return
        val current = _vehicleData.value ?: VehicleData()
        _vehicleData.value = when (service) {
            "carState" -> current.copy(
                carState = CarState(
                    vEgo = (decoded["vEgo"] as? Number)?.toFloat() ?: 0f,
                    steeringAngleDeg = (decoded["steeringAngleDeg"] as? Number)?.toFloat() ?: 0f,
                    leftBlinker = decoded["leftBlinker"] as? Boolean ?: false,
                    rightBlinker = decoded["rightBlinker"] as? Boolean ?: false,
                )
            )
            "modelV2" -> current.copy(
                modelV2 = ModelV2(
                    leadX = (decoded["leadX"] as? Number)?.toFloat() ?: 0f,
                    leadV = (decoded["leadV"] as? Number)?.toFloat() ?: 0f,
                    leadProb = (decoded["leadProb"] as? Number)?.toFloat() ?: 0f,
                )
            )
            "controlsState" -> current.copy(
                controlsState = ControlsState(
                    enabled = decoded["enabled"] as? Boolean ?: false,
                    active = decoded["active"] as? Boolean ?: false,
                    vCruise = (decoded["vCruise"] as? Number)?.toFloat() ?: 0f,
                )
            )
            "selfdriveState" -> current.copy(
                selfdriveState = SelfdriveState(
                    enabled = decoded["enabled"] as? Boolean ?: false,
                    active = decoded["active"] as? Boolean ?: false,
                )
            )
            "deviceState" -> {
                _deviceStatus.value = DeviceStatus(
                    cpuTemp = (decoded["cpuTemp"] as? Number)?.toFloat() ?: 0f,
                    cpuUsage = (decoded["cpuUsage"] as? Number)?.toFloat() ?: 0f,
                    memoryUsage = (decoded["memoryUsage"] as? Number)?.toFloat() ?: 0f,
                    batteryPercent = (decoded["batteryPercent"] as? Number)?.toInt() ?: 0,
                    gpsLatitude = (decoded["gpsLatitude"] as? Number)?.toDouble() ?: 0.0,
                    gpsLongitude = (decoded["gpsLongitude"] as? Number)?.toDouble() ?: 0.0,
                )
                current
            }
            else -> current
        }
    }
}

// ===================== 数据模型 =====================

data class VehicleData(
    val carState: CarState? = null,
    val modelV2: ModelV2? = null,
    val controlsState: ControlsState? = null,
    val selfdriveState: SelfdriveState? = null,
    val carrotMan: Map<String, Any?>? = null,
    val gpsLocation: GpsLocation? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class CarState(
    val vEgo: Float = 0f,           // m/s
    val steeringAngleDeg: Float = 0f,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
)

data class ModelV2(
    val leadX: Float = 0f,          // 前车距离 m
    val leadV: Float = 0f,          // 前车速度 m/s
    val leadProb: Float = 0f,       // 前车置信度
    val laneLineProbs: List<Float> = emptyList(),
    val leftDist: Float = 0f,       // 到左路边距离
    val rightDist: Float = 0f,      // 到右路边距离
)

data class ControlsState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val vCruise: Float = 0f,        // 巡航设定 m/s
    val angleSteers: Float = 0f,    // 目标方向盘角
)

data class SelfdriveState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val engageAllowed: Boolean = false,
)

data class GpsLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
)

data class DeviceStatus(
    val cpuTemp: Float = 0f,
    val cpuUsage: Float = 0f,
    val memoryUsage: Float = 0f,
    val batteryPercent: Int = 0,
    val networkType: String = "",
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
)

data class CameraFrame(
    val camera: String,
    val codec: String,
    val frameId: Int,
    val width: Int,
    val height: Int,
    val keyFrame: Boolean,
    val payload: ByteArray,
    val timestamp: Long
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

// ===================== 二进制协议解析 =====================

/**
 * 多路复用帧解析
 * 格式: [1字节服务名长度][服务名字节][capnp payload]
 */
object MultiplexFrame {
    fun decode(data: ByteArray): Frame {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val nameLen = dis.readUnsignedByte()
        val nameBytes = ByteArray(nameLen)
        dis.readFully(nameBytes)
        val service = String(nameBytes, Charsets.UTF_8)
        val payload = ByteArray(data.size - 1 - nameLen)
        dis.readFully(payload)
        return Frame(service, payload)
    }

    data class Frame(val service: String, val payload: ByteArray)
}

/**
 * 摄像头 WebSocket 帧解析
 * 格式: [4字节JSON元数据长度][JSON元数据][H264 header][H264 data]
 */
object CameraWsFrame {
    fun decode(data: ByteArray): CameraFrame {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val metaLen = dis.readInt()
        val metaBytes = ByteArray(metaLen)
        dis.readFully(metaBytes)
        val metaStr = String(metaBytes, Charsets.UTF_8)

        // 解析 JSON 元数据
        val json = org.json.JSONObject(metaStr)
        val camera = json.optString("camera", "road")
        val codec = json.optString("codec", "avc1.640028")
        val frameId = json.optInt("frameId", 0)
        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        val keyFrame = json.optBoolean("keyFrame", false)

        // 剩余 payload
        val payload = ByteArray(data.size - 4 - metaLen)
        dis.readFully(payload)

        return CameraFrame(
            camera = camera,
            codec = codec,
            frameId = frameId,
            width = width,
            height = height,
            keyFrame = keyFrame,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )
    }
}

// ===================== Cereal 解码器 =====================

/**
 * Cap'n Proto cereal 消息轻量解码器。
 *
 * 解析 capnp segment 结构，提取已知 cereal 消息的字段。
 * capnp 二进制格式：
 *   [4字节 segment数(通常=1)][4字节 segment大小(字=8字节)] [struct数据...]
 */
object CerealDecoder {

    fun decode(service: String, payload: ByteArray): Map<String, Any?>? {
        return try {
            val reader = CapnpReader(payload)
            when (service) {
                "carState" -> decodeCarState(reader)
                "modelV2" -> decodeModelV2(reader)
                "controlsState" -> decodeControlsState(reader)
                "selfdriveState" -> decodeSelfdriveState(reader)
                "deviceState" -> decodeDeviceState(reader)
                "carrotMan" -> decodeCarrotMan(reader)
                else -> null
            }
        } catch (e: Exception) {
            Log.w("CerealDecoder", "解码 $service 失败: ${e.message}")
            null
        }
    }

    private fun decodeCarState(r: CapnpReader): Map<String, Any?> {
        r.segment() // 跳到第0段
        // carState struct:
        //   vEgo             (Float64) @10
        //   steeringAngleDeg (Float64) @14
        //   leftBlinker      (Bool)    @40
        //   rightBlinker     (Bool)    @41
        return mapOf(
            "vEgo" to r.f64(10),
            "steeringAngleDeg" to r.f64(14),
            "leftBlinker" to r.bool(40),
            "rightBlinker" to r.bool(41),
        )
    }

    private fun decodeModelV2(r: CapnpReader): Map<String, Any?> {
        r.segment()
        // modelV2:
        //   lead (struct pointer @0)
        //   laneLineProbs (list @3)  
        //   meta (struct @5)
        //   orientationRate (Float32 @6)
        val leadPtr = r.ptr(0)
        val leadX = if (leadPtr > 0) {
            r.savePos()
            r.seek(leadPtr)
            val x = r.f64(0)  // LeadData.x (Float64 @0)
            r.restorePos()
            x
        } else 0.0

        val leadV = if (leadPtr > 0) {
            r.savePos()
            r.seek(leadPtr)
            val v = r.f64(4)  // LeadData.v (Float64 @4) - rough
            r.restorePos()
            v
        } else 0.0

        val leadProb = if (leadPtr > 0) {
            r.savePos()
            r.seek(leadPtr)
            val prob = r.f64(6)  // LeadData.prob (Float64 @6) - rough
            r.restorePos()
            prob
        } else 0.0

        return mapOf(
            "leadX" to leadX.toFloat(),
            "leadV" to leadV.toFloat(),
            "leadProb" to leadProb.toFloat(),
        )
    }

    private fun decodeControlsState(r: CapnpReader): Map<String, Any?> {
        r.segment()
        // enabled  (Bool @0)
        // active   (Bool @1)
        // vCruise  (Float64 @8)
        // angleSteers (Float32 @16)
        return mapOf(
            "enabled" to r.bool(0),
            "active" to r.bool(1),
            "vCruise" to r.f64(8),
            "angleSteers" to r.f32(16),
        )
    }

    private fun decodeSelfdriveState(r: CapnpReader): Map<String, Any?> {
        r.segment()
        // enabled       (Bool @0)
        // active        (Bool @1)
        // engageAllowed (Bool @11)
        return mapOf(
            "enabled" to r.bool(0),
            "active" to r.bool(1),
            "engageAllowed" to r.bool(11),
        )
    }

    private fun decodeDeviceState(r: CapnpReader): Map<String, Any?> {
        r.segment()
        return mapOf(
            "cpuTemp" to r.f32(8),
            "cpuUsage" to r.f32(10),
            "memoryUsage" to r.f32(11),
            "batteryPercent" to r.i32(12),
            "networkType" to "",
            "gpsLatitude" to r.f64(14),
            "gpsLongitude" to r.f64(15),
        )
    }

    private fun decodeCarrotMan(r: CapnpReader): Map<String, Any?>? {
        // carrotMan 是自定义数据，格式未知
        return null
    }
}

/**
 * 简化的 Cap'n Proto 二进制读取器
 *
 * 只支持标准 capnp 格式（非 packed）。
 * 消息格式: [4B segment数][4B segment大小(字)] [segment0数据...]
 * struct 数据: [data section][pointer section]
 * data section 按 8 字节对齐
 */
private class CapnpReader(private val data: ByteArray) {
    private var pos = 0
    private var structDataOffset = 0
    private val savedPositions = mutableListOf<Int>()

    /** 跳到第一个 segment 的 struct 数据起始位置 */
    fun segment() {
        val reader = java.io.DataInputStream(java.io.ByteArrayInputStream(data))
        val segCount = reader.readInt() // segment 数量
        val segSize = reader.readInt() // segment 大小（字 = 8 字节）
        // 跳过 segment table（如果有多个 segment）
        pos = 8
        structDataOffset = pos
    }

    /** 读取 Float64 字段 */
    fun f64(fieldIndex: Int): Double {
        val offset = structDataOffset + (fieldIndex * 8)
        if (offset + 8 > data.size) return 0.0
        return java.nio.ByteBuffer.wrap(data, offset, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble()
    }

    /** 读取 Float32 字段 */
    fun f32(fieldIndex: Int): Float {
        val offset = structDataOffset + (fieldIndex * 4)
        if (offset + 4 > data.size) return 0f
        return java.nio.ByteBuffer.wrap(data, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat()
    }

    /** 读取 Int32 字段 */
    fun i32(fieldIndex: Int): Int {
        val offset = structDataOffset + (fieldIndex * 4)
        if (offset + 4 > data.size) return 0
        return java.nio.ByteBuffer.wrap(data, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
    }

    /** 读取 Bool 字段（位域，每 8 个 bool 共享一个字节） */
    fun bool(fieldIndex: Int): Boolean {
        val byteOffset = structDataOffset + (fieldIndex / 8)
        if (byteOffset >= data.size) return false
        return (data[byteOffset].toInt() and (1 shl (fieldIndex % 8))) != 0
    }

    /** 读取指针（指向另一个 struct/list 的偏移量，以字为单位） */
    fun ptr(fieldIndex: Int): Int {
        // 指针位于 data section 之后
        // 先确定 data section 大小（基于 data section word count）
        // 简单实现：指针从 structDataOffset + firstWordOfPointers 开始
        val ptrOffset = structDataOffset + (fieldIndex * 8) // pointer section also 8-byte aligned
        if (ptrOffset + 4 > data.size) return 0
        return java.nio.ByteBuffer.wrap(data, ptrOffset, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
    }

    fun savePos() { savedPositions.add(pos) }
    fun restorePos() { if (savedPositions.isNotEmpty()) pos = savedPositions.removeAt(savedPositions.size - 1) }
    fun seek(newPos: Int) { pos = newPos }
}
