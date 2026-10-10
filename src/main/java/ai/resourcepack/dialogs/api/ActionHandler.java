package ai.resourcepack.dialogs.api;

/** What a custom {@code [tag] argument} action does. Runs on the main thread. */
@FunctionalInterface
public interface ActionHandler {

    /**
     * @param context  the click; open, refresh or close the dialog through it
     * @param argument everything after the tag, placeholders filled, trimmed. What a
     *                 player typed is in it as typed, on one line: check it before it
     *                 goes into a command ({@code [console]} and {@code [player]} refuse
     *                 one with more than letters, digits, {@code _ . -} unless filtered)
     */
    void run(ClickContext context, String argument);
}
