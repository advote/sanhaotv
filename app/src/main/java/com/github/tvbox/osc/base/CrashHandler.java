package com.github.tvbox.osc.base;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 三好TV · 全局崩溃捕获器
 * <p>崩溃时把完整堆栈写入 filesDir/crash/crash_*.txt，并把最近一次崩溃原因
 * 保存到 last_crash.txt；下次启动时 App 会弹出对话框显示错误原因，便于定位问题。</p>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private final Context appContext;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context) {
        this.appContext = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    public static File getCrashDir(Context context) {
        File dir = new File(context.getFilesDir(), "crash");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 最近一次崩溃信息文件（存在即表示上次是崩溃退出的）。 */
    public static File getLastCrashFile(Context context) {
        return new File(getCrashDir(context), "last_crash.txt");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            String stack = sw.toString();
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String header = "崩溃时间: " + time + "\n"
                    + "设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "Android: " + Build.VERSION.RELEASE + "\n"
                    + "线程: " + thread.getName() + "\n\n";

            File dir = getCrashDir(appContext);
            File detail = new File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            writeFile(detail, header + stack);
            // 保存精简摘要供下次启动弹窗显示（只取前若干行，避免过长）
            writeFile(getLastCrashFile(appContext), header + shorten(stack));
        } catch (Throwable ignored) {
        }

        // 交给系统默认处理器，保持原本的“已停止运行”行为
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    private static String shorten(String stack) {
        if (TextUtils.isEmpty(stack)) return "";
        String[] lines = stack.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(lines.length, 14);
        for (int i = 0; i < n; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static void writeFile(File file, String content) {
        FileWriter fw = null;
        try {
            fw = new FileWriter(file, false);
            fw.write(content);
            fw.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fw != null) {
                try { fw.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 在首页启动后调用：若存在上次崩溃记录，弹窗显示原因并清除标记。 */
    public static void showLastCrashIfAny(final Context context) {
        final File last = getLastCrashFile(context);
        if (last == null || !last.exists()) return;
        String content;
        try {
            byte[] data = new byte[(int) last.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(last);
            int len = fis.read(data);
            fis.close();
            content = new String(data, 0, Math.max(len, 0), "UTF-8");
        } catch (Throwable e) {
            content = "读取崩溃日志失败";
        }
        final String msg = content;
        // 先删除标记，避免每次启动都弹
        last.delete();
        try {
            new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        new AlertDialog.Builder(context)
                                .setTitle("上次运行崩溃原因（三好TV）")
                                .setMessage(msg)
                                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) { d.dismiss(); }
                                })
                                .setCancelable(true)
                                .show();
                    } catch (Throwable ignored) {
                    }
                }
            }, 1500);
        } catch (Throwable ignored) {
        }
    }
}
