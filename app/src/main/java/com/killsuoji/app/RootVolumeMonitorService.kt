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
        return START_STICKY
    }

    private fun monitorVolumeDown() {
        var count = 0
        var firstPressAt = 0L

        // getevent 偶发退出或输入设备重置时自动重连；START_STICKY 负责服务进程被系统回收后的恢复。
        while (monitoring) {
            var process: Process? = null
            try {
                val activeProcess = ProcessBuilder("su", "-c", "exec /system/bin/getevent -lt")
                    .redirectErrorStream(true)
                    .start()
                process = activeProcess
                rootProcess = activeProcess
                updateNotification("Root 全局音量下键监听运行中")

                BufferedReader(InputStreamReader(activeProcess.inputStream)).use { reader ->
                    while (monitoring) {
                        val line = reader.readLine() ?: break
                        if (isVolumeDownKeyPress(line)) {
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
                    val exitCode = try { activeProcess.waitFor() } catch (_: InterruptedException) { -1 }
                    updateNotification("Root 按键监听进程退出（$exitCode），正在重新连接")
                }
            } catch (e: Exception) {
                if (monitoring) {
                    updateNotification("Root 监听暂时中断，正在重试：" + (e.message ?: "未知错误"))
                }
            } finally {
                rootProcess = null
            }

            if (monitoring) {
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                    if (!monitoring) break
                }
            }
        }
        monitoring = false
    }

    /**
     * getevent -l 在部分厂商内核上输出 KEY_VOLUMEDOWN，
     * 另一些设备只输出 Linux 键码 0x0072（十进制 114）。
     * 只接受 EV_KEY 的 DOWN/数值 1，忽略 UP 与长按重复值 2。
     */
    private fun isVolumeDownKeyPress(line: String): Boolean {
        if (!line.contains("EV_KEY", ignoreCase = true)) return false

        val isVolumeDown = VOLUME_DOWN_NAME.containsMatchIn(line) ||
            VOLUME_DOWN_CODE.containsMatchIn(line)
        if (!isVolumeDown) return false

        return KEY_DOWN_VALUE.containsMatchIn(line)
    }

    /** 每累计五次音量下键，只以 Root 身份执行一次用户指定的启动脚本。 */
    private fun launchMainActivity() {
        val scriptPath = "/data/local/tmp/launch_killapp.sh"
        try {
            val process = ProcessBuilder("su", "-c", "sh '$scriptPath'")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                updateNotification("Root 启动脚本执行超时，请检查脚本与 Root 授权")
                return
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            updateNotification(
                if (exitCode == 0 && !output.contains("Error:", ignoreCase = true)) {
                    "已由 Root 执行 launch_killapp.sh"
                } else {
                    "Root 启动脚本执行失败（$exitCode）：${output.take(120)}"
                }
            )
        } catch (e: Exception) {
            updateNotification("Root 启动脚本执行失败：" + (e.message ?: "未知错误"))
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
        private val VOLUME_DOWN_NAME = Regex("\\bKEY_?VOLUMEDOWN\\b|\\bKEY_VOLUME_DOWN\\b", RegexOption.IGNORE_CASE)
        private val VOLUME_DOWN_CODE = Regex("(?:^|\\s)(?:0x0*72|114)(?:\\s|$)", RegexOption.IGNORE_CASE)
        private val KEY_DOWN_VALUE = Regex("(?:^|\\s)(?:DOWN|0x0*1|0*1)\\s*$", RegexOption.IGNORE_CASE)
    }
}
