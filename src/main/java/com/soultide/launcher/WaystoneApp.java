package com.soultide.launcher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Map;

/**
 * Waystone (2026-10-24) - one launcher for every server (user: "i need a laucher that supports multiple servers ... integrate
 * other servers like my wifes"). The world list comes from the VPS (WaystoneApi), so adding a server never needs a new
 * launcher: pick a world on the left, read its news on the right, press Play - it downloads that world's client if it's
 * new or out of date, then runs it with the Java it needs. Ctrl+B shows hidden channels (beta builds) for testers.
 */
public final class WaystoneApp extends JFrame {

    static final String NAME = "Waystone";
    private static final int W = 1060, H = 660, SIDE = 286, TOP = 46, BOTTOM = 86;
    static final Color BG = new Color(13, 11, 18), PANEL = new Color(20, 17, 27), CARD = new Color(28, 24, 37), EDGE = new Color(48, 41, 64),
            TEXT = new Color(236, 231, 244), MUTED = new Color(150, 142, 168), GOLD = new Color(214, 178, 106);

    private final List<WaystoneApi.Server> servers = new ArrayList<>();
    private final Map<String, BufferedImage> banners = new HashMap<>(), icons = new HashMap<>();
    private WaystoneApi.Server selected;
    private String channel;
    private boolean showHidden;
    private String error;

    private final JPanel list = new JPanel();
    private final Hero hero = new Hero();
    private final JPanel news = new JPanel();
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JPanel channelBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final PlayButton play = new PlayButton();
    private boolean busy;

    private WaystoneApp() {
        super(NAME);
        setUndecorated(true);
        setSize(W, H);
        setLocationRelativeTo(null);
        setIconImage(Sigil.icon(64));
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(EDGE));
        root.add(titleBar(), BorderLayout.NORTH);
        root.add(sidebar(), BorderLayout.WEST);
        root.add(main(), BorderLayout.CENTER);
        setContentPane(root);

        JComponent rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_B, java.awt.event.InputEvent.CTRL_DOWN_MASK), "hidden");
        rp.getActionMap().put("hidden", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showHidden = !showHidden;
                setStatus(showHidden ? "Test builds shown" : "Test builds hidden");
                refreshChannels();
            }
        });
        new Timer(60_000, e -> loadServers(false)).start();
    }

    // ---------------------------------------------------------------- layout

    private JComponent titleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                g.setPaint(new GradientPaint(0, 0, new Color(26, 22, 36), 0, getHeight(), new Color(16, 13, 22)));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(EDGE);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                g.drawImage(Sigil.icon(28), 14, 9, null);
                g.setFont(new Font("Serif", Font.BOLD, 19));
                drawSpaced(g, "WAYSTONE", 52, 30, 3.2f, GOLD);
                g.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(W, TOP));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 8));
        buttons.setOpaque(false);
        buttons.add(barButton("⚙", "Settings", this::settings));
        buttons.add(barButton("–", "Minimise", () -> setState(Frame.ICONIFIED)));
        buttons.add(barButton("✕", "Close", () -> System.exit(0)));
        bar.add(buttons, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            Point from;

            @Override
            public void mousePressed(MouseEvent e) {
                from = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = getLocation();
                setLocation(p.x + e.getX() - from.x, p.y + e.getY() - from.y);
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        return bar;
    }

    private JButton barButton(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setForeground(MUTED);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorder(new EmptyBorder(4, 10, 4, 10));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> r.run());
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                b.setForeground(TEXT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                b.setForeground(MUTED);
            }
        });
        return b;
    }

    private JComponent sidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(PANEL);
        side.setPreferredSize(new Dimension(SIDE, H));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EDGE));
        JLabel title = new JLabel("WORLDS");
        title.setForeground(MUTED);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        title.setBorder(new EmptyBorder(16, 18, 8, 18));
        side.add(title, BorderLayout.NORTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(PANEL);
        list.setBorder(new EmptyBorder(0, 10, 10, 10));
        JPanel holder = new Tracking(new BorderLayout());
        holder.setBackground(PANEL);
        holder.add(list, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(PANEL);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        side.add(sp, BorderLayout.CENTER);
        JLabel foot = new JLabel("<html><div style='width:240px'>Every world, one launcher.</div></html>");
        foot.setForeground(new Color(110, 102, 128));
        foot.setFont(foot.getFont().deriveFont(11f));
        foot.setBorder(new EmptyBorder(8, 18, 12, 18));
        side.add(foot, BorderLayout.SOUTH);
        return side;
    }

    private JComponent main() {
        JPanel m = new JPanel(new BorderLayout());
        m.setBackground(BG);
        hero.setPreferredSize(new Dimension(W - SIDE, 300));
        m.add(hero, BorderLayout.NORTH);
        news.setLayout(new BoxLayout(news, BoxLayout.Y_AXIS));
        news.setBackground(BG);
        news.setBorder(new EmptyBorder(6, 26, 14, 26));
        JPanel holder = new Tracking(new BorderLayout());
        holder.setBackground(BG);
        holder.add(news, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(BG);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        m.add(sp, BorderLayout.CENTER);
        m.add(actionBar(), BorderLayout.SOUTH);
        return m;
    }

    private JComponent actionBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0)) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(EDGE);
                g.drawLine(0, 0, getWidth(), 0);
                g.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(W - SIDE, BOTTOM));
        bar.setBorder(new EmptyBorder(14, 22, 14, 22));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        channelBar.setOpaque(false);
        channelBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(12.5f));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setMaximumSize(new Dimension(420, 6));
        progress.setPreferredSize(new Dimension(420, 6));
        progress.setBorderPainted(false);
        progress.setBackground(CARD);
        progress.setVisible(false);
        left.add(channelBar);
        left.add(Box.createVerticalStrut(8));
        left.add(status);
        left.add(Box.createVerticalStrut(5));
        left.add(progress);
        bar.add(left, BorderLayout.CENTER);
        play.setPreferredSize(new Dimension(200, 56));
        play.addActionListener(e -> onPlay());
        bar.add(play, BorderLayout.EAST);
        return bar;
    }

    // ---------------------------------------------------------------- data

    private void loadServers(boolean first) {
        new SwingWorker<List<WaystoneApi.Server>, Void>() {
            String err;

            @Override
            protected List<WaystoneApi.Server> doInBackground() {
                try {
                    List<WaystoneApi.Server> got = WaystoneApi.servers();
                    for (WaystoneApi.Server s : got) {
                        if (!icons.containsKey(s.id)) icons.put(s.id, WaystoneApi.image(s.icon, s.id + "-icon.png"));
                        if (!banners.containsKey(s.id)) banners.put(s.id, WaystoneApi.image(s.banner, s.id + "-banner.png"));
                    }
                    return got;
                } catch (IOException e) {
                    err = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                List<WaystoneApi.Server> got;
                try {
                    got = get();
                } catch (Exception e) {
                    got = null;
                }
                if (got == null) {
                    error = "Can't reach the Waystone server" + (err == null ? "" : " (" + err + ")");
                    if (first) setStatus(error);
                    rebuildList();
                    return;
                }
                error = null;
                String keep = selected != null ? selected.id : remembered();
                servers.clear();
                servers.addAll(got);
                WaystoneApi.Server pick = null;
                for (WaystoneApi.Server s : servers) if (s.id.equals(keep)) pick = s;
                if (pick == null && !servers.isEmpty()) pick = servers.get(0);
                rebuildList();
                if (pick != null) select(pick, first || selected == null || !pick.id.equals(selected.id));
            }
        }.execute();
    }

    private void rebuildList() {
        list.removeAll();
        if (servers.isEmpty()) {
            JLabel l = new JLabel("<html><div style='width:230px'>" + (error != null ? error + "<br><br>Check your connection - it retries every minute." : "Loading worlds...") + "</div></html>");
            l.setForeground(MUTED);
            l.setBorder(new EmptyBorder(10, 8, 10, 8));
            list.add(l);
        }
        for (WaystoneApi.Server s : servers) {
            list.add(new WorldCard(s));
            list.add(Box.createVerticalStrut(6));
        }
        list.revalidate();
        list.repaint();
    }

    private void select(WaystoneApi.Server s, boolean resetChannel) {
        selected = s;
        remember(s.id);
        if (resetChannel || channel == null || channelOf(s, channel) == null) channel = firstVisibleChannel(s);
        hero.repaint();
        list.repaint();
        rebuildNews();
        refreshChannels();
        if (!busy) checkInstalled();
    }

    private static WaystoneApi.Channel channelOf(WaystoneApi.Server s, String id) {
        for (WaystoneApi.Channel c : s.channels) if (c.id.equals(id)) return c;
        return null;
    }

    private String firstVisibleChannel(WaystoneApi.Server s) {
        for (WaystoneApi.Channel c : s.channels) if (!c.hidden || showHidden) return c.id;
        return null;
    }

    private void refreshChannels() {
        channelBar.removeAll();
        if (selected != null) {
            if (channel != null) {
                WaystoneApi.Channel c = channelOf(selected, channel);
                if (c != null && c.hidden && !showHidden) channel = firstVisibleChannel(selected);
            } else {
                channel = firstVisibleChannel(selected);
            }
            List<WaystoneApi.Channel> shown = new ArrayList<>();
            for (WaystoneApi.Channel c : selected.channels) if (!c.hidden || showHidden) shown.add(c);
            if (shown.size() > 1) {
                for (WaystoneApi.Channel c : shown) {
                    Pill p = new Pill(c.label + (c.hidden ? " (test)" : ""), c.id.equals(channel), accent(selected));
                    p.addActionListener(e -> {
                        channel = c.id;
                        refreshChannels();
                        if (!busy) checkInstalled();
                    });
                    channelBar.add(p);
                }
            }
        }
        channelBar.revalidate();
        channelBar.repaint();
        play.accent = selected == null ? GOLD : accent(selected);
        play.setText(selected == null ? "PLAY" : channel == null ? "COMING SOON" : "PLAY");
        play.setEnabled(!busy && selected != null && channel != null);
        play.repaint();
    }

    private void rebuildNews() {
        news.removeAll();
        if (selected == null) return;
        if (selected.description != null && !selected.description.isEmpty()) {
            news.add(text(selected.description, TEXT, 13f, false));
            news.add(Box.createVerticalStrut(14));
        }
        JLabel h = new JLabel("NEWS");
        h.setForeground(MUTED);
        h.setFont(h.getFont().deriveFont(Font.BOLD, 11f));
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        news.add(h);
        news.add(Box.createVerticalStrut(8));
        if (selected.news.isEmpty()) news.add(text("No news yet.", MUTED, 12.5f, false));
        for (WaystoneApi.News n : selected.news) {
            news.add(new NewsCard(n, accent(selected)));
            news.add(Box.createVerticalStrut(8));
        }
        news.revalidate();
        news.repaint();
    }

    private static JLabel text(String s, Color c, float size, boolean bold) {
        JLabel l = new JLabel("<html><div style='width:600px'>" + esc(s).replace("\n", "<br>") + "</div></html>");
        l.setForeground(c);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Color accent(WaystoneApi.Server s) {
        try {
            return Color.decode(s.accent);
        } catch (Exception e) {
            return GOLD;
        }
    }

    // ---------------------------------------------------------------- install + play

    private Path installDir(WaystoneApi.Server s) {
        String dir = s.installDir == null || s.installDir.trim().isEmpty() ? "Waystone/" + s.id : s.installDir.trim();
        return java.nio.file.Paths.get(System.getProperty("user.home")).resolve(dir);
    }

    private Path jarPath(WaystoneApi.Server s, String ch) {
        return installDir(s).resolve(s.jarFile.replace("{channel}", ch));
    }

    private Path versionPath(WaystoneApi.Server s, String ch) {
        return installDir(s).resolve(s.versionFile.replace("{channel}", ch));
    }

    private String localVersion(WaystoneApi.Server s, String ch) {
        try {
            Path p = versionPath(s, ch);
            return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Quietly: is this world's client installed and current? */
    private void checkInstalled() {
        final WaystoneApi.Server s = selected;
        final String ch = channel;
        if (s == null || ch == null) {
            setStatus(s == null ? " " : s.name + " isn't open yet - stay tuned.");
            return;
        }
        WaystoneApi.Channel c = channelOf(s, ch);
        String online = c == null ? "" : c.online ? "Online" + (c.players >= 0 ? " - " + c.players + " playing" : "") : "Offline";
        setStatus(online);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    WaystoneApi.Build b = WaystoneApi.build(s.id, ch);
                    String local = localVersion(s, ch);
                    boolean have = Files.isRegularFile(jarPath(s, ch));
                    if (!have) return online + "   ·   First play downloads " + b.version;
                    if (!b.version.equals(local)) return online + "   ·   Update ready: " + b.version;
                    return online + "   ·   Up to date (" + b.version + ")";
                } catch (IOException e) {
                    return online + "   ·   " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                if (s != selected || !ch.equals(channel) || busy) return;
                try {
                    setStatus(get());
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void onPlay() {
        final WaystoneApi.Server s = selected;
        final String ch = channel;
        if (s == null || ch == null || busy) return;
        setBusy(true);
        progress.setVisible(true);
        progress.setIndeterminate(true);
        setStatus("Checking " + s.name + "...");
        new SwingWorker<String, Object[]>() {
            @Override
            protected String doInBackground() {
                try {
                    WaystoneApi.Build b = WaystoneApi.build(s.id, ch);
                    Path dir = installDir(s);
                    Files.createDirectories(dir);
                    if (!Files.isRegularFile(jarPath(s, ch)) || !b.version.equals(localVersion(s, ch))) {
                        Properties have = installedHashes(dir);
                        for (String f : b.files) {
                            Path dest = f.equals(b.jarAsset) ? jarPath(s, ch) : dir.resolve(f);
                            String key = dest.getFileName().toString();
                            String want = b.hashes == null ? null : b.hashes.get(f);
                            if (want != null && want.equals(have.getProperty(key)) && Files.isRegularFile(dest)) {
                                continue; // already on disk from an earlier build (Bloodtide's 120 MB cache)
                            }
                            WaystoneApi.download(s.id, ch, f, dest, pct -> publish(new Object[]{f, pct}));
                            if (want != null && want.startsWith("sha256:") && !want.substring(7).equalsIgnoreCase(WaystoneApi.sha256(dest))) {
                                Files.deleteIfExists(dest);
                                throw new IOException(f + " didn't download correctly - press Play to try again");
                            }
                            if (want != null) have.setProperty(key, want);
                            else have.remove(key);
                            saveInstalledHashes(dir, have);
                        }
                        Files.write(versionPath(s, ch), b.version.getBytes(StandardCharsets.UTF_8));
                    }
                    publish(new Object[]{null, -1});
                    launch(s, ch);
                    return null;
                } catch (IOException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                if (last[0] == null) {
                    progress.setIndeterminate(true);
                    setStatus("Launching " + s.name + "...");
                } else {
                    progress.setIndeterminate(false);
                    progress.setValue((Integer) last[1]);
                    setStatus("Downloading " + last[0] + "... " + last[1] + "%");
                }
            }

            @Override
            protected void done() {
                String err;
                try {
                    err = get();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                progress.setVisible(false);
                setBusy(false);
                if (err != null) {
                    setStatus("Couldn't start " + s.name + ": " + err);
                } else {
                    setStatus(s.name + " is starting - have fun!");
                    Timer t = new Timer(2500, e -> System.exit(0));
                    t.setRepeats(false);
                    t.start();
                }
            }
        }.execute();
    }

    /** What's on disk in an install folder: file name -> the hub's hash it was downloaded as. Shared by its channels. */
    private static Properties installedHashes(Path dir) {
        Properties p = new Properties();
        Path f = dir.resolve(".waystone-files");
        if (Files.isRegularFile(f)) {
            try (java.io.Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    private static void saveInstalledHashes(Path dir, Properties p) throws IOException {
        try (java.io.Writer w = Files.newBufferedWriter(dir.resolve(".waystone-files"), StandardCharsets.UTF_8)) {
            p.store(w, "Waystone: files in this folder and the build hash each came from");
        }
    }

    private void launch(WaystoneApi.Server s, String ch) throws IOException {
        Path dir = installDir(s);
        String java = s.javaVersion <= 8 ? ClientJavaResolver.resolveClientJavaExecutable() : ClientJavaResolver.resolveJavaAtLeast(s.javaVersion);
        if (java == null) {
            throw new IOException(s.name + " needs Java " + s.javaVersion + " - reinstall Waystone, or install Java " + s.javaVersion);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        ownJar().ifPresent(p -> cmd.add("-DlauncherJar=" + p)); // a client's own "update available" notice can reopen Waystone
        cmd.add("-jar");
        cmd.add(jarPath(s, ch).toString());
        new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("client-launch.log").toFile()).start();
    }

    private static java.util.Optional<String> ownJar() {
        try {
            java.io.File f = new java.io.File(WaystoneApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? java.util.Optional.of(f.getAbsolutePath()) : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private void settings() {
        if (selected == null) return;
        Path dir = installDir(selected);
        Object[] opts = {"Open install folder", "Re-download " + selected.name, "Close"};
        int pick = JOptionPane.showOptionDialog(this, "<html><b>" + esc(selected.name) + "</b><br>Installed in " + esc(dir.toString())
                        + "<br><br>Waystone " + (LauncherSelfUpdate.ownVersion() == null ? "(dev build)" : LauncherSelfUpdate.ownVersion()) + "</html>",
                "Settings", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[2]);
        if (pick == 0) {
            try {
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception e) {
                setStatus("Can't open " + dir);
            }
        } else if (pick == 1 && channel != null) {
            try {
                Files.deleteIfExists(versionPath(selected, channel));
                setStatus("The next Play downloads " + selected.name + " again.");
            } catch (IOException e) {
                setStatus(e.getMessage());
            }
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        play.setEnabled(!b && selected != null && channel != null);
        play.busy = b;
        play.repaint();
    }

    private void setStatus(String s) {
        status.setText(s == null || s.isEmpty() ? " " : s);
    }

    private static String remembered() {
        try {
            Path p = WaystoneApi.HOME.resolve("last-world.txt");
            return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void remember(String id) {
        try {
            Files.createDirectories(WaystoneApi.HOME);
            Files.write(WaystoneApi.HOME.resolve("last-world.txt"), id.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- pieces

    /** One world in the list: icon, name, online state. */
    private final class WorldCard extends JComponent {
        final WaystoneApi.Server s;
        boolean hover;

        WorldCard(WaystoneApi.Server s) {
            this.s = s;
            setPreferredSize(new Dimension(SIDE - 20, 66));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (selected != s) select(s, true);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            boolean on = selected == s;
            Color acc = accent(s);
            RoundRectangle2D r = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 14, 14);
            g.setColor(on ? mix(CARD, acc, 0.16) : hover ? new Color(34, 29, 45) : CARD);
            g.fill(r);
            g.setColor(on ? alpha(acc, 190) : EDGE);
            g.draw(r);
            if (on) {
                g.setColor(acc);
                g.fillRoundRect(0, 12, 4, h - 24, 4, 4);
            }
            BufferedImage icon = icons.get(s.id);
            int ix = 12, iy = (h - 44) / 2;
            if (icon != null) {
                Shape clip = g.getClip();
                g.setClip(new RoundRectangle2D.Float(ix, iy, 44, 44, 12, 12));
                g.drawImage(icon, ix, iy, 44, 44, null);
                g.setClip(clip);
            } else {
                g.setPaint(new GradientPaint(ix, iy, acc, ix + 44, iy + 44, mix(acc, Color.BLACK, 0.55)));
                g.fillRoundRect(ix, iy, 44, 44, 12, 12);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Serif", Font.BOLD, 22));
                String init = s.name.substring(0, 1).toUpperCase();
                FontMetrics fm = g.getFontMetrics();
                g.drawString(init, ix + (44 - fm.stringWidth(init)) / 2, iy + 31);
            }
            g.setColor(TEXT);
            g.setFont(getFont().deriveFont(Font.BOLD, 14f));
            g.drawString(s.name, 68, 28);
            WaystoneApi.Channel c = null;
            for (WaystoneApi.Channel x : s.channels) if (!x.hidden) {
                c = x;
                break;
            }
            String line;
            Color dot;
            if (c == null) {
                line = "Coming soon";
                dot = GOLD;
            } else if (c.online) {
                line = c.players >= 0 ? c.players + " playing" : "Online";
                dot = new Color(110, 220, 140);
            } else {
                line = "Offline";
                dot = new Color(200, 90, 90);
            }
            g.setColor(dot);
            g.fillOval(68, 39, 8, 8);
            g.setColor(MUTED);
            g.setFont(getFont().deriveFont(12f));
            g.drawString(line, 82, 47);
            g.dispose();
        }
    }

    /** The selected world's banner, name and links. */
    private final class Hero extends JComponent {
        Hero() {
            setLayout(null);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (selected == null) return;
                    for (Object[] l : links()) {
                        if (((Rectangle) l[0]).contains(e.getPoint())) open((String) l[1]);
                    }
                }
            });
        }

        List<Object[]> links() {
            List<Object[]> out = new ArrayList<>();
            int x = getWidth() - 22;
            for (String[] l : new String[][]{{"Discord", selected.discord}, {"Website", selected.website}}) {
                if (l[1] == null || l[1].isEmpty()) continue;
                int w = 86;
                x -= w;
                out.add(new Object[]{new Rectangle(x, 18, w, 28), l[1], l[0]});
                x -= 8;
            }
            return out;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.setColor(BG);
            g.fillRect(0, 0, w, h);
            if (selected == null) {
                g.drawImage(Sigil.icon(120), (w - 120) / 2, (h - 140) / 2, null);
                g.dispose();
                return;
            }
            Color acc = accent(selected);
            BufferedImage b = banners.get(selected.id);
            if (b != null) {
                double sc = Math.max(w / (double) b.getWidth(), h / (double) b.getHeight());
                int bw = (int) (b.getWidth() * sc), bh = (int) (b.getHeight() * sc);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(b, (w - bw) / 2, (h - bh) / 2, bw, bh, null);
            } else {
                g.setPaint(new RadialGradientPaint(w * 0.7f, h * 0.3f, w * 0.7f, new float[]{0f, 1f}, new Color[]{alpha(acc, 120), BG}));
                g.fillRect(0, 0, w, h);
            }
            g.setPaint(new GradientPaint(0, h * 0.35f, new Color(13, 11, 18, 0), 0, h, BG));
            g.fillRect(0, 0, w, h);
            g.setFont(new Font("Serif", Font.BOLD, 40));
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(selected.name, 28, h - 58);
            g.setColor(TEXT);
            g.drawString(selected.name, 26, h - 60);
            if (selected.tagline != null && !selected.tagline.isEmpty()) {
                g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                g.setColor(mix(TEXT, acc, 0.35));
                g.drawString(selected.tagline, 28, h - 32);
            }
            g.setColor(acc);
            g.fillRect(28, h - 22, 60, 3);
            for (Object[] l : links()) {
                Rectangle r = (Rectangle) l[0];
                g.setColor(new Color(10, 8, 14, 170));
                g.fillRoundRect(r.x, r.y, r.width, r.height, r.height, r.height);
                g.setColor(alpha(acc, 200));
                g.drawRoundRect(r.x, r.y, r.width, r.height, r.height, r.height);
                g.setColor(TEXT);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                FontMetrics fm = g.getFontMetrics();
                String t = (String) l[2];
                g.drawString(t, r.x + (r.width - fm.stringWidth(t)) / 2, r.y + 18);
            }
            g.dispose();
        }
    }

    private static void open(String url) {
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {
        }
    }

    /** A scroll view that's always as wide as the visible area (text wraps instead of running off the edge). */
    private static final class Tracking extends JPanel implements Scrollable {
        Tracking(LayoutManager l) {
            super(l);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A news post. */
    private static final class NewsCard extends JPanel {
        NewsCard(WaystoneApi.News n, Color acc) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(CARD);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, acc), new EmptyBorder(10, 14, 10, 14)));
            JLabel t = new JLabel("<html><b>" + esc(n.title) + "</b>&nbsp;&nbsp;<span style='color:#968ea8'>" + esc(n.date) + "</span></html>");
            t.setForeground(TEXT);
            t.setFont(t.getFont().deriveFont(13.5f));
            add(t);
            if (n.body != null && !n.body.isEmpty()) {
                add(Box.createVerticalStrut(4));
                add(text(n.body, new Color(206, 200, 218), 12.5f, false));
            }
            if (n.link != null && !n.link.isEmpty()) {
                JLabel l = new JLabel("<html><u>Read more</u></html>");
                l.setForeground(acc);
                l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                l.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        open(n.link);
                    }
                });
                add(Box.createVerticalStrut(4));
                add(l);
            }
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height + 400));
        }
    }

    /** A channel choice (Live / Beta). */
    private static final class Pill extends JButton {
        final boolean on;
        final Color acc;

        Pill(String text, boolean on, Color acc) {
            super(text);
            this.on = on;
            this.acc = acc;
            setFont(getFont().deriveFont(Font.BOLD, 11.5f));
            setForeground(on ? Color.WHITE : MUTED);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorder(new EmptyBorder(4, 12, 4, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(on ? alpha(acc, 210) : CARD);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.setColor(on ? acc : EDGE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** The big button, in the world's colour. */
    private static final class PlayButton extends JButton {
        Color accent = GOLD;
        boolean busy, hover;

        PlayButton() {
            super("PLAY");
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            boolean on = isEnabled();
            Color top = on ? mix(accent, Color.WHITE, hover ? 0.28 : 0.15) : new Color(60, 54, 72);
            Color bot = on ? mix(accent, Color.BLACK, 0.25) : new Color(40, 36, 50);
            if (on) {
                g.setColor(alpha(accent, hover ? 90 : 55));
                g.fillRoundRect(0, 0, w, h, 18, 18);
            }
            g.setPaint(new GradientPaint(0, 0, top, 0, h, bot));
            g.fillRoundRect(3, 3, w - 6, h - 6, 14, 14);
            g.setColor(on ? alpha(Color.WHITE, 70) : EDGE);
            g.drawRoundRect(3, 3, w - 7, h - 7, 14, 14);
            String t = busy ? "WORKING..." : getText();
            g.setFont(new Font("Serif", Font.BOLD, t.length() > 8 ? 16 : 22));
            FontMetrics fm = g.getFontMetrics();
            int tx = (w - fm.stringWidth(t)) / 2 - (t.length() - 1) * 1, ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            drawSpaced(g, t, tx, ty, 2f, on ? Color.WHITE : MUTED);
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static void drawSpaced(Graphics2D g, String s, float x, float y, float spacing, Color c) {
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        for (char ch : s.toCharArray()) {
            g.drawString(String.valueOf(ch), x, y);
            x += fm.charWidth(ch) + spacing;
        }
    }

    static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** The Waystone mark: a standing stone with a glowing rune. */
    static final class Sigil {
        private static final Map<Integer, BufferedImage> CACHE = new HashMap<>();

        static synchronized BufferedImage icon(int size) {
            BufferedImage img = CACHE.get(size);
            if (img != null) return img;
            img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            aa(g);
            float s = size / 64f;
            g.scale(s, s);
            // glow
            g.setPaint(new RadialGradientPaint(32, 34, 30, new float[]{0f, 1f}, new Color[]{new Color(214, 178, 106, 110), new Color(214, 178, 106, 0)}));
            g.fillOval(2, 4, 60, 60);
            // the stone
            GeneralPath stone = new GeneralPath();
            stone.moveTo(20, 60);
            stone.lineTo(17, 26);
            stone.curveTo(18, 12, 26, 5, 32, 4);
            stone.curveTo(39, 5, 47, 13, 47, 26);
            stone.lineTo(44, 60);
            stone.closePath();
            g.setPaint(new GradientPaint(17, 4, new Color(96, 88, 112), 47, 60, new Color(36, 31, 46)));
            g.fill(stone);
            g.setColor(new Color(20, 17, 27));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(stone);
            // the rune: a diamond with a stem, like a waypoint
            g.setColor(new Color(240, 205, 128));
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            GeneralPath rune = new GeneralPath();
            rune.moveTo(32, 16);
            rune.lineTo(39, 26);
            rune.lineTo(32, 36);
            rune.lineTo(25, 26);
            rune.closePath();
            g.draw(rune);
            g.drawLine(32, 36, 32, 48);
            g.drawLine(27, 44, 37, 44);
            // ground
            g.setColor(new Color(214, 178, 106, 160));
            g.fillRoundRect(12, 58, 40, 3, 3, 3);
            g.dispose();
            CACHE.put(size, img);
            return img;
        }
    }

    // ---------------------------------------------------------------- start

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        UIManager.put("ToolTip.background", CARD);
        UIManager.put("ToolTip.foreground", TEXT);
        SwingUtilities.invokeLater(() -> {
            WaystoneApp app = new WaystoneApp();
            app.setVisible(true);
            app.rebuildList();
            app.loadServers(true);
            app.selfUpdate();
        });
    }

    /** Same self-update as the Soultide launcher: a newer installer on GitHub -> offer it. */
    private void selfUpdate() {
        String own = LauncherSelfUpdate.ownVersion();
        if (own == null) return;
        new SwingWorker<LauncherSelfUpdate.LatestInfo, Void>() {
            @Override
            protected LauncherSelfUpdate.LatestInfo doInBackground() throws Exception {
                return LauncherSelfUpdate.fetchLatest();
            }

            @Override
            protected void done() {
                try {
                    LauncherSelfUpdate.LatestInfo latest = get();
                    if (latest.downloadUrl == null || own.equals(latest.version)) return;
                    int c = JOptionPane.showConfirmDialog(WaystoneApp.this, "Waystone " + latest.version + " is available. Update now?",
                            "Waystone update", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (c == JOptionPane.YES_OPTION) {
                        LauncherSelfUpdate.downloadAndLaunchInstaller(latest.downloadUrl);
                        System.exit(0);
                    }
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }
}
