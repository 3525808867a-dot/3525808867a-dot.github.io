package com.killsuoji.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

/**
 * Non-invasive permission probe for the Android 16 native-window prototype.
 * Run with app_process and this APK on CLASSPATH; it does not create a window
 * or change device settings.
 */
public final class RootPermissionProbe {
    private static final String TAG = "KillSuojiPermissionProbe";

    private RootPermissionProbe() {}

    public static void main(String[] args) {
        try {
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

            Log.i(TAG, "pid=" + pid
                    + " uid=" + uid
                    + " ACCESS_SURFACE_FLINGER="
                    + (sf == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
                    + " ACCESS_INPUT_FLINGER="
                    + (input == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        } catch (Throwable error) {
            Log.e(TAG, "Permission probe failed", error);
        }
    }
}
