package ai.resourcepack.dialogs.core.placeholder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder filling. {@code {key}} is a value; {@code {key?a:Words|b:Other|Default}}
 * picks the words for the value {@code key} has, the last entry when none
 * matches. What a player typed is escaped before MiniMessage reads it, so a
 * name or a reason can never become a click event or a colour, and it is kept
 * away from PlaceholderAPI, so it can never name a {@code %placeholder%}.
 *
 * <p>In a command that runs ({@link Mode#COMMAND}) a typed value may hold only
 * {@code A-Z a-z 0-9 _ . -}: a player name, a number or an id, and nothing
 * that is an argument of its own ({@code @a}, a space) or leaves a string (a
 * quote). Any other is {@link Unsafe} and the command does not run, unless the
 * dialog's author filtered it: {@code {key|raw}} as typed, on one line;
 * {@code {key|json}} and {@code {key|snbt}} escaped for the inside of a quoted
 * JSON or SNBT string. A filter works on any value, typed or not.
 *
 * <p>An author's text is MiniMessage, in which {@code &#92;u2764},
 * {@code &#92;U0001F600} and {@code &#92;u{1F600}} are the character they name
 * (a symbol, or a resource pack's glyph: YAML decodes those only inside double
 * quotes). Or it is the game's own JSON text component, the one
 * {@code /tellraw} and Paper take, when the whole text is one ({@link #isJson}):
 * {@code {"text":"&#92;uE001","font":"myicons:icons"}}.
 */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    public enum Mode {
        /** For MiniMessage: player-typed values are escaped. */
        TEXT,
        /** For action arguments: player-typed values are flattened to one line. */
        RAW,
        /** For a command that runs: as RAW, and a player-typed value must be {@link #safe} or filtered. */
        COMMAND,
        /** For a raw JSON dialog: every value is escaped as a JSON string body. */
        JSON
    }

    /** What {@code {key|filter}} may say. */
    private static final Set<String> FILTERS = Set.of("raw", "json", "snbt");

    /**
     * A {@link Mode#COMMAND} fill met a value a player typed that a command
     * may not carry as it is. {@code key} is the placeholder that held it.
     */
    public static final class Unsafe extends RuntimeException {
        public final String key;

        Unsafe(String key) {
            super("{" + key + "} holds typed text a command may not carry", null, false, false);
            this.key = key;
        }
    }

    private Text() {}

    public static Component component(String raw, Scope scope) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (isJson(raw)) {
            Component json = fromJson(raw, scope);
            if (json != null) return json;
        }
        return MM.deserialize(fill(raw, scope, Mode.TEXT));
    }

    /** An author's text with nothing filled in: MiniMessage, or a JSON text component. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (isJson(raw)) {
            Component json = fromJson(raw, null);
            if (json != null) return json;
        }
        return MM.deserialize(unicode(raw));
    }

    /** An author's text as plain words, placeholders filled: for a message that names an input. */
    public static String plain(String raw, Scope scope) {
        if (raw == null) return "";
        return isJson(raw) ? PlainTextComponentSerializer.plainText().serialize(component(raw, scope))
                : MM.stripTags(fill(raw, scope, Mode.TEXT));
    }

    /**
     * Whether an author's text is meant as a JSON text component rather than
     * MiniMessage: the whole of it an object whose first key is quoted
     * ({@code {"text":...}}, which no placeholder can be, since a key holds no
     * quote), or an array of components or strings ({@code [{...}, "..."]}).
     * {@code [VIP] Welcome} and {@code {player} joined} stay MiniMessage.
     */
    public static boolean isJson(String raw) {
        if (raw == null) return false;
        String t = raw.strip();
        if (t.length() < 2) return false;
        char first = t.charAt(0), last = t.charAt(t.length() - 1);
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return false;
        int i = 1;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        if (i >= t.length()) return false;
        char next = t.charAt(i);
        return first == '{' ? next == '"' : next == '{' || next == '"';
    }

    /**
     * Why a text {@link #isJson} takes for JSON is not a component the game
     * reads, or null when it is (or is MiniMessage). For the load report: such
     * a text is shown as MiniMessage, which prints the JSON as written.
     */
    public static String jsonProblem(String raw) {
        if (!isJson(raw)) return null;
        try {
            GsonComponentSerializer.gson().deserializeFromTree(JsonParser.parseString(raw.strip()));
            return null;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int nl = message.indexOf('\n');
            return nl < 0 ? message : message.substring(0, nl);
        }
    }

    /** Keys whose strings are words a player reads, filled as text; every other string is filled as a command. */
    private static final Set<String> JSON_WORDS = Set.of("text", "fallback", "with", "extra");

    /** The component a JSON text is, its strings filled when there is a scope, or null when it is not one. */
    private static Component fromJson(String raw, Scope scope) {
        try {
            JsonElement tree = JsonParser.parseString(raw.strip());
            return GsonComponentSerializer.gson().deserializeFromTree(scope == null ? tree : fillJson(tree, null, scope));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Fills every string of a JSON component after it is parsed, so no value
     * (a PlaceholderAPI answer, a typed reason) can change its structure.
     * Words ({@code text}, {@code extra}, {@code with}) take any value; any
     * other string (a click's command, a hover's id, a font) is filled as a
     * command is, so a typed value that is more than a name leaves the
     * placeholder in it as written.
     */
    static JsonElement fillJson(JsonElement e, String key, Scope scope) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : e.getAsJsonObject().entrySet()) out.add(entry.getKey(), fillJson(entry.getValue(), entry.getKey(), scope));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : e.getAsJsonArray()) out.add(fillJson(item, key, scope));
            return out;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return e;
        String s = e.getAsString();
        if (key == null || JSON_WORDS.contains(key)) return new JsonPrimitive(fill(s, scope, Mode.RAW));
        try {
            return new JsonPrimitive(fill(s, scope, Mode.COMMAND));
        } catch (Unsafe unsafe) {
            return e;
        }
    }

    private static final Pattern UNICODE = Pattern.compile("(?<!\\\\)\\\\(?:u\\{([0-9A-Fa-f]{1,6})}|u([0-9A-Fa-f]{4})|U([0-9A-Fa-f]{8}))");

    /**
     * The escapes {@code &#92;u2764}, {@code &#92;U0001F600} and
     * {@code &#92;u{1F600}} as the characters they name; two four-digit ones in
     * a row make one character above U+FFFF, as in JSON. One preceded by
     * another backslash, or naming no character, is left as written.
     */
    static String unicode(String raw) {
        if (raw.indexOf('\\') < 0) return raw;
        Matcher m = UNICODE.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        while (m.find()) {
            String hex = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            long cp = Long.parseLong(hex, 16);
            String ch = cp <= Character.MAX_CODE_POINT ? new String(Character.toChars((int) cp)) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(ch));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** MiniMessage, filled, as the game's JSON text component (what a dialog JSON holds). */
    public static JsonElement json(String raw, Scope scope) {
        return json(component(raw, scope));
    }

    public static JsonElement json(Component component) {
        return GsonComponentSerializer.gson().serializeToTree(component);
    }

    public static String fill(String raw, Scope scope, Mode mode) {
        if (raw == null) return "";
        // The author's own escapes, before any value goes in: a value is never read for one.
        if (mode == Mode.TEXT) raw = unicode(raw);
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

    // Noncharacters, which no text holds: U+E000 on is where resource packs put their glyphs.
    private static final char HELD_OPEN = '\uFDD0';
    private static final char HELD_CLOSE = '\uFDD1';
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
        // {key|filter}: on a plain value only, since a choice's words are split on |.
        String filter = null;
        int bar = q < 0 ? key.indexOf('|') : -1;
        if (bar >= 0) {
            filter = key.substring(bar + 1);
            key = key.substring(0, bar);
            if (!FILTERS.contains(filter)) return null;
        }
        if (!isKey(key)) return null;
        String value = scope.lookup(key);
        boolean untrusted = scope.isUntrusted(key);
        if (q < 0) {
            if (value == null) return null;
            return hold(encode(key, value, untrusted, mode, filter), untrusted, held);
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
                // "*" is any value that is not empty, as in RP Engine's drawn dialogs.
                if (value != null && (match.equalsIgnoreCase(value) || (match.equals("*") && !value.isBlank()))) {
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
            chosen = chosen.replace("$value", value == null ? "" : hold(encode(key, value, untrusted, mode, null), untrusted, held));
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

    /**
     * One value, as it goes where {@code mode} puts it. A filter escapes for
     * the string the author put the value in, and the mode then escapes for
     * what is around that: {@code {x|json}} in a raw JSON dialog is escaped
     * twice, as a JSON string inside a JSON string has to be.
     */
    private static String encode(String key, String value, boolean untrusted, Mode mode, String filter) {
        if (mode == Mode.COMMAND && untrusted && filter == null && !safe(value)) throw new Unsafe(key);
        String v = filter == null ? value : switch (filter) {
            case "json" -> jsonEscape(value);
            case "snbt" -> snbtEscape(value);
            default -> value;
        };
        return switch (mode) {
            case TEXT -> untrusted ? MM.escapeTags(v) : v;
            case RAW, COMMAND -> untrusted ? v.replace('\n', ' ').replace('\r', ' ') : v;
            case JSON -> jsonEscape(v);
        };
    }

    /**
     * Whether a typed value can go into a command as it is: a player name, a
     * number, an id. Not a space (a second argument), not {@code @} (a
     * selector), not a quote, brace or bracket (out of a JSON or SNBT string).
     */
    static boolean safe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
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

    /**
     * The inside of a quoted SNBT string, in either quote: the game's SNBT
     * (1.21.5 and newer, so every server this runs on) reads {@code \'} and
     * {@code \"} in both.
     */
    static String snbtEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\'' -> out.append("\\'");
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
    public static String number(float f) {
        if (f == Math.rint(f) && Math.abs(f) < 1e9) return String.valueOf((long) f);
        String s = String.valueOf(f);
        return s.contains("E") ? java.math.BigDecimal.valueOf(f).stripTrailingZeros().toPlainString() : s;
    }
}
