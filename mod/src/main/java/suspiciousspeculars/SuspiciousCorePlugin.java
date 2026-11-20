package suspiciousspeculars;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The early half: a coremod that hands the launch over to the launcher window before FML constructs
 * any mod.
 *
 * Forge 1.12 builds mods in pre-init in mod-id order, so a normal mod called
 * {@code suspiciousspeculars} sits near the end of a 200-mod list and a hand-over from there lands
 * ~70 s into the launch. This class runs at coremod discovery instead, right after the mods folder is
 * scanned and before mixins, resource loading and any mod code - a few seconds in.
 *
 * Deliberately free of every other class in this jar. A coremod's classes are loaded by the launch
 * loader before FML knows about the mod half, and the earlier version of this class touched
 * {@code ModData}/{@code Patcher}; once the jar was replaced between launches that produced
 * {@code NoClassDefFoundError: suspiciousspeculars/ModData}. Everything here is JDK plus the loader
 * interface, so nothing can be loaded twice. The patch data and the launcher are unpacked from this
 * jar directly, with the same markers the mod's own unpackers use, so the two never disagree.
 *
 * Declared in the jar manifest as FMLCorePlugin, with FMLCorePluginContainsFMLMod so the mod half of
 * the jar still loads normally. If anything here fails, it logs and returns: pre-init's hand-over is
 * still there as the fallback, and a coremod must never take the launch down.
 */
public final class SuspiciousCorePlugin implements IFMLLoadingPlugin {

    /** Kept for pre-init, which asks whether the early half already did the work. */
    public static final String EARLY_PROPERTY = "suspiciousspeculars.earlyRun";

    private static final String BUNDLE = "assets/suspiciousspeculars/patch-bundle.zip";
    private static final String BUNDLE_VERSION = "assets/suspiciousspeculars/patch-bundle.version";
    /** Where the data came from: "bundled" may be replaced by a newer jar, "repository" may not. */
    private static final String SOURCE = ".bundle-source";
    private static final String LAUNCHER = "assets/suspiciousspeculars/launcher.jar";
    private static final String LAUNCHER_VERSION = "assets/suspiciousspeculars/launcher.version";
    private static final String ATTEMPTED = "relaunchAttempted";

    /**
     * Mirrors {@code Patcher.MANIFEST_URL}. Read here as well because a release is invisible to the
     * state until something asks the host, and the in-game prompt is no longer opened by itself.
     */
    private static final String MANIFEST =
            "https://raw.githubusercontent.com/NeoRussia/suspicious-speculars/main/packs.txt";

    /** Mirrors {@code Patcher.OUT_SHADER}: this class must not link against mod classes. */
    private static final String SHADER = "Eclipse-SUSY.zip";

    @Override
    public void injectData(Map<String, Object> data) {
        Logger log = LogManager.getLogger("Suspicious Speculars (early)");
        try {
            Object location = data.get("mcLocation");
            if (!(location instanceof File)) {
                log.warn("no game directory from FML, leaving the hand-over to pre-init");
                return;
            }
            File gameDir = (File) location;

            // On a Cleanroom relauncher instance the first JVM only starts the second one. Let that
            // second JVM hand over, so the player sees one window and one launcher, not two.
            if (!insideCleanroom() && relauncherInstalled(gameDir)) {
                log.info("bootstrap JVM: leaving the hand-over to the relaunched game");
                return;
            }

            File self = selfJar();
            if (self == null) {
                log.warn("own jar not readable; leaving the hand-over to pre-init");
                return;
            }

            File config = new File(new File(gameDir, "config"), "suspicious-speculars");
            File dataDir = new File(config, "patch");
            ensureData(self, dataDir, log);
            File launcher = ensureLauncher(self, config, log);
            if (launcher == null) launcher = findLauncher(gameDir);

            Properties state = loadState(gameDir);
            boolean allowed = !"false".equals(state.getProperty("checkOnLaunch"));
            // "installed" counts as done unless an update or an in-game install request is waiting:
            // otherwise the relaunch Continue starts hands over again, one window per launch.
            String status = state.getProperty("status", "");
            boolean current = "current".equals(status) || "installed".equals(status);
            boolean update = "true".equals(state.getProperty("updateAvailable"));
            boolean requested = "true".equals(state.getProperty("installOnNextStart"));
            boolean attempted = "true".equals(state.getProperty(ATTEMPTED));
            // The state is a claim about files, and the claim can outlive them - a player who clears
            // shaderpacks/ or mods/ to "start over" still has status=installed. Without this, no
            // window opens and pre-init silently refetches and repatches, which looks like nothing
            // happened at all.
            if (current) {
                String missing = missingOutput(gameDir, state);
                if (missing != null) {
                    current = false;
                    log.warn("state says " + status + " but " + missing + " is gone; handing over");
                }
            }
            // The patch itself changes between releases. A shader that still matches its recorded hash
            // may have been patched from older data, so the release's shader edits have not arrived; the
            // window is where a rebuild happens, so this earns a hand-over on this launch.
            if (current && !dataVersion(dataDir).equals(state.getProperty("dataVersion"))) {
                log.info("patch data changed since the shader was written; handing over");
                current = false;
            }
            // The state only knows what the last run saw. Ask the host whether a newer pack release
            // exists, so the window opens on the first launch after a release rather than one launch
            // later. Offline, the state's verdict stands.
            if (current && !update && !requested) {
                String published = publishedVersion();
                String installed = state.getProperty("packVersion", "");
                if (installed.isEmpty()) installed = state.getProperty("availableVersion", "");
                if (newer(published, installed)) {
                    log.info("packs: " + published + " is published, " + installed
                            + " is installed; handing over");
                    // the window reads these to say "update <version> available" and label the
                    // button Update, instead of offering a plain reinstall
                    state.setProperty("updateAvailable", "true");
                    state.setProperty("availableVersion", published);
                    saveState(gameDir, state);
                    current = false;
                }
            }
            log.info("state: status=" + status + " update=" + update + " requested=" + requested
                    + " attempted=" + attempted + " launcher=" + (launcher != null));
            // A failed run must be readable in the client log: the window lives in its own process,
            // so its failure would otherwise stay in that process's log file.
            String why = state.getProperty("message", "");
            if (!why.isEmpty() && ("error".equals(status) || "base-mismatch".equals(status)
                    || "packs-missing".equals(status))) {
                log.error("previous run did not finish (" + status + "): " + why);
            }

            if (!allowed) {
                log.info("checkOnLaunch=false, leaving the shader to the player");
                return;
            }
            if (current && !update && !requested) return;   // nothing to do
            if (launcher == null) {
                log.info("no launcher jar; pre-init will patch in-process");
                return;
            }
            if (attempted && !requested) {
                log.info("hand-over already attempted; pre-init will patch in-process");
                return;
            }
            if (!new File(dataDir, "patch.txt").isFile()) {
                log.warn("patch data missing; pre-init will unpack it and hand over there");
                return;
            }

            state.setProperty(ATTEMPTED, "true");
            saveState(gameDir, state);
            log.info("window: handing over before the game initialises");
            if (startLauncher(gameDir, launcher, dataDir, log)) {
                System.out.flush();
                Runtime.getRuntime().halt(0);
            }
        } catch (Throwable t) {
            // a coremod that throws takes the whole launch down: log and let pre-init try instead
            log.error("early hand-over failed; pre-init will retry", t);
        }
    }

    // ---- the launcher -------------------------------------------------------

    private static boolean startLauncher(File gameDir, File launcher, File dataDir, Logger log) {
        try {
            File java = new File(System.getProperty("java.home"), "bin/javaw.exe");
            if (!java.isFile()) java = new File(System.getProperty("java.home"), "bin/java");
            File instanceDir = "minecraft".equals(gameDir.getName()) ? gameDir.getParentFile() : gameDir;
            File logFile = new File(new File(gameDir, "logs"), "suspicious-speculars-launcher.log");
            logFile.getParentFile().mkdirs();

            List<String> cmd = new ArrayList<String>();
            cmd.add(java.getAbsolutePath());
            cmd.add("-jar");
            cmd.add(launcher.getAbsolutePath());
            cmd.add("--instance");
            cmd.add(instanceDir.getAbsolutePath());
            cmd.add("--data");
            cmd.add(dataDir.getAbsolutePath());
            cmd.add("--wait");
            cmd.add("60");
            cmd.add("--window");
            // the launch this handed over is already halted: give it back even when the run finds
            // nothing to change
            cmd.add("--hand-back");

            log.info("handing over to the launcher: " + cmd);
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start();
            Thread.sleep(600); // long enough for a missing jar or a bad java to die
            if (!process.isAlive()) {
                log.warn("launcher exited immediately (code " + process.exitValue() + ")");
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.error("could not start the launcher", t);
            return false;
        }
    }

    /** The launcher jar, wherever the pack put it: beside the mods or in our config folder. */
    private static File findLauncher(File gameDir) {
        File mods = new File(gameDir, "mods");
        File[] candidates = mods.listFiles();
        if (candidates != null) {
            for (File f : candidates) {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("suspicious-speculars-launcher") && name.endsWith(".jar")) return f;
            }
        }
        File direct = new File(new File(new File(gameDir, "config"), "suspicious-speculars"), "launcher.jar");
        return direct.isFile() ? direct : null;
    }

    // ---- unpacking from our own jar ----------------------------------------

    private static File selfJar() {
        try {
            URL url = SuspiciousCorePlugin.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI());
            return file.isFile() ? file : null;   // a classes directory in a dev launch
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureData(File self, File dataDir, Logger log) {
        try {
            String want = text(readEntry(self, BUNDLE_VERSION)).trim();
            if (want.isEmpty()) return;
            Path marker = dataDir.toPath().resolve(".bundle-version");
            Path source = dataDir.toPath().resolve(SOURCE);
            String have = Files.isRegularFile(marker)
                    ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim() : "";
            if (want.equals(have)) return;

            // Data the mod fetched from the shader repository can be newer than this jar. Overwriting
            // it here would undo the update on every launch, before the mod gets a chance to run, so
            // the early half only ever writes the bundled copy over bundled data (or over nothing).
            String from = Files.isRegularFile(source)
                    ? new String(Files.readAllBytes(source), StandardCharsets.UTF_8).trim() : "";
            if ("repository".equals(from)) return;

            byte[] bundle = readEntry(self, BUNDLE);
            if (bundle == null) return;

            // Replace, do not merge: files from an older bundle (whole stock files, mostly) would
            // otherwise survive beside the new ones and never be used again.
            deleteTree(dataDir.toPath());
            Files.createDirectories(dataDir.toPath());
            int files = 0;
            ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bundle));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path target = dataDir.toPath().resolve(entry.getName()).normalize();
                    if (!target.startsWith(dataDir.toPath())) throw new IOException("bad entry " + entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            } finally {
                zis.close();
            }
            Files.write(marker, want.getBytes(StandardCharsets.UTF_8));
            Files.write(source, "bundled".getBytes(StandardCharsets.UTF_8));
            log.info("unpacked " + files + " patch files before the game started");
        } catch (Throwable t) {
            log.warn("patch data unpack failed; pre-init will retry: " + t);
        }
    }

    /** Recursive delete for the data directory; before mods load, nothing else has it open. */
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            DirectoryStream<Path> stream = Files.newDirectoryStream(path);
            try {
                for (Path child : stream) deleteTree(child);
            } finally {
                stream.close();
            }
        }
        Files.deleteIfExists(path);
    }

    /** The patch data marker written by {@link #ensureData}, or empty when it is not there. */
    private static String dataVersion(File dataDir) {
        try {
            Path marker = dataDir.toPath().resolve(".bundle-version");
            return Files.isRegularFile(marker)
                    ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim()
                    : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static File ensureLauncher(File self, File config, Logger log) {
        File jar = new File(config, "launcher.jar");
        File marker = new File(config, "launcher.version");
        try {
            String want = text(readEntry(self, LAUNCHER_VERSION)).trim();
            if (want.isEmpty()) return jar.isFile() ? jar : null;
            if (jar.isFile() && marker.isFile()
                    && want.equals(new String(Files.readAllBytes(marker.toPath()),
                    StandardCharsets.UTF_8).trim())) {
                return jar;
            }
            byte[] payload = readEntry(self, LAUNCHER);
            if (payload == null) return jar.isFile() ? jar : null;

            Files.createDirectories(config.toPath());
            Path temp = config.toPath().resolve("launcher.jar.tmp");
            Files.write(temp, payload);
            Files.move(temp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(marker.toPath(), want.getBytes(StandardCharsets.UTF_8));
            log.info("launcher: unpacked " + want.substring(0, Math.min(12, want.length()))
                    + " before the game started");
            return jar;
        } catch (Throwable t) {
            log.warn("launcher unpack failed; pre-init will retry: " + t);
            return jar.isFile() ? jar : null;
        }
    }

    /** Reads one entry out of this jar, as bytes. */
    private static byte[] readEntry(File jar, String name) throws IOException {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            InputStream in = zip.getInputStream(entry);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1 << 13];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            if (zip != null) zip.close();
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    // ---- state --------------------------------------------------------------

    private static File stateFile(File gameDir) {
        return new File(new File(gameDir, "config"), "suspicious-speculars.properties");
    }

    private static Properties loadState(File gameDir) {
        Properties state = new Properties();
        File file = stateFile(gameDir);
        if (!file.isFile()) return state;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            state.load(in);
        } catch (Throwable ignored) {
            // an unreadable state only means "assume there is work"
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
        return state;
    }

    private static void saveState(File gameDir, Properties state) {
        File file = stateFile(gameDir);
        try {
            Files.createDirectories(file.toPath().getParent());
            Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            try {
                state.store(writer, "Suspicious Speculars");
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // a missing flag only costs one extra hand-over attempt, never a failed launch
        }
    }

    // ---- environment --------------------------------------------------------

    /**
     * The first file the recorded state promises that is not on disk, or null. Only the two the
     * window would rebuild: the shader, and the Demonica the state says it installed. The base is
     * left out on purpose - it is refetchable without a window, and its absence alone does not mean
     * the installed shader is wrong.
     */
    private static String missingOutput(File gameDir, Properties state) {
        File shader = new File(new File(gameDir, "shaderpacks"), SHADER);
        if (!shader.isFile()) return "shaderpacks/" + SHADER;
        String demonica = state.getProperty("demonica", "");
        if (!demonica.isEmpty() && !hasModJar(gameDir, "demonica")) {
            return "Demonica " + demonica;
        }
        // A pack the state says it enabled but which is gone is an incomplete install too; without
        // this the game starts and its own main-menu prompt asks the player to quit and install.
        File packs = new File(gameDir, "resourcepacks");
        for (String key : new String[]{"pack", "addon"}) {
            String name = state.getProperty(key, "");
            if (!name.isEmpty() && !new File(packs, name).isFile()) {
                return "resourcepacks/" + name;
            }
        }
        return null;
    }

    /** VERSION from the published manifest, or "" when it cannot be read. */
    private static String publishedVersion() {
        java.net.URLConnection conn = null;
        try {
            String url = System.getProperty("suspiciousspeculars.manifestUrl", MANIFEST);
            conn = new java.net.URL(url).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            if (conn instanceof java.net.HttpURLConnection) {
                conn.setRequestProperty("User-Agent", "SuspiciousSpeculars");
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(),
                            StandardCharsets.UTF_8));
            try {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("VERSION")) continue;
                    String[] fields = line.split("\t");
                    return fields.length > 1 ? fields[1].trim() : "";
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            // offline, or the host is unreachable: the state's verdict stands
        } finally {
            if (conn instanceof java.net.HttpURLConnection) {
                ((java.net.HttpURLConnection) conn).disconnect();
            }
        }
        return "";
    }

    /** Numeric compare of dotted versions: 2026-11-19.1 is newer than 2026-11-18.1. */
    private static boolean newer(String candidate, String installed) {
        if (candidate == null || installed == null
                || candidate.isEmpty() || installed.isEmpty()) {
            return false;
        }
        String[] a = candidate.split("[^0-9]+");
        String[] b = installed.split("[^0-9]+");
        try {
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
                int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
                if (x != y) return x > y;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return false;
    }

    /** A jar named after {@code token}, in mods/ or FML's versioned subfolder. */
    private static boolean hasModJar(File gameDir, String token) {
        File mods = new File(gameDir, "mods");
        File[] dirs = {mods, new File(mods, "1.12.2")};
        for (File dir : dirs) {
            File[] jars = dir.listFiles();
            if (jars == null) continue;
            for (File jar : jars) {
                String name = jar.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".jar") && name.contains(token)) return true;
            }
        }
        return false;
    }

    /** True when Cleanroom's loader is on this JVM's classpath rather than the bootstrap Forge one. */
    private static boolean insideCleanroom() {
        try {
            return SuspiciousCorePlugin.class.getClassLoader()
                    .getResource("com/cleanroommc/boot/Main.class") != null;
        } catch (Throwable notCleanroom) {
            return false;
        }
    }

    /** True when mods/ holds the Cleanroom relauncher, so a second JVM is still to come. */
    private static boolean relauncherInstalled(File gameDir) {
        for (String dir : new String[]{"mods", "mods/1.12.2"}) {
            File[] files = new File(gameDir, dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().toLowerCase(java.util.Locale.ROOT).contains("cleanroom-relauncher")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when this launch has already been handled by {@link #injectData}. */
    public static boolean alreadyRan() {
        return Boolean.parseBoolean(System.getProperty(EARLY_PROPERTY, "false"));
    }

    // ---- loader interface ---------------------------------------------------

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
