package com.heyu.notes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Map;

/** 定时备份闹钟触发：在后台线程执行备份，然后排下一次 */
public class BackupReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> s = AppSettings.load(context);
                    Map<String, Object> backup = Json.obj(s.get("backup"));
                    if (Json.bool(backup, "scheduled", false)) {
                        new BackupEngine(context).backupNow("scheduled");
                    }
                    BackupScheduler.apply(context);
                } catch (Throwable ignored) {
                } finally {
                    try { result.finish(); } catch (Throwable ignored2) { }
                }
            }
        }, "jianjian-scheduled-backup").start();
    }
}
