package com.killsuoji.app

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import androidx.compose.material3.OutlinedTextField
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val KsuAccent = Color(0xFF4285F4)
private val StatusRed = Color(0xFFFFE1E1)
private val StatusGreen = Color(0xFFD5F7E1)

private data class InstalledApp(val packageName: String, val label: String, val isSukiSU: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KillSuojiApp() }
        showInterceptToast(intent)
        ensureLaunchScriptExists()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showInterceptToast(intent)
    }


    /** 每次启动时同步写入新版 Root 启动脚本，确保升级后覆盖旧脚本。 */
    private fun ensureLaunchScriptExists() {
        val scriptPath = "/data/local/tmp/launch_killapp.sh"
        val encodedScript = "IyEvc3lzdGVtL2Jpbi9zaAojCiMg5paH5Lu25ZCN77yabGF1bmNoX2tpbGxhcHAuc2gKIyDnlKjpgJTvvJpyb290IOadg+mZkOaJp+ihjO+8jOaJk+W8gOWMheWQjeS4uiBjb20ua2lsbHN1b2ppLmFwcCDnmoTlupTnlKgKIyAgICAgICAtIOi/m+eoi+W3suWtmOWcqCAgLT4g55u05o6l5oqK5bqU55So5YiH5Yiw5YmN5Y+wCiMgICAgICAgLSDov5vnqIvkuI3lrZjlnKggIC0+IOWGt+WQr+WKqOivpeW6lOeUqAojCiMg55So5rOV77yaCiMgICBjaG1vZCA3NTUgbGF1bmNoX2tpbGxhcHAuc2gKIyAgIHN1IC1jIC4vbGF1bmNoX2tpbGxhcHAuc2gKIyDmiJbvvJoKIyAgIGFkYiBzaGVsbCAic3UgLWMgJ3NoIC9kYXRhL2xvY2FsL3RtcC9sYXVuY2hfa2lsbGFwcC5zaCciCiMKClBLRz0iY29tLmtpbGxzdW9qaS5hcHAiCgojIC0tLS0tLS0tLS0gMS4gcm9vdCDmo4Dmn6UgLS0tLS0tLS0tLQppZiBbICIkKGlkIC11KSIgIT0gIjAiIF07IHRoZW4KICAgIGVjaG8gIlshXSDpnIDopoEgcm9vdCDmnYPpmZDvvIzor7fnlKggc3Ug5omn6KGMIgogICAgZXhpdCAxCmZpCgojIC0tLS0tLS0tLS0gMi4g5p+l5om+6L+b56iLIFBJRCAtLS0tLS0tLS0tClBJRD0iIgppZiBjb21tYW5kIC12IHBpZG9mID4vZGV2L251bGwgMj4mMTsgdGhlbgogICAgUElEPSQocGlkb2YgIiRQS0ciIDI+L2Rldi9udWxsKQpmaQppZiBbIC16ICIkUElEIiBdOyB0aGVuCiAgICBQSUQ9JChwcyAtQSAyPi9kZXYvbnVsbCB8IGdyZXAgLXcgIiRQS0ciIHwgZ3JlcCAtdiBncmVwIHwgYXdrICdOUj09MXtwcmludCAkMn0nKQpmaQppZiBbIC16ICIkUElEIiBdOyB0aGVuCiAgICAjIOWFvOWuueayoeaciSAtQSDlj4LmlbDnmoTogIHns7vnu58KICAgIFBJRD0kKHBzIDI+L2Rldi9udWxsIHwgZ3JlcCAtdyAiJFBLRyIgfCBncmVwIC12IGdyZXAgfCBhd2sgJ05SPT0xe3ByaW50ICQyfScpCmZpCgojIC0tLS0tLS0tLS0gMy4g6I635Y+WIExhdW5jaGVyIEFjdGl2aXR5IC0tLS0tLS0tLS0KQUNUSVZJVFk9JChjbWQgcGFja2FnZSByZXNvbHZlLWFjdGl2aXR5IC0tYnJpZWYgLWMgYW5kcm9pZC5pbnRlbnQuY2F0ZWdvcnkuTEFVTkNIRVIgIiRQS0ciIDI+L2Rldi9udWxsIHwgdGFpbCAtbiAxKQpjYXNlICIkQUNUSVZJVFkiIGluCiAgICAiInwqTm8qYWN0aXZpdHkqfCpFeGNlcHRpb24qfCpFcnJvciopIEFDVElWSVRZPSIiIDs7CmVzYWMKaWYgWyAteiAiJEFDVElWSVRZIiBdOyB0aGVuCiAgICAjIOWFnOW6le+8muS7jiBkdW1wc3lzIOmHjOino+aekCBNQUlOIOWFpeWPowogICAgQUNUSVZJVFk9JChkdW1wc3lzIHBhY2thZ2UgIiRQS0ciIDI+L2Rldi9udWxsIHwgYXdrICcKICAgICAgICAvYW5kcm9pZC5pbnRlbnQuYWN0aW9uLk1BSU4vIHsKICAgICAgICAgICAgZ2V0bGluZQogICAgICAgICAgICBmb3IgKGkgPSAxOyBpIDw9IE5GOyBpKyspIGlmICgkaSB+IC9cLy8pIHsgcHJpbnQgJGk7IGV4aXQgfQogICAgICAgIH0nKQpmaQoKZWNobyAiWypdIOWMheWQjSAgICAgOiAkUEtHIgplY2hvICJbKl0g6L+b56iLUElEICA6ICR7UElEOi3mnKrov5DooYx9IgplY2hvICJbKl0gQWN0aXZpdHkgOiAke0FDVElWSVRZOi3oh6rliqjop6PmnpB9IgoKIyAtLS0tLS0tLS0tIDQuIOWQr+WKqCAvIOWIh+WJjeWPsCAtLS0tLS0tLS0tCmlmIFsgLW4gIiRQSUQiIF07IHRoZW4KICAgIGVjaG8gIlsqXSDov5vnqIvlt7LlrZjlnKjvvIzmraPlnKjliIfmjaLliLDliY3lj7AuLi4iCiAgICBpZiBbIC1uICIkQUNUSVZJVFkiIF07IHRoZW4KICAgICAgICBhbSBzdGFydCAtLWFjdGl2aXR5LXJlb3JkZXItdG8tZnJvbnQgLW4gIiRBQ1RJVklUWSIgPi9kZXYvbnVsbCAyPiYxCiAgICBlbHNlCiAgICAgICAgbW9ua2V5IC1wICIkUEtHIiAtYyBhbmRyb2lkLmludGVudC5jYXRlZ29yeS5MQVVOQ0hFUiAxID4vZGV2L251bGwgMj4mMQogICAgZmkKZWxzZQogICAgZWNobyAiWypdIOi/m+eoi+S4jeWtmOWcqO+8jOato+WcqOWQr+WKqOW6lOeUqC4uLiIKICAgIGlmIFsgLW4gIiRBQ1RJVklUWSIgXTsgdGhlbgogICAgICAgIGFtIHN0YXJ0IC1uICIkQUNUSVZJVFkiID4vZGV2L251bGwgMj4mMQogICAgZWxzZQogICAgICAgIG1vbmtleSAtcCAiJFBLRyIgLWMgYW5kcm9pZC5pbnRlbnQuY2F0ZWdvcnkuTEFVTkNIRVIgMSA+L2Rldi9udWxsIDI+JjEKICAgIGZpCmZpCgojIC0tLS0tLS0tLS0gNS4g57uT5p6c5qCh6aqMIC0tLS0tLS0tLS0Kc2xlZXAgMQpORVdQSUQ9IiIKaWYgY29tbWFuZCAtdiBwaWRvZiA+L2Rldi9udWxsIDI+JjE7IHRoZW4KICAgIE5FV1BJRD0kKHBpZG9mICIkUEtHIiAyPi9kZXYvbnVsbCkKZmkKaWYgWyAteiAiJE5FV1BJRCIgXTsgdGhlbgogICAgTkVXUElEPSQocHMgLUEgMj4vZGV2L251bGwgfCBncmVwIC13ICIkUEtHIiB8IGdyZXAgLXYgZ3JlcCB8IGF3ayAnTlI9PTF7cHJpbnQgJDJ9JykKZmkKCmlmIFsgLW4gIiRORVdQSUQiIF07IHRoZW4KICAgIGVjaG8gIlsrXSDmiJDlip/vvJokUEtHIOato+WcqOi/kOihjCAoUElEOiAkTkVXUElEKSIKICAgIGV4aXQgMAplbHNlCiAgICBlY2hvICJbLV0g5aSx6LSl77ya5pyq6IO95ZCv5YqoICRQS0fvvIzor7fmo4Dmn6XljIXlkI3mmK/lkKbmraPnoa7miJYgYW0g5ZG95Luk5p2D6ZmQIgogICAgZXhpdCAyCmZpCg=="
        val command = "echo '$encodedScript' | base64 -d > '$scriptPath' && chmod 755 '$scriptPath'"
        thread(name = "KillSuoji-Update-Launch-Script") {
            try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
            } catch (_: Exception) { }
        }
    }

    private fun showInterceptToast(intent: Intent?) {
        if (intent?.getBooleanExtra("killsuoji_trigger", false) == true) {
            Toast.makeText(this, "已启动 KillSuoji！", Toast.LENGTH_SHORT).show()
            intent.removeExtra("killsuoji_trigger")
        }
    }
}

/** 通过 Root 检查 SukiSU 模块是否已安装并启用。 */
private fun detectOverlayModule(): Boolean {
    return try {
        val command = "test -f /data/adb/modules/killsuoji_overlay/module.prop && grep -q '^id=killsuoji_overlay$' /data/adb/modules/killsuoji_overlay/module.prop && test ! -f /data/adb/modules/killsuoji_overlay/disable"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else process.exitValue() == 0
    } catch (_: Exception) { false }
}

/** 检查 su 命令是否确实能以 uid=0 执行，而不只检查文件是否存在。 */
private fun detectRootPermission(): Boolean {
    return try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { it.readText() }.contains("uid=0")
        }
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun KillSuojiApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("killsuoji_v2", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    var isWorking by remember { mutableStateOf(prefs.getBoolean("is_working", false)) }
    // 先读取上次已确认的状态，避免进入首页时等待 Root 检测才显示状态。
    var rootDetected by remember { mutableStateOf(prefs.getBoolean("root_detected_cache", prefs.getBoolean("is_working", false))) }
    var moduleInstalled by remember { mutableStateOf(prefs.getBoolean("module_installed_cache", prefs.getBoolean("is_working", false))) }
    var moduleCheckComplete by remember { mutableStateOf(false) }
    val installedApps = remember { mutableStateListOf<InstalledApp>() }
    var whitelist by remember { mutableStateOf(prefs.getStringSet("app_whitelist", null)?.toSet() ?: emptySet()) }

    LaunchedEffect(Unit) {
        // 三项启动检查并行执行：应用列表不再阻塞 Root 和模块状态识别。
        coroutineScope {
            val appsTask = async(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { info ->
                    if ((info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return@mapNotNull null
                    val pkg = info.packageName
                    val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { pkg }
                    val suki = label.contains("SukiSU", ignoreCase = true) ||
                        pkg.contains("sukisu", ignoreCase = true) || pkg == "me.weishu.kernelsu"
                    InstalledApp(pkg, label, suki)
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
            val rootTask = async(Dispatchers.IO) { detectRootPermission() }
            val moduleTask = async(Dispatchers.IO) { detectOverlayModule() }

            // 优先完成 Root/模块识别并立即刷新首页，应用列表加载放在后面。
            rootDetected = rootTask.await()
            prefs.edit().putBoolean("root_detected_cache", rootDetected).apply()

            moduleInstalled = moduleTask.await()
            prefs.edit().putBoolean("module_installed_cache", moduleInstalled).apply()
            moduleCheckComplete = true

            val discovered = appsTask.await()
            installedApps.clear()
            installedApps.addAll(discovered)
            if (!prefs.contains("app_whitelist")) {
                whitelist = (setOf(context.packageName) + discovered.filter { it.isSukiSU }.map { it.packageName }).toSet()
                prefs.edit().putStringSet("app_whitelist", whitelist.toMutableSet()).apply()
            } else {
                whitelist = (prefs.getStringSet("app_whitelist", emptySet())?.toSet() ?: emptySet()) + context.packageName
            }

            if (!moduleInstalled && prefs.getBoolean("is_working", false)) {
                prefs.edit().putBoolean("is_working", false).apply()
                isWorking = false
                context.stopService(Intent(context, RootVolumeMonitorService::class.java))
            } else if (moduleInstalled && prefs.getBoolean("is_working", false)) {
                try { context.startForegroundService(Intent(context, RootVolumeMonitorService::class.java)) }
                catch (_: Exception) { }
            }
            if (!rootDetected) tab = 0
        }
    }

    MiuixTheme {
        Scaffold(
            topBar = { TopAppBar(title = "KillSuoji") },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavItem("首页", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    if (rootDetected) {
                        NavItem("启动", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    }
                    NavItem("设置", tab == 2, Modifier.weight(1f)) { tab = 2 }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(Modifier.padding(padding), rootDetected, isWorking)
                1 -> if (rootDetected) LaunchScreen(
                    Modifier.padding(padding), isWorking, moduleInstalled, moduleCheckComplete,
                    installedApps = installedApps.toList(), whitelist = whitelist,
                    onWhitelistChange = { pkg, checked ->
                        whitelist = if (checked) whitelist + pkg else whitelist - pkg
                        whitelist = whitelist + context.packageName
                        prefs.edit().putStringSet("app_whitelist", whitelist.toMutableSet()).apply()
                    }
                ) {
                    val next = !isWorking
                    if (next) {
                        if (!moduleInstalled) {
                            Toast.makeText(context, "未检测到已安装并启用的 SukiSU 模块，请先安装模块后再启动", Toast.LENGTH_LONG).show()
                            return@LaunchScreen
                        }
                        try {
                            context.startForegroundService(Intent(context, RootVolumeMonitorService::class.java))
                            isWorking = true
                            prefs.edit().putBoolean("is_working", true).apply()
                            Toast.makeText(context, "Root 窗口已启动，点击 killzuoji 执行启动脚本", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "工作状态启动失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        try {
                            context.startForegroundService(
                                Intent(context, RootVolumeMonitorService::class.java)
                                    .setAction(RootVolumeMonitorService.ACTION_STOP_WORKING)
                            )
                        } catch (_: Exception) {
                            context.stopService(Intent(context, RootVolumeMonitorService::class.java))
                        }
                        isWorking = false
                        prefs.edit().putBoolean("is_working", false).apply()
                    }
                } else HomeScreen(Modifier.padding(padding), false, false)
                else -> SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) KsuAccent else Color(0xFF777777),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier, rootDetected: Boolean, isWorking: Boolean) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                val statusBackground = when {
                    !rootDetected -> Color.Transparent
                    isWorking -> StatusGreen
                    else -> StatusRed
                }
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (rootDetected) Modifier.background(statusBackground, RoundedCornerShape(18.dp)) else Modifier)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when {
                            !rootDetected -> "无法启动！请获取 Root 的权限！"
                            isWorking -> "已经检测到了 Root"
                            else -> "已经检测到了 Root"
                        },
                        color = if (!rootDetected) Color(0xFF777777) else Color(0xFFB42318),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(if (isWorking) "KillSuoji · 工作中" else "KillSuoji", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (BuildConfig.VERSION_NAME.endsWith("-beta", ignoreCase = true))
                                "V${BuildConfig.VERSION_NAME.removeSuffix("-beta")} beta"
                            else "V${BuildConfig.VERSION_NAME} 正式版",
                            fontSize = 13.sp, color = Color(0xFF68736D)
                        )
                    }
                    
                }
            }
        }
        item { UpdateCard() }
    }
}


private fun fetchLatestUpdate(installedVersionName: String): Pair<String, String>? {
    val connection = URL("https://api.github.com/repos/3525808867a-dot/3525808867a-dot.github.io/releases?per_page=100")
        .openConnection()
    connection.connectTimeout = 10000
    connection.readTimeout = 10000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "KillSuoji-Android")
    return try {
        val releases = org.json.JSONArray(connection.getInputStream().bufferedReader().use { it.readText() })
        fun parseVersion(raw: String): List<Int>? {
            val normalized = raw.removePrefix("v").substringBefore("-").substringBefore(" ")
            val parts = normalized.split(".")
            if (parts.size != 3) return null
            return parts.map { it.toIntOrNull() ?: return null }
        }

        val current = parseVersion(installedVersionName) ?: return null

        fun isNewer(candidate: List<Int>, baseline: List<Int>): Boolean {
            for (i in 0..2) {
                if (candidate[i] != baseline[i]) return candidate[i] > baseline[i]
            }
            return false
        }

        var bestVersion: List<Int>? = null
        var bestResult: Pair<String, String>? = null

        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", false)) continue
            val version = release.optString("tag_name").removePrefix("v")
            val numbers = parseVersion(version) ?: continue
            if (!isNewer(numbers, current)) continue

            val assets = release.optJSONArray("assets") ?: continue
            val apkName = numbers.joinToString(".") + ".apk"
            var apkUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name") == apkName && asset.optLong("size", 0L) > 0L) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) continue
            if (bestVersion == null || isNewer(numbers, bestVersion!!)) {
                bestVersion = numbers
                bestResult = Pair(numbers.joinToString("."), apkUrl!!)
            }
        }
        bestResult
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun UpdateCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var update by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf("正在检查新版本…") }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.3.4"
            update = withContext(Dispatchers.IO) { fetchLatestUpdate(installedVersion) }
            message = if (update == null) "当前已是最新版本" else "发现新版本 V" + update!!.first + "，点击即可下载安装"
        } catch (_: Exception) {
            message = "暂时无法检查更新，请检查网络"
        }
    }

    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            val status = if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
            cursor.close()
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                downloading = false
                message = "安装包已下载完成，点击按钮安装"
                break
            } else if (status == DownloadManager.STATUS_FAILED) {
                downloading = false
                downloadId = null
                message = "下载失败，请稍后重试"
                break
            }
            delay(700)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本更新", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(message, color = Color(0xFF68736D), fontSize = 14.sp)
            if (update != null || downloadId != null) {
                Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !downloading) {
                    val id = downloadId
                    if (id != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
                            Toast.makeText(context, "请允许安装此应用的未知来源应用，然后返回点击安装更新", Toast.LENGTH_LONG).show()
                        } else {
                            val uri = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id)
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    } else {
                        val info = update ?: return@clickable
                        val request = DownloadManager.Request(Uri.parse(info.second))
                            .setTitle("KillSuoji V" + info.first)
                            .setDescription("正在下载更新安装包")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "KillSuoji-" + info.first + ".apk")
                        downloadId = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        downloading = true
                        message = "正在下载 V" + info.first + "…"
                    }
                }) {
                    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        Text(if (downloading) "正在下载…" else if (downloadId != null) "安装更新" else "立即更新",
                            color = KsuAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchScreen(
    modifier: Modifier = Modifier, isWorking: Boolean, moduleInstalled: Boolean, moduleCheckComplete: Boolean,
    installedApps: List<InstalledApp>, whitelist: Set<String>, onWhitelistChange: (String, Boolean) -> Unit,
    onToggle: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var appSearch by remember { mutableStateOf("") }
    var rootLog by remember { mutableStateOf("正在读取 Root 启动日志…") }
    var logRefreshKey by remember { mutableIntStateOf(0) }
    var copyMessage by remember { mutableStateOf("复制日志") }

    suspend fun readLatestRootLog(): String = withContext(Dispatchers.IO) {
        try {
            val command = "LOG=/data/adb/killsuoji_overlay/root-window.log; " +
                "if [ -f \"\$LOG\" ]; then tail -n 160 \"\$LOG\"; else " +
                "echo '尚无日志文件：等待执行启动'; fi"
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "读取日志超时，请检查 Root 授权。"
            } else if (process.exitValue() != 0) {
                "读取日志失败（退出码：${process.exitValue()}）\n$output"
            } else output.ifBlank { "日志文件为空，尚未记录启动信息。" }
        } catch (e: Exception) {
            "读取日志异常：${e.message ?: "未知错误"}"
        }
    }

    LaunchedEffect(isWorking, logRefreshKey) {
        delay(if (isWorking) 1200 else 3800)
        rootLog = readLatestRootLog()
        while (isWorking) {
            delay(1500)
            rootLog = readLatestRootLog()
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("控制台", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                val status = when {
                    !moduleCheckComplete -> "正在检查 SukiSU 常驻模块安装状态…"
                    !moduleInstalled -> "未检测到已安装并启用的 SukiSU 常驻模块。请先安装模块。"
                    isWorking -> "工作状态运行中。"
                    else -> "模块已安装并启用，可以执行"
                }
                Text("$status（白名单 ${whitelist.size} 个）", color = Color(0xFF68736D), fontSize = 14.sp)
            }
        }
        Card(modifier = Modifier.fillMaxWidth().clickable(enabled = moduleCheckComplete && moduleInstalled, onClick = onToggle)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 17.dp), contentAlignment = Alignment.Center) {
                Text(when {
                    !moduleCheckComplete -> "正在检查模块…"
                    !moduleInstalled -> "请先安装模块"
                    isWorking -> "停止"
                    else -> "执行"
                }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isWorking) Color(0xFFB42318) else if (moduleInstalled) KsuAccent else Color(0xFF999999))
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启动日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("刷新", color = KsuAccent, modifier = Modifier.clickable {
                            logRefreshKey++
                            copyMessage = "复制日志"
                        })
                        Text(copyMessage, color = KsuAccent, modifier = Modifier.clickable {
                            scope.launch {
                                val latest = readLatestRootLog()
                                rootLog = latest
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("KillSuoji 启动日志", latest))
                                copyMessage = "已复制"
                            }
                        })
                    }
                }
                // 收紧日志预览高度，把更多屏幕空间留给可滚动的应用白名单。
                Text(rootLog, fontSize = 9.sp, color = Color(0xFF68736D), lineHeight = 12.sp, maxLines = 4)
                Text("执行和停止后自动更新；复制时会重新读取最新日志。", fontSize = 9.sp, color = Color(0xFF999999), maxLines = 1)
            }
        }
        Text("添加应用白名单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("勾选的应用不会被结束。", fontSize = 12.sp, color = Color(0xFF777777))
        OutlinedTextField(
            value = appSearch,
            onValueChange = { appSearch = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { androidx.compose.material3.Text("输入应用名称或包名搜索") }
        )
        // 过滤和排序只在数据变化时执行，避免每次滚动重组都全量排序。
        val orderedApps = remember(installedApps, whitelist, appSearch) {
            val keyword = appSearch.trim()
            installedApps.asSequence()
                .filter { keyword.isEmpty() || it.label.contains(keyword, ignoreCase = true) || it.packageName.contains(keyword, ignoreCase = true) }
                .sortedWith(compareByDescending<InstalledApp> { it.packageName in whitelist }.thenBy { it.label.lowercase() })
                .toList()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(items = orderedApps, key = { it.packageName }, contentType = { "installed-app" }) { app ->
                val checked = app.packageName in whitelist
                Card(modifier = Modifier.fillMaxWidth().clickable {
                    if (app.packageName != "com.killsuoji.app") onWhitelistChange(app.packageName, !checked)
                }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
                            Text(app.packageName, fontSize = 10.sp, color = Color(0xFF888888))
                        }
                        Box(Modifier.size(24.dp).background(if (checked) KsuAccent else Color.Transparent, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center) {
                            if (checked) Text("✓", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repairLines = remember { mutableStateListOf<String>() }
    var repairing by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("工具", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !repairing) {
                scope.launch {
                    repairing = true
                    repairLines.clear()
                    repairLines.add("正在读取 Root 启动日志……")
                    try {
                        runOneClickRepair { line -> repairLines.add(line) }
                    } catch (e: Exception) {
                        repairLines.add("修复过程异常：${e.message ?: "未知错误"}")
                    } finally {
                        repairing = false
                    }
                }
            }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("一键修复", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (repairing) "正在检测…" else "开始", color = KsuAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (repairing || repairLines.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(14.dp))
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一键修复进度", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (repairing) androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp
                    )
                    repairLines.takeLast(18).forEach { line ->
                        Text(line, color = Color(0xFFE4E4E4), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        item {
            Text("关于", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            InfoCard("应用名称", "KillSuoji")
            InfoCard("当前版本", "V${BuildConfig.VERSION_NAME} 正式版")
        }
    }
}

/** 日志驱动的有限修复：只修复 KillSuoji 自己的模块守护标记和守护进程，不触碰系统服务。 */
private suspend fun runOneClickRepair(onProgress: (String) -> Unit) {
    onProgress("检查 Root 授权……")
    val rootCheck = withContext(Dispatchers.IO) { runRepairRootCommand("id") }
    if (rootCheck.first != 0 || !rootCheck.second.contains("uid=0")) {
        onProgress("无法修复：当前没有可用的 Root 授权。")
        return
    }
    onProgress("Root 授权正常。")

    onProgress("读取最近的 200 行 Root 悬浮窗日志……")
    val logResult = withContext(Dispatchers.IO) {
        runRepairRootCommand("LOG=/data/adb/killsuoji_overlay/root-window.log; if [ -f \"\$LOG\" ]; then tail -n 200 \"\$LOG\"; else echo LOG_NOT_FOUND; fi")
    }
    val logText = logResult.second
    if (logResult.first != 0) {
        onProgress("日志读取失败：${logText.take(180)}")
    } else {
        when {
            logText.contains("Native overlay startup failed") -> onProgress("日志发现悬浮窗启动异常，准备检查并重启 KillSuoji 守护进程。")
            logText.contains("monitor active") -> onProgress("日志包含 monitor active，最近一次记录显示触摸监听已启动。")
            logText.contains("SurfaceControl transaction applied") -> onProgress("日志包含 SurfaceControl transaction applied，最近一次窗口事务已提交。")
            else -> onProgress("日志未发现明确的悬浮窗启动错误，继续检查模块与守护进程。")
        }
    }

    onProgress("检查 killsuoji必要模块和 watchdog.sh……")
    val moduleCheck = withContext(Dispatchers.IO) {
        runRepairRootCommand("test -f /data/adb/modules/killsuoji_overlay/module.prop && test -f /data/adb/modules/killsuoji_overlay/watchdog.sh && test ! -f /data/adb/modules/killsuoji_overlay/disable")
    }
    if (moduleCheck.first != 0) {
        onProgress("无法自动修复：未检测到已启用且包含 watchdog.sh 的 killsuoji必要模块，请先在 Root 管理器中安装并启用模块。")
        return
    }
    onProgress("必要模块存在且已启用。")

    onProgress("修复 KillSuoji 守护状态并启动 watchdog……")
    val repair = withContext(Dispatchers.IO) {
        runRepairRootCommand("""
            MOD=/data/adb/modules/killsuoji_overlay
            STATE=/data/adb/killsuoji_overlay
            mkdir -p "${'$'}STATE" || exit 10
            touch "${'$'}STATE/enabled"
            chmod 700 "${'$'}MOD/watchdog.sh"
            for PIDFILE in "${'$'}STATE/root-window.pid" "${'$'}STATE/watchdog.pid"; do
              if [ -f "${'$'}PIDFILE" ]; then
                PID=$(cat "${'$'}PIDFILE" 2>/dev/null)
                [ -n "${'$'}PID" ] && kill "${'$'}PID" 2>/dev/null
                rm -f "${'$'}PIDFILE"
              fi
            done
            nohup sh "${'$'}MOD/watchdog.sh" >/dev/null 2>&1 </dev/null &
            echo WATCHDOG_RESTART_REQUESTED
        """.trimIndent())
    }
    if (repair.first != 0) {
        onProgress("自动修复失败（退出码 ${repair.first}）：${repair.second.take(180)}")
        return
    }
    onProgress(repair.second.ifBlank { "守护进程启动命令已执行。" })

    delay(1800)
    val verify = withContext(Dispatchers.IO) {
        runRepairRootCommand("test -f /data/adb/killsuoji_overlay/enabled && echo ENABLED; test -f /data/adb/killsuoji_overlay/root-window.pid && echo ROOT_WINDOW_PID_PRESENT; tail -n 8 /data/adb/killsuoji_overlay/root-window.log 2>/dev/null")
    }
    if (verify.first == 0) {
        onProgress("修复后检查结果：")
        verify.second.lineSequence().filter { it.isNotBlank() }.takeLast(10).forEach(onProgress)
        if (verify.second.contains("ROOT_WINDOW_PID_PRESENT")) onProgress("已检测到 Root 悬浮窗进程 PID 文件；请观察悬浮窗是否恢复。")
        else onProgress("守护状态已修复，但暂未检测到 Root 悬浮窗 PID 文件；日志已保留，不能判定完全修复。")
    } else {
        onProgress("修复后验证失败：${verify.second.take(180)}")
    }
}

private fun runRepairRootCommand(command: String): Pair<Int, String> {
    return try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Pair(124, "Root 命令执行超时")
        } else Pair(process.exitValue(), output)
    } catch (e: Exception) {
        Pair(1, e.message ?: "Root 命令执行异常")
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color(0xFF777777))
            Text(value, fontWeight = FontWeight.Medium)
        }
    }
}
