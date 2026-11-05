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
        if (intent?.action == ACTION_STOP_WORKING) {
            // 只有用户明确点击“停止”时才关闭独立 Root 守护进程。
            stopRootWatchdog()
            removeKillzuojiOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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

    /** 通过 SukiSU 模块启动独立 Root 用户态守护进程；状态保存在 /data/adb，跨重启保留。 */
    private fun startRootWatchdog() {
        val command = """
            MODDIR=/data/adb/modules/killsuoji_overlay
            STATE_DIR=/data/adb/killsuoji_overlay
            mkdir -p "$STATE_DIR" || exit 1
            if [ ! -f "$MODDIR/watchdog.sh" ]; then
              echo MODULE_MISSING
              exit 2
            fi
            touch "$STATE_DIR/enabled"
            chmod 700 "$MODDIR/watchdog.sh"
            nohup sh "$MODDIR/watchdog.sh" >/dev/null 2>&1 </dev/null &
        """.trimIndent()
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                updateNotification("Root 守护进程启动超时")
            } else if (process.exitValue() != 0) {
                updateNotification("未检测到 SukiSU 模块，请先安装 KillSuoji Overlay Keeper 1.0.0 beta")
            }
        } catch (_: Exception) {
            updateNotification("无法启动 Root 守护进程，请检查 SukiSU Root 授权")
        }
    }

    private fun stopRootWatchdog() {
        try {
            val command = """
                STATE_DIR=/data/adb/killsuoji_overlay
                PIDFILE="$STATE_DIR/watchdog.pid"
                rm -f "$STATE_DIR/enabled"
                if [ -f "$PIDFILE" ]; then
                  PID=$(cat "$PIDFILE" 2>/dev/null)
                  [ -n "$PID" ] && kill "$PID" 2>/dev/null
                  rm -f "$PIDFILE"
                fi
            """.trimIndent()
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
        // 系统回收悬浮窗服务时不能关闭 watchdog；它需要负责重新拉起服务。
        // 用户主动停止时，ACTION_STOP_WORKING 已先明确关闭 watchdog。
        removeKillzuojiOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP_WORKING = "com.killsuoji.app.action.STOP_WORKING"
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
    }
}
