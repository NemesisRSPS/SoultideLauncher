package com.soultide.launcher;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Talks to the VPS's credential-free /launcher/* routes (see SoultideCacheEditor's WebMain -
 * registerLauncherRoutes) - this process never sees or needs a GitHub token, that lives only on
 * the VPS.
 */
final class UpdateChecker {
    private static final Gson GSON = new Gson();

    static final class VersionInfo {
        String version;
        @SerializedName("jarAsset")
        String jarAsset;
        List<String> assets;
    }

    static VersionInfo fetchLatestVersion() throws IOException {
        String json = httpGetString(LauncherConfig.VPS_BASE_URL + "/launcher/version");
        return GSON.fromJson(json, VersionInfo.class);
    }

    /** Downloads one asset (the jar, or a launcher-assets/* file) to dest, reporting 0-100 progress. */
    static void downloadAsset(String assetName, Path dest, IntConsumer onProgress) throws IOException {
        URL url = new URL(LauncherConfig.VPS_BASE_URL + "/launcher/download/" + assetName);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("Server returned " + status + " for " + assetName);
        }
        long total = conn.getContentLengthLong();
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.createDirectories(dest.getParent());
        try (InputStream in = conn.getInputStream();
             java.io.OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            long downloaded = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int percent = (int) (downloaded * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        onProgress.accept(percent);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    static String readLocalVersion() {
        try {
            if (!Files.isRegularFile(LauncherConfig.VERSION_FILE)) return null;
            return new String(Files.readAllBytes(LauncherConfig.VERSION_FILE), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void writeLocalVersion(String version) throws IOException {
        Files.createDirectories(LauncherConfig.CACHE_DIR);
        Files.write(LauncherConfig.VERSION_FILE, version.getBytes(StandardCharsets.UTF_8));
    }

    private static String httpGetString(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("Server returned " + status + " for " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    private UpdateChecker() {
    }
}
