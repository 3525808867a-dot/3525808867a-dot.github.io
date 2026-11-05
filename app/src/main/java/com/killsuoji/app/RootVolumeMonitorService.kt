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
 * 不请求辅助功能权限；停止时销毁 root getevent 进程。
 */
class RootVolumeMonitorService : Service() {
    @Volatile private var rootProcess: Process? = null
    @Volatile private var monitoring = false
    private var monitorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        try {
            // exec 让 su 进程直接变成 getevent，停止服务时可以可靠结束 root 监听进程。
            val process = ProcessBuilder("su", "-c", "exec /system/bin/getevent -l /dev/input/event*")
                .redirectErrorStream(true)
                .start()
            rootProcess = process
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (monitoring) {
                    val line = reader.readLine() ?: break
                    if (line.contains("KEY_VOLUMEDOWN") && line.trimEnd().endsWith("DOWN")) {
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
            if (monitoring && process.exitValueOrNull() != 0) {
                updateNotification("Root 输入监听未能读取音量键，请检查设备 getevent 支持")
            }
        } catch (e: Exception) {
            updateNotification("Root 音量键监听失败：" + (e.message ?: "请检查 Root 授权"))
        } finally {
            rootProcess = null
            if (monitoring) {
                monitoring = false
                stopSelf()
            }
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

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KillSuoji · 工作中")
            .setContentText("Root 正在监听音量下键，连续按 5 次返回应用")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification().also {
            it.extras.putCharSequence(Notification.EXTRA_TEXT, message)
        })
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

    private fun Process.exitValueOrNull(): Int? = try { exitValue() } catch (_: IllegalThreadStateException) { null }

    companion object {
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
    }
}
