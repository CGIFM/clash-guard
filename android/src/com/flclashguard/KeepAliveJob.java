package com.flclashguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 保活作业：让守护服务在「被系统杀掉」之后自己回来。
 *
 * Android 8+ 不允许普通 App 随便起后台服务，但允许 JobScheduler 周期执行。
 * 这里用 15 分钟一次的极小成本任务（系统允许的最短周期）做「叫醒」，
 * 每次醒了就检查一次 WatchService 还在不在，不在就拉起来。
 *
 * 配合 START_STICKY 的前台服务，实测能显著提高存活率。
 */
public class KeepAliveJob extends JobService {

    static final int JOB_ID = 8801;
    private static final String TAG = "FlClashGuard";

    /** 排一个周期作业（幂等，重复调用不会重复排） */
    static void schedule(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 21) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            JobInfo.Builder b = new JobInfo.Builder(
                    JOB_ID, new ComponentName(c, KeepAliveJob.class))
                    .setPersisted(true)                 // 重启后仍然有效
                    .setPeriodic(15 * 60 * 1000);       // 系统允许的最短周期
            if (Build.VERSION.SDK_INT >= 24) {
                // 允许在低电量时也跑，避免被 Doze 拖死
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
                b.setImportantWhileForeground(false);
            }
            js.schedule(b.build());
            Log.i(TAG, "KeepAliveJob 已排期");
        } catch (Throwable t) {
            Log.w(TAG, "排期失败", t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean alive = WatchService.alive;
        Log.i(TAG, "KeepAliveJob 执行，服务存活=" + alive);
        if (!alive && App.prefs(this).getBoolean("autostart", true)) {
            WatchService.start(this);
        }
        // 同步完成，不需要 reschedule
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 被杀就重排
    }
}
