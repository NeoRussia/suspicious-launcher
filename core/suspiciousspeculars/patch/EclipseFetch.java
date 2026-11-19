package suspiciousspeculars.patch;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the pinned Eclipse source archive on the user's request.
 *
 * Eclipse is Merlin1809's pack under its own licence, so it is never bundled and never mirrored
 * here: this asks GitHub for the author's own archive of the commit the patch was built against,
 * exactly as a launcher would, and keeps nothing else. The bytes are still checked afterwards -
 * the patcher verifies every file it edits against its recorded SHA-256 and refuses the archive
 * if any of them differ.
 */
public final class EclipseFetch {

    public static final String COMMIT = "e89e6c6";
    public static final String URL_BASE = "https://codeload.github.com/Merlin1809/Eclipse-Shader/zip/";
    public static final String FILE_NAME = "Eclipse-" + COMMIT + ".zip";

    private EclipseFetch() {
    }

    /** @return the archive in {@code shaderpacks/}, downloading it only when it is not usable yet. */
    public static Path fetch(Path gameDir, String detectFile, String detectMarker, Patcher.Log log)
            throws IOException {
        Path dir = gameDir.resolve("shaderpacks");
        Files.createDirectories(dir);
        Path out = dir.resolve(FILE_NAME);
        if (Files.exists(out) && usable(out, detectFile, detectMarker)) {
            log.line("eclipse: already present and recognised (" + FILE_NAME + ")");
            return out;
        }
        if (Files.exists(out)) {
            log.line("eclipse: " + FILE_NAME + " is not a usable Eclipse pack, downloading it again");
        }
        Fetch.download(URL_BASE + COMMIT, out, log);
        if (!usable(out, detectFile, detectMarker)) {
            Files.deleteIfExists(out);
            throw new IOException("downloaded archive is not an Eclipse pack");
        }
        return out;
    }

    /**
     * A quick "does this look like the pack the patch expects" test: the marker file is present
     * (at the root, or inside a single top folder for source archives) and carries the marker text.
     */
    static boolean usable(Path zip, String detectFile, String detectMarker) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(detectFile);
            if (e == null) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry cand = en.nextElement();
                    if (cand.getName().endsWith("/" + detectFile)) {
                        e = cand;
                        break;
                    }
                }
            }
            if (e == null) return false;
            String text = new String(Patcher.readAll(z.getInputStream(e)), StandardCharsets.UTF_8);
            return text.contains(detectMarker);
        } catch (IOException notAZip) {
            return false;
        }
    }
}
