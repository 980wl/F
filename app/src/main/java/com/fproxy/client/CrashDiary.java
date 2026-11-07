package com.fproxy.client;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.regex.Pattern;

public class CrashDiary {
    private static final String[] LOG_FILES = new String[]{
            "core/CrashReport-core.log",
            "probe/CrashReport-probe.log",
            "last-error.log",
            "CrashReport-.log"
    };

    // 脱敏正则：隐藏异常堆栈或日志中可能出现的订阅查询参数、密码、UUID 与节点凭据
    private static final Pattern URL_USERINFO_PATTERN =
            Pattern.compile("(?i)(https?://)[^/\\s\"'<>@]+@");
    private static final Pattern URL_QUERY_TOKEN_PATTERN =
            Pattern.compile("(?i)(https?://[^\\s\"'<>?]+)\\?[^\\s\"'<>]+");
    private static final Pattern CREDENTIAL_KV_PATTERN =
            Pattern.compile("(?i)(\"(?:password|uuid|private_key|public_key|short_id|auth|token)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    public static void install(Context context, String source) {
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            record(appContext, source, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                Process.killProcess(Process.myPid());
            }
        });
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String sanitized = URL_USERINFO_PATTERN.matcher(input).replaceAll("$1***@");
        sanitized = URL_QUERY_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1?[REDACTED]");
        sanitized = CREDENTIAL_KV_PATTERN.matcher(sanitized).replaceAll("$1***$3");
        sanitized = UUID_PATTERN.matcher(sanitized).replaceAll("[UUID-REDACTED]");
        return sanitized;
    }

    public static String read(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String relPath : LOG_FILES) {
            File file = new File(context.getFilesDir(), relPath);
            if (!file.exists() || file.length() == 0L) {
                continue;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long seekPos = Math.max(0L, raf.length() - 12000L);
                raf.seek(seekPos);
                int len = (int) (raf.length() - raf.getFilePointer());
                byte[] buf = new byte[len];
                raf.readFully(buf);
                sb.append(relPath).append('\n');
                sb.append(redact(new String(buf, StandardCharsets.UTF_8))).append('\n');
            } catch (Exception ignored) {
            }
        }
        return sb.length() == 0 ? "暂无错误记录。" : sb.toString();
    }

    public static void record(Context context, String stage, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                pw.println(new Date() + "  " + stage);
                if (throwable != null) {
                    throwable.printStackTrace(pw);
                }
            }
            String redacted = redact(sw.toString());
            File logFile = new File(context.getFilesDir(), "last-error.log");
            try (FileOutputStream out = new FileOutputStream(logFile, false)) {
                out.write(redacted.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
