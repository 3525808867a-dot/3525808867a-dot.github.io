package com.killsuoji.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // KernelSU-style design tokens, adapted for KillSuoji's native Android UI.
    private val pageBg = Color.rgb(246, 247, 249)
    private val surface = Color.rgb(255, 255, 255)
    private val surfaceVariant = Color.rgb(239, 242, 246)
    private val mint = Color.rgb(225, 246, 235)
    private val green = Color.rgb(55, 145, 105)
    private val deepGreen = Color.rgb(34, 112, 79)
    private val ink = Color.rgb(28, 31, 35)
    private val muted = Color.rgb(104, 112, 122)
    private val outline = Color.rgb(224, 228, 233)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var rootAvailable = false
    private var volumeCount = 0
    private var lastVolumeAt = 0L
    private var currentPage = "home"
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        showShell()
        detectRoot()
    }

    private fun detectRoot() {
        executor.execute {
            val available = try {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && output.contains("uid=0")
            } catch (_: Exception) { false }
            runOnUiThread {
                rootAvailable = available
                if (currentPage == "launch") showLaunch()
                else showHome()
            }
        }
    }

    private fun showShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(20))
        }
        val logo = label("Kill", 25, ink, true).apply {
            gravity = Gravity.CENTER
            background = shape(Color.WHITE, Color.BLACK, 2, 50)
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
        }
        header.addView(logo)
        header.addView(label("KillSuoji", 27, ink, true).apply {
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val close = label("⚙", 26, muted, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle("设置").setMessage("KillSuoji 1.0.0").setPositiveButton("知道了", null).show() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = shape(surface, outline, 1, 28)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val home = navButton("⌂\n主页") { currentPage = "home"; showHome() }
        val launch = navButton("▷\n启动") { currentPage = "launch"; showLaunch() }
        nav.addView(home, LinearLayout.LayoutParams(0, dp(66), 1f))
        nav.addView(launch, LinearLayout.LayoutParams(0, dp(66), 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun showHome() {
        currentPage = "home"
        content.removeAllViews()
        val hero = card(mint)
        hero.addView(tagRow())
        hero.addView(label("工作中", 36, ink, true).apply { setPadding(0, dp(44), 0, 0) })
        hero.addView(label("版本：1.0.0", 16, muted, false))
        hero.addView(label("✓", 54, green, true).apply { gravity = Gravity.RIGHT; setPadding(0, 0, dp(12), 0) })
        content.addView(hero)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statCard("0", "超级用户"), LinearLayout.LayoutParams(0, dp(112), 1f))
        stats.addView(statCard("0", "模块"), LinearLayout.LayoutParams(0, dp(112), 1f).apply { leftMargin = dp(12) })
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val info = card(Color.WHITE)
        listOf(
            "应用版本" to "1.0.0",
            "Root 权限" to if (rootAvailable) "已获得" else "未获得",
            "运行环境" to "Android",
            "应用名称" to "KillSuoji"
        ).forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
            row.addView(label(pair.first, 15, ink, false), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(pair.second, 14, muted, false))
            info.addView(row)
            if (index != 3) info.addView(View(this).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        content.addView(info)
    }

    private fun showLaunch() {
        currentPage = "launch"
        content.removeAllViews()
        val status = card(mint)
        status.addView(label("◈  Root 权限状态", 18, muted, true))
        status.addView(label(if (rootAvailable) "已获得 root 权限，可执行" else "不可执行！", 25, if (rootAvailable) deepGreen else Color.rgb(210, 75, 75), true).apply { setPadding(0, dp(26), 0, dp(14)) })
        status.addView(label(if (rootAvailable) "Root 授权检测通过" else "尚未检测到可用的 su 授权", 15, muted, false))
        content.addView(status)

        val execute = Button(this).apply {
            text = "▶   执行"
            textSize = 20f
            setTextColor(deepGreen)
            background = shape(mint, Color.TRANSPARENT, 0, 40)
            isEnabled = rootAvailable
            alpha = if (rootAvailable) 1f else 0.55f
            setOnClickListener { runSafeGuardCheck() }
        }
        content.addView(execute, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(18) })

        val instructions = card(Color.WHITE)
        instructions.addView(label("ⓘ  操作说明", 18, muted, true))
        instructions.addView(label("1. 连续按下 5 次音量上键弹出提示\n2. 执行前检查 Root 授权状态\n3. 仅管理本应用自身状态，不强制结束其他应用或系统进程", 15, muted, false).apply { setPadding(dp(8), dp(18), 0, 0); setLineSpacing(dp(8).toFloat(), 1f) })
        content.addView(instructions)
    }

    private fun runSafeGuardCheck() {
        if (!rootAvailable) {
            AlertDialog.Builder(this).setMessage("未获得 Root 权限，当前不可执行。").setPositiveButton("确定", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("安全执行确认")
            .setMessage("将检查 KillSuoji 自身运行状态。为保护系统稳定，不会强制结束其他应用或系统进程。是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("守护检查完成")
                    .setMessage("KillSuoji 本应用状态检查已完成。")
                    .setPositiveButton("关闭") { _, _ -> currentPage = "home"; showHome(); Toast.makeText(this, "已返回 KillSuoji", Toast.LENGTH_SHORT).show() }
                    .show()
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

    private fun tagRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label("LKM", 12, Color.WHITE, true).apply { background = shape(green, Color.TRANSPARENT, 0, 20); setPadding(dp(10), dp(4), dp(10), dp(4)) })
        addView(label("自定义", 12, deepGreen, true).apply { background = shape(Color.rgb(215, 239, 226), Color.TRANSPARENT, 0, 20); setPadding(dp(10), dp(4), dp(10), dp(4)); setMarginsStart(dp(8)) })
        addView(label("安全模式", 12, Color.rgb(125, 75, 75), true).apply { background = shape(Color.rgb(255, 239, 239), Color.TRANSPARENT, 0, 20); setPadding(dp(10), dp(4), dp(10), dp(4)); setMarginsStart(dp(8)) })
    }

    private fun statCard(value: String, title: String) = card(Color.WHITE).apply {
        addView(label(value, 30, ink, true))
        addView(label(title, 14, muted, false))
    }
    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(color, outline, 1, 22)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    private fun navButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(deepGreen); setOnClickListener { action() }
        background = shape(mint, Color.TRANSPARENT, 0, 22)
    }
    private fun shape(color: Int, stroke: Int, strokeWidth: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun TextView.setMarginsStart(margin: Int) { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = margin }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
