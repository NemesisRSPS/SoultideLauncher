package com.soultide.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared constants - the VPS base URL matches DeployDashboard's HOST in SoultideCacheEditor, and
 * the cache directory matches signlink.findcachedir() in SoultideClient exactly
 * ({@code user.home}/SoultideCache/) - both intentionally, so a file this launcher downloads lands
 * exactly where the client already expects to find it, with no separate install location to keep
 * in sync.
 */
final class LauncherConfig {
    static final String VPS_HOST = "158.69.193.55";
    static final int VPS_PORT = 7070;
    static final String VPS_BASE_URL = "http://" + VPS_HOST + ":" + VPS_PORT;

    static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), "SoultideCache");
    static final Path VERSION_FILE = CACHE_DIR.resolve(".launcher-version");
    static final String CLIENT_JAR_NAME = "SoultideClient.jar";

    private LauncherConfig() {
    }
}
