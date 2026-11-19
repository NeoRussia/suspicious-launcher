package suspiciousspeculars;

import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.Logger;
import suspiciousspeculars.patch.EclipseFetch;
import suspiciousspeculars.patch.Patcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The in-game half of Suspicious Speculars.
 *
 * Ships as an optional file inside SuperSymmetry, so it does exactly one job at start-up: if the
 * recorded state no longer matches what is on disk, run the patcher. Everything it writes -
 * {@code shaderpacks/Eclipse-SUSY.zip}, the merged resource pack, Demonica's
 * {@code shaders.properties} entry and the pack's place in {@code options.txt} - is read by
 * Demonica before a world loads, so a run that produces the shader can only take effect on the
 * next start. That is why it says so once, in chat, instead of pretending otherwise.
 */
@Mod(modid = SuspiciousSpeculars.MODID, name = SuspiciousSpeculars.NAME,
        version = SuspiciousSpeculars.VERSION, clientSideOnly = true,
        acceptableRemoteVersions = "*")
public final class SuspiciousSpeculars {

    public static final String MODID = "suspiciousspeculars";
    public static final String NAME = "Suspicious Speculars";
    public static final String VERSION = "0.1.3pre"; // the @Mod annotation; the jar name has the build number
    /** Full build version from mcmod.info (0.1.3pre-7), so logs and state name the exact jar. */
    private static final String BUILD = buildVersion();

    private static String buildVersion() {
        // Read this jar's own mcmod.info. Not getResourceAsStream("/mcmod.info"): that lookup is
        // classpath-wide and every mod ships the same path, so it returned whichever jar the loader
        // reached first - Demonica's, which is how 0.9.1 ended up in our state file.
        try {
            java.security.CodeSource source =
                    SuspiciousSpeculars.class.getProtectionDomain().getCodeSource();
            java.io.File self = source == null || source.getLocation() == null
                    ? null : new java.io.File(source.getLocation().toURI());
            if (self != null && self.isFile()) {
                try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(self)) {
                    java.util.zip.ZipEntry entry = jar.getEntry("mcmod.info");
                    if (entry != null) {
                        String version;
                        try (java.io.InputStream in = jar.getInputStream(entry)) {
                            version = versionIn(new String(readAll(in),
                                    java.nio.charset.StandardCharsets.UTF_8));
                        }
                        if (version != null) return version;
                    }
                }
            } else if (self != null && self.isDirectory()) {
                // a dev run: the expanded resource sits beside the classes
                java.io.File meta = new java.io.File(self, "mcmod.info");
                if (meta.isFile()) {
                    String version = versionIn(new String(
                            java.nio.file.Files.readAllBytes(meta.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
                    if (version != null) return version;
                }
            }
        } catch (Throwable ignored) {
            // exotic loaders (Cleanroom's union: scheme), dev trees: fall back below
        }
        String packaged = SuspiciousSpeculars.class.getPackage().getImplementationVersion();
        return packaged == null || packaged.isEmpty() ? VERSION : packaged;
    }

    /** First string value of {@code "version"} in a mcmod.info document, or null. */
    private static String versionIn(String text) {
        int at = text.indexOf("\"version\"");
        if (at < 0) return null;
        int q1 = text.indexOf('"', text.indexOf(':', at) + 1);
        int q2 = text.indexOf('"', q1 + 1);
        return q1 > 0 && q2 > q1 ? text.substring(q1 + 1, q2) : null;
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Mod.Instance(MODID)
    public static SuspiciousSpeculars instance;

    private Logger log;
    private Path gameDir;
    private Path dataDir;

    private String lastStatus = "";
    private String lastMessage = "";
    private boolean patchedThisSession;
    private boolean updateWaiting;
    private boolean updateOffered;
    private String availableVersion = "";
    private boolean noticed;

    /**
     * Jars Demonica cannot share the game with, as their file names. Reported in the log and in chat,
     * never removed or disabled: what leaves a player's mods folder is their decision.
     */
    private final java.util.List<String> incompatible = new java.util.ArrayList<String>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        log = event.getModLog();
        log.info("Suspicious Speculars " + BUILD + " starting");
        gameDir = event.getModConfigurationDirectory().getParentFile().toPath();
        dataDir = gameDir.resolve("config").resolve("suspicious-speculars").resolve("patch");
        MinecraftForge.EVENT_BUS.register(this);

        // Launch order on a relauncher instance is: LaunchWrapper -> FML (Java 8) -> Cleanroom
        // relauncher -> a *second* JVM on Cleanroom. Patching in the first session would be thrown
        // away with that process, so the work belongs to the Cleanroom one - and Cleanroom is the
        // only loader Demonica runs on anyway. If no relauncher is installed, this session is the
        // real one, so an unreadable marker must not stop the work there.
        if (!insideCleanroom() && relauncherInstalled()) {
            log.info("Cleanroom relauncher present: leaving the shader to the relaunched game");
            lastStatus = "deferred";
            return;
        }

        detectIncompatibleMods();

        if (SuspiciousCorePlugin.alreadyRan()) {
            // the coremod patched before GameSettings existed; nothing to do but read the result
            log.info("already patched before the game initialised");
            lastStatus = state().getProperty("status", "");
            lastMessage = state().getProperty("message", "");
            if (Patcher.isFailure(lastStatus) && !lastMessage.isEmpty()) {
                // the window runs in its own process, so its failure would otherwise stay in that
                // process's log: surface it here, in the client log, on the first launch after it
                log.error("previous run did not finish (" + lastStatus + "): " + lastMessage);
            }
            updateWaiting = updatePendingFromState();
            availableVersion = versionFromState();
            return;
        }

        try {
            ModData.ensure(dataDir, log);
        } catch (IOException e) {
            log.error("could not unpack the patch data", e);
            lastStatus = Patcher.ERROR;
            lastMessage = "patch data missing: " + e.getMessage();
            return;
        }

        // the window is a resource of this jar: unpack it before the plan looks for a launcher
        ModLauncher.ensure(gameDir, new java.util.function.Consumer<String>() {
            public void accept(String message) {
                log.info(message);
            }
        });

        // Launch-time hand-over. The core plugin that used to do this is not registered (one jar
        // serving as coremod and mod loaded our classes twice), so the decision lives here now.
        if (handOverAtLaunch()) return;

        if (checkOnLaunch()) {
            runPatcher(false, true);
        } else {
            log.info("checkOnLaunch=false, leaving the shader alone (use /suspiciousspeculars install)");
        }
    }

    /**
     * True when Cleanroom's loader is on this JVM's classpath rather than the bootstrap Forge one.
     * Resource lookup rather than {@code Class.forName}: Cleanroom's classes are Java 25 bytecode and
     * loading one on a Java 8 JVM throws, which would read as "not Cleanroom".
     */
    private static boolean insideCleanroom() {
        try {
            return SuspiciousSpeculars.class.getClassLoader()
                    .getResource("com/cleanroommc/boot/Main.class") != null;
        } catch (Throwable notCleanroom) {
            return false;
        }
    }

    /** True when mods/ holds the Cleanroom relauncher, i.e. a second JVM is still to come. */
    private boolean relauncherInstalled() {
        try {
            for (Path p : Patcher.modJars(gameDir)) {
                if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .contains("cleanroom-relauncher")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // treat an unreadable mods folder as "no relauncher"
        }
        return false;
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSuspicious(this));
    }

    /** Runs the patcher and remembers the outcome for the command and the chat notice. */
    public void runPatcher(boolean force, boolean atStartup) {
        try {
            Patcher patcher = new Patcher(gameDir, dataDir).version(BUILD).force(force).late(!atStartup)
                    .packs(atStartup ? Patcher.PackMode.CHECK : Patcher.PackMode.INSTALL)
                    .log(new Patcher.Log() {
                public void line(String text) {
                    log.info(text);
                }
            });
            patcher.run();
            patcher.finish();
            lastStatus = patcher.status();
            lastMessage = patcher.message();
            if (Patcher.isFailure(lastStatus)) {
                // in-process runs already stream their lines into the client log; this names the
                // verdict at error level so a failed install is visible without reading the state
                log.error("patching did not finish (" + lastStatus + ")"
                        + (lastMessage.isEmpty() ? "" : ": " + lastMessage));
            }
            patchedThisSession = patcher.needsRestart();
            updateWaiting = patcher.updateAvailable();
            availableVersion = patcher.availableVersion();
        } catch (Throwable t) {
            // A broken patch must never take the game down with it.
            log.error("patching failed", t);
            lastStatus = Patcher.ERROR;
            lastMessage = String.valueOf(t);
            patchedThisSession = false;
        }
    }

    public Properties state() {
        try {
            return Patcher.loadState(Patcher.stateFile(gameDir));
        } catch (IOException e) {
            return new Properties();
        }
    }

    public boolean checkOnLaunch() {
        return Boolean.parseBoolean(state().getProperty("checkOnLaunch", "true"));
    }

    public void setCheckOnLaunch(boolean value) {
        Properties state = state();
        state.setProperty("checkOnLaunch", Boolean.toString(value));
        Path file = Patcher.stateFile(gameDir);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                state.store(w, "Suspicious Speculars");
            }
        } catch (IOException e) {
            log.error("could not save checkOnLaunch", e);
        }
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Logger log() {
        return log;
    }

    public String lastStatus() {
        return lastStatus;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public boolean patchedThisSession() {
        return patchedThisSession;
    }

    /**
     * One chat line, once per session, and only when there is something the player has to do:
     * restart, or fetch the Eclipse pack he does not have.
     */
    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (noticed || !event.getWorld().isRemote) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || event.getEntity() != mc.player) return;
        noticed = true;

        if (patchedThisSession) {
            say(TextFormatting.GOLD, "Suspicious Speculars installed - restart Minecraft to apply it.");
        } else if (Patcher.BASE_MISSING.equals(lastStatus) || Patcher.BASE_MISSING.equals(state().getProperty("status"))) {
            say(TextFormatting.GOLD, "Suspicious Speculars needs an Eclipse pack: run /suspiciousspeculars download.");
        }
        if (updateWaiting || updatePendingFromState()) {
            say(TextFormatting.GOLD, "Suspicious Speculars: resource pack update "
                    + versionFromState() + " is ready - /suspiciousspeculars update.");
        }
        // The update notice above already says this; echoing it in red made a normal update look
        // like a failure. Red is for problems only.
        if (!lastMessage.isEmpty() && !lastMessage.startsWith("resource pack update")) {
            say(TextFormatting.RED, "Suspicious Speculars: " + lastMessage);
        }
        if (!incompatible.isEmpty()) {
            // the one incompatibility worth saying out loud in game: the shader cannot work next to it
            say(TextFormatting.RED, "Demonica is not compatible with "
                    + String.join(", ", incompatible) + " - remove it and restart.");
        }
    }

    /**
     * OptiFine and Nothirium cannot work with Demonica: the first replaces the shader pipeline the
     * shader pack is drawn by (Demonica descends from Angelica, an OptiFine replacement), the second
     * replaces the chunk renderer Celeritas provides. Detection is by jar name, because both ship
     * without a usable mod id at this point in the launch; nothing is deleted or disabled.
     */
    private void detectIncompatibleMods() {
        incompatible.clear();
        try {
            for (Path jar : Patcher.modJars(gameDir)) {
                String name = jar.getFileName().toString();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("optifine") && !lower.contains("nothirium")) continue;
                String label = lower.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
                incompatible.add(label);
                log.warn(label + " is installed - Demonica is not compatible with it; remove it"
                        + " for the shader to work");
            }
        } catch (java.io.IOException e) {
            log.warn("could not scan mods/ for incompatible mods: " + e);
        }
    }

    /**
     * True when no usable Eclipse base is on disk right now. Asked of the filesystem rather than the
     * recorded state: after a manual change the state can lag reality, and a prompt that says
     * "missing" about something present is worse than no prompt.
     */
    public boolean needsSetup() {
        try {
            return !new Patcher(gameDir, dataDir).hasBase();
        } catch (Throwable t) {
            log.warn("could not check for an Eclipse base", t);
            return Patcher.BASE_MISSING.equals(state().getProperty("status"));
        }
    }

    /** Live dependency detection for the screen, again rather than trusting the state file. */
    public Patcher freshDetection() {
        Patcher patcher = new Patcher(gameDir, dataDir);
        try {
            patcher.detectPrerequisites();
        } catch (IOException e) {
            log.warn("could not detect prerequisites", e);
        }
        return patcher;
    }

    /** Fetches Eclipse e89e6c6 and patches, off-thread; used by the screen's first-run button. */
    public void downloadEclipse() {
        final SuspiciousSpeculars mod = this;
        Thread worker = new Thread("suspicious-speculars-download") {
            @Override
            public void run() {
                try {
                    Patcher patcher = new Patcher(gameDir, dataDir);
                    EclipseFetch.fetch(gameDir, patcher.detectFile(), patcher.detectMarker(),
                            new Patcher.Log() {
                                public void line(String text) {
                                    log.info(text);
                                }
                            });
                    runPatcher(true, false);
                } catch (Throwable t) {
                    log.error("Eclipse download failed", t);
                }
            }
        };
        worker.setDaemon(true);
        worker.start();
    }

    /** True when a previous startup recorded an update that has not been installed yet. */
    public boolean updatePendingFromState() {
        return Boolean.parseBoolean(state().getProperty("updateAvailable", "false"));
    }

    public String versionFromState() {
        String v = state().getProperty("availableVersion", "");
        if (v == null || v.isEmpty()) v = availableVersion;
        return v == null || v.isEmpty() ? "(unknown version)" : v;
    }

    /** Where the pack put the launcher: beside the mods, or in our config folder. */
    private Path findLauncherJar() {
        Path mods = gameDir.resolve("mods");
        if (java.nio.file.Files.isDirectory(mods)) {
            try (java.nio.file.DirectoryStream<Path> ds =
                         java.nio.file.Files.newDirectoryStream(mods, "suspicious-speculars-launcher*.jar")) {
                for (Path p : ds) return p;
            } catch (java.io.IOException ignored) {
                // fall through to the config copy
            }
        }
        Path config = gameDir.resolve("config").resolve("suspicious-speculars").resolve("launcher.jar");
        return java.nio.file.Files.isRegularFile(config) ? config : null;
    }

    /**
     * The launch-time hand-over: when the window is available and there is work, quit this JVM and
     * let the launcher install in front of the player.
     *
     * This is the decision the unregistered core plugin used to make, expressed with the same
     * {@link CorePlan} so the guards (current, attempted, no launcher) are still pure and tested. The
     * harness passes {@code --window}, so Install, progress, the band and Continue are all visible;
     * Continue is what relaunches the instance.
     */
    /**
     * The first file the recorded state promises that is gone, or null. Mirrors the coremod's copy:
     * the shader, and the Demonica the state says it installed. The base is left out - it is
     * refetchable without a window.
     */
    private String missingOutput(Properties state) {
        if (!java.nio.file.Files.isRegularFile(
                gameDir.resolve("shaderpacks").resolve(Patcher.OUT_SHADER))) {
            return "shaderpacks/" + Patcher.OUT_SHADER;
        }
        String demonica = state.getProperty("demonica", "");
        if (!demonica.isEmpty()) {
            boolean found = false;
            try {
                for (Path jar : Patcher.modJars(gameDir)) {
                    if (jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .contains("demonica")) {
                        found = true;
                        break;
                    }
                }
            } catch (IOException ignored) {
                // an unreadable mods/ cannot prove the jar is there either
            }
            if (!found) return "Demonica " + demonica;
        }
        // A pack the state says it enabled but which is gone is an incomplete install too; without
        // this the game starts and its own main-menu prompt asks the player to quit and install.
        for (String key : new String[]{"pack", "addon"}) {
            String name = state.getProperty(key, "");
            if (!name.isEmpty() && !java.nio.file.Files.isRegularFile(
                    gameDir.resolve("resourcepacks").resolve(name))) {
                return "resourcepacks/" + name;
            }
        }
        return null;
    }

    private boolean handOverAtLaunch() {
        Path launcherJar = findLauncherJar();
        if (launcherJar == null) return false;

        Properties state = state();
        String missing = missingOutput(state);
        if (missing != null) {
            // The state can outlive the files it describes (a player clearing shaderpacks/ to start
            // over). Treat that as work, so the window opens instead of a silent in-process patch.
            log.warn("state says " + state.getProperty("status", "") + " but " + missing
                    + " is gone; handing over");
            state.setProperty("status", "stale");
        }
        if (CorePlan.decide(state, true, checkOnLaunch()) != CorePlan.Action.HAND_OVER) return false;

        state.setProperty(CorePlan.ATTEMPTED, "true");
        saveState(state, "hand-over");
        try {
            File java = new File(System.getProperty("java.home"), "bin/javaw.exe");
            if (!java.isFile()) java = new File(System.getProperty("java.home"), "bin/java");
            File instanceDir = gameDir.getFileName().toString().equals("minecraft")
                    ? gameDir.getParent().toFile() : gameDir.toFile();

            java.util.List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(java.getAbsolutePath());
            cmd.add("-jar");
            cmd.add(launcherJar.toAbsolutePath().toString());
            cmd.add("--instance");
            cmd.add(instanceDir.getAbsolutePath());
            cmd.add("--data");
            cmd.add(dataDir.toAbsolutePath().toString());
            cmd.add("--wait");
            cmd.add("60");
            cmd.add("--window");

            log.info("handing over to the launcher: " + cmd);
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            gameDir.resolve("logs").resolve("suspicious-speculars-launcher.log").toFile()))
                    .start();
            Thread.sleep(600);
            if (!process.isAlive()) {
                log.warn("launcher exited immediately (code " + process.exitValue()
                        + "); patching in-process instead");
                return false;
            }
            System.out.flush();
            Runtime.getRuntime().halt(0);
            return true;
        } catch (Throwable t) {
            log.error("could not start the launcher; patching in-process", t);
            return false;
        }
    }

    /** Writes the state so a hand-over attempt is remembered across the JVM it halts. */
    private void saveState(Properties state, String why) {
        Path file = Patcher.stateFile(gameDir);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                state.store(w, "Suspicious Speculars");
            }
        } catch (IOException e) {
            log.error("could not record the " + why, e);
        }
    }

    /**
     * Asks the next start to install, then quits. Necessary because a running game holds the pack
     * zips open, so replacing them in-session fails on Windows with a sharing violation.
     */
    public void requestInstallAndQuit() {
        // The launcher does the install, not the next game start: it can wait for Minecraft to
        // release the pack zips, which no in-game code can do.
        Path launcherJar = findLauncherJar();
        if (launcherJar == null) {
            notify("Launcher not found next to the pack; close the game and run "
                    + "suspicious-speculars-launcher.jar yourself.", TextFormatting.RED);
            return;
        }
        try {
            File java = new File(System.getProperty("java.home"), "bin/javaw.exe");
            if (!java.isFile()) java = new File(System.getProperty("java.home"), "bin/java");
            File instanceDir = gameDir.getFileName().toString().equals("minecraft")
                    ? gameDir.getParent().toFile() : gameDir.toFile();
            java.util.List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(java.getAbsolutePath());
            cmd.add("-jar");
            cmd.add(launcherJar.toAbsolutePath().toString());
            cmd.add("--instance");
            cmd.add(instanceDir.getAbsolutePath());
            cmd.add("--data");
            cmd.add(dataDir.toAbsolutePath().toString());
            cmd.add("--wait");
            cmd.add("90");
            // the window is the point: it shows progress, then what was fetched, then waits for
            // Continue before relaunching the instance
            cmd.add("--window");
            new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            gameDir.resolve("logs").resolve("suspicious-speculars-launcher.log").toFile()))
                    .start();
            log.info("launcher started; quitting so it can install and relaunch");
            Minecraft.getMinecraft().shutdown();
            return;
        } catch (Throwable t) {
            log.error("could not start the launcher", t);
        }

        Properties state = state();
        state.setProperty("installOnNextStart", "true");
        Path file = Patcher.stateFile(gameDir);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                state.store(w, "Suspicious Speculars");
            }
        } catch (IOException e) {
            log.error("could not record the install request", e);
        }
        log.info("quitting so the next start can install the resource packs");
        Minecraft.getMinecraft().shutdown();
    }

    /** Closing the prompt with an update pending silences it for that version only. */
    public void dismissUpdate() {
        Properties state = state();
        state.setProperty("dismissedVersion", versionFromState());
        Path file = Patcher.stateFile(gameDir);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                state.store(w, "Suspicious Speculars");
            }
        } catch (IOException e) {
            log.error("could not save the dismissal", e);
        }
    }

    /** True when the waiting update has not been dismissed for its version yet. */
    public boolean updatePendingForDisplay() {
        if (!updatePendingFromState()) return false;
        String dismissed = state().getProperty("dismissedVersion", "");
        return !versionFromState().equals(dismissed);
    }

    public String pendingUpdateDetail() {
        String text = state().getProperty("updatePacks", "");
        return text == null ? "" : text;
    }

    /**
     * Opens the installer screen on the main menu, once per session, when the state says an update
     * is waiting - so the next restart after an update is detected shows it without a command.
     */
    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onGuiOpen(net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent.Post event) {
        if (updateOffered || !(event.getGui() instanceof net.minecraft.client.gui.GuiMainMenu)) return;
        if (!updatePendingForDisplay() && !needsSetup()) return;
        updateOffered = true;
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            public void run() {
                Minecraft.getMinecraft().displayGuiScreen(new GuiInstaller(SuspiciousSpeculars.this));
            }
        });
    }

    private void say(TextFormatting colour, String text) {
        notify(text, colour);
    }

    /**
     * Chat feedback, only when there is a player to receive it: on the main menu {@code mc.player}
     * is null, and sending to it is the NullPointerException the first version of the screen threw.
     * In menus the screen reports in place instead, and the log always has the line.
     */
    @SideOnly(Side.CLIENT)
    public void notify(String text, TextFormatting colour) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            TextComponentString line = new TextComponentString(text);
            line.getStyle().setColor(colour);
            mc.player.sendMessage(line);
        } else {
            log.info(text);
        }
    }
}
