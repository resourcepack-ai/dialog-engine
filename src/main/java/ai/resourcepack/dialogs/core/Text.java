package ai.resourcepack.dialogs.core;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder filling. {@code {key}} is a value; {@code {key?a:Words|b:Other|Default}}
 * picks the words for the value {@code key} has, the last entry when none
 * matches. What a player typed is escaped before MiniMessage reads it, so a
 * name or a reason can never become a click event or a colour, and it is kept
 * away from PlaceholderAPI, so it can never name a {@code %placeholder%}.
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
        // Not in a raw JSON dialog: a PlaceholderAPI value there could break the JSON it lands in.
        if (mode == Mode.JSON || !Papi.available()) return fillBraces(raw, scope, mode, null);
        // What a player typed never reaches PlaceholderAPI: "%parseother_{Admin}_{player_ip}%"
        // in a text box would otherwise print somebody else's address. Each typed value
        // is held out as a marker while PlaceholderAPI runs, and put back after.
        List<String> held = new ArrayList<>();
        String out = fillBraces(raw, scope, mode, held);
        if (out.indexOf('%') >= 0) out = Papi.apply(scope.viewer, out, mode);
        return held.isEmpty() ? out : restore(out, held);
    }

    private static final char HELD_OPEN = '\uE000';
    private static final char HELD_CLOSE = '\uE001';
    private static final Pattern HELD = Pattern.compile(HELD_OPEN + "(\\d{1,6})" + HELD_CLOSE);

    /** Puts the held values back, in one pass: a value is never searched again for markers. */
    static String restore(String out, List<String> held) {
        Matcher m = HELD.matcher(out);
        StringBuilder sb = new StringBuilder(out.length() + 16);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(i < held.size() ? held.get(i) : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fillBraces(String raw, Scope scope, Mode mode, List<String> held) {
        if (raw.indexOf('{') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                int end = raw.indexOf('}', i + 1);
                if (end > i) {
                    String inner = raw.substring(i + 1, end);
                    String replaced = placeholder(inner, scope, mode, held);
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
    private static String placeholder(String inner, Scope scope, Mode mode, List<String> held) {
        int q = inner.indexOf('?');
        String key = q < 0 ? inner : inner.substring(0, q);
        if (!isKey(key)) return null;
        String value = scope.lookup(key);
        if (q < 0) {
            if (value == null) return null;
            return hold(encode(value, scope.isUntrusted(key), mode), scope.isUntrusted(key), held);
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
            chosen = chosen.replace("$value", value == null ? "" : hold(encode(value, scope.isUntrusted(key), mode), scope.isUntrusted(key), held));
        }
        return mode == Mode.JSON ? jsonEscape(chosen) : chosen;
    }

    /** An untrusted value as a marker {@link #restore} swaps back, when PlaceholderAPI is about to run. */
    private static String hold(String encoded, boolean untrusted, List<String> held) {
        // Every typed value, not just one with a % in it: "%{x}%" would let the typed half name the placeholder.
        if (!untrusted || held == null) return encoded;
        held.add(encoded);
        return HELD_OPEN + String.valueOf(held.size() - 1) + HELD_CLOSE;
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
