package com.killsuoji.app

import android.app.DownloadManager
import android.content.Context
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
import org.json.JSONObject
import java.net.URL
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KillSuojiApp() }
        showInterceptToast(intent)
        ensureLaunchScriptExists()
        if (getSharedPreferences("killsuoji_v2", MODE_PRIVATE).getBoolean("is_working", false)) {
            try {
                startForegroundService(Intent(this, RootVolumeMonitorService::class.java))
            } catch (_: Exception) { }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showInterceptToast(intent)
    }


    /** 首次启动时以 Root 创建用户指定的启动脚本；已有文件时绝不覆盖。 */
    private fun ensureLaunchScriptExists() {
        val scriptPath = "/data/local/tmp/launch_killapp.sh"
        val command = "if [ -e '$scriptPath' ]; then echo EXISTS; else " +
            "echo 'IyEvc3lzdGVtL2Jpbi9zaAojIOaWh+S7tuWQjTogL2RhdGEvbG9jYWwvdG1wL2xhdW5jaF9raWxsYXBwLnNoCiMg5L2c55SoOiBSb290IOadg+mZkOaJp+ihjOWQjueri+WNs+aKiiBjb20ua2lsbHN1b2ppLmFwcCDmi4nliLDliY3lj7DvvIjkuI3nrqHlvZPliY3lnKjlgZrku4DkuYjvvIkKIyDnlKjms5U6IHN1IC1jICJzaCAvZGF0YS9sb2NhbC90bXAvbGF1bmNoX2tpbGxhcHAuc2giCgpQS0c9ImNvbS5raWxsc3VvamkuYXBwIgoKIyAtLS0tLS0tLS0tIDEuIOiHquaPkOadgyAtLS0tLS0tLS0tCmlmIFsgIiQoaWQgLXUpIiAhPSAiMCIgXTsgdGhlbgogICAgZXhlYyBzdSAtYyAic2ggJDAiCmZpCgojIC0tLS0tLS0tLS0gMi4g54K55Lqu5bGP5bmVICsg6Kej6Zmk6ZSB5bGPIC0tLS0tLS0tLS0KaW5wdXQga2V5ZXZlbnQgS0VZQ09ERV9XQUtFVVAgID4vZGV2L251bGwgMj4mMQppbnB1dCBrZXlldmVudCA4MiAgICAgICAgICAgICAgPi9kZXYvbnVsbCAyPiYxICAgIyDoj5zljZXplK7vvIzpg6jliIbmnLrlnovnlKjkuo7op6PplIEKd20gZGlzbWlzcy1rZXlndWFyZCAgICAgICAgICAgID4vZGV2L251bGwgMj4mMSAgICMgQW5kcm9pZCA2KyDnm7TmjqXlubLmjokga2V5Z3VhcmQKCiMgLS0tLS0tLS0tLSAzLiDojrflj5blkK/liqggQWN0aXZpdHkgLS0tLS0tLS0tLQpBQ1Q9JChjbWQgcGFja2FnZSByZXNvbHZlLWFjdGl2aXR5IC0tYnJpZWYgXAogICAgICAgIC1hIGFuZHJvaWQuaW50ZW50LmFjdGlvbi5NQUlOIFwKICAgICAgICAtYyBhbmRyb2lkLmludGVudC5jYXRlZ29yeS5MQVVOQ0hFUiBcCiAgICAgICAgIiRQS0ciIDI+L2Rldi9udWxsIHwgdGFpbCAtbiAxKQoKIyDlhZzlupXvvJrku44gZHVtcHN5cyDkuK3op6PmnpAKaWYgWyAteiAiJEFDVCIgXSB8fCBlY2hvICIkQUNUIiB8IGdyZXAgLXEgIk5vIGFjdGl2aXR5IjsgdGhlbgogICAgQUNUPSQoZHVtcHN5cyBwYWNrYWdlICIkUEtHIiAyPi9kZXYvbnVsbCBcCiAgICAgICAgICB8IGdyZXAgLW0xIC1vRSAiJFBLRy9bQS1aYS16MC05Xy5cXCRdKyIgKQpmaQoKIyAtLS0tLS0tLS0tIDQuIOWQr+WKqOW6lOeUqCAtLS0tLS0tLS0tCmlmIFsgLW4gIiRBQ1QiIF07IHRoZW4KICAgICMgLVM6IOWFiCBmb3JjZS1zdG9wIOWGjeWQr+WKqO+8jOS/neivgeaYr+WFqOaWsOWJjeWPsAogICAgIyAtZiAweDEwMDAwMDAwOiBGTEFHX0FDVElWSVRZX05FV19UQVNL77yM5LuOIHNoZWxsIOWQr+WKqOW/hemhu+WKoAogICAgYW0gc3RhcnQgLVMgLW4gIiRBQ1QiIFwKICAgICAgICAtYSBhbmRyb2lkLmludGVudC5hY3Rpb24uTUFJTiBcCiAgICAgICAgLWMgYW5kcm9pZC5pbnRlbnQuY2F0ZWdvcnkuTEFVTkNIRVIgXAogICAgICAgIC1mIDB4MTAwMDAwMDAgLS11c2VyIDAKZWxzZQogICAgIyDkuI3nn6XpgZMgQWN0aXZpdHkg5ZCN5pe255SoIG1vbmtleSDlhZzlupUKICAgIG1vbmtleSAtcCAiJFBLRyIgLWMgYW5kcm9pZC5pbnRlbnQuY2F0ZWdvcnkuTEFVTkNIRVIgMQpmaQoKZXhpdCAwCg==' | base64 -d > '$scriptPath' && chmod 755 '$scriptPath' && echo CREATED; fi"
        thread(name = "KillSuoji-Create-Launch-Script") {
            try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@thread
                }
                if (process.exitValue() == 0 && output.contains("CREATED")) {
                    runOnUiThread {
                        Toast.makeText(this, "已创建 Root 启动脚本", Toast.LENGTH_SHORT).show()
                    }
                }
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
    var rootDetected by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(prefs.getBoolean("is_working", false)) }

    LaunchedEffect(Unit) {
        rootDetected = withContext(Dispatchers.IO) { detectRootPermission() }
        if (!rootDetected) tab = 0
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
                1 -> if (rootDetected) LaunchScreen(Modifier.padding(padding), isWorking) {
                    val next = !isWorking
                    if (next) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "请先允许 KillSuoji 显示悬浮窗，然后返回点击执行", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
                            return@LaunchScreen
                        }
                        try {
                            context.startForegroundService(Intent(context, RootVolumeMonitorService::class.java))
                            isWorking = true
                            prefs.edit().putBoolean("is_working", true).apply()
                            Toast.makeText(context, "工作状态已启动，点击 killzuoji 悬浮窗执行启动脚本", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "工作状态启动失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        context.stopService(Intent(context, RootVolumeMonitorService::class.java))
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
                        Text("V2.5.3 PR", fontSize = 13.sp, color = Color(0xFF68736D))
                        Text("当前为PR调试构建，请勿在生产环境中使用！",
                            fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
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
private fun LaunchScreen(modifier: Modifier = Modifier, isWorking: Boolean, onToggle: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Root 悬浮窗启动", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isWorking) "工作状态运行中。点击固定在底部“首页”文字位置的 killzuoji 悬浮窗，即可由 Root 执行 /data/local/tmp/launch_killapp.sh。"
                    else "点击执行开启工作状态并显示 killzuoji 悬浮窗；之后点击悬浮窗即可由 Root 执行启动脚本。",
                    color = Color(0xFF68736D), fontSize = 14.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Text(if (isWorking) "停止" else "执行", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isWorking) Color(0xFFB42318) else KsuAccent)
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("关于", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            InfoCard("应用名称", "KillSuoji")
        }
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
