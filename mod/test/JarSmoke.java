import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Smoke test for the built mod jar, without Minecraft.
 *
 * Loads the classes from the jar, runs the same data extraction the mod runs at pre-init
 * (ModData.ensure), then the same patcher (Patcher) against a scratch instance, and prints the
 * resulting state so the run can be compared with the command-line front end.
 *
 *   java -cp "suspicious-speculars-0.1.0.jar;log4j-api.jar;log4j-core.jar;." JarSmoke <instance dir>
 */
public final class JarSmoke {

    public static void main(String[] args) throws Exception {
        Path instance = Paths.get(args[0]).toAbsolutePath();
        Path config = instance.resolve("config");
        Path dataDir = config.resolve("suspicious-speculars").resolve("patch");

        org.apache.logging.log4j.Logger logger =
                org.apache.logging.log4j.LogManager.getLogger("JarSmoke");

        Class<?> modData = Class.forName("suspiciousspeculars.ModData");
        Method ensure = modData.getDeclaredMethod("ensure", Path.class,
                org.apache.logging.log4j.Logger.class);
        ensure.setAccessible(true);
        ensure.invoke(null, dataDir, logger);

        long unpacked = Files.walk(dataDir).filter(Files::isRegularFile).count();
        System.out.println("extracted files: " + unpacked);
        System.out.println("patch.txt present: " + Files.exists(dataDir.resolve("patch.txt")));
        System.out.println("packs present: " + Files.exists(
                dataDir.resolve("packs").resolve("Suspicious Speculars.zip")));

        Class<?> patcherClass = Class.forName("suspiciousspeculars.patch.Patcher");
        Object patcher = patcherClass
                .getConstructor(Path.class, Path.class).newInstance(instance, dataDir);
        Method force = patcherClass.getMethod("force", boolean.class);
        force.invoke(patcher, Boolean.TRUE);          // ignore recorded state for the test
        Method run = patcherClass.getMethod("run");
        run.invoke(patcher);
        patcherClass.getMethod("finish").invoke(patcher);

        System.out.println("status: " + patcherClass.getMethod("status").invoke(patcher));
        System.out.println("message: " + patcherClass.getMethod("message").invoke(patcher));
        System.out.println("edited files: " + patcherClass.getMethod("editedFiles").invoke(patcher));

        Properties state = (Properties) patcherClass
                .getMethod("loadState", Path.class)
                .invoke(null, patcherClass.getMethod("stateFile", Path.class).invoke(null, instance));
        System.out.println("state.status=" + state.getProperty("status")
                + "  pendingRestart=" + state.getProperty("pendingRestart")
                + "  demonica=" + state.getProperty("demonica")
                + "  cleanroom=" + state.getProperty("cleanroom"));
        System.out.println("shader: " + Files.exists(instance.resolve("shaderpacks")
                .resolve("Eclipse-SUSY.zip")));
    }
}
