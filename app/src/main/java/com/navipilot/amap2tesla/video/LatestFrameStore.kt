package com.navipilot.amap2tesla.video

class LatestFrameStore {
    private var frame: ByteArray? = null
    private var sequence = 0L
    private var latestFrameAt = 0L
    private var fpsWindowStartedAt = android.os.SystemClock.elapsedRealtime()
    private var fpsWindowFrames = 0
    private var encodedFps = 0f

    @Synchronized
    fun publish(bytes: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()
        frame = bytes
        sequence++
        latestFrameAt = now
        fpsWindowFrames++
        val windowMillis = now - fpsWindowStartedAt
        if (windowMillis >= 1_000L) {
            encodedFps = fpsWindowFrames * 1_000f / windowMillis
            fpsWindowStartedAt = now
            fpsWindowFrames = 0
        }
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    fun clear() {
        frame = null
        latestFrameAt = 0L
        fpsWindowStartedAt = android.os.SystemClock.elapsedRealtime()
        fpsWindowFrames = 0
        encodedFps = 0f
    }

    @Synchronized
    fun snapshot(): Snapshot? = frame?.let { Snapshot(sequence, it) }

    /**
     * 等待「比 previousSequence 更新」的一帧。
     *
     * 注意：这里必须用循环 + 实际等待时长累计，不能只 wait 一次：
     *  - `frame == null`（服务刚起 / installDisplay 里 clear 之后）时必须也进入等待，
     *    否则调用方 `?: continue` 会变成无退避的紧循环，打满一个 CPU 核；
     *  - `wait` 会被 publish 的 notifyAll 提前唤醒（虚假唤醒），需要重新判条件。
     *
     * `clear()` 刻意**不**重置 sequence：一旦重置，已连接的 MJPEG 客户端持有的
     * previousSequence 会永远大于新 sequence，从此再也取不到新帧。
     */
    @Synchronized
    fun awaitAfter(previousSequence: Long, timeoutMillis: Long): Snapshot? {
        var waited = 0L
        while (frame == null || sequence <= previousSequence) {
            val slice = timeoutMillis - waited
            if (slice <= 0L) break
            val startedAt = android.os.SystemClock.elapsedRealtime()
            (this as java.lang.Object).wait(slice)
            waited += android.os.SystemClock.elapsedRealtime() - startedAt
        }
        val current = frame ?: return null
        return Snapshot(sequence, current)
    }

    @Synchronized
    fun metrics(): Metrics {
        val now = android.os.SystemClock.elapsedRealtime()
        val age = if (frame == null) -1L else now - latestFrameAt
        return Metrics(
            sequence = sequence,
            encodedFps = if (age in 0L..1_000L) encodedFps else 0f,
            frameAgeMillis = age
        )
    }

    data class Snapshot(val sequence: Long, val bytes: ByteArray)

    data class Metrics(
        val sequence: Long,
        val encodedFps: Float,
        val frameAgeMillis: Long
    )
}
