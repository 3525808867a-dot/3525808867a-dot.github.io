package com.killsuoji.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/** 用户明确开启辅助功能后，在工作状态下监听五次音量下键。 */
class VolumeDownShortcutService : AccessibilityService() {
    private var pressCount = 0
    private var firstPressAt = 0L

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        pressCount = 0
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!getSharedPreferences("killsuoji_v2", MODE_PRIVATE).getBoolean("is_working", false)) {
            pressCount = 0
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (pressCount == 0 || now - firstPressAt > 3000L) {
                pressCount = 0
                firstPressAt = now
            }
            pressCount++
            if (pressCount >= 5) {
                pressCount = 0
                Toast.makeText(applicationContext, "拦截成功！", Toast.LENGTH_SHORT).show()
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?: Intent(this, MainActivity::class.java)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launch)
            }
        }
        return true
    }
}
