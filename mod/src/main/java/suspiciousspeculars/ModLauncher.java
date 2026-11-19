package suspiciousspeculars;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * The launcher window travels inside this jar and is unpacked beside the patch data.
 *
 * Shipping it as a second thing to install was the reason the window could not be reached from the
 * game at all: the mod looked for a launcher jar in mods/ and config/ and found neither. One jar is
 * now enough - the launcher is a resource and lands in {@code config/suspicious-speculars/launcher.jar},
 * which is where {@link SuspiciousCorePlugin} already looks before it decides to hand over.
 *
 * The unpack happens before the plan is computed, on the first launch and again whenever the bundled
 * build differs, so an updated mod never runs an older window. The version marker is the jar's
 * SHA-256: cheap to compare, and it changes on every launcher build.
 */
final class ModLauncher {

    private static final String JAR = "/assets/suspiciousspeculars/launcher.jar";
    private static final String VERSION = "/assets/suspiciousspeculars/launcher.version";

    private ModLauncher() {
    }

    /**
     * Unpacks the bundled launcher when it is missing or out of date.
     *
     * @return the launcher jar to run, or null when the jar carries none and none was unpacked before
     */
    static Path ensure(Path gameDir, Consumer<String> log) {
        Path dir = gameDir.resolve("config").resolve("suspicious-speculars");
        Path target = dir.resolve("launcher.jar");
        Path marker = dir.resolve("launcher.version");

        String want = resourceText(VERSION);
        if (want == null) {
            // a launcher without a marker: never overwrite a jar a previous version unpacked
            return Files.isRegularFile(target) ? target : null;
        }
        want = want.trim();

        try {
            if (Files.isRegularFile(target) && Files.isRegularFile(marker)
                    && want.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim())) {
                return target;
            }
            byte[] jar = resource(JAR);
            if (jar == null) {
                return Files.isRegularFile(target) ? target : null;
            }
            Files.createDirectories(dir);
            Path temp = dir.resolve("launcher.jar.tmp");
            Files.write(temp, jar);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.write(marker, want.getBytes(StandardCharsets.UTF_8));
            if (log != null) log.accept("launcher: unpacked " + shortHash(want) + " beside the patch data");
            return target;
        } catch (IOException e) {
            if (log != null) log.accept("launcher: could not unpack it (" + e + ")");
            return Files.isRegularFile(target) ? target : null;
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = ModLauncher.class.getResourceAsStream(name)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String resourceText(String name) {
        byte[] bytes = resource(name);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String shortHash(String hex) {
        return hex.length() > 12 ? hex.substring(0, 12) : hex;
    }
}
