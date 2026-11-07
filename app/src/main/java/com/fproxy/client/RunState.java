package com.fproxy.client;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.json.JSONObject;

public class RunState {
    public static boolean active(JSONObject state) {
        if (state == null) {
            return false;
        }
        String phase = state.optString("phase");
        return "starting".equals(phase) || "connected".equals(phase) || "stopping".equals(phase);
    }

    public static boolean alive(Context context, int pid) {
        if (pid <= 0) {
            return false;
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return true;
        }
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return true;
        }
        int myUid = Process.myUid();
        for (ActivityManager.RunningAppProcessInfo info : procs) {
            if (info.pid == pid && info.uid == myUid) {
                return true;
            }
        }
        return false;
    }

    public static String label(JSONObject state) {
        if (state == null) {
            return "未连接";
        }
        String phase = state.optString("phase");
        switch (phase) {
            case "failed":
                return "连接失败";
            case "connected":
                return "已连接 · 大陆直连";
            case "starting":
                return "正在连接";
            case "stopping":
                return "正在断开";
            default:
                return "未连接";
        }
    }

    /**
     * 返回状态文件的最后修改时间戳（毫秒），供轮询方快速判断文件是否发生变更，
     * 避免在状态未变化时每 900ms 重复读取磁盘与解析 JSON。
     */
    public static long lastModified(Context context, String name) {
        File file = new File(context.getFilesDir(), name + ".json");
        return file.exists() ? file.lastModified() : 0L;
    }

    public static JSONObject read(Context context, String name) {
        try {
            File file = new File(context.getFilesDir(), name + ".json");
            if (!file.exists()) {
                return new JSONObject();
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject vpn(Context context) {
        JSONObject state = read(context, "vpn-state");
        String phase = state.optString("phase", "idle");
        if ("starting".equals(phase) || "connected".equals(phase) || "stopping".equals(phase)) {
            if (System.currentTimeMillis() - state.optLong("updated") > 3000L) {
                if (!alive(context, state.optInt("pid"))) {
                    try {
                        state.put("phase", "failed")
                                .put("error", "内核进程意外退出，请查看页面下方的错误日志。");
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return state;
    }

    public static void vpn(Context context, String phase, String error, String node) {
        try {
            JSONObject obj = new JSONObject()
                    .put("phase", phase)
                    .put("error", error == null ? "" : error)
                    .put("node", node == null ? "" : node)
                    .put("pid", Process.myPid());
            write(context, "vpn-state", obj);
        } catch (Exception ignored) {
        }
    }

    /**
     * 使用跨进程文件锁（FileLock）+ 临时文件原子替换（ATOMIC_MOVE）保证多进程并发写入安全。
     */
    public static synchronized void write(Context context, String name, JSONObject payload) {
        File filesDir = context.getFilesDir();
        File targetFile = new File(filesDir, name + ".json");
        File lockFile = new File(filesDir, name + ".lock");
        File tempFile = new File(filesDir, name + "-" + Process.myPid() + ".tmp");
        try {
            payload.put("updated", System.currentTimeMillis());
            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);

            try (RandomAccessFile lockRaf = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockRaf.getChannel();
                 FileLock ignored = lockChannel.lock()) {

                try (FileOutputStream out = new FileOutputStream(tempFile)) {
                    out.write(data);
                    out.getFD().sync();
                }
                Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (Exception e) {
            tempFile.delete();
            if (BuildConfig.DEBUG) {
                Log.e("F-State", "Write failed", e);
            }
        }
    }
}
