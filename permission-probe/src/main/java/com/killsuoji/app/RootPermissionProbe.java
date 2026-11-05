package com.killsuoji.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class RootPermissionProbe extends Activity {
    private static final String TAG = "KillSuojiPermissionProbe";
    private TextView resultView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 24);

        TextView title = new TextView(this);
        title.setText("KillSuoji Root 权限检测");
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText("点击下方按钮，通过 Root 环境检测系统图形与输入服务权限。");
        intro.setTextSize(15);
        intro.setPadding(0, 22, 0, 22);
        root.addView(intro);

        Button run = new Button(this);
        run.setText("开始检测");
        root.addView(run, new LinearLayout.LayoutParams(-1, -2));

        resultView = new TextView(this);
        resultView.setTextSize(16);
        resultView.setText("等待检测");
        resultView.setPadding(0, 24, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(resultView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        run.setOnClickListener(v -> {
            run.setEnabled(false);
            resultView.setText("正在请求 Root 授权并检测……");
            new Thread(() -> {
                StringBuilder output = new StringBuilder();
                int exit = -1;
                try {
                    String apk = getApplicationInfo().sourceDir;
                    String command = "CLASSPATH='" + apk.replace("'", "'\\''")
                            + "' app_process /system/bin "
                            + RootPermissionProbe.class.getName();
                    java.lang.Process process = new ProcessBuilder("su", "-c", command)
                            .redirectErrorStream(true).start();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    }
                    exit = process.waitFor();
                } catch (Throwable e) {
                    output.append("检测启动失败：").append(Log.getStackTraceString(e));
                }
                final String text = output.length() == 0
                        ? "没有收到探测程序输出。退出码：" + exit
                        : output + "\n进程退出码：" + exit;
                runOnUiThread(() -> {
                    resultView.setText(text);
                    run.setEnabled(true);
                });
            }).start();
        });
    }

    public RootPermissionProbe() {}

    public static void main(String[] args) {
        System.out.println("探测入口已启动");
        System.out.flush();
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
            System.out.println("PID：" + pid + "  UID：" + uid);
            System.out.println("ACCESS_SURFACE_FLINGER：" +
                    (sf == PackageManager.PERMISSION_GRANTED ? "允许" : "拒绝"));
            System.out.println("ACCESS_INPUT_FLINGER：" +
                    (input == PackageManager.PERMISSION_GRANTED ? "允许" : "拒绝"));
        } catch (Throwable error) {
            System.out.println("检测异常：" + Log.getStackTraceString(error));
        }
        System.out.flush();
    }
}
