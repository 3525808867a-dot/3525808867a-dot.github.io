package com.killsuoji.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KsuBlue = Color(0xFF4169E1)
private val KsuBlueDark = Color(0xFFADC6FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KillSuojiApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KillSuojiApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var darkTheme by remember { mutableStateOf(false) }
    var useDynamicColor by remember { mutableStateOf(true) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("killsuoji_v2", android.content.Context.MODE_PRIVATE)
        darkTheme = prefs.getBoolean("dark_theme", false)
        useDynamicColor = prefs.getBoolean("dynamic_color", true)
    }
    val colorScheme = when {
        useDynamicColor && android.os.Build.VERSION.SDK_INT >= 31 && !darkTheme ->
            androidx.compose.material3.dynamicLightColorScheme(context)
        useDynamicColor && android.os.Build.VERSION.SDK_INT >= 31 && darkTheme ->
            androidx.compose.material3.dynamicDarkColorScheme(context)
        darkTheme -> darkColorScheme(primary = KsuBlueDark, secondary = KsuBlueDark)
        else -> lightColorScheme(primary = KsuBlue, secondary = KsuBlue)
    }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("KillSuoji", fontWeight = FontWeight.SemiBold)
                            Text("V2.0.0 Beta", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { selectedTab = 2 }) {
                            Icon(Icons.Default.Info, contentDescription = "关于")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("模块") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> HomeScreen(Modifier.padding(padding))
                1 -> ModulesScreen(Modifier.padding(padding))
                else -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    darkTheme = darkTheme,
                    dynamicColor = useDynamicColor,
                    onDarkThemeChange = {
                        darkTheme = it
                        context.getSharedPreferences("killsuoji_v2", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("dark_theme", it).apply()
                    },
                    onDynamicColorChange = {
                        useDynamicColor = it
                        context.getSharedPreferences("killsuoji_v2", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("dynamic_color", it).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                        Text("KSU UI", color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("KillSuoji", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("V2.0.0 Beta", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("KernelSU 风格界面版本", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            SectionTitle("应用状态")
            InfoCard("版本", "V2.0.0 Beta")
            InfoCard("界面来源", "KernelSU Manager 风格")
            InfoCard("功能模式", "纯界面模式")
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("功能说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("本版本仅保留应用界面与主题展示，不包含 Root 获取、Root 授权、超级用户管理、模块安装或系统修改功能。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ModulesScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null,
            modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("模块", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("此版本不提供模块安装、启用、停用或管理功能。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column {
                    SettingsSwitch("深色主题", "使用深色配色", darkTheme, onDarkThemeChange)
                    HorizontalDivider()
                    SettingsSwitch("动态取色", "跟随系统壁纸颜色（Android 12+）",
                        dynamicColor, onDynamicColorChange)
                }
            }
        }
        item {
            Text("关于", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        }
        item { InfoCard("应用名称", "KillSuoji") }
        item { InfoCard("版本", "V2.0.0 Beta") }
        item { InfoCard("界面项目", "KernelSU Manager") }
        item {
            Text("本应用为独立的纯界面版本，不连接 KernelSU 内核，也不执行 Root 操作。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Medium)
        }
    }
}
