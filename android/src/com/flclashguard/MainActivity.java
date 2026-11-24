package com.flclashguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 线路守卫主界面。
 *
 * 面板是 assets/index.html（本地文件，不依赖任何服务），
 * 通过 JavascriptInterface 和原生 Guard 通话：
 *   getState()      → 当前完整状态 JSON
 *   getSettings()   → 设置 JSON
 *   saveSettings(s) → 保存
 *   checkNow()      → 立刻检测一轮
 */
public class MainActivity extends Activity {

    private WebView web;
    private final List<String> events = new ArrayList<String>();
    private static final SimpleDateFormat HHMM = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        App.ensureChannel(this);
        WatchService.start(this);
        KeepAliveJob.schedule(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFF0B0F17);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        // 关键：没有 WebChromeClient，JS 的 confirm()/alert() 会静默返回 false，
        // 表现就是「点了按钮没反应」。这里补上，让确认框真正弹出来。
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("知道了", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .show();
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "GuardBridge");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F17);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        try {
            getWindow().setStatusBarColor(0xFF0B0F17);
            getWindow().setNavigationBarColor(0xFF0B0F17);
        } catch (Throwable ignored) { }

        web.loadUrl("file:///android_asset/index.html");

        SharedPreferences sp = App.prefs(this);
        if (!sp.getBoolean("inited", false)) {
            sp.edit()
                    .putBoolean("inited", true)
                    .putBoolean("autostart", true)
                    .putBoolean("notifyOnStart", true)
                    .putBoolean("autoSwitch", true)
                    .putInt("intervalSec", 6)
                    .putInt("clashPort", App.DEFAULT_CLASH_PORT)
                    .putString("clashSecret", App.DEFAULT_CLASH_SECRET)
                    .apply();
            Toast.makeText(this, "线路守卫已启动：Clash 打开时会通知你", Toast.LENGTH_LONG).show();
        }
        // 老版本升级上来时，如果密钥是空的，补上默认值（用户手动填过的不覆盖）
        if (sp.getString("clashSecret", "").length() == 0
                && App.DEFAULT_CLASH_SECRET.length() > 0
                && !sp.getBoolean("secret_touched", false)) {
            sp.edit().putString("clashSecret", App.DEFAULT_CLASH_SECRET).apply();
        }
    }

    /** JS ↔ 原生 的桥 */
    private class Bridge {

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                Guard g = WatchService.guard;
                SharedPreferences sp = App.prefs(MainActivity.this);

                o.put("clashAlive", WatchService.cachedAlive || sp.getBoolean("clash_alive", false));
                o.put("roundCount", WatchService.roundCount);
                o.put("lastRoundAt", WatchService.lastRoundAt);
                o.put("lastRoundMsg", WatchService.lastRoundMsg == null ? "" : WatchService.lastRoundMsg);
                o.put("serviceAlive", WatchService.alive);
                o.put("intervalSec", sp.getInt("intervalSec", 6));
                o.put("latencyWarn", sp.getInt("latencyWarn", 4000));

                if (g != null) {
                    o.put("tickCount", g.tickCount);
                    o.put("allOk", g.allOk);
                    o.put("latency", g.latency);
                    o.put("failStreak", g.failStreak);
                    o.put("currentNode", g.currentNode == null ? "" : g.currentNode);
                    o.put("currentGroup", g.currentGroup == null ? "" : g.currentGroup);
                    o.put("lastSwitchFrom", g.lastSwitchFrom == null ? "" : g.lastSwitchFrom);
                    o.put("lastSwitchTo", g.lastSwitchTo == null ? "" : g.lastSwitchTo);
                    // 注意：不能在这里调 api.version()，那是一次网络请求，UI 线程会卡住
                    o.put("apiVersion", WatchService.cachedVersion);

                    JSONArray arr = new JSONArray();
                    synchronized (g.services) {
                        for (Guard.ServiceResult r : g.services) {
                            JSONObject so = new JSONObject();
                            so.put("id", r.id);
                            so.put("label", r.label);
                            so.put("ok", r.ok);
                            so.put("ms", r.ms);
                            so.put("error", r.error == null ? "" : r.error);
                            arr.put(so);
                        }
                    }
                    o.put("services", arr);

                    // 全部节点（含不通的，绝不隐藏）
                    JSONArray nodes = new JSONArray();
                    List<ClashApi.Node> snapshot;
                    synchronized (g.allNodes) {
                        snapshot = new ArrayList<ClashApi.Node>(g.allNodes);
                    }
                    int okCount = 0;
                    for (ClashApi.Node n : snapshot) {
                        JSONObject no = new JSONObject();
                        no.put("name", n.name);
                        no.put("via", n.via == null ? "" : n.via);
                        no.put("delay", n.delay);          // -1 = 不通 / 未测
                        no.put("ok", n.delay > 0);
                        no.put("current", n.current);
                        nodes.put(no);
                        if (n.delay > 0) okCount++;
                    }
                    o.put("nodes", nodes);
                    o.put("nodeTotal", snapshot.size());
                    o.put("nodeOk", okCount);
                    o.put("nodeBad", snapshot.size() - okCount);
                    o.put("lastScanAt", g.lastScanAt);
                    o.put("lastScanSummary", g.lastScanSummary == null ? "" : g.lastScanSummary);

                    // DNS 状态
                    o.put("dnsStatus", g.dnsStatus);
                    o.put("dnsDetail", g.dnsDetail);

                    // 局域网共享信息
                    String lanIp = getLanIp();
                    int lport = App.prefs(MainActivity.this).getInt("proxyPort", 7890);
                    o.put("lanIp", lanIp);
                    o.put("lanProxyAddr", lanIp.length() > 0 ? (lanIp + ":" + lport) : "");
                    o.put("lanProxyOpen", isLanProxyOpen());
                    o.put("lanPageUrl", lanIp.length() > 0
                            ? ("http://" + lanIp + ":" + LanServer.PORT + "/p") : "");
                    o.put("lanPageOn", LanServer.isRunning());
                    o.put("localPort", lport);

                    // 出口地区（Gemini 能不能用的关键）
                    o.put("exitIp", g.exitIp == null ? "" : g.exitIp);
                    o.put("exitCountry", g.exitCountry == null ? "" : g.exitCountry);
                    o.put("geminiAllowed", g.geminiAllowedInCurrentRegion());
                    o.put("geoError", g.geoError == null ? "" : g.geoError);
                } else {
                    o.put("tickCount", 0);
                    o.put("allOk", false);
                    o.put("latency", -1);
                    o.put("services", new JSONArray());
                    o.put("currentNode", "");
                    o.put("currentGroup", "");
                }

                JSONArray evs = new JSONArray();
                synchronized (events) {
                    for (String e : events) evs.put(new JSONObject(e));
                }
                o.put("events", evs);

                return o.toString();
            } catch (Throwable t) {
                try {
                    JSONObject e = new JSONObject();
                    e.put("bridgeError", t.getClass().getSimpleName() + ": " + t.getMessage());
                    return e.toString();
                } catch (Throwable ignored) { return "{}"; }
            }
        }

        @JavascriptInterface
        public String getSettings() {
            SharedPreferences sp = App.prefs(MainActivity.this);
            try {
                JSONObject o = new JSONObject();
                o.put("intervalSec", sp.getInt("intervalSec", 6));
                o.put("notifyOnStart", sp.getBoolean("notifyOnStart", true));
                o.put("autoSwitch", sp.getBoolean("autoSwitch", true));
                o.put("autostart", sp.getBoolean("autostart", true));
                o.put("assocStart", sp.getBoolean("assocStart", true));
                o.put("stopWithClash", sp.getBoolean("stopWithClash", true));
                o.put("clashSecret", sp.getString("clashSecret", ""));
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            try {
                JSONObject o = new JSONObject(json);
                App.prefs(MainActivity.this).edit()
                        .putInt("intervalSec", o.optInt("intervalSec", 6))
                        .putBoolean("notifyOnStart", o.optBoolean("notifyOnStart", true))
                        .putBoolean("autoSwitch", o.optBoolean("autoSwitch", true))
                        .putBoolean("autostart", o.optBoolean("autostart", true))
                        .putBoolean("assocStart", o.optBoolean("assocStart", true))
                        .putBoolean("stopWithClash", o.optBoolean("stopWithClash", true))
                        .putString("clashSecret", o.optString("clashSecret", ""))
                        .putBoolean("secret_touched", true)
                        .apply();
                WatchService.start(MainActivity.this);
                addEvent("info", "设置已保存");
            } catch (Throwable ignored) { }
        }

        /**
         * 一键启动 FlClash 的代理服务 —— 不跳转、不显示 FlClash 界面。
         *
         * 关键发现：`com.follow.clash.action.START` 不是广播，而是
         * `com.follow.clash/.QuickActionActivity` 的 intent-filter。
         * 这是个「快捷操作页」，调起来之后它会自己启动 VPN 服务再立刻关掉自己，
         * 所以用户完全看不到界面跳转。
         *
         * 依次尝试：QuickActionActivity(START) → QuickActionActivity(TOGGLE) → TileService
         */
        @JavascriptInterface
        public String startClashService() {
            String[] actions = {
                    "com.follow.clash.action.START",
                    "com.follow.clash.action.TOGGLE",
            };
            StringBuilder log = new StringBuilder();

            for (String a : actions) {
                try {
                    Intent i = new Intent(a);
                    i.setPackage("com.follow.clash");
                    i.setComponent(new android.content.ComponentName(
                            "com.follow.clash", "com.follow.clash.QuickActionActivity"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_HISTORY);
                    MainActivity.this.startActivity(i);
                    log.append("ok:").append(a.replace("com.follow.clash.action.", "")).append(" ");
                    break; // 成功发出一个就够了
                } catch (Throwable t) {
                    log.append("fail:").append(t.getMessage()).append(" ");
                }
            }

            return log.toString().trim();
        }

        /**
         * 关闭代理。
         *
         * 为什么要做「先判断再动作 + 事后验证」：
         * FlClash 的 QuickActionActivity 响应 START/TOGGLE/STOP 三个 action，
         * 但实测 STOP 有时只是把界面收掉、VPN 还连着。
         * 所以这里：先看当前状态 → 发合适的 action → 等 2 秒验证 → 没关上就换 TOGGLE 再试。
         */
        @JavascriptInterface
        public String stopClashService() {
            boolean wasUp = isVpnUpNow();
            if (!wasUp) {
                addEvent("info", "代理本来就是关着的");
                return "already-off";
            }

            // ── 招 0：用 Shizuku 执行 am force-stop（最干净，实测有效，不跳转）──
            if (ShizukuHelper.isReady() && ShizukuHelper.hasPermission()) {
                if (ShizukuHelper.killClash(MainActivity.this)) {
                    addEvent("ok", "代理已关闭（Shizuku 静默）");
                    return "ok:shizuku";
                }
                addEvent("info", "Shizuku 关闭没成功，继续尝试其他方式");
            }

            // ── 招 1：直接停 VpnService（不跳转任何界面）──
            // 这是最干净的方式：stopService 是静默的，不会把 FlClash 拉到前台。
            try {
                Intent stop = new Intent();
                stop.setComponent(new android.content.ComponentName(
                        "com.follow.clash", "com.follow.clash.service.VpnService"));
                boolean ok = stopService(stop);
                addEvent("info", "stopService 返回 " + ok);
            } catch (Throwable t) {
                addEvent("info", "stopService 失败：" + t.getMessage());
            }
            if (waitForVpn(false, 2500)) {
                addEvent("ok", "代理已关闭");
                return "ok:erpc";
            }

            // ── 招 2：结束 FlClash 进程（VPN 会随之断开，不跳界面）──
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses("com.follow.clash");
                }
                // 注意：App 沙箱内无法执行 am force-stop，这里只靠 killBackgroundProcesses
            } catch (Throwable ignored) { }
            if (waitForVpn(false, 2500)) {
                addEvent("ok", "代理已关闭（结束进程）");
                return "ok:kill";
            }

            // ── 招 3（会跳转，作为最后手段）：QuickActionActivity STOP ──
            addEvent("warn", "两种静默方式都没关掉，改用快捷键方式（会短暂切到 FlClash）");
            fireQuickAction("com.follow.clash.action.STOP");
            if (waitForVpn(false, 2500)) {
                addEvent("ok", "代理已关闭（快捷键）");
                return "ok:stop";
            }

            addEvent("error", "关不掉代理，请去 FlClash 里手动关闭");
            return "failed";
        }

        /** 启动代理（一键：开代理 + 开监听） */
        @JavascriptInterface
        public String startClashAndMonitor() {
            boolean wasUp = isVpnUpNow();
            if (!wasUp) {
                // 优先用 Shizuku 静默启动（不跳界面）
                if (ShizukuHelper.isReady() && ShizukuHelper.hasPermission()) {
                    if (ShizukuHelper.startClash(MainActivity.this)) {
                        addEvent("ok", "代理已启动（Shizuku 静默）");
                    } else {
                        fireQuickAction("com.follow.clash.action.START");
                    }
                } else {
                    fireQuickAction("com.follow.clash.action.START");
                }
                if (!waitForVpn(true, 4000)) {
                    // START 没成，试 TOGGLE
                    fireQuickAction("com.follow.clash.action.TOGGLE");
                    waitForVpn(true, 4000);
                }
            }
            // 不管代理是不是本来就开着，都把监听拉起来
            WatchService.guard = null;
            WatchService.start(MainActivity.this);
            KeepAliveJob.schedule(MainActivity.this);
            addEvent("info", wasUp ? "代理已在运行，已开启监听" : "已开启代理和监听");
            return isVpnUpNow() ? "ok" : "no-vpn";
        }

        /** 发一个 FlClash 的快捷动作 */
        private void fireQuickAction(String action) {
            try {
                Intent i = new Intent(action);
                i.setPackage("com.follow.clash");
                i.setComponent(new android.content.ComponentName(
                        "com.follow.clash", "com.follow.clash.QuickActionActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);
                MainActivity.this.startActivity(i);
            } catch (Throwable t) {
                addEvent("error", "动作失败：" + t.getMessage());
            }
        }

        /** 当前 VPN/代理是否真的在跑 */
        private boolean isVpnUpNow() {
            try {
                return AppWatcher.isVpnServiceRunning(MainActivity.this)
                        || AppWatcher.hasActiveVpn(MainActivity.this);
            } catch (Throwable t) {
                return false;
            }
        }

        /** 等状态变成期望值；超时返回 false */
        private boolean waitForVpn(boolean want, long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                if (isVpnUpNow() == want) return true;
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            }
            return isVpnUpNow() == want;
        }

        /** 只打开 FlClash 界面（不启服务） */
        @JavascriptInterface
        public String openClashUi() {
            String[] candidates = {
                    "com.follow.clash/.MainActivity",
                    "com.follow.clash/com.follow.clash.MainActivity",
            };
            for (String c : candidates) {
                try {
                    Intent i = new Intent();
                    i.setComponent(android.content.ComponentName.unflattenFromString(c));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    MainActivity.this.startActivity(i);
                    return "ok";
                } catch (Throwable ignored) { }
            }
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.follow.clash");
                if (i != null) {
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    MainActivity.this.startActivity(i);
                    return "ok";
                }
            } catch (Throwable ignored) { }
            return "notfound";
        }

        /** 代理服务是否在跑（9090 通不通） */
        @JavascriptInterface
        public boolean isClashServiceUp() {
            try {
                Guard g = WatchService.guard;
                if (g == null) {
                    android.content.SharedPreferences sp = App.prefs(MainActivity.this);
                    g = new Guard("127.0.0.1", sp.getInt("clashPort", 9090),
                            sp.getString("clashSecret", ""));
                }
                return g.api.alive();
            } catch (Throwable t) {
                return false;
            }
        }

        /** FlClash 是否在运行（供按钮显示文案） */
        @JavascriptInterface
        public boolean isClashRunning() {
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am == null) return false;
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                if (list != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
                        if ("com.follow.clash".equals(p.processName)) return true;
                    }
                }
            } catch (Throwable ignored) { }
            return false;
        }

        /** Shizuku 是否可用 */
        @JavascriptInterface
        public boolean shizukuReady() {
            return ShizukuHelper.isReady() && ShizukuHelper.hasPermission();
        }

        /** Shizuku 是否装了 */
        @JavascriptInterface
        public boolean shizukuInstalled() {
            return ShizukuHelper.isInstalled(MainActivity.this);
        }

        /** 申请 Shizuku 授权 */
        @JavascriptInterface
        public void requestShizuku() {
            ShizukuHelper.requestPermission(new Runnable() {
                @Override public void run() {
                    boolean ok = ShizukuHelper.hasPermission();
                    addEvent(ok ? "ok" : "error", ok ? "Shizuku 已授权" : "Shizuku 授权未完成");
                }
            });
        }

        /** 手动启动局域网页 */
        @JavascriptInterface
        public void startLanPage() {
            try { LanServer.start(MainActivity.this); } catch (Throwable ignored) { }
        }

        /** 生成二维码（返回 data:image/png;base64,...），内容是可点的配置页链接 */
        @JavascriptInterface
        public String makeQrDataUrl(String text) {
            try {
                if (text == null || text.length() == 0) return "";
                QrCode qr = new QrCode(text);
                int n = qr.size();
                int quiet = 2;                       // 静区，扫码必须留
                int scale = Math.max(4, 480 / (n + quiet * 2));
                int dim = (n + quiet * 2) * scale;
                int[] px = new int[dim * dim];
                int white = 0xFFFFFFFF, black = 0xFF000000;
                for (int i = 0; i < px.length; i++) px[i] = white;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        if (!qr.dark(x, y)) continue;
                        int bx = (x + quiet) * scale, by = (y + quiet) * scale;
                        for (int dy = 0; dy < scale; dy++) {
                            int row = (by + dy) * dim;
                            for (int dx = 0; dx < scale; dx++) px[row + bx + dx] = black;
                        }
                    }
                }
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        px, dim, dim, android.graphics.Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                String b64 = android.util.Base64.encodeToString(
                        bos.toByteArray(), android.util.Base64.NO_WRAP);
                return "data:image/png;base64," + b64;
            } catch (Throwable t) {
                return "";
            }
        }

        /** 本机的局域网 IP（非 127.0.0.1），用于告诉别的设备填哪个地址 */
        @JavascriptInterface
        public String getLanIp() {
            try {
                java.util.Enumeration<java.net.NetworkInterface> nis =
                        java.net.NetworkInterface.getNetworkInterfaces();
                java.util.List<String> candidates = new ArrayList<String>();
                while (nis != null && nis.hasMoreElements()) {
                    java.net.NetworkInterface ni = nis.nextElement();
                    if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                    String name = ni.getName();
                    // 优先 wlan 网段，跳过移动数据（rmnet）和 VPN（tun/vgate）
                    if (name.startsWith("tun") || name.startsWith("ppp")
                            || name.startsWith("vgate") || name.startsWith("rmnet")) continue;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        java.net.InetAddress a = ia.getAddress();
                        if (a == null || a.isLoopbackAddress()) continue;
                        String ip = a.getHostAddress();
                        if (ip == null || ip.contains(":")) continue; // 只要 IPv4
                        if (ip.startsWith("169.254.")) continue;
                        candidates.add(ip);
                    }
                }
                if (!candidates.isEmpty()) return candidates.get(0);
            } catch (Throwable ignored) { }
            return "";
        }

        /** 本机网段前缀，例如 172.16.30. */
        @JavascriptInterface
        public String getLanSubnet() {
            String ip = getLanIp();
            int idx = ip.lastIndexOf('.');
            return idx > 0 ? ip.substring(0, idx + 1) : "";
        }

        /** 检测局域网代理端口是否真的能从外部访问 */
        @JavascriptInterface
        public boolean isLanProxyOpen() {
            try {
                android.content.SharedPreferences sp = App.prefs(MainActivity.this);
                int port = sp.getInt("proxyPort", 7890);
                String ip = getLanIp();
                if (ip.length() == 0) return false;
                java.net.Socket sock = new java.net.Socket();
                sock.connect(new java.net.InetSocketAddress(ip, port), 800);
                sock.close();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 是否有「使用情况访问」权限（关联启动需要） */
        @JavascriptInterface
        public boolean hasUsagePermission() {
            return AppWatcher.hasUsagePermission(MainActivity.this);
        }

        /** 跳到系统设置，让用户授予使用情况访问权限 */
        @JavascriptInterface
        public void openUsageSettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                MainActivity.this.startActivity(i);
                addEvent("info", "请在列表里找到「线路守卫」并打开开关");
            } catch (Throwable t) {
                addEvent("error", "打不开设置：" + t.getMessage());
            }
        }

        /** 申请「不受电池优化限制」（国产 ROM 保活关键） */
        @JavascriptInterface
        public void requestIgnoreBattery() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent i = new Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(android.net.Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        MainActivity.this.startActivity(i);
                        addEvent("info", "请点「允许」让系统不要杀后台");
                        return;
                    }
                }
                addEvent("ok", "已经不受电池优化限制");
            } catch (Throwable t) {
                addEvent("error", "申请失败：" + t.getMessage());
            }
        }

        /** 当前是否已加入电池优化白名单 */
        @JavascriptInterface
        public boolean isIgnoringBattery() {
            try {
                if (android.os.Build.VERSION.SDK_INT < 23) return true;
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Throwable t) {
                return false;
            }
        }

        /** 手动触发全量节点测速 */
        @JavascriptInterface
        public void scanNodes() {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Guard g = WatchService.guard;
                        if (g == null) return;
                        g.scanAllNodes();
                        g.checkDns();
                        addEvent("info", g.lastScanSummary == null ? "测速完成" : g.lastScanSummary);
                    } catch (Throwable t) {
                        addEvent("error", "测速失败：" + t.getMessage());
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void reloadGuard() {
            WatchService.guard = null;   // 下一轮会用新设置重建
            WatchService.start(MainActivity.this);
            addEvent("info", "正在按当前设置重新连接…");
        }

        @JavascriptInterface
        public void checkNow() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Guard g = WatchService.guard;
                    if (g == null) {
                        android.content.SharedPreferences sp = App.prefs(MainActivity.this);
                        g = new Guard("127.0.0.1", sp.getInt("clashPort", 9090),
                                sp.getString("clashSecret", ""));
                        WatchService.guard = g;
                    }
                    String msg = g.tick();
                    addEvent(msg == null ? "ok" : "info", msg == null ? "手动检测：全部正常" : msg);
                }
            }).start();
        }
    }

    private void addEvent(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("time", HHMM.format(new Date()));
            o.put("text", text);
            synchronized (events) {
                events.add(o.toString());
                while (events.size() > 60) events.remove(0);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        // 退出界面但守护继续跑
        moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        WatchService.start(this);
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.getRepeatCount() == 3) {
            if (web != null) web.reload();
            Toast.makeText(this, "已重载面板", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
