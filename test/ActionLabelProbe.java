package suspiciousspeculars.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The window's main button must say what the state means: Install when nothing is on disk, Reinstall
 * when something is, Update only when the host offers a newer pack release.
 *
 * <p>Regression guard: "current" - the status a dormant install records - was missing from the
 * installed set, so a window opened on a complete instance read "Install", as if the shader were
 * absent. The label is derived in {@code Launcher.isInstalledStatus}, and this runs it against a
 * throwaway state file.</p>
 *
 * <pre>
 * javac -cp suspicious-speculars-launcher.jar -d out ActionLabelProbe.java
 * java  -cp "suspicious-speculars-launcher.jar;out" suspiciousspeculars.launcher.ActionLabelProbe &lt;gameDir&gt; &lt;dataDir&gt;
 * </pre>
 */
public final class ActionLabelProbe {

    public static void main(String[] args) throws Exception {
        Path game = Paths.get(args[0]);
        Path data = Paths.get(args[1]);

        write(game, null);
        System.out.println(Launcher.actionLabel(game, data));

        write(game, "status=current");
        System.out.println(Launcher.actionLabel(game, data));

        write(game, "status=installed\n"
                + "updateAvailable=true\n"
                + "availableVersion=2026-11-19.1\n"
                + "packVersion=2026-11-18.1\n");
        System.out.println(Launcher.actionLabel(game, data));

        write(game, "status=installed\n"
                + "updateAvailable=true\n"
                + "availableVersion=2026-11-19.1\n"
                + "packVersion=2026-11-19.1\n");
        System.out.println(Launcher.actionLabel(game, data));
    }

    private static void write(Path game, String state) throws IOException {
        Path file = game.resolve("config").resolve("suspicious-speculars.properties");
        Files.createDirectories(file.getParent());
        if (state == null) {
            Files.deleteIfExists(file);
        } else {
            Files.write(file, state.getBytes(StandardCharsets.UTF_8));
        }
    }
}
