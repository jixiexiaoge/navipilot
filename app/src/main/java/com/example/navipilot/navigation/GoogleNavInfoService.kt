package com.example.navipilot.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo

/**
 * Google Navigation SDK NavInfo 接收服务
 *
 * 通过 Messenger IPC 机制接收 Navigation SDK 发送的 NavInfo 消息，
 * 将其解析后通过回调通知 [GoogleNavManager]，从而恢复 TBT 转弯/道路名称等数据链路。
 *
 * SDK 7.0.0 移除了旧的 Navigator 监听器 API，改用此 Service + Messenger 方案。
 * 参考 Google 官方 Navigation Sample 的 NavInfoReceivingService 实现。
 */
class GoogleNavInfoService : Service() {

    companion object {
        private const val TAG = "GoogleNavInfoSvc"

        @Volatile
        private var listener: OnNavInfoListener? = null

        /**
         * 设置 NavInfo 接收回调（由 GoogleNavManager 注册）
         * 注意：回调会在后台 HandlerThread 上触发，实现者需自行切换到主线程
         */
        fun setListener(l: OnNavInfoListener?) {
            listener = l
        }

        interface OnNavInfoListener {
            fun onNavInfoReceived(navInfo: NavInfo)
        }
    }

    private lateinit var incomingMessenger: Messenger
    private var handlerThread: HandlerThread? = null

    /**
     * 内部 Handler：在独立 HandlerThread 上处理 NavInfo 消息
     */
    private class IncomingNavStepHandler(
        looper: Looper
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
                try {
                    val navInfo = TurnByTurnManager.createInstance()
                        .readNavInfoFromBundle(msg.data)
                    if (navInfo != null) {
                        listener?.let { l ->
                            // 在主线程回调，方便更新 UI/状态
                            Handler(Looper.getMainLooper()).post {
                                l.onNavInfoReceived(navInfo)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析 NavInfo 失败: ${e.message}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("NavInfoThread", Process.THREAD_PRIORITY_DEFAULT)
        thread.start()
        handlerThread = thread
        incomingMessenger = Messenger(IncomingNavStepHandler(thread.looper))
        Log.i(TAG, "GoogleNavInfoService 已创建")
    }

    override fun onBind(intent: Intent): IBinder {
        return incomingMessenger.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        listener = null
        handlerThread?.quitSafely()
        handlerThread = null
        Log.i(TAG, "GoogleNavInfoService 已销毁")
    }
}
