package suspiciousspeculars;

import java.util.Properties;

/**
 * The one decision the coremod makes: patch in this JVM, or hand over to the launcher window.
 *
 * Kept as a pure function so the guards can be tested without Minecraft - a wrong answer here either
 * kills a launch or loops it, and neither is visible until the game is in front of a player.
 */
public final class CorePlan {

    public enum Action {
        /** Nothing to do: leave the game alone. */
        NOTHING,
        /** Spawn the launcher window, then halt this JVM. */
        HAND_OVER,
        /** No launcher available (or a hand-over already failed): patch right here. */
        PATCH_IN_PROCESS
    }

    /** State key recording that a hand-over has been attempted for the current work. */
    public static final String ATTEMPTED = "relaunchAttempted";

    private CorePlan() {
    }

    /**
     * @param state            the recorded state, possibly empty on a first run
     * @param launcherPresent  whether a launcher jar was found in the instance
     * @param allowed          the user's checkOnLaunch toggle
     * @param staleShader      the shader on disk was patched from other data than this build carries
     */
    public static Action decide(Properties state, boolean launcherPresent, boolean allowed, boolean staleShader) {
        if (!allowed) return Action.NOTHING;

        // "installed" means the shader was written by the last run; with no pending update there is
        // nothing for the window to do, and treating it like "current" avoids a second hand-over on
        // the launch Continue just started. An in-game install request still earns a window.
        String status = state.getProperty("status", "");
        boolean current = "current".equals(status) || "installed".equals(status);
        boolean update = "true".equals(state.getProperty("updateAvailable"));
        boolean requested = "true".equals(state.getProperty("installOnNextStart"));
        boolean attempted = "true".equals(state.getProperty(ATTEMPTED));

        // A rebuild is work too: the shader still matches its recorded hash, but it was patched from
        // older data, so the release's new shader edits have not arrived yet.
        if (current && !update && !requested && !staleShader) return Action.NOTHING;
        if (!launcherPresent) return Action.PATCH_IN_PROCESS;
        // The guard exists to stop a hand-over loop within one launch. An explicit request from the
        // game ("Quit and install") is not a loop, so it must not be swallowed by a stale flag.
        if (attempted && !requested) return Action.PATCH_IN_PROCESS;
        return Action.HAND_OVER;
    }
}
