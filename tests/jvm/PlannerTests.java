import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.heyu.notes.BackupPlanner;
import com.heyu.notes.Json;

/** 备份规划（增量 / 完整 / 留存 / 命名）纯 JVM 测试 */
public class PlannerTests {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String name) { ok(cond, name, null); }

    static void ok(boolean cond, String name, String extra) {
        if (cond) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name + (extra == null ? "" : " :: " + extra)); }
    }

    static Map<String, Object> note(String id, long updatedAt) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("title", "笔记 " + id);
        m.put("content", "# 内容 " + id);
        m.put("createdAt", updatedAt);
        m.put("updatedAt", updatedAt);
        return m;
    }

    public static void main(String[] args) {
        long t0 = 1762000000000L;

        List<Map<String, Object>> notes = new ArrayList<Map<String, Object>>();
        notes.add(note("a", t0 - 10000));
        notes.add(note("b", t0 - 5000));

        System.out.println("== 首次备份为完整备份");
        Map<String, Object> idx = new LinkedHashMap<String, Object>();
        BackupPlanner.Plan p1 = BackupPlanner.plan(notes, new ArrayList<Map<String, Object>>(), idx, 7, t0, "test", "1.0.0");
        ok(!p1.skip, "首次不跳过");
        ok("full".equals(Json.str(p1.doc, "type", "")), "类型为 full");
        ok(p1.changed.size() == 2, "包含全部 2 条笔记");
        ok(Json.num(p1.index, "sinceFull", -1) == 0, "sinceFull 归零");
        ok(Json.num(p1.index, "lastFullAt", 0L) == t0, "记录 lastFullAt", Json.num(p1.index, "lastFullAt", 0L) + "");

        System.out.println("== 无变化时跳过");
        BackupPlanner.Plan p2 = BackupPlanner.plan(notes, new ArrayList<Map<String, Object>>(), p1.index, 7, t0 + 1000, "test", "1.0.0");
        ok(p2.skip, "无变化 → skip");

        System.out.println("== 修改一条笔记 → 增量只包含它");
        notes.get(0).put("updatedAt", t0 + 5000);
        BackupPlanner.Plan p3 = BackupPlanner.plan(notes, new ArrayList<Map<String, Object>>(), p1.index, 7, t0 + 6000, "test", "1.0.0");
        ok(!p3.skip, "有变化不跳过");
        ok("incremental".equals(Json.str(p3.doc, "type", "")), "类型为 incremental", Json.str(p3.doc, "type", ""));
        ok(p3.changed.size() == 1, "增量只含 1 条");
        ok("a".equals(Json.str(Json.obj(p3.changed.get(0)), "id", "")), "增量内容为被修改的笔记 a");
        ok(Json.num(p3.index, "sinceFull", -1) == 1, "sinceFull 递增");

        System.out.println("== 删除记录进入增量");
        List<Map<String, Object>> tombs = new ArrayList<Map<String, Object>>();
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("id", "b");
        t.put("at", t0 + 7000);
        tombs.add(t);
        BackupPlanner.Plan p4 = BackupPlanner.plan(notes, tombs, p3.index, 7, t0 + 8000, "test", "1.0.0");
        ok(Json.arr(p4.doc.get("removed")).size() == 1, "removed 记录 1 条");
        ok(Json.str(Json.obj(Json.arr(p4.doc.get("removed")).get(0)), "id", "").equals("b"), "removed 指向笔记 b");

        System.out.println("== 超过 keep-1 次增量后强制完整备份");
        BackupPlanner.Plan p5 = BackupPlanner.plan(notes, tombs, p4.index, 2, t0 + 9000, "test", "1.0.0");
        ok("full".equals(Json.str(p5.doc, "type", "")), "keep=2 时第二次增量转为 full", Json.str(p5.doc, "type", ""));
        ok(p5.changed.size() == 2, "完整备份包含全部笔记");

        System.out.println("== 超过 7 天强制完整备份");
        BackupPlanner.Plan p6 = BackupPlanner.plan(notes, tombs, p1.index, 30, t0 + 8L * 86400000L, "test", "1.0.0");
        ok("full".equals(Json.str(p6.doc, "type", "")), "8 天后为 full");

        System.out.println("== 文件命名与时间解析");
        String name = BackupPlanner.filename(t0, true, false);
        ok(name.matches("notes-\\d{8}-\\d{6}-full\\.hnb"), "完整备份命名", name);
        String encName = BackupPlanner.filename(t0, false, true);
        ok(encName.matches("notes-\\d{8}-\\d{6}-inc\\.hnbe"), "加密增量命名", encName);
        long parsed = BackupPlanner.parseTimestamp(name, 0);
        ok(parsed == (t0 / 1000) * 1000, "文件名可解析回时间戳", parsed + " vs " + t0);
        ok(BackupPlanner.parseTimestamp("bad-name.hnb", 7) == 7, "无法解析时使用兜底值");

        System.out.println("== 留存：保留最新 N 份");
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 9; i++) {
            Map<String, Object> it = new LinkedHashMap<String, Object>();
            it.put("id", "local:notes-" + i);
            it.put("ts", t0 + i * 1000L);
            items.add(it);
        }
        List<Map<String, Object>> drop = BackupPlanner.expired(items, 7);
        ok(drop.size() == 2, "9 份保留 7 份 → 删除 2 份", "drop=" + drop.size());
        java.util.Set<String> dropped = new java.util.HashSet<String>(); for (Map<String, Object> d : drop) dropped.add(Json.str(d, "id", ""));
        ok(dropped.contains("local:notes-0") && dropped.contains("local:notes-1") && dropped.size() == 2, "删除的是最旧的 2 份", dropped.toString());
        ok(BackupPlanner.expired(items, 20).isEmpty(), "不足保留数时不删除");

        System.out.println("== 备份文档结构");
        ok("heyunotes.backup".equals(Json.str(p1.doc, "format", "")), "format 标识");
        ok(Json.num(p1.doc, "version", 0) == 1, "version=1");
        ok(Json.obj(p1.doc.get("counts")).get("total") != null, "包含 counts.total");
        String json = Json.stringify(p1.doc);
        Map<String, Object> reparsed = Json.obj(Json.parse(json));
        ok(reparsed.get("notes") instanceof List, "序列化后可解析回 notes 数组");

        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
