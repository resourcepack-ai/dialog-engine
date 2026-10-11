package ai.resourcepack.dialogs.core.yaml;


import java.util.List;
import java.util.Map;

/** A dialog as written, before any placeholder is filled. Immutable; rebuilt on reload. */
public final class Defs {

    private Defs() {}

    public enum Type { AUTO, NOTICE, CONFIRMATION, MULTI_ACTION, LINKS }

    /** What the screen does once a button is pressed: the game's {@code after_action}. */
    public enum After {
        CLOSE, NONE, WAIT_FOR_RESPONSE;

        String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
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
            Map<String, String> defaults) {
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
