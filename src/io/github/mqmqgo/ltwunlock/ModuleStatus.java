package io.github.mqmqgo.ltwunlock;

/**
 * Classic LSPosed self-check: returns false until the module hooks itself
 * inside its own process (requires this package in the module scope).
 */
public final class ModuleStatus {
    private ModuleStatus() {
    }

    /** Default false; replaced with true when LSPosed injects into this app. */
    public static boolean isActivated() {
        return false;
    }
}
