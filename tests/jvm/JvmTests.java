import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.heyu.notes.CryptoBox;
import com.heyu.notes.Json;
import com.heyu.notes.WebDavClient;

/** 纯 JVM 测试：JSON / 加密格式（与 Web 端互通）/ WebDAV 客户端 */
public class JvmTests {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String name) {
        if (cond) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        File dir = new File(args.length > 1 ? args[1] : "/tmp/jvmtests");
        dir.mkdirs();

        if ("phase1".equals(mode)) { phase1(dir); report(); return; }
        if ("phase2".equals(mode)) { phase2(dir); report(); return; }

        System.out.println("== JSON 解析/序列化");
        String json = "{\"a\":1,\"b\":[1,2.5,true,null],\"c\":\"中文\\n转义\\\"q\\\"\",\"d\":{\"e\":\"x\"}}";
        Object parsed = Json.parse(json);
        Map<String, Object> obj = Json.obj(parsed);
        ok(Json.num(obj, "a", 0) == 1, "数字解析");
        ok(Json.arr(obj.get("b")).size() == 4, "数组解析");
        ok("中文\n转义\"q\"".equals(Json.str(obj, "c", "")), "转义与中文");
        String again = Json.stringify(parsed);
        Map<String, Object> obj2 = Json.obj(Json.parse(again));
        ok(obj2.equals(obj), "序列化后再解析一致");

        System.out.println("== 加密（本地往返）");
        String password = "测试密码-abc-123";
        byte[] plain = "{\"format\":\"heyunotes.backup\",\"x\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
        byte[] enc = CryptoBox.encrypt(plain, password);
        ok(CryptoBox.isEncrypted(enc), "密文带 HNB1 魔数");
        ok(enc.length > plain.length + 40, "密文长度包含头部与 tag");
        ok(("HNB1").equals(new String(Arrays.copyOf(enc, 4), StandardCharsets.US_ASCII)), "魔数内容正确");
        ok(enc[4] == 1 && enc[5] == 1, "版本与 KDF 标识");
        int iter = ((enc[6] & 0xFF) << 24) | ((enc[7] & 0xFF) << 16) | ((enc[8] & 0xFF) << 8) | (enc[9] & 0xFF);
        ok(iter == 200000, "迭代次数 200000");
        ok(Arrays.equals(plain, CryptoBox.decrypt(enc, password)), "正确密码可解密");

        boolean wrongFailed = false;
        try { CryptoBox.decrypt(enc, "bad"); } catch (Exception e) { wrongFailed = true; }
        ok(wrongFailed, "错误密码解密失败");

        byte[] tampered = enc.clone();
        tampered[tampered.length - 1] ^= 0x01;
        boolean tamperFailed = false;
        try { CryptoBox.decrypt(tampered, password); } catch (Exception e) { tamperFailed = true; }
        ok(tamperFailed, "篡改密文被 GCM 拒绝");

        System.out.println("== Web 互通材料生成");
        Files.write(new File(dir, "java.enc").toPath(), enc);
        Files.write(new File(dir, "java.plain").toPath(), plain);
        System.out.println("  已生成 " + new File(dir, "java.enc").getAbsolutePath());

        System.out.println("== WebDAV 客户端（本地模拟服务器）");
        String davBase = System.getenv("DAV_URL");
        if (davBase != null && davBase.length() > 0) {
            WebDavClient c = new WebDavClient(davBase, "davtest", "davpass", "heyunotes");
            int put = c.put("notes-20260101-010101-full.hnb", "hello-dav".getBytes(StandardCharsets.UTF_8));
            ok(put >= 200 && put < 300, "PUT 上传（目录不存在时自动 MKCOL）: " + put);
            int testCode = c.test();
            ok(testCode == 207 || testCode == 200, "PROPFIND 测试连接: " + testCode);
            byte[] got = c.get("notes-20260101-010101-full.hnb");
            ok(got != null && "hello-dav".equals(new String(got, StandardCharsets.UTF_8)), "GET 下载");
            List<Map<String, Object>> items = c.list();
            boolean found = false;
            for (Map<String, Object> m : items) if ("notes-20260101-010101-full.hnb".equals(Json.str(m, "name", ""))) found = true;
            ok(found, "PROPFIND 列出备份文件（" + items.size() + " 项）");
            List<Map<String, Object>> afterPut = c.list();
            Map<String, Object> target = null;
            for (Map<String, Object> m : afterPut) if ("notes-20260101-010101-full.hnb".equals(Json.str(m, "name", ""))) target = m;
            ok(target != null && Json.num(target, "size", -1) == 9, "PROPFIND 返回文件大小");
            ok(c.delete("notes-20260101-010101-full.hnb"), "DELETE 删除");
            ok(c.get("notes-20260101-010101-full.hnb") == null, "删除后 GET 返回空");
            ok(new WebDavClient(davBase, "davtest", "wrong", "heyunotes").test() == 401, "错误密码返回 401");
        } else {
            System.out.println("  SKIP 未提供 DAV_URL");
        }

        report();
    }

    static void phase1(File dir) throws Exception {
        byte[] plain = "{\"msg\":\"来自 Java 的加密\",\"n\":42}".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(dir, "java2.plain").toPath(), plain);
        Files.write(new File(dir, "java2.enc").toPath(), CryptoBox.encrypt(plain, "cross-lang-pass"));
        System.out.println("  已写出 java2.enc");
    }

    static void phase2(File dir) throws Exception {
        File f = new File(dir, "node.enc");
        ok(f.exists(), "Node 生成的 node.enc 存在");
        if (!f.exists()) { report(); return; }
        byte[] enc = Files.readAllBytes(f.toPath());
        ok(CryptoBox.isEncrypted(enc), "Node 密文格式与 Java 一致");
        String text = new String(CryptoBox.decrypt(enc, "cross-lang-pass"), StandardCharsets.UTF_8);
        ok(text.contains("来自 Node"), "Java 可解密 Node 加密的内容: " + text);
        System.out.println("  NODE_DECRYPT_OK " + text.trim());
    }

    static void report() {
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
