/**
 * Prints the build version the mod reports for itself.
 *
 * Run with another mod's jar ahead of ours on the classpath: every mod ships {@code /mcmod.info},
 * so a classpath-wide lookup returned that jar's version instead of ours (Demonica's 0.9.1 landed in
 * the state file). Kept as a test, because the bug is invisible until something reads the state.
 *
 *   java -cp "<other mod>;<ours>;<log4j>;<classes>" VersionProbe
 */
public final class VersionProbe {

    public static void main(String[] args) throws Exception {
        Class<?> mod = Class.forName("suspiciousspeculars.SuspiciousSpeculars");
        java.lang.reflect.Field build = mod.getDeclaredField("BUILD");
        build.setAccessible(true);
        System.out.println(build.get(null));
    }
}
