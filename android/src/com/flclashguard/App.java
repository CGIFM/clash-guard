package com.flclashguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 通知相关的小工具：建通道、发通知、读设置 */
final class App {

    static final String CH_MAIN = "guard_main";
    static final String CH_WATCH = "guard_watch";
    static final String PREFS = "guard";

    // 打包时由 build.sh 用小脚本改这两行（或用环境变量注入），装完就自带正确值
    static final int DEFAULT_CLASH_PORT = 9090;
    // 留空：密钥由用户在 App 设置页填写，绝不硬编码（构建时可从 local.properties 注入）
    static final String DEFAULT_CLASH_SECRET = "";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 8.0+ 必须先建通道，否则通知不会显示 */
    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel main = new NotificationChannel(
                CH_MAIN, "FlClash 状态提醒", NotificationManager.IMPORTANCE_HIGH);
        main.setDescription("Clash 启动/退出时提醒你监测是否已打开");
        main.enableVibration(true);
        nm.createNotificationChannel(main);

        NotificationChannel watch = new NotificationChannel(
                CH_WATCH, "守护运行中", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("常驻状态栏提示，可隐藏");
        watch.enableVibration(false);
        watch.setShowBadge(false);
        nm.createNotificationChannel(watch);
    }

    /** 取得本机局域网 IPv4（给局域网页用） */
    static String lanIp(android.content.Context c) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("vgate") || name.startsWith("rmnet")) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a == null || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.contains(":") || ip.startsWith("169.254.")) continue;
                    return ip;
                }
            }
        } catch (Throwable ignored) { }
        return "0.0.0.0";
    }

    /** 通知点击后打开面板 */
    static PendingIntent openIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    /** 发一条普通通知（带点击打开面板） */
    static void notify(Context c, int id, String title, String text, boolean ongoing) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, ongoing ? CH_WATCH : CH_MAIN);
            } else {
                b = new Notification.Builder(c);
            }

            b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(!ongoing)
                    .setOngoing(ongoing)
                    .setContentIntent(openIntent(c))
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);

            if (Build.VERSION.SDK_INT >= 16) {
                b.setStyle(new Notification.BigTextStyle().bigText(text));
            }
            if (!ongoing) {
                b.setDefaults(Notification.DEFAULT_ALL);
            }

            nm.notify(id, b.build());
        } catch (Throwable t) {
            // 没有通知权限时静默失败，绝不能崩
        }
    }
}
