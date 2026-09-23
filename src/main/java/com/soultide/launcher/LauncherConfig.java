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

    // Channel switching (2026-09-23, Ctrl+B in the launcher window) - "live" is the default and
    // what the Play button loads unless the player has explicitly toggled to "beta" this session
    // (see Launcher's own onCtrlB handler and CHANNEL_FILE below for how that choice persists).
    // Each channel gets its own jar/version file so switching back and forth doesn't force a
    // re-download every time - both channels can sit cached side by side.
    static final String CHANNEL_LIVE = "live";
    static final String CHANNEL_BETA = "beta";
    static final Path CHANNEL_FILE = CACHE_DIR.resolve(".launcher-channel");

    static Path versionFile(String channel) {
        return CACHE_DIR.resolve(".launcher-version-" + channel);
    }

    static String clientJarName(String channel) {
        return "SoultideClient-" + channel + ".jar";
    }

    private LauncherConfig() {
    }
}
