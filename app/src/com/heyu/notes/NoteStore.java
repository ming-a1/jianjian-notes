package com.heyu.notes;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 笔记存储：应用私有目录 notes/<id>.json，天然免权限、免挂载依赖 */
public class NoteStore {

    private final File dir;

    public NoteStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "notes");
        if (!dir.exists()) dir.mkdirs();
    }

    public File dir() { return dir; }

    private static String safeId(String id) {
        if (id == null) return "";
        return id.replaceAll("[^A-Za-z0-9_-]", "");
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                Map<String, Object> m = Json.obj(Json.parse(read(f)));
                if (m.get("id") == null) m.put("id", f.getName().substring(0, f.getName().length() - 5));
                out.add(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public Map<String, Object> get(String id) {
        File f = new File(dir, safeId(id) + ".json");
        if (!f.exists()) return null;
        try {
            return Json.obj(Json.parse(read(f)));
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> save(Map<String, Object> note) throws Exception {
        String id = safeId(Json.str(note, "id", ""));
        if (id.length() == 0) {
            id = "n" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 4096), 36);
        }
        long now = System.currentTimeMillis();
        note.put("id", id);
        if (note.get("createdAt") == null) note.put("createdAt", now);
        if (note.get("updatedAt") == null) note.put("updatedAt", now);
        File tmp = new File(dir, id + ".json.tmp");
        File dst = new File(dir, id + ".json");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(Json.stringify(note).getBytes(StandardCharsets.UTF_8));
        fos.flush();
        fos.getFD().sync();
        fos.close();
        if (!tmp.renameTo(dst)) {
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        }
        return note;
    }

    public boolean delete(String id) {
        File f = new File(dir, safeId(id) + ".json");
        return !f.exists() || f.delete();
    }

    public void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    public long bytes() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.length();
        return total;
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    public static Map<String, Object> newNote() {
        Map<String, Object> n = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();
        n.put("title", "");
        n.put("content", "");
        n.put("createdAt", now);
        n.put("updatedAt", now);
        n.put("pinned", false);
        n.put("renderMode", false);
        return n;
    }
}
