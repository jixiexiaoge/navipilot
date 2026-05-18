package com.example.navipilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * CarrotAmap前台服务
 * 确保应用在后台稳定运行，处理高德地图数据转发
 */
class CarrotAmapForegroundService : Service() {
    
    companion object {
        private const val TAG = "CarrotAmapForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "carrot_amap_foreground"
        private const val CHANNEL_NAME = "CarrotAmap前台服务"
        
        // 服务控制Action
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_UPDATE_STATUS = "UPDATE_STATUS"
        
        // 服务状态
        private var isServiceRunning = false
    }
    
    // 服务组件
    private lateinit var notificationManager: NotificationManager
    private var serviceStartTime = 0L
    private var dataProcessedCount = 0
    private var lastUpdateTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🔧 前台服务创建")
        
        // 初始化通知管理器
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 创建通知渠道
        createNotificationChannel()
        
        serviceStartTime = System.currentTimeMillis()
        isServiceRunning = true
        
        Log.i(TAG, "✅ 前台服务初始化完成")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🚀 前台服务启动命令")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundService()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
            ACTION_UPDATE_STATUS -> {
                updateServiceStatus()
            }
            else -> {
                // 默认启动前台服务
                startForegroundService()
            }
        }
        
        // 返回START_STICKY确保服务被系统杀死后自动重启
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🔧 前台服务销毁")
        
        isServiceRunning = false
        
        // 停止前台服务
        @Suppress("DEPRECATION")
        stopForeground(true)
        
        Log.i(TAG, "✅ 前台服务已停止")
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        Log.i(TAG, "🔔 启动前台服务")
        
        try {
            // 创建前台服务通知
            val notification = createForegroundNotification()
            
            // 启动前台服务
            startForeground(NOTIFICATION_ID, notification)
            
            Log.i(TAG, "✅ 前台服务已启动")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动前台服务失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        Log.i(TAG, "🛑 停止前台服务")
        
        try {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            
            Log.i(TAG, "✅ 前台服务已停止")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 停止前台服务失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新服务状态
     */
    private fun updateServiceStatus() {
        Log.d(TAG, "📊 更新服务状态")
        
        try {
            val notification = createForegroundNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            lastUpdateTime = System.currentTimeMillis()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 更新服务状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CarrotAmap前台服务通知渠道"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            notificationManager.createNotificationChannel(channel)
            Log.i(TAG, "📢 通知渠道已创建")
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createForegroundNotification(): Notification {
        // 创建点击通知时打开应用的Intent
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 计算运行时间
        val runningTime = System.currentTimeMillis() - serviceStartTime
        val runningMinutes = runningTime / (1000 * 60)
        
        // 构建通知内容
        val contentText = buildString {
            append("运行时间: ${runningMinutes}分钟")
            if (dataProcessedCount > 0) {
                append(" | 处理数据: ${dataProcessedCount}条")
            }
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarrotAmap正在运行")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }
    
    /**
     * 更新数据处理计数
     */
    fun updateDataProcessedCount(count: Int) {
        dataProcessedCount = count
        updateServiceStatus()
    }
    
    /**
     * 获取服务运行状态
     */
    fun isServiceRunning(): Boolean {
        return isServiceRunning
    }
    
    /**
     * 获取服务运行时间
     */
    fun getServiceRunningTime(): Long {
        return if (isServiceRunning) {
            System.currentTimeMillis() - serviceStartTime
        } else {
            0L
        }
    }
    
    /**
     * 获取数据处理计数
     */
    fun getDataProcessedCount(): Int {
        return dataProcessedCount
    }
}
