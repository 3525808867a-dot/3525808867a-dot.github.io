package com.killsuoji.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.util.Log;
import java.lang.reflect.Method;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Root-owned native overlay. SurfaceControl draws the button above ordinary app
 * windows; monitorGestureInput observes taps without consuming them.
 */
public final class RootWindowMain {
    private static final String TAG = "KillSuojiRootWindow";
    private static volatile boolean starting = false;
    private static final String LOG_FILE = "/data/adb/killsuoji_overlay/root-window.log";

    private static synchronized void writeLog(String message, Throwable error) {
        StringBuilder line = new StringBuilder();
        line.append("[").append(System.currentTimeMillis()).append("] ").append(message);
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            line.append("\\n").append(sw);
        }
        line.append("\\n");
        try {
            java.io.File dir = new java.io.File("/data/adb/killsuoji_overlay");
            if (!dir.exists()) dir.mkdirs();
            FileWriter writer = new FileWriter(LOG_FILE, true);
            writer.write(line.toString());
            writer.close();
        } catch (Throwable ignored) {}
        if (error == null) Log.i(TAG, message); else Log.e(TAG, message, error);
    }

    private static Method findMethod(Class<?> type, String name, int count) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == count) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + count);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        return findMethod(target.getClass(), name, args.length).invoke(target, args);
    }

    public static void main(String[] args) {
        Object monitor = null;
        InputEventReceiver receiver = null;
        SurfaceControl buttonControl = null;
        Surface buttonSurface = null;
        writeLog("RootWindowMain entered; uid=" + android.os.Process.myUid(), null);
        try {
            Looper.prepareMainLooper();
            writeLog("Main looper prepared", null);
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            writeLog("System context acquired", null);
            InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            writeLog("Creating gesture input monitor", null);
            monitor = call(inputManager, "monitorGestureInput", "KillSuoji-Root-Overlay", Display.DEFAULT_DISPLAY);
            writeLog("Gesture input monitor created", null);
            InputChannel channel = (InputChannel) call(monitor, "getInputChannel");
            SurfaceControl monitorSurface = (SurfaceControl) call(monitor, "getSurface");

            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            float density = dm.density;
            int width = Math.max(1, (int) (94f * density));
            int height = Math.max(1, (int) (30f * density));
            int left = Math.max(0, (int) (dm.widthPixels / 6f - 31f * density));
            int top = Math.max(0, dm.heightPixels - (int) (34f * density) - height);

            SurfaceControl.Builder builder = new SurfaceControl.Builder();
            call(builder, "setName", "KillSuoji-PR05-Button");
            call(builder, "setParent", monitorSurface);
            call(builder, "setBufferSize", width, height);
            call(builder, "setFormat", PixelFormat.TRANSLUCENT);
            writeLog("Building SurfaceControl button", null);
            buttonControl = builder.build();
            buttonSurface = new Surface(buttonControl);

            Canvas canvas = buttonSurface.lockCanvas(null);
            try {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(Color.BLACK);
                canvas.drawRoundRect(0, 0, width, height, 4f * density, 4f * density, bg);
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(Color.WHITE);
                text.setTextSize(12f * density);
                // Do not call Typeface APIs here. Some vendor builds crash inside
                // Typeface.DEFAULT_BOLD / Typeface.create when the base font is null.
                // A Paint with no explicit typeface uses the platform default.
                text.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = text.getFontMetrics();
                float baseline = height / 2f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText("killzuoji", width / 2f, baseline, text);
            } finally {
                buttonSurface.unlockCanvasAndPost(canvas);
            }

            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            call(tx, "setPosition", buttonControl, (float) left, (float) top);
            call(tx, "setWindowCrop", buttonControl, new Rect(0, 0, width, height));
            call(tx, "setLayer", buttonControl, Integer.MAX_VALUE - 2);
            call(tx, "show", buttonControl);
            call(tx, "apply");
            call(tx, "close");
            writeLog("Native SurfaceControl button attached; monitor active at " + left + "," + top + " size " + width + "x" + height, null);

            final int hitLeft = left, hitTop = top, hitRight = left + width, hitBottom = top + height;
            receiver = new InputEventReceiver(channel, Looper.myLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) {
                            MotionEvent motion = (MotionEvent) event;
                            if (motion.getActionMasked() == MotionEvent.ACTION_UP) {
                                float x = motion.getRawX(), y = motion.getRawY();
                                if (x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom) {
                                    executeNow();
                                }
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "Input dispatch failed", error);
                    } finally {
                        finishInputEvent(event, false);
                    }
                }
            };
            Looper.loop();
        } catch (Throwable error) {
            writeLog("Native overlay startup failed", error);
        } finally {
            try { if (receiver != null) receiver.dispose(); } catch (Throwable ignored) {}
            try { if (monitor != null) call(monitor, "dispose"); } catch (Throwable ignored) {}
            try { if (buttonSurface != null) buttonSurface.release(); } catch (Throwable ignored) {}
            try { if (buttonControl != null) buttonControl.release(); } catch (Throwable ignored) {}
        }
    }

    private static void executeNow() {
        if (starting) return;
        starting = true;
        writeLog("Overlay button tapped; dispatching execute action", null);
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("sh", "-c",
                    "am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService "
                    + "-a com.killsuoji.app.action.EXECUTE_NOW")
                    .redirectErrorStream(true).start();
                int exitCode = p.waitFor();
                writeLog("Execute action service dispatch exit=" + exitCode, null);
            } catch (Throwable t) {
                writeLog("Unable to dispatch execute action", t);
            } finally {
                starting = false;
            }
        }, "KillSuojiNativeOverlayAction").start();
    }
}
