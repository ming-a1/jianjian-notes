package com.heyu.notes;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
 * 备份引擎：本地快照 + WebDAV 上传 + 增量差异 + 加密 + 保留 N 份。
 * 与网站端 Backup 模块使用同一文件格式（.hnb 明文 / .hnbe 加密）。
 */
public class BackupEngine {

    public static final String FORMAT = "heyunotes.backup";
    public static final int VERSION = 1;

    private final Context ctx;
    private final NoteStore store;

    public BackupEngine(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new NoteStore(this.ctx);
    }

    public File dir() {
        File d = new File(ctx.getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /* ---------------- 备份 ---------------- */
    public Map<String, Object> backupNow(String reason) {
        return backupNow(reason, true);
    }

    /** localCopy=false 时只上传 WebDAV，不在本机保留副本（手动「立即备份」用） */
    public Map<String, Object> backupNow(String reason, boolean localCopy) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> s = AppSettings.load(ctx);
            Map<String, Object> backup = Json.obj(s.get("backup"));
            Map<String, Object> webdav = Json.obj(s.get("webdav"));
            boolean encrypt = Json.bool(backup, "encrypt", false);
            String pwd = "";
            if (encrypt) {
                pwd = SecureStore.getBackupPassword(ctx);
                if (pwd.length() == 0) {
                    out.put("ok", false);
                    out.put("error", "NO_PASSWORD");
                    return out;
                }
            }
            int keep = Json.num(backup, "keep", 7);
            if (keep < 1) keep = 1;
            boolean davReady = WebDavClient.configured(webdav);
            if (!localCopy && !davReady) {
                out.put("ok", false);
                out.put("error", "NO_WEBDAV");
                return out;
            }
            long now = System.currentTimeMillis();
            List<Map<String, Object>> tombs = new ArrayList<Map<String, Object>>();
            for (Object o : AppSettings.tombstones(ctx)) tombs.add(Json.obj(o));
            BackupPlanner.Plan plan = BackupPlanner.plan(store.list(), tombs, Json.obj(s.get("backupIndex")),
                    keep, now, Build.MANUFACTURER + " " + Build.MODEL, BuildConfigVars.VERSION_NAME);
            if (plan.skip) {
                out.put("ok", true);
                out.put("skipped", true);
                out.put("message", "没有变化，跳过本次备份");
                return out;
            }
            Map<String, Object> doc = plan.doc;
            boolean needFull = plan.needFull;
            List<Object> changed = plan.changed;
            List<Object> removed = plan.removed;

            byte[] plain = Json.stringify(doc).getBytes(StandardCharsets.UTF_8);
            byte[] blob = encrypt ? CryptoBox.encrypt(plain, pwd) : plain;
            String name = BackupPlanner.filename(now, needFull, encrypt);
            String davMsg = "";
            List<Map<String, Object>> drop = new ArrayList<Map<String, Object>>();
            if (localCopy) {
                File f = new File(dir(), name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(blob);
                fos.flush();
                fos.getFD().sync();
                fos.close();
            }

            if (davReady) {
                try {
                    WebDavClient dav = WebDavClient.from(webdav);
                    int code = dav.put(name, blob);
                    if (code >= 200 && code < 300) {
                        davMsg = localCopy ? "（已上传 WebDAV）" : "（仅上传 WebDAV，本机未保留副本）";
                    } else {
                        davMsg = "（WebDAV 上传失败：HTTP " + code + "）";
                        if (!localCopy) {
                            out.put("ok", false);
                            out.put("error", "WebDAV 上传失败：HTTP " + code);
                            return out;
                        }
                    }
                } catch (Exception e) {
                    davMsg = "（WebDAV 上传失败：" + e.getMessage() + "）";
                    if (!localCopy) {
                        out.put("ok", false);
                        out.put("error", "WebDAV 上传失败：" + e.getMessage());
                        return out;
                    }
                }
            }

            // 保留份数：多余的旧备份删除（本地文件 + WebDAV 同名文件）
            drop = localCopy ? collectExpired(keep) : new ArrayList<Map<String, Object>>();
            for (Map<String, Object> item : drop) {
                deleteBackupFile(Json.str(item, "name", ""), davReady, davReady ? WebDavClient.from(webdav) : null);
            }
            if (!localCopy && davReady) {
                trimRemote(keep, webdav);
            }

            s.put("backupIndex", plan.index);
            s.put("lastBackupAt", now);
            s.put("lastBackupType", needFull ? "full" : "incremental");
            AppSettings.save(ctx, s);

            out.put("ok", true);
            out.put("type", needFull ? "full" : "incremental");
            out.put("file", name);
            out.put("count", plan.changed.size());
            out.put("removed", plan.removed.size());
            out.put("size", blob.length);
            out.put("encrypted", encrypt);
            out.put("message", (needFull ? "完整备份" : "增量备份") + "完成" + davMsg + (drop.size() > 0 ? "，清理了 " + drop.size() + " 份旧备份" : ""));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
    }

    private void trimRemote(int keep, Map<String, Object> webdav) {
        try {
            List<Map<String, Object>> remote = WebDavClient.from(webdav).list();
            List<String> names = new ArrayList<String>();
            for (Map<String, Object> item : remote) {
                String n = Json.str(item, "name", "");
                if (n.endsWith(".hnb") || n.endsWith(".hnbe")) names.add(n);
            }
            Collections.sort(names, Collections.reverseOrder());
            for (int i = keep; i < names.size(); i++) {
                try { WebDavClient.from(webdav).delete(names.get(i)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    /** 超出 keep 份数的旧文件（按文件名时间倒序） */
    private List<Map<String, Object>> collectExpired(int keep) {
        return BackupPlanner.expired(localList(), keep);
    }

    private void deleteBackupFile(String name, boolean dav, WebDavClient client) {
        if (name == null || name.length() == 0) return;
        File f = new File(dir(), name);
        if (f.exists()) f.delete();
        if (dav && client != null) {
            try { client.delete(name); } catch (Exception ignored) { }
        }
    }

    /* ---------------- 列表 / 读取 / 删除 ---------------- */
    private List<Map<String, Object>> localList() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File[] files = dir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".hnb") && !name.endsWith(".hnbe")) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", "local:" + name);
            m.put("source", "local");
            m.put("name", name);
            m.put("ts", BackupPlanner.parseTimestamp(name, f.lastModified()));
            m.put("size", f.length());
            m.put("type", name.contains("-full") ? "full" : "incremental");
            m.put("encrypted", name.endsWith(".hnbe"));
            out.add(m);
        }
        return out;
    }

    public int localBackupCount() {
        return localList().size();
    }

    public List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> items = localList();
        Map<String, Object> s = AppSettings.load(ctx);
        Map<String, Object> webdav = Json.obj(s.get("webdav"));
        if (WebDavClient.configured(webdav)) {
            try {
                WebDavClient dav = WebDavClient.from(webdav);
                List<Map<String, Object>> remote = dav.list();
                for (Map<String, Object> r : remote) {
                    String name = Json.str(r, "name", "");
                    if (name.length() == 0) continue;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", "dav:" + name);
                    m.put("source", "webdav");
                    m.put("name", name);
                    long ts = Json.num(r, "modified", 0L);
                    m.put("ts", ts > 0 ? ts : BackupPlanner.parseTimestamp(name, 0L));
                    m.put("size", Json.num(r, "size", 0L));
                    m.put("type", name.contains("-full") ? "full" : "incremental");
                    m.put("encrypted", name.endsWith(".hnbe"));
                    items.add(m);
                }
            } catch (Exception ignored) { }
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Long.compare(Json.num(b, "ts", 0L), Json.num(a, "ts", 0L));
            }
        });
        return items;
    }

    public byte[] readBackup(String id) throws Exception {
        if (id == null) return null;
        String name;
        if (id.startsWith("local:")) {
            name = id.substring(6);
            File f = new File(dir(), name);
            if (!f.exists()) return null;
            return readFile(f);
        }
        if (id.startsWith("dav:")) {
            name = id.substring(4);
            Map<String, Object> s = AppSettings.load(ctx);
            Map<String, Object> webdav = Json.obj(s.get("webdav"));
            if (!WebDavClient.configured(webdav)) return null;
            return WebDavClient.from(webdav).get(name);
        }
        return null;
    }

    public Map<String, Object> getBackupInfo(String id) throws Exception {
        byte[] bytes = readBackup(id);
        if (bytes == null) return null;
        String name = id.substring(id.indexOf(':') + 1);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("base64", java.util.Base64.getEncoder().encodeToString(bytes));
        m.put("size", bytes.length);
        m.put("encrypted", CryptoBox.isEncrypted(bytes));
        m.put("type", name.contains("-full") ? "full" : "incremental");
        m.put("source", id.startsWith("dav:") ? "webdav" : "local");
        m.put("ts", BackupPlanner.parseTimestamp(name, 0L));
        m.put("id", id);
        return m;
    }

    public boolean deleteBackup(String id) {
        if (id == null) return false;
        Map<String, Object> s = AppSettings.load(ctx);
        Map<String, Object> webdav = Json.obj(s.get("webdav"));
        boolean davReady = WebDavClient.configured(webdav);
        WebDavClient dav = davReady ? WebDavClient.from(webdav) : null;
        String name;
        if (id.startsWith("local:")) {
            name = id.substring(6);
            File f = new File(dir(), name);
            boolean ok = !f.exists() || f.delete();
            if (davReady) dav.delete(name);
            return ok;
        }
        if (id.startsWith("dav:")) {
            name = id.substring(4);
            if (davReady) return dav.delete(name);
        }
        return false;
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    public Map<String, Object> testWebdav() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> s = AppSettings.load(ctx);
        Map<String, Object> webdav = Json.obj(s.get("webdav"));
        if (!WebDavClient.configured(webdav)) {
            out.put("ok", false);
            out.put("message", "请先填写 WebDAV 服务器地址");
            return out;
        }
        try {
            int code = WebDavClient.from(webdav).test();
            out.put("ok", code >= 200 && code < 300);
            if (code == 401 || code == 403) out.put("message", "认证失败（HTTP " + code + "），请检查账号或应用密码");
            else if (code == 404) out.put("message", "目录不存在（HTTP 404），请检查地址与备份目录");
            else if (code >= 200 && code < 300) out.put("message", "连接成功（HTTP " + code + "）");
            else out.put("message", "服务器返回 HTTP " + code);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "连接失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return out;
    }
}
