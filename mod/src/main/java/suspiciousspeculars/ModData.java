package suspiciousspeculars;

import org.apache.logging.log4j.Logger;
import suspiciousspeculars.patch.EclipseFetch;
import suspiciousspeculars.patch.Fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the patch data on disk current.
 *
 * The data is owned by the shader repository and published there as {@code patch/patch.txt} plus a
 * content-addressed {@code patch/patch-<hash>.zip}, so a shader change reaches players without a
 * launcher release: this checks that manifest on every start, and downloads only when the identity
 * differs from what is installed. The copy inside the mod jar is the fallback, used when the
 * repository cannot be reached and nothing usable is installed.
 *
 * Installed data is never traded for the jar's snapshot: an offline launch keeps the newer patch it
 * already has, and a manifest this build cannot honour (another Eclipse commit, a newer patch format)
 * is refused before anything is unpacked. That also keeps downgrades sane - an older launcher keeps
 * its own bundled data and repatches to it, without ever seeing the published manifest.
 *
 * The data is unpacked to ordinary files under {@code config/suspicious-speculars/patch/} so the
 * patcher can work with them and a user can inspect exactly what was applied.
 */
final class ModData {

    private static final String BUNDLE = "/assets/suspiciousspeculars/patch-bundle.zip";
    private static final String VERSION = "/assets/suspiciousspeculars/patch-bundle.version";
    private static final String CURRENT = ".bundle-version";
    /** Provenance, read by the coremod: repository data must survive a newer jar, bundled data need not. */
    private static final String SOURCE = ".bundle-source";

    /** Published by bundle.py next to the bundle it names; relative entries resolve against it. */
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/NeoRussia/suspicious-speculars/main/patch/patch.txt";

    /**
     * The newest patch definition this build can apply. bundle.py writes {@code REQUIRES 1}; a future
     * data change that needs newer parsing bumps it, and this build then keeps what it has instead of
     * fetching something it would half understand.
     */
    private static final int FORMAT = 2; // 2 adds COPY: files built from a stock file plus our lines

    /** A patch bundle is 0.3 MB today; anything near this cap is not one. */
    private static final long MAX_BUNDLE = 32L * 1024 * 1024;
    /** And the bytes it may expand to: the zip is remote data, so it gets a ceiling too. */
    private static final long MAX_UNPACKED = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 8192;

    private ModData() {
    }

    /**
     * @param allowRemote false when the player turned the launch check off: the data on disk is then
     *                    left exactly as it is, and the network is not touched. A missing directory
     *                    is still unpacked from the jar, because the patcher cannot run without it.
     */
    static void ensure(Path dataDir, Logger log, boolean allowRemote) throws IOException {
        Path parent = dataDir.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        // Two launches of the same instance would otherwise swap the directory under each other.
        Path lockFile = dataDir.resolveSibling("patch.lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = null;
            for (int attempt = 0; attempt < 30 && held == null; attempt++) {
                try {
                    held = channel.tryLock();
                } catch (IOException | RuntimeException ignored) {
                    // a lock we cannot take is the same as one someone else holds
                }
                if (held == null) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (held == null) {
                String installed = marker(dataDir);
                if (Files.exists(dataDir.resolve("patch.txt")) && !installed.isEmpty()) {
                    log.info("patch data: another launch is preparing it, keeping "
                            + shortHash(installed));
                    return;
                }
                install(dataDir, null, readResource(VERSION).trim(), log);
                return;
            }
            try {
                prepare(dataDir, log, allowRemote);
            } finally {
                held.release();
            }
        }
    }

    private static void prepare(Path dataDir, Logger log, boolean allowRemote) throws IOException {
        String bundled = readResource(VERSION).trim();
        String installed = marker(dataDir);
        boolean usable = Files.exists(dataDir.resolve("patch.txt")) && !installed.isEmpty();

        Remote remote = null;
        if (allowRemote) {
            try {
                remote = readRemote(dataDir, log);
            } catch (IOException e) {
                log.warn("patch data: repository manifest unavailable (" + e.getMessage()
                        + "), keeping what is installed");
            }
        } else {
            log.info("patch data: launch check is off, using what is installed");
        }

        if (remote != null && !remote.base.isEmpty() && !remote.base.equals(EclipseFetch.COMMIT)) {
            // The manifest is newer than this build, but it edits a different Eclipse commit.
            // Applying it would fail on the first stock hash; say so up front.
            log.warn("patch data: the repository serves a patch for Eclipse " + remote.base
                    + ", this build patches " + EclipseFetch.COMMIT + "; keeping what is installed");
            remote = null;
        }
        if (remote != null && remote.requires > FORMAT) {
            log.warn("patch data: the repository patch needs format " + remote.requires
                    + ", this build reads " + FORMAT + "; keeping what is installed");
            remote = null;
        }

        if (remote != null) {
            if (usable && remote.version.equals(installed)) {
                log.info("patch data up to date (" + shortHash(installed) + ")");
                return;
            }
            if (remote.version.equals(bundled)) {
                // The repository names exactly what this jar carries; unpack it and be done.
                install(dataDir, null, bundled, log);
                return;
            }
            try {
                install(dataDir, download(remote, dataDir, log), remote.version, log);
                return;
            } catch (IOException e) {
                log.warn("patch data: could not fetch " + remote.name + " (" + e.getMessage()
                        + "), keeping what is installed");
            }
        }

        // Offline, refused, or failed mid-download: never trade working data for the jar's snapshot.
        if (usable) {
            log.info("patch data: using the installed patch (" + shortHash(installed) + ")");
            return;
        }
        install(dataDir, null, bundled, log);
    }

    // ---------------------------------------------------------------- remote manifest

    /** What the repository says is current. */
    private static final class Remote {
        String version = "";
        String base = "";
        String name = "";
        String sha = "";
        String url = "";
        int requires = 0;
    }

    /**
     * Where the patch manifest is read from: the published one by default, but a {@code patch.url}
     * file beside the patch data or the {@code suspiciousspeculars.patchUrl} system property wins,
     * so a fork, a mirror or a test can point this at its own copy.
     */
    private static String manifestUrl(Path dataDir) {
        try {
            Path file = dataDir.resolveSibling("patch.url");
            if (Files.exists(file)) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty() && !text.startsWith("#")) return text;
            }
        } catch (IOException ignored) {
            // fall through to the constant
        }
        String override = System.getProperty("suspiciousspeculars.patchUrl");
        return override != null && !override.trim().isEmpty() ? override.trim() : MANIFEST_URL;
    }

    private static Remote readRemote(Path dataDir, Logger log) throws IOException {
        String url = manifestUrl(dataDir);
        // short: this runs on the launch path, before the game has drawn anything
        byte[] raw = Fetch.read(url, 16 * 1024, 4000);
        Remote r = parseManifest(new String(raw, StandardCharsets.UTF_8), url);
        if (r.version.isEmpty()) throw new IOException("no VERSION line in " + url);
        if (r.name.isEmpty()) throw new IOException("no PATCH line in " + url);
        log.info("patch data: repository says " + shortHash(r.version)
                + (r.base.isEmpty() ? "" : " for Eclipse " + r.base));
        return r;
    }

    private static Remote parseManifest(String text, String base) {
        Remote r = new Remote();
        for (String line : text.split("\n")) {
            String row = line.trim();
            if (row.isEmpty() || row.startsWith("#")) continue;
            String[] f = row.split("\t");
            if (f[0].equalsIgnoreCase("VERSION") && f.length >= 2) {
                r.version = f[1].trim();
            } else if (f[0].equalsIgnoreCase("BASE") && f.length >= 2) {
                r.base = f[1].trim().toLowerCase(Locale.ROOT);
            } else if (f[0].equalsIgnoreCase("REQUIRES") && f.length >= 2) {
                try {
                    r.requires = Integer.parseInt(f[1].trim());
                } catch (NumberFormatException ignored) {
                    r.requires = Integer.MAX_VALUE; // unreadable means "do not touch it"
                }
            } else if (f[0].equalsIgnoreCase("PATCH") && f.length >= 4) {
                r.name = f[1].trim();
                r.sha = f[2].trim().toLowerCase(Locale.ROOT);
                r.url = resolve(base, f[3].trim());
            }
        }
        return r;
    }

    /** A relative entry resolves against the manifest's own location, so a fork or a folder works. */
    private static String resolve(String base, String ref) {
        if (ref.contains("://") || ref.startsWith("file:")) return ref;
        if (base.isEmpty()) return ref;
        try {
            if (base.contains("://")) return new URL(new URL(base), ref).toString();
            Path dir = java.nio.file.Paths.get(base).toAbsolutePath().getParent();
            return dir == null ? ref : dir.resolve(ref).toString();
        } catch (IOException e) {
            return ref;
        }
    }

    /** Downloads the bundle and proves it is the one the manifest named before anything is unpacked. */
    private static Path download(Remote r, Path dataDir, Logger log) throws IOException {
        Path temp = dataDir.resolveSibling("patch-download.zip");
        Files.deleteIfExists(temp);
        try {
            Fetch.download(r.url, temp, new FetchLog(log));
            long size = Files.size(temp);
            if (size > MAX_BUNDLE) throw new IOException("bundle is " + size + " bytes, not a patch");
            String actual = sha256(temp);
            String declared = r.sha.isEmpty() ? r.version.split(" ")[0] : r.sha;
            if (!actual.equalsIgnoreCase(declared)) {
                throw new IOException("downloaded bundle is " + actual.substring(0, 8)
                        + ", manifest says " + declared.substring(0, 8));
            }
            // The marker carries the bundle hash; a manifest that disagrees with itself would make
            // the next launch download the same bytes again forever.
            String versionHash = r.version.split(" ")[0];
            if (!versionHash.equalsIgnoreCase(actual)) {
                throw new IOException("VERSION and PATCH name different bundles");
            }
            return temp;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    // ---------------------------------------------------------------- installing

    private static void install(Path dataDir, Path zip, String version, Logger log) throws IOException {
        Path tmp = dataDir.resolveSibling(dataDir.getFileName() + ".new");
        Path old = dataDir.resolveSibling(dataDir.getFileName() + ".old");
        deleteTree(tmp);
        deleteTree(old);
        Files.createDirectories(tmp);

        // A local manifest override lives inside the data directory, so a wholesale swap would
        // silently drop it. Read it first and put it back afterwards.
        Path override = dataDir.resolve("manifest.url");
        String overrideText = Files.isRegularFile(override)
                ? new String(Files.readAllBytes(override), StandardCharsets.UTF_8)
                : null;

        log.info(zip == null
                ? "unpacking patch data into " + dataDir + " (bundled)"
                : "unpacking patch data into " + dataDir + " (from the repository)");
        int files;
        try {
            if (zip == null) {
                try (InputStream in = ModData.class.getResourceAsStream(BUNDLE)) {
                    if (in == null) throw new IOException("mod jar has no " + BUNDLE);
                    files = extract(in, tmp);
                }
            } else {
                try (InputStream in = Files.newInputStream(zip)) {
                    files = extract(in, tmp);
                }
            }
            if (!Files.exists(tmp.resolve("patch.txt"))) {
                throw new IOException("patch data has no patch.txt");
            }
        } catch (IOException e) {
            deleteTree(tmp);
            if (zip != null) Files.deleteIfExists(zip);
            throw e;
        }

        // Swap whole directories rather than unpacking over the live one: a failed download or a
        // half-written entry can then never leave a mix of two patch versions to apply.
        if (Files.exists(dataDir)) Files.move(dataDir, old);
        try {
            Files.move(tmp, dataDir);
        } catch (IOException e) {
            if (Files.exists(old)) Files.move(old, dataDir);
            deleteTree(tmp);
            throw e;
        }
        deleteTree(old);
        Files.write(dataDir.resolve(CURRENT), version.getBytes(StandardCharsets.UTF_8));
        Files.write(dataDir.resolve(SOURCE),
                (zip == null ? "bundled" : "repository").getBytes(StandardCharsets.UTF_8));
        if (overrideText != null) {
            Files.write(dataDir.resolve("manifest.url"), overrideText.getBytes(StandardCharsets.UTF_8));
        }
        if (zip != null) Files.deleteIfExists(zip);
        log.info("unpacked " + files + " patch files (" + shortHash(version) + ")");
    }

    private static int extract(InputStream in, Path dir) throws IOException {
        int files = 0;
        int entries = 0;
        long unpacked = 0;
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (++entries > MAX_ENTRIES) throw new IOException("patch data has too many entries");
            Path target = dir.resolve(e.getName()).normalize();
            if (!target.startsWith(dir)) throw new IOException("bad entry " + e.getName());
            if (e.isDirectory()) {
                Files.createDirectories(target);
                continue;
            }
            Files.createDirectories(target.getParent());
            unpacked += Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            if (unpacked > MAX_UNPACKED) {
                throw new IOException("patch data expands past " + MAX_UNPACKED + " bytes");
            }
            files++;
        }
        return files;
    }

    // ---------------------------------------------------------------- helpers

    private static String marker(Path dataDir) {
        Path marker = dataDir.resolve(CURRENT);
        try {
            return Files.exists(marker)
                    ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim()
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = ModData.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("mod jar has no " + name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 12];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String shortHash(String version) {
        String head = version.split(" ")[0];
        return head.length() > 8 ? head.substring(0, 8) : head;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) deleteTree(child);
                else Files.deleteIfExists(child);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** The download log the patcher expects, backed by the mod's logger. */
    private static final class FetchLog implements suspiciousspeculars.patch.Patcher.Log {
        private final Logger log;

        FetchLog(Logger log) {
            this.log = log;
        }

        @Override
        public void line(String message) {
            log.info(message);
        }
    }
}
