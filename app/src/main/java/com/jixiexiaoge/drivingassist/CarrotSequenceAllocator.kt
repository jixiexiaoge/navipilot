package com.jixiexiaoge.drivingassist

/**
 * 车端协议 `carrotIndex` 的序号分配器（纯逻辑，不依赖 Android，便于单测）。
 *
 * ## 为什么需要它
 * 车端 `carrot_serv.py` 收到带 `carrotCmd` 的包时，会把该包的 `carrotIndex` 记成
 * `carrotCmdIndex`，消费者靠它判断「这条命令是不是新的」。由此产生两条硬要求：
 *
 * 1. **同一次发送的多份副本必须复用同一个序号**：每份副本各自递增序号，车端会当成多次新请求
 *    （原实现一次点击变道发 3 个序号 → 变道 3 次）。
 * 2. **序号不能重复使用**：进程重启后从 0 重来，会让车端把新包当成旧包（迟到重放 / 被去重丢弃）。
 *
 * ## 做法
 * 每次只把「已经预留到哪」写盘，一次预留 [blockSize] 个号：
 * - 正常运行期间只有内存自增，几乎不产生磁盘写；
 * - 进程异常退出最多浪费本次预留的剩余几十个号，**绝不会重复使用已发出的号**；
 * - 冷启动从上次预留值继续（跳到 [lastAllocated] = 预留值），保证单调递增。
 *
 * 序号范围严格是 1..[maxIndex]（协议要求 1..2147483647），不做自动环绕；
 * 只有真的跑到上限才回落到 1（按每秒 10 包约 6.8 年才会发生）。
 *
 * @param loadReserved 读取上次预留到的序号（无记录返回 0）
 * @param saveReserved 持久化新的预留上界
 * @param blockSize 每次预留的号段大小
 * @param maxIndex 允许的最大序号
 */
internal class CarrotSequenceAllocator(
    private val loadReserved: () -> Long,
    private val saveReserved: (Long) -> Unit,
    private val blockSize: Long = DEFAULT_BLOCK,
    private val maxIndex: Long = Int.MAX_VALUE.toLong(),
) {
    /** 当前已分配到的序号；-1 表示还没初始化（尚未读过持久化记录） */
    private var current: Long = -1L

    /** 已预留到的序号上界 */
    private var reservedThrough: Long = 0L

    /** 最近一次分配出去的序号（未分配过返回 0，供调试面板显示） */
    val lastAllocated: Long
        get() = if (current < 0L) 0L else current

    /** 下一个将要分配出去的序号（不消耗序号，仅供预览显示） */
    val peekNext: Long
        get() = if (current < 0L) 1L else if (current >= maxIndex) 1L else current + 1L

    @Synchronized
    fun next(): Long {
        if (current < 0L) {
            // 冷启动：跳过上次预留但没用完的号段
            reservedThrough = loadReserved().coerceIn(0L, maxIndex)
            current = reservedThrough
        }
        var allocated = current + 1L
        if (allocated > maxIndex) {
            allocated = 1L
        }
        current = allocated
        if (allocated > reservedThrough) {
            val end = minOf(allocated + blockSize - 1L, maxIndex)
            reservedThrough = end
            saveReserved(end)
        }
        return allocated
    }

    companion object {
        /** 一次预留 256 个号：按每秒 10 包算，约 25 秒才写一次盘 */
        const val DEFAULT_BLOCK = 256L
    }
}
