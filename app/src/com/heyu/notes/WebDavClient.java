package com.heyu.notes;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 最小 WebDAV 客户端：PUT / GET / DELETE / MKCOL / PROPFIND（坚果云、Nextcloud、群晖等） */
public class WebDavClient {

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:getlastmodified/>"
                    + "<d:resourcetype/></d:prop></d:propfind>";

    private final String baseUrl;
    private final String user;
    private final String pass;
    private final String dir;

    public WebDavClient(String baseUrl, String user, String pass, String dir) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.dir = dir == null ? "" : dir.replaceAll("^/+|/+$", "");
    }

    public static boolean configured(Map<String, Object> webdav) {
        return webdav != null && Json.str(webdav, "url", "").trim().length() > 0;
    }

    public static WebDavClient from(Map<String, Object> webdav) {
        return new WebDavClient(Json.str(webdav, "url", ""), Json.str(webdav, "user", ""),
                Json.str(webdav, "pass", ""), Json.str(webdav, "path", "heyunotes"));
    }

    private String url(String name) {
        String b = baseUrl;
        if (!b.endsWith("/")) b += "/";
        StringBuilder sb = new StringBuilder(b);
        if (dir.length() > 0) {
            for (String p : dir.split("/")) if (p.length() > 0) sb.append(enc(p)).append('/');
        }
        if (name != null) sb.append(enc(name));
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private Map<String, String> headers(String depth, String contentType) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (user.length() > 0) {
            String token = java.util.Base64.getEncoder().encodeToString(
                    (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            h.put("Authorization", "Basic " + token);
        }
        if (depth != null) h.put("Depth", depth);
        if (contentType != null) h.put("Content-Type", contentType);
        return h;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public int test() throws Exception {
        return DavHttp.request("PROPFIND", url(null), headers("0", "application/xml; charset=utf-8"), utf8(PROPFIND_BODY)).code;
    }

    public int put(String name, byte[] data) throws Exception {
        int code = DavHttp.request("PUT", url(name), headers(null, "application/octet-stream"), data).code;
        if (code == 404 || code == 409) {
            mkcol();
            code = DavHttp.request("PUT", url(name), headers(null, "application/octet-stream"), data).code;
        }
        return code;
    }

    public int mkcol() {
        try {
            return DavHttp.request("MKCOL", url(null), headers(null, null), null).code;
        } catch (Exception e) {
            return -1;
        }
    }

    public byte[] get(String name) throws Exception {
        DavHttp.Resp r = DavHttp.request("GET", url(name), headers(null, null), null);
        if (r.code >= 200 && r.code < 300) return r.body;
        return null;
    }

    public boolean delete(String name) {
        try {
            int code = DavHttp.request("DELETE", url(name), headers(null, null), null).code;
            return (code >= 200 && code < 300) || code == 404;
        } catch (Exception e) {
            return false;
        }
    }

    /** PROPFIND Depth:1 列出备份文件 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            DavHttp.Resp r = DavHttp.request("PROPFIND", url(null), headers("1", "application/xml; charset=utf-8"), utf8(PROPFIND_BODY));
            if (r.code >= 400) return out;
            String xml = r.text();
            String[] blocks = xml.split("(?i)<(?:d|D|\\w+):response[ >]");
            Pattern hrefPat = Pattern.compile("(?i)<(?:d|D|\\w+):href>([^<]+)<");
            Pattern lenPat = Pattern.compile("(?i)<(?:d|D|\\w+):getcontentlength>(\\d+)<");
            Pattern datePat = Pattern.compile("(?i)<(?:d|D|\\w+):getlastmodified>([^<]+)<");
            for (String b : blocks) {
                Matcher hm = hrefPat.matcher(b);
                if (!hm.find()) continue;
                String href = URLDecoder.decode(hm.group(1), "UTF-8");
                String name = href.endsWith("/") ? "" : href.substring(href.lastIndexOf('/') + 1);
                if (!name.endsWith(".hnb") && !name.endsWith(".hnbe")) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", name);
                Matcher lm = lenPat.matcher(b);
                m.put("size", lm.find() ? Long.parseLong(lm.group(1)) : 0);
                Matcher dm = datePat.matcher(b);
                if (dm.find()) {
                    try {
                        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
                        m.put("modified", f.parse(dm.group(1).trim()).getTime());
                    } catch (Exception ignored) { }
                }
                out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
