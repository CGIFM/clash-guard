package com.flclashguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 关联启动的「触发器」：专门盯住通知栏那个 FlClash 快捷开关。
 *
 * FlClash 在 VPN 状态变化时会发这两个广播（从它的 ServiceBroadcastReceiver 声明看得到）：
 *   com.follow.clash.intent.action.VPN_START_REQUESTED
 *   com.follow.clash.intent.action.VPN_REVOKED
 *
 * 注意：这是**别的应用**注册的广播，我们静态注册监听它属于灰色地带，
 * 有些 ROM 会拦（Android 8+ 对隐式广播有限制）。所以这里只是第一道触发，
 * 真正可靠的是 WatchService 里对 VpnService 状态的轮询兜底。
 */
public class ClashEventReceiver extends BroadcastReceiver {

    private static final String TAG = "FlClashGuard";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        Log.i(TAG, "收到 FlClash 事件: " + action);

        try {
            if (action.contains("VPN_START_REQUESTED")) {
                // 代理刚被打开 → 关联启动自己
                if (App.prefs(context).getBoolean("assocStart", true)) {
                    WatchService.start(context);
                    KeepAliveJob.schedule(context);
                }
            } else if (action.contains("VPN_REVOKED")) {
                // 代理被关掉 → 按设置决定是否彻底退出
                Intent i = new Intent(context, WatchService.class);
                i.setAction(WatchService.ACTION_CLASH_DOWN);
                context.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "处理事件失败", t);
        }
    }
}
