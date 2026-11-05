package com.killsuoji.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class RootPermissionProbe extends Activity {
    private static final String TAG = "KillSuojiPermissionProbe";
    private static final String OUT = "/data/local/tmp/killsuoji_probe.txt";

    private RootPermissionProbe() {}

    private static void report(String message) {
        System.out.println(message);
        System.out.flush();
        try (FileOutputStream out = new FileOutputStream(OUT, true)) {
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
            System.err.println("写入探测文件失败: " + ignored);
        }
        Log.i(TAG, message);
    }

    public static void main(String[] args) {
        report("PROBE_MAIN_ENTERED");
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object thread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThreadClass
                    .getMethod("getSystemContext").invoke(thread);

            int pid = Process.myPid();
            int uid = Process.myUid();
            int sf = context.checkPermission(
                    "android.permission.ACCESS_SURFACE_FLINGER", pid, uid);
            int input = context.checkPermission(
                    "android.permission.ACCESS_INPUT_FLINGER", pid, uid);

            report("pid=" + pid + " uid=" + uid
                    + " ACCESS_SURFACE_FLINGER="
                    + (sf == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
                    + " ACCESS_INPUT_FLINGER="
                    + (input == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        } catch (Throwable error) {
            report("PROBE_EXCEPTION: " + Log.getStackTraceString(error));
        }
    }
}
