package suspiciousspeculars;

import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Exercises the early half's data rule ({@code SuspiciousCorePlugin.ensureData}) through reflection.
 *
 * The rule that matters: data fetched from the shader repository is newer than the jar and must not be
 * overwritten before mods load, while bundled data may be replaced by a newer jar - and a replacement
 * must clear the old files instead of merging two bundles in one directory.
 *
 * Prints {@code key=value} lines the scenario script reads.
 */
public final class CoreDataProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        File jar = new File(args[1]);

        Files.createDirectories(dir);
        Files.write(dir.resolve(".bundle-version"), "remote-version 999".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(".bundle-source"), "repository".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("keep.txt"), "x".getBytes(StandardCharsets.UTF_8));
        call(jar, dir);
        System.out.println("marker1=" + read(dir.resolve(".bundle-version")));
        System.out.println("source1=" + read(dir.resolve(".bundle-source")));
        System.out.println("keep1=" + Files.exists(dir.resolve("keep.txt")));

        // No provenance recorded (an install from before the marker existed): the jar's copy wins,
        // and the directory is replaced rather than merged.
        Files.deleteIfExists(dir.resolve(".bundle-source"));
        call(jar, dir);
        System.out.println("marker2=" + read(dir.resolve(".bundle-version")));
        System.out.println("source2=" + read(dir.resolve(".bundle-source")));
        System.out.println("keep2=" + Files.exists(dir.resolve("keep.txt")));
    }

    private static void call(File jar, Path dir) throws Exception {
        Class<?> type = Class.forName("suspiciousspeculars.SuspiciousCorePlugin");
        Method ensure = type.getDeclaredMethod("ensureData", File.class, File.class,
                org.apache.logging.log4j.Logger.class);
        ensure.setAccessible(true);
        ensure.invoke(null, jar, dir.toFile(), LogManager.getLogger("core-data-probe"));
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
