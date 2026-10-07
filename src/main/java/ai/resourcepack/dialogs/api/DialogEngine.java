package ai.resourcepack.dialogs.api;

/**
 * The way in. {@code DialogEngine.api()} from any plugin that declares
 * {@code depend: [DialogEngine]} (or {@code softdepend}, after checking the
 * plugin is enabled).
 *
 * <pre>{@code
 * DialogEngineAPI dialogs = DialogEngine.api();
 * dialogs.open(player, "shop:main", Map.of("coins", "120"));
 * }</pre>
 */
public final class DialogEngine {

    private static volatile DialogEngineAPI api;

    private DialogEngine() {}

    /**
     * The running engine.
     *
     * @throws IllegalStateException when Dialog Engine is not enabled
     */
    public static DialogEngineAPI api() {
        DialogEngineAPI current = api;
        if (current == null) {
            throw new IllegalStateException("Dialog Engine is not enabled. Add depend: [DialogEngine] to your plugin.yml.");
        }
        return current;
    }

    /** True while the engine is enabled. */
    public static boolean isEnabled() {
        return api != null;
    }

    /** Internal: set by the plugin on enable and cleared on disable. */
    public static void set(DialogEngineAPI engine) {
        api = engine;
    }
}
