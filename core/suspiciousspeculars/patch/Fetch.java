package suspiciousspeculars.patch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Fetches a file to disk, over http(s) or from a local path, and never leaves a half file behind.
 *
 * The download goes to {@code <name>.part} and is renamed only when the whole body arrived, so a
 * droppped connection cannot leave something that looks like a pack. Callers verify the SHA-256
 * afterwards; this class only refuses short reads and non-200 answers.
 */
public final class Fetch {

    private Fetch() {
    }

    /**
     * A fetch that did not produce the file. {@code status} is the HTTP code when there was one
     * (404 for "the file is not at that URL", 403, 500...), or 0 for a local path that is missing.
     * The message is written for a log or a chat line: code, meaning and URL.
     */
    public static final class Failure extends IOException {
        private final int status;
        private final String url;

        Failure(int status, String url) {
            super(describe(status, url));
            this.status = status;
            this.url = url;
        }

        public int status() {
            return status;
        }

        public String url() {
            return url;
        }

        public boolean notFound() {
            return status == 404;
        }

        private static String describe(int status, String url) {
            if (status == 404) return "404 not found: " + url;
            if (status == 403) return "403 forbidden: " + url;
            if (status > 0) return "HTTP " + status + ": " + url;
            return "no such file: " + url;
        }
    }

    /**
     * Reads a small text resource into memory (the pack manifest), with the same http/file rules
     * as {@link #download} and a hard size cap so a wrong URL cannot stream a video into the heap.
     */
    public static byte[] read(String url, int maxBytes) throws IOException {
        return read(url, maxBytes, 8000);
    }

    /**
     * Same, with a caller-chosen timeout. A launch path checks a small manifest before the game does
     * anything, so it cannot afford to sit on a dead connection as long as a pack download can.
     */
    public static byte[] read(String url, int maxBytes, int timeoutMs) throws IOException {
        if (url.startsWith("file:") || !url.contains("://")) {
            Path source = url.startsWith("file:")
                    ? java.nio.file.Paths.get(java.net.URI.create(url))
                    : java.nio.file.Paths.get(url);
            if (!Files.exists(source)) throw new Failure(0, url);
            byte[] all = Files.readAllBytes(source);
            if (all.length > maxBytes) throw new IOException("too large: " + url);
            return all;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "suspicious-speculars");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Failure(code, url);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IOException("too large: " + url);
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /**
     * @param url     http(s) URL, or a file: URL / absolute path for local sources
     * @param dest    file to write; parent directories are created
     * @param log     progress sink
     */
    public static void download(String url, Path dest, Patcher.Log log) throws IOException {
        Files.createDirectories(dest.getParent());
        Path temp = dest.resolveSibling(dest.getFileName() + ".part");

        if (url.startsWith("file:") || !url.contains("://")) {
            Path source = url.startsWith("file:")
                    ? java.nio.file.Paths.get(java.net.URI.create(url))
                    : java.nio.file.Paths.get(url);
            if (!Files.exists(source)) throw new Failure(0, url);
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            log.line("fetched " + dest.getFileName() + " (" + Files.size(dest) + " bytes, local)");
            return;
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "suspicious-speculars");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Failure(code, url);
            }
            long total = c.getContentLengthLong();
            long read = 0;
            long nextReport = 4L * 1024 * 1024;
            try (InputStream in = c.getInputStream();
                 OutputStream out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    read += n;
                    if (read >= nextReport) {
                        nextReport += 4L * 1024 * 1024;
                        log.line("  " + (read / (1024 * 1024)) + " MB...");
                    }
                }
            }
            if (total > 0 && read != total) {
                throw new IOException("incomplete download of " + url + ": "
                        + read + " of " + total + " bytes");
            }
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            log.line("fetched " + dest.getFileName() + " (" + (read / 1024) + " KB)");
        } finally {
            Files.deleteIfExists(temp);
            c.disconnect();
        }
    }
}
