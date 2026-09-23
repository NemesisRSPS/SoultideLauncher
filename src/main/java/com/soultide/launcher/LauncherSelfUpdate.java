package com.soultide.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Self-update check/install for the launcher app itself (not the game client the Play button
 * launches - see UpdateChecker for that, which is what talks to the VPS's channel-aware proxy).
 * SoultideLauncher's own GitHub repo is public, unlike SoultideClient's private one, so this calls
 * GitHub's REST API directly - no VPS proxy, no token needed.
 * <p>
 * Built 2026-09-23 because the jpackage-built .exe has no update mechanism of its own - a player
 * who installed it once stays on that exact build forever otherwise, silently missing every future
 * launcher change (this is literally why the first Ctrl+B build didn't work for the person who
 * asked for it: they were still running a pre-Ctrl+B install with no way to know a newer one
 * existed).
 */
final class LauncherSelfUpdate {
    private static final String API_URL = "https://api.github.com/repos/NemesisRSPS/SoultideLauncher/releases/latest";
    private static final String INSTALLER_ASSET_NAME = "SoultideLauncher-Setup.exe";

    static final class LatestInfo {
        final String version;
        final String downloadUrl; // null if that release has no installer asset attached

        LatestInfo(String version, String downloadUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
        }
    }

    private LauncherSelfUpdate() {
    }

    /** The version baked into THIS running build (see build.yml's "Embed this build's own
     *  version" step, which writes this resource right before `mvn package`) - null during a dev
     *  run from unpacked classes, where that resource was never written; callers treat null as
     *  "nothing to compare against" rather than guessing. */
    static String ownVersion() {
        try (InputStream in = LauncherSelfUpdate.class.getResourceAsStream("/launcher-version.txt")) {
            if (in == null) return null;
            String v = readAll(in).trim();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    static LatestInfo fetchLatest() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("GitHub API returned " + status);
        }
        String json;
        try (InputStream in = conn.getInputStream()) {
            json = readAll(in);
        } finally {
            conn.disconnect();
        }
        JsonObject o = new JsonParser().parse(json).getAsJsonObject();
        String tag = o.get("tag_name").getAsString();
        String url = null;
        for (JsonElement el : o.getAsJsonArray("assets")) {
            JsonObject a = el.getAsJsonObject();
            if (INSTALLER_ASSET_NAME.equals(a.get("name").getAsString())) {
                url = a.get("browser_download_url").getAsString();
                break;
            }
        }
        return new LatestInfo(tag, url);
    }

    /** Downloads the new installer to a temp file and launches it interactively (the normal WiX
     *  installer window - deliberately not a silent install flag, which would need real testing on
     *  an actual Windows machine to trust; this way the player just clicks through the same
     *  installer UI they used the first time). Returns as soon as the installer process has
     *  started - the caller must exit immediately afterward, since Windows won't let that
     *  installer overwrite this process's own running exe/jar files while they're still open. */
    static void downloadAndLaunchInstaller(String url) throws IOException {
        Path tempFile = Files.createTempFile("SoultideLauncher-Setup-", ".exe");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("Download returned " + status);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
        new ProcessBuilder(tempFile.toString()).start();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
