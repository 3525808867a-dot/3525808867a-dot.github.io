package com.killsuoji.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.util.Base64
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 工作服务负责通知和 Root 操作；窗口由模块启动的独立 UID 0 进程创建。 */
class RootVolumeMonitorService : Service() {
    @Volatile private var scriptRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
        startRootWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXECUTE_NOW) {
            executeLaunchScript()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_WORKING) {
            stopRootWatchdog()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** 通过 SukiSU 模块启动 UID 0 窗口进程及守护进程。 */
    private fun startRootWatchdog() {
        val command = """
            MODDIR=/data/adb/modules/killsuoji_overlay
            STATE_DIR=/data/adb/killsuoji_overlay
            mkdir -p "${'$'}STATE_DIR" || exit 1
            if [ ! -f "${'$'}MODDIR/watchdog.sh" ]; then
              echo MODULE_MISSING
              exit 2
            fi
            touch "${'$'}STATE_DIR/enabled"
            chmod 700 "${'$'}MODDIR/watchdog.sh"
            nohup sh "${'$'}MODDIR/watchdog.sh" >/dev/null 2>&1 </dev/null &
        """.trimIndent()
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                updateNotification("Root 窗口守护进程启动超时")
            } else if (process.exitValue() != 0) {
                updateNotification("未检测到 SukiSU 必要模块，请先安装模块")
            }
        } catch (_: Exception) {
            updateNotification("无法启动 Root 窗口，请检查 SukiSU Root 授权")
        }
    }

    private fun stopRootWatchdog() {
        try {
            val command = """
                STATE_DIR=/data/adb/killsuoji_overlay
                rm -f "${'$'}STATE_DIR/enabled"
                for PIDFILE in "${'$'}STATE_DIR/watchdog.pid" "${'$'}STATE_DIR/root-window.pid"; do
                  if [ -f "${'$'}PIDFILE" ]; then
                    PID=$(cat "${'$'}PIDFILE" 2>/dev/null)
                    [ -n "${'$'}PID" ] && kill "${'$'}PID" 2>/dev/null
                    rm -f "${'$'}PIDFILE"
                  fi
                done
            """.trimIndent()
            ProcessBuilder("su", "-c", command).start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    private fun executeLaunchScript() {
        if (scriptRunning) {
            updateNotification("进程清理或启动脚本正在执行，请稍候")
            return
        }
        scriptRunning = true
        thread(name = "KillSuoji-Kill-And-Launch") {
            updateNotification("Root 窗口已触发，正在执行应用清理和启动脚本")
            try {
                val prefs = getSharedPreferences("killsuoji_v2", MODE_PRIVATE)
                val whitelist = (prefs.getStringSet("app_whitelist", emptySet()) ?: emptySet()) + packageName
                val whitelistText = whitelist.sorted().joinToString("\n")
                val encoded = Base64.encodeToString(whitelistText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val rootCommand = """
                    echo '$encoded' | base64 -d > /data/local/tmp/killsuoji_whitelist.txt || exit 20
                    pm list packages -3 | cut -d: -f2 | grep -vxFf /data/local/tmp/killsuoji_whitelist.txt | xargs -r -P 16 -n 1 am force-stop >/dev/null 2>&1
                    rm -f /data/local/tmp/killsuoji_whitelist.txt
                    sh /data/local/tmp/launch_killapp.sh
                """.trimIndent()
                val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
                val finished = process.waitFor(90, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    updateNotification("应用清理或 launch_killapp.sh 执行超时")
                } else {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    updateNotification(if (process.exitValue() == 0) "非白名单应用已并行结束，launch_killapp.sh 执行完成"
                        else "进程清理或启动脚本执行失败（${process.exitValue()}）：${output.take(100)}")
                }
            } catch (e: Exception) {
                updateNotification("结束应用或执行启动脚本失败：" + (e.message ?: "未知错误"))
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

    private fun buildNotification(message: String = "点击 Root 窗口或通知中的“立即执行”运行启动脚本"): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val executeIntent = Intent(this, RootVolumeMonitorService::class.java).setAction(ACTION_EXECUTE_NOW)
        val executePending = PendingIntent.getForegroundService(
            this, 2402, executeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val executeAction = Notification.Action.Builder(
            android.R.drawable.ic_media_play, "立即执行", executePending
        ).build()
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KillSuoji · Root 工作状态").setContentText(message)
            .setContentIntent(pending).addAction(executeAction).setOngoing(true).build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        // 系统回收前台服务时不关闭 Root 窗口守护进程；它会负责重新拉起。
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP_WORKING = "com.killsuoji.app.action.STOP_WORKING"
        const val ACTION_EXECUTE_NOW = "com.killsuoji.app.action.EXECUTE_NOW"
        private const val CHANNEL_ID = "killsuoji_root_monitor"
        private const val NOTIFICATION_ID = 2401
    }
}

/** 快捷设置磁贴备用入口。 */
class KillSuojiQuickSettingsTileService : android.service.quicksettings.TileService() {
    override fun onClick() {
        super.onClick()
        val executeIntent = Intent(this, RootVolumeMonitorService::class.java)
            .setAction(RootVolumeMonitorService.ACTION_EXECUTE_NOW)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(executeIntent)
            else startService(executeIntent)
            qsTile?.state = android.service.quicksettings.Tile.STATE_ACTIVE
            qsTile?.updateTile()
        } catch (_: Exception) {
            qsTile?.state = android.service.quicksettings.Tile.STATE_UNAVAILABLE
            qsTile?.updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.label = "KillSuoji 执行"
        qsTile?.contentDescription = "执行 KillSuoji Root 启动脚本"
        qsTile?.state = android.service.quicksettings.Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
