package suspiciousspeculars;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Exercises {@link ModData#ensure} against a stand-in repository.
 *
 * Lives in the mod's package because ModData is package-private: the harness must be able to prove
 * that a repository patch is installed, that a patch built for another Eclipse commit is refused,
 * and that an unreachable repository falls back to the copy inside the jar.
 *
 * Prints {@code key=value} lines the scenario script reads.
 */
public final class PatchDataProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Logger log = LogManager.getLogger("patch-data-probe");
        try {
            boolean allowRemote = args.length < 2 || !"off".equalsIgnoreCase(args[1]);
            ModData.ensure(dir, log, allowRemote);
        } catch (Exception e) {
            System.out.println("error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println("marker=" + read(dir.resolve(".bundle-version")));
        System.out.println("patch=" + Files.exists(dir.resolve("patch.txt")));
        System.out.println("sentinel=" + Files.exists(dir.resolve("sentinel.txt")));
        System.out.println("download=" + Files.exists(dir.resolveSibling("patch-download.zip")));
        // a no-op must not rewrite the data: the timestamp is the cheap proof
        System.out.println("stamp=" + (Files.exists(dir.resolve("patch.txt"))
                ? Files.getLastModifiedTime(dir.resolve("patch.txt")).toMillis()
                : 0L));
    }

    private static String read(Path file) {
        try {
            return Files.exists(file)
                    ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
                    : "";
        } catch (Exception e) {
            return "";
        }
    }
}
