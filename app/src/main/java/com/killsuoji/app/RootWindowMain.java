package com.killsuoji.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Standalone Root-owned window process.
 * Started by the installed Magisk/SukiSU module with app_process and this APK on CLASSPATH.
 */
public final class RootWindowMain {
    private static final String TAG = "KillSuojiRootWindow";
    private static volatile boolean starting = false;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) throw new IllegalStateException("WindowManager unavailable");

            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            float density = dm.density;

            TextView label = new TextView(context);
            label.setText("killzuoji");
            label.setTextColor(Color.WHITE);
            label.setTextSize(12f);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            label.setPadding((int)(8 * density), (int)(5 * density),
                    (int)(8 * density), (int)(5 * density));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.BLACK);
            bg.setCornerRadius(0f);
            label.setBackground(bg);
            label.setClickable(true);
            label.setOnClickListener(v -> executeNow());

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.LEFT;
            params.x = Math.max(0, (int)(dm.widthPixels / 6f - 31f * density));
            params.y = (int)(34f * density);
            wm.addView(label, params);
            Log.i(TAG, "Root window attached; uid=" + android.os.Process.myUid());
            Looper.loop();
        } catch (Throwable t) {
            Log.e(TAG, "Root window startup failed", t);
        }
    }

    private static void executeNow() {
        if (starting) return;
        starting = true;
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("sh", "-c",
                        "am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService "
                                + "-a com.killsuoji.app.action.EXECUTE_NOW")
                        .redirectErrorStream(true).start();
                p.waitFor();
            } catch (Throwable t) {
                Log.e(TAG, "Unable to dispatch execute action", t);
            } finally {
                starting = false;
            }
        }, "KillSuojiRootWindowAction").start();
    }
}
