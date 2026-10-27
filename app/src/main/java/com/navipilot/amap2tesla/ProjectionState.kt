package com.navipilot.amap2tesla

import com.navipilot.amap2tesla.video.LatestFrameStore

object ProjectionState {
    @Volatile
    var running = false

    @Volatile
    var status = "未启动"

    @Volatile
    var accessUrl: String? = null

    @Volatile
    var serverPort: Int? = null

    @Volatile
    internal var previewFrameStore: LatestFrameStore? = null

    internal val activePreviewFrameStore: LatestFrameStore?
        get() = if (running) previewFrameStore else null

    val localPreviewUrl: String?
        get() = if (running) serverPort?.let { "http://127.0.0.1:$it/?embedded=1&transport=mjpeg" } else null

    @Volatile
    var displayId: Int? = null

    @Volatile
    var width = 0

    @Volatile
    var height = 0

    @Volatile
    var densityDpi = 0

    @Volatile
    var targetFps = 0

    @Volatile
    var trialMode = true

    fun reset(statusText: String = "未启动") {
        running = false
        status = statusText
        accessUrl = null
        serverPort = null
        previewFrameStore = null
        displayId = null
        width = 0
        height = 0
        densityDpi = 0
        targetFps = 0
        trialMode = true
    }
}
