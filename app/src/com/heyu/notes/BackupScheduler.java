package com.heyu.notes;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Map;

/** 定时备份调度：AlarmManager 单次触发 + 触发后重新排程（兼容 Doze） */
public final class BackupScheduler {

    private static final int REQ = 1001;

    public static void apply(Context ctx) {
        Map<String, Object> s = AppSettings.load(ctx);
        Map<String, Object> backup = Json.obj(s.get("backup"));
        boolean on = Json.bool(backup, "scheduled", false);
        int interval = Json.num(backup, "interval", 60);
        if (interval < 1) interval = 1;
        if (interval > 1440) interval = 1440;

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(ctx);
        am.cancel(pi);
        if (!on) return;
        long triggerAt = System.currentTimeMillis() + interval * 60000L;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void apply(Context ctx, Map<String, Object> backupPatch) {
        Map<String, Object> s = AppSettings.load(ctx);
        Map<String, Object> merged = AppSettings.merge(s, wrap(backupPatch));
        AppSettings.save(ctx, merged);
        apply(ctx);
    }

    private static Map<String, Object> wrap(Map<String, Object> backup) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        if (backup != null) m.put("backup", backup);
        return m;
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, BackupReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    private BackupScheduler() { }
}
