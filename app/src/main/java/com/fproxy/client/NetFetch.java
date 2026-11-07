package com.fproxy.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class NetFetch {
    // 使用通用移动端浏览器 User-Agent，消除固定 "F-SingBox/0.1" 客户端网络指纹
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private NetFetch() {
    }

    public static byte[] bytes(String urlStr, int maxBytes) throws Exception {
        URL url = new URL(urlStr);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("仅支持 HTTPS");
        }
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
                conn.setRequestProperty("Accept", "*/*");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new IllegalArgumentException("重定向缺少 Location");
                    }
                    URL nextUrl = new URL(url, location);
                    if (!"https".equalsIgnoreCase(nextUrl.getProtocol())) {
                        throw new SecurityException("禁止降级到 HTTP");
                    }
                    url = nextUrl;
                    continue;
                }

                if (code != 200) {
                    throw new IOException("HTTP " + code + " (" + url.getHost() + ")");
                }

                long contentLength = conn.getContentLengthLong();
                if (contentLength > (long) maxBytes) {
                    throw new IllegalArgumentException("响应体过大");
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (out.size() + read > maxBytes) {
                            throw new IllegalArgumentException("文件超过大小限制");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("HTTPS 重定向次数过多");
    }

    public static void downloadTo(String urlStr, File target, long maxBytes, String expectedSha256) throws Exception {
        URL url = new URL(urlStr);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("仅允许 HTTPS APK");
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            for (int redirects = 0; redirects < 5; redirects++) {
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                try {
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(35000);
                    conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);

                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String location = conn.getHeaderField("Location");
                        if (location == null) {
                            throw new IOException("无重定向地址");
                        }
                        URL nextUrl = new URL(url, location);
                        if (!"https".equalsIgnoreCase(nextUrl.getProtocol())) {
                            throw new SecurityException("拒绝降级至 HTTP");
                        }
                        url = nextUrl;
                        continue;
                    }

                    if (code != 200) {
                        throw new IOException("APK 下载 HTTP " + code);
                    }

                    if (conn.getContentLengthLong() > maxBytes) {
                        throw new IOException("APK 文件过大");
                    }

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[16384];
                        long total = 0L;
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > maxBytes) {
                                throw new IOException("下载超过大小限制");
                            }
                            out.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                        }
                        out.flush();
                        out.getFD().sync();
                    }

                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) {
                        hex.append(String.format(Locale.US, "%02x", b & 0xff));
                    }
                    if (!hex.toString().equalsIgnoreCase(expectedSha256)) {
                        throw new SecurityException("APK SHA-256 不符");
                    }
                    return;
                } finally {
                    conn.disconnect();
                }
            }
            throw new IOException("HTTPS 重定向过多");
        } catch (Exception e) {
            target.delete();
            throw e;
        }
    }

    public static String text(String urlStr, int maxBytes) throws Exception {
        return new String(bytes(urlStr, maxBytes), StandardCharsets.UTF_8);
    }
}
