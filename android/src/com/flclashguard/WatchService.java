package com.flclashguard;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 前台守护服务 —— 真正干活的地方。
 *
 * 每 intervalMs（默认 6 秒）跑一轮 Guard.tick()：
 *   检测 → 不通就测速择优 → 自动切节点
 *
 * 通知策略（你要的「打开 Clash 时告诉我监测开了」）：
 *   - Clash 一上线   → 发「FlClash 已启动 · 监测已开始」
 *   - Clash 一退出   → 发「FlClash 已退出 · 监测已暂停」
 *   - 发生自动切换    → 发「已自动切换到 X 节点」
 */
public class WatchService extends Service {

    private static final String TAG = "FlClashGuard";
    static final String ACTION_CLASH_DOWN = "com.flclashguard.CLASH_DOWN";
    static final String ACTION_CLASH_UP = "com.flclashguard.CLASH_UP";
    static final String ACTION_TOGGLE = "com.flclashguard.TOGGLE";

    static final int ID_RUNNING = 1000;
    static final int ID_ONLINE = 1001;
    static final int ID_OFFLINE = 1002;
    static final int ID_SWITCH = 1003;

    static Guard guard;
    static volatile boolean alive = false;
    static volatile long lastRoundAt = 0;
    static volatile String lastRoundMsg = null;
    static volatile int roundCount = 0;
    static volatile String lastLanIp = "";
    static volatile long lastClashDownAt = 0;
    static volatile long lastFullScanAt = 0;
    static volatile long lastDnsCheckAt = 0;
    static volatile String cachedVersion = "";
    static volatile boolean cachedAlive = false;

    private Handler handler;
    private volatile boolean stopped = false;
    private long lastCooldownNotify = 0;
    // 后台线程池：不依赖主线程 Looper，界面在不在前台都能按时跑
    private java.util.concurrent.ScheduledExecutorService scheduler;
    private java.util.concurrent.ScheduledFuture<?> scheduledFuture;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            try {
                oneRound();
                roundCount++;
                lastRoundAt = System.currentTimeMillis();
            } catch (Throwable t) {
                Log.w(TAG, "round failed", t);
                lastRoundMsg = "检测异常：" + t.getMessage();
            }
        }
    };

    /**
     * 彻底退出：停前台服务、取消保活作业、结束进程。
     *
     * 为什么要杀进程而不是只 stopSelf()：
     *   START_STICKY + JobScheduler.setPersisted 会把服务重新拉起来，
     *   用户就会看到「关掉 Clash 后它又自己活了」。所以这里必须一路关到底。
     */
    private void deepExit(long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 1. 取消保活作业，否则系统会把它叫醒
                    android.app.job.JobScheduler js =
                            (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
                    if (js != null) js.cancel(KeepAliveJob.JOB_ID);

                    // 2. 标记为「用户主动关闭」，让 START_STICKY 别复活
                    stopped = true;
                    if (scheduledFuture != null) scheduledFuture.cancel(true);
                    if (scheduler != null) scheduler.shutdownNow();

                    // 3. 撤掉常驻通知
                    stopForeground(true);
                    stopSelf();

                    // 4. 最后终止进程（这一步才真正零耗电）
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override public void run() {
                                    try {
                                        android.os.Process.killProcess(android.os.Process.myPid());
                                    } catch (Throwable ignored) { }
                                }
                            }, 400);
                } catch (Throwable ignored) { }
            }
        }, delayMs);
    }

    /**
     * 待命（Clash 没开）时的探测间隔。
     *
     * 用户关掉 Clash 往往马上又开，所以头 60 秒保持灵敏（5 秒一次）；
     * 超过 60 秒还没开，说明是真的不用了 → 降到 60 秒一次，几乎不耗电。
     * 另外每次探测只发一个 2 秒超时的本地连接，代价极小。
     */
    private long idleDelay() {
        // 深退模式下不会走到这里（进程已经没了），这里只服务「待命模式」
        long idle = System.currentTimeMillis() - lastClashDownAt;
        if (lastClashDownAt == 0) return 4000;
        return idle < 60000 ? 4000 : 15000;
    }

    private void scheduleNext(long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) return;
        if (scheduledFuture != null) scheduledFuture.cancel(false);
        scheduledFuture = scheduler.schedule(tick, Math.max(1000, delayMs),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 一轮：先看 Clash 在不在，在就干活 */
    private void oneRound() {
        SharedPreferences sp = App.prefs(this);
        int clashPort = sp.getInt("clashPort", 9090);
        String secret = sp.getString("clashSecret", "");

        if (guard == null) {
            guard = new Guard("127.0.0.1", clashPort, secret);
        }
        guard.attach(getApplicationContext());
        guard.intervalMs = sp.getInt("intervalSec", 6) * 1000;
        guard.autoSwitch = sp.getBoolean("autoSwitch", true);
        guard.proxyPort = sp.getInt("proxyPort", 7890);
        guard.latencyWarnMs = sp.getInt("latencyWarn", 4000);
        guard.failThreshold = sp.getInt("failThreshold", 2);
        // 默认 direct：系统的 VPN/TUN 已经接管全部流量，再套一层 7890 属于双重代理，
        // 实测会不稳定（有时成功有时 503）。需要时可手动切回 proxy。
        guard.channel = sp.getBoolean("viaProxy", false) ? "proxy" : "direct";

        // ── 关联启动 ──
        // 我们自己常驻，靠「FlClash 是否刚被打开」来触发，效果等同关联启动
        // 判断「用户到底开没开代理」：优先看 VPN 服务 + tun 接口，其次才看进程。
        // 只看进程会误判 —— FlClash 本身会常驻，进程在但代理是关的。
        boolean vpnSvc = AppWatcher.isVpnServiceRunning(this);
        boolean vpnActive = AppWatcher.hasActiveVpn(this);
        boolean clashPidAlive = AppWatcher.isClashRunning(this);
        boolean clashFg = AppWatcher.isClashForeground(this);
        boolean clashWasFg = sp.getBoolean("clash_fg", false);
        boolean assocOn = sp.getBoolean("assocStart", true);
        if (assocOn && clashFg && !clashWasFg) {
            // 用户刚打开了 FlClash
            if (sp.getBoolean("notifyOnStart", true)) {
                App.notify(this, ID_ONLINE, "FlClash 已打开 · 监测启动",
                        "线路守卫已关联启动，正在检测 ChatGPT / OpenAI / Gemini / Claude / Google", false);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { scheduleNext(500); }  // 立刻先跑一轮
            }, 200);
        }
        sp.edit().putBoolean("clash_fg", clashFg).putBoolean("clash_pid", clashPidAlive).apply();

        boolean clashUp = vpnSvc || vpnActive || guard.api.alive();
        // 兜底：如果前三个都不满足但进程刚被打开，也算「起来中」
        if (!clashUp && clashFg && clashPidAlive) clashUp = true;
        cachedAlive = clashUp;
        // 关键：默认值必须是「和当前相反」，否则首次判断 clashUp != prev 永远为 false，
        // 标志永远不写、通知也永远不发。这里用「不在存储里就当它是未知」的方式处理。
        boolean known = sp.contains("clash_alive");
        boolean prev = sp.getBoolean("clash_alive", !clashUp);

        if (!known) {
            // 第一次跑：不管 Clash 在不在，都把状态落盘
            sp.edit().putBoolean("clash_alive", clashUp).apply();
            if (clashUp) lastClashDownAt = 0;
            if (clashUp && sp.getBoolean("notifyOnStart", true)) {
                App.notify(this, ID_ONLINE, "监测已开始",
                        "FlClash 正在运行，已开始监测 ChatGPT / OpenAI / Gemini / Claude / Google", false);
            }
            if (!clashUp) {
                refreshForeground();
                scheduleNext(idleDelay());
                return;
            }
        } else if (clashUp != prev) {
            sp.edit().putBoolean("clash_alive", clashUp).apply();
            boolean notifyOn = sp.getBoolean("notifyOnStart", true);
            if (clashUp) {
                lastClashDownAt = 0;
                if (notifyOn) {
                    App.notify(this, ID_ONLINE, "FlClash 已启动 · 监测已开始",
                            "每 " + (guard.intervalMs / 1000) + " 秒检查 ChatGPT / OpenAI / Gemini / Claude / Google，不通会自动切最快节点",
                            false);
                }
            } else {
                lastClashDownAt = System.currentTimeMillis();
                boolean deepStop = sp.getBoolean("stopWithClash", true);
                if (notifyOn) {
                    App.notify(this, ID_OFFLINE, "FlClash 已退出 · 监测已停止",
                            deepStop
                                ? "后台已完全退出，不耗电。下次打开 FlClash 会自动关联启动"
                                : "检测已停止，转入低频待命（每 2 分钟探一次），基本不耗电", false);
                }
                // 注意：这一步必须在 notifyOnStart 之外，否则用户关掉通知就不会退了
                if (deepStop) {
                    deepExit(notifyOn ? 2500 : 300);
                    return;
                }
            }
        }
        if (!clashUp) {
            refreshForeground();
            scheduleNext(idleDelay());
            return;
        }
        if (cachedVersion.length() == 0) {
            String v = guard.api.version();
            if (v != null) cachedVersion = v;
        }

        String msg = null;
        try {
            // 每轮先刷新节点列表（很轻，只是读 API），让 UI 能看到全部节点
            guard.refreshNodeList();

            // 每 60 秒做一次「全节点测速」，结果包括不通的节点
            long nowTs = System.currentTimeMillis();
            if (nowTs - lastFullScanAt > 60000 || lastFullScanAt == 0) {
                lastFullScanAt = nowTs;
                guard.scanAllNodes();
            }

            // 每 5 分钟查一次 DNS 泄露
            if (nowTs - lastDnsCheckAt > 300000 || lastDnsCheckAt == 0) {
                lastDnsCheckAt = nowTs;
                guard.checkDns();
            }

            // 每轮顺手确认「局域网代理」开着（不需要 root，走 Clash 自己的接口）
            try { guard.ensureAllowLan(); } catch (Throwable ignored) { }
            // 局域网页服务（给扫二维码的人看配置说明）
            try {
                String ip = App.lanIp(this);
                if (!ip.equals(lastLanIp)) {
                    // 网络变了（例如从 WiFi 切到开热点）：重启服务让它重新绑定
                    if (lastLanIp.length() > 0) {
                        LanServer.stop();
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try { LanServer.start(getApplicationContext()); }
                                        catch (Throwable ignored) { }
                                    }
                                }, 1500);
                    }
                    lastLanIp = ip;
                } else if (!LanServer.isRunning()) {
                    LanServer.start(getApplicationContext());
                }
            } catch (Throwable ignored) { }

            msg = guard.tick();
            roundCount++;
            lastRoundAt = System.currentTimeMillis();
            lastRoundMsg = msg;
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
            lastRoundMsg = "检测异常：" + t.getMessage();
        }
        if (msg != null) {
            Log.i(TAG, "tick: " + msg);
            lastRoundMsg = msg;
            if (msg.startsWith("已切换")) {
                App.notify(this, ID_SWITCH, "已自动切换节点", msg, false);
            }
        }
        refreshForeground();
        // 这一轮结束后排下一轮（间隔跟随设置）
        scheduleNext(guard == null ? 6000 : Math.max(2000, guard.intervalMs));
    }

    private void refreshForeground() {
        try {
            startForeground(ID_RUNNING, buildForeground());
        } catch (Throwable ignored) { }
    }

    private Notification buildForeground() {
        String state = "待命中";
        if (guard != null) {
            if (guard.allOk && guard.currentNode != null) {
                state = "全部正常 · " + guard.currentNode;
            } else if (guard.currentNode != null) {
                state = "有站点不通 · " + guard.currentNode;
            }
        }
        if (guard != null && guard.currentNode == null) {
            state = "Clash 未连接";
        }

        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, App.CH_WATCH);
        else b = new Notification.Builder(this);

        b.setContentTitle("线路守卫 · " + state)
                .setContentText("点开查看面板")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(App.openIntent(this));
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            b.setStyle(new Notification.BigTextStyle().bigText(state));
        }
        return b.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        alive = true;
        KeepAliveJob.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        App.ensureChannel(this);

        String act = intent == null ? null : intent.getAction();
        if (ACTION_CLASH_DOWN.equals(act)) {
            // 通知栏/广播告诉我们代理关了：直接按深退走，不用等下一轮轮询
            SharedPreferences sp2 = App.prefs(this);
            if (sp2.getBoolean("stopWithClash", true)) {
                if (sp2.getBoolean("notifyOnStart", true)) {
                    App.notify(this, ID_OFFLINE, "代理已关闭 · 监测已停止",
                            "后台已退出，不耗电。下次开代理会自动关联启动", false);
                }
                deepExit(sp2.getBoolean("notifyOnStart", true) ? 2200 : 300);
                return START_NOT_STICKY;
            }
        }
        try {
            startForeground(ID_RUNNING, buildForeground());
        } catch (Throwable ignored) { }

        stopped = false;
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "guard-loop");
                            t.setDaemon(true);
                            return t;
                        }
                    });
        }
        scheduleNext(800);
        // 用 NOT_STICKY：被杀后不要系统自动复活（否则用户关掉 Clash 后它又活了）。
        // 真正需要「自己回来」的场景由 BootReceiver 和 KeepAliveJob 负责。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        alive = false;
        if (scheduledFuture != null) scheduledFuture.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            try { c.startService(i); } catch (Throwable ignored) { }
        }
    }
}
