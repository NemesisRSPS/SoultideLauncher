package com.soultide.launcher;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Waystone's side of the hub API on the VPS (2026-10-24 - SoultideCacheEditor's WaystoneRoutes): the server list, each
 * channel's current build and its files, banners and icons. HTTPS first, plain http as the fallback - the same two bases
 * the Soultide launcher always used (LauncherConfig.BASE_URLS).
 */
final class WaystoneApi {

    static final Path HOME = Paths.get(System.getProperty("user.home"), "Waystone");
    private static final Gson GSON = new Gson();
    private static volatile String workingBase;

    static final class Server {
        String id, name, tagline, description, accent, website, discord, installDir, jarFile, versionFile, jarAsset, banner, icon;
        int javaVersion = 8;
        List<Channel> channels = new ArrayList<>();
        List<News> news = new ArrayList<>();
    }

    static final class Channel {
        String id, label;
        boolean hidden, online;
        int players = -1;
    }

    static final class News {
        String title, body, date, link;
    }

    static final class Build {
        String version, jarAsset;
        List<String> files = new ArrayList<>();
    }

    private static final class ServerList {
        @SerializedName("servers")
        List<Server> servers = new ArrayList<>();
    }

    private WaystoneApi() {
    }

    static List<Server> servers() throws IOException {
        return GSON.fromJson(getString("/waystone/api/servers"), ServerList.class).servers;
    }

    static Build build(String server, String channel) throws IOException {
        return GSON.fromJson(getString("/waystone/api/servers/" + enc(server) + "/" + enc(channel)), Build.class);
    }

    static void download(String server, String channel, String file, Path dest, IntConsumer progress) throws IOException {
        IOException last = null;
        for (String base : bases()) {
            try {
                HttpURLConnection c = open(base + "/waystone/api/servers/" + enc(server) + "/" + enc(channel) + "/files/" + enc(file));
                long total = c.getContentLengthLong();
                Files.createDirectories(dest.getParent());
                Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != lastPct) progress.accept(lastPct = pct);
                    }
                } finally {
                    c.disconnect();
                }
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                workingBase = base;
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Can't reach the Waystone server");
    }

    /** A banner or icon, cached on disk so the launcher opens with pictures even offline. */
    static BufferedImage image(String path, String cacheName) {
        Path cached = HOME.resolve("cache").resolve(cacheName);
        if (path != null) {
            for (String base : bases()) {
                try {
                    HttpURLConnection c = open(base + path);
                    byte[] data;
                    try (InputStream in = c.getInputStream()) {
                        data = readAll(in);
                    } finally {
                        c.disconnect();
                    }
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) {
                        Files.createDirectories(cached.getParent());
                        Files.write(cached, data);
                        return img;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        try {
            return Files.isRegularFile(cached) ? ImageIO.read(cached.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String getString(String path) throws IOException {
        IOException last = null;
        for (String base : bases()) {
            try {
                HttpURLConnection c = open(base + path);
                try (InputStream in = c.getInputStream()) {
                    workingBase = base;
                    return new String(readAll(in), StandardCharsets.UTF_8);
                } finally {
                    c.disconnect();
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Can't reach the Waystone server");
    }

    private static List<String> bases() {
        List<String> out = new ArrayList<>();
        if (workingBase != null) out.add(workingBase);
        for (String b : LauncherConfig.BASE_URLS) if (!out.contains(b)) out.add(b);
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Waystone");
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            String msg = "";
            try (InputStream err = c.getErrorStream()) {
                if (err != null) msg = new String(readAll(err), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
            c.disconnect();
            throw new IOException(msg.isEmpty() ? "Server returned " + status : msg);
        }
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
