package com.killsuoji.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.util.Base64
import android.widget.TextView
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 用户主动开启工作状态后显示固定悬浮窗；点击悬浮窗执行 Root 启动脚本。 */
class RootVolumeMonitorService : Service() {
    private var overlayView: TextView? = null
    private var overlayWindowManager: WindowManager? = null
    @Volatile private var scriptRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
        startRootWatchdog()
        showKillzuojiOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showKillzuojiOverlay()
        return START_STICKY
    }

    /** 固定在底部导航栏“首页”文字位置；可点击执行脚本，不可拖动。 */
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
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { executeLaunchScript() }
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    /** Root 守护进程独立于应用进程运行；检测悬浮窗服务进程退出后重新拉起。 */
    private fun startRootWatchdog() {
        val script = """#!/system/bin/sh
PIDFILE=/data/local/tmp/killsuoji_overlay_watchdog.pid
ENABLED=/data/local/tmp/killsuoji_overlay_enabled
if [ -f "${'$'}PIDFILE" ]; then
  OLD_PID=${\u0024\u007b\u0027$\u0027\u007d}(cat "${\u0024\u007b\u0027$\u0027\u007d}PIDFILE" 2>/dev/null)
  if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then exit 0; fi
fi
echo ${'$'}${'$'} > "${'$'}PIDFILE"
trap 'rm -f "${'$'}PIDFILE"' EXIT
while [ -f "${'$'}ENABLED" ]; do
  if ! pidof com.killsuoji.app:overlay >/dev/null 2>&1; then
    am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService >/dev/null 2>&1
  fi
  sleep 3
done
"""
        val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "echo '$encoded' | base64 -d > /data/local/tmp/killsuoji_overlay_watchdog.sh && chmod 700 /data/local/tmp/killsuoji_overlay_watchdog.sh && touch /data/local/tmp/killsuoji_overlay_enabled && (nohup sh /data/local/tmp/killsuoji_overlay_watchdog.sh >/dev/null 2>&1 </dev/null & )"
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    private fun stopRootWatchdog() {
        try {
            val command = "rm -f /data/local/tmp/killsuoji_overlay_enabled; if [ -f /data/local/tmp/killsuoji_overlay_watchdog.pid ]; then kill $(cat /data/local/tmp/killsuoji_overlay_watchdog.pid) 2>/dev/null; rm -f /data/local/tmp/killsuoji_overlay_watchdog.pid; fi"
            ProcessBuilder("su", "-c", command).start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    private fun executeLaunchScript() {
        if (scriptRunning) {
            updateNotification("启动脚本正在执行，请稍候")
            return
        }
        scriptRunning = true
        updateNotification("已点击 killzuoji，正在以 Root 执行启动脚本")
        thread(name = "KillSuoji-Launch-Script") {
            try {
                val process = ProcessBuilder("su", "-c", "sh /data/local/tmp/launch_killapp.sh")
                    .redirectErrorStream(true).start()
                val finished = process.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    updateNotification("启动脚本执行超时")
                } else {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    updateNotification(if (process.exitValue() == 0) "Root 启动脚本执行完成"
                        else "启动脚本执行失败（${process.exitValue()}）：${output.take(100)}")
                }
            } catch (e: Exception) {
                updateNotification("Root 启动脚本执行失败：" + (e.message ?: "未知错误"))
            } finally {
                scriptRunning = false
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KillSuoji 工作状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(message: String = "点击 killzuoji 悬浮窗执行启动脚本"): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KillSuoji · 工作状态").setContentText(message)
            .setContentIntent(pending).setOngoing(true).build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun removeKillzuojiOverlay() {
        try { overlayView?.let { overlayWindowManager?.removeView(it) } } catch (_: Exception) { }
        overlayView = null
        overlayWindowManager = null
    }

    override fun onDestroy() {
        stopRootWatchdog()
        removeKillzuojiOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
    }
}
