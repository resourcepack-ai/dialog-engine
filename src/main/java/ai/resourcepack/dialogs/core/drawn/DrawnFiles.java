package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.edit.EditWire;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A drawn dialog's folder, {@code drawn/<namespace>/<name>/}:
 *
 * <ul>
 *   <li>{@code dialog.yml}: the dialog itself, Studio's document as YAML. The
 *   one file a person edits, by hand or with {@code /de edit}.</li>
 *   <li>{@code dialogs.json} and {@code pack/}: what Studio drew from it. Never
 *   edited; redrawn whenever {@code dialog.yml} no longer matches.</li>
 *   <li>{@code .built}: the hash of the {@code dialog.yml} they were drawn
 *   from, which is how a hand edit is noticed.</li>
 * </ul>
 *
 * A folder from 0.2.0's first builds has a {@code spec.json} instead, and is
 * moved over to {@code dialog.yml} the first time it is read.
 */
public final class DrawnFiles {

    public static final String SOURCE = "dialog.yml";
    private static final String BUILT = ".built";
    private static final String HEADER = """
            # A drawn dialog: Studio's dialog editor document, as YAML.
            # Edit it with /de edit, or by hand here and run /de reload: a changed
            # file is redrawn by Studio (resourcepack.ai), which needs the internet.
            # A clickable piece's command can be Dialog Engine actions, e.g.
            #   value: "[console] give {player} diamond; [message] <green>Here you go"
            """;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() {}.getType();

    private DrawnFiles() {
    }

    /** The dialog as Studio's document, or null when the folder has none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path dir) throws IOException {
        Path yml = dir.resolve(SOURCE);
        if (Files.isRegularFile(yml)) {
            Object loaded = yaml().load(Files.readString(yml, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> map)) throw new IOException(SOURCE + " is not a YAML map");
            return (Map<String, Object>) map;
        }
        Path json = dir.resolve("spec.json");
        if (Files.isRegularFile(json)) {
            Map<String, Object> spec = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), MAP);
            // Moved over: the drawing in this folder is already this document's.
            write(dir, spec);
            Files.delete(json);
            return spec;
        }
        return null;
    }

    /** Writes the document, and records the drawing beside it as drawn from it. */
    static void write(Path dir, Map<String, Object> spec) throws IOException {
        Files.createDirectories(dir);
        String text = HEADER + yaml().dump(tidy(spec));
        Files.writeString(dir.resolve(SOURCE), text, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(BUILT), hash(text), StandardCharsets.UTF_8);
    }

    /**
     * Whether the drawing puts its picture in the shared default font: drawn
     * before Studio gave every picture a font of its own ({@code rpai:dialog_*}),
     * so an emoji or HUD plugin's pack can take its characters.
     */
    static boolean inDefaultFont(Path dir) {
        try {
            Path built = dir.resolve("dialogs.json");
            if (!Files.isRegularFile(built) || !Files.isRegularFile(dir.resolve("pack/assets/minecraft/font/default.json"))) return false;
            return !Files.readString(built, StandardCharsets.UTF_8).contains("\"rpai:dialog_");
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether {@code dialog.yml} has changed since the drawing was made. */
    static boolean stale(Path dir) {
        try {
            Path yml = dir.resolve(SOURCE);
            if (!Files.isRegularFile(yml)) return false;
            if (!Files.isRegularFile(dir.resolve("dialogs.json"))) return true;
            Path built = dir.resolve(BUILT);
            return !Files.isRegularFile(built)
                    || !Files.readString(built).trim().equals(hash(Files.readString(yml, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes what Studio drew: every pack file, the pages, and the document.
     * Every file is checked before anything is written, because it came over
     * the network: one path leaving the dialog's own {@code pack/} folder and
     * nothing is.
     */
    public static void writeDrawing(Path dir, EditWire.Pull pull, boolean writeSource) throws IOException {
        // The drawing may only run commands its document has: the document that
        // came with it, or (a redraw) the one here, which Studio cannot change.
        Map<String, Object> source = writeSource ? pull.dialog.spec : read(dir);
        List<String> invented = invented(GSON.toJsonTree(pull.dialog.built), commands(source));
        if (!invented.isEmpty()) {
            throw new IOException("the drawing runs a command " + SOURCE + " does not have: " + invented.get(0));
        }
        Path pack = dir.resolve("pack");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (EditWire.File f : pull.files == null ? List.<EditWire.File>of() : pull.files) {
            if (f.path == null || !f.path.startsWith("pack/") || !f.path.matches("[A-Za-z0-9._/\\-]+") || f.path.contains("..")) {
                throw new IOException("refused a file outside the dialog's pack: " + f.path);
            }
            Path target = dir.resolve(f.path).normalize();
            if (!target.startsWith(pack)) throw new IOException("refused " + f.path);
            files.put(target, "base64".equals(f.encoding)
                    ? Base64.getDecoder().decode(f.content)
                    : f.content.getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(dir);
        if (Files.isDirectory(pack)) {
            try (var walk = Files.walk(pack)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        for (var e : files.entrySet()) {
            Files.createDirectories(e.getKey().getParent());
            Files.write(e.getKey(), e.getValue());
        }
        Files.writeString(dir.resolve("dialogs.json"), GSON.toJson(pull.dialog.built));
        if (writeSource) {
            write(dir, keepServerKeys(dir, pull.dialog.spec));
        } else {
            // Drawn from the file as it stands: record that, and leave the file (comments and all) alone.
            Files.writeString(dir.resolve(BUILT), hash(Files.readString(dir.resolve(SOURCE), StandardCharsets.UTF_8)));
        }
    }

    /** Keys of dialog.yml that are this server's, not the drawing's: Studio does not send them, so a Send keeps them. */
    private static final List<String> SERVER_KEYS = List.of("quickAction", "quick-action");

    private static Map<String, Object> keepServerKeys(Path dir, Map<String, Object> spec) {
        if (spec == null) return null;
        Map<String, Object> old;
        try {
            old = read(dir);
        } catch (IOException | RuntimeException e) {
            return spec;
        }
        if (old == null) return spec;
        Map<String, Object> out = new LinkedHashMap<>(spec);
        for (String k : SERVER_KEYS) {
            if (!out.containsKey(k) && old.containsKey(k)) out.put(k, old.get(k));
        }
        return out;
    }

    // ------------------------------------------------------------------ commands

    private static final Pattern CHAT_ILLEGAL = Pattern.compile("[\\x00-\\x1f\\x7f\u00a7]");

    /**
     * A command as Studio writes it into a drawing: no leading space or slash,
     * nothing the game refuses in chat, no trailing space (Studio's
     * {@code dialogCommandText}). Both sides of {@link #commands} go through it.
     */
    static String commandText(String value) {
        String s = value.stripLeading();
        if (s.startsWith("/")) s = s.substring(1);
        return CHAT_ILLEGAL.matcher(s).replaceAll("").stripTrailing();
    }

    /**
     * Every string in the document, as a command: what a drawing of it may run.
     * A list is its one template drawn once per row, each row spelled its own
     * way ({@link #rowCopies}), so a command in a list is allowed once per row
     * it is drawn in.
     */
    public static Set<String> commands(Map<String, Object> spec) {
        Set<String> out = new HashSet<>();
        if (spec == null) return out;
        Set<String> written = new LinkedHashSet<>();
        collect(spec, written);
        for (String text : withRows(written, lists(spec))) {
            String c = commandText(text);
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    /**
     * The document's "Run as console" commands, by the key Studio's click names
     * them with ({@code rp click <key>}): each piece or button whose action is
     * {@code console_command}, its command as {@link #commandText} spells it.
     * One built from what the player typed ({@code $(name)}) is left out, as
     * Studio leaves it out. Read from dialog.yml, never from Studio's answer.
     *
     * <p>A "Run command" with several commands is kept by key the same way
     * (Studio's {@code rp run <key>}); one is the game's own run_command.
     *
     * <p>A key is a hash of the commands as the click runs them, and a list's
     * row runs its own copy ({@link #rowCopies}), so a click in a list has a key
     * per row. Before 0.7.1 only the template's was kept, and every "Run as
     * console" button and every button with several commands on a list row did
     * nothing ("a click runs rp click ..., which its dialog.yml does not have").
     */
    public static Map<String, String> consoleCommands(Map<String, Object> spec) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Click> clicks = new ArrayList<>();
        clicks(spec, clicks);
        if (clicks.isEmpty()) return out;
        Map<String, ListRows> lists = lists(spec);
        for (Click click : clicks) {
            for (String value : withRows(List.of(click.value()), lists)) keyed(click.console(), value, out);
        }
        return out;
    }

    /** What a piece or button runs when clicked, as written: as the console, or as the player. */
    private record Click(boolean console, String value) {}

    private static void clicks(Object value, List<Click> out) {
        if (value instanceof Map<?, ?> map) {
            boolean console = "console_command".equals(map.get("action"));
            boolean run = "run_command".equals(map.get("action"));
            if ((console || run) && map.get("value") instanceof String raw) out.add(new Click(console, raw));
            for (Object v : map.values()) clicks(v, out);
        } else if (value instanceof List<?> list) {
            for (Object v : list) clicks(v, out);
        }
    }

    /** A click's commands under their key, when Studio sends it by key: the console's, or more than one. */
    private static void keyed(boolean console, String value, Map<String, String> out) {
        if (TYPED.matcher(value).find()) return;
        List<String> lines = commandLines(value);
        if (lines.isEmpty() || (!console && lines.size() < 2)) return;
        String commands = String.join("\n", lines);
        out.put(consoleKey(commands), commands);
    }

    /** The most commands one click runs: Studio's {@code DIALOG_MAX_CLICK_COMMANDS}. */
    static final int MAX_CLICK_COMMANDS = 16;

    /**
     * A click's commands, one per line, each as {@link #commandText} spells
     * it, blank lines dropped (Studio's {@code dialogCommandLines}).
     */
    static List<String> commandLines(String value) {
        List<String> out = new ArrayList<>();
        for (String line : value.split("\r?\n", -1)) {
            String command = commandText(line);
            if (!command.isEmpty() && out.size() < MAX_CLICK_COMMANDS) out.add(command);
        }
        return out;
    }

    /** What the player typed, in a command: a dialog input's {@code $(key)}. */
    private static final Pattern TYPED = Pattern.compile("\\$\\([A-Za-z0-9_]+\\)");

    /**
     * Studio's {@code dialogConsoleKey}: eight hex digits of the command's
     * FNV-1a hash over its UTF-16 characters (RP Engine makes it the same way).
     */
    static String consoleKey(String command) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < command.length(); i++) {
            hash ^= command.charAt(i);
            hash *= 0x01000193;
        }
        return String.format("%08x", hash);
    }

    // ------------------------------------------------------------------ lists

    /**
     * How many rows Studio draws a list as, each way a list can be: one the
     * server fills, whose row 3 says {@code {warps_3}}, and one that counts
     * ("Repeated a number of times", a {@code count:}), whose row 3 says
     * {@code 3}. Zero for a way the document does not use the name.
     */
    private record ListRows(int filled, int counted) {}

    /** More rows than Studio draws any list with (its FLEX_LIST_MAX_ROWS is 12), so a raised cap there needs nothing here. */
    private static final int MAX_ROWS = 64;
    /** A list with no {@code rows:} written: Studio draws 5, and may draw up to its cap. */
    private static final int UNWRITTEN_ROWS = 12;
    /** A count that is a value's name, as Studio's {@code listCountOf} reads one: {@code {homes}}, {@code %homes%}, {@code homes}. */
    private static final Pattern COUNT_NAME = Pattern.compile("\\{[A-Za-z0-9_]{1,48}}|%[A-Za-z0-9_]{1,48}%|[A-Za-z_][A-Za-z0-9_]{0,47}");

    /** Every list in the document, by name, in the order Studio numbers them: a list before the lists inside it. */
    private static Map<String, ListRows> lists(Object spec) {
        Map<String, ListRows> out = new LinkedHashMap<>();
        lists(spec, out);
        return out;
    }

    private static void lists(Object value, Map<String, ListRows> out) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("list") instanceof String name && name.matches("[A-Za-z0-9_]+")) {
                int rows = map.get("rows") instanceof Number n ? Math.min(MAX_ROWS, Math.max(1, n.intValue())) : UNWRITTEN_ROWS;
                int count = counted(map.get("count"));
                // A count that is a number draws that many rows, whatever rows: says.
                ListRows drawn = count < 0 ? new ListRows(rows, 0) : new ListRows(0, Math.max(rows, count));
                out.merge(name, drawn, (a, b) -> new ListRows(Math.max(a.filled(), b.filled()), Math.max(a.counted(), b.counted())));
            }
            for (Object v : map.values()) lists(v, out);
        } else if (value instanceof List<?> list) {
            for (Object v : list) lists(v, out);
        }
    }

    /**
     * How many rows a list's {@code count:} asks for by itself: -1 when it is
     * not a count (a list the server fills), 0 for a value's name (as many as
     * there is room for). Studio's {@code listCountOf}.
     */
    private static int counted(Object count) {
        if (count instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? -1 : (int) Math.max(0, Math.min(MAX_ROWS, Math.round(d)));
        }
        if (!(count instanceof String s)) return -1;
        String t = s.strip();
        if (t.matches("\\d{1,3}")) return Math.min(MAX_ROWS, Integer.parseInt(t));
        return COUNT_NAME.matcher(t).matches() ? 0 : -1;
    }

    /** A placeholder of list {@code name} in any words: {@code {name}}, {@code {name_field}}, a choice on either. Studio's {@code listPlaceholder}. */
    private static Pattern placeholder(String name) {
        return Pattern.compile("\\{" + Pattern.quote(name) + "((?:_[A-Za-z0-9_]*)?)(\\?[^{}]*)?\\}");
    }

    /** Each text, and its copy for every row of every list it is in (in a list in a list, for every pair of rows). */
    private static Set<String> withRows(Collection<String> written, Map<String, ListRows> lists) {
        Set<String> out = new LinkedHashSet<>(written);
        for (Map.Entry<String, ListRows> list : lists.entrySet()) {
            Pattern placeholder = placeholder(list.getKey());
            for (String text : List.copyOf(out)) {
                if (placeholder.matcher(text).find()) out.addAll(rowCopies(text, placeholder, list.getKey(), list.getValue()));
            }
        }
        return out;
    }

    /**
     * A text's copy for each row of a list, spelled as Studio spells a row.
     *
     * <ul>
     *   <li>A list the server fills (Studio's {@code listRowOf}): {@code {name}}
     *   is {@code {name_3}}, {@code {name_field}} is {@code {name_3_field}}, a
     *   choice on either kept as written.</li>
     *   <li>A list that counts (Studio's {@code listCountRowOf}): {@code {name}}
     *   is the row's own number, {@code 3}; a choice on it is decided for that
     *   number as the drawing is made; and {@code {name_field}} is still
     *   {@code {name_3_field}}, a value of the row's own. Before 0.7.1 such a
     *   row was spelled the first way, so a counting list whose button ran a
     *   command with its number could not be sent to the server at all ("the
     *   drawing runs a command dialog.yml does not have").</li>
     * </ul>
     */
    private static List<String> rowCopies(String text, Pattern placeholder, String name, ListRows rows) {
        List<String> out = new ArrayList<>(rows.filled() + rows.counted());
        for (int k = 1; k <= rows.filled(); k++) {
            String row = "{" + name + "_" + k;
            out.add(placeholder.matcher(text).replaceAll(m ->
                    Matcher.quoteReplacement(row + m.group(1) + (m.group(2) == null ? "" : m.group(2)) + "}")));
        }
        for (int k = 1; k <= rows.counted(); k++) {
            String row = "{" + name + "_" + k;
            String number = String.valueOf(k);
            out.add(placeholder.matcher(text).replaceAll(m -> {
                String choice = m.group(2);
                if (!m.group(1).isEmpty()) return Matcher.quoteReplacement(row + m.group(1) + (choice == null ? "" : choice) + "}");
                if (choice == null) return number;
                // Decided by the rule the picture's own choices are (RP Engine's, which Studio's chooseEntry mirrors).
                return Matcher.quoteReplacement(DialogPlaceholders.choose(choice.substring(1), Optional.of(number)).orElse(""));
            }));
        }
        return out;
    }

    /** Every string in the document, as written. */
    private static void collect(Object value, Set<String> out) {
        if (value instanceof String s) {
            if (!s.isBlank()) out.add(s);
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) collect(v, out);
        } else if (value instanceof List<?> list) {
            for (Object v : list) collect(v, out);
        }
    }

    /**
     * Every click in a drawing that runs a command {@code allowed} lacks. RP
     * Engine's page, var and slot clicks are left out: Studio writes those
     * itself, and they only ever turn a page or set a declared setting.
     */
    public static List<String> invented(JsonElement built, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        scan(built, allowed, out);
        return out;
    }

    private static void scan(JsonElement e, Set<String> allowed, List<String> out) {
        if (e instanceof JsonArray a) {
            for (JsonElement x : a) scan(x, allowed, out);
        } else if (e instanceof JsonObject o) {
            boolean runs = (o.get("action") instanceof JsonPrimitive p && p.isString() && "run_command".equals(p.getAsString()))
                    || (o.get("type") instanceof JsonPrimitive p2 && p2.isString() && "minecraft:run_command".equals(p2.getAsString()));
            if (runs && o.get("command") instanceof JsonPrimitive c && c.isString()) {
                String command = c.getAsString();
                // A console or several-command click names its commands by key, and
                // only what dialog.yml has by that key ever runs (DrawnDialogs.rewrite).
                boolean byKey = DrawnDialogs.CONSOLE_CLICK.matcher(command).matches() || DrawnDialogs.RUN_CLICK.matcher(command).matches();
                if (!byKey && !DrawnDialogs.ENGINE_CLICK.matcher(command).matches() && !allowed.contains(commandText(command))) out.add(command);
            }
            for (Map.Entry<String, JsonElement> x : o.entrySet()) scan(x.getValue(), allowed, out);
        }
    }

    // ------------------------------------------------------------------ YAML

    private static Yaml yaml() {
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dump.setIndent(2);
        dump.setIndicatorIndent(0);
        dump.setWidth(120);
        dump.setSplitLines(false);
        LoaderOptions load = new LoaderOptions();
        load.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(load), new Representer(dump), dump, load);
    }

    /** Gson reads every number as a double; a whole one is written as a whole one. */
    private static Object tidy(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) return d.longValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), tidy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(tidy(v));
            return out;
        }
        return value;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
