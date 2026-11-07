package com.fproxy.client;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class RuleResources {
    public static final String GEOIP_CN_NAME = "geoip-cn.srs";
    public static final String GEOSITE_CN_NAME = "geosite-cn.srs";

    public static String mainland(Context context) throws IOException {
        return ensureRuleSet(context, GEOIP_CN_NAME, true);
    }

    /**
     * 获取或释放 geosite-cn.srs 规则集路径。
     * 若用户已通过 UpdateManager 下载或 assets 中内置了 geosite-cn.srs，则返回其绝对路径；
     * 若均不存在则返回 null（允许降级为仅使用 geoip-cn.srs）。
     */
    public static String geositeCn(Context context) {
        try {
            return ensureRuleSet(context, GEOSITE_CN_NAME, false);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String ensureRuleSet(Context context, String fileName, boolean required) throws IOException {
        File rulesDir = new File(context.getFilesDir(), "rules");
        if (!rulesDir.isDirectory() && !rulesDir.mkdirs()) {
            throw new IOException("无法创建分流规则目录");
        }

        File targetFile = new File(rulesDir, fileName);
        if (isValidSrsFile(targetFile)) {
            return targetFile.getAbsolutePath();
        }

        File tempFile = new File(rulesDir, fileName + "-bundled.tmp");
        try (InputStream in = context.getAssets().open(fileName)) {
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.getFD().sync();
            }
            Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            return targetFile.getAbsolutePath();
        } catch (FileNotFoundException e) {
            tempFile.delete();
            if (required) {
                throw e;
            }
            return null;
        } catch (IOException e) {
            tempFile.delete();
            throw e;
        }
    }

    public static boolean isValidSrsFile(File file) {
        if (file == null || !file.exists() || file.length() <= 8L) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 'S' && in.read() == 'R' && in.read() == 'S';
        } catch (IOException e) {
            return false;
        }
    }
}
