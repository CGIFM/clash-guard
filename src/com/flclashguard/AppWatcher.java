package com.flclashguard;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * 「关联启动」的核心：检测 FlClash 是否在前台运行。
 *
 * 关联启动的现实：
 *   Android 没有「别的 App 一启动就通知我」的公开机制，但可以反着做——
 *   我们自己常驻，用 UsageStatsManager 查最近使用过的 App，
 *   一旦发现 FlClash 刚被打开，就立刻把自己拉到前台并开始监测。
 *
 * 权限：UsageStatsManager 需要用户在「设置 → 特殊权限 → 使用情况访问」
 *       里手动授予，没有权限时会退化成「进程是否存活」判断（没那么及时，但也能用）。
 */
final class AppWatcher {

    private static final String TAG = "FlClashGuard";
    static final String CLASH_PKG = "com.follow.clash";

    /** 是否有「使用情况访问」权限（能精确判断前后台） */
    static boolean hasUsagePermission(Context c) {
        if (Build.VERSION.SDK_INT < 21) return true;
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    c.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** FlClash 现在是否在前台（需要使用情况权限） */
    static boolean isClashForeground(Context c) {
        if (Build.VERSION.SDK_INT < 21) {
            return isClashRunning(c);
        }
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return isClashRunning(c);

            long now = System.currentTimeMillis();
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 60 * 1000, now);
            if (list == null || list.isEmpty()) return isClashRunning(c);

            UsageStats latest = null;
            for (UsageStats u : list) {
                if (CLASH_PKG.equals(u.getPackageName())) {
                    if (latest == null || u.getLastTimeUsed() > latest.getLastTimeUsed()) latest = u;
                }
            }
            if (latest == null) return false;
            // 最近 10 秒内用过 = 视为刚打开
            return (now - latest.getLastTimeUsed()) < 10000;
        } catch (Throwable t) {
            Log.w(TAG, "查询前台失败", t);
            return isClashRunning(c);
        }
    }

    /**
     * FlClash 的 VPN 服务是否在运行 —— 这是判断「用户点了通知栏开关没有」最准的方式。
     *
     * 为什么比查进程准：FlClash 进程可能常驻（它自己也做保活），
     * 但 VPN 服务只在用户真正开代理时才存在。查这个就不会误判。
     */
    static boolean isVpnServiceRunning(Context c) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            // Android 8+ 这个方法只返回自己的进程，所以用服务列表判断
            android.app.ActivityManager.RunningServiceInfo[] arr = null;
            try {
                java.util.List<android.app.ActivityManager.RunningServiceInfo> list =
                        am.getRunningServices(200);
                if (list != null) {
                    for (android.app.ActivityManager.RunningServiceInfo si : list) {
                        if (si.service != null
                                && CLASH_PKG.equals(si.service.getPackageName())
                                && si.service.getClassName() != null
                                && si.service.getClassName().contains("VpnService")) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否有活动的 VPN 连接（含其他 App 的） */
    static boolean hasActiveVpn(Context c) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        String iface = lp.getInterfaceName();
                        if (iface != null && (iface.startsWith("tun") || iface.startsWith("ppp"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** FlClash 进程是否存活（不需要特殊权限） */
    static boolean isClashRunning(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo p : list) {
                    if (CLASH_PKG.equals(p.processName)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }
}
