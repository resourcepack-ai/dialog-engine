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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            write(dir, pull.dialog.spec);
        } else {
            // Drawn from the file as it stands: record that, and leave the file (comments and all) alone.
            Files.writeString(dir.resolve(BUILT), hash(Files.readString(dir.resolve(SOURCE), StandardCharsets.UTF_8)));
        }
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
     * A list's rows are copies of its template with {@code {name}} numbered
     * {@code {name_3}} (and {@code {name_field}} as {@code {name_3_field}}), so a
     * command in a list is allowed once per row it is drawn in.
     */
    public static Set<String> commands(Map<String, Object> spec) {
        Set<String> out = new HashSet<>();
        if (spec == null) return out;
        collect(spec, out);
        Map<String, Integer> lists = new LinkedHashMap<>();
        lists(spec, lists);
        for (Map.Entry<String, Integer> list : lists.entrySet()) {
            Pattern p = Pattern.compile("\\{" + Pattern.quote(list.getKey()) + "((?:_[A-Za-z0-9_]*)?)(\\?[^{}]*)?\\}");
            for (String c : List.copyOf(out)) {
                if (!p.matcher(c).find()) continue;
                for (int k = 1; k <= list.getValue(); k++) {
                    String row = "{" + list.getKey() + "_" + k;
                    out.add(p.matcher(c).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(row + m.group(1) + (m.group(2) == null ? "" : m.group(2)) + "}")));
                }
            }
        }
        return out;
    }

    /** Every list in the document, by name, with how many rows it draws. */
    private static void lists(Object value, Map<String, Integer> out) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("list") instanceof String name && name.matches("[A-Za-z0-9_]+") && map.get("rows") instanceof Number rows) {
                out.merge(name, Math.min(64, Math.max(0, rows.intValue())), Math::max);
            }
            for (Object v : map.values()) lists(v, out);
        } else if (value instanceof List<?> list) {
            for (Object v : list) lists(v, out);
        }
    }

    private static void collect(Object value, Set<String> out) {
        if (value instanceof String s) {
            String c = commandText(s);
            if (!c.isEmpty()) out.add(c);
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
                if (!DrawnDialogs.ENGINE_CLICK.matcher(command).matches() && !allowed.contains(commandText(command))) out.add(command);
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
