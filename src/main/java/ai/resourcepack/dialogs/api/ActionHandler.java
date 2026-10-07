package ai.resourcepack.dialogs.api;

/** What a custom {@code [tag] argument} action does. Runs on the main thread. */
@FunctionalInterface
public interface ActionHandler {

    /**
     * @param context  the click; open, refresh or close the dialog through it
     * @param argument everything after the tag, placeholders filled, trimmed
     */
    void run(ClickContext context, String argument);
}
