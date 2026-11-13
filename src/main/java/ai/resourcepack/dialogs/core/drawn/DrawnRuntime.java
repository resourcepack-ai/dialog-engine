package ai.resourcepack.dialogs.core.drawn;

/** What the plugin tells the drawn-dialog runtime about this server, from outside this package. */
public final class DrawnRuntime {
    private DrawnRuntime() {
    }

    /** Whether the server draws the 1.21.9 player text component a live head needs ({@link Heads}). */
    public static boolean headsSupported() {
        return Heads.supported();
    }
}
