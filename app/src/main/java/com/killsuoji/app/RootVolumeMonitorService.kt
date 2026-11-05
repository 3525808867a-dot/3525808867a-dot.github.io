package com.killsuoji.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * 用户点击“执行”后启动的前台服务。由 su 运行 getevent 读取全局音量下键，
 * 不请求辅助功能权限。监听失败时保留错误通知，避免界面仍显示工作中却没有监听。
 */
class RootVolumeMonitorService : Service() {
    @Volatile private var rootProcess: Process? = null
    @Volatile private var monitoring = false
    private var monitorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!monitoring) {
            monitoring = true
            monitorThread = thread(name = "KillSuoji-Root-Volume-Monitor") { monitorVolumeDown() }
        }
        return START_NOT_STICKY
    }

    private fun monitorVolumeDown() {
        var count = 0
        var firstPressAt = 0L
        var failureMessage: String? = null
        try {
            // 不预先枚举 event 节点：让 getevent 自行监听设备上的全部输入节点，
            // 避免不同厂商设备的 event 节点数量/权限布局不同导致通配符失效。
            val process = ProcessBuilder("su", "-c", "exec /system/bin/getevent -lt")
                .redirectErrorStream(true)
                .start()
            rootProcess = process

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (monitoring) {
                    val line = reader.readLine() ?: break
                    // 不依赖 getevent 输出行的固定结尾格式。
                    if (line.contains("KEY_VOLUMEDOWN") && Regex("\\bDOWN\\b").containsMatchIn(line)) {
                        val now = SystemClock.elapsedRealtime()
                        if (count == 0 || now - firstPressAt > 3000L) {
                            count = 0
                            firstPressAt = now
                        }
                        count++
                        if (count >= 5) {
                            count = 0
                            firstPressAt = 0L
                            returnToApp()
                        }
                    }
                }
            }

            if (monitoring) {
                val exitCode = try { process.waitFor() } catch (_: InterruptedException) { -1 }
                failureMessage = "Root 监听进程已退出（代码 $exitCode），请检查 Root 授权和设备输入事件支持"
            }
        } catch (e: Exception) {
            failureMessage = "Root 音量键监听失败：" + (e.message ?: "请检查 Root 授权")
        } finally {
            rootProcess = null
            if (monitoring) {
                monitoring = false
                updateNotification(failureMessage ?: "监听已停止；请重新点击执行")
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun returnToApp() {
        try {
            ProcessBuilder(
                "su", "-c",
                "am start --activity-single-top --activity-clear-top -n com.killsuoji.app/.MainActivity --ez killsuoji_trigger true"
            ).redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("killsuoji_trigger", true)
            }
            startActivity(intent)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KillSuoji 工作状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(message: String = "Root 正在监听音量下键，连续按 5 次返回应用"): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KillSuoji · 工作状态")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        monitoring = false
        rootProcess?.destroy()
        rootProcess?.destroyForcibly()
        rootProcess = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
    }
}
