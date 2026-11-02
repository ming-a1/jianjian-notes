package com.heyu.notes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 客户端（Socket 直连）。
 * 使用它而不是 HttpURLConnection，是因为后者在部分运行时会拒绝 PROPFIND 等
 * WebDAV 方法；这里对 JVM / Android 行为完全一致，也便于单元测试。
 */
public final class DavHttp {

    public static final class Resp {
        public int code;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public byte[] body = new byte[0];
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static Resp request(String method, String urlStr, Map<String, String> extraHeaders, byte[] body) throws Exception {
        URI uri = URI.create(urlStr);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        int port = uri.getPort();
        if (port < 0) port = "https".equals(scheme) ? 443 : 80;
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        if (host == null) throw new IllegalArgumentException("URL 缺少主机名: " + urlStr);

        Socket socket;
        if ("https".equals(scheme)) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) f.createSocket();
            s.connect(new InetSocketAddress(host, port), 15000);
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.startHandshake();
            socket = s;
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 15000);
        }
        socket.setSoTimeout(30000);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host);
            if ((port != 80 && "http".equals(scheme)) || (port != 443 && "https".equals(scheme))) sb.append(':').append(port);
            sb.append("\r\n");
            sb.append("User-Agent: JianJian-Android/1.0\r\n");
            sb.append("Accept: */*\r\n");
            sb.append("Connection: close\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            if (body != null) sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) out.write(body);
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) break;
                head.write(b);
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') state++;
                else state = (b == '\r') ? 1 : 0;
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            Resp resp = new Resp();
            if (lines.length == 0 || lines[0].trim().isEmpty()) throw new Exception("服务器未返回有效响应");
            String[] status = lines[0].split(" ");
            resp.code = Integer.parseInt(status[1]);
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx <= 0) continue;
                resp.headers.put(lines[i].substring(0, idx).trim().toLowerCase(), lines[i].substring(idx + 1).trim());
            }
            String te = resp.headers.get("transfer-encoding");
            String cl = resp.headers.get("content-length");
            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            if (te != null && te.toLowerCase().contains("chunked")) {
                while (true) {
                    String line = readLine(in);
                    if (line == null) break;
                    int semi = line.indexOf(';');
                    if (semi >= 0) line = line.substring(0, semi);
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int size = Integer.parseInt(line, 16);
                    if (size == 0) { readLine(in); break; }
                    bodyBuf.write(readN(in, size));
                    readLine(in);
                }
            } else if (cl != null) {
                bodyBuf.write(readN(in, Integer.parseInt(cl.trim())));
            } else {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bodyBuf.write(buf, 0, n);
            }
            resp.body = bodyBuf.toByteArray();
            return resp;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) break;
            off += r;
        }
        if (off == n) return out;
        byte[] cut = new byte[off];
        System.arraycopy(out, 0, cut, 0, off);
        return cut;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b = -1;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        return bos.size() == 0 && b < 0 ? null : bos.toString("UTF-8");
    }

    private DavHttp() { }
}
