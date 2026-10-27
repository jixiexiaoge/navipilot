package com.jixiexiaoge.drivingassist.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

/**
 * 车端 `xiaoge_data.py` 的 7711 广播客户端（Tesla 红绿灯 / 停止线）。
 *
 * ## 协议（车端实现见 `selfdrive/carrot/xiaoge_data.py`）
 * - TCP，车端 20 Hz 主动推送，**不需要客户端请求**
 * - 每帧 = 4 字节大端长度 + 该长度的 UTF-8 JSON
 * - JSON：`{version, sequence, timestamp, ip, data:{carState:{stopLineDist, trafficLightColor}}}`
 * - `trafficLightColor`：1 = 红、2 = 绿、3 = 黄（与 dashpilot `TL_COLOR_MAP` 一致）；
 *   `stopLineDist`：米，车端只在 Tesla `DAS_road` 新鲜（1 s）时给值
 *
 * ## 为什么单独一条连接
 * 这条路是车端 CAN 直读，语义跟高德投屏广播（`TrafficHint`）不同：高德给的是导航灯组
 * + 倒计时，Tesla 给的是路口实际灯色 + 停止线距离。两者不合并成同一份状态，
 * 卡片分别显示；7714 按 CAN 相位优先、高德独立回退，不拼接倒计时/方向/距离。
 *
 * 连接断开或超过 [DATA_TIMEOUT_MS] 没有新帧时，对外状态回落成 `null`，
 * 卡片因此不会再显示上一帧的灯色（不沿用过期值）。
 */
class XiaogeBridgeClient(private val scope: CoroutineScope? = null) {
    companion object {
        private const val TAG = "XiaogeBridgeClient"
        const val PORT = 7711
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L
        const val DATA_TIMEOUT_MS = 1500L
        private const val SOCKET_TIMEOUT_MS = 3000
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * 停止线有效上限（米）。**全项目唯一权威值**：
         * 卡片可见性、7714 `traffic_signal` 合成、`/dashboard` 快照都必须引用它，
         * 否则会出现「卡片说可见、合成判定超距」的自相矛盾（历史上 125 / 126.99 / 127 三处不一致）。
         */
        const val MAX_VALID_DISTANCE_M = 125f

        /** 车端超范围哨兵：`stopLineDist == 127` 表示「超出量程」，不是 127 米 */
        const val OUT_OF_RANGE_SENTINEL_M = 127f
    }

    /** 一帧世界量；[stopLineDistM] 为 null 表示车端没给（不在路口 / CAN 过期） */
    data class World(
        val stopLineDistM: Float? = null,
        val trafficLightColor: Int = 0,
        val receivedAtMs: Long = 0,
        val receivedWallMs: Long = 0,
    ) {
        /** 1 红 / 2 绿 / 3 黄；0 或其他值 = 无灯 */
        val hasLamp: Boolean get() = trafficLightColor in 1..3
        /** dashpilot `buildTrafficLightState`：距离有效才生成灯对象 */
        val lampVisible: Boolean get() = hasLamp && (stopLineDistM ?: 0f) > 0f
        /** dashpilot `stop_line_dist`：额外要求落在 (0, [MAX_VALID_DISTANCE_M]]（127 哨兵自然落在区间外） */
        val stopLineVisible: Boolean get() = (stopLineDistM ?: 0f) in 0.01f..MAX_VALID_DISTANCE_M
    }

    private val ownerJob = SupervisorJob(scope?.coroutineContext?.get(Job))
    private val workScope = CoroutineScope((scope?.coroutineContext ?: Dispatchers.IO) + ownerJob)
    private val lock = Any()
    private var socket: Socket? = null
    private var loopJob: Job? = null
    private var host: String? = null
    private var disposed = false
    private var attempts = 0

    /**
     * 连接代号。每次 [restart] 自增；旧 loop 醒来后据此判断自己是否已被淘汰，
     * 从而**不会**去关闭 / 清空新一代连接的 socket 与状态（旧实现就是在这里互相踩踏的）。
     */
    private var generation = 0

    /** 最近一帧的 `sequence`，用于丢弃回退 / 重复帧；新一轮连接会重置 */
    private var lastSequence = Long.MIN_VALUE
    private var lastSourceTimestamp = Double.NEGATIVE_INFINITY
    private var duplicateLogged = false

    private val _world = MutableStateFlow<World?>(null)
    val world = _world.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    /**
     * 调试面板用快照：连接态 + 主机 + 原始世界量 + 派生可见性。
     * 只读，不加锁阻塞读帧线程（`_world` / `_connected` 都是 StateFlow，读取本身安全）。
     */
    fun debugSnapshot(): JSONObject = JSONObject().apply {
        put("connected", _connected.value)
        put("host", synchronized(lock) { host } ?: "")
        put("port", PORT)
        val world = _world.value
        if (world == null) {
            put("world", JSONObject.NULL)
        } else {
            put("world", JSONObject().apply {
                put("stopLineDist", world.stopLineDistM?.toDouble() ?: JSONObject.NULL)
                put("trafficLightColor", world.trafficLightColor)
                put("receivedAtMs", world.receivedAtMs)
                put("hasLamp", world.hasLamp)
                put("lampVisible", world.lampVisible)
                put("stopLineVisible", world.stopLineVisible)
            })
        }
    }

    /** 切换车端 IP（空串/null 表示断开）。重复相同 host 不重连。 */
    fun connect(deviceHost: String?) {
        val target = deviceHost?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (disposed || target == host) return
            host = target
            attempts = 0
        }
        restart()
    }

    fun close() {
        synchronized(lock) {
            disposed = true
            host = null
            generation++
        }
        restart()
        ownerJob.cancel()
        workScope.cancel()
        _world.value = null
        _connected.value = false
    }

    private fun restart() {
        val previousLoop: Job?
        val previousSocket: Socket?
        val myGeneration: Int
        synchronized(lock) {
            generation++
            myGeneration = generation
            previousLoop = loopJob
            previousSocket = socket
            socket = null
            loopJob = null
        }
        // 先让旧 loop 与被替换的连接彻底作废，再起新 loop。
        previousLoop?.cancel()
        previousSocket?.let { runCatching { it.close() } }
        _connected.value = false
        _world.value = null
        val target = synchronized(lock) { host }
        if (disposed || target == null) return
        val job = workScope.launch { runLoop(target, myGeneration) }
        synchronized(lock) { loopJob = job }
    }

    private fun isCurrent(myGeneration: Int): Boolean =
        synchronized(lock) { !disposed && generation == myGeneration }

    private suspend fun runLoop(target: String, myGeneration: Int) {
        while (workScope.isActive && isCurrent(myGeneration)) {
            val connection = withContext(Dispatchers.IO) { open(target, myGeneration) }
            if (connection == null) {
                backoff()
                continue
            }
            attempts = 0
            try {
                withContext(Dispatchers.IO) { readFrames(connection, myGeneration) }
            } catch (error: Exception) {
                if (error !is EOFException) Log.i(TAG, "7711 读取结束: ${error.message}")
            } finally {
                // 只清理自己这一代的状态：旧 loop 超时醒来时不能动新一代的 socket / 世界量
                if (isCurrent(myGeneration)) {
                    _connected.value = false
                    _world.value = null
                    synchronized(lock) { if (socket === connection) socket = null }
                }
                runCatching { connection.close() }
            }
            if (workScope.isActive && isCurrent(myGeneration)) backoff()
        }
    }

    private fun open(target: String, myGeneration: Int): Socket? = try {
        val connection = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(target, PORT), SOCKET_TIMEOUT_MS)
            soTimeout = DATA_TIMEOUT_MS.toInt()
        }
        val accepted = synchronized(lock) {
            if (disposed || generation != myGeneration) {
                false
            } else {
                socket = connection
                // 车端可能已重启，sequence 从头开始：新一轮连接必须重置判重基准
                lastSequence = Long.MIN_VALUE
                lastSourceTimestamp = Double.NEGATIVE_INFINITY
                duplicateLogged = false
                true
            }
        }
        if (!accepted) {
            runCatching { connection.close() }
            null
        } else {
            _connected.value = true
            Log.i(TAG, "已连接 $target:$PORT")
            connection
        }
    } catch (error: Exception) {
        Log.i(TAG, "连接 $target:$PORT 失败: ${error.message}")
        null
    }

    private fun readFrames(connection: Socket, myGeneration: Int) {
        val input = connection.getInputStream()
        while (isCurrent(myGeneration)) {
            val length = readU32(input) ?: return
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                Log.i(TAG, "帧长度异常 $length，断开重连")
                return
            }
            val payload = readFully(input, length) ?: return
            applyFrame(String(payload, Charsets.UTF_8), myGeneration)
        }
    }

    private fun applyFrame(text: String, myGeneration: Int) {
        val json = try {
            JSONObject(text)
        } catch (error: org.json.JSONException) {
            throw IOException("Invalid 7711 JSON", error)
        }
        val carState = json.optJSONObject("data")?.optJSONObject("carState")
            ?: throw IOException("7711 carState missing")
        val rawSequence = json.opt("sequence") as? Number
        val sequence = rawSequence?.toLong() ?: -1L
        val timestamp = (json.opt("timestamp") as? Number)?.toDouble() ?: Double.NaN
        if (sequence < 0 || rawSequence?.toDouble() != sequence.toDouble()) {
            throw IOException("Invalid 7711 sequence")
        }
        if (!timestamp.isFinite() || timestamp < 0) throw IOException("Invalid 7711 timestamp")
        val distance = carState.optDouble("stopLineDist", Double.NaN)
            .takeIf { it.isFinite() }?.toFloat()
        val color = carState.optInt("trafficLightColor", 0)
        synchronized(lock) {
            if (disposed || generation != myGeneration) return
            val now = System.nanoTime() / NANOS_PER_MILLISECOND
            if (_world.value?.let { now - it.receivedAtMs !in 0..DATA_TIMEOUT_MS } == true) {
                _world.value = null
            }
            if (sequence < lastSequence || timestamp < lastSourceTimestamp) {
                throw IOException("7711 source ordering rolled back")
            }
            if (sequence == lastSequence || timestamp == lastSourceTimestamp) {
                if (!duplicateLogged) {
                    Log.w(TAG, "7711 duplicate sequence/source timestamp rejected")
                    duplicateLogged = true
                }
                return
            }
            lastSequence = sequence
            lastSourceTimestamp = timestamp
            _world.value = World(
                stopLineDistM = distance,
                trafficLightColor = color,
                receivedAtMs = now,
                receivedWallMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun backoff() {
        attempts = (attempts + 1).coerceAtMost(16)
        val wait = min(RECONNECT_MAX_MS, RECONNECT_BASE_MS shl (attempts - 1).coerceIn(0, 4))
        delay(wait)
    }

    private fun readU32(input: InputStream): Int? {
        val head = readFully(input, 4) ?: return null
        return ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }
}
