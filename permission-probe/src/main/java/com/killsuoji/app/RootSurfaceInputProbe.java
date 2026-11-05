package com.killsuoji.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

/** Android 16 system-owned gesture-monitor + SurfaceControl proof of concept. */
public final class RootSurfaceInputProbe {
    private RootSurfaceInputProbe() {}

    public static void run(Context context) {
        Object monitor = null;
        SurfaceControl button = null;
        InputEventReceiver receiver = null;
        try {
            System.out.println("开始创建系统手势监视通道……");
            System.out.flush();

            InputManager inputManager = InputManager.getInstance();
            monitor = inputManager.monitorGestureInput("KillSuoji-Surface-Probe", Display.DEFAULT_DISPLAY);
            InputChannel channel = (InputChannel) monitor.getClass()
                    .getMethod("getInputChannel").invoke(monitor);
            SurfaceControl monitorSurface = (SurfaceControl) monitor.getClass()
                    .getMethod("getSurface").invoke(monitor);
            System.out.println("InputMonitor：创建成功");
            System.out.println("InputChannel：已取得");
            System.out.println("Monitor Surface：有效=" + monitorSurface.isValid());

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            display.getRealMetrics(dm);
            int left = Math.max(24, (int) (24 * dm.density));
            int top = Math.max(80, dm.heightPixels - (int) (220 * dm.density));
            int width = (int) (210 * dm.density);
            int height = (int) (76 * dm.density);

            button = new SurfaceControl.Builder()
                    .setName("KillSuoji-Native-Surface-Test")
                    .setParent(monitorSurface)
                    .setColorLayer()
                    .build();

            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setPosition(button, left, top);
            transaction.setWindowCrop(button, width, height);
            transaction.setLayer(button, Integer.MAX_VALUE - 2);
            transaction.setColor(button, new float[]{0.08f, 0.32f, 0.82f});
            transaction.setAlpha(button, 0.96f);
            transaction.show(button);
            transaction.apply();
            transaction.close();
            System.out.println("SurfaceControl：彩色测试层已提交");
            System.out.println("请在屏幕左下方蓝色测试条上点击，测试输入事件。");
            System.out.flush();

            final int hitLeft = left;
            final int hitTop = top;
            final int hitRight = left + width;
            final int hitBottom = top + height;
            final int[] tapCount = {0};
            receiver = new InputEventReceiver(channel, Looper.myLooper()) {
                @Override
                protected void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) {
                            MotionEvent motion = (MotionEvent) event;
                            if (motion.getActionMasked() == MotionEvent.ACTION_UP) {
                                float x = motion.getRawX();
                                float y = motion.getRawY();
                                if (x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom) {
                                    tapCount[0]++;
                                    System.out.println("蓝色测试条收到点击 #" + tapCount[0]
                                            + " 坐标=(" + (int)x + "," + (int)y + ")");
                                    System.out.flush();
                                }
                            }
                        }
                    } finally {
                        finishInputEvent(event, false);
                    }
                }
            };

            Looper looper = Looper.myLooper();
            new Handler(looper).postDelayed(() -> looper.quit(), 20000);
            Looper.loop();
            System.out.println("20 秒测试结束，蓝色测试条点击次数：" + tapCount[0]);
        } catch (Throwable error) {
            System.out.println("原生 Surface/Input 测试失败：" + android.util.Log.getStackTraceString(error));
        } finally {
            try { if (receiver != null) receiver.dispose(); } catch (Throwable ignored) {}
            try {
                if (monitor != null) monitor.getClass().getMethod("dispose").invoke(monitor);
            } catch (Throwable ignored) {}
            try { if (button != null) button.release(); } catch (Throwable ignored) {}
        }
        System.out.flush();
    }
}
