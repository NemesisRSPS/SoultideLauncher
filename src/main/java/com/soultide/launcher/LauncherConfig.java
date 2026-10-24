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
    /** HTTPS through the VPS's nginx (same /launcher/* routes, proxied to port 7070) - tried first
     *  (2026-10-02): antivirus web shields / ISP and school filters were answering plain http to a
     *  bare IP:port with a 302 to an https block page, which Java won't follow ("Server returned
     *  302"). VPS_BASE_URL stays as the fallback for anything that can't do the HTTPS one. */
    static final String VPS_HTTPS_URL = "https://soultide.duckdns.org";
    static final String[] BASE_URLS = {VPS_HTTPS_URL, VPS_BASE_URL};

    static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), "SoultideCache");

    // Channel switching (2026-09-23, Ctrl+B in the launcher window) - "live" is the default and
    // what the Play button loads unless the player has explicitly toggled to "beta" this session
    // (see Launcher's own onCtrlB handler and CHANNEL_FILE below for how that choice persists).
    // Each channel gets its own jar/version file so switching back and forth doesn't force a
    // re-download every time - both channels can sit cached side by side.
    static final String CHANNEL_LIVE = "live";
    static final String CHANNEL_BETA = "beta";
    static final Path CHANNEL_FILE = CACHE_DIR.resolve(".launcher-channel");
    // World picker (2026-10-24, user: "the picker for world needs to go on launcher"): Soultide or Bloodtide. Each world
    // has its own client build - Bloodtide's release channels are "bloodtide-live" / "bloodtide-beta" on the update
    // server - so a Bloodtide-only client change never reaches Soultide players. Remembered like the channel.
    static final String WORLD_SOULTIDE = "soultide";
    static final String WORLD_BLOODTIDE = "bloodtide";
    static final Path WORLD_FILE = CACHE_DIR.resolve(".launcher-world");
    /** Bloodtide only runs on beta for now: until this is true the picker shows on the beta channel only (Ctrl+B), and
     *  live always plays Soultide - players don't see a world they can't join. */
    static final boolean BLOODTIDE_LIVE_OPEN = false;

    /** The update server's release channel for a world + live/beta. */
    static String releaseChannel(String world, String channel) {
        return WORLD_BLOODTIDE.equals(world) ? "bloodtide-" + channel : channel;
    }
    /** The game's language (en/es/pt/fr) - read by the client (com.soultide.Lang), set here or in-game. */
    static final Path LANGUAGE_FILE = CACHE_DIR.resolve(".language");

    static Path versionFile(String channel) {
        return CACHE_DIR.resolve(".launcher-version-" + channel);
    }

    static String clientJarName(String channel) {
        return "SoultideClient-" + channel + ".jar";
    }

    private LauncherConfig() {
    }
}
