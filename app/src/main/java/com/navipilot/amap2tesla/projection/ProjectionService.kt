package com.navipilot.amap2tesla.projection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.privileged.IPrivilegedDisplayService
import com.navipilot.amap2tesla.privileged.PrivilegedDisplayService
import com.jixiexiaoge.drivingassist.BuildConfig
import com.jixiexiaoge.drivingassist.MainActivity
import com.navipilot.amap2tesla.server.ControlSink
import com.navipilot.amap2tesla.server.LocalProjectionServer
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.navipilot.amap2tesla.video.MjpegEncoder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ProjectionService : Service() {
    private val frameStore = LatestFrameStore()
    private val pipelineExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "Amap2Tesla-pipeline").apply { isDaemon = true }
        }
    private var encoder: MjpegEncoder? = null
    private var server: LocalProjectionServer? = null
    private var privileged: IPrivilegedDisplayService? = null
    @Volatile
    private var displayId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayWatchdog: ScheduledExecutorService? = null
    private var projectionTimeout: ScheduledExecutorService? = null
    private var userServiceBound = false
    @Volatile
    private var destroyed = false
    private var finalStatus = "已停止"
    private var connectionMode = ConnectionMode.LAN
    private var maxProjectionDurationMillis: Long? = DAILY_TRIAL_DURATION_MILLIS
    private var trialMode = true
    @Volatile
    private var requestedWidth = DEFAULT_DISPLAY_WIDTH
    @Volatile
    private var requestedHeight = DEFAULT_DISPLAY_HEIGHT

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, PrivilegedDisplayService::class.java.name)
        )
            .daemon(false)
            // The AIDL contract changed during an earlier projection experiment.
            // A versioned process prevents Shizuku from reusing an incompatible
            // UserService process that may still be alive from that release.
            .processNameSuffix("privileged-v2")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (destroyed) {
                Log.i(TAG, "Ignoring privileged service connection after projection service stopped")
                return
            }
            Log.i(TAG, "Privileged service connected: $name")
            userServiceBound = true
            privileged = IPrivilegedDisplayService.Stub.asInterface(binder)
            startProjectionPipeline()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (destroyed) {
                Log.i(TAG, "Ignoring privileged service disconnect after projection service stopped")
                return
            }
            Log.w(TAG, "Privileged service disconnected: $name")
            userServiceBound = false
            privileged = null
            fail("特权服务连接已断开")
        }
    }

    /**
     * 是否成功进入前台。
     *
     * Android 14+ 对带类型的 FGS 有额外前置权限要求，缺失时 `startForeground` 会抛
     * `SecurityException`，而异常发生在 `onCreate` 里会直接**杀掉进程**（表现为点「投屏」闪退）。
     * 这里兜底：进不了前台就记下失败原因并结束服务，让上层看到状态文本而不是崩溃。
     */
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        ProjectionState.previewFrameStore = frameStore
        createNotificationChannel()
        foregroundStarted = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("正在准备增强投屏"))
            true
        }.onFailure { Log.e(TAG, "startForeground rejected", it) }.getOrDefault(false)
        if (!foregroundStarted) {
            // 不 throw：调用方（Activity）会读到 ProjectionState.status 并提示用户
            finalStatus = "错误：系统不允许投屏后台服务，请检查前台服务权限"
            ProjectionState.running = false
            ProjectionState.status = finalStatus
            ProjectionState.previewFrameStore = null
            stopSelf()
            return
        }
        wakeLock = (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:projection")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            Log.w(TAG, "Ignoring start command, foreground notification unavailable")
            return START_NOT_STICKY
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            fail("高德投屏仅支持 Android 13 及更高版本")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                maxProjectionDurationMillis = intent?.getLongExtra(
                    EXTRA_MAX_DURATION_MILLIS,
                    DAILY_TRIAL_DURATION_MILLIS,
                )?.takeIf { it > 0L }
                trialMode = intent?.getBooleanExtra(EXTRA_TRIAL_MODE, maxProjectionDurationMillis != null)
                    ?: (maxProjectionDurationMillis != null)
                ProjectionState.trialMode = trialMode
                connectionMode = intent?.getStringExtra(EXTRA_CONNECTION_MODE)
                    ?.let { name -> ConnectionMode.entries.firstOrNull { it.name == name } }
                    ?: ConnectionMode.load(this)
                if (connectionMode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady()) {
                    fail("Tesla 固定访问地址尚未就绪，请重新授权 VPN")
                } else {
                    bindPrivilegedService()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        ProjectionState.running = false
        ProjectionState.previewFrameStore = null
        ProjectionState.status = "正在停止"
        displayWatchdog?.shutdownNow()
        displayWatchdog = null
        projectionTimeout?.shutdownNow()
        projectionTimeout = null
        displayId?.let { id -> runCatching { privileged?.releaseDisplay(id) } }
        displayId = null
        ProjectionState.displayId = null
        runCatching { server?.stop() }
        server = null
        runCatching { encoder?.close() }
        encoder = null
        pipelineExecutor.shutdownNow()
        if (userServiceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        }
        userServiceBound = false
        privileged = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ProjectionState.reset(finalStatus)
        if (connectionMode == ConnectionMode.TESLA) {
            TeslaVirtualIpService.stop(this)
        }
        if (trialMode) {
            DailyProjectionTrial.finish(this)
        }
        super.onDestroy()
    }

    private fun bindPrivilegedService() {
        Log.i(
            TAG,
            "Binding privileged service: ping=${Shizuku.pingBinder()}, " +
                "permission=${runCatching { Shizuku.checkSelfPermission() }.getOrNull()}, " +
                "version=${runCatching { Shizuku.getVersion() }.getOrNull()}, " +
                "uid=${runCatching { Shizuku.getUid() }.getOrNull()}"
        )
        if (!Shizuku.pingBinder()) {
            fail("Shizuku / Stellar 未运行")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            fail("尚未授权 Shizuku / Stellar")
            return
        }
        if (userServiceBound || privileged != null) return
        ProjectionState.running = true
        ProjectionState.status = "正在连接特权服务"
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (error: Throwable) {
            fail("特权服务启动失败：${error.rootMessage()}")
        }
    }

    private fun startProjectionPipeline() {
        if (destroyed || encoder != null || pipelineExecutor.isShutdown) return
        val remote = privileged ?: return
        try {
            pipelineExecutor.execute {
                if (destroyed) return@execute
                try {
                    ProjectionState.status = "正在检测虚拟屏能力"
                    val probe = remote.probeCapabilities()
                    Log.i(TAG, "Capability probe: $probe")
                    if (!probe.startsWith("可用")) error(probe)

                    installDisplay(remote, requestedWidth, requestedHeight)

                    val newServer = LocalProjectionServer.startWithFallback(
                        this,
                        frameStore,
                        object : ControlSink {
                            override fun onPointer(
                                gestureId: Int,
                                action: Int,
                                x: Float,
                                y: Float,
                                pointerId: Int
                            ): Boolean {
                                val targetDisplayId = displayId ?: return false
                                return try {
                                    remote.injectTouch(
                                        targetDisplayId,
                                        gestureId,
                                        action,
                                        x,
                                        y,
                                        pointerId
                                    )
                                } catch (error: Throwable) {
                                    Log.w(TAG, "Pointer injection failed", error)
                                    false
                                }
                            }

                            override fun onCancel(gestureId: Int) {
                                val targetDisplayId = displayId ?: return
                                runCatching {
                                    remote.cancelTouch(targetDisplayId, gestureId)
                                }.onFailure { error ->
                                    Log.w(TAG, "Pointer cancellation failed", error)
                                }
                            }

                            override fun onKey(keyCode: Int): Boolean {
                                val targetDisplayId = displayId ?: return false
                                return try {
                                    remote.injectKey(targetDisplayId, keyCode)
                                } catch (error: Throwable) {
                                    Log.w(TAG, "Key injection failed", error)
                                    false
                                }
                            }

                            override fun onViewport(width: Int, height: Int): Boolean {
                                return requestViewportResize(width, height)
                            }
                        }
                    )
                    if (destroyed) {
                        newServer.stop()
                        return@execute
                    }
                    server = newServer
                    ProjectionState.accessUrl = newServer.accessUrl(connectionMode)
                    ProjectionState.serverPort = newServer.activePort
                    ProjectionState.running = true
                    ProjectionState.status = "投屏已启动，等待车机浏览器"
                    scheduleProjectionTimeout()
                    updateNotification("车机打开 ${ProjectionState.accessUrl}")
                } catch (error: Throwable) {
                    if (!destroyed) {
                        fail(error.rootMessage())
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!destroyed) {
                fail("投屏启动任务未能提交：${error.rootMessage()}")
            }
        }
    }

    private fun requestViewportResize(width: Int, height: Int): Boolean {
        val size = normalizeViewport(width, height) ?: return false
        if (destroyed || pipelineExecutor.isShutdown) return false
        if (abs(size.width - requestedWidth) < MIN_RESIZE_DELTA &&
            abs(size.height - requestedHeight) < MIN_RESIZE_DELTA
        ) {
            return true
        }
        requestedWidth = size.width
        requestedHeight = size.height
        try {
            pipelineExecutor.execute {
                if (destroyed) return@execute
                val remote = privileged ?: return@execute
                if (requestedWidth != size.width || requestedHeight != size.height) {
                    return@execute
                }
                if (ProjectionState.width == size.width && ProjectionState.height == size.height) {
                    return@execute
                }
                val previousWidth = ProjectionState.width.takeIf { it > 0 } ?: DEFAULT_DISPLAY_WIDTH
                val previousHeight = ProjectionState.height.takeIf { it > 0 } ?: DEFAULT_DISPLAY_HEIGHT
                ProjectionState.status = "正在适配浏览器 ${size.width}×${size.height}"
                try {
                    installDisplay(remote, size.width, size.height)
                    ProjectionState.status = "投屏已启动，已适配浏览器"
                    updateNotification("已适配浏览器 ${size.width}×${size.height}")
                } catch (resizeError: Throwable) {
                    Log.e(TAG, "Viewport resize failed; restoring previous display", resizeError)
                    requestedWidth = previousWidth
                    requestedHeight = previousHeight
                    try {
                        installDisplay(remote, previousWidth, previousHeight)
                        ProjectionState.status = "浏览器尺寸适配失败，已恢复原尺寸"
                        updateNotification(ProjectionState.status)
                    } catch (restoreError: Throwable) {
                        resizeError.addSuppressed(restoreError)
                        fail("浏览器尺寸适配失败：${resizeError.rootMessage()}")
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!destroyed) {
                Log.w(TAG, "Viewport resize ignored because projection is stopping", error)
            }
            return false
        }
        return true
    }

    private fun installDisplay(
        remote: IPrivilegedDisplayService,
        width: Int,
        height: Int
    ) {
        displayWatchdog?.shutdownNow()
        displayWatchdog = null
        displayId = null
        ProjectionState.displayId = null
        frameStore.clear()

        val newEncoder = MjpegEncoder(
            width = width,
            height = height,
            frameStore = frameStore,
            fps = DISPLAY_FPS,
            quality = JPEG_QUALITY
        )
        try {
            ProjectionState.status = "正在创建 ${width}×${height} 高德车机版虚拟屏"
            val newDisplayId = remote.createVirtualDisplay(
                "MapProjection",
                width,
                height,
                DISPLAY_DENSITY_DPI,
                DISPLAY_FPS.toFloat(),
                newEncoder.surface
            )
            if (newDisplayId < 0) error("系统未能创建虚拟 Display")

            ProjectionState.status = "正在虚拟屏中启动高德车机版"
            if (!remote.launchPackage(newDisplayId, AMAP_AUTO_PACKAGE)) {
                remote.releaseDisplay(newDisplayId)
                error("无法在虚拟屏启动高德车机版")
            }

            val oldEncoder = encoder
            encoder = newEncoder
            displayId = newDisplayId
            ProjectionState.displayId = newDisplayId
            ProjectionState.width = width
            ProjectionState.height = height
            ProjectionState.densityDpi = DISPLAY_DENSITY_DPI
            ProjectionState.targetFps = DISPLAY_FPS
            runCatching { oldEncoder?.close() }
            Log.i(TAG, "Virtual display ready: id=$newDisplayId ${width}x$height")
            startDisplayWatchdog(newDisplayId, remote)
        } catch (error: Throwable) {
            newEncoder.close()
            throw error
        }
    }

    private fun normalizeViewport(width: Int, height: Int): DisplaySize? {
        if (width < MIN_VIEWPORT_WIDTH || height < MIN_VIEWPORT_HEIGHT) return null
        val scale = minOf(
            1.0,
            MAX_DISPLAY_WIDTH.toDouble() / width,
            MAX_DISPLAY_HEIGHT.toDouble() / height
        )
        val normalizedWidth = ((width * scale).toInt().coerceAtLeast(2) + 1) and -2
        val normalizedHeight = ((height * scale).toInt().coerceAtLeast(2) + 1) and -2
        return DisplaySize(normalizedWidth, normalizedHeight)
    }

    private fun startDisplayWatchdog(
        expectedDisplayId: Int,
        remote: IPrivilegedDisplayService
    ) {
        displayWatchdog?.shutdownNow()
        displayWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Amap2Tesla-display-watchdog").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    if (!ProjectionState.running || displayId != expectedDisplayId) {
                        return@scheduleWithFixedDelay
                    }
                    try {
                        when (val result = remote.ensurePackageOnDisplay(
                            expectedDisplayId,
                            AMAP_AUTO_PACKAGE
                        )) {
                            1 -> {
                                Log.w(TAG, "Amap Auto task returned to display $expectedDisplayId")
                                updateNotification("已自动恢复高德车机版虚拟屏")
                            }
                            0 -> Unit
                            else -> Log.w(TAG, "Amap Auto display watchdog failed: $result")
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "Amap Auto display watchdog error", error)
                    }
                },
                WATCHDOG_INITIAL_DELAY_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun scheduleProjectionTimeout() {
        val durationMillis = maxProjectionDurationMillis ?: return
        projectionTimeout?.shutdownNow()
        projectionTimeout = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Amap2Tesla-projection-timeout").apply { isDaemon = true }
        }.also { executor ->
            executor.schedule(
                {
                    if (destroyed || !ProjectionState.running) return@schedule
                    finalStatus = "今日试用时长已达 45 分钟，已自动停止"
                    ProjectionState.status = finalStatus
                    updateNotification(finalStatus)
                    stopSelf()
                },
                durationMillis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        finalStatus = "错误：$message"
        ProjectionState.running = false
        ProjectionState.status = finalStatus
        updateNotification("启动失败：$message")
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Amap2Tesla 投屏",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(content: String): android.app.Notification {
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Amap2Tesla")
            .setContentText(content)
            .setContentIntent(activity)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "navipilot_amap_projection"
        private const val TAG = "Amap2TeslaService"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_START = "com.jixiexiaoge.drivingassist.AMAP_PROJECTION_START"
        private const val ACTION_STOP = "com.jixiexiaoge.drivingassist.AMAP_PROJECTION_STOP"
        private const val EXTRA_CONNECTION_MODE = "connection_mode"
        private const val AMAP_AUTO_PACKAGE = "com.autonavi.amapauto"
        private const val DEFAULT_DISPLAY_WIDTH = 960
        private const val DEFAULT_DISPLAY_HEIGHT = 720
        private const val MIN_VIEWPORT_WIDTH = 320
        private const val MIN_VIEWPORT_HEIGHT = 240
        private const val MIN_RESIZE_DELTA = 8
        private const val MAX_DISPLAY_WIDTH = 1920
        private const val MAX_DISPLAY_HEIGHT = 1080
        private const val DISPLAY_DENSITY_DPI = 280
        private const val DISPLAY_FPS = 30
        private const val JPEG_QUALITY = 60
        private const val WATCHDOG_INITIAL_DELAY_SECONDS = 2L
        private const val WATCHDOG_INTERVAL_SECONDS = 3L
        private const val DAILY_TRIAL_DURATION_MILLIS = DailyProjectionTrial.DAILY_LIMIT_MILLIS
        private const val EXTRA_MAX_DURATION_MILLIS = "max_duration_millis"
        private const val EXTRA_TRIAL_MODE = "trial_mode"

        fun start(
            context: Context,
            mode: ConnectionMode = ConnectionMode.load(context),
            trialMode: Boolean = true,
        ) {
            ConnectionMode.save(context, mode)
            val maxDurationMillis = if (trialMode) DailyProjectionTrial.begin(context) else null
            if (trialMode && maxDurationMillis == 0L) {
                ProjectionState.running = false
                ProjectionState.status = "今日 45 分钟试用额度已用完"
                return
            }
            ProjectionState.running = true
            ProjectionState.status = "正在启动服务"
            ProjectionState.trialMode = trialMode
            context.startForegroundService(
                Intent(context, ProjectionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONNECTION_MODE, mode.name)
                    .putExtra(EXTRA_MAX_DURATION_MILLIS, maxDurationMillis ?: -1L)
                    .putExtra(EXTRA_TRIAL_MODE, trialMode)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}

private data class DisplaySize(val width: Int, val height: Int)

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
