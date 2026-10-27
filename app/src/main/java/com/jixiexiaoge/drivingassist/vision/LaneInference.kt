@file:Suppress("MagicNumber", "TooManyFunctions", "LongMethod")

package com.jixiexiaoge.drivingassist.vision

import android.content.Context
import android.util.Log
import com.jixiexiaoge.drivingassist.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.VideoFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 车道数推理调度。
 *
 * 数据通路：
 * ```
 * WebRTC 解码线程          →  onFrame 旁路：只拷 Y 平面（灰度），队列容量 1，忙则丢帧
 * 推理线程（单线程）        →  缩放 480×288 + /255 + runSession(vlaneDay)
 *                          →  解析 chedaoshuLeft/Right → LaneResult
 * 顶部标题栏（主线程）      →  读 StateFlow 显示「车道 N · 当前第 M 条」
 * ```
 *
 * 设计要点：
 * - **挂上就工作**：只要 AR 页面拿到帧流就自动推理，没有开关
 * - **丢帧不排队**：车道数是慢变量，宁可少一帧也不要延迟累积
 * - **零大数组分配**：roadOut 的 float[] 全部复用，避免每帧 GC
 * - **纯附加**：调用方不挂 tap 就完全回到改造前的行为
 */
internal class LaneInference(context: Context) {

    private data class Session(val id: Long, val host: String, val route: Long)
    private class Frame(
        val gray: ByteArray, val width: Int, val height: Int, val stride: Int,
        val session: Session?,
        val atMs: Long = System.currentTimeMillis(),
        val monoMs: Long = System.nanoTime() / 1_000_000,
    )
    private val sessionLock = Any()
    @Volatile private var session: Session? = null

    private val appContext = context.applicationContext

    /** 与页面共用同一份最新结果；顶部标题栏通过 [live] 读它 */
    val result: StateFlow<LaneResult?> = sharedResult.asStateFlow()
    val status: StateFlow<LaneStatus> = sharedStatus.asStateFlow()

    @Volatile private var running = false
    @Volatile private var mode = LaneModelMode.AUTO
    @Volatile private var currentModel = ""
    @Volatile private var handle = 0L

    /** 每 N 帧推理一次。2 ≈ 15fps 推理，对车道数足够，CPU 直接减半 */
    @Volatile var frameSkip = 2

    /**
     * 中心裁剪标定（视野域不匹配时用）：左右各裁掉的比例，0 = 整帧喂入。
     * 只在车道数系统性偏高时才需要动，默认不裁。
     *
     * ✔️ 摄像头已恢复为**只订阅长焦 road**（`WebRtcSignaling.CAMERA_CANDIDATES`），
     * 视野与旧版一致，因此默认保持 0 裁剪；只有换源或实车系统性偏高时才需要调这两个值。
     */
    @Volatile var roiLeft = 0f
    @Volatile var roiRight = 0f

    private var thread: Thread? = null
    private val tick = AtomicInteger()
    private val queue = ArrayBlockingQueue<Frame>(1)
    private val pool = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var poolSize = 0

    // 推理上下文（换模型时重建）
    private var outH = 0
    private var outW = 0
    private var outC = 1
    private val countBuf = FloatArray(8)

    // 车道数时序平滑

    private var frames = 0L
    private var sumMs = 0L
    private var lastSecond = 0L
    private var fpsBase = 0L
    private var fps = 0f
    private var rotationWarned = false
    private var selfTestPending = false

    // ---- 自动选型评分（只在 AUTO 模式生效）----
    /** 亮度投票：低于 NIGHT_LUMA 加分，高于 DAY_LUMA 减分，>= NIGHT_VOTES 认为进入夜间 */
    private var nightVotes = 0
    /** 耗时投票：近 20 帧中位数超预算加分，否则快速减分 */
    private var slowVotes = 0
    /** 降级到 multitask 后的观察冷却（帧），避免来回抖动 */
    private var slowHold = 0
    /** 换模型冷却，任何两次切换至少间隔 SWITCH_COOLDOWN 帧 */
    private var switchCooldown = 0
    private val recentMs = IntArray(20)
    private var recentIdx = 0
    private var recentFill = 0

    val model: String get() = currentModel

    fun start() {
        if (running) return
        if (!LaneNative.ready) {
            sharedStatus.value = LaneStatus(libReady = false, message = LaneNative.error)
            return
        }
        running = true
        thread = Thread({ loop() }, "lane-infer").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        synchronized(sessionLock) {
            running = false
            if (activeSession?.id == session?.id) activeSession = null
            session = null
            sharedResult.value = null
        }
        thread?.interrupt()
        thread = null
        // 页面销毁后标题栏不应再显示上一次的车道数
        sharedResult.value = null
        sharedStatus.value = LaneStatus()
    }

    fun beginSession(host: String, route: Long): Long = synchronized(sessionLock) {
        val next = Session(nextSession.incrementAndGet(), host, route)
        session = next
        activeSession = LaneObservationSession(next.id, host, route, System.nanoTime() / 1_000_000)
        sharedResult.value = null
        next.id
    }

    fun endSession(id: Long) = synchronized(sessionLock) {
        if (session?.id == id) {
            if (activeSession?.id == id) activeSession = null
            session = null
            sharedResult.value = null
        }
    }

    /**
     * 切换模型策略。AUTO 会按亮度与耗时自行在 vlaneDay / vlaneNight / multitask 之间切换；
     * 其余三档是手动锁定。工作线程在下一轮循环里按新策略重建会话。
     */
    fun setMode(next: LaneModelMode) {
        if (mode != next) {
            mode = next
            switchCooldown = 0
        }
    }

    fun mode(): LaneModelMode = mode

    /** 自动模式的目标模型；手动模式直接返回锁定的那一个 */
    private fun targetModel(): String = when (mode) {
        LaneModelMode.DAY -> LaneModels.DAY
        LaneModelMode.NIGHT -> LaneModels.NIGHT
        LaneModelMode.LITE -> LaneModels.LITE
        LaneModelMode.AUTO -> {
            val base = if (nightVotes >= NIGHT_VOTES) LaneModels.NIGHT else LaneModels.DAY
            if (slowVotes >= SLOW_VOTES) LaneModels.LITE else base
        }
    }

    // ------------------------------------------------------------------
    // 解码线程入口
    // ------------------------------------------------------------------

    /**
     * 只做一件事：把 I420 的 Y 平面拷进复用 buffer 丢给推理线程。
     * **绝不能在这里做重活**，否则会直接卡住 WebRTC 解码。
     */
    fun onRoadFrame(frame: VideoFrame, sessionId: Long) {
        if (!running || handle == 0L) return
        val activeSession = session?.takeIf { it.id == sessionId } ?: return
        val receivedAt = System.currentTimeMillis()
        val receivedMono = System.nanoTime() / 1_000_000
        if (frame.rotation != 0) {
            if (!rotationWarned) {
                rotationWarned = true
                Log.w(TAG, "frame rotation ${frame.rotation}° 暂不支持识别，跳过")
            }
            return
        }
        val skip = frameSkip
        if (skip > 1 && tick.incrementAndGet() % skip != 0) return

        val i420 = try {
            frame.buffer.toI420()
        } catch (throwable: Throwable) {
            Log.w(TAG, "toI420 failed", throwable)
            return
        } ?: return
        try {
            val width = i420.width
            val height = i420.height
            val stride = i420.strideY
            if (width <= 0 || height <= 0 || stride <= 0) return
            val need = stride * height
            val dst = acquire(need) ?: return
            val src = i420.dataY ?: return
            val view = src.duplicate()
            view.position(0)
            if (view.remaining() < need) {
                pool.offer(dst)
                return
            }
            view.get(dst, 0, need)
            offer(Frame(dst, width, height, stride, activeSession, receivedAt, receivedMono))
        } catch (throwable: Throwable) {
            Log.w(TAG, "frame tap failed", throwable)
        } finally {
            // JavaI420Buffer.toI420() 对自身返回 this，不能误 release
            if (i420 !== frame.buffer) i420.release()
        }
    }

    private fun acquire(need: Int): ByteArray? {
        if (need > poolSize) {
            pool.clear()
            poolSize = need
            repeat(3) { pool.offer(ByteArray(need)) }
        }
        return pool.poll()
    }

    private fun offer(frame: Frame) {
        // 队列容量 1：旧帧直接丢回池子，不排队
        while (true) {
            val stale = queue.poll() ?: break
            pool.offer(stale.gray)
        }
        if (!queue.offer(frame)) pool.offer(frame.gray)
    }

    // ------------------------------------------------------------------
    // 推理线程
    // ------------------------------------------------------------------

    private fun loop() {
        while (running) {
            try {
                syncModel()
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    infer(frame)
                } finally {
                    pool.offer(frame.gray)
                }
            } catch (interrupted: InterruptedException) {
                break
            } catch (throwable: Throwable) {
                Log.e(TAG, "infer loop error", throwable)
            }
        }
        releaseModel()
    }

    private fun syncModel() {
        if (switchCooldown > 0) {
            switchCooldown--
            if (handle != 0L) return
        }
        val want = targetModel()
        if (want == currentModel && handle != 0L) return
        if (handle != 0L) {
            // 自动切换时给新模型一段观察期，避免耗时抖动导致来回换
            switchCooldown = SWITCH_COOLDOWN
            if (want == LaneModels.LITE) slowHold = SLOW_HOLD
            Log.i(
                TAG,
                "model switch ${LaneModels.shortName(currentModel)} -> " +
                    "${LaneModels.shortName(want)} (mode=$mode)",
            )
        }
        releaseModel()
        val file = LaneModels.prepare(appContext, want) ?: run {
            sharedStatus.value = LaneStatus(false, want, "模型文件缺失：$want")
            return
        }
        val opened = LaneNative.open(file.absolutePath)
        if (opened == 0L) {
            sharedStatus.value = LaneStatus(false, want, "模型加载失败：${LaneModels.shortName(want)}")
            Log.e(TAG, "nativeInit failed for $want")
            return
        }
        handle = opened
        currentModel = want
        val road = LaneNative.roadShape(opened)
        outH = road[0]
        outW = road[1]
        outC = max(1, road[2])
        LaneNative.setRoi(roiLeft, roiRight)
        Log.i(
            TAG,
            "model ready ${LaneModels.shortName(want)} roadOut=${outH}x${outW}x$outC" +
                " outs=${LaneNative.outNames(opened).size}",
        )
        sharedStatus.value = LaneStatus(true, want, "", 0, 0f, 0f, 0)
        maybeSelfTest()
    }

    /** 调试自检：用测试图跑一帧，验证全链路（详见 LaneSelfTest） */
    private fun maybeSelfTest() {
        if (!BuildConfig.DEBUG) return
        if (!LaneSelfTest.requested(appContext)) return
        val frame = LaneSelfTest.load(appContext) ?: return
        selfTestPending = true
        offer(Frame(frame.bytes, frame.width, frame.height, frame.width, null))
    }

    private fun releaseModel() {
        if (handle != 0L) {
            LaneNative.close(handle)
            handle = 0L
        }
        currentModel = ""
    }

    private fun infer(frame: Frame) {
        if (frame.session != null && frame.session != session) return
        val frameSession = frame.session?.id ?: -1L
        val h = handle
        if (h == 0L) return
        val began = System.currentTimeMillis()
        // 只画顶部文字，不需要 roadOut 掩码数据；传空数组让 native 跳过这 553 KB 的回填
        val rc = LaneNative.infer(
            h, frame.gray, frame.width, frame.height, frame.stride, NO_FLOATS, countBuf,
        )
        if (rc != 0) {
            if (frames % 60L == 0L) Log.w(TAG, "infer rc=$rc")
            return
        }
        val cost = (System.currentTimeMillis() - began).toInt()
        frames++
        sumMs += cost
        val luma = meanLuma(frame)
        updateModelScores(cost, luma)

        val leftVote = decodeLaneClass(countBuf, 0)
        val rightVote = decodeLaneClass(countBuf, 4)
        val total = laneCountTotal(leftVote, rightVote)

        val result = LaneResult(
            model = currentModel,
            lanesLeft = leftVote,
            lanesRight = rightVote,
            lanesTotal = total,
            latencyMs = cost,
            avgLatencyMs = sumMs.toFloat() / frames,
            frames = frames,
            srcW = frame.width,
            srcH = frame.height,
            meanLuma = luma,
            atMs = frame.atMs,
            receivedMonoMs = frame.monoMs,
            targetHost = frame.session?.host.orEmpty(),
            routeGeneration = frame.session?.route ?: -1,
            sessionId = frameSession,
            leftScores = countBuf.take(4),
            rightScores = countBuf.drop(4),
        )
        synchronized(sessionLock) {
            if (running && frame.session != null && frame.session == session) sharedResult.value = result
        }

        if (selfTestPending) {
            selfTestPending = false
            Log.i(
                TAG,
                "SELFTEST ok model=$currentModel cost=${cost}ms" +
                    " lanes L=$leftVote R=$rightVote total=$total" +
                    " src=${frame.width}x${frame.height} roadOut=${outH}x${outW}x$outC",
            )
        }

        val now = System.currentTimeMillis()
        if (lastSecond == 0L) {
            lastSecond = now
            fpsBase = frames
        } else if (now - lastSecond >= 1000L) {
            fps = (frames - fpsBase) * 1000f / (now - lastSecond)
            lastSecond = now
            fpsBase = frames
        }
        if (frames == 1L || frames % 10L == 0L) {
            sharedStatus.value = LaneStatus(
                libReady = true,
                model = currentModel,
                message = "",
                latencyMs = cost,
                avgLatencyMs = sumMs.toFloat() / frames,
                fps = fps,
                frames = frames,
            )
        }
    }

    /**
     * 自动选型的投票更新。
     *
     * 亮度：以 Y 平面均值为准，低于 [NIGHT_LUMA] 加分、高于 [DAY_LUMA] 减分，
     * 两档之间保持不动（迟滞区间），避免树荫/隧道口来回切模型。
     *
     * 耗时：取最近 20 帧中位数，超过 [SLOW_MS] 加分。降级到 multitask 后进入
     * [SLOW_HOLD] 帧观察期，期间不再重新评估，避免升降级震荡。
     */
    private fun updateModelScores(cost: Int, luma: Int) {
        recentMs[recentIdx] = cost
        recentIdx = (recentIdx + 1) % recentMs.size
        if (recentFill < recentMs.size) recentFill++

        when {
            luma < NIGHT_LUMA -> nightVotes = min(nightVotes + 1, NIGHT_VOTES * 2)
            luma > DAY_LUMA -> nightVotes = max(nightVotes - 1, 0)
        }

        if (slowHold > 0) {
            slowHold--
            return
        }
        if (medianRecent() > SLOW_MS) {
            slowVotes = min(slowVotes + 1, SLOW_VOTES * 2)
        } else {
            slowVotes = max(slowVotes - 3, 0)
        }
    }

    private fun medianRecent(): Int {
        if (recentFill == 0) return 0
        val copy = IntArray(recentFill) { recentMs[it] }
        copy.sort()
        return copy[copy.size / 2]
    }

    private fun meanLuma(frame: Frame): Int {
        val step = 64
        var sum = 0L
        var n = 0
        var i = 0
        val limit = frame.stride * frame.height
        while (i < limit) {
            sum += frame.gray[i].toInt() and 0xFF
            n++
            i += step
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    companion object {
        private const val TAG = "LaneInference"
        private val nextSession = AtomicLong()
        @Volatile internal var activeSession: LaneObservationSession? = null
            private set

        // ---- 自动选型参数 ----
        /** Y 均值低于此值视为夜间（15fps 下约 1.3 秒确认） */
        private const val NIGHT_LUMA = 52
        /** Y 均值高于此值视为白天；52~78 是迟滞区间，不做切换 */
        private const val DAY_LUMA = 78
        /** 夜间投票达到该值即切 vlaneNight */
        private const val NIGHT_VOTES = 18
        /** 单帧耗时中位数超过该值（ms）才考虑降级 */
        private const val SLOW_MS = 28
        /** 降级投票达到该值即切 multitask（约 3 秒） */
        private const val SLOW_VOTES = 45
        /** 降级后观察多少帧再重新评估 */
        private const val SLOW_HOLD = 300
        /** 任意两次换模型之间的最小间隔（帧） */
        private const val SWITCH_COOLDOWN = 90

        /** 空数组 = 不需要 roadOut 回填（native 会跳过 SetFloatArrayRegion） */
        private val NO_FLOATS = FloatArray(0)

        // 结果与状态的唯一真源：页面与顶部标题栏读同一份
        private val sharedResult = MutableStateFlow<LaneResult?>(null)

        // 刻意不在这里读 LaneNative：标题栏一启动就会读 live，
        // 若在此触发 LaneNative 初始化，等于每次冷启动都白加载 19MB 运行时。
        private val sharedStatus = MutableStateFlow(LaneStatus())

        /** 供 AR 页面之外的界面（例如顶部标题栏的车道数）观察最新结果 */
        val live: StateFlow<LaneResult?> = sharedResult.asStateFlow()

        val liveStatus: StateFlow<LaneStatus> = sharedStatus.asStateFlow()
    }

}

internal data class LaneObservationSession(
    val id: Long,
    val host: String,
    val route: Long,
    val startedMonoMs: Long = Long.MIN_VALUE,
)
