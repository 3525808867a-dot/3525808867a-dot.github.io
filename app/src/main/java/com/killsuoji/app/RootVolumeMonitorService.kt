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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 工作服务负责通知和 Root 操作；窗口由模块启动的独立 UID 0 进程创建。 */
class RootVolumeMonitorService : Service() {
    @Volatile private var scriptRunning = false
    @Volatile private var safeModeActive = false
    @Volatile private var safeModeStartedAt = 0L
    @Volatile private var safeModeThread: Thread? = null
    @Volatile private var safeModeGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (intent?.action == ACTION_ENTER_SAFE_MODE) {
            startSafetyMode()
            return START_STICKY
        }
        if (intent?.action == ACTION_CANCEL_SAFE_MODE) {
            stopSafetyMode()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_WORKING) {
            stopSafetyMode()
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
        safeModeActive = false
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            val command = """
                STATE_DIR=/data/adb/killsuoji_overlay
                echo "[KILLSUOJI] UI requested STOP" >> /data/adb/killsuoji_overlay/root-window.log
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
            ProcessBuilder("su", "-c", "echo '[KILLSUOJI] STOP completed' >> /data/adb/killsuoji_overlay/root-window.log").start().waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    private fun executeLaunchScript() {
        if (scriptRunning) {
            updateNotification("进程清理或启动脚本正在执行，请稍候")
            return
        }
        scriptRunning = true
        thread(name = "KillSuoji-Kill-And-Launch") {
            updateNotification("Root 窗口已触发，正在清理第三方进程")
            try {
                val killedCount = cleanupThirdPartyProcesses()
                if (killedCount < 0) {
                    updateNotification("Root 清理失败，已停止后续启动脚本；请检查 Root 授权和日志")
                    mainHandler.post {
                        Toast.makeText(this, "Root 清理失败，未执行启动脚本", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                val launch = ProcessBuilder("su", "-c", "sh /data/local/tmp/launch_killapp.sh")
                    .redirectErrorStream(true).start()
                val finished = launch.waitFor(90, TimeUnit.SECONDS)
                if (!finished) {
                    launch.destroyForcibly()
                    updateNotification("启动脚本执行超时")
                } else {
                    val output = launch.inputStream.bufferedReader().use { it.readText() }
                    updateNotification(if (launch.exitValue() == 0) "Root 已停止 ${killedCount} 个非白名单第三方应用，启动脚本执行完成"
                        else "启动脚本执行失败（${launch.exitValue()}）：${output.take(100)}")
                }
                mainHandler.post {
                    Toast.makeText(this, "Root 已强制停止 ${killedCount} 个非白名单第三方应用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                updateNotification("结束第三方进程或执行启动脚本失败：" + (e.message ?: "未知错误"))
            } finally {
                scriptRunning = false
            }
        }
    }

    /** 只枚举 pm list packages -3；系统包、系统 UID 和无法映射的进程不会进入结束列表。 */
    private fun cleanupThirdPartyProcesses(): Int {
        return try {
            val prefs = getSharedPreferences("killsuoji_v2", MODE_PRIVATE)
            val whitelist = (prefs.getStringSet("app_whitelist", emptySet()) ?: emptySet()) + packageName
            val whitelistText = whitelist.sorted().joinToString("\n")
            val encoded = Base64.encodeToString(whitelistText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val rootCommand = """
                WLFILE=/data/local/tmp/killsuoji_safe_whitelist.txt
                FAILFILE=/data/local/tmp/killsuoji_cleanup_failed.§$
                mkdir -p /data/adb/killsuoji_overlay || exit 19
                echo '$encoded' | base64 -d > "§{WLFILE}" || exit 20
                : > "§{FAILFILE}" || exit 22
                WL=" $(tr '\n' ' ' < "§{WLFILE}") "
                UID_NOW=$(id -u 2>/dev/null)
                if [ "§{UID_NOW}" != "0" ]; then
                  echo "ROOT_REQUIRED:uid=§{UID_NOW}" >> /data/adb/killsuoji_overlay/root-window.log
                  exit 21
                fi
                echo "[$(date +%s)] ROOT_CLEANUP_BEGIN uid=§{UID_NOW}" >> /data/adb/killsuoji_overlay/root-window.log
                COUNT=$(
                  pm list packages -3 2>/dev/null | cut -d: -f2 | while IFS= read -r §{pkg}; do
                    [ -n "§{pkg}" ] || continue
                    case "§{WL}" in *" §{pkg} "*) echo "WHITELIST_SKIP:§{pkg}" >> /data/adb/killsuoji_overlay/root-window.log; continue ;; esac
                    if am force-stop --user all "§{pkg}" >/dev/null 2>&1; then
                      echo "ROOT_STOPPED_PACKAGE:§{pkg}" >> /data/adb/killsuoji_overlay/root-window.log
                      echo 1
                    else
                      echo "ROOT_STOP_FAILED:§{pkg}" >> /data/adb/killsuoji_overlay/root-window.log
                      echo "§{pkg}" >> "§{FAILFILE}"
                      echo 0
                    fi
                  done | awk '{total+=$1} END {print total+0}'
                )
                FAILS=$(wc -l < "§{FAILFILE}" | tr -d ' ')
                echo "[$(date +%s)] ROOT_CLEANUP_END stopped_package_count=§{COUNT} failed_package_count=§{FAILS}" >> /data/adb/killsuoji_overlay/root-window.log
                if [ "§{FAILS}" -gt 0 ]; then echo -1; else echo "§{COUNT}"; fi
                rm -f "§{WLFILE}" "§{FAILFILE}"
            """.trimIndent().replace("§{", "\${'$'}{")
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}WLFILE") "
                UID_NOW=$(id -u 2>/dev/null)
                if [ "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}UID_NOW" != "0" ]; then
                  echo "ROOT_REQUIRED:uid=${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}UID_NOW" >> /data/adb/killsuoji_overlay/root-window.log
                  exit 21
                fi
                echo "[$(date +%s)] ROOT_CLEANUP_BEGIN uid=${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}UID_NOW" >> /data/adb/killsuoji_overlay/root-window.log
                COUNT=$(
                  pm list packages -3 2>/dev/null | cut -d: -f2 | while IFS= read -r ${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg; do
                    [ -n "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg" ] || continue
                    case "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}WL" in *" ${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg "*) echo "WHITELIST_SKIP:${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg" >> /data/adb/killsuoji_overlay/root-window.log; continue ;; esac
                    # am force-stop terminates all processes belonging to this third-party package.
                    if am force-stop --user all "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg" >/dev/null 2>&1; then
                      echo "ROOT_STOPPED_PACKAGE:${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg" >> /data/adb/killsuoji_overlay/root-window.log
                      echo 1
                    else
                      echo "ROOT_STOP_FAILED:${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}pkg" >> /data/adb/killsuoji_overlay/root-window.log
                      echo 0
                    fi
                  done | awk '{total+=${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}1} END {print total+0}'
                )
                echo "[$(date +%s)] ROOT_CLEANUP_END stopped_package_count=${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}COUNT" >> /data/adb/killsuoji_overlay/root-window.log
                echo "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}COUNT"
                rm -f "${'
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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
}}WLFILE"
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", rootCommand).redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                output.lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startSafetyMode() {
        if (safeModeActive) return
        safeModeActive = true
        val generation = ++safeModeGeneration
        safeModeStartedAt = SystemClock.elapsedRealtime()
        try {
            ProcessBuilder("su", "-c", "mkdir -p /data/adb/killsuoji_overlay && touch /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        updateNotification("安全模式已启动：仅持续清理非白名单第三方进程")
        safeModeThread = thread(name = "KillSuoji-Safety-Cleanup") {
            try {
                // Shizuku 是第三方服务；按用户要求在进入安全模式时强制停止其服务与服务端进程。
                try {
                    val stopShizuku = ProcessBuilder("su", "-c",
                        "am force-stop moe.shizuku.privileged.api >/dev/null 2>&1; pkill -9 -f '[s]hizuku_server' >/dev/null 2>&1; echo SHIZUKU_STOP_ATTEMPTED")
                        .redirectErrorStream(true).start()
                    stopShizuku.waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) { }
                while (safeModeActive && generation == safeModeGeneration && !Thread.currentThread().isInterrupted) {
                    val count = cleanupThirdPartyProcesses()
                    updateNotification(if (count >= 0) "安全模式运行中：本轮结束 $count 个非白名单第三方进程"
                        else "安全模式运行中：本轮清理失败，请检查 Root 授权和日志")
                    Thread.sleep(1800L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (generation == safeModeGeneration) safeModeThread = null
            }
        }
    }

    private fun stopSafetyMode() {
        val wasActive = safeModeActive
        safeModeActive = false
        safeModeGeneration++
        safeModeThread?.interrupt()
        safeModeThread = null
        try {
            ProcessBuilder("su", "-c", "rm -f /data/adb/killsuoji_overlay/safety_mode")
                .start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        if (!wasActive) return
        val seconds = ((SystemClock.elapsedRealtime() - safeModeStartedAt) / 1000L).coerceAtLeast(0L)
        updateNotification("安全模式已取消，持续 ${seconds} 秒")
        mainHandler.post {
            Toast.makeText(this, "安全模式已运行 ${seconds} 秒", Toast.LENGTH_LONG).show()
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
        const val ACTION_ENTER_SAFE_MODE = "com.killsuoji.app.action.ENTER_SAFE_MODE"
        const val ACTION_CANCEL_SAFE_MODE = "com.killsuoji.app.action.CANCEL_SAFE_MODE"
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