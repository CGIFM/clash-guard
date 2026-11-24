package com.flclashguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 一个极小的 HTTP 服务（端口 8788），只为一个目的：
 * 让别的设备扫二维码后，能看到「怎么填代理」的说明页。
 *
 * 不引任何框架，一个 ServerSocket 就够了。只处理 GET /，其余一律 404。
 * 只在 Clash 代理运行时才启动，避免白占端口。
 */
final class LanServer {

    private static final String TAG = "FlClashGuard";
    static final int PORT = 8788;

    private static Thread thread;
    private static ServerSocket server;
    private static volatile boolean running = false;

    static synchronized void start(final Context ctx) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    // 关键：绑 0.0.0.0，这样从 WiFi 切到热点后新地址照样能访问。
                    // 如果只绑具体 IP，换网后旧连接和监听都会失效。
                    server.bind(new InetSocketAddress("0.0.0.0", PORT));
                    Log.i(TAG, "局域网说明页已启动 :" + PORT);
                    while (running) {
                        Socket s = null;
                        try {
                            s = server.accept();
                            handle(s, ctx);
                        } catch (Throwable e) {
                            if (running) Log.w(TAG, "accept 失败", e);
                        } finally {
                            if (s != null) try { s.close(); } catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "LanServer 启动失败", e);
                }
            }
        }, "lan-http");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
        thread = null;
    }

    static boolean isRunning() { return running; }

    private static void handle(Socket s, Context ctx) throws Exception {
        s.setSoTimeout(3000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), Charset.forName("UTF-8")));
        String line = in.readLine();
        if (line == null) return;
        String path = "/";
        try {
            String[] parts = line.split(" ");
            if (parts.length > 1) path = parts[1];
        } catch (Throwable ignored) { }

        String body;
        int code = 200;
        if (path.startsWith("/p") || path.equals("/")) {
            body = page(ctx);
        } else {
            code = 404;
            body = "<h1>404</h1>";
        }

        byte[] bytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(header.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static String page(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE);
        int port = sp.getInt("proxyPort", 7890);
        // 拿不到对端要访问的 IP，就用服务端自己的局域网地址
        String ip = App.lanIp(ctx);
        String addr = ip + ":" + port;

        return "<!doctype html><html lang=zh><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>加入代理</title><style>"
                + "body{margin:0;background:#0b0f17;color:#e8eef9;"
                + "font:16px/1.6 -apple-system,'PingFang SC',sans-serif;padding:24px 18px}"
                + ".box{max-width:520px;margin:0 auto}h1{font-size:22px;margin:8px 0 18px}"
                + ".addr{background:#151d2b;border:1px solid #253044;border-radius:16px;"
                + "padding:22px;text-align:center;margin-bottom:18px}"
                + ".ip{font-size:30px;font-weight:800;color:#4f8cff;letter-spacing:.5px}"
                + ".lbl{font-size:12px;color:#64748b;margin-bottom:6px}"
                + "button{width:100%;padding:15px;border:none;border-radius:14px;"
                + "background:#4f8cff;color:#fff;font-size:16px;font-weight:700;margin-bottom:10px}"
                + ".c{background:#151d2b;border:1px solid #253044;border-radius:16px;"
                + "padding:16px;margin-bottom:14px;font-size:14px}"
                + "h2{font-size:14px;color:#93a1b8;margin:0 0 10px;font-weight:600}"
                + "ol{margin:0;padding-left:20px}li{margin-bottom:8px}"
                + "code{background:#1b2434;padding:2px 6px;border-radius:6px;color:#7fb0ff}"
                + "</style></head><body><div class=box>"
                + "<h1>加入代理</h1>"
                + "<div class=addr><div class=lbl>代理服务器地址</div>"
                + "<div class=ip id=a>" + addr + "</div></div>"
                + "<button id=cp onclick=\"cp()\">复制地址</button>"
                + "<script>var A='" + addr + "';function cp(){"
                + "if(navigator.clipboard){navigator.clipboard.writeText(A).then(function(){"
                + "document.getElementById('cp').textContent='已复制 ✓';});}"
                + "else{var t=document.createElement('textarea');t.value=A;"
                + "document.body.appendChild(t);t.select();document.execCommand('copy');"
                + "document.body.removeChild(t);document.getElementById('cp').textContent='已复制 ✓';}}"
                + "</script>"
                + "<div class=c><h2>iPhone</h2><ol>"
                + "<li>设置 → 无线局域网</li>"
                + "<li>点当前 WiFi 右边的 <code>i</code></li>"
                + "<li>滑到最下面「配置代理」→ 手动</li>"
                + "<li>服务器填 <code>" + ip + "</code>，端口填 <code>" + port + "</code></li>"
                + "</ol></div>"
                + "<div class=c><h2>Android</h2><ol>"
                + "<li>设置 → WLAN</li>"
                + "<li>长按当前网络 → 修改网络</li>"
                + "<li>高级选项 → 代理 → 手动</li>"
                + "<li>主机名填 <code>" + ip + "</code>，端口填 <code>" + port + "</code></li>"
                + "</ol></div>"
                + "<div class=c style='color:#64748b;font-size:13px'>"
                + "注意：只对能连到这个地址的设备有效。如果你不在同一 WiFi，"
                + "先连上对方的热点/网络。</div>"
                + "</div></body></html>";
    }
}
