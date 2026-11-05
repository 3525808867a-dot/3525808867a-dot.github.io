package com.killsuoji.app;

import android.content.Context;
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
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.lang.reflect.Method;

/** Android 16 hidden-API diagnostic. Calls framework methods reflectively to avoid SDK stub drift. */
public final class RootSurfaceInputProbe {
    private RootSurfaceInputProbe() {}

    private static Method method(Class<?> type, String name, int count) throws Exception {
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
        Method m = method(target.getClass(), name, args.length);
        return m.invoke(target, args);
    }

    public static void run(Context context) {
        Object monitor = null;
        SurfaceControl button = null;
        InputEventReceiver receiver = null;
        try {
            System.out.println("开始创建系统手势监视通道……");
            System.out.flush();

            InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            monitor = call(inputManager, "monitorGestureInput",
                    "KillSuoji-Surface-Probe", Display.DEFAULT_DISPLAY);
            InputChannel channel = (InputChannel) call(monitor, "getInputChannel");
            SurfaceControl monitorSurface = (SurfaceControl) call(monitor, "getSurface");
            System.out.println("InputMonitor：创建成功");
            System.out.println("InputChannel：已取得");
            System.out.println("Monitor Surface：有效=" + monitorSurface.isValid());

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            int left = Math.max(24, (int) (24 * dm.density));
            int top = Math.max(80, dm.heightPixels - (int) (220 * dm.density));
            int width = (int) (210 * dm.density);
            int height = (int) (76 * dm.density);

            SurfaceControl.Builder builder = new SurfaceControl.Builder();
            call(builder, "setName", "KillSuoji-Native-Surface-Test");
            call(builder, "setParent", monitorSurface);
            call(builder, "setColorLayer");
            button = builder.build();

            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            call(tx, "setPosition", button, (float) left, (float) top);
            call(tx, "setWindowCrop", button, new Rect(0, 0, width, height));
            call(tx, "setLayer", button, Integer.MAX_VALUE - 2);
            call(tx, "setColor", button, new float[]{0.08f, 0.32f, 0.82f});
            call(tx, "setAlpha", button, 0.96f);
            call(tx, "show", button);
            call(tx, "apply");
            call(tx, "close");
            System.out.println("SurfaceControl：彩色测试层已提交");
            System.out.println("请在屏幕左下方蓝色测试条上点击，测试输入事件。");
            System.out.flush();

            final int hitLeft = left, hitTop = top;
            final int hitRight = left + width, hitBottom = top + height;
            final int[] tapCount = {0};
            receiver = new InputEventReceiver(channel, Looper.myLooper()) {
                @Override
                public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) {
                            MotionEvent motion = (MotionEvent) event;
                            if (motion.getActionMasked() == MotionEvent.ACTION_UP) {
                                float x = motion.getRawX(), y = motion.getRawY();
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
            try { if (monitor != null) call(monitor, "dispose"); } catch (Throwable ignored) {}
            try { if (button != null) button.release(); } catch (Throwable ignored) {}
        }
        System.out.flush();
    }
}
