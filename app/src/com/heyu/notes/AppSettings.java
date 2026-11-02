package com.heyu.notes;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 设置持久化 + 默认值合并 + 删除墓碑（供增量备份使用） */
public final class AppSettings {

    private static final String PREFS = "jj_settings";
    private static final String KEY = "json";
    private static final String TOMB = "tombstones";

    public static Map<String, Object> defaults() {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("theme", "light");
        s.put("fontSize", 16);
        Map<String, Object> wd = new LinkedHashMap<String, Object>();
        wd.put("url", "");
        wd.put("user", "");
        wd.put("pass", "");
        wd.put("path", "heyunotes");
        s.put("webdav", wd);
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("realtime", false);
        b.put("scheduled", false);
        b.put("interval", 60);
        b.put("encrypt", false);
        b.put("keep", 7);
        s.put("backup", b);
        Map<String, Object> idx = new LinkedHashMap<String, Object>();
        idx.put("lastIncludedAt", 0L);
        idx.put("lastFullAt", 0L);
        idx.put("sinceFull", 0);
        s.put("backupIndex", idx);
        s.put("lastBackupAt", 0L);
        s.put("lastBackupType", "");
        s.put("settingsVersion", 2);
        return s;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Map<String, Object> load(Context c) {
        String raw = prefs(c).getString(KEY, null);
        Map<String, Object> cur = defaults();
        if (raw == null) return cur;
        try {
            Map<String, Object> saved = Json.obj(Json.parse(raw));
            Map<String, Object> merged = merge(cur, saved);
            // v1 -> v2：备份策略默认改为关闭（老安装一次性纠正，其余设置保留）
            if (Json.num(saved, "settingsVersion", 0L) < 2) {
                Map<String, Object> backup = Json.obj(merged.get("backup"));
                backup.put("realtime", false);
                backup.put("scheduled", false);
                merged.put("backup", backup);
                merged.put("settingsVersion", 2);
                save(c, merged);
            }
            return merged;
        } catch (Exception e) {
            return cur;
        }
    }

    public static void save(Context c, Map<String, Object> s) {
        prefs(c).edit().putString(KEY, Json.stringify(s)).apply();
    }

    /** 合并部分更新（webdav / backup / backupIndex 做一层深合并） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> cur, Map<String, Object> inc) {
        if (cur == null) cur = defaults();
        if (inc == null) return cur;
        Map<String, Object> out = new LinkedHashMap<String, Object>(cur);
        for (Map.Entry<String, Object> e : inc.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof Map && cur.get(k) instanceof Map
                    && ("webdav".equals(k) || "backup".equals(k) || "backupIndex".equals(k))) {
                Map<String, Object> base = new LinkedHashMap<String, Object>((Map<String, Object>) cur.get(k));
                base.putAll((Map<String, Object>) v);
                out.put(k, base);
            } else if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /* ---------- 删除墓碑：增量备份需要知道哪些笔记被删除 ---------- */
    public static List<Object> tombstones(Context c) {
        String raw = prefs(c).getString(TOMB, null);
        if (raw == null) return new ArrayList<Object>();
        try {
            return Json.arr(Json.parse(raw));
        } catch (Exception e) {
            return new ArrayList<Object>();
        }
    }

    public static void addTombstone(Context c, String id) {
        List<Object> list = tombstones(c);
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("id", id);
        t.put("at", System.currentTimeMillis());
        list.add(t);
        long cutoff = System.currentTimeMillis() - 30L * 86400000L;
        List<Object> kept = new ArrayList<Object>();
        for (Object o : list) {
            Map<String, Object> m = Json.obj(o);
            if (Json.num(m, "at", 0) > cutoff) kept.add(m);
        }
        prefs(c).edit().putString(TOMB, Json.stringify(kept)).apply();
    }

    public static void clearTombstones(Context c) {
        prefs(c).edit().remove(TOMB).apply();
    }

    private AppSettings() { }
}
