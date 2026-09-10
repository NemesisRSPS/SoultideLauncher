package com.soultide.launcher;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the VPS for the latest SoultideClient release, downloads the jar + launcher-assets/* if
 * out of date (see UpdateChecker), then launches the client. See LauncherConfig for the VPS URL
 * and the cache directory this writes into (matches SoultideClient's signlink.findcachedir()
 * exactly, so nothing downloaded here needs a second install location).
 */
public final class Launcher extends JFrame {
    private final JLabel statusLabel = new JLabel("Starting...", SwingConstants.CENTER);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton playButton = new JButton("Play");

    private Launcher() {
        super("Soultide Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 220);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("SOULTIDE", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        content.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(13f));
        center.add(statusLabel, BorderLayout.NORTH);
        center.add(progressBar, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        playButton.setEnabled(false);
        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 16f));
        playButton.addActionListener(e -> launchClient());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
        south.add(playButton);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> {
            Launcher launcher = new Launcher();
            launcher.setVisible(true);
            launcher.checkForUpdates();
        });
    }

    private void checkForUpdates() {
        setStatus("Checking for updates...", true);
        new SwingWorker<Void, Object[]>() {
            private String errorMessage;
            private boolean playable;

            @Override
            protected Void doInBackground() {
                try {
                    UpdateChecker.VersionInfo latest = UpdateChecker.fetchLatestVersion();
                    String localVersion = UpdateChecker.readLocalVersion();
                    Path jarPath = LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME);
                    boolean jarMissing = !java.nio.file.Files.isRegularFile(jarPath);

                    if (jarMissing || localVersion == null || !localVersion.equals(latest.version)) {
                        publish(new Object[]{"status", "Downloading " + latest.version + "..."});
                        downloadAll(latest);
                        UpdateChecker.writeLocalVersion(latest.version);
                        publish(new Object[]{"status", "Up to date (" + latest.version + ")"});
                    } else {
                        publish(new Object[]{"status", "Up to date (" + localVersion + ")"});
                    }
                    playable = true;
                } catch (IOException e) {
                    errorMessage = e.getMessage();
                    // Still let an already-installed client run if the VPS is briefly unreachable -
                    // only a genuinely first-time install (no jar at all) has to block on this.
                    playable = java.nio.file.Files.isRegularFile(
                            LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME));
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
                        if (asset.equals(latest.jarAsset)) continue; // jarAsset is also listed in assets
                        UpdateChecker.downloadAsset(asset, LauncherConfig.CACHE_DIR.resolve(asset),
                                pct -> publish(new Object[]{"progress", asset, pct}));
                    }
                }
            }

            @Override
            protected void process(List<Object[]> chunks) {
                for (Object[] chunk : chunks) {
                    if ("status".equals(chunk[0])) {
                        setStatus((String) chunk[1], true);
                    } else if ("progress".equals(chunk[0])) {
                        setStatus("Downloading " + chunk[1] + "...", false);
                        progressBar.setValue((Integer) chunk[2]);
                    }
                }
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                if (errorMessage != null) {
                    setStatus(playable
                            ? "Update check failed (playing existing install): " + errorMessage
                            : "Failed to reach the update server: " + errorMessage, false);
                } else {
                    setStatus(statusLabel.getText(), false);
                }
                playButton.setEnabled(playable);
            }
        }.execute();
    }

    private void setStatus(String text, boolean indeterminate) {
        statusLabel.setText(text);
        progressBar.setIndeterminate(indeterminate);
    }

    private void launchClient() {
        playButton.setEnabled(false);
        setStatus("Launching...", true);
        Path jarPath = LauncherConfig.CACHE_DIR.resolve(LauncherConfig.CLIENT_JAR_NAME);
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        try {
            new ProcessBuilder(javaBin, "-jar", jarPath.toString())
                    .directory(LauncherConfig.CACHE_DIR.toFile())
                    .inheritIO()
                    .start();
            dispose();
        } catch (IOException e) {
            setStatus("Failed to launch: " + e.getMessage(), false);
            playButton.setEnabled(true);
        }
    }
}
