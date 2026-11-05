package com.killsuoji.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final String LOG_FILE = "/data/adb/killsuoji_overlay/root-window.log";
    private static final String SAFETY_FILE = "/data/adb/killsuoji_overlay/safety_mode";
    private static final String BUILD_ID = "3.1.0-formal";
    private static SurfaceControl monitorSurface;
    private static SurfaceControl buttonControl;
    private static Surface buttonSurface;
    private static SurfaceControl safetyControl;
    private static Surface safetySurface;
    private static Handler mainHandler;
    private static int left, top, width, height, mainHeight, cancelTop, cancelBottom, safetyHeight;
    private static float density;
    private static volatile boolean safetyMode = false;
    private static int tapCount = 0;
    private static long firstTapAt = 0L;
    private static int rainbowIndex = 0;
    private static final int[] RAINBOW = { Color.rgb(255,65,85), Color.rgb(255,145,40), Color.rgb(255,220,55), Color.rgb(65,220,110), Color.rgb(55,165,255), Color.rgb(175,95,255) };

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

        writeLog("BUILD_ID=" + BUILD_ID + "; RootWindowMain entered; uid=" + android.os.Process.myUid(), null);
        try {
            Looper.prepareMainLooper();
            mainHandler = new Handler(Looper.myLooper());
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
            monitorSurface = (SurfaceControl) call(monitor, "getSurface");

            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            density = dm.density;
            width = Math.max(1, (int) (128f * density));
            mainHeight = Math.max(1, (int) (36f * density));
            height = Math.max(1, (int) (68f * density));
            cancelTop = mainHeight + Math.max(1, (int) (4f * density));
            cancelBottom = cancelTop + Math.max(1, (int) (25f * density));
            safetyHeight = mainHeight;
            left = Math.max(0, (int) (dm.widthPixels / 6f - 31f * density));
            top = Math.max(0, dm.heightPixels - (int) (34f * density) - height);

            writeLog("Building SurfaceControl button", null);
            safetyMode = new java.io.File(SAFETY_FILE).exists();
            buttonControl = createSurface("KillSuoji-Root-Button", width, height);
            writeLog("SurfaceControl button built", null);
            buttonSurface = new Surface(buttonControl);
            drawNormalButton();
            applySurface(buttonControl, left, top, Integer.MAX_VALUE - 2, width, height);
            writeLog("SurfaceControl transaction applied", null);
            writeLog("Native SurfaceControl button attached; monitor active at " + left + "," + top + " size " + width + "x" + height, null);
            if (safetyMode) {
                writeLog("Restoring persisted safety mode after overlay restart", null);
                createSafetyOverlay();
                dispatchServiceAction("com.killsuoji.app.action.ENTER_SAFE_MODE");
            }

            final int hitLeft = left, hitRight = left + width, hitTop = top;
            final int mainHitBottom = top + mainHeight;
            final int cancelHitTop = top + cancelTop, cancelHitBottom = top + cancelBottom;
            receiver = new InputEventReceiver(channel, Looper.myLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) {
                            MotionEvent motion = (MotionEvent) event;
                            if (motion.getActionMasked() == MotionEvent.ACTION_UP) {
                                float x = motion.getRawX(), y = motion.getRawY();
                                if (x >= hitLeft && x <= hitRight) {
                                    if (safetyMode && y >= cancelHitTop && y <= cancelHitBottom) cancelSafetyMode();
                                    else if (y >= hitTop && y <= mainHitBottom) handleNormalTap();
                                }
                            }
                        }
                    } catch (Throwable error) {
                        writeLog("Input dispatch failed", error);
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

    private static SurfaceControl createSurface(String name, int w, int h) throws Exception {
        SurfaceControl.Builder builder = new SurfaceControl.Builder();
        call(builder, "setName", name);
        call(builder, "setParent", monitorSurface);
        call(builder, "setBufferSize", w, h);
        call(builder, "setFormat", PixelFormat.TRANSLUCENT);
        return builder.build();
    }

    private static void applySurface(SurfaceControl control, int x, int y, int layer, int w, int h) throws Exception {
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        call(tx, "setPosition", control, (float)x, (float)y);
        call(tx, "setWindowCrop", control, new Rect(0, 0, w, h));
        call(tx, "setLayer", control, layer);
        call(tx, "show", control);
        call(tx, "apply");
        call(tx, "close");
    }

    private static void drawCenteredText(Canvas canvas, String value, float cx, float cy, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        Paint.FontMetrics fm = p.getFontMetrics();
        canvas.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    private static void drawNormalButton() {
        if (buttonSurface == null) return;
        Canvas canvas = null;
        try {
            canvas = buttonSurface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.BLACK);
            canvas.drawRoundRect(0, 0, width, mainHeight, 8f*density, 8f*density, bg);
            drawCenteredText(canvas, "停止", width/2f, mainHeight/2f, 17f*density, Color.WHITE);
            if (safetyMode) {
                int ch = Math.max(1, (int)(25f*density));
                Paint cbg = new Paint(Paint.ANTI_ALIAS_FLAG);
                cbg.setColor(Color.rgb(75,24,30));
                canvas.drawRoundRect(0, cancelTop, width, cancelTop+ch, 7f*density, 7f*density, cbg);
                drawCenteredText(canvas, "取消安全模式", width/2f, cancelTop+ch/2f, 12f*density, Color.WHITE);
            }
            buttonSurface.unlockCanvasAndPost(canvas);
            writeLog("Button canvas posted with text", null);
        } catch (Throwable error) {
            try { if (canvas != null) buttonSurface.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
            writeLog("Button text rendering failed", error);
        }
    }

    private static void handleNormalTap() {
        long now = SystemClock.uptimeMillis();
        if (tapCount == 0 || now - firstTapAt > 3000L) { tapCount = 1; firstTapAt = now; }
        else tapCount++;
        writeLog("Normal overlay tap count=" + tapCount, null);
        if (tapCount >= 5 && now - firstTapAt <= 3000L) {
            tapCount = 0;
            enterSafetyMode();
        } else executeNow();
    }

    private static void enterSafetyMode() {
        if (safetyMode) return;
        safetyMode = true;
        try {
            java.io.File state = new java.io.File(SAFETY_FILE);
            java.io.File parent = state.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            state.createNewFile();
        } catch (Throwable error) { writeLog("Unable to persist safety mode state", error); }
        writeLog("Safety mode entered after five taps within three seconds", null);
        drawNormalButton();
        createSafetyOverlay();
        dispatchServiceAction("com.killsuoji.app.action.ENTER_SAFE_MODE");
    }

    private static void createSafetyOverlay() {
        if (!safetyMode || safetyControl != null) return;
        try {
            safetyControl = createSurface("KillSuoji-Safety-Mode-Passive", width, safetyHeight);
            safetySurface = new Surface(safetyControl);
            drawSafetyBanner();
            int safeTop = Math.max(0, top - safetyHeight - Math.max(2, (int)(5f*density)));
            applySurface(safetyControl, left, safeTop, Integer.MAX_VALUE - 1, width, safetyHeight);
            animateSafetyBanner();
            writeLog("Passive safety overlay attached without touch or drag handling", null);
        } catch (Throwable error) { writeLog("Safety overlay creation failed", error); removeSafetySurface(); }
    }

    private static void drawSafetyBanner() {
        if (safetySurface == null || !safetyMode) return;
        Canvas canvas = null;
        try {
            canvas = safetySurface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.BLACK);
            canvas.drawRoundRect(0, 0, width, safetyHeight, 8f*density, 8f*density, bg);
            int[] colors = { Color.rgb(255,65,85), Color.rgb(255,145,40), Color.rgb(255,220,55), Color.rgb(65,220,110), Color.rgb(55,165,255), Color.rgb(175,95,255) };
            drawCenteredText(canvas, "安全模式", width/2f, safetyHeight/2f, 17f*density, colors[rainbowIndex % colors.length]);
            safetySurface.unlockCanvasAndPost(canvas);
        } catch (Throwable error) {
            try { if (canvas != null) safetySurface.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
            writeLog("Safety banner draw failed", error);
        }
    }

    private static void animateSafetyBanner() {
        if (!safetyMode || safetySurface == null || mainHandler == null) return;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!safetyMode || safetySurface == null) return;
                rainbowIndex = (rainbowIndex + 1) % 6;
                drawSafetyBanner();
                animateSafetyBanner();
            }
        }, 170L);
    }

    private static void cancelSafetyMode() {
        if (!safetyMode) return;
        safetyMode = false;
        try { new java.io.File(SAFETY_FILE).delete(); } catch (Throwable ignored) {}
        writeLog("Safety mode cancelled by user", null);
        removeSafetySurface();
        drawNormalButton();
        dispatchServiceAction("com.killsuoji.app.action.CANCEL_SAFE_MODE");
    }

    private static void removeSafetySurface() {
        try {
            if (safetyControl != null) {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                call(tx, "hide", safetyControl); call(tx, "apply"); call(tx, "close");
            }
        } catch (Throwable ignored) {}
        try { if (safetySurface != null) safetySurface.release(); } catch (Throwable ignored) {}
        try { if (safetyControl != null) safetyControl.release(); } catch (Throwable ignored) {}
        safetySurface = null; safetyControl = null;
    }

    private static void executeNow() {
        dispatchServiceAction("com.killsuoji.app.action.EXECUTE_NOW");
    }

    private static void dispatchServiceAction(String action) {
        writeLog("Dispatching service action=" + action, null);
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("sh", "-c",
                    "am start-foreground-service -n com.killsuoji.app/.RootVolumeMonitorService -a " + action)
                    .redirectErrorStream(true).start();
                int exitCode = p.waitFor();
                writeLog("Service dispatch exit=" + exitCode + " action=" + action, null);
            } catch (Throwable t) { writeLog("Unable to dispatch service action=" + action, t); }
        }, "KillSuojiNativeOverlayAction").start();
    }
}
