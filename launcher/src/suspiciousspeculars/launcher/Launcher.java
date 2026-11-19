package suspiciousspeculars.launcher;

import suspiciousspeculars.patch.Patcher;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Out-of-game front end for the patcher: the window the in-game screen cannot be.
 *
 * Two ways in, same program:
 *
 *   suspicious-speculars-launcher.jar --instance <instance dir>   (spawned by the mod, or by hand)
 *   suspicious-speculars-launcher.jar --game-dir <minecraft dir>
 *
 * It patches, then hands the game back by relaunching the instance through its launcher. Relaunching
 * is only attempted for the MultiMC family (Prism, PolyMC, MultiMC), which share both the instance
 * layout and the {@code --dir/--launch} command line; every other launcher gets the patch and a
 * plain instruction, because no external way to start their instance exists.
 *
 * Flags:
 *   --window        show the installer window instead of running headless
 *   --preview <png> draw the window offscreen into an image (no display needed) and exit
 *   --frame <n>     with --preview: render patcher.gif frame n (0..26) instead of live state
 *   --hint <icon>   with --preview --frame: show that tile's tooltip (duck, github, modrinth, discord)
 *   --errors <mode> with --preview --frame: fill the band; demo is the web build's sample, live is
 *                   this instance's actual missing prerequisites, failed is the failure band
 *                   ("install did not finish" and a reason) for reviewing that state offline
 *   --scale <n>     integer GUI scale (default 1: one unit is one pixel of patcher.gif)
 *   --dry-run       report what would happen; write nothing, launch nothing
 *   --patch-only    patch but never relaunch (the mod's fallback when a relaunch already failed)
 *   --data <dir>    patch data; default is a "patch" folder beside this jar
 *   --prism <file>  explicit launcher binary, overriding detection
 *   --list          find launchers and instances, print them, do nothing else
 *   --links         print the four tile targets (what clicking each one opens) and exit
 *   --quack         check and play Duck mode's cue (the jar's only sound), then exit
 *   --sound         check the cue decodes, without playing it; exit 1 when the jar lacks it
 */
public final class Launcher {

    /** A launcher we know how to start an instance with. */
    static final class Known {
        final String name;
        final String[] binaries;
        final String[] rootMarkers;

        Known(String name, String[] binaries, String[] rootMarkers) {
            this.name = name;
            this.binaries = binaries;
            this.rootMarkers = rootMarkers;
        }
    }

    private static final Known[] KNOWN = new Known[]{
            new Known("Prism Launcher", new String[]{"prismlauncher.exe", "prismlauncher.sh"},
                    new String[]{"prismlauncher.cfg"}),
            new Known("PolyMC", new String[]{"polymc.exe", "polymc.sh"},
                    new String[]{"polymc.cfg"}),
            new Known("MultiMC", new String[]{"MultiMC.exe", "multimc.exe", "multimc.sh"},
                    new String[]{"multimc.cfg"}),
    };

    /** Progress denominator shown in the bottom bar: 22 shader file edits + 2 packs. */
    static final int STEPS = 24;

    private final Path gameDir;
    private final Path instanceDir;
    private final Path dataDir;
    private final boolean dryRun;
    private final boolean patchOnly;
    private final String forcedLauncher;
    private final int waitSeconds;

    private Launcher(Path gameDir, Path instanceDir, Path dataDir, boolean dryRun,
                     boolean patchOnly, String forcedLauncher, int waitSeconds) {
        this.gameDir = gameDir;
        this.instanceDir = instanceDir;
        this.dataDir = dataDir;
        this.dryRun = dryRun;
        this.patchOnly = patchOnly;
        this.forcedLauncher = forcedLauncher;
        this.waitSeconds = waitSeconds;
    }

    public static void main(String[] args) {
        Path instance = null;
        Path gameDir = null;
        Path data = null;
        String forced = null;
        boolean dry = false;
        boolean patchOnly = false;
        boolean list = false;
        boolean links = false;
        boolean quack = false;
        boolean sound = false;
        boolean window = false;
        Path preview = null;        int frame = -1;
        int guiScale = 1;
        boolean scaleGiven = false;
        String hint = null;
        String errors = null;
        int waitSeconds = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--instance") && i + 1 < args.length) instance = Paths.get(args[++i]);
            else if (a.equals("--game-dir") && i + 1 < args.length) gameDir = Paths.get(args[++i]);
            else if (a.equals("--data") && i + 1 < args.length) data = Paths.get(args[++i]);
            else if (a.equals("--prism") && i + 1 < args.length) forced = args[++i];
            else if (a.equals("--dry-run")) dry = true;
            else if (a.equals("--patch-only")) patchOnly = true;
            else if (a.equals("--window")) window = true;
            else if (a.equals("--scale") && i + 1 < args.length) {
                guiScale = parseScale(args[++i], 1);
                scaleGiven = true;
            } else if (a.equals("--preview") && i + 1 < args.length) preview = Paths.get(args[++i]);
            else if (a.equals("--hint") && i + 1 < args.length) hint = args[++i];
            else if (a.equals("--errors") && i + 1 < args.length) errors = args[++i];
            else if (a.equals("--frame") && i + 1 < args.length) frame = Integer.parseInt(args[++i]);
            else if (a.equals("--wait") && i + 1 < args.length) waitSeconds = Integer.parseInt(args[++i]);
            else if (a.equals("--list")) list = true;
            else if (a.equals("--links")) links = true;
            else if (a.equals("--quack")) quack = true;
            else if (a.equals("--sound")) sound = true;
            else {
                System.err.println("unknown argument " + a);
                usage();
                System.exit(2);
            }
        }

        if (list) {
            discover();
            return;
        }
        if (links) {
            printLinks();
            return;
        }
        if (quack) {
            System.exit(playQuack());
        }
        if (sound) {
            System.exit(describeQuack());
        }
        if (instance == null && gameDir == null) {
            usage();
            System.exit(2);
        }

        if (gameDir == null) {
            gameDir = instance.resolve("minecraft");
            if (!Files.isDirectory(gameDir)) gameDir = instance;
        }
        if (instance == null) {
            // <instance>/minecraft -> <instance>
            instance = "minecraft".equals(gameDir.getFileName().toString())
                    ? gameDir.getParent() : gameDir;
        }
        Path dataDir = data != null ? data : beside("patch");

        if (preview != null) {
            previewWindow(gameDir.toAbsolutePath(), instance.toAbsolutePath(),
                    dataDir.toAbsolutePath(), guiScale, preview, frame, hint, errors);
            return;
        }
        if (frame >= 0) {
            System.err.println("--frame needs --preview <png> for the output path");
            System.exit(2);
        }

        // Reaching here means the launcher really started, so the hand-over worked: clear the guard
        // that stops a broken spawn from repeating. Without this, one successful window blocks every
        // later launch, because the coremod sets the flag before it spawns.
        clearRelaunchFlag(gameDir);

        if (window) {
            // the window is drawn in GIF pixels, so one unit is one pixel: 3x was the art-unit scale of
            // the window this replaced, and only an explicit --scale should ask for it
            if (!scaleGiven) guiScale = 1;
            openWindow(gameDir.toAbsolutePath(), instance.toAbsolutePath(),
                    dataDir.toAbsolutePath(), guiScale, waitSeconds, forced, dry, patchOnly);
            return;
        }

        try {
            new Launcher(gameDir.toAbsolutePath(), instance.toAbsolutePath(), dataDir,
                    dry, patchOnly, forced, waitSeconds).run();
        } catch (Throwable t) {
            // The window (and the mod that spawned us) must still be able to report: never throw.
            System.err.println("launcher failed: " + t);
            t.printStackTrace();
        }
    }

    private static void usage() {
        System.err.println("usage: --instance <instance dir> | --game-dir <minecraft dir>"
                + " [--data <dir>] [--prism <exe>] [--dry-run] [--patch-only] [--list] [--links]"
                + " [--quack] [--sound]"
                + " [--window [--scale <n>]]"
                + " | [--preview <png> [--frame <n>] [--hint <icon>] [--errors <mode>]]");
    }

    /** A file next to this jar, so the launcher can be dropped anywhere. */
    static Path beside(String name) {
        try {
            Path self = Paths.get(Launcher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            return dir.resolve(name);
        } catch (Exception e) {
            return Paths.get(name);
        }
    }

    void run() throws Exception {
        System.out.println("instance:  " + instanceDir);
        System.out.println("game dir:  " + gameDir);

        Game launcher = findLauncher(instanceDir, forcedLauncher);
        if (launcher == null) {
            System.out.println("launcher:  not one we can start (Prism/PolyMC/MultiMC layout not found)");
        } else {
            System.out.println("launcher:  " + launcher.describe());
        }

        waitForLocks(gameDir.resolve("resourcepacks"), waitSeconds);
        Patcher patcher = new Patcher(gameDir, dataDir)
                .version("launcher")
                .packs(Patcher.PackMode.INSTALL)
                .dryRun(dryRun)
                .log(new Patcher.Log() {
                    public void line(String text) {
                        System.out.println(text);
                    }
                });
        patcher.run();
        patcher.finish();

        boolean changed = patcher.editedFiles() > 0
                || !Patcher.CURRENT.equals(patcher.status());
        System.out.println("result:    " + patcher.status()
                + (patcher.message().isEmpty() ? "" : " - " + patcher.message()));

        if (dryRun) {
            System.out.println("relaunch:  " + (launcher == null
                    ? "(not a launcher we can start)" : launcher.command()) + "   [dry run]");
            return;
        }
        if (patchOnly) {
            System.out.println("patch-only: not relaunching; start the instance yourself");
            return;
        }
        if (launcher == null) {
            System.out.println("next:      start the instance from your launcher");
            return;
        }
        if (!changed) {
            System.out.println("next:      nothing changed, not relaunching");
            return;
        }
        System.out.println("relaunch:  " + launcher.command());
        if (launcher.start()) {
            System.out.println("relaunch:  started");
        } else {
            System.out.println("relaunch:  failed - start the instance yourself");
        }
    }

    /**
     * Waits until the game has released the resource pack zips. The mod starts this process and
     * then shuts Minecraft down, so the first seconds belong to a still-exiting game; without this
     * the install would fail with a sharing violation exactly as it does in-session.
     */
    static void waitForLocks(Path resourcepacks, int seconds) {
        if (seconds <= 0) return;
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        boolean said = false;
        while (System.currentTimeMillis() < deadline) {
            boolean free = true;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(resourcepacks, "*.zip")) {
                for (Path p : ds) {
                    try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                        // opening for write is enough; nothing is modified
                    } catch (IOException locked) {
                        free = false;
                        break;
                    }
                }
            } catch (IOException ignored) {
                // folder missing: nothing to wait for
            }
            if (free) return;
            if (!said) {
                System.out.println("waiting for the game to release the resource packs...");
                said = true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        System.out.println("gave up waiting for the pack locks; trying anyway");
    }

    // ---- launcher detection -------------------------------------------------

    /** The launcher whose instance layout contains {@code instanceDir}, and how to start it. */
    static Game findLauncher(Path instanceDir, String forcedBinary) {
        Path instances = instanceDir.getParent();
        if (forcedBinary != null) {
            Path exe = Paths.get(forcedBinary);
            return exe.toFile().isFile() ? new Game("explicit", exe, instances,
                    instanceDir.getFileName().toString()) : null;
        }
        if (instances == null || instances.getFileName() == null
                || !"instances".equalsIgnoreCase(instances.getFileName().toString())) {
            return null;
        }
        Path root = instances.getParent();
        if (root == null) return null;
        for (Known known : KNOWN) {
            for (String binary : known.binaries) {
                Path candidate = root.resolve(binary);
                if (candidate.toFile().isFile()) {
                    return new Game(known.name, candidate, root,
                            instanceDir.getFileName().toString());
                }
            }
            for (String marker : known.rootMarkers) {
                if (root.resolve(marker).toFile().isFile()) {
                    // cfg present but binary not beside it: still the right launcher, unknown path
                    return new Game(known.name + " (binary not found)", null, root,
                            instanceDir.getFileName().toString());
                }
            }
        }
        return null;
    }

    /** A launcher invocation: {@code <binary> --dir <root> --launch <id>}. */
    static final class Game {
        final String name;
        final Path binary;
        final Path root;
        final String id;

        Game(String name, Path binary, Path root, String id) {
            this.name = name;
            this.binary = binary;
            this.root = root;
            this.id = id;
        }

        String command() {
            if (binary == null) return "(no binary found for " + name + ")";
            return "\"" + binary + "\" --dir \"" + root + "\" --launch \"" + id + "\"";
        }

        String describe() {
            return name + "  id=" + id + "  root=" + root;
        }

        boolean start() {
            if (binary == null) return false;
            try {
                List<String> cmd = new ArrayList<String>();
                cmd.add(binary.toString());
                cmd.add("--dir");
                cmd.add(root.toString());
                cmd.add("--launch");
                cmd.add(id);
                new ProcessBuilder(cmd).directory(root.toFile()).start();
                return true;
            } catch (IOException e) {
                System.err.println("could not start " + binary + ": " + e.getMessage());
                return false;
            }
        }
    }

    // ---- discovery (for the standalone window) ------------------------------

    /** Prints every known launcher root and its instances; the window will use the same data. */
    static void discover() {
        List<Path> roots = new ArrayList<Path>();
        String appData = System.getenv("APPDATA");
        String home = System.getProperty("user.home", ".");
        addIfDir(roots, home + "/.local/share/PrismLauncher");
        addIfDir(roots, home + "/.local/share/multimc");
        addIfDir(roots, home + "/.local/share/polymc");
        if (appData != null) {
            addIfDir(roots, appData + "/PrismLauncher");
            addIfDir(roots, appData + "/MultiMC");
            addIfDir(roots, appData + "/PolyMC");
        }
        // portable installs: walk up from this jar and look for anything with an instances folder
        Path here = beside(".");
        for (Path p = here; p != null && p.getParent() != null; p = p.getParent()) {
            for (String name : new String[]{"PrismLauncher", "PrismLauncher-Windows", "MultiMC", "PolyMC"}) {
                addIfDir(roots, p.resolve(name).toString());
            }
            Path instances = p.resolve("instances");
            if (Files.isDirectory(instances)) {
                System.out.println("root: " + p);
                listInstances(p);
                break;
            }
        }
        for (Path root : roots) {
            System.out.println("root: " + root);
            listInstances(root);
        }
    }

    private static void addIfDir(List<Path> roots, String path) {
        Path p = Paths.get(path);
        if (Files.isDirectory(p) && Files.isDirectory(p.resolve("instances"))) {
            for (Path existing : roots) {
                if (existing.equals(p)) return;
            }
            roots.add(p);
        }
    }

    private static void listInstances(Path root) {
        Path instances = root.resolve("instances");
        if (!Files.isDirectory(instances)) return;
        try {
            Files.list(instances).filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(new java.util.function.Consumer<Path>() {
                public void accept(Path dir) {
                    Path mc = dir.resolve("minecraft");
                    String mods = mc.resolve("mods").toFile().isDirectory() ? "mods" : "-";
                    String shaders = mc.resolve("shaderpacks").toFile().isDirectory() ? "shaderpacks" : "-";
                    boolean demonica = hasFileNamed(mc.resolve("mods"), "demonica");
                    String state = readState(mc.resolve("config").resolve(Patcher.STATE_NAME));
                    System.out.println("  " + dir.getFileName() + "  [" + mods + " " + shaders
                            + (demonica ? " demonica" : "") + "]  " + state);
                }
            });
        } catch (IOException e) {
            System.out.println("  could not list " + instances + ": " + e.getMessage());
        }
    }

    private static boolean hasFileNamed(Path dir, String lowerNeedle) {
        File d = dir.toFile();
        File[] files = d.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.ROOT).contains(lowerNeedle)) return true;
        }
        return false;
    }

    // ---- the window ---------------------------------------------------------

    /**
     * Opens the installer window on a real display. Undecorated, because the mockup is drawn as a
     * GregTech panel with its own square close button; dragging therefore has to be handled here.
     */
    static void openWindow(final Path gameDir, final Path instanceDir, final Path dataDir,
                           final int scale, final int waitSeconds, final String forcedLauncher,
                           final boolean dryRun, final boolean patchOnly) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("no display: use --preview <png> to render the window instead");
            return;
        }
        final Game game = findLauncher(instanceDir, forcedLauncher);
        final boolean[] changed = {false};
        try {
            final suspiciousspeculars.launcher.ui.PatcherWindow panel =
                    new suspiciousspeculars.launcher.ui.PatcherWindow(scale);
            panel.setInstalled(isInstalled(gameDir));
            panel.setOutputName(Patcher.OUT_SHADER);
            // the window exists to get people unstuck: say what is missing before the first click
            panel.setWarnings(prerequisiteLines(gameDir, dataDir, true));
            showRecordedFailure(panel, gameDir);

            final javax.swing.JFrame frame = new javax.swing.JFrame("Suspicious Speculars");
            frame.setUndecorated(true);
            frame.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            System.out.println("window:    " + panel.getPreferredSize().width + "x"
                    + panel.getPreferredSize().height + " at scale " + scale);
            System.out.println("output:    " + Patcher.OUT_SHADER);

            panel.onClose(new Runnable() {
                public void run() {
                    // Never end the process mid-run: the worker owns files while it is going.
                    if (panel.isBusy()) return;
                    frame.dispose();
                    System.exit(0);
                }
            });
            panel.onLink(new suspiciousspeculars.launcher.ui.PatcherWindow.LinkHandler() {
                public void open(String url) {
                    if (!openLink(url)) System.err.println("could not open " + url);
                }
            });
            panel.onAction(new Runnable() {
                public void run() {
                    if (panel.isComplete()) {
                        // Continue is the hand-off: relaunch the instance the way the CLI path does,
                        // then exit so the process that spawned us can let go.
                        handOff(game, gameDir, changed[0], dryRun, patchOnly);
                        frame.dispose();
                        System.exit(0);
                    } else if (!panel.isBusy()) {
                        startInstall(panel, gameDir, dataDir, waitSeconds, changed);
                    }
                }
            });

            MouseAdapter mover = new MouseAdapter() {
                private int grabX = -1;
                private int grabY;

                @Override
                public void mousePressed(MouseEvent e) {
                    boolean onTitle = e.getY() / scale < 14;
                    grabX = onTitle ? e.getX() : -1;
                    grabY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (grabX >= 0) {
                        frame.setLocation(frame.getX() + e.getX() - grabX,
                                frame.getY() + e.getY() - grabY);
                    }
                }
            };
            panel.addMouseListener(mover);
            panel.addMouseMotionListener(mover);

            frame.setVisible(true);
            System.out.println("window: shown, scale " + scale);
        } catch (Throwable t) {
            System.err.println("could not open the window: " + t);
        }
    }

    /**
     * Starts the instance again, the way the CLI path does after a run. Only when something actually
     * changed, and never in a dry run or a patch-only run - those exist to leave the game alone.
     */
    static void handOff(Game game, Path gameDir, boolean changed, boolean dryRun, boolean patchOnly) {
        if (dryRun || patchOnly) {
            System.out.println("hand-off:  skipped (dry run or patch only)");
            return;
        }
        if (game == null) {
            System.out.println("hand-off:  no launcher to start; start the instance yourself");
            return;
        }
        if (!changed) {
            System.out.println("hand-off:  nothing changed, not relaunching");
            return;
        }
        System.out.println("hand-off:  " + game.command());
        // the relaunched game must not hand over again: mark the attempt so its coremod patches
        // in-process (a quick check) and starts instead of opening a second window
        markRelaunchGuard(gameDir);
        if (game.start()) {
            System.out.println("hand-off:  started");
        } else {
            System.out.println("hand-off:  failed - start the instance yourself");
        }
    }

    /**
     * Runs the patcher on a worker thread and streams its progress into the window.
     *
     * The window never blocks: every update goes through invokeLater, the counter follows what the
     * patcher actually reports ("patched N files", one "packs: ..." line per pack), and the run ends
     * either in the complete state - summary and Continue - or with the failure in the band.
     */
    static void startInstall(final suspiciousspeculars.launcher.ui.PatcherWindow panel,
                             final Path gameDir, final Path dataDir, final int waitSeconds,
                             final boolean[] changed) {
        panel.setBusy(true);
        panel.setProgress(0);

        final List<String[]> fetched = new ArrayList<String[]>();
        final int[] shaderFiles = {0};
        final int[] packs = {0};
        final String[] baseName = {""};
        final boolean[] demonicaFailed = {false};

        Thread worker = new Thread(new Runnable() {
            public void run() {
                Patcher patcher = null;
                try {
                    // the mod starts this process while the game is still exiting: give the pack
                    // zips a moment to be released, exactly as the CLI path does
                    waitForLocks(gameDir.resolve("resourcepacks"), waitSeconds);
                    patcher = new Patcher(gameDir, dataDir)
                            .version("launcher")
                            .packs(Patcher.PackMode.INSTALL)
                            .log(new Patcher.Log() {
                                public void line(String text) {
                                    note(text, fetched, shaderFiles, packs, baseName, demonicaFailed, panel);
                                }
                            });
                    patcher.run();
                    patcher.finish();
                    changed[0] = patcher.editedFiles() > 0
                            || !Patcher.CURRENT.equals(patcher.status());

                    // a fresh install, a no-op run and a pending pack update are all "done" as far as
                    // the window is concerned; the pack update is applied in-game by the command
                    String state = patcher.status();
                    boolean ok = Patcher.CURRENT.equals(state)
                            || Patcher.INSTALLED.equals(state)
                            || Patcher.PACKS_UPDATE.equals(state)
                            || Patcher.RESTART_REQUIRED.equals(state);

                    if (ok) {
                        final List<String[]> summary = new ArrayList<String[]>(fetched);
                        if (!baseName[0].isEmpty()) {
                            summary.add(new String[]{"Eclipse", baseName[0]});
                        }
                        final List<String[]> prereq = prerequisiteLines(gameDir, dataDir, !demonicaFailed[0]);
                        javax.swing.SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                panel.setWarnings(prereq);
                                panel.setComplete(summary.isEmpty()
                                        ? defaultSummary() : summary);
                            }
                        });
                    } else {
                        final String why = patcher.message().isEmpty()
                                ? "status: " + patcher.status() : patcher.message();
                        final List<String[]> prereq = prerequisiteLines(gameDir, dataDir, !demonicaFailed[0]);
                        javax.swing.SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                panel.setWarnings(prereq);
                                List<String[]> msg = new ArrayList<String[]>();
                                msg.add(new String[]{"install", "did not finish"});
                                msg.add(new String[]{"", why});
                                panel.setFailure(msg);
                            }
                        });
                    }
                } catch (final Throwable t) {
                    // the patcher records its reason before it rethrows, so prefer that wording to a
                    // bare exception message like "AccessDeniedException: <path>"
                    final String why = patcher != null && !patcher.message().isEmpty()
                            ? patcher.message()
                            : (t.getMessage() == null ? String.valueOf(t) : t.getMessage());
                    javax.swing.SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            List<String[]> msg = new ArrayList<String[]>();
                            msg.add(new String[]{"install", "failed"});
                            msg.add(new String[]{"", why});
                            panel.setFailure(msg);
                        }
                    });
                }
            }
        }, "patcher-install");
        worker.setDaemon(true);
        worker.start();
    }

    /** Reads the patcher's own lines: progress steps, the packs it applied, the base it fetched. */
    static void note(String text,
                     List<String[]> fetched, int[] shaderFiles, int[] packs, String[] baseName,
                     boolean[] demonicaFailed,
                     suspiciousspeculars.launcher.ui.PatcherWindow panel) {
        System.out.println(text);

        if (text.startsWith("base: ")) {
            // "Eclipse-e89e6c6-pristine.zip" -> "e89e6c6", which is what the summary should say
            String name = text.substring(6).trim();
            name = name.replaceFirst("^Eclipse-", "")
                    .replaceFirst("-pristine\\.zip$", "")
                    .replaceFirst("\\.zip$", "");
            baseName[0] = name;
        } else if (text.startsWith("patched ")) {
            // "patched 22 files -> Eclipse-SUSY.zip": the token after the verb is the count
            int end = text.indexOf(' ', 8);
            String token = end > 0 ? text.substring(8, end) : text.substring(8);
            try {
                shaderFiles[0] = Integer.parseInt(token.trim());
            } catch (RuntimeException ignored) {
                shaderFiles[0]++;
            }
        } else if (text.startsWith("eclipse: ") && text.contains("downloading")) {
            synchronized (fetched) {
                fetched.add(new String[]{"Eclipse", "base downloaded"});
            }
        } else if (text.startsWith("demonica: fetch failed")) {
            demonicaFailed[0] = true;
        } else if (text.startsWith("demonica: fetched ")) {
            demonicaFailed[0] = false;
            String version = text.substring("demonica: fetched ".length()).trim();
            synchronized (fetched) {
                fetched.add(new String[]{"Demonica", version + " installed"});
            }
        } else if (text.startsWith("packs: ") && text.contains(".zip")) {
            // one line per pack: "packs: <name>.zip up to date (hash)" or "... installed", and the
            // download's own "packs: fetched <name>.zip (KB)" - strip the verb so the band shows the
            // pack, not "fetched Suspicious Speculars ..." running into the corner logo
            packs[0]++;
            String rest = text.substring(7).trim();
            if (rest.startsWith("fetched ")) rest = rest.substring("fetched ".length());
            else if (rest.startsWith("would fetch ")) rest = rest.substring("would fetch ".length());
            int zip = rest.indexOf(".zip");
            String name = zip > 0 ? rest.substring(0, zip) : rest;
            String detail = zip > 0 ? rest.substring(zip + 4).trim() : "";
            name = name.replaceFirst("^Suspicious Speculars - ", "");
            detail = detail.replaceFirst("\\s*\\([0-9a-f]{8}\\)$", "");
            synchronized (fetched) {
                fetched.add(new String[]{name, detail});
            }
        }

        final int done = Math.min(STEPS, shaderFiles[0] + packs[0]);
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                panel.setProgress(done);
            }
        });
    }

    /** What to say when a run finished but reported nothing specific. */
    static List<String[]> defaultSummary() {
        List<String[]> out = new ArrayList<String[]>();
        out.add(new String[]{"Eclipse", "installed"});
        return out;
    }

    /** Draws the window into a PNG, so it can be reviewed without a display. */
    static void previewWindow(Path gameDir, Path instanceDir, Path dataDir, int scale, Path out,
                              int frame, String hint, String errors) {
        System.setProperty("java.awt.headless", "true");
        if (frame >= 0) {
            framePreview(gameDir, dataDir, scale, out, frame, hint, errors);
            return;
        }
        try {
            // Live state, drawn by the same window the game opens: installed or not, the real output
            // name, and whatever prerequisites are actually missing.
            suspiciousspeculars.launcher.ui.PatcherWindow panel =
                    new suspiciousspeculars.launcher.ui.PatcherWindow(scale);
            panel.setInstalled(isInstalled(gameDir));
            panel.setOutputName(Patcher.OUT_SHADER);
            panel.setWarnings(prerequisiteLines(gameDir, dataDir, true));
            showRecordedFailure(panel, gameDir);
            panel.savePreview(out.toFile());
            System.out.println("preview: " + out.toAbsolutePath());
        } catch (Throwable t) {
            System.err.println("could not render the preview: " + t);
        }
    }

    /** Renders one GIF state from the new window, which is being ported element by element. */    static void framePreview(Path gameDir, Path dataDir, int scale, Path out, int frame, String hint,
                             String errors) {
        try {
            suspiciousspeculars.launcher.ui.PatcherWindow panel =
                    new suspiciousspeculars.launcher.ui.PatcherWindow(scale);
            panel.setFrame(frame);
            panel.showHint(hintIndex(hint));
            if ("demo".equals(errors)) {
                panel.showDemoErrors();
            } else if ("live".equals(errors)) {
                // live mode shows the instance's real values, including the output's real name
                panel.setOutputName(Patcher.OUT_SHADER);
                panel.setWarnings(prerequisiteLines(gameDir, dataDir, true));
                showRecordedFailure(panel, gameDir);
            } else if ("failed".equals(errors)) {
                // the failure band as a run leaves it, so the state can be reviewed without a game
                List<String[]> msg = new ArrayList<String[]>();
                msg.add(new String[]{"install", "did not finish"});
                msg.add(new String[]{"", "that Eclipse zip is not the supported version (e89e6c6)"});
                panel.setFailure(msg);
            } else if (errors != null) {
                System.err.println("unknown --errors " + errors + " (demo, live, failed)");
            }
            panel.savePreview(out.toFile());
            System.out.println("frame " + frame + ": " + out.toAbsolutePath());
        } catch (Throwable t) {
            System.err.println("could not render frame " + frame + ": " + t);
        }
    }

    /**
     * A window reopened after a failed run: say what went wrong instead of looking pristine. The
     * failure was written by the run that threw (or the run that reached base-mismatch), so the
     * reason survives the process that produced it.
     */
    static void showRecordedFailure(suspiciousspeculars.launcher.ui.PatcherWindow panel, Path gameDir) {
        try {
            Properties state = Patcher.loadState(Patcher.stateFile(gameDir));
            String status = state.getProperty("status", "");
            String why = state.getProperty("message", "");
            if (Patcher.isFailure(status) && !why.isEmpty()) {
                List<String[]> msg = new ArrayList<String[]>();
                msg.add(new String[]{"install", "failed"});
                msg.add(new String[]{"", why});
                panel.setFailure(msg);
            }
        } catch (Throwable ignored) {
            // an unreadable state simply shows nothing; the run itself will say why
        }
    }

    /**
     * Missing prerequisites as band lines: {@code {name, detail, href}}. The name is what the link
     * opens, so only a detected problem produces one; nothing is invented when the instance is fine.
     */
    static List<String[]> prerequisiteLines(Path gameDir, Path dataDir, boolean demonicaWillFetch) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            Patcher patcher = new Patcher(gameDir, dataDir);
            patcher.detectPrerequisites();
            boolean cleanroomMissing = patcher.cleanroom() == null || patcher.cleanroom().isEmpty();
            if (cleanroomMissing) {
                out.add(new String[]{"Cleanroom", "missing", Patcher.URL_CLEANROOM});
            }
            // A mod Demonica cannot share the game with: named so it can be removed, with no link
            // and no action of ours - the shader still installs.
            for (String[] m : patcher.incompatibleMods()) {
                out.add(new String[]{m[0], m[2], null, "problem"});
            }
            // Demonica is ours to install and cannot run without Cleanroom: while Cleanroom is
            // missing there is nothing for the player to do, so it is not shown at all. With
            // Cleanroom present it says "will be installed", and only a failed fetch falls back to
            // the "missing" link.
            if (!cleanroomMissing && !patcher.demonicaOk()) {
                out.add(demonicaWillFetch
                        ? new String[]{"Demonica", "will be installed", null}
                        : new String[]{"Demonica", "missing", Patcher.URL_DEMONICA});
            }
        } catch (Throwable ignored) {
            // an unreadable instance simply reports nothing; the run itself will say why
        }
        return out;
    }

    /** Tile name from --hint to its position in the row, in the order the tiles are drawn. */
    static int hintIndex(String name) {
        if (name == null) return -1;
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < suspiciousspeculars.launcher.ui.PatcherWindow.tileCount(); i++) {
            if (suspiciousspeculars.launcher.ui.PatcherWindow.tileId(i).equals(key)) return i;
        }
        System.err.println("unknown hint " + name + " (duck, github, modrinth, discord)");
        return -1;
    }

    /**
     * What each tile will open, one line per tile: {@code link <id> <url>} (or {@code quack} for the
     * button). The build diffs this against ui/manifest.json, because no URL is ever drawn and so no
     * pixel diff can see a typo in one.
     */
    static void printLinks() {
        for (int i = 0; i < suspiciousspeculars.launcher.ui.PatcherWindow.tileCount(); i++) {
            System.out.println("link " + suspiciousspeculars.launcher.ui.PatcherWindow.tileId(i)
                    + " " + suspiciousspeculars.launcher.ui.PatcherWindow.tileTarget(i));
        }
    }

    /**
     * Reports the cue's format and plays it once. Exit 0 when the jar carries a decodable cue, 1 when
     * it does not; a machine with no mixer still exits 0 and says so, because that is not a packaging
     * fault. Nothing draws the sound, so this is its only automated check.
     */
    static int playQuack() {
        String path = "/ui/sound/quack.wav";
        String described = suspiciousspeculars.launcher.ui.Sound.describe(path);
        System.out.println("sound: " + described);
        if (!described.startsWith(path)) return 1;
        boolean played = suspiciousspeculars.launcher.ui.Sound.playNow(path);
        System.out.println(played ? "sound: played"
                : "sound: no audio device here; the tile falls back to the system beep");
        return 0;
    }

    /** {@code --sound}: the same check without playing, so the build gate stays silent. */
    static int describeQuack() {
        String path = "/ui/sound/quack.wav";
        String described = suspiciousspeculars.launcher.ui.Sound.describe(path);
        System.out.println("sound: " + described);
        return described.startsWith(path) ? 0 : 1;
    }

    /** The file the action produces next to the button, as the state last recorded it. */
    static String outputName(Path gameDir) {
        String shader = state(gameDir).getProperty("shader", "");
        return shader.isEmpty() ? Patcher.OUT_SHADER : shader;
    }

    /** Compares dotted date versions numerically: 2026-11-17.4 is older than 2026-11-17.19. */
    static int compareVersions(String a, String b) {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int x = i < left.length && !left[i].isEmpty() ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length && !right[i].isEmpty() ? Integer.parseInt(right[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** VERSION from the bundled manifest, or "" when the patch data is not beside us. */
    static String manifestVersion(Path dataDir) {
        try {
            Path manifest = dataDir.resolve("packs.txt");
            if (!Files.isRegularFile(manifest)) return "";
            for (String line : Files.readAllLines(manifest)) {
                if (line.startsWith("VERSION\t")) return line.substring(8).trim();
            }
        } catch (IOException ignored) {
            // no manifest: the caller falls back to the state's own version
        }
        return "";
    }

    /** True once a run has completed: the output slot then shows the cut-out goog block. */
    static boolean isInstalled(Path gameDir) {
        String status = state(gameDir).getProperty("status", "");
        return Patcher.INSTALLED.equals(status) || Patcher.PACKS_UPDATE.equals(status);
    }

    static String actionLabel(Path gameDir, Path dataDir) {
        Properties state = state(gameDir);
        String status = state.getProperty("status", "");
        boolean installed = Patcher.INSTALLED.equals(status) || Patcher.PACKS_UPDATE.equals(status);
        if (!installed) return "Install";
        if (updatePending(state, dataDir)) return "Update";
        return "Reinstall";
    }

    /** True only when the offered version is newer than what is installed. */
    static boolean updatePending(Properties state, Path dataDir) {
        if (!"true".equals(state.getProperty("updateAvailable", "false"))) return false;
        String available = state.getProperty("availableVersion", "");
        String local = state.getProperty("packVersion", "");
        if (local.isEmpty()) local = manifestVersion(dataDir);
        if (available.isEmpty() || local.isEmpty()) return true;
        return compareVersions(available, local) >= 0;
    }

    static String versionLabel() {
        String version = Launcher.class.getPackage().getImplementationVersion();
        return version == null || version.isEmpty() ? "V0.1.1" : "V" + version;
    }

    static Properties state(Path gameDir) {
        try {
            return Patcher.loadState(gameDir.resolve("config").resolve(Patcher.STATE_NAME));
        } catch (IOException e) {
            return new Properties();
        }
    }

    /** Integer GUI scale, or the fallback when the value is not a positive number. */
    static int parseScale(String value, int fallback) {
        try {
            int n = Integer.parseInt(value.trim());
            return n >= 1 ? n : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Marks the relaunch as a hand-over: the next launch's coremod then patches in-process (a quick
     * check) instead of opening a second window, whatever the state file says.
     */
    static void markRelaunchGuard(Path gameDir) {
        Properties state = state(gameDir);
        state.setProperty("relaunchAttempted", "true");
        Path file = Patcher.stateFile(gameDir);
        try {
            Files.createDirectories(file.getParent());
            java.io.Writer writer = Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                state.store(writer, "Suspicious Speculars");
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("could not record the relaunch: " + e);
        }
    }

    /**
     * Removes the hand-over guard, because the launcher running is the proof that the spawn worked.
     * The coremod sets the flag before spawning so a launcher that dies cannot cause a retry loop;
     * a launcher that starts and then shows its window (or is closed by the player) must not keep the
     * flag, or the window would appear only once per install.
     */
    static void clearRelaunchFlag(Path gameDir) {
        Properties state = state(gameDir);
        if (state.remove("relaunchAttempted") == null) return;
        Path file = Patcher.stateFile(gameDir);
        try {
            Files.createDirectories(file.getParent());
            java.io.Writer writer = Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                state.store(writer, "Suspicious Speculars");
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("could not clear the hand-over flag: " + e);
        }
    }

    /** Opens a project link; false when the link is not configured yet. */
    static boolean openLink(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            java.awt.Desktop.getDesktop().browse(new URI(url));
            return true;
        } catch (Throwable t) {
            System.err.println("could not open " + url + ": " + t);
            return false;
        }
    }

    private static String readState(Path file) {
        try {
            java.util.Properties p = Patcher.loadState(file);
            String status = p.getProperty("status", "never run");
            String version = p.getProperty("packVersion", "");
            return status + (version.isEmpty() ? "" : " " + version);
        } catch (IOException e) {
            return "unreadable state";
        }
    }
}
