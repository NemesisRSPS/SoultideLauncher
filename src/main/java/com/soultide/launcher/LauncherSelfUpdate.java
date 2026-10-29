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
    /** The cross-platform jar (the website's Mac / Linux download) - what a non-Windows launcher updates itself from. */
    private static final String JAR_ASSET_NAME = "SoultideLauncher.jar";
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    static final class LatestInfo {
        final String version;
        final String downloadUrl; // null if that release has no installer asset attached
        final String jarUrl; // null if it has no jar

        LatestInfo(String version, String downloadUrl, String jarUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.jarUrl = jarUrl;
        }

        /** Something this platform can update from: the installer on Windows, the jar everywhere else (2026-10-29: a Mac
         *  was offered the Windows installer, which can't run there). */
        boolean available() {
            return WINDOWS ? downloadUrl != null : jarUrl != null;
        }
    }

    /** Installs {@code latest} the way this platform can; the caller exits right after. */
    static void install(LatestInfo latest) throws IOException {
        if (WINDOWS) downloadAndLaunchInstaller(latest.downloadUrl);
        else replaceJarAndRestart(latest.jarUrl);
    }

    /**
     * Mac / Linux, run from the cross-platform jar: the new jar is downloaded beside this one, moved over it (a rename, so
     * this running copy keeps its open file) and started on the same Java. Throws if this isn't a writable jar (a dev run,
     * or a read-only folder) - the player then gets it from the website instead.
     */
    static void replaceJarAndRestart(String url) throws IOException {
        java.io.File own;
        try {
            own = new java.io.File(LauncherSelfUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("can't find this launcher's own jar - download the new one from soultidersps.com");
        }
        if (!own.isFile() || !own.getParentFile().canWrite()) {
            throw new IOException("can't replace " + own + " - download the new one from soultidersps.com");
        }
        Path fresh = own.getParentFile().toPath().resolve(own.getName() + ".new");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("Download returned " + conn.getResponseCode());
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, fresh, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
        try {
            Files.move(fresh, own.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(fresh, own.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String javaExe = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        new ProcessBuilder(javaExe, "-jar", own.getAbsolutePath()).start();
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
        String url = null, jar = null;
        for (JsonElement el : o.getAsJsonArray("assets")) {
            JsonObject a = el.getAsJsonObject();
            String name = a.get("name").getAsString();
            if (INSTALLER_ASSET_NAME.equals(name)) url = a.get("browser_download_url").getAsString();
            if (JAR_ASSET_NAME.equals(name)) jar = a.get("browser_download_url").getAsString();
        }
        return new LatestInfo(tag, url, jar);
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
