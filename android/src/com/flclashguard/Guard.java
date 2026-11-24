package com.flclashguard;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Guard — 守护核心（Java 版，逻辑和 node 版一致）
 *
 *   1) 每 N 秒检测 ChatGPT / OpenAI / Gemini / Claude / Google
 *   2) 不通时在勾选的节点里并发测延迟，挑最低且真能连通的
 *   3) 通过 Clash API 切过去；失败节点冷却
 *
 * 所有网络请求都设为「不允许重定向」+ 短超时，保证一轮检测 5 秒内结束。
 */
final class Guard {

    // ───────── 配置 ─────────
    int intervalMs = 6000;
    int probeTimeoutMs = 7000;
    int latencyWarnMs = 4000;
    int failThreshold = 2;
    int switchCooldownMs = 8000;
    int nodeDelayTimeout = 4000;
    int nodeCooldownMs = 45000;
    boolean autoSwitch = true;
    String channel = "proxy";   // proxy | direct
    String proxyHost = "127.0.0.1";
    int proxyPort = 7890;

    static final String TEST_URL = "https://www.gstatic.com/generate_204";

    // ───────── 状态 ─────────
    volatile boolean running = false;
    volatile long lastCheckAt = 0;
    volatile int tickCount = 0;
    volatile boolean allOk = true;
    volatile int failStreak = 0;
    volatile int okStreak = 0;
    volatile int slowStreak = 0;
    volatile int latency = -1;
    volatile String currentGroup = null;
    volatile String currentNode = null;
    volatile String lastSwitchFrom = null;
    volatile String lastSwitchTo = null;
    volatile long lastSwitchAt = 0;
    volatile String lastError = null;

    /** 全部节点（含不通的）——UI 要能完整显示，不能藏 */
    final List<ClashApi.Node> allNodes = new ArrayList<ClashApi.Node>();
    /** 每个节点上次测速时间 */
    final Map<String, Long> nodeTestedAt = new LinkedHashMap<String, Long>();
    volatile long lastScanAt = 0;
    volatile String lastScanSummary = null;

    final Map<String, Long> cooldowns = new LinkedHashMap<String, Long>();
    final List<ServiceResult> services = new ArrayList<ServiceResult>();
    final List<String> whitelist = new ArrayList<String>(); // 空 = 全选
    volatile int nodeCount = 0;

    static final class ServiceResult {
        String id;
        String label;
        boolean ok;
        int ms;
        String error;
        ServiceResult(String id, String label) { this.id = id; this.label = label; }
    }

    // ───────── 站点定义 ─────────

    // [id, 显示名, 探测URL, 期望状态码, 需要的地区(空=不限制)]
    private static final String[][] SITES = {
            {"chatgpt", "ChatGPT", "https://chatgpt.com/backend-api/me", "401,200", ""},
            {"openai", "OpenAI", "https://api.openai.com/v1/models", "401,200", ""},
            {"gemini", "Gemini", "https://gemini.google.com/generate_204", "204", "gemini"},
            {"claude", "Claude", "https://claude.ai/login", "200,302,403", ""},
            {"google", "Google", "https://www.gstatic.com/generate_204", "204", ""},
    };

    // ───────── 地区检测 ─────────
    //
    // Gemini 的地区限制按「出口 IP 归属地」判定，中国大陆(除 Workspace)不可用；
    // 香港/澳门/台湾/新加坡/日本/韩国/美国 等都支持。见官方支持列表。
    // 关键：必须每轮都查 —— 节点漂移或 Clash 回退到直连时，地区会瞬间变回 CN，
    // 只看「能连上」是发现不了的。
    static final java.util.Set<String> GEMINI_BLOCKED = new java.util.HashSet<String>(
            java.util.Arrays.asList("CN", "RU", "IR", "KP", "SY", "CU", "VE", "BY", "MM", "AF"));

    volatile String exitIp = null;
    volatile String exitCountry = null;
    volatile long exitCheckedAt = 0;
    volatile String geoError = null;

    /** 查当前出口 IP 与地区。优先 1.1.1.1（走系统 VPN 直连即可，不依赖 DNS） */
    synchronized boolean checkExitRegion() {
        String[] endpoints = {
                "https://1.1.1.1/cdn-cgi/trace",
                "https://www.cloudflare.com/cdn-cgi/trace",
        };
        for (String url : endpoints) {
            try {
                String body = httpGetText(url, 4000);
                if (body == null) continue;
                String ip = null, loc = null;
                for (String line : body.split("\n")) {
                    if (line.startsWith("ip=")) ip = line.substring(3).trim();
                    else if (line.startsWith("loc=")) loc = line.substring(4).trim().toUpperCase();
                }
                if (ip != null && loc != null && loc.length() == 2) {
                    exitIp = ip;
                    exitCountry = loc;
                    exitCheckedAt = System.currentTimeMillis();
                    geoError = null;
                    return true;
                }
            } catch (Throwable e) {
                geoError = e.getMessage();
            }
        }
        geoError = "地区查询失败";
        return false;
    }

    boolean geminiAllowedInCurrentRegion() {
        if (exitCountry == null) return true; // 查不到就别误判
        return !GEMINI_BLOCKED.contains(exitCountry);
    }

    /** 极简 GET，返回文本；失败返回 null */
    String httpGetText(String urlStr, int timeout) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "curl/8.5.0");
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int n, total = 0;
            while ((n = in.read(b)) > 0 && total < 8192) { bos.write(b, 0, n); total += n; }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Google 备用主机名（www.google.com 容易被节点干扰） */
    private static final String[] GOOGLE_ALT = {
            "https://accounts.google.com/generate_204",
            "https://google.com/generate_204",
    };

    ClashApi api;

    Guard(String clashHost, int clashPort, String secret) {
        this.api = new ClashApi(clashHost, clashPort, secret, 8000);
    }

    // ───────── 单站点探测 ─────────

    private boolean inList(String[] list, int code) {
        for (String s : list) {
            if (s.trim().equals(String.valueOf(code))) return true;
        }
        return false;
    }

    /** 返回延迟 ms，失败返回 -1 */
    int probe(String urlStr, String[] expect, boolean viaProxy) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            if (viaProxy && "proxy".equals(channel)) {
                Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                c = (HttpURLConnection) u.openConnection(p);
            } else {
                c = (HttpURLConnection) u.openConnection();
            }
            c.setConnectTimeout(probeTimeoutMs);
            c.setReadTimeout(probeTimeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();

            // Cloudflare 人机验证：说明线路通，只是脚本过不了 JS 挑战
            String cf = c.getHeaderField("cf-mitigated");
            if (code == 403 && "challenge".equalsIgnoreCase(cf)) {
                return (int) (System.currentTimeMillis() - t0);
            }
            if (!inList(expect, code)) return -1;

            // 把 body 读掉（部分站点要看到内容才算真通）
            try {
                InputStream in = c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] b = new byte[2048];
                    int n, total = 0;
                    while ((n = in.read(b)) > 0 && total < 8192) {
                        bos.write(b, 0, n);
                        total += n;
                    }
                    in.close();
                }
            } catch (Throwable ignored) { }
            return (int) (System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 检测全部站点（并发）+ 地区 */
    void checkServices() {
        services.clear();
        // 每轮都查地区：节点漂移 / Clash 回退直连时地区会变，只看连通性发现不了
        checkExitRegion();
        final boolean useProxy = "proxy".equals(channel);
        ExecutorService ex = Executors.newFixedThreadPool(SITES.length);
        List<Future<ServiceResult>> fs = new ArrayList<Future<ServiceResult>>();

        for (final String[] s : SITES) {
            fs.add(ex.submit(new Callable<ServiceResult>() {
                @Override
                public ServiceResult call() {
                    ServiceResult r = new ServiceResult(s[0], s[1]);
                    String[] expect = s[3].split(",");
                    int ms = probe(s[2], expect, useProxy);

                    // Google：主地址失败就试备用主机名（anyOf）
                    if (ms < 0 && "google".equals(s[0])) {
                        for (String alt : GOOGLE_ALT) {
                            int a = probe(alt, new String[]{"204"}, useProxy);
                            if (a >= 0) { ms = a; break; }
                        }
                    }
                    r.ok = ms >= 0;
                    r.ms = ms < 0 ? 0 : ms;
                    r.error = ms < 0 ? "连不上" : null;

                    // Gemini 特有：连通 ≠ 可用，还要看地区
                    if (r.ok && "gemini".equals(s[0]) && !geminiAllowedInCurrentRegion()) {
                        r.ok = false;
                        r.ms = 0;
                        r.error = "地区不支持（" + exitCountry + "）";
                    }
                    return r;
                }
            }));
        }

        int worst = 0;
        boolean ok = true;
        for (Future<ServiceResult> f : fs) {
            try {
                ServiceResult r = f.get(probeTimeoutMs + 3000, TimeUnit.MILLISECONDS);
                services.add(r);
                if (!r.ok) ok = false;
                else if (r.ms > worst) worst = r.ms;
            } catch (Throwable e) {
                ok = false;
            }
        }
        ex.shutdownNow();
        allOk = ok;
        latency = ok ? worst : -1;
        lastCheckAt = System.currentTimeMillis();
        tickCount++;
    }

    // ───────── 一轮 ─────────

    /** @return 本轮做的事情（用于日志/通知） */
    String tick() {
        String clashGroup = null;
        try {
            JSONObject proxies = api.proxies();
            clashGroup = ClashApi.pickMainGroup(proxies);
            if (clashGroup != null) {
                currentGroup = clashGroup;
                currentNode = ClashApi.currentReal(proxies, clashGroup);
            }
        } catch (Throwable e) {
            lastError = "读不到 Clash 节点：" + e.getMessage();
        }

        checkServices();
        String bad = "";
        for (ServiceResult r : services) {
            if (!r.ok) bad += (bad.length() > 0 ? "、" : "") + r.label;
        }

        if (allOk && latency <= latencyWarnMs) {
            okStreak++;
            failStreak = 0;
            slowStreak = 0;
            return null;
        }

        okStreak = 0;
        if (allOk) {
            // 全通但慢：连续 3 轮才动手，避免抖动
            slowStreak++;
            if (slowStreak < 3) return null;
            slowStreak = 0;
        } else {
            failStreak++;
            if (failStreak < failThreshold) return null; // 先观察一轮
        }

        if (!autoSwitch) return "不通但自动切换已关闭：" + bad;
        if (clashGroup == null) return "不通：" + bad + "（读不到策略组，无法切换）";

        return recover(clashGroup, allOk ? "slow" : "down", false);
    }

    /** 确保 FlClash 开着「局域网代理」，每轮最多试一次 */
    volatile long lastLanCheckAt = 0;
    volatile boolean lanOk = false;

    void ensureAllowLan() {
        long now = System.currentTimeMillis();
        if (now - lastLanCheckAt < 120000 && lanOk) return; // 2 分钟内成功过就不再折腾
        lastLanCheckAt = now;
        try {
            JSONObject cfg = api.configs();
            boolean allow = cfg.optBoolean("allow-lan", false);
            if (allow) { lanOk = true; return; }
            api.enableAllowLan();
            lanOk = true;
            lastError = null;
        } catch (Throwable e) {
            lanOk = false;
            // 不覆盖 lastError，避免干扰主流程
        }
    }

    /** 读策略组、刷新节点列表（不测速，很快） */
    void refreshNodeList() {
        try {
            JSONObject proxies = api.proxies();
            String g = ClashApi.pickMainGroup(proxies);
            if (g == null) return;
            currentGroup = g;
            currentNode = ClashApi.currentReal(proxies, g);
            ClashApi.Expanded ex = ClashApi.expand(proxies, g);
            // 先把旧的延迟存成 map，再重建列表（clear 之后就查不到了）
            Map<String, Integer> oldDelay = new LinkedHashMap<String, Integer>();
            Map<String, Boolean> oldCool = new LinkedHashMap<String, Boolean>();
            synchronized (allNodes) {
                for (ClashApi.Node n : allNodes) {
                    oldDelay.put(n.name, n.delay);
                    oldCool.put(n.name, cooldowns.containsKey(n.name));
                }
                allNodes.clear();
                for (ClashApi.Node n : ex.nodes) {
                    Integer d = oldDelay.get(n.name);
                    n.delay = d != null ? d : -1;
                    n.current = n.name.equals(currentNode);
                    allNodes.add(n);
                }
            }
            nodeCount = allNodes.size();
        } catch (Throwable e) {
            lastError = "读节点失败：" + e.getMessage();
        }
    }

    private ClashApi.Node findNode(String name) {
        synchronized (allNodes) {
            for (ClashApi.Node n : allNodes) {
                if (n.name.equals(name)) return n;
            }
        }
        return null;
    }

    /** 测试全部节点（并发），结果写回 allNodes —— 包括失败的，不能隐藏 */
    void scanAllNodes() {
        refreshNodeList();
        List<ClashApi.Node> snapshot;
        synchronized (allNodes) {
            snapshot = new ArrayList<ClashApi.Node>(allNodes);
        }
        if (snapshot.isEmpty()) return;

        final List<ClashApi.Node> pool = new ArrayList<ClashApi.Node>();
        for (ClashApi.Node n : snapshot) {
            if (whitelist.isEmpty() || whitelist.contains(n.name)) pool.add(n);
        }

        // 并发度降到 6：FlClash 的 delay 接口在高并发下容易 503
        final int CONCURRENCY = 6;
        ExecutorService ex = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<?>> fs = new ArrayList<Future<?>>();
        for (final ClashApi.Node n : pool) {
            fs.add(ex.submit(new Callable<Void>() {
                @Override public Void call() {
                    try {
                        n.delay = api.delay(n.name, TEST_URL, nodeDelayTimeout);
                        nodeTestedAt.put(n.name, System.currentTimeMillis());
                        cooldowns.remove(n.name);
                    } catch (Throwable e) {
                        n.delay = -1;
                        nodeTestedAt.put(n.name, System.currentTimeMillis());
                        cooldowns.put(n.name, System.currentTimeMillis() + nodeCooldownMs);
                    }
                    return null;
                }
            }));
        }
        // 关键修复：超时按「每个任务自己的耗时」算，不要用固定值。
        // 之前写死 nodeDelayTimeout+3000，导致排在后面的节点还没开始测就被判失败（62 个全挂）。
        long deadline = System.currentTimeMillis()
                + (long) Math.ceil((double) pool.size() / CONCURRENCY) * (nodeDelayTimeout + 2000)
                + 15000;
        for (Future<?> f : fs) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            try { f.get(remain, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        }
        ex.shutdownNow();

        int ok = 0, bad = 0;
        for (ClashApi.Node n : pool) { if (n.delay > 0) ok++; else bad++; }
        lastScanAt = System.currentTimeMillis();
        lastScanSummary = "扫描 " + pool.size() + " 个：通 " + ok + " · 不通 " + bad;
    }

    // ───────── DNS 泄露检测 ─────────
    //
    // 判断标准（不依赖任何第三方服务）：
    //  1) 域名解析必须返回 fake-ip 段（198.18.0.0/16 或 198.19.0.0/16）→ 说明 DNS 被 Clash 接管
    //  2) 如果解析出的是真实公网 IP，且该 IP 能被直连打通 → 说明 DNS 查询走了本地/国内解析器 = 泄露风险
    //  3) 系统 DNS 服务器列表里出现国内 DNS（223.5.5.5 / 119.29.29.29 / 114.114.114.114 等）→ 提醒

    volatile String dnsStatus = "unknown"; // ok | fakeip | risk | leak
    volatile String dnsDetail = "";

    private static final String[] CN_DNS = {
            "223.5.5.5", "223.6.6.6",            // 阿里
            "119.29.29.29",                      // 腾讯
            "114.114.114.114", "114.114.115.115",// 114
            "180.76.76.76",                      // 百度
            "1.2.4.8", "210.2.4.8",              // CNNIC
    };

    /** 检测 FakeIP / DNS 泄露 */
    void checkDns() {
        StringBuilder sb = new StringBuilder();
        boolean sawFakeIp = false;
        boolean sawRealIp = false;
        String realIp = null;

        String[] testDomains = {"chatgpt.com", "google.com", "claude.ai", "baidu.com"};
        for (String d : testDomains) {
            try {
                java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(d);
                for (java.net.InetAddress a : addrs) {
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) {
                        sawFakeIp = true;
                    } else if (ip.startsWith("10.") || ip.startsWith("127.")
                            || ip.startsWith("169.254.") || ip.startsWith("192.168.")) {
                        // 内网，忽略
                    } else {
                        // 注意：如果系统本身就解析出真实 IP，说明 DNS 没被接管
                        sawRealIp = true;
                        if (realIp == null) realIp = ip;
                    }
                }
                sb.append(d).append("→");
                sb.append(addrs.length > 0 ? addrs[0].getHostAddress() : "?");
                sb.append(" ");
            } catch (Throwable e) {
                sb.append(d).append("→失败 ");
            }
        }

        // 读系统 DNS 服务器（需要 Context）
        StringBuilder dnsServers = new StringBuilder();
        try {
            android.net.ConnectivityManager cm = null;
            if (appCtx != null) {
                cm = (android.net.ConnectivityManager)
                        appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            }
            if (cm != null && android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        if (dnsServers.length() > 0) dnsServers.append(", ");
                        dnsServers.append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) { }

        boolean cnDnsPresent = false;
        String ds = dnsServers.toString();
        for (String cn : CN_DNS) {
            if (ds.contains(cn)) { cnDnsPresent = true; break; }
        }

        if (sawFakeIp && !sawRealIp) {
            dnsStatus = "fakeip";
            dnsDetail = "DNS 已被 FlClash 接管（fake-ip），所有域名走 Clash 解析，不泄露";
        } else if (sawRealIp) {
            dnsStatus = cnDnsPresent ? "leak" : "risk";
            dnsDetail = "解析出真实 IP " + (realIp == null ? "" : realIp)
                    + (cnDnsPresent ? "，且系统 DNS 含国内服务器（" + ds + "）→ 有泄露风险" : "，DNS 未被接管");
        } else {
            dnsStatus = "unknown";
            dnsDetail = "解析异常：" + sb;
        }
        dnsDetail += " | 系统 DNS: " + (ds.length() > 0 ? ds : "读不到");
    }

    private android.content.Context appCtx;
    void attach(android.content.Context c) { this.appCtx = c; }

    // ───────── 测速 + 择优 + 切换 ─────────

    String recover(String group, String reason, boolean manual) {
        try {
            JSONObject proxies = api.proxies();
            if (group == null) group = ClashApi.pickMainGroup(proxies);
            if (group == null) return "找不到可切换的策略组";

            ClashApi.Expanded ex = ClashApi.expand(proxies, group);
            nodeCount = ex.nodes.size();

            final List<ClashApi.Node> pool = new ArrayList<ClashApi.Node>();
            for (ClashApi.Node n : ex.nodes) {
                if (whitelist.isEmpty() || whitelist.contains(n.name)) pool.add(n);
            }
            if (pool.isEmpty()) return "勾选的节点里没有可用的";

            // 并发测速
            ExecutorService ex2 = Executors.newFixedThreadPool(12);
            List<Future<ClashApi.Node>> fs = new ArrayList<Future<ClashApi.Node>>();
            for (final ClashApi.Node n : pool) {
                fs.add(ex2.submit(new Callable<ClashApi.Node>() {
                    @Override
                    public ClashApi.Node call() {
                        try {
                            n.delay = api.delay(n.name, TEST_URL, nodeDelayTimeout);
                            cooldowns.remove(n.name);
                        } catch (Throwable e) {
                            n.delay = -1;
                            cooldowns.put(n.name, System.currentTimeMillis() + nodeCooldownMs);
                        }
                        return n;
                    }
                }));
            }
            // 同样：按任务数算总时限，不能用固定超时（否则排在后面的节点全被判死）
            long dl = System.currentTimeMillis()
                    + (long) Math.ceil((double) pool.size() / 12) * (nodeDelayTimeout + 2000)
                    + 15000;
            for (Future<ClashApi.Node> f : fs) {
                long remain = dl - System.currentTimeMillis();
                if (remain <= 0) break;
                try { f.get(remain, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
            }
            ex2.shutdownNow();

            List<ClashApi.Node> good = new ArrayList<ClashApi.Node>();
            for (ClashApi.Node n : pool) {
                if (n.delay > 0) {
                    Long cd = cooldowns.get(n.name);
                    if (cd == null || cd < System.currentTimeMillis()) good.add(n);
                }
            }
            if (good.isEmpty()) {
                // 全挂了：清空冷却再试
                cooldowns.clear();
                for (ClashApi.Node n : pool) if (n.delay > 0) good.add(n);
            }
            if (good.isEmpty()) return "所有勾选节点都不通，保持现状等恢复";

            java.util.Collections.sort(good, new java.util.Comparator<ClashApi.Node>() {
                @Override
                public int compare(ClashApi.Node a, ClashApi.Node b) {
                    return a.delay - b.delay;
                }
            });

            ClashApi.Node chosen = good.get(0);
            String curReal = ClashApi.currentReal(proxies, group);

            // 不通场景不能留在这个节点
            if (chosen.name.equals(curReal)) {
                if ("down".equals(reason)) {
                    ClashApi.Node alt = null;
                    for (ClashApi.Node n : good) {
                        if (!n.name.equals(curReal)) { alt = n; break; }
                    }
                    if (alt == null) return "除当前节点外没有可用节点";
                    chosen = alt;
                } else if (!manual) {
                    return null; // 只是慢，当前已最优，不切
                }
            }

            long now = System.currentTimeMillis();
            if (!manual && now < nextSwitchAllowedAt) {
                return "切换冷却中";
            }
            nextSwitchAllowedAt = now + switchCooldownMs;

            lastSwitchFrom = curReal;
            lastSwitchTo = chosen.name;
            lastSwitchAt = now;

            int steps = api.selectNode(proxies, group, chosen.name);
            if (steps == 0) return "找不到切到该节点的路径";

            failStreak = 0;
            okStreak = 0;

            // 切完立刻复检
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            checkServices();
            if (allOk) {
                return "已切换 → " + chosen.name + "（" + chosen.delay + "ms），复检通过";
            }
            return "已切换 → " + chosen.name + "（" + chosen.delay + "ms），仍有站点不通";
        } catch (Throwable e) {
            lastError = e.getMessage();
            return "切换失败：" + e.getMessage();
        }
    }

    volatile long nextSwitchAllowedAt = 0;
}
