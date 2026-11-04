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

private val KsuAccent = Color(0xFF4285F4)
private val StatusRed = Color(0xFFFFE1E1)
private val StatusGreen = Color(0xFFD5F7E1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KillSuojiApp() }
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
                    isWorking = !isWorking
                    prefs.edit().putBoolean("is_working", isWorking).apply()
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
                    Text("V2.3.2 Beta", fontSize = 16.sp, color = Color(0xFF777777))
                }
            }
        }
        item { UpdateCard() }
        item { InfoCard("版本", "V2.3.2 Beta") }
    }
}


private fun fetchLatestUpdate(): Pair<String, String>? {
    val connection = URL("https://api.github.com/repos/3525808867a-dot/3525808867a-dot.github.io/releases/latest").openConnection()
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "KillSuoji-Android")
    return try {
        val json = JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
        val version = json.optString("tag_name").removePrefix("v")
        val latest = version.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val current = listOf(2, 3, 1)
        var newer = false
        for (i in 0..2) {
            val diff = (latest.getOrElse(i) { 0 }).compareTo(current[i])
            if (diff != 0) { newer = diff > 0; break }
        }
        if (!newer) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == version.substringBefore("-") + ".apk") apk = asset.optString("browser_download_url")
        }
        if (apk.isNullOrBlank()) null else Pair(version, apk!!)
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
            update = withContext(Dispatchers.IO) { fetchLatestUpdate() }
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
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isWorking) "停止" else "执行", fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, color = KsuAccent)
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
            InfoCard("版本", "V2.3.2 Beta")
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
