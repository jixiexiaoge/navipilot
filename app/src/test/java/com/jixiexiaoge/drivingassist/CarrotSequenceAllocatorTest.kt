package com.jixiexiaoge.drivingassist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarrotSequenceAllocatorTest {
    @Test
    fun `first allocation starts at one and stays monotonic`() {
        val allocator = CarrotSequenceAllocator(loadReserved = { 0L }, saveReserved = {})
        assertThat((1..4).map { allocator.next() }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `reservation is persisted once per block instead of on every packet`() {
        val saved = mutableListOf<Long>()
        val allocator = CarrotSequenceAllocator(
            loadReserved = { 0L },
            saveReserved = { saved += it },
            blockSize = 4L,
        )
        repeat(9) { allocator.next() }
        // 1..4 落在第一块、5..8 落在第二块、9 触发第三块（9..12）
        assertThat(saved).containsExactly(4L, 8L, 12L).inOrder()
    }

    @Test
    fun `restart skips the reserved block so numbers are never reused`() {
        var persisted = 0L
        val first = CarrotSequenceAllocator(
            loadReserved = { persisted },
            saveReserved = { persisted = it },
            blockSize = 8L,
        )
        first.next()
        first.next()
        // 进程重启：本次只发了 1、2，但 1..8 都已预留，必须从 9 继续
        val second = CarrotSequenceAllocator(
            loadReserved = { persisted },
            saveReserved = { persisted = it },
            blockSize = 8L,
        )
        assertThat(second.next()).isEqualTo(9L)
        assertThat(persisted).isEqualTo(16L)
    }

    @Test
    fun `overflow restarts at one and never returns zero`() {
        val allocator = CarrotSequenceAllocator(
            loadReserved = { 3L },
            saveReserved = {},
            blockSize = 8L,
            maxIndex = 3L,
        )
        // 预留值 3 已在上限，下一个只能回到 1；协议要求正整数，绝不能出现 0
        assertThat(listOf(allocator.next(), allocator.next(), allocator.next()))
            .containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `peek does not consume a number`() {
        val allocator = CarrotSequenceAllocator(loadReserved = { 0L }, saveReserved = {})
        assertThat(allocator.peekNext).isEqualTo(1L)
        assertThat(allocator.peekNext).isEqualTo(1L)
        assertThat(allocator.next()).isEqualTo(1L)
        assertThat(allocator.peekNext).isEqualTo(2L)
        assertThat(allocator.lastAllocated).isEqualTo(1L)
    }
}
