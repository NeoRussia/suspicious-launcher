package suspiciousspeculars.patch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The Suspicious Speculars patcher: one implementation, two front ends.
 *
 * It takes the user's own Eclipse shader pack, applies the recorded line edits and replacement
 * files, writes {@code shaderpacks/Eclipse-SUSY.zip}, merges the base pack with the Eclipse
 * addon into one resource pack, points Demonica at the result and enables the pack in
 * {@code options.txt}. Every run records what happened in
 * {@code config/suspicious-speculars.properties}, and a run whose inputs and outputs still match
 * that record does nothing at all.
 *
 * No Forge, no Minecraft, no GUI: {@code suspicious-speculars-pre} calls it from the command line
 * before the JVM starts Minecraft, and the mod calls it from pre-init. Java 8 on purpose, because
 * the pre-launch front end runs on the launcher's own java.
 */
public final class Patcher {

    public static final String STATE_NAME = "suspicious-speculars.properties";

    /** Where a missing prerequisite is fetched from; the window shows these as blue links. */
    public static final String URL_CLEANROOM = "https://github.com/CleanroomMC/Cleanroom/releases/latest";
    public static final String URL_DEMONICA = "https://github.com/ndellagrotte/Demonica/releases/latest";
    // No URL for Celeritas: it is shaded into Demonica, so there is nothing separate to fetch.

    public static final String OUT_SHADER = "Eclipse-SUSY.zip";
    /** The shader loader a fresh install gets; any version already in mods/ is left alone. */
    public static final String DEMONICA_VERSION = "0.9.1";
    public static final String DEMONICA_JAR = "Demonica-" + DEMONICA_VERSION + ".jar";
    public static final String DEMONICA_URL =
            "https://github.com/ndellagrotte/Demonica/releases/download/v0.9.1/Demonica-0.9.1.jar";
    public static final String DEMONICA_SHA256 =
            "3855237cf837ae395a43a546bd24d1e6d7d4328666ab4b73cf7347530e93bd26";
    /** The two shippable resource packs, fetched and enabled separately. */
    public static final String PACK_BASE = "Suspicious Speculars.zip";
    public static final String PACK_ADDON = "Suspicious Speculars - Eclipse Addon.zip";
    /** Where the packs come from when they are not already in resourcepacks/. */
    public static final String DEFAULT_PACK_URL =
            "https://raw.githubusercontent.com/NeoRussia/suspicious-speculars/main/packs/";
    /**
     * The published manifest. The copy inside the jar is only the fallback: reading the repository
     * version lets a pack update reach players without rebuilding the mod, and lets the run say
     * which version it installed.
     */
    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/NeoRussia/suspicious-speculars/main/packs.txt";

    /** Statuses: current, installed, packs-update, packs-missing, base-missing, base-mismatch. */
    public static final String CURRENT = "current";
    public static final String INSTALLED = "installed";
    public static final String PACKS_UPDATE = "packs-update";
    public static final String PACKS_MISSING = "packs-missing";
    public static final String RESTART_REQUIRED = "restart-required";
    public static final String BASE_MISSING = "base-missing";
    public static final String BASE_MISMATCH = "base-mismatch";

    /**
     * The run threw instead of reaching a verdict: a write failed, a file was locked, the disk
     * filled up. Kept apart from the statuses above so the next launch retries rather than trusting
     * the previous result, and so every front end can log it as an error.
     */
    public static final String ERROR = "error";

    /** Whether this run may download and replace the resource packs, or only report them. */
    public enum PackMode {
        /** Report an available update; write nothing. */
        CHECK,
        /** Fetch, verify and enable. */
        INSTALL
    }

    /** Where progress lines go: stdout for the CLI, the game log for the mod. */
    public interface Log {
        void line(String text);
    }

    private final Path gameDir;
    private final Path dataDir;
    private boolean late;
    private boolean force;
    private boolean dryRun;

    private Log log = new Log() {
        public void line(String text) {
        }
    };
    private final StringBuilder transcript = new StringBuilder();
    private boolean finished;

    private String status = "ok";
    private String message = "";
    private String packsFailure = "";
    private String packVersion = "";
    private PackMode packMode = PackMode.CHECK;
    private boolean updateAvailable;
    private int packsInstalled;
    private String availableVersion = "";
    private final java.util.List<String> updatePacks = new java.util.ArrayList<String>();
    private String modVersion = "dev";
    private String baseName = "";
    private int editedFiles = 0;

    /** Detected from mods/ on every run; recorded in the state file. */
    private String cleanroom;
    private String demonica;
    private boolean demonicaOk;

    /**
     * Mods that cannot work alongside Demonica: {name, version, why}. Detected, reported, never
     * touched - removing a player's mod is not the installer's business.
     */
    private final List<String[]> incompatible = new ArrayList<String[]>();

    public Patcher(Path gameDir, Path dataDir) {
        this.gameDir = gameDir;
        this.dataDir = dataDir;
    }

    /** Re-run even when the state says the result is still current. */
    public Patcher force(boolean value) {
        this.force = value;
        return this;
    }

    /** Report only: read, verify, say what would happen, write nothing. */
    public Patcher dryRun(boolean value) {
        this.dryRun = value;
        return this;
    }

    public Patcher log(Log target) {
        this.log = target == null ? this.log : target;
        return this;
    }

    public String status() {
        return status;
    }

    public String message() {
        return message;
    }

    public String baseName() {
        return baseName;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Version line of the manifest the last run installed from; empty when it had none. */
    public String packVersion() {
        return packVersion;
    }

    /** Name and version of the front end writing the state, so a state file identifies its build. */
    public Patcher version(String value) {
        this.modVersion = value == null || value.trim().isEmpty() ? "dev" : value.trim();
        return this;
    }

    /** CHECK reports an available pack update and writes nothing; INSTALL fetches and enables. */
    public Patcher packs(PackMode mode) {
        this.packMode = mode == null ? PackMode.CHECK : mode;
        return this;
    }

    public boolean updateAvailable() {
        return updateAvailable;
    }

    public String availableVersion() {
        return availableVersion;
    }

    /** One line per pack that does not match the manifest, e.g. "name e488bfa5 -> b0691117". */
    public java.util.List<String> updatePacks() {
        return updatePacks;
    }

    /** Marker pair from the patch data: the file and the text that identify an Eclipse pack. */
    public String detectFile() throws IOException {
        return Patch.load(dataDir.resolve("patch.txt")).detectFile;
    }

    public String detectMarker() throws IOException {
        return Patch.load(dataDir.resolve("patch.txt")).detectMarker;
    }

    public int editedFiles() {
        return editedFiles;
    }

    public String transcript() {
        return transcript.toString();
    }

    public String cleanroom() {
        return cleanroom;
    }

    public String demonica() {
        return demonica;
    }

    public boolean demonicaOk() {
        return demonicaOk;
    }

    /**
     * Set when the run happens inside an already-started game (a command) rather than at pre-init.
     * Demonica reads {@code shaders.properties} from a GameSettings mixin, which runs inside the
     * Minecraft constructor - after every FML lifecycle event - so a pre-init run is picked up in
     * the same launch and only an in-session run needs a restart.
     */
    public Patcher late(boolean value) {
        this.late = value;
        return this;
    }

    /** True when this run produced a shader that only a restart can pick up. */
    public boolean needsRestart() {
        return late && !dryRun && (INSTALLED.equals(status) || PACKS_MISSING.equals(status));
    }

    private void say(String text) {
        log.line(text);
        transcript.append(text).append('\n');
    }

    private void setStatus(String value, String why) {
        status = value;
        message = why;
        say("status: " + value + (why.isEmpty() ? "" : " - " + why));
    }

    public void run() throws IOException {
        try {
            execute(gameDir.resolve("config").resolve(STATE_NAME));
        } catch (IOException e) {
            failed(e);
            throw e;
        } catch (RuntimeException e) {
            failed(e);
            throw e;
        } catch (Error e) {
            failed(e);
            throw e;
        }
    }

    private void execute(Path stateFile) throws IOException {
        Path config = gameDir.resolve("config");
        Path target = gameDir.resolve("shaderpacks").resolve(OUT_SHADER);
        Path resourcepacks = gameDir.resolve("resourcepacks");
        Path shaderProps = config.resolve("shaders.properties");
        Path options = gameDir.resolve("options.txt");
        Files.createDirectories(config);
        Files.createDirectories(target.getParent());
        Files.createDirectories(resourcepacks);

        Patch patch = Patch.load(dataDir.resolve("patch.txt"));
        say("Suspicious Speculars patcher" + (dryRun ? " (dry run)" : ""));

        detectPrerequisites();
        say("cleanroom: " + (cleanroom == null ? "MISSING" : cleanroom)
                + "   demonica: " + (demonica == null ? "MISSING" : demonica));
        for (String[] conflict : incompatible) {
            // a warning, not a blocker: the shader still installs, and the player decides what to do
            say("warning: " + conflict[0]
                    + (conflict[1].isEmpty() ? "" : " " + conflict[1])
                    + " is installed - " + conflict[2]);
        }

        Path base = pickBase(patch);
        if (base == null) {
            // pickBase already set base-missing or base-mismatch, with the reason
            writeState(stateFile, null, null);
            finish();
            return;
        }
        baseName = base.getFileName().toString();
        say("base: " + baseName);

        Properties state = loadState(stateFile);
        boolean shaderCurrent = !force
                && "true".equals(state.getProperty("installed"))
                && Files.exists(target)
                && sha256(target).equals(state.getProperty("outputHash"))
                && sha256(base).equals(state.getProperty("baseHash"));

        if (shaderCurrent) {
            say("shader: current");
        } else if (late) {
            say("shader: out of date, but the shader zip is open - restart to patch it");
        } else if (dryRun) {
            // report the plan and write nothing: Patch.apply() opens the output zip, so a dry run
            // that called it would overwrite a working shader on a real instance
            String problem = patch.check(base);
            if (problem != null) {
                setStatus(BASE_MISMATCH, "that Eclipse zip is not the supported version (e89e6c6)");
                writeState(stateFile, base, null);
                finish();
                return;
            }
            say("would patch " + patch.editCount() + " files -> " + OUT_SHADER);
        } else {
            editedFiles = patch.apply(base, target, this);
            if (editedFiles < 0) {
                editedFiles = 0;
                setStatus(BASE_MISMATCH, "that Eclipse zip is not the supported version (e89e6c6)");
                writeState(stateFile, base, null);
                finish();
                return;
            }
            say("patched " + editedFiles + " files -> " + OUT_SHADER);
        }

        // The pack step runs even when the shader is current: a pack update is a manifest change,
        // not a shader change, and must not be masked by "nothing to do".
        boolean packsOk = packMode == PackMode.INSTALL
                ? installPacks(resourcepacks, options)
                : checkPacks(resourcepacks);

        // The shader loader is a dependency of the shader, not a pack: fetch it once, pinned and
        // hash-checked, only when mods/ has none. It loads on the next start - the same launch the
        // shader applies on - so the player does not have to fetch it by hand.
        boolean demonicaFetched = false;
        if (!dryRun) {
            demonicaFetched = fetchDemonica();
            if (demonicaFetched) detectPrerequisites();
        } else if (demonica == null && cleanroom != null) {
            say("demonica: would fetch " + DEMONICA_VERSION);
        }

        String blocker = prerequisiteProblem();
        if (!packsOk) {
            blocker = blocker == null
                    ? "resource pack missing: " + packsFailure + " (see logs/suspicious-speculars.log)"
                    : blocker;
        } else if (updateAvailable && blocker == null) {
            blocker = late
                    ? "restart Minecraft to install resource pack update " + availableVersion
                    : "resource pack update " + availableVersion + " available";
        }
        if (blocker == null) {
            say("prerequisites: ok");
        } else {
            say("warning: " + blocker);
        }

        if (!dryRun) {
            if (!shaderCurrent) writeShaderSelection(shaderProps);
            boolean changed = !shaderCurrent || packsInstalled > 0;
            String newStatus = !packsOk ? PACKS_MISSING
                    : (late && (updateAvailable || !shaderCurrent) ? RESTART_REQUIRED
                    : (updateAvailable ? PACKS_UPDATE : (changed ? INSTALLED : CURRENT)));
            setStatus(newStatus, blocker == null ? "" : blocker);
            writeState(stateFile, base, target);

            // Demonica keeps the saved option set in "<shader>.txt" beside the zip. Without one it
            // starts on the pack's own defaults, which the GUI labels "Custom"; write the SUSY
            // profile once on a fresh install, and never overwrite a file the player has edited.
            Path optionsFile = target.resolveSibling(target.getFileName() + ".txt");
            Path susyOptions = dataDir.resolve("susy-options.txt");
            if (Files.isRegularFile(target) && Files.isRegularFile(susyOptions)
                    && !Files.exists(optionsFile)) {
                Files.copy(susyOptions, optionsFile);
                say("options: wrote the SUSY profile to " + optionsFile.getFileName());
            }

            if (needsRestart()) {
                say("restart the game to apply");
            } else if (updateAvailable) {
                say("shader applies now; the pack update waits for /suspiciousspeculars update");
            } else if (shaderCurrent) {
                say("nothing to do");
            } else {
                say("written before Demonica reads its config - this launch picks it up");
            }
        }
        finish();
    }

    /**
     * Fetches the shader loader into mods/ when the instance has none. Only once Cleanroom is there:
     * Demonica cannot run without it, so downloading it earlier would just leave an unused jar.
     * Any version wins - there is no newer-version gate - so an existing jar is never replaced; the
     * pinned release is only what a fresh install gets. It loads on the next JVM start, which is the
     * same launch the shader applies on when the launcher hands back.
     */
    private boolean fetchDemonica() {
        if (demonica != null) return false;
        if (cleanroom == null) {
            // Demonica cannot run without Cleanroom; fetching it earlier would just leave an unused
            // jar, and the window hides it entirely in this state.
            return false;
        }
        Path mods = gameDir.resolve("mods");
        Path target = mods.resolve(DEMONICA_JAR);
        try {
            Files.createDirectories(mods);
            if (Files.exists(target)) {
                // same name, different body: a broken or partial file from an earlier run
                if (!DEMONICA_SHA256.equalsIgnoreCase(sha256(target))) Files.delete(target);
                else return false;
            }
            say("demonica: fetching " + DEMONICA_VERSION + " from its release");
            Fetch.download(DEMONICA_URL, target, new Log() {
                public void line(String text) {
                    say(text);
                }
            });
            if (!DEMONICA_SHA256.equalsIgnoreCase(sha256(target))) {
                Files.deleteIfExists(target);
                say("demonica: download does not match its hash, deleted");
                return false;
            }
            say("demonica: fetched " + DEMONICA_VERSION);
            return true;
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // a leftover jar here is worse than a missing one, but only the next run can retry
            }
            say("demonica: fetch failed - " + t.getMessage());
            return false;
        }
    }

    /** Refreshes the prerequisite detection without running the patch (screens and launchers use it). */
    public void detectPrerequisites() throws IOException {
        // Cleanroom's FML scans mods/ *and* a versioned subfolder (mods/1.12.2/), so jars live in
        // either place and a scan of the flat folder alone would report Demonica as missing.
        List<Path> mods = modJars(gameDir);
        // The Cleanroom relauncher is a coremod in mods/; without it the instance is Forge on
        // Java 8, and nothing shader-side works.
        // Three ways to be on Cleanroom: the loader itself (this JVM), the relauncher mod that
        // starts a second JVM, or the installer-style instance whose launch target *is* Cleanroom.
        // The launcher runs in its own JVM, so it can never see the loader; without the instance
        // check it reported a missing prerequisite on a perfectly good Cleanroom install.
        Path clea = findMod(mods, "cleanroom-relauncher");
        boolean loader = cleanroomLoader();
        cleanroom = loader ? "loader"
                : (clea != null ? versionOf(clea) : cleanroomInstance(gameDir));
        Path demonicaJar = findMod(mods, "demonica");
        demonica = demonicaJar == null ? null : versionOf(demonicaJar);
        // Celeritas is shaded into Demonica (com/demonica/celeritas/, ContainedDeps): it is not a
        // prerequisite of its own, and a correct install has no separate jar. Checking for one would
        // report a missing dependency on every good install.
        // Any Demonica is enough: it is the shader loader we target, its releases stay compatible
        // with these patches, and a break is fixed on our side rather than by asking for an upgrade.
        demonicaOk = demonica != null;
        detectConflicts(mods);
    }

    /**
     * The two mods Demonica cannot share the game with: OptiFine owns the shader pipeline Demonica
     * provides (the project descends from Angelica, an OptiFine replacement), and Nothirium replaces
     * the chunk renderer Celeritas provides. Found so the window and the chat can say so; nothing is
     * removed, disabled or blocked, because deciding what leaves a player's mods folder is theirs.
     */
    private void detectConflicts(List<Path> mods) throws IOException {
        incompatible.clear();
        conflict(mods, "optifine", "OptiFine");
        conflict(mods, "nothirium", "Nothirium");
    }

    private void conflict(List<Path> mods, String modid, String name) throws IOException {
        Path jar = findMod(mods, modid);
        if (jar == null) return;
        String version;
        try {
            version = versionOf(jar);
        } catch (IOException e) {
            version = "";
        }
        // versionOf falls back to the file name; repeating "OptiFine OptiFine_1.12.2_HD_U_G5" helps
        // nobody, so a fallback that starts with the mod id is treated as "no version known"
        if (version.toLowerCase(Locale.ROOT).startsWith(modid)) version = "";
        incompatible.add(new String[]{name, version, "not compatible with Demonica"});
    }

    /** Mods found that cannot work with Demonica: {name, version, why}. Empty on a normal install. */
    public List<String[]> incompatibleMods() {
        return incompatible;
    }

    /**
     * Cleanroom installed as the instance's launch target (the installer style), not as a mod in
     * mods/. MultiMC-family instances keep the component in {@code mmc-pack.json} and expand it into
     * {@code patches/<uid>.json}; Cleanroom's patch names itself and pulls {@code com.cleanroommc}
     * libraries. The version is read from that patch so the state and chat can quote it.
     */
    private static String cleanroomInstance(Path gameDir) {
        try {
            Path instance = "minecraft".equals(gameDir.getFileName().toString())
                    ? gameDir.getParent() : gameDir;
            if (instance == null) return null;

            Path patch = instance.resolve("patches").resolve("net.minecraftforge.json");
            if (Files.isRegularFile(patch)) {
                String text = new String(Files.readAllBytes(patch), StandardCharsets.UTF_8);
                if (text.contains("com.cleanroommc") || text.contains("\"name\": \"Cleanroom\"")) {
                    String version = metaValue(text, "version");
                    return version == null || version.isEmpty() ? "instance" : version;
                }
            }
            Path pack = instance.resolve("mmc-pack.json");
            if (Files.isRegularFile(pack)) {
                String text = new String(Files.readAllBytes(pack), StandardCharsets.UTF_8);
                if (text.contains("\"cachedName\": \"Cleanroom\"")) return "instance";
            }
        } catch (Throwable ignored) {
            // an unreadable instance simply reports nothing; the run will say why
        }
        return null;
    }

    /** Jars from mods/ and from FML's versioned subfolders (mods/1.12.2/, mods/1.12/...). */    public static List<Path> modJars(Path gameDir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        Path mods = gameDir.resolve("mods");
        out.addAll(jarsIn(mods));
        if (!Files.isDirectory(mods)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path sub : ds) {
                String name = sub.getFileName().toString();
                if (Files.isDirectory(sub) && name.matches("1\\.\\d+(\\.\\d+)?")) {
                    out.addAll(jarsIn(sub));
                }
            }
        }
        return out;
    }

    /**
     * True when Cleanroom's loader is on this JVM's classpath. A resource lookup, not
     * {@code Class.forName}: Cleanroom's classes are compiled for Java 25, so loading one on the
     * bootstrap Java 8 throws UnsupportedClassVersionError and would look like "not Cleanroom".
     */
    private static boolean cleanroomLoader() {
        try {
            return Patcher.class.getClassLoader()
                    .getResource("com/cleanroommc/boot/Main.class") != null;
        } catch (Throwable notCleanroom) {
            return false;
        }
    }

    /** @return why the setup cannot work yet, or null when the prerequisites are satisfied. */
    public String prerequisiteProblem() {
        if (cleanroom == null) {
            return "Cleanroom missing - install it from " + URL_CLEANROOM;
        }
        if (demonica == null) {
            return "Demonica missing - install it from " + URL_DEMONICA;
        }
        return null;
    }

    /**
     * Installs the two resource packs side by side and enables both.
     *
     * They are disjoint - the split put every texture in exactly one of them, so no file is
     * overridden and stack order does not matter - and they are small (1.8 MB + 0.13 MB), so they
     * are fetched from the project's own repository rather than shipped inside the mod. Each
     * download is checked against the SHA-256 in {@code packs.txt}: a mismatch deletes the file
     * instead of enabling something that would look wrong in game.
     *
     * @return false when a pack is missing or does not match, so the run can report it.
     */
    /**
     * Compares what is installed against the manifest without touching anything: this is what lets
     * a startup run say "an update is ready" instead of replacing packs behind the player's back.
     */
    private boolean checkPacks(Path resourcepacks) throws IOException {
        Manifest manifest;
        try {
            manifest = readManifest();
        } catch (IOException e) {
            say("packs: no manifest available (" + e.getMessage() + ")");
            return true;
        }
        // Note: packVersion is deliberately NOT touched here. It records what is installed, and a
        // check that rewrote it made the screen claim an update was already in place.
        availableVersion = manifest.version;
        updatePacks.clear();
        for (PackRef ref : manifest.packs) {
            Path dest = resourcepacks.resolve(ref.name);
            if (!Files.exists(dest)) {
                updatePacks.add(ref.name + " not installed");
                continue;
            }
            String have = sha256(dest);
            if (!have.equals(ref.sha256)) {
                updatePacks.add(ref.name + " " + shortHash(have) + " -> " + shortHash(ref.sha256));
            }
        }
        updateAvailable = !updatePacks.isEmpty();
        if (!updateAvailable) {
            // Every pack matches the manifest, so this version *is* what is installed. Leaving it
            // unset made the screen report "installed packs: none" and kept status at packs-update.
            packVersion = manifest.version;
        }
        if (updateAvailable) {
            say("packs: update available (" + (availableVersion.isEmpty() ? "no version" : availableVersion)
                    + "), " + updatePacks.size() + " of " + manifest.packs.size() + " packs differ");
            for (String line : updatePacks) say("packs:   " + line);
        } else {
            say("packs: up to date (" + (availableVersion.isEmpty() ? "no version" : availableVersion) + ")");
        }
        return true;
    }

    private boolean installPacks(Path resourcepacks, Path options) throws IOException {
        Manifest manifest;
        try {
            manifest = readManifest();
        } catch (IOException e) {
            // bundled manifest missing or unreadable: nothing to install, but not fatal
            say("packs: no manifest available (" + e.getMessage() + ")");
            return false;
        }
        if (manifest.packs.isEmpty()) {
            say("packs: the manifest lists no packs");
            return false;
        }
        boolean ok = true;
        String failure = "";
        for (PackRef ref : manifest.packs) {
            if (late) {
                // The game keeps resource pack zips open, so replacing one here fails with a
                // sharing violation. Report the difference and let the next start install it.
                Path dest = resourcepacks.resolve(ref.name);
                if (!Files.exists(dest) || !sha256(dest).equals(ref.sha256)) {
                    updatePacks.add(ref.name + (Files.exists(dest)
                            ? " " + shortHash(sha256(dest)) + " -> " + shortHash(ref.sha256)
                            : " not installed"));
                }
                continue;
            }
            Path dest = resourcepacks.resolve(ref.name);
            boolean current = Files.exists(dest) && sha256(dest).equals(ref.sha256);
            if (!current) {
                if (dryRun) {
                    say("packs: would fetch " + ref.name);
                    continue;
                }
                String url = manifest.resolve(ref.url);
                if (Files.exists(dest)) {
                    String have = sha256(dest);
                    say("packs: " + ref.name + " update " + shortHash(have) + " -> " + shortHash(ref.sha256));
                }
                try {
                    Fetch.download(url, dest, new Patcher.Log() {
                        public void line(String text) {
                            say("packs: " + text);
                        }
                    });
                } catch (Fetch.Failure f) {
                    // a 404 here means the file is not published at that URL - say so plainly
                    say("packs: " + ref.name + " -> " + f.getMessage());
                    failure = ref.name + ": " + shortReason(f);
                    ok = false;
                    continue;
                } catch (IOException e) {
                    say("packs: " + ref.name + " failed: " + e.getMessage());
                    failure = ref.name + ": " + e.getMessage();
                    ok = false;
                    continue;
                }
                if (!sha256(dest).equals(ref.sha256)) {
                    Files.deleteIfExists(dest);
                    say("packs: " + ref.name + " does not match its recorded hash, deleted");
                    failure = ref.name + ": downloaded file does not match its recorded hash";
                    ok = false;
                    continue;
                }
                packsInstalled++;
            } else {
                say("packs: " + ref.name + " up to date (" + shortHash(ref.sha256) + ")");
            }
            if (!dryRun) enableResourcePack(options, ref.name);
        }
        if (!ok) {
            packsFailure = failure;
        } else {
            packVersion = manifest.version;
            updateAvailable = false;
            availableVersion = packVersion;
        }
        return ok;
    }

    private static String shortHash(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }

    /** "404 not found" rather than the whole URL, which the log line already carries. */
    private static String shortReason(Fetch.Failure f) {
        if (f.notFound()) return "404 not found";
        if (f.status() > 0) return "HTTP " + f.status();
        return "file missing";
    }

    /** One line per pack in the manifest: name, sha256, url (absolute or relative to the manifest). */
    private static final class PackRef {
        final String name;
        final String sha256;
        final String url;

        PackRef(String name, String sha256, String url) {
            this.name = name;
            this.sha256 = sha256;
            this.url = url;
        }
    }

    /** The manifest as loaded: where it came from, its version, and the packs it lists. */
    private static final class Manifest {
        final List<PackRef> packs = new ArrayList<PackRef>();
        String version = "";
        String source = "bundled";
        String base = "";

        String resolve(String url) {
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:")) {
                return url;
            }
            if (base.isEmpty()) return url;
            try {
                return new java.net.URL(new java.net.URL(base), url).toString();
            } catch (java.net.MalformedURLException e) {
                return url;
            }
        }
    }

    /**
     * Where the manifest is read from: the published one by default, but a {@code manifest.url}
     * file in the patch data or the {@code suspiciousspeculars.manifestUrl} system property wins,
     * so a fork, a mirror or a test server can point the patcher at its own copy.
     */
    private String manifestUrl() {
        try {
            Path file = dataDir.resolve("manifest.url");
            if (Files.exists(file)) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty() && !text.startsWith("#")) return text;
            }
        } catch (IOException ignored) {
            // fall through to the constant
        }
        String override = System.getProperty("suspiciousspeculars.manifestUrl");
        return override != null && !override.trim().isEmpty() ? override.trim() : MANIFEST_URL;
    }

    /**
     * The repository manifest if it can be read, otherwise the bundled one. A pack update is then
     * simply a new manifest: no mod rebuild needed, and the run can name the version it installed.
     */
    private Manifest readManifest() throws IOException {
        String url = manifestUrl();
        Manifest remote = null;
        try {
            byte[] raw = Fetch.read(url, 64 * 1024);
            remote = parseManifest(new String(raw, StandardCharsets.UTF_8), "repository", url);
            say("packs: manifest " + (remote.version.isEmpty() ? "(no version)" : remote.version)
                    + " from " + url + ", " + remote.packs.size() + " packs");
            return remote;
        } catch (Fetch.Failure f) {
            say("packs: manifest unavailable (" + shortReason(f) + "), using the bundled copy");
        } catch (IOException e) {
            say("packs: manifest unavailable (" + e.getMessage() + "), using the bundled copy");
        }
        Manifest bundled = parseManifest(
                new String(Files.readAllBytes(dataDir.resolve("packs.txt")), StandardCharsets.UTF_8),
                "bundled", "");
        say("packs: manifest " + (bundled.version.isEmpty() ? "(no version)" : bundled.version)
                + " bundled, " + bundled.packs.size() + " packs");
        return bundled;
    }

    private static Manifest parseManifest(String text, String source, String base) {
        Manifest m = new Manifest();
        m.source = source;
        m.base = base;
        for (String line : text.split("\n")) {
            String row = line.trim();
            if (row.isEmpty() || row.startsWith("#")) continue;
            String[] f = row.split("\t");
            if (f[0].equalsIgnoreCase("VERSION") && f.length >= 2) {
                m.version = f[1].trim();
            } else if (f[0].equalsIgnoreCase("PACK") && f.length >= 4) {
                m.packs.add(new PackRef(f[1].trim(), f[2].trim().toLowerCase(Locale.ROOT), f[3].trim()));
            }
        }
        return m;
    }

    private void writeShaderSelection(Path shaderProps) throws IOException {
        List<String> lines = Files.exists(shaderProps)
                ? Files.readAllLines(shaderProps, StandardCharsets.UTF_8)
                : new ArrayList<String>();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("shaderPack=")) {
                lines.set(i, "shaderPack=" + OUT_SHADER);
                replaced = true;
                break;
            }
        }
        if (!replaced) lines.add("shaderPack=" + OUT_SHADER);
        if (!lines.contains("enableShaders=true")) lines.add("enableShaders=true");
        Files.write(shaderProps, lines, StandardCharsets.UTF_8);
        say("config: shaderPack=" + OUT_SHADER);
    }

    /**
     * Appends the pack to the end of options.txt's resourcePacks list, which is the top of the
     * stack (highest priority), and drops any earlier duplicate.
     */
    private void enableResourcePack(Path options, String pack) throws IOException {
        if (!Files.exists(options)) {
            say("options.txt: absent, pack left disabled");
            return;
        }
        List<String> lines = new ArrayList<String>(Files.readAllLines(options, StandardCharsets.UTF_8));
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("resourcePacks:")) {
                at = i;
                break;
            }
        }
        List<String> packs = new ArrayList<String>();
        if (at >= 0) {
            String value = lines.get(at).substring("resourcePacks:".length());
            int i = 0;
            while (i < value.length()) {
                int q = value.indexOf('"', i);
                if (q < 0) break;
                int end = value.indexOf('"', q + 1);
                if (end < 0) break;
                packs.add(value.substring(q + 1, end));
                i = end + 1;
            }
        }
        boolean had = packs.remove(pack);
        packs.add(pack);
        StringBuilder sb = new StringBuilder("resourcePacks:[");
        for (int i = 0; i < packs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(packs.get(i)).append('"');
        }
        sb.append(']');
        if (at >= 0) lines.set(at, sb.toString());
        else lines.add(sb.toString());
        Files.write(options, lines, StandardCharsets.UTF_8);
        say("options.txt: " + (had ? "moved to the top of the stack: " : "enabled ") + pack);
    }

    /**
     * Every zip in shaderpacks/ that carries the Eclipse marker, by content rather than by name:
     * a renamed build or a source archive with its top folder still on are both candidates.
     */
    public List<Path> findBases(Patch patch) throws IOException {
        List<Path> out = new ArrayList<Path>();
        Path dir = gameDir.resolve("shaderpacks");
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
            for (Path p : ds) {
                // never a base: our own output
                if (p.getFileName().toString().equals(OUT_SHADER)) continue;
                try (ZipFile z = new ZipFile(p.toFile())) {
                    ZipEntry e = z.getEntry(patch.detectFile);
                    if (e == null) {
                        // source archives keep their top folder, so match by suffix too
                        for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                            ZipEntry cand = en.nextElement();
                            if (cand.getName().endsWith("/" + patch.detectFile)) {
                                e = cand;
                                break;
                            }
                        }
                    }
                    if (e == null) continue;
                    String text = new String(readAll(z.getInputStream(e)), StandardCharsets.UTF_8);
                    if (text.contains(patch.detectMarker)) out.add(p);
                } catch (IOException ignored) {
                    // not a readable zip: not a candidate
                }
            }
        }
        return out;
    }

    /**
     * Picks the base to patch: the first candidate whose edited files all hash to the recorded
     * values. Users keep several Eclipse builds side by side, and "the first zip with a marker"
     * would otherwise fail the run while a matching build sat next to it.
     *
     * @return the usable candidate, or null when none matches.
     */
    public Path pickBase(Patch patch) throws IOException {
        List<Path> candidates = findBases(patch);
        if (candidates.isEmpty()) {
            // The window's Install is the user's request to install: fetch the pinned Eclipse
            // archive from its author when shaderpacks/ has none, rather than dead-ending in
            // base-missing. The patch still validates every edited file's hash afterwards.
            if (dryRun) {
                say("eclipse: would fetch " + EclipseFetch.FILE_NAME + " from the author's repository");
                setStatus(BASE_MISSING, "no Eclipse pack in shaderpacks/ (dry run: it would be fetched)");
                return null;
            }
            try {
                say("eclipse: fetching " + EclipseFetch.FILE_NAME + " from the author's repository");
                EclipseFetch.fetch(gameDir, patch.detectFile, patch.detectMarker, new Log() {
                    public void line(String text) {
                        say(text);
                    }
                });
                candidates = findBases(patch);
            } catch (IOException e) {
                say("eclipse: fetch failed - " + e.getMessage());
                setStatus(BASE_MISSING, "no Eclipse pack and the download failed: " + e.getMessage());
                return null;
            }
            if (candidates.isEmpty()) {
                say("no Eclipse pack carries the " + patch.detectMarker + " marker");
                setStatus(BASE_MISSING, "no Eclipse pack in shaderpacks/ after the download");
                return null;
            }
        }
        Path fallback = null;
        String why = "";
        for (Path p : candidates) {
            String problem;
            try {
                problem = patch.check(p);
            } catch (IOException e) {
                problem = "unreadable (" + e.getMessage() + ")";
            }
            if (problem == null) {
                if (candidates.size() > 1) say("chose " + p.getFileName() + " from "
                        + candidates.size() + " Eclipse builds in shaderpacks/");
                return p;
            }
            say("skip " + p.getFileName() + ": " + problem);
            if (fallback == null) {
                fallback = p;
                why = problem;
            }
        }
        baseName = fallback == null ? "" : fallback.getFileName().toString();
        setStatus(BASE_MISMATCH, "no Eclipse zip in shaderpacks/ is the supported build"
                + (fallback == null ? "" : " (" + fallback.getFileName() + ": " + why + ")"));
        return null;
    }

    /** True when a usable Eclipse base is present, without running anything. */
    public boolean hasBase() {
        try {
            Patch patch = Patch.load(dataDir.resolve("patch.txt"));
            for (Path p : findBases(patch)) {
                if (patch.check(p) == null) return true;
            }
        } catch (IOException ignored) {
            // no data or no readable candidate
        }
        return false;
    }

    // ---- state --------------------------------------------------------------

    public static Path stateFile(Path gameDir) {
        return gameDir.resolve("config").resolve(STATE_NAME);
    }

    public static Properties loadState(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    /**
     * Records the outcome so the in-game screen and the next run can see what happened.
     * {@code checkOnLaunch} is the user's toggle and is never overwritten here.
     */
    private void writeState(Path stateFile, Path base, Path target) throws IOException {
        if (dryRun) return;
        Properties state = loadState(stateFile);
        if (!state.containsKey("checkOnLaunch")) state.setProperty("checkOnLaunch", "true");
        state.setProperty("status", status);
        state.setProperty("modVersion", modVersion);
        state.setProperty("message", message);
        state.setProperty("checked", Long.toString(System.currentTimeMillis()));
        state.setProperty("cleanroom", cleanroom == null ? "" : cleanroom);
        state.setProperty("demonica", demonica == null ? "" : demonica);
        state.remove("celeritas");      // an old key from when it was checked; it is inside Demonica
        state.setProperty("prereq", cleanroom == null ? "cleanroom"
                : (demonicaOk ? "ok" : "demonica"));
        state.setProperty("updateAvailable", Boolean.toString(updateAvailable));
        state.setProperty("availableVersion", availableVersion);
        StringBuilder pending = new StringBuilder();
        for (String line : updatePacks) {
            if (pending.length() > 0) pending.append(" | ");
            pending.append(line);
        }
        state.setProperty("updatePacks", pending.toString());
        if (base != null) state.setProperty("base", base.getFileName().toString());
        if (base != null && target != null && Files.exists(target)) {
            state.setProperty("installed", "true");
            state.setProperty("baseHash", sha256(base));
            state.setProperty("outputHash", sha256(target));
            state.setProperty("packVersion", packVersion);
            state.setProperty("pack", PACK_BASE);
            state.setProperty("addon", PACK_ADDON);
            state.setProperty("shader", OUT_SHADER);
        }
        if (INSTALLED.equals(status) || PACKS_MISSING.equals(status) || PACKS_UPDATE.equals(status)) {
            state.setProperty("pendingRestart", needsRestart() ? "true" : "false");
        }
        // The guard's job is to stop a hand-over loop inside one launch, and it has done that by the
        // time a run records a verdict. Leaving it set meant one failed spawn (or a window closed
        // early) could disable the window for every later launch, which then only patched silently.
        state.setProperty("relaunchAttempted", "false");
        if (CURRENT.equals(status)) {
            state.setProperty("pendingRestart", "false");
        }
        Files.createDirectories(stateFile.getParent());
        try (Writer w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
            state.store(w, "Suspicious Speculars");
        }
    }

    /** Appends this run to logs/suspicious-speculars.log; called even after a failure. */
    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        Path logs = gameDir.resolve("logs");
        Files.createDirectories(logs);
        try (Writer w = Files.newBufferedWriter(logs.resolve("suspicious-speculars.log"),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            w.write("--- " + new Date() + "\n" + transcript);
        }
    }

    /**
     * Records a run that threw. Without this the state would keep the previous verdict - a stale
     * "current" can hide a shader that is now broken - and the transcript would miss the failure
     * that matters most. It never throws: the original exception is already on its way out.
     */
    private void failed(Throwable t) {
        try {
            String why = t.getMessage() == null || t.getMessage().trim().isEmpty()
                    ? t.toString() : t.getMessage().trim();
            setStatus(ERROR, why);
            if (!dryRun) {
                Path stateFile = gameDir.resolve("config").resolve(STATE_NAME);
                Properties state = loadState(stateFile);
                if (!state.containsKey("checkOnLaunch")) state.setProperty("checkOnLaunch", "true");
                state.setProperty("status", ERROR);
                state.setProperty("message", why);
                state.setProperty("checked", Long.toString(System.currentTimeMillis()));
                Files.createDirectories(stateFile.getParent());
                try (Writer w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
                    state.store(w, "Suspicious Speculars");
                }
            }
            finish();
        } catch (Throwable ignored) {
            // the exception that got us here is the one worth reporting
        }
    }

    /** True for the statuses that mean the run did not do what was asked. */
    public static boolean isFailure(String status) {
        return ERROR.equals(status) || BASE_MISSING.equals(status)
                || BASE_MISMATCH.equals(status) || PACKS_MISSING.equals(status);
    }

    // ---- mod and file helpers ----------------------------------------------

    static List<Path> jarsIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) out.add(p);
        }
        return out;
    }

    /** A jar whose file name mentions {@code token} (case insensitive), e.g. "demonica". */
    static Path findJar(List<Path> jars, String token) {
        for (Path p : jars) {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains(token)) return p;
        }
        return null;
    }

    /**
     * A jar whose mcmod.info declares {@code modid}, falling back to the file name. Matching the
     * declared id matters: the Cleanroom relauncher jar is called
     * "-cleanroom-relauncher-1.3.0.jar" and Demonica's jar carries a capital, while mod ids never do.
     */
    static Path findMod(List<Path> jars, String modid) throws IOException {
        for (Path p : jars) {
            String name = p.getFileName().toString();
            // launchers disable a mod by prefixing a dot; a leading dash is a different thing
            if (name.startsWith(".")) continue;
            try (ZipFile z = new ZipFile(p.toFile())) {
                ZipEntry e = z.getEntry("mcmod.info");
                if (e == null) continue;
                String text = new String(readAll(z.getInputStream(e)), StandardCharsets.UTF_8);
                String id = metaValue(text, "modid");
                if (id != null && id.equalsIgnoreCase(modid)) return p;
            } catch (IOException ignored) {
                // unreadable jar: not a candidate
            }
        }
        return findJar(jars, modid);
    }

    /** First string value of {@code "key"} in a mcmod.info document. */
    static String metaValue(String text, String key) {
        int i = text.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int q1 = text.indexOf('"', text.indexOf(':', i) + 1);
        if (q1 < 0) return null;
        int q2 = text.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return text.substring(q1 + 1, q2);
    }

    /** Mod version from mcmod.info, falling back to the file name. */
    static String versionOf(Path jar) throws IOException {
        String n = jar.getFileName().toString();
        String fallback = n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (String info : new String[]{"mcmod.info", "META-INF/mcmod.info"}) {
                ZipEntry e = z.getEntry(info);
                if (e == null) continue;
                String text = new String(readAll(z.getInputStream(e)), StandardCharsets.UTF_8);
                String version = metaValue(text, "version");
                if (version != null) return version;
            }
        }
        return fallback;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    public static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    // ---- patch data ---------------------------------------------------------

    /** The recorded edits as a line-oriented text file, so no JSON parser is needed. */
    public static final class Patch {
        String detectFile;
        String detectMarker;
        final List<Edit> edits = new ArrayList<Edit>();
        final List<Pair> whole = new ArrayList<Pair>();
        final List<Pair> add = new ArrayList<Pair>();
        Path dataRoot;

        static final class Pair {
            final String path;
            final String source;

            Pair(String path, String source) {
                this.path = path;
                this.source = source;
            }
        }

        static final class Op {
            final int line;
            final int delete;
            final List<String> insert;

            Op(int line, int delete, List<String> insert) {
                this.line = line;
                this.delete = delete;
                this.insert = insert;
            }
        }

        static final class Edit {
            final String path;
            final String sha;
            final List<Op> ops;

            Edit(String path, String sha, List<Op> ops) {
                this.path = path;
                this.sha = sha;
                this.ops = ops;
            }
        }

        public static Patch load(Path file) throws IOException {
            Patch p = new Patch();
            p.dataRoot = file.toAbsolutePath().getParent();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int i = 0;
            if (lines.isEmpty() || !lines.get(i++).startsWith("PATCH")) {
                throw new IOException("bad patch header");
            }
            while (i < lines.size()) {
                String[] f = lines.get(i++).split(" ", 4);
                if (f[0].equals("DETECT")) {
                    p.detectFile = f[1];
                    p.detectMarker = f[2];
                } else if (f[0].equals("EDIT")) {
                    String path = f[1];
                    String sha = f[2];
                    int count = Integer.parseInt(f[3]);
                    List<Op> ops = new ArrayList<Op>();
                    for (int k = 0; k < count; k++) {
                        String[] o = lines.get(i++).split(" ");
                        int line = Integer.parseInt(o[1]);
                        int del = Integer.parseInt(o[2]);
                        int ins = Integer.parseInt(o[3]);
                        List<String> body = new ArrayList<String>();
                        for (int j = 0; j < ins; j++) body.add(lines.get(i++).substring(1));
                        ops.add(new Op(line, del, body));
                    }
                    p.edits.add(new Edit(path, sha, ops));
                } else if (f[0].equals("WHOLE")) {
                    p.whole.add(new Pair(f[1], f[2]));
                } else if (f[0].equals("ADD")) {
                    p.add.add(new Pair(f[1], f[2]));
                }
            }
            return p;
        }

        /** How many files the patch edits, without applying it. */
        int editCount() {
            return edits.size();
        }

        /** @return number of edited files, or -1 when the base does not match. */
        int apply(Path baseZip, Path out, Patcher owner) throws IOException {
            LinkedHashMap<String, byte[]> files = readZip(baseZip);

            for (Edit e : edits) {
                byte[] raw = files.get(e.path);
                if (raw == null) {
                    owner.say("missing " + e.path);
                    return -1;
                }
                List<String> lines = new ArrayList<String>(Arrays.asList(norm(raw).split("\n", -1)));
                if (!sha256(lines).equals(e.sha)) {
                    owner.say("hash mismatch " + e.path);
                    return -1;
                }
                for (int k = e.ops.size() - 1; k >= 0; k--) {
                    Op op = e.ops.get(k);
                    if (op.line > lines.size()) {
                        owner.say("line out of range " + e.path);
                        return -1;
                    }
                    int to = Math.min(lines.size(), op.line + op.delete);
                    lines.subList(op.line, to).clear();
                    lines.addAll(op.line, op.insert);
                }
                files.put(e.path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            }
            for (Pair p : whole) files.put(p.path, Files.readAllBytes(dataRoot.resolve(p.source)));
            for (Pair p : add) files.put(p.path, Files.readAllBytes(dataRoot.resolve(p.source)));

            // Write beside the target and move it into place. A failure here - disk full, a locked
            // file - must leave the shader that already works untouched rather than a truncated zip
            // the game would then have to load.
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            try {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                    for (Map.Entry<String, byte[]> e : files.entrySet()) {
                        zos.putNextEntry(new ZipEntry(e.getKey()));
                        zos.write(e.getValue());
                        zos.closeEntry();
                    }
                }
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a locked or read-only leftover stays; the run's own state now says error
                }
            }
            return edits.size();
        }

        /**
         * Cheap "is this the build the patch was made against" test: every edited file must be
         * present and hash to its recorded value. Used to pick between several Eclipse zips in
         * shaderpacks/ before any work is done.
         *
         * @return null when the zip is usable, otherwise the first reason it is not.
         */
        String check(Path baseZip) throws IOException {
            LinkedHashMap<String, byte[]> files = readZip(baseZip);
            for (Edit e : edits) {
                byte[] raw = files.get(e.path);
                if (raw == null) return "missing " + e.path;
                List<String> lines = Arrays.asList(norm(raw).split("\n", -1));
                if (!sha256(lines).equals(e.sha)) return "different " + e.path;
            }
            return null;
        }

        /**
         * Reads a shader zip into memory, flattening one archive top folder and dropping repository
         * metadata (README, .github, ISSUE_TEMPLATE) so a GitHub source archive and a built pack with
         * the same shader content compare equal.
         */
        private static LinkedHashMap<String, byte[]> readZip(Path zip) throws IOException {
            LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
            String prefix = null;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    int slash = e.getName().indexOf('/');
                    String head = slash > 0 ? e.getName().substring(0, slash) : "";
                    if (prefix == null) prefix = head;
                    else if (!prefix.equals(head)) {
                        prefix = "";
                        break;
                    }
                }
                String strip = prefix == null || prefix.isEmpty() ? "" : prefix + "/";
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (!strip.isEmpty() && name.startsWith(strip)) name = name.substring(strip.length());
                    if (name.equals("README.md") || name.startsWith(".github/")
                            || name.startsWith("ISSUE_TEMPLATE/")) continue;
                    files.put(name, readAll(z.getInputStream(e)));
                }
            }
            return files;
        }

        static String norm(byte[] raw) {
            return new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        static String sha256(List<String> lines) throws IOException {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }
    }
}
