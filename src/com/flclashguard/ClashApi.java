package com.flclashguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clash / mihomo 控制器客户端（纯 Java，无第三方依赖）
 *
 * 只用得上的四个接口：
 *   GET  /version
 *   GET  /proxies
 *   GET  /proxies/{name}/delay?url=..&timeout=..
 *   PUT  /proxies/{group}   {"name":"..."}
 */
final class ClashApi {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    final String host;
    final int port;
    final String secret;
    final int timeoutMs;

    ClashApi(String host, int port, String secret, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.secret = secret == null ? "" : secret;
        this.timeoutMs = timeoutMs;
    }

    // ───────────────────────── 底层 HTTP ─────────────────────────

    private HttpURLConnection open(String method, String path) throws Exception {
        URL u = new URL("http://" + host + ":" + port + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (secret.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + secret);
        }
        if ("PUT".equals(method)) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
        }
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), UTF8);
    }

    private String call(String method, String path, String body) throws Exception {
        HttpURLConnection c = null;
        try {
            c = open(method, path);
            if (body != null) {
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(UTF8));
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                return readAll(c.getInputStream());
            }
            // 4xx/5xx 也把 body 读出来，401/504 是有意义的信息
            String err = readAll(c.getErrorStream());
            throw new ClashException("HTTP " + code + (err.length() > 0 ? " " + err : ""));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static final class ClashException extends Exception {
        ClashException(String m) { super(m); }
    }

    // ───────────────────────── 业务接口 ─────────────────────────

    boolean alive() {
        try {
            call("GET", "/version", null);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    String version() {
        try {
            JSONObject o = new JSONObject(call("GET", "/version", null));
            return o.optString("version", "ok");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读全部代理项（含策略组和真实节点） */
    JSONObject proxies() throws Exception {
        JSONObject root = new JSONObject(call("GET", "/proxies", null));
        return root.optJSONObject("proxies");
    }

    /** 测某个节点的延迟，失败抛异常 */
    int delay(String name, String testUrl, int timeout) throws Exception {
        String path = "/proxies/" + enc(name) + "/delay?url=" + enc(testUrl) + "&timeout=" + timeout;
        JSONObject o = new JSONObject(call("GET", path, null));
        int d = o.optInt("delay", -1);
        if (d < 0) throw new ClashException("no delay");
        return d;
    }

    /** 读配置（allow-lan / 端口等） */
    JSONObject configs() throws Exception {
        return new JSONObject(call("GET", "/configs", null));
    }

    /**
     * 打开「局域网代理」，让别的设备能连进来。
     * 用 PATCH /configs，只改这一个字段，不动订阅和规则。
     */
    boolean enableAllowLan() throws Exception {
        JSONObject body = new JSONObject();
        body.put("allow-lan", true);
        JSONObject bind = new JSONObject();
        call("PATCH", "/configs", body.toString());
        return true;
    }

    /** 在策略组里选中某个成员 */
    void select(String group, String name) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", name);
        call("PUT", "/proxies/" + enc(group), body.toString());
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return s;
        }
    }

    // ───────────────────────── 策略组展开 ─────────────────────────

    /**
     * 一个「真实可切换节点」：name 是 Clash 里的名字，via 是它归属的子组（用于分组显示）。
     */
    static final class Node {
        final String name;
        final String type;
        String via;
        int depth;
        int score;      // 归属优先级：成员越少的组分数越高
        int delay = -1; // -1 = 未测 / 失败
        boolean current;

        Node(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    static final class Expanded {
        final List<Node> nodes = new ArrayList<Node>();
        final List<String> subGroups = new ArrayList<String>();
    }

    /** 从主策略组递归展开出全部真实节点（处理「策略组套策略组」） */
    static Expanded expand(JSONObject proxies, String groupName) {
        Expanded out = new Expanded();
        JSONObject main = proxies.optJSONObject(groupName);
        if (main == null) return out;

        Map<String, Node> index = new LinkedHashMap<String, Node>();
        JSONArray members = main.optJSONArray("all");
        if (members == null) return out;

        Set<String> seenGroups = new LinkedHashSet<String>();
        seenGroups.add(groupName);

        for (int i = 0; i < members.length(); i++) {
            String m = members.optString(i, null);
            if (m == null) continue;
            JSONObject mi = proxies.optJSONObject(m);
            if (mi == null) continue;
            if (mi.optJSONArray("all") != null) {
                expandSub(proxies, m, 1, index, out.subGroups, seenGroups);
            } else {
                addNode(proxies, m, groupName, 0, -100000, index);
            }
        }
        // 展开完成后统一从 index 取（顺序按主组中子组出现的次序）
        out.nodes.clear();
        out.nodes.addAll(index.values());
        final JSONArray orderArr = members;
        java.util.Collections.sort(out.nodes, new java.util.Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                int ra = rank(orderArr, a.via);
                int rb = rank(orderArr, b.via);
                return ra - rb;
            }
        });
        return out;
    }

    private static int rank(JSONArray order, String via) {
        for (int i = 0; i < order.length(); i++) {
            if (via != null && via.equals(order.optString(i, ""))) return i;
        }
        return 999;
    }

    private static void expandSub(JSONObject proxies, String name, int depth,
                                  Map<String, Node> index, List<String> subGroups,
                                  Set<String> seenGroups) {
        if (depth > 4) return;
        JSONObject info = proxies.optJSONObject(name);
        if (info == null) return;
        JSONArray all = info.optJSONArray("all");
        if (all == null) return;
        if (!subGroups.contains(name)) subGroups.add(name);

        int size = all.length();
        for (int i = 0; i < size; i++) {
            String child = all.optString(i, null);
            if (child == null) continue;
            JSONObject ci = proxies.optJSONObject(child);
            if (ci == null) continue;
            if (ci.optJSONArray("all") != null) {
                if (!seenGroups.contains(child)) {
                    seenGroups.add(child);
                    expandSub(proxies, child, depth + 1, index, subGroups, seenGroups);
                }
            } else {
                // 成员越少的组越具体 → 分数越高
                int score = -size * 100 + depth;
                addNode(proxies, child, name, depth, score, index);
            }
        }
    }

    private static void addNode(JSONObject proxies, String name, String via, int depth, int score,
                                Map<String, Node> index) {
        JSONObject info = proxies.optJSONObject(name);
        if (info == null) return;
        if (info.optJSONArray("all") != null) return;         // 还是组
        if (isGroupType(info.optString("type", ""))) return;   // DIRECT/REJECT 等
        if (isInfoNode(name)) return;                          // 剩余流量/到期 伪节点

        Node prev = index.get(name);
        if (prev != null) {
            if (score > prev.score) {
                prev.score = score;
                prev.via = via;
                prev.depth = depth;
            }
            return;
        }
        Node n = new Node(name, info.optString("type", ""));
        n.via = via;
        n.depth = depth;
        n.score = score;
        index.put(name, n);
    }

    private static boolean isGroupType(String t) {
        if (t == null) return false;
        String s = t.toLowerCase();
        return s.equals("selector") || s.equals("urltest") || s.equals("fallback")
                || s.equals("loadbalance") || s.equals("relay") || s.equals("direct")
                || s.equals("reject") || s.equals("rejectdrop") || s.equals("pass")
                || s.equals("compatible") || s.equals("dns");
    }

    /** 机场广告/信息伪节点：「剩余流量：499 GB」「套餐到期：…」 */
    static boolean isInfoNode(String name) {
        if (name == null) return false;
        // 有国旗 emoji 视为真节点
        if (name.codePointCount(0, name.length()) > 0) {
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return false;
                i += Character.charCount(cp);
            }
        }
        String[] keys = {"剩余流量", "到期", "距离下次重置", "重置剩余", "套餐", "官网", "客服",
                "购买", "续订", "公告", "流量", "过期", "expire", "traffic", "remaining", "reset"};
        String lower = name.toLowerCase();
        for (String k : keys) {
            if (lower.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    /** 顺着 now 一路走到底层真实节点 */
    static String currentReal(JSONObject proxies, String groupName) {
        String cur = groupName;
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < 6; i++) {
            JSONObject info = proxies.optJSONObject(cur);
            if (info == null) return cur;
            String now = info.optString("now", null);
            if (now == null || now.length() == 0 || seen.contains(now)) return cur;
            seen.add(now);
            JSONObject nx = proxies.optJSONObject(now);
            if (nx == null) return now;
            if (nx.optJSONArray("all") == null) return now; // 到真实节点
            cur = now;
        }
        return cur;
    }

    /**
     * 从主组切到某个真实节点。若节点在子组里，先切子组再切节点。
     * 返回实际执行的步骤数（0 = 找不到路径）。
     */
    int selectNode(JSONObject proxies, String groupName, String target) throws Exception {
        List<String[]> path = findPath(proxies, groupName, target);
        for (String[] step : path) {
            select(step[0], step[1]);
            Thread.sleep(80);
        }
        return path.size();
    }

    /** BFS 找「主组 → … → 直接包含 target 的组」的切换步骤 */
    static List<String[]> findPath(JSONObject proxies, String groupName, String target) {
        List<String[]> result = new ArrayList<String[]>();
        if (target == null) return result;

        List<String[]> queueGroup = new ArrayList<String[]>();
        List<List<String[]>> queuePath = new ArrayList<List<String[]>>();
        Set<String> visited = new LinkedHashSet<String>();

        queueGroup.add(new String[]{groupName});
        queuePath.add(new ArrayList<String[]>());
        visited.add(groupName);

        int head = 0;
        while (head < queueGroup.size()) {
            String g = queueGroup.get(head)[0];
            List<String[]> p = queuePath.get(head);
            head++;

            JSONObject info = proxies.optJSONObject(g);
            if (info == null) continue;
            JSONArray all = info.optJSONArray("all");
            if (all == null) continue;

            boolean contains = false;
            for (int i = 0; i < all.length(); i++) {
                if (target.equals(all.optString(i, ""))) { contains = true; break; }
            }
            if (contains) {
                result.addAll(p);
                result.add(new String[]{g, target});
                return result;
            }

            for (int i = 0; i < all.length(); i++) {
                String child = all.optString(i, "");
                JSONObject ci = proxies.optJSONObject(child);
                if (ci == null || ci.optJSONArray("all") == null) continue;
                if (visited.contains(child)) continue;
                visited.add(child);
                List<String[]> np = new ArrayList<String[]>(p);
                np.add(new String[]{g, child});
                queueGroup.add(new String[]{child});
                queuePath.add(np);
            }
        }
        // 找不到就退化成直接选（扁平结构）
        result.add(new String[]{groupName, target});
        return result;
    }

    /** 挑主策略组：优先 selector 里像「节点选择」的那个 */
    static String pickMainGroup(JSONObject proxies) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        Iterator<String> it = proxies.keys();
        while (it.hasNext()) {
            String name = it.next();
            JSONObject g = proxies.optJSONObject(name);
            if (g == null) continue;
            if (g.optJSONArray("all") == null) continue;
            String type = g.optString("type", "").toLowerCase();
            if (!type.equals("selector")) continue;

            int score = 0;
            if (name.indexOf("节点选择") >= 0) score += 100;
            if (name.indexOf("🚀") >= 0) score += 30;
            if (name.toLowerCase().indexOf("select") >= 0) score += 20;
            if (name.indexOf("自动") >= 0 || name.indexOf("自动选择") >= 0) score -= 50;
            if (name.indexOf("漏网") >= 0 || name.indexOf("广告") >= 0) score -= 40;
            score += Math.min(g.optJSONArray("all").length(), 100) / 10;

            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }
}
