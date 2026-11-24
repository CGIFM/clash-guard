package com.flclashguard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Shizuku 集成：用高权限执行 shell 命令（uid 2000），从而静默关闭 FlClash。
 *
 * 为什么需要它：
 *   普通 App 无法执行 `am force-stop`（沙箱限制），
 *   但 Shizuku 以 shell(uid 2000) 身份运行，可以。实测这条命令能关闭
 *   FlClash 的 VPN 且**不会跳转到任何界面**。
 *
 * 实现方式：反射调用 Shizuku 的 API，避免引入编译期依赖（本项目是纯命令行构建）。
 */
final class ShizukuHelper {

    private static final String TAG = "FlClashGuard";
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    /** Shizuku 是否已安装 */
    static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 服务是否在运行且已授权 */
    static boolean isReady() {
        try {
            Class<?> sz = Class.forName("rikka.shizuku.Shizuku");
            Method ping = sz.getMethod("pingBinder");
            Object ok = ping.invoke(null);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已经获得 Shizuku 授权 */
    static boolean hasPermission() {
        try {
            Class<?> sz = Class.forName("rikka.shizuku.Shizuku");
            Method check = sz.getMethod("checkSelfPermission");
            Object r = check.invoke(null);
            return r instanceof Integer && ((Integer) r) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 请求授权。会弹出 Shizuku 的授权对话框。
     */
    static void requestPermission(final Runnable onResult) {
        try {
            Class<?> sz = Class.forName("rikka.shizuku.Shizuku");
            Method req = sz.getMethod("requestPermission", int.class);
            req.invoke(null, 0);
            // 授权是异步的，稍等再查
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    if (onResult != null) onResult.run();
                }
            }, 3000);
        } catch (Throwable t) {
            Log.w(TAG, "请求 Shizuku 授权失败", t);
            if (onResult != null) onResult.run();
        }
    }

    /**
     * 用 Shizuku 执行 shell 命令。
     *
     * 走 Shizuku.newProcess(cmd, env, dir) —— 这是它对外暴露的标准方式，
     * 返回一个 Process，我们可以读它的输出判断成功与否。
     */
    static String exec(String cmd) {
        java.lang.Process proc = null;
        try {
            Class<?> sz = Class.forName("rikka.shizuku.Shizuku");
            Method newProcess = sz.getMethod("newProcess",
                    String[].class, String[].class, String.class);
            String[] cmds = {"sh", "-c", cmd};
            Object o = newProcess.invoke(null, cmds, null, null);
            if (!(o instanceof java.lang.Process)) return null;
            proc = (java.lang.Process) o;

            StringBuilder out = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            r.close();
            try { proc.waitFor(); } catch (InterruptedException ignored) { }
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "执行命令失败: " + cmd, t);
            return null;
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 静默关闭 FlClash（不跳转界面） */
    static boolean killClash(Context c) {
        String out = exec("am force-stop com.follow.clash");
        // 命令本身不返回内容，用进程是否消失来判断
        for (int i = 0; i < 10; i++) {
            if (!AppWatcher.isClashRunning(c)) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        }
        return !AppWatcher.isClashRunning(c);
    }

    /** 静默启动 FlClash（不跳转界面） */
    static boolean startClash(Context c) {
        exec("am start-service -n com.follow.clash/.TileService 2>/dev/null; "
                + "am start -n com.follow.clash/.QuickActionActivity -a com.follow.clash.action.START");
        for (int i = 0; i < 20; i++) {
            if (AppWatcher.isVpnServiceRunning(c)) return true;
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        return AppWatcher.isVpnServiceRunning(c);
    }
}
