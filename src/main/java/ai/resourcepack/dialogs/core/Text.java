package ai.resourcepack.dialogs.core;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * Placeholder filling. {@code {key}} is a value; {@code {key?a:Words|b:Other|Default}}
 * picks the words for the value {@code key} has, the last entry when none
 * matches. What a player typed is escaped before MiniMessage reads it, so a
 * name or a reason can never become a click event or a colour.
 */
final class Text {

    static final MiniMessage MM = MiniMessage.miniMessage();

    enum Mode {
        /** For MiniMessage: player-typed values are escaped. */
        TEXT,
        /** For commands and action arguments: player-typed values are flattened to one line. */
        RAW,
        /** For a raw JSON dialog: every value is escaped as a JSON string body. */
        JSON
    }

    private Text() {}

    static Component component(String raw, Scope scope) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return MM.deserialize(fill(raw, scope, Mode.TEXT));
    }

    /** MiniMessage, filled, as the game's JSON text component (what a dialog JSON holds). */
    static JsonElement json(String raw, Scope scope) {
        return json(component(raw, scope));
    }

    static JsonElement json(Component component) {
        return GsonComponentSerializer.gson().serializeToTree(component);
    }

    static String fill(String raw, Scope scope, Mode mode) {
        if (raw == null) return "";
        String out = fillBraces(raw, scope, mode);
        // Not in a raw JSON dialog: a PlaceholderAPI value there could break the JSON it lands in.
        if (mode != Mode.JSON && out.indexOf('%') >= 0) out = Papi.apply(scope.viewer, out, mode);
        return out;
    }

    private static String fillBraces(String raw, Scope scope, Mode mode) {
        if (raw.indexOf('{') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                int end = raw.indexOf('}', i + 1);
                if (end > i) {
                    String inner = raw.substring(i + 1, end);
                    String replaced = placeholder(inner, scope, mode);
                    if (replaced != null) {
                        out.append(replaced);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The replacement for one {@code {...}}, or null to leave it as written. */
    private static String placeholder(String inner, Scope scope, Mode mode) {
        int q = inner.indexOf('?');
        String key = q < 0 ? inner : inner.substring(0, q);
        if (!isKey(key)) return null;
        String value = scope.lookup(key);
        if (q < 0) {
            if (value == null) return null;
            return encode(value, scope.isUntrusted(key), mode);
        }
        // A choice: the words are the dialog's own, so they are not escaped.
        String[] entries = inner.substring(q + 1).split("\\|", -1);
        String chosen = null;
        for (int e = 0; e < entries.length; e++) {
            String entry = entries[e];
            int colon = entry.indexOf(':');
            boolean last = e == entries.length - 1;
            if (colon >= 0) {
                String match = entry.substring(0, colon);
                if (value != null && match.equalsIgnoreCase(value)) {
                    chosen = entry.substring(colon + 1);
                    break;
                }
                if (last) chosen = entry.substring(colon + 1);
            } else if (last) {
                chosen = entry;
            }
        }
        if (chosen == null) chosen = "";
        // The chosen words may themselves name placeholders: "{x?a:{y}|b}" is not
        // supported (a brace ends the choice), but "$value" prints the value.
        if (chosen.contains("$value")) {
            chosen = chosen.replace("$value", value == null ? "" : encode(value, scope.isUntrusted(key), mode));
        }
        return mode == Mode.JSON ? jsonEscape(chosen) : chosen;
    }

    static boolean isKey(String key) {
        if (key.isEmpty() || key.length() > 64) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == ':')) return false;
        }
        return true;
    }

    private static String encode(String value, boolean untrusted, Mode mode) {
        return switch (mode) {
            case TEXT -> untrusted ? MM.escapeTags(value) : value;
            case RAW -> untrusted ? value.replace('\n', ' ').replace('\r', ' ') : value;
            case JSON -> jsonEscape(value);
        };
    }

    static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** "1.0" → "1", "2.50" → "2.5": how a slider's number reads in a command. */
    static String number(float f) {
        if (f == Math.rint(f) && Math.abs(f) < 1e9) return String.valueOf((long) f);
        String s = String.valueOf(f);
        return s.contains("E") ? java.math.BigDecimal.valueOf(f).stripTrailingZeros().toPlainString() : s;
    }
}
