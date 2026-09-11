package com.soultide.launcher;

import java.io.File;
import java.net.URISyntaxException;

/**
 * Resolves the java/javaw executable to launch the GAME CLIENT with - deliberately NOT this
 * launcher's own {@code java.home}. SoultideClient is old applet-era code (java.applet.Applet,
 * native AWT bridges) that only runs cleanly on JDK 8 - Applet was removed outright in JDK 17.
 * Bundling one shared JDK 8 runtime for both the launcher and the client (via jpackage's
 * --runtime-image) fixed that crash but broke something else - Play then did nothing at all,
 * confirming the same lesson a sibling project (Bloodtide's launcher) already learned the hard way:
 * forcing the client onto whatever runtime the launcher's own UI needs produces subtle,
 * hard-to-diagnose failures rather than a clean version mismatch.
 * <p>
 * A dedicated JDK 8 is bundled alongside the installed launcher as a "client-jdk8" folder (see
 * build.yml's --app-content) specifically so testers never need their own JDK 8 install. Exactly
 * where jpackage's --app-content lands that folder relative to the running jar isn't nailed down
 * from documentation alone, so several plausible locations are checked rather than assuming one -
 * falls back to this launcher's own runtime only if none of them pan out (e.g. running the bare jar
 * directly, outside the installer), which is wrong for the client but better than refusing to
 * launch at all.
 */
final class ClientJavaResolver {

    private static final String BUNDLE_FOLDER_NAME = "client-jdk8";

    private ClientJavaResolver() {
    }

    static String resolveClientJavaExecutable() {
        File bundled = clientJdk8Home();
        if (bundled != null) {
            String resolved = resolveJavaExecutableIn(bundled);
            if (resolved != null) {
                return resolved;
            }
        }
        return resolveOwnJavaExecutable();
    }

    /** For diagnostics only (shown in the "Failed to launch" status text) - which exact bundle
     *  location, if any, was actually used. */
    static String describeClientJavaSource() {
        File bundled = clientJdk8Home();
        return bundled != null ? ("bundled JDK 8 at " + bundled) : "launcher's own runtime (no bundled client-jdk8 found)";
    }

    private static String resolveOwnJavaExecutable() {
        String home = System.getProperty("java.home");
        String resolved = home != null ? resolveJavaExecutableIn(new File(home)) : null;
        return resolved != null ? resolved : "java";
    }

    private static File clientJdk8Home() {
        File launcherDir = launcherDirectory();
        if (launcherDir == null) {
            return null;
        }
        // Checked in order: the jar's own directory (jpackage's --app-content historically lands
        // extra content as a sibling of the main jar, inside the app image's "app" folder for a
        // --type exe/msi build), then one and two levels up (in case it instead lands at the
        // install root, sibling of "app" and "runtime" rather than inside "app" itself).
        File[] candidates = {
                new File(launcherDir, BUNDLE_FOLDER_NAME),
                new File(launcherDir.getParentFile(), BUNDLE_FOLDER_NAME),
                launcherDir.getParentFile() != null
                        ? new File(launcherDir.getParentFile().getParentFile(), BUNDLE_FOLDER_NAME)
                        : null,
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    private static String resolveJavaExecutableIn(File javaHome) {
        File bin = new File(javaHome, "bin");
        File javaw = new File(bin, "javaw.exe");
        if (javaw.isFile()) {
            return javaw.getAbsolutePath();
        }
        File java = new File(bin, "java.exe");
        if (java.isFile()) {
            return java.getAbsolutePath();
        }
        File javaNoExt = new File(bin, "java");
        return javaNoExt.isFile() ? javaNoExt.getAbsolutePath() : null;
    }

    /** Directory containing the running launcher jar. */
    private static File launcherDirectory() {
        try {
            File jarFile = new File(ClientJavaResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jarFile.isFile() ? jarFile.getParentFile() : jarFile;
        } catch (URISyntaxException | NullPointerException | SecurityException ex) {
            return null;
        }
    }
}
