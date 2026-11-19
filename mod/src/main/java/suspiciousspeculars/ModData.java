package suspiciousspeculars;

import org.apache.logging.log4j.Logger;
import suspiciousspeculars.patch.Patcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks the patch data that travels inside the mod jar.
 *
 * 3.5 MB of patch data (500 files) is carried as one zip resource plus a version line. The first
 * run, or the first run after a mod update, extracts it to {@code config/suspicious-speculars/patch/}
 * so the patcher can work with ordinary files - which also means a user can inspect exactly what
 * was applied without opening the jar.
 */
final class ModData {

    private static final String BUNDLE = "/assets/suspiciousspeculars/patch-bundle.zip";
    private static final String VERSION = "/assets/suspiciousspeculars/patch-bundle.version";
    private static final String CURRENT = ".bundle-version";

    private ModData() {
    }

    static void ensure(Path dataDir, Logger log) throws IOException {
        String want = readResource(VERSION).trim();
        Path marker = dataDir.resolve(CURRENT);
        if (Files.isDirectory(dataDir) && Files.exists(marker)) {
            String have = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            if (have.equals(want)) {
                log.info("patch data up to date (" + want.split(" ")[0].substring(0, 8) + ")");
                return;
            }
        }

        Files.createDirectories(dataDir);
        log.info("unpacking patch data into " + dataDir);
        int files = 0;
        try (InputStream in = ModData.class.getResourceAsStream(BUNDLE)) {
            if (in == null) throw new IOException("mod jar has no " + BUNDLE);
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path target = dataDir.resolve(e.getName()).normalize();
                if (!target.startsWith(dataDir)) throw new IOException("bad entry " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                files++;
            }
        }
        Files.write(marker, want.getBytes(StandardCharsets.UTF_8));
        log.info("unpacked " + files + " patch files");
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
}
