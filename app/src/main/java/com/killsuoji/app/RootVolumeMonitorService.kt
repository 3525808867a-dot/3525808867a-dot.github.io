package com.killsuoji.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
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
    @Volatile private var triggerInProgress = false
    @Volatile private var ignoreEventsUntil = 0L
    private var overlayView: TextView? = null
    private var overlayWindowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
        showKillzuojiOverlay()
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
        var lastPressAt = 0L

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
                        val eventAt = getFreshEventUptime(line) ?: continue
                        if (!isVolumeDownKeyPress(line)) continue
                        val now = SystemClock.uptimeMillis()
                        // 丢弃 getevent 缓冲区中的旧事件，避免返回应用时补执行旧的五击。
                        if (now < ignoreEventsUntil || triggerInProgress) continue
                        // 同一次物理按键可能由多个输入设备重复上报，做短间隔去重。
                        if (lastPressAt != 0L && eventAt - lastPressAt < 180L) continue
                        if (count == 0 || eventAt - firstPressAt > 3000L || eventAt < firstPressAt) {
                            count = 0
                            firstPressAt = eventAt
                        }
                        lastPressAt = eventAt
                        count++
                        updateNotification("已检测到音量下键：$count/5")
                        if (count >= 5) {
                            count = 0
                            firstPressAt = 0L
                            lastPressAt = 0L
                            triggerInProgress = true
                            ignoreEventsUntil = now + 5000L
                            try {
                                launchMainActivity()
                            } finally {
                                triggerInProgress = false
                                ignoreEventsUntil = SystemClock.uptimeMillis() + 5000L
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
     * getevent -t 输出设备启动后的单调时钟时间戳。
     * 只处理最近 1.5 秒内产生的事件，防止积压事件在返回应用后补执行。
     */
    private fun getFreshEventUptime(line: String): Long? {
        val match = EVENT_TIMESTAMP.find(line) ?: return null
        val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
        val eventAt = (seconds * 1000.0).toLong()
        val now = SystemClock.uptimeMillis()
        return if (eventAt <= now && now - eventAt <= 1500L) eventAt else null
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

    /** 五次音量下键触发：先停止所有第三方应用，再执行既有启动脚本。 */
    private fun launchMainActivity() {
        val scriptPath = "/data/local/tmp/launch_killapp.sh"
        try {
            updateNotification("已触发快捷键，正在清理前台和后台应用进程…")
            // 仅停止第三方应用（pm -3），保护 Android 系统组件及 KillSuoji 自身。
            // 清理完成后才执行启动脚本，避免 Activity 先跳转而中断清理。
            val command =
                "/system/bin/appops set com.killsuoji.app SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; " +
                "count=0; " +
                "for pkg in \$(pm list packages -3 | cut -d: -f2); do " +
                "[ -n \"\$pkg\" ] || continue; " +
                "[ \"\$pkg\" = 'com.killsuoji.app' ] && continue; " +
                "am force-stop --user 0 \"\$pkg\" >/dev/null 2>&1; " +
                "count=\$((count + 1)); " +
                "done; echo CLEANED:\$count; sh '$scriptPath'"
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                updateNotification("应用进程清理超时，未继续跳转")
                return
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            val cleaned = Regex("CLEANED:(\\d+)").find(output)?.groupValues?.get(1) ?: "未知"
            updateNotification(
                if (exitCode == 0 && !output.contains("Error:", ignoreCase = true)) {
                    "已清理第三方应用进程（$cleaned 个），正在启动 KillSuoji"
                } else {
                    "清理或启动失败（$exitCode）：${output.take(120)}"
                }
            )
        } catch (e: Exception) {
            updateNotification("Root 清理/启动失败：" + (e.message ?: "未知错误"))
        }
    }


    /** 固定显示在底部导航栏“首页”文字位置的不可交互悬浮标记。 */
    private fun showKillzuojiOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return
        try {
            val density = resources.displayMetrics.density
            val label = TextView(this).apply {
                text = "killzuoji"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (5 * density).toInt(),
                    (8 * density).toInt(), (5 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.BLACK)
                    cornerRadius = 0f
                }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                x = (resources.displayMetrics.widthPixels / 6f - 31f * density).toInt().coerceAtLeast(0)
                y = (34f * density).toInt()
            }
            wm.addView(label, params)
            overlayWindowManager = wm
            overlayView = label
        } catch (_: Exception) {
            overlayView = null
            overlayWindowManager = null
        }
    }

    private fun removeKillzuojiOverlay() {
        try {
            overlayView?.let { overlayWindowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null
        overlayWindowManager = null
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
        removeKillzuojiOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
        private val EVENT_TIMESTAMP = Regex("^\\[\\s*(\\d+\\.\\d+)\\]")
        private val VOLUME_DOWN_NAME = Regex("\\bKEY_?VOLUMEDOWN\\b|\\bKEY_VOLUME_DOWN\\b", RegexOption.IGNORE_CASE)
        private val VOLUME_DOWN_CODE = Regex("(?:^|\\s)(?:0x0*72|114)(?:\\s|$)", RegexOption.IGNORE_CASE)
        private val KEY_DOWN_VALUE = Regex("(?:^|\\s)(?:DOWN|0x0*1|0*1)\\s*$", RegexOption.IGNORE_CASE)
    }
}
