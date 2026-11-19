import suspiciousspeculars.CorePlan;

import java.util.Properties;

/**
 * Guard tests for the coremod's hand-over decision.
 *
 * The wrong answer either kills a launch (hand over when nothing is wrong) or loops it (hand over
 * again after a failed attempt), and neither shows up until a player is staring at a closed game.
 *
 *   javac -cp suspicious-speculars-<build>.jar -d out CorePlanTest.java
 *   java  -cp "suspicious-speculars-<build>.jar;out" CorePlanTest
 *
 * Run by test\run-scenarios.ps1 against the newest built jar, so the guards stay attached to the
 * decision instead of drifting with the docs.
 */
public final class CorePlanTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // first run: no state at all
        expect("first run hands over", CorePlan.Action.HAND_OVER, props(null, null, null), true, true);
        expect("first run without launcher patches here", CorePlan.Action.PATCH_IN_PROCESS,
                props(null, null, null), false, true);

        // everything current: leave the game alone, even with a launcher present
        expect("current does nothing", CorePlan.Action.NOTHING,
                props("current", "false", "false"), true, true);
        expect("current with an update hands over", CorePlan.Action.HAND_OVER,
                props("current", "true", "false"), true, true);

        // a hand-over already happened for this work: never again
        expect("attempted hand-over patches here", CorePlan.Action.PATCH_IN_PROCESS,
                props("base-missing", "false", "true"), true, true);

        // stale shader (state says current but files changed) still hands over once
        expect("stale state hands over", CorePlan.Action.HAND_OVER,
                props("packs-update", "false", "false"), true, true);

        // a shader patched from older data is work of its own: the file matches its recorded hash, but
        // the release's shader edits have not arrived, so the window has something to do
        expect("data patched from older data hands over", CorePlan.Action.HAND_OVER,
                props("current", "false", "false"), true, true, true);
        expect("data patched from older data without a launcher patches here", CorePlan.Action.PATCH_IN_PROCESS,
                props("current", "false", "false"), false, true, true);
        expect("attempted data rebuild patches here", CorePlan.Action.PATCH_IN_PROCESS,
                props("current", "false", "true"), true, true, true);

        // the user's toggle wins over everything
        expect("checkOnLaunch off does nothing", CorePlan.Action.NOTHING,
                props("base-missing", "true", "false"), true, false);

        // an explicit request from the game ("Quit and install") outranks a stale hand-over guard,
        // otherwise the player asks for a repair and gets another silent in-process check
        Properties requested = props("packs-update", "false", "true");
        requested.setProperty("installOnNextStart", "true");
        expect("an install request outranks the guard", CorePlan.Action.HAND_OVER, requested, true, true);

        System.out.println();
        System.out.println("== " + passed + " passed, " + failed + " failed ==");
        if (failed > 0) System.exit(1);
    }

    private static Properties props(String status, String update, String attempted) {
        Properties p = new Properties();
        if (status != null) p.setProperty("status", status);
        if (update != null) p.setProperty("updateAvailable", update);
        if (attempted != null) p.setProperty(CorePlan.ATTEMPTED, attempted);
        return p;
    }

    private static void expect(String name, CorePlan.Action want, Properties state,
                               boolean launcher, boolean allowed) {
        expect(name, want, state, launcher, allowed, false);
    }

    private static void expect(String name, CorePlan.Action want, Properties state,
                               boolean launcher, boolean allowed, boolean staleShader) {
        CorePlan.Action got = CorePlan.decide(state, launcher, allowed, staleShader);
        if (got == want) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  (wanted " + want + ", got " + got + ")");
        }
    }
}
