package suspiciousspeculars.launcher;

/**
 * A finished window run must give back the launch it took: the mod spawns the window in place of the
 * game, so only this process can start it again. A run that found nothing to change used to end with
 * "nothing changed, not relaunching" and leave the player outside the game.
 *
 * <pre>
 * javac -cp suspicious-speculars-launcher.jar -d out HandBackProbe.java
 * java  -cp "suspicious-speculars-launcher.jar;out" suspiciousspeculars.launcher.HandBackProbe
 * </pre>
 */
public final class HandBackProbe {

    public static void main(String[] args) {
        // changed run, opened by hand: relaunch, as the CLI path does
        System.out.println(Launcher.handsBack(true, false, false, false, true));
        // nothing changed, but the mod spawned the window and halted the launch: give it back
        System.out.println(Launcher.handsBack(false, true, false, false, true));
        // nothing changed, opened by hand: leave the user alone
        System.out.println(Launcher.handsBack(false, false, false, false, true));
        // dry run: report only
        System.out.println(Launcher.handsBack(true, false, true, false, true));
        // patch-only: the mod's fallback after a failed relaunch
        System.out.println(Launcher.handsBack(true, false, false, true, true));
        // no launcher to start
        System.out.println(Launcher.handsBack(true, true, false, false, false));
    }
}
