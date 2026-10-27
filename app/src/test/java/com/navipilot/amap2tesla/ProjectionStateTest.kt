package com.navipilot.amap2tesla

import com.google.common.truth.Truth.assertThat
import com.navipilot.amap2tesla.video.LatestFrameStore
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test

class ProjectionStateTest {
    @Before
    fun setUp() = ProjectionState.reset()

    @After
    fun tearDown() = ProjectionState.reset()

    @Test
    fun `native preview cannot access a stopped projection frame store`() {
        ProjectionState.previewFrameStore = mockk<LatestFrameStore>()
        assertThat(ProjectionState.activePreviewFrameStore).isNull()
    }

    @Test
    fun `native preview shares the source store without requiring an HTTP port`() {
        val frames = mockk<LatestFrameStore>()
        ProjectionState.previewFrameStore = frames
        ProjectionState.running = true
        assertThat(ProjectionState.serverPort).isNull()
        assertThat(ProjectionState.activePreviewFrameStore).isSameInstanceAs(frames)
        ProjectionState.running = false
        assertThat(ProjectionState.activePreviewFrameStore).isNull()
    }

    @Test
    fun `reset detaches native frames and a restart uses the new store`() {
        val previous = mockk<LatestFrameStore>()
        val replacement = mockk<LatestFrameStore>()
        ProjectionState.running = true
        ProjectionState.previewFrameStore = previous
        ProjectionState.reset()
        assertThat(ProjectionState.previewFrameStore).isNull()
        assertThat(ProjectionState.activePreviewFrameStore).isNull()
        ProjectionState.running = true
        ProjectionState.previewFrameStore = replacement
        assertThat(ProjectionState.activePreviewFrameStore).isSameInstanceAs(replacement)
    }

    @Test
    fun `stopped projection cannot expose an old browser URL`() {
        ProjectionState.serverPort = 8080
        assertThat(ProjectionState.localPreviewUrl).isNull()
    }

    @Test
    fun `startup waits for the server to publish its listening port`() {
        ProjectionState.running = true
        assertThat(ProjectionState.localPreviewUrl).isNull()
    }

    @Test
    fun `local preview uses loopback instead of the Tesla address`() {
        ProjectionState.running = true
        ProjectionState.serverPort = 8080
        ProjectionState.accessUrl = "http://192.168.41.1:8080"
        assertThat(ProjectionState.localPreviewUrl).isEqualTo("http://127.0.0.1:8080/?embedded=1&transport=mjpeg")
    }

    @Test
    fun `local preview follows fallback port changes`() {
        ProjectionState.running = true
        ProjectionState.serverPort = 8082
        assertThat(ProjectionState.localPreviewUrl).isEqualTo("http://127.0.0.1:8082/?embedded=1&transport=mjpeg")
        ProjectionState.serverPort = 8099
        assertThat(ProjectionState.localPreviewUrl).isEqualTo("http://127.0.0.1:8099/?embedded=1&transport=mjpeg")
    }

    @Test
    fun `reset removes the browser endpoint and listening port`() {
        ProjectionState.running = true
        ProjectionState.serverPort = 8080
        ProjectionState.reset("已停止")
        assertThat(ProjectionState.serverPort).isNull()
        assertThat(ProjectionState.localPreviewUrl).isNull()
    }
}
