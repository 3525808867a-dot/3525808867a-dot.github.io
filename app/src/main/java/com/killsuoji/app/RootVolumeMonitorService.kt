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
 * 用户主动点击“执行”后启动的前台服务。
 * 使用 Root getevent 监听全局音量下键；连续 5 次后将主界面切到前台。
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
            val process = ProcessBuilder("su", "-c", "exec /system/bin/getevent -lt")
                .redirectErrorStream(true)
                .start()
            rootProcess = process

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (monitoring) {
                    val line = reader.readLine() ?: break
                    // 只统计音量下键的 DOWN；忽略 UP 和长按 REPEAT。
                    if (line.contains("KEY_VOLUMEDOWN") &&
                        Regex("\\b(?:DOWN|1)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (count == 0 || now - firstPressAt > 3000L) {
                            count = 0
                            firstPressAt = now
                        }
                        count++
                        updateNotification("已检测到音量下键：$count/5")
                        if (count >= 5) {
                            count = 0
                            firstPressAt = 0L
                            launchMainActivity()
                        }
                    }
                }
            }

            if (monitoring) {
                val exitCode = try { process.waitFor() } catch (_: InterruptedException) { -1 }
                failureMessage = "Root 按键监听进程已退出（代码 $exitCode），请检查 Root 授权"
            }
        } catch (e: Exception) {
            failureMessage = "Root 音量下键监听失败：" + (e.message ?: "请检查 Root 授权")
        } finally {
            rootProcess = null
            if (monitoring) {
                monitoring = false
                updateNotification(failureMessage ?: "监听已停止；请重新点击执行")
            }
        }
    }

    /**
     * 优先由应用自身的前台服务启动 Activity。
     * Android 10+ 默认限制后台 Activity 启动，因此执行前要求用户授予
     * “显示在其他应用上层”权限；该权限是 Android 官方列出的 BAL 例外。
     * Root am start 作为兼容性回退，并使用明确的 Intent flags。
     */
    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("killsuoji_trigger", true)
        }

        try {
            startActivity(intent)
            updateNotification("已发送启动请求：KillSuoji 正在切到前台")
            return
        } catch (e: Exception) {
            updateNotification("应用内启动受限，正在尝试 Root 启动")
        }

        try {
            val command = "am start -W -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -f 0x34020000 " +
                "-n com.killsuoji.app/.MainActivity --ez killsuoji_trigger true"
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val failed = exitCode != 0 ||
                output.contains("Error:", ignoreCase = true) ||
                output.contains("Exception", ignoreCase = true)
            updateNotification(
                if (failed) "已检测到 5 次音量下键，但启动失败：${output.take(120)}"
                else "已发送 Root 启动请求；若未切到前台，请检查系统后台弹出限制"
            )
        } catch (e: Exception) {
            updateNotification("已检测到 5 次音量下键，但启动失败：" + (e.message ?: "未知错误"))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KillSuoji 工作状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(message: String = "Root 正在监听音量下键，3 秒内连续按 5 次启动应用"): Notification {
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
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
