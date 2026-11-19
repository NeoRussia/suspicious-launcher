package suspiciousspeculars.cli;

import suspiciousspeculars.patch.Patcher;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line front end for the patcher, for use as a launcher pre-launch command.
 *
 *   java -jar suspicious-speculars-pre.jar --game-dir <path to .minecraft> [--data <dir>]
 *                                          [--force] [--dry-run] [--strict]
 *
 * --check reports a resource pack update without installing it (the mod does this at startup).
 *
 * Exit codes: 0 unless the command line is wrong (2). Prism aborts the whole launch on a non-zero
 * exit, so "no Eclipse pack found" and "unsupported version" are reported through the state file
 * and the log instead of blocking the game. --strict turns them into exit 1, for scripts and CI.
 *
 * The patcher itself lives in {@code suspiciousspeculars.patch.Patcher}, which the mod also calls
 * from pre-init, so both front ends behave identically.
 */
public final class PreLauncher {

    private static final String USAGE =
            "usage: --game-dir <path to .minecraft> [--data <dir>] [--force] [--dry-run] [--check] [--strict]";

    public static void main(String[] args) {
        Path game = null;
        Path data = null;
        boolean force = false;
        boolean dry = false;
        boolean strict = false;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--game-dir") && i + 1 < args.length) {
                game = Paths.get(args[++i]);
            } else if (args[i].equals("--data") && i + 1 < args.length) {
                data = Paths.get(args[++i]);
            } else if (args[i].equals("--force")) {
                force = true;
            } else if (args[i].equals("--dry-run")) {
                dry = true;
            } else if (args[i].equals("--strict")) {
                strict = true;
            } else if (args[i].equals("--check")) {
                check = true;
            } else {
                System.err.println("unknown argument " + args[i]);
                System.err.println(USAGE);
                System.exit(2);
            }
        }
        if (game == null) {
            System.err.println(USAGE);
            System.exit(2);
        }

        Patcher patcher = null;
        try {
            Path dataDir = data;
            if (dataDir == null) {
                // default: a "patch" folder next to the jar
                dataDir = Paths.get(PreLauncher.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).getParent().resolve("patch");
            }
            patcher = new Patcher(game, dataDir).version("pre-launch").force(force).dryRun(dry)
                    .packs(check ? Patcher.PackMode.CHECK : Patcher.PackMode.INSTALL)
                    .log(new Patcher.Log() {
                public void line(String text) {
                    System.out.println(text);
                }
            });
            patcher.run();
            patcher.finish();
        } catch (Throwable t) {
            // Nothing here may abort the launch: report and let the game start on what is on disk.
            System.err.println("Suspicious Speculars pre-launch failed: " + t);
            if (strict) System.exit(1);
            return;
        }

        System.exit(exitCode(patcher, strict));
    }

    static int exitCode(Patcher patcher, boolean strict) {
        if (!strict) return 0;
        boolean fine = Patcher.CURRENT.equals(patcher.status())
                || Patcher.INSTALLED.equals(patcher.status());
        return fine && patcher.message().isEmpty() ? 0 : 1;
    }
}
