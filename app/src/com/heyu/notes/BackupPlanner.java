package com.heyu.notes;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 备份规划（纯逻辑，无 Android 依赖，可在 JVM 上直接测试）。
 * 决策：何时做完整备份、哪些笔记进入增量、删除墓碑、索引推进、文件命名与留存。
 */
public final class BackupPlanner {

    public static final String FORMAT = "heyunotes.backup";
    public static final int VERSION = 1;
    private static final long DAY = 86400000L;

    public static final class Plan {
        public final Map<String, Object> doc;
        public final Map<String, Object> index;
        public final List<Object> changed;
        public final List<Object> removed;
        public final boolean needFull;
        public final boolean skip;

        Plan(Map<String, Object> doc, Map<String, Object> index, List<Object> changed,
             List<Object> removed, boolean needFull, boolean skip) {
            this.doc = doc;
            this.index = index;
            this.changed = changed;
            this.removed = removed;
            this.needFull = needFull;
            this.skip = skip;
        }
    }

    @SuppressWarnings("unchecked")
    public static Plan plan(List<Map<String, Object>> notes, List<Map<String, Object>> tombstones,
                            Map<String, Object> idx, int keep, long now, String device, String appVersion) {
        if (keep < 1) keep = 1;
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        if (idx != null) index.putAll(idx);
        long lastIncludedAt = Json.num(index, "lastIncludedAt", 0L);
        long lastFullAt = Json.num(index, "lastFullAt", 0L);
        int sinceFull = Json.num(index, "sinceFull", 0);
        boolean hasBase = lastFullAt > 0;
        boolean needFull = !hasBase || sinceFull >= Math.max(1, keep - 1) || (now - lastFullAt) > 7 * DAY;

        List<Object> changed = new ArrayList<Object>();
        for (Map<String, Object> n : notes) {
            long u = Json.num(n, "updatedAt", 0L);
            if (needFull || u > lastIncludedAt) changed.add(n);
        }
        List<Object> removed = new ArrayList<Object>();
        long cutoff = now - 30 * DAY;
        if (tombstones != null) {
            for (Map<String, Object> t : tombstones) {
                long at = Json.num(t, "at", 0L);
                if (at > cutoff) {
                    Map<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("id", Json.str(t, "id", ""));
                    r.put("at", at);
                    removed.add(r);
                }
            }
        }

        Map<String, Object> nextIndex = new LinkedHashMap<String, Object>();
        nextIndex.put("lastIncludedAt", now);
        nextIndex.put("lastFullAt", needFull ? now : lastFullAt);
        nextIndex.put("sinceFull", needFull ? 0 : sinceFull + 1);

        boolean skip = !needFull && changed.isEmpty() && removed.isEmpty();
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("format", FORMAT);
        doc.put("version", VERSION);
        doc.put("type", needFull ? "full" : "incremental");
        doc.put("createdAt", now);
        doc.put("device", device == null ? "android" : device);
        doc.put("appVersion", appVersion == null ? "1.0.0" : appVersion);
        doc.put("notes", changed);
        doc.put("removed", removed);
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("notes", changed.size());
        counts.put("removed", removed.size());
        counts.put("total", notes == null ? 0 : notes.size());
        doc.put("counts", counts);
        return new Plan(doc, nextIndex, changed, removed, needFull, skip);
    }

    public static String filename(long ts, boolean full, boolean encrypted) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "notes-" + f.format(new Date(ts)) + (full ? "-full" : "-inc") + (encrypted ? ".hnbe" : ".hnb");
    }

    public static long parseTimestamp(String name, long fallback) {
        try {
            String core = name.replace("notes-", "");
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            return f.parse(core.substring(0, 15)).getTime();
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 按时间倒序保留最新 keep 份，返回需要删除的条目 */
    public static List<Map<String, Object>> expired(List<Map<String, Object>> items, int keep) {
        if (keep < 1) keep = 1;
        List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>(items);
        Collections.sort(sorted, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Long.compare(Json.num(b, "ts", 0L), Json.num(a, "ts", 0L));
            }
        });
        List<Map<String, Object>> drop = new ArrayList<Map<String, Object>>();
        for (int i = keep; i < sorted.size(); i++) drop.add(sorted.get(i));
        return drop;
    }

    private BackupPlanner() { }
}
