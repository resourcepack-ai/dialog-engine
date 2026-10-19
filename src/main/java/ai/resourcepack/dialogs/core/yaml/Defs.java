package ai.resourcepack.dialogs.core.yaml;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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
            ModelData modelData,
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

    /**
     * A body item's {@code custom-model-data:} as written: every value kept as
     * text, so a {placeholder} in it is filled for the player before it is
     * read. {@code auto} is the shorthand, a value or a list of them, each a
     * float, a flag or a string by what it says once filled; the other four are
     * the game's own lists, given by name.
     */
    record ModelData(List<String> auto, List<String> floats, List<String> flags, List<String> strings, List<String> colors) {

        static final ModelData NONE = new ModelData(List.of(), List.of(), List.of(), List.of(), List.of());

        /**
         * The {@code minecraft:custom_model_data} component, with what does not
         * read as its list's kind left out — or null when nothing is left, so a
         * value a placeholder emptied sends no component at all.
         */
        JsonObject json(UnaryOperator<String> fill) {
            JsonArray f = new JsonArray();
            JsonArray b = new JsonArray();
            JsonArray s = new JsonArray();
            JsonArray c = new JsonArray();
            for (String raw : auto) {
                String v = fill.apply(raw).trim();
                Double n = number(v);
                if (n != null) f.add(n);
                else if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) b.add(Boolean.parseBoolean(v));
                else if (!v.isEmpty()) s.add(v);
            }
            for (String raw : floats) {
                Double n = number(fill.apply(raw).trim());
                if (n != null) f.add(n);
            }
            for (String raw : flags) {
                String v = fill.apply(raw).trim();
                if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) b.add(Boolean.parseBoolean(v));
            }
            for (String raw : strings) {
                String v = fill.apply(raw);
                if (!v.isEmpty()) s.add(v);
            }
            for (String raw : colors) {
                Integer rgb = color(fill.apply(raw).trim());
                if (rgb != null) c.add(rgb);
            }
            JsonObject out = new JsonObject();
            if (!f.isEmpty()) out.add("floats", f);
            if (!b.isEmpty()) out.add("flags", b);
            if (!s.isEmpty()) out.add("strings", s);
            if (!c.isEmpty()) out.add("colors", c);
            return out.isEmpty() ? null : out;
        }

        private static Double number(String v) {
            if (v.isEmpty()) return null;
            try {
                double d = Double.parseDouble(v);
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** An RGB colour, {@code #rrggbb} or a number up to 0xFFFFFF. */
        private static Integer color(String v) {
            try {
                int rgb = v.startsWith("#") && v.length() == 7 ? Integer.parseInt(v.substring(1), 16) : Integer.parseInt(v);
                return rgb >= 0 && rgb <= 0xFFFFFF ? rgb : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
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
