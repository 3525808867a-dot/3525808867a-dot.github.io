package com.killsuoji.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val KsuAccent = Color(0xFF4285F4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KillSuojiApp() }
    }
}

@Composable
private fun KillSuojiApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("killsuoji_v2", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }

    MiuixTheme {
        Scaffold(
            topBar = { TopAppBar(title = "KillSuoji") },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavItem("首页", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    NavItem("模块", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    NavItem("设置", tab == 2, Modifier.weight(1f)) { tab = 2 }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(Modifier.padding(padding))
                1 -> ModulesScreen(Modifier.padding(padding))
                else -> SettingsScreen(Modifier.padding(padding), dark) {
                    dark = it
                    prefs.edit().putBoolean("dark_theme", it).apply()
                }
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
private fun HomeScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KernelSU", color = KsuAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("KillSuoji", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("V2.0.0 Beta", fontSize = 16.sp, color = Color(0xFF777777))
                    Text("KernelSU · Miuix UI", fontSize = 14.sp)
                }
            }
        }
        item { InfoCard("应用状态", "纯界面模式") }
        item { InfoCard("版本", "V2.0.0 Beta") }
        item { InfoCard("界面风格", "KernelSU Miuix") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("功能说明", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("仅保留 KSU Miuix 风格界面，不包含 Root 获取、Root 授权、超级用户管理、模块安装或系统修改功能。",
                        fontSize = 14.sp, color = Color(0xFF777777))
                }
            }
        }
    }
}

@Composable
private fun ModulesScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("模块", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("此纯界面版本不提供模块安装或管理功能。",
            color = Color(0xFF777777))
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("外观", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { onDarkChange(!dark) }.padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("深色模式")
                        Text("切换 Miuix 深色外观", fontSize = 12.sp, color = Color(0xFF777777))
                    }
                    Text(if (dark) "已开启" else "已关闭", color = KsuAccent)
                }
            }
        }
        item {
            Text("关于", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            InfoCard("应用名称", "KillSuoji")
            Spacer(Modifier.height(8.dp))
            InfoCard("版本", "V2.0.0 Beta")
            Spacer(Modifier.height(8.dp))
            InfoCard("UI 框架", "Miuix · KernelSU")
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
