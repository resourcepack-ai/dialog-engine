package ai.resourcepack.dialogs.core.yaml;

import ai.resourcepack.dialogs.core.click.Clicks;
import ai.resourcepack.dialogs.core.yaml.Defs.BodyDef;
import ai.resourcepack.dialogs.core.yaml.Defs.BodyKind;
import ai.resourcepack.dialogs.core.yaml.Defs.ButtonDef;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Defs.ForEach;
import ai.resourcepack.dialogs.core.yaml.Defs.InputDef;
import ai.resourcepack.dialogs.core.yaml.Defs.InputKind;
import ai.resourcepack.dialogs.core.yaml.Defs.OptionDef;
import ai.resourcepack.dialogs.core.yaml.Defs.Type;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads dialog YAML into {@link DialogDef}s. Lenient where it can be (a
 * misspelt key is ignored, a bad number falls back to the default) and loud
 * where it cannot: each problem is a line in {@link #problems}, which
 * {@code /de reload} prints.
 */
public final class Loader {

    static final Pattern NAME = Pattern.compile("[a-z0-9_.\\-/]+");
    static final Pattern COMMAND = Pattern.compile("[a-z0-9_\\-]{1,32}");

    public final List<String> problems = new ArrayList<>();
    private final Defs.After defaultAfter;

    public Loader(Defs.After defaultAfter) {
        this.defaultAfter = defaultAfter;
    }

    /** Every .yml under a folder, recursively, as dialogs in {@code namespace}. */
    public Map<String, DialogDef> loadFolder(String namespace, File folder) {
        Map<String, DialogDef> out = new LinkedHashMap<>();
        if (!folder.isDirectory()) return out;
        walk(namespace, folder, folder, out);
        return out;
    }

    private void walk(String namespace, File root, File dir, Map<String, DialogDef> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File file : files) {
            if (file.isDirectory()) {
                walk(namespace, root, file, out);
            } else if (file.getName().endsWith(".yml") || file.getName().endsWith(".yaml")) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file);
                } catch (Exception e) {
                    problems.add(root.getName() + "/" + root.toPath().relativize(file.toPath()) + ": " + firstLine(e.getMessage()));
                    continue;
                }
                String where = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                for (String key : yaml.getKeys(false)) {
                    ConfigurationSection section = yaml.getConfigurationSection(key);
                    if (section == null) continue;
                    String id = namespace + ":" + key.toLowerCase(Locale.ROOT);
                    DialogDef def = parse(id, namespace, where, section, file.getParentFile());
                    if (def == null) continue;
                    if (out.containsKey(id)) problems.add(where + ": " + id + " is defined twice; the later one wins");
                    out.put(id, def);
                }
            }
        }
    }

    public DialogDef parse(String id, String namespace, String source, ConfigurationSection s, File baseDir) {
        String name = id.substring(id.indexOf(':') + 1);
        if (!NAME.matcher(name).matches()) {
            problems.add(source + ": '" + name + "' is not a usable dialog name (a-z, 0-9, _ . - only)");
            return null;
        }
        String json = null;
        if (s.isString("json")) {
            File f = baseDir == null ? null : new File(baseDir, s.getString("json"));
            if (f == null || !f.isFile()) {
                problems.add(source + ": " + id + " names json file " + s.getString("json") + ", which is not there");
                return null;
            }
            json = f.getPath();
        }

        List<ButtonDef> buttons = new ArrayList<>();
        Type type = type(s.getString("type", "auto"), source, id);
        // confirm: and cancel: are the two buttons of a confirmation. yes: and no:
        // are accepted too, but YAML reads those keys as true and false, so look
        // for them under that name as well.
        ConfigurationSection yes = first(s, "confirm", "yes", "true");
        ConfigurationSection no = first(s, "cancel", "no", "false");
        if (yes != null || no != null) {
            if (yes != null) buttons.add(button(yes, "confirm", source, id));
            if (no != null) buttons.add(button(no, "cancel", source, id));
            if (type == Type.AUTO) type = Type.CONFIRMATION;
        }
        int i = 0;
        for (Map<?, ?> raw : s.getMapList("buttons")) {
            buttons.add(button(section(raw), String.valueOf(i++), source, id));
        }
        ButtonDef exit = s.isConfigurationSection("exit") ? button(s.getConfigurationSection("exit"), "exit", source, id) : null;
        if (exit != null && exit.validate()) {
            // Escape runs the exit action too: it must never be held up by an input check.
            exit = new ButtonDef(exit.id(), exit.label(), exit.tooltip(), exit.width(), exit.permission(), exit.condition(),
                    exit.forEach(), exit.actions(), s.getConfigurationSection("exit").getBoolean("validate", false));
        }

        List<BodyDef> body = new ArrayList<>();
        for (Object raw : s.getList("body", List.of())) {
            BodyDef b = body(raw, source, id);
            if (b != null) body.add(b);
        }

        List<InputDef> inputs = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("inputs")) {
            InputDef in = input(section(raw), source, id);
            if (in != null) inputs.add(in);
        }

        Map<String, List<Map<String, String>>> lists = new LinkedHashMap<>();
        ConfigurationSection listSection = s.getConfigurationSection("lists");
        if (listSection != null) {
            for (String list : listSection.getKeys(false)) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (Object row : listSection.getList(list, List.of())) {
                    Map<String, String> r = new LinkedHashMap<>();
                    if (row instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> r.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                    } else {
                        r.put("value", String.valueOf(row));
                    }
                    rows.add(r);
                }
                lists.put(list, rows);
            }
        }

        Map<String, String> defaults = new LinkedHashMap<>();
        ConfigurationSection defaultSection = s.getConfigurationSection("defaults");
        if (defaultSection != null) {
            for (String k : defaultSection.getKeys(false)) defaults.put(k, String.valueOf(defaultSection.get(k)));
        }

        String command = s.getString("command");
        if (command != null) {
            command = command.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            if (!COMMAND.matcher(command).matches()) {
                problems.add(source + ": " + id + " command '" + command + "' must be one word of a-z, 0-9, _ and -");
                command = null;
            }
        }
        List<String> aliases = new ArrayList<>();
        for (String alias : s.getStringList("aliases")) {
            String a = alias.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            if (COMMAND.matcher(a).matches()) aliases.add(a);
        }

        Defs.After after = after(s.getString("after"), source, id);
        boolean pause = s.getBoolean("pause", false);
        if (pause && after == Defs.After.NONE) {
            problems.add(source + ": " + id + " cannot pause with after: none (the game refuses it); pause is off");
            pause = false;
        }
        // Seamless is none on the wire, which the game will not pause under. Not
        // said: it is the default, so it would be said of every pausing dialog,
        // and a server's game does not pause anyway.
        if (after == Defs.After.SEAMLESS) pause = false;

        String ifMissing = s.getString("if-missing");
        if (ifMissing != null && !ifMissing.contains(":")) ifMissing = namespace + ":" + ifMissing;

        return new DialogDef(id, namespace, source,
                s.getString("title", ""),
                s.getString("external-title"),
                type,
                Math.max(1, s.getInt("columns", 2)),
                s.getBoolean("escape", s.getBoolean("can-close-with-escape", true)),
                pause,
                after,
                List.copyOf(body),
                List.copyOf(inputs),
                List.copyOf(buttons),
                exit,
                Collections.unmodifiableMap(lists),
                command,
                List.copyOf(aliases),
                s.getString("permission"),
                List.copyOf(s.getStringList("args")),
                ifMissing,
                json,
                Collections.unmodifiableMap(defaults),
                quickAction(s, source, id));
    }

    /**
     * {@code quick-action: true} (or {@code direct}), {@code launcher}, or a
     * section with {@code mode:} and {@code label:}. Absent or false: not on
     * the key.
     */
    private Defs.QuickAction quickAction(ConfigurationSection s, String source, String id) {
        String mode;
        String label = null;
        if (s.isConfigurationSection("quick-action")) {
            ConfigurationSection q = s.getConfigurationSection("quick-action");
            mode = q.getString("mode", "direct");
            label = q.getString("label");
        } else if (s.isBoolean("quick-action")) {
            return s.getBoolean("quick-action") ? new Defs.QuickAction(false, null) : null;
        } else {
            mode = s.getString("quick-action");
            if (mode == null) return null;
        }
        return switch (mode.toLowerCase(Locale.ROOT).trim()) {
            case "direct", "true", "yes", "on" -> new Defs.QuickAction(false, label);
            case "launcher" -> new Defs.QuickAction(true, label);
            case "false", "no", "off", "" -> null;
            default -> {
                problems.add(source + ": " + id + " quick-action '" + mode + "' is not direct or launcher; it is direct");
                yield new Defs.QuickAction(false, label);
            }
        };
    }

    private static ConfigurationSection first(ConfigurationSection s, String... keys) {
        for (String k : keys) {
            if (s.isConfigurationSection(k)) return s.getConfigurationSection(k);
        }
        return null;
    }

    private Type type(String raw, String source, String id) {
        return switch (raw.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "auto" -> Type.AUTO;
            case "notice" -> Type.NOTICE;
            case "confirmation", "confirm" -> Type.CONFIRMATION;
            case "multi_action", "multi", "menu" -> Type.MULTI_ACTION;
            case "links", "server_links" -> Type.LINKS;
            default -> {
                problems.add(source + ": " + id + " has unknown type '" + raw + "'; using auto");
                yield Type.AUTO;
            }
        };
    }

    private Defs.After after(String raw, String source, String id) {
        if (raw == null) return defaultAfter;
        Defs.After after = Defs.After.parse(raw);
        if (after == null) {
            problems.add(source + ": " + id + " has unknown after '" + raw + "'");
            return defaultAfter;
        }
        return after;
    }

    private ButtonDef button(ConfigurationSection b, String fallbackId, String source, String id) {
        List<String> actions = new ArrayList<>();
        if (b.isList("actions")) actions.addAll(b.getStringList("actions"));
        else if (b.isString("actions")) actions.add(b.getString("actions"));
        if (b.isString("action")) actions.add(b.getString("action"));
        shorthand(b, "command", "player", actions);
        shorthand(b, "console", "console", actions);
        shorthand(b, "open", "open", actions);
        shorthand(b, "url", "url", actions);
        shorthand(b, "copy", "copy", actions);
        shorthand(b, "suggest", "suggest", actions);
        shorthand(b, "message", "message", actions);
        if (b.getBoolean("close", false)) actions.add("[close]");
        if (b.getBoolean("back", false)) actions.add("[back]");
        int width = b.getInt("width", 150);
        if (width < 1 || width > 1024) {
            problems.add(source + ": " + id + " button width must be 1-1024");
            width = Math.max(1, Math.min(1024, width));
        }
        return new ButtonDef(
                b.getString("id", fallbackId),
                b.getString("label", b.getString("text", "Ok")),
                b.getString("tooltip"),
                width,
                b.getString("permission"),
                condition(b),
                forEach(b),
                List.copyOf(actions),
                b.getBoolean("validate", true));
    }

    private static void shorthand(ConfigurationSection b, String key, String tag, List<String> actions) {
        if (b.isList(key)) {
            for (String v : b.getStringList(key)) actions.add("[" + tag + "] " + v);
        } else if (b.isString(key)) {
            actions.add("[" + tag + "] " + b.getString(key));
        }
    }

    private BodyDef body(Object raw, String source, String id) {
        if (raw instanceof String text) {
            return new BodyDef(BodyKind.TEXT, text, 200, null, null, null, List.of(), null, Defs.ModelData.NONE, 1, false, true, true, 16, 16, null, null, null);
        }
        if (!(raw instanceof Map<?, ?> map)) return null;
        ConfigurationSection b = section(map);
        if (b.contains("item") || b.contains("head")) {
            return new BodyDef(BodyKind.ITEM, null, 200,
                    b.getString("item", b.contains("head") ? "player_head" : "paper"),
                    b.getString("head"),
                    b.getString("name"),
                    b.getStringList("lore"),
                    b.getString("model"),
                    modelData(b),
                    Math.max(1, Math.min(99, b.getInt("amount", 1))),
                    b.getBoolean("glow", false),
                    b.getBoolean("tooltip", true),
                    b.getBoolean("decorations", true),
                    clamp(b.getInt("width", 16), 1, 256),
                    clamp(b.getInt("height", 16), 1, 256),
                    joined(b, "description"),
                    condition(b),
                    forEach(b));
        }
        String text = joined(b, "text");
        if (text == null) {
            problems.add(source + ": " + id + " has a body entry with neither text: nor item:");
            return null;
        }
        return new BodyDef(BodyKind.TEXT, text, clamp(b.getInt("width", 200), 1, 1024), null, null, null, List.of(), null, Defs.ModelData.NONE,
                1, false, true, true, 16, 16, null, condition(b), forEach(b));
    }

    private InputDef input(ConfigurationSection in, String source, String id) {
        String key = in.getString("key", in.getString("id"));
        if (key == null || !key.matches("[A-Za-z0-9_]+")) {
            problems.add(source + ": " + id + " has an input without a usable key (letters, digits and _)");
            return null;
        }
        if (key.equalsIgnoreCase(Clicks.TOKEN)) {
            problems.add(source + ": " + id + " has an input called " + key + ", which Dialog Engine keeps for itself");
            return null;
        }
        String rawType = in.getString("type", "text").toLowerCase(Locale.ROOT).replace('-', '_');
        InputKind kind = switch (rawType) {
            case "text", "string", "input" -> InputKind.TEXT;
            case "options", "option", "single_option", "dropdown", "choice", "select" -> InputKind.OPTIONS;
            case "toggle", "bool", "boolean", "checkbox", "switch" -> InputKind.TOGGLE;
            case "slider", "number", "range", "number_range" -> InputKind.SLIDER;
            default -> {
                problems.add(source + ": " + id + " input " + key + " has unknown type '" + rawType + "'; using text");
                yield InputKind.TEXT;
            }
        };
        List<OptionDef> options = new ArrayList<>();
        ForEach optionsForEach = null;
        OptionDef template = null;
        if (in.isConfigurationSection("options")) {
            ConfigurationSection o = in.getConfigurationSection("options");
            optionsForEach = forEach(o);
            template = new OptionDef(o.getString("id", "{row.id}"), o.getString("text", "{row.text}"), o.getString("initial"));
            if (optionsForEach == null) problems.add(source + ": " + id + " input " + key + " options need for-each: or a list");
        } else {
            for (Object raw : in.getList("options", List.of())) {
                if (raw instanceof Map<?, ?> m) {
                    ConfigurationSection o = section(m);
                    if (o.contains("id")) {
                        options.add(new OptionDef(o.getString("id"), o.getString("text", o.getString("id")),
                                String.valueOf(o.get("initial", "false"))));
                    } else if (m.size() == 1) {
                        // "- 1h: One hour"
                        Map.Entry<?, ?> e = m.entrySet().iterator().next();
                        options.add(new OptionDef(String.valueOf(e.getKey()), String.valueOf(e.getValue()), "false"));
                    }
                } else if (raw != null) {
                    options.add(new OptionDef(String.valueOf(raw), String.valueOf(raw), "false"));
                }
            }
            if (kind == InputKind.OPTIONS && options.isEmpty()) {
                problems.add(source + ": " + id + " input " + key + " has no options");
                return null;
            }
        }
        float min = (float) in.getDouble("min", 0);
        float max = (float) in.getDouble("max", 100);
        if (kind == InputKind.SLIDER && max <= min) {
            problems.add(source + ": " + id + " input " + key + " needs max above min");
            max = min + 1;
        }
        return new InputDef(
                key,
                kind,
                in.getString("label", ""),
                in.getBoolean("label-visible", true),
                clamp(in.getInt("width", 200), 1, 1024),
                in.contains("initial") ? String.valueOf(in.get("initial")) : null,
                Math.max(1, in.getInt("max-length", 256)),
                Math.max(0, in.getInt("lines", 0)),
                clamp(in.getInt("height", 0), 0, 512),
                List.copyOf(options),
                optionsForEach,
                template,
                in.getString("on-true"),
                in.getString("on-false"),
                min,
                max,
                in.contains("step") ? (float) in.getDouble("step") : null,
                in.getString("format", "options.generic_value"),
                in.getBoolean("required", false),
                Math.max(0, in.getInt("min-length", 0)),
                in.getString("pattern"),
                in.getString("error"),
                condition(in));
    }

    private static String condition(ConfigurationSection s) {
        String c = s.getString("if", s.getString("condition"));
        return c == null || c.isBlank() ? null : c;
    }

    private static ForEach forEach(ConfigurationSection s) {
        String list = s.getString("for-each", s.getString("foreach"));
        if (list == null || list.isBlank()) return null;
        return new ForEach(list.trim(), s.getString("as", "row"), Math.max(0, s.getInt("per-page", 0)), Math.max(0, s.getInt("limit", 0)));
    }

    /**
     * An item's {@code custom-model-data:}: a value or a list of them (each a
     * float, a flag or a string by what it says), or the game's own lists by
     * name — {@code floats}, {@code flags}, {@code strings}, {@code colors}.
     */
    static Defs.ModelData modelData(ConfigurationSection b) {
        String key = "custom-model-data";
        if (!b.contains(key)) return Defs.ModelData.NONE;
        if (b.isConfigurationSection(key)) {
            ConfigurationSection d = b.getConfigurationSection(key);
            return new Defs.ModelData(List.of(), texts(d, "floats"), texts(d, "flags"), texts(d, "strings"), texts(d, "colors"));
        }
        return new Defs.ModelData(texts(b, key), List.of(), List.of(), List.of(), List.of());
    }

    /** A key's values as text, one or a list of them: a number, a word or a {placeholder} alike. */
    private static List<String> texts(ConfigurationSection s, String key) {
        if (s.isList(key)) {
            List<?> values = s.getList(key);
            return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        Object v = s.get(key);
        return v == null || v instanceof ConfigurationSection ? List.of() : List.of(String.valueOf(v));
    }

    private static String joined(ConfigurationSection s, String key) {
        if (s.isList(key)) return String.join("\n", s.getStringList(key));
        return s.getString(key);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static ConfigurationSection section(Map<?, ?> map) {
        YamlConfiguration yaml = new YamlConfiguration();
        map.forEach((k, v) -> {
            if (v instanceof Map<?, ?> nested) yaml.createSection(String.valueOf(k), nested);
            else yaml.set(String.valueOf(k), v);
        });
        return yaml;
    }

    private static String firstLine(String s) {
        if (s == null) return "unreadable";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
