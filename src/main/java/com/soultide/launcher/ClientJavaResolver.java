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

    /**
     * A java executable of at least {@code major}: this launcher's own runtime when it is new enough
     * (the installer bundles 21), else the newest qualifying JDK/JRE found on the machine - e.g. when
     * the bare jar was double-clicked and Windows opened it with an old Java 8. Null if none.
     */
    static String resolveJavaAtLeast(int major) {
        String home = System.getProperty("java.home");
        if (home != null && majorOf(System.getProperty("java.specification.version")) >= major) {
            String own = resolveJavaExecutableIn(new File(home));
            if (own != null) {
                return own;
            }
        }
        java.util.List<File> homes = new java.util.ArrayList<>();
        String env = System.getenv("JAVA_HOME");
        if (env != null) {
            homes.add(new File(env));
        }
        java.util.List<File> roots = new java.util.ArrayList<>();
        for (String pf : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
            if (pf != null) {
                for (String vendor : new String[]{"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft", "Semeru"}) {
                    roots.add(new File(pf, vendor));
                }
            }
        }
        roots.add(new File(System.getProperty("user.home"), ".jdks"));
        for (File root : roots) {
            File[] kids = root.listFiles(File::isDirectory);
            if (kids != null) {
                homes.addAll(java.util.Arrays.asList(kids));
            }
        }
        String best = null;
        int bestMajor = 0;
        for (File h : homes) {
            int m = releaseMajor(h);
            String exe = m >= major && m > bestMajor ? resolveJavaExecutableIn(h) : null;
            if (exe != null) {
                best = exe;
                bestMajor = m;
            }
        }
        return best;
    }

    /** Feature version from a JDK's "release" file (JAVA_VERSION="21.0.11"), 0 if unreadable. */
    private static int releaseMajor(File javaHome) {
        File release = new File(javaHome, "release");
        if (!release.isFile()) {
            return 0;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(release.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return majorOf(line.substring(13).replace("\"", "").trim());
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return 0;
    }

    /** "1.8.0_503" -> 8, "21.0.11" -> 21, "17" -> 17. */
    private static int majorOf(String v) {
        if (v == null) {
            return 0;
        }
        String[] p = v.split("[._+-]");
        try {
            int first = Integer.parseInt(p[0]);
            return first == 1 && p.length > 1 ? Integer.parseInt(p[1]) : first;
        } catch (NumberFormatException e) {
            return 0;
        }
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
