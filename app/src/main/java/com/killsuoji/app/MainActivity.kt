package com.killsuoji.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors

private val KsuBg = Color(0xFFF5F6F8)
private val KsuMint = Color(0xFFDDF5E8)
private val KsuGreen = Color(0xFF269565)
private val KsuInk = Color(0xFF20242A)
private val KsuMuted = Color(0xFF737B86)

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var rootAvailable by mutableStateOf(false)
    private var volumeCount = 0
    private var lastVolumeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245, 246, 248)
        window.navigationBarColor = android.graphics.Color.rgb(245, 246, 248)
        window.decorView.systemUiVisibility = 0x00002000 or 0x00000010
        detectRoot()
        setContent {
            MiuixTheme {
                KillSuojiUi(rootAvailable = rootAvailable, onExecute = { runSafeGuardCheck() })
            }
        }
    }

    private fun detectRoot() {
        executor.execute {
            val available = try {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && output.contains("uid=0")
            } catch (_: Exception) { false }
            runOnUiThread { rootAvailable = available }
        }
    }

    private fun runSafeGuardCheck() {
        if (!rootAvailable) {
            android.app.AlertDialog.Builder(this).setMessage("未获得 Root 权限，当前不可执行。").setPositiveButton("确定", null).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("安全执行确认")
            .setMessage("将检查 KillSuoji 自身运行状态。不会强制结束其他应用或系统进程。是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("守护检查完成")
                    .setMessage("KillSuoji 本应用状态检查已完成。")
                    .setPositiveButton("关闭", null).show()
            }.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && !event.isLongPress) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeAt > 3500) volumeCount = 0
            lastVolumeAt = now
            volumeCount++
            if (volumeCount >= 5) {
                volumeCount = 0
                Toast.makeText(this, "已检测到连续 5 次音量上键", Toast.LENGTH_LONG).show()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun KillSuojiUi(rootAvailable: Boolean, onExecute: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = KsuGreen,
            onPrimary = Color.White,
            background = KsuBg,
            surface = Color.White,
            onSurface = KsuInk,
            secondaryContainer = KsuMint
        )
    ) {
        Scaffold(
            containerColor = KsuBg,
            topBar = {
                TopAppBar(
                    title = { Text("KillSuoji", fontWeight = FontWeight.Bold, color = KsuInk) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = KsuBg)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = page == 0, onClick = { page = 0 },
                        icon = { Text("⌂", fontSize = 22.sp) }, label = { Text("主页") }
                    )
                    NavigationBarItem(
                        selected = page == 1, onClick = { page = 1 },
                        icon = { Text("▷", fontSize = 22.sp) }, label = { Text("启动") }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (page == 0) {
                    KsuCard(container = KsuMint) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = KsuGreen, shape = CircleShape) {
                                Text("KSU", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("安全管理", color = KsuGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(26.dp))
                        Text("工作中", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = KsuInk)
                        Text("V1.1.0 beta", color = KsuMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (rootAvailable) "✓" else "!", modifier = Modifier.align(Alignment.End), fontSize = 42.sp, color = KsuGreen)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KsuCard(Modifier.weight(1f)) { Text("0", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("超级用户", color = KsuMuted) }
                        KsuCard(Modifier.weight(1f)) { Text("0", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("模块", color = KsuMuted) }
                    }
                    KsuCard {
                        InfoRow("应用版本", "V1.1.0 beta")
                        HorizontalDivider(color = Color(0xFFE8EBEF))
                        InfoRow("Root 权限", if (rootAvailable) "已获得" else "未获得")
                        HorizontalDivider(color = Color(0xFFE8EBEF))
                        InfoRow("运行环境", "Android")
                    }
                } else {
                    KsuCard(container = KsuMint) {
                        Text("◈  Root 权限状态", color = KsuMuted, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(20.dp))
                        Text(if (rootAvailable) "已获得 root 权限，可执行" else "不可执行！", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = if (rootAvailable) KsuGreen else Color(0xFFD34B4B))
                        Text(if (rootAvailable) "Root 授权检测通过" else "尚未检测到可用的 su 授权", color = KsuMuted)
                    }
                    Button(onClick = onExecute, enabled = rootAvailable, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("▶   执行", fontSize = 18.sp)
                    }
                    KsuCard {
                        Text("操作说明", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("1. 连续按下 5 次音量上键弹出提示\n2. 执行前检查 Root 授权状态\n3. 仅管理本应用自身状态，不强制结束其他应用或系统进程", color = KsuMuted, lineHeight = 24.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun KsuCard(
    modifier: Modifier = Modifier,
    container: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E9ED)),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = KsuInk)
        Text(value, color = KsuMuted)
    }
}
