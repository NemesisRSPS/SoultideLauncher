package com.soultide.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Borderless window matching launcher-background.png exactly (765x503, bundled as a classpath
 * resource - unlike SoultideClient's load.png/login.png, this doesn't need runtime OTA syncing,
 * it's baked into the launcher jar itself). Five invisible click regions sit over the artwork's own
 * painted PLAY/SETTINGS/UPDATE/EXIT/SUPPORT icons - coordinates measured directly against the real
 * image (a green-glow column-intensity scan across the icon row, not eyeballed) rather than
 * guessed, so they land precisely on each icon+label instead of a rough approximation.
 */
public final class Launcher extends JFrame {
    private static final int WIDTH = 765;
    private static final int HEIGHT = 503;

    // Each icon's measured horizontal center (green-glow pixel analysis against the real artwork),
    // ~56px apart; half-width chosen to fill each slot without overlapping its neighbor.
    private static final int[] HOTSPOT_CENTERS_X = {276, 332, 388, 444, 501};
    private static final int HOTSPOT_HALF_WIDTH = 27;
    private static final int HOTSPOT_TOP = 425;
    private static final int HOTSPOT_HEIGHT = 75;

    private final BufferedImage background;
    private final JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private JButton updateHotspot;
    private JButton playHotspot;

    private Launcher() throws IOException {
        super("Soultide Launcher");
        background = loadBackground();

        setUndecorated(true);
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel root = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.drawImage(background, 0, 0, WIDTH, HEIGHT, null);
            }
        };
        root.setPreferredSize(new Dimension(WIDTH, HEIGHT));

        statusLabel.setForeground(new Color(220, 255, 235));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setBounds(40, 393, WIDTH - 80, 18);
        root.add(statusLabel);

        progressBar.setBounds(60, 413, WIDTH - 120, 10);
        progressBar.setVisible(false);
        root.add(progressBar);

        playHotspot = addHotspot(root, 0, "Play", e -> onPlay());
        addHotspot(root, 1, "Settings", e -> onSettings());
        updateHotspot = addHotspot(root, 2, "Update", e -> onUpdate());
        addHotspot(root, 3, "Exit", e -> onExit());
        addHotspot(root, 4, "Support", e -> onSupport());

        setContentPane(root);
    }

    private BufferedImage loadBackground() throws IOException {
        try (InputStream in = Launcher.class.getResourceAsStream("/launcher-background.png")) {
            if (in == null) throw new IOException("launcher-background.png missing from classpath");
            return ImageIO.read(in);
        }
    }

    private JButton addHotspot(JPanel root, int index, String tooltip, java.awt.event.ActionListener action) {
        JButton button = new JButton();
        button.setBounds(HOTSPOT_CENTERS_X[index] - HOTSPOT_HALF_WIDTH, HOTSPOT_TOP,
                HOTSPOT_HALF_WIDTH * 2, HOTSPOT_HEIGHT);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setToolTipText(tooltip);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(action);
        root.add(button);
        return button;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Launcher launcher = new Launcher();
                launcher.setVisible(true);
                launcher.checkForUpdatesSilently();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Failed to start launcher: " + e.getMessage(),
                        "Soultide Launcher", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private boolean localJarExists() {
        return Files.isRegularFile(LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME));
    }

    /** Background check on startup - reports whether an update is available, but never
     *  auto-downloads. Downloading only ever happens via the Update hotspot, matching the artwork's
     *  own explicit Update button rather than forcing a download on every launch. */
    private void checkForUpdatesSilently() {
        setStatus(localJarExists() ? "Checking for updates..." : "No client installed - click Update");
        new SwingWorker<Void, Void>() {
            private String latestVersion;
            private String error;

            @Override
            protected Void doInBackground() {
                try {
                    latestVersion = UpdateChecker.fetchLatestVersion().version;
                } catch (IOException e) {
                    error = e.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                String localVersion = UpdateChecker.readLocalVersion();
                if (error != null) {
                    setStatus(localJarExists()
                            ? "Can't reach update server (playing " + (localVersion == null ? "installed version" : localVersion) + ")"
                            : "Can't reach update server: " + error);
                } else if (!localJarExists() || localVersion == null || !localVersion.equals(latestVersion)) {
                    setStatus("Update available: " + latestVersion);
                } else {
                    setStatus("Up to date (" + latestVersion + ")");
                }
            }
        }.execute();
    }

    private void onUpdate() {
        setBusy(true);
        setStatus("Checking for updates...");
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        new SwingWorker<Void, Object[]>() {
            private String errorMessage;

            @Override
            protected Void doInBackground() {
                try {
                    UpdateChecker.VersionInfo latest = UpdateChecker.fetchLatestVersion();
                    String localVersion = UpdateChecker.readLocalVersion();
                    if (!localJarExists() || localVersion == null || !localVersion.equals(latest.version)) {
                        publish(new Object[]{"status", "Downloading " + latest.version + "..."});
                        downloadAll(latest);
                        UpdateChecker.writeLocalVersion(latest.version);
                        publish(new Object[]{"status", "Up to date (" + latest.version + ")"});
                    } else {
                        publish(new Object[]{"status", "Already up to date (" + latest.version + ")"});
                    }
                } catch (IOException e) {
                    errorMessage = e.getMessage();
                }
                return null;
            }

            private void downloadAll(UpdateChecker.VersionInfo latest) throws IOException {
                UpdateChecker.downloadAsset(latest.jarAsset,
                        LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME),
                        pct -> publish(new Object[]{"progress", latest.jarAsset, pct}));
                List<String> assets = latest.assets;
                if (assets != null) {
                    for (String asset : assets) {
                        if (asset.equals(latest.jarAsset)) continue;
                        UpdateChecker.downloadAsset(asset, LauncherConfig.CACHE_DIR.resolve(asset),
                                pct -> publish(new Object[]{"progress", asset, pct}));
                    }
                }
            }

            @Override
            protected void process(List<Object[]> chunks) {
                for (Object[] chunk : chunks) {
                    if ("status".equals(chunk[0])) {
                        setStatus((String) chunk[1]);
                        progressBar.setIndeterminate(true);
                    } else if ("progress".equals(chunk[0])) {
                        progressBar.setIndeterminate(false);
                        setStatus("Downloading " + chunk[1] + "... " + chunk[2] + "%");
                        progressBar.setValue((Integer) chunk[2]);
                    }
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                if (errorMessage != null) {
                    setStatus("Update failed: " + errorMessage);
                }
                setBusy(false);
            }
        }.execute();
    }

    private void onPlay() {
        if (!localJarExists()) {
            setStatus("No client installed - click Update first");
            return;
        }
        setBusy(true);
        setStatus("Launching...");
        Path jarPath = LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME);
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        try {
            new ProcessBuilder(javaBin, "-jar", jarPath.toString())
                    .directory(LauncherConfig.CACHE_DIR.toFile())
                    .inheritIO()
                    .start();
            dispose();
        } catch (IOException e) {
            setStatus("Failed to launch: " + e.getMessage());
            setBusy(false);
        }
    }

    private void onSettings() {
        JOptionPane.showMessageDialog(this,
                "Install directory:\n" + LauncherConfig.CACHE_DIR,
                "Settings", JOptionPane.PLAIN_MESSAGE);
    }

    private void onExit() {
        dispose();
        System.exit(0);
    }

    private void onSupport() {
        // TODO: point at the real support destination (Discord invite, forum, etc.) once known -
        // deliberately not guessing a URL here.
        JOptionPane.showMessageDialog(this,
                "Support link not configured yet.",
                "Support", JOptionPane.INFORMATION_MESSAGE);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void setBusy(boolean busy) {
        playHotspot.setEnabled(!busy);
        updateHotspot.setEnabled(!busy);
    }
}
