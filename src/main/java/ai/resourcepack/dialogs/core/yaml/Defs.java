package ai.resourcepack.dialogs.core.yaml;


import java.util.List;
import java.util.Map;

/** A dialog as written, before any placeholder is filled. Immutable; rebuilt on reload. */
public final class Defs {

    private Defs() {}

    public enum Type { AUTO, NOTICE, CONFIRMATION, MULTI_ACTION, LINKS }

    /**
     * What the screen does once a button is pressed: the game's
     * {@code after_action}, and {@link #SEAMLESS}, which the game does not have.
     */
    public enum After {
        CLOSE, NONE, WAIT_FOR_RESPONSE,
        /**
         * Stays on screen exactly as it is until the engine answers the click:
         * the next dialog takes its place, or the engine closes it. Sent to the
         * client as {@code none}, so there is no waiting screen in between and
         * no flash of the world; the engine does the closing {@code close}
         * would have done.
         */
        SEAMLESS;

        String json() {
            return this == SEAMLESS ? "none" : name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Whether something has to answer a click: the engine, since the client will not. */
        public boolean awaitsAnswer() {
            return this == WAIT_FOR_RESPONSE || this == SEAMLESS;
        }

        /** Reads one as written in a dialog or config.yml, or null. */
        public static After parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
                case "close" -> CLOSE;
                case "none", "stay" -> NONE;
                case "wait", "wait_for_response" -> WAIT_FOR_RESPONSE;
                case "seamless" -> SEAMLESS;
                default -> null;
            };
        }
    }

    record ForEach(String list, String alias, int perPage, int limit) {}

    public record DialogDef(
            String id,
            String namespace,
            String source,
            String title,
            String externalTitle,
            Type type,
            int columns,
            boolean escape,
            boolean pause,
            Defs.After after,
            List<BodyDef> body,
            List<InputDef> inputs,
            List<ButtonDef> buttons,
            ButtonDef exit,
            Map<String, List<Map<String, String>>> lists,
            String command,
            List<String> aliases,
            String permission,
            List<String> args,
            String ifMissing,
            String json,
            Map<String, String> defaults,
            QuickAction quick) {
    }

    /**
     * {@code quick-action:}, the dialog on the game's Quick Actions key (G).
     * {@code launcher}: the key opens a one-button screen that asks the server
     * for the dialog, drawn fresh. Otherwise the key opens the dialog itself,
     * drawn for the player as they joined (see {@code core/quick}).
     * {@code label}: what the key's list and the launcher call it.
     */
    public record QuickAction(boolean launcher, String label) {
    }

    enum BodyKind { TEXT, ITEM }

    record BodyDef(
            BodyKind kind,
            String text,
            int width,
            String item,
            String head,
            String name,
            List<String> lore,
            String model,
            int amount,
            boolean glow,
            boolean tooltip,
            boolean decorations,
            int itemWidth,
            int itemHeight,
            String description,
            String condition,
            ForEach forEach) {
    }

    public enum InputKind { TEXT, OPTIONS, TOGGLE, SLIDER }

    record OptionDef(String id, String text, String initial) {}

    public record InputDef(
            String key,
            InputKind kind,
            String label,
            boolean labelVisible,
            int width,
            String initial,
            int maxLength,
            int lines,
            int height,
            List<OptionDef> options,
            ForEach optionsForEach,
            OptionDef optionTemplate,
            String onTrue,
            String onFalse,
            float min,
            float max,
            Float step,
            String format,
            boolean required,
            int minLength,
            String pattern,
            String error,
            String condition) {
    }

    public record ButtonDef(
            String id,
            String label,
            String tooltip,
            int width,
            String permission,
            String condition,
            ForEach forEach,
            List<String> actions,
            boolean validate) {
    }
}
