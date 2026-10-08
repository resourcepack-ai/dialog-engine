package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.drawn.DialogBars;
import ai.resourcepack.dialogs.drawn.DialogPackets;
import ai.resourcepack.dialogs.drawn.DialogPlaceholders;
import ai.resourcepack.dialogs.drawn.DialogRows;
import ai.resourcepack.dialogs.drawn.DialogSnbt;
import ai.resourcepack.dialogs.drawn.DialogVariables;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drawn dialogs: dialogs made in Studio's dialog editor ({@code /de edit}),
 * kept in {@code drawn/<namespace>/<name>/} and drawn by a resource pack
 * ({@link DrawnPack}).
 *
 * <p>They are run by RP Engine's own dialog code, compiled into this plugin
 * (the {@code drawn} package): the same placeholder and choice pass, the same
 * list rows, bars, heads and packet route. What is this plugin's is where they
 * are kept and the clicks they make. A drawn dialog's page turns and bound
 * controls click {@code /rp page} and {@code /rp var}, which are RP Engine's
 * commands; they are rewritten to {@code /dialogclick} as each dialog loads,
 * and that command honours a click only when the dialog the player was last
 * shown holds exactly it, as RP Engine's does.
 */
final class DrawnDialogs {

    /** The command every drawn dialog's clicks run, as the player. */
    static final String CLICK = "dialogclick";

    private static final Gson GSON = new Gson();
    private static final Pattern ENGINE_CLICK = Pattern.compile("\"(/?)(?:rp|rpe|rpengine) (page|var|slot) ");

    /** One drawn dialog: its pages by Studio's id for each, the first first. */
    record Drawn(String id, File dir, String first, Map<String, String> pages, Map<String, Set<String>> vars,
                 String command, String permission) {}

    private record Shown(String drawn, String page, Map<String, String> values) {}

    private final Engine engine;
    private DialogVariables variables;
    private final Map<String, Drawn> dialogs = new LinkedHashMap<>();
    private final Map<UUID, Shown> lastShown = new ConcurrentHashMap<>();
    private final List<Command> commands = new ArrayList<>();
    final List<String> problems = new ArrayList<>();

    DrawnDialogs(Engine engine) {
        this.engine = engine;
    }

    /** Players' settings, kept on the player; made on first use, once the engine has its plugin. */
    private DialogVariables variables() {
        if (variables == null) variables = new DialogVariables(engine.plugin());
        return variables;
    }

    File folder() {
        return new File(engine.plugin().getDataFolder(), "drawn");
    }

    // ------------------------------------------------------------------ loading

    void load() {
        dialogs.clear();
        problems.clear();
        File[] namespaces = folder().listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File ns : namespaces) {
                File[] names = ns.listFiles(File::isDirectory);
                if (names == null) continue;
                for (File dir : names) {
                    String id = ns.getName().toLowerCase(Locale.ROOT) + ":" + dir.getName().toLowerCase(Locale.ROOT);
                    try {
                        Drawn d = read(id, dir);
                        if (d != null) dialogs.put(id, d);
                    } catch (Exception e) {
                        problems.add("drawn/" + ns.getName() + "/" + dir.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        registerCommands();
    }

    private Drawn read(String id, File dir) throws Exception {
        File built = new File(dir, "dialogs.json");
        if (!built.isFile()) return null;
        JsonArray list = JsonParser.parseString(Files.readString(built.toPath(), StandardCharsets.UTF_8)).getAsJsonArray();
        Map<String, String> pages = new LinkedHashMap<>();
        Map<String, Set<String>> vars = new LinkedHashMap<>();
        String first = null;
        for (JsonElement e : list) {
            JsonObject page = e.getAsJsonObject();
            String studioId = "studio:" + page.get("id").getAsString();
            if (first == null) first = studioId;
            pages.put(studioId, rewrite(GSON.toJson(page.get("json"))));
            if (page.has("vars")) {
                for (JsonElement v : page.getAsJsonArray("vars")) {
                    Set<String> values = vars.computeIfAbsent(v.getAsJsonObject().get("name").getAsString(), k -> new LinkedHashSet<>());
                    for (JsonElement value : v.getAsJsonObject().getAsJsonArray("values")) values.add(value.getAsString());
                }
            }
        }
        if (first == null) return null;
        String command = null;
        String permission = null;
        File specFile = new File(dir, "spec.json");
        if (specFile.isFile()) {
            JsonObject spec = JsonParser.parseString(Files.readString(specFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (spec.has("command") && !spec.get("command").getAsString().isBlank()) {
                command = spec.get("command").getAsString().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            }
            if (spec.has("commandPermission") && !spec.get("commandPermission").getAsString().isBlank()) {
                permission = spec.get("commandPermission").getAsString();
            }
        }
        return new Drawn(id, dir, first, pages, vars, command, permission);
    }

    /** RP Engine's click commands, as this plugin's. */
    static String rewrite(String json) {
        Matcher m = ENGINE_CLICK.matcher(json);
        return m.replaceAll(r -> Matcher.quoteReplacement("\"" + r.group(1) + CLICK + " " + r.group(2) + " "));
    }

    boolean has(String id) {
        return dialogs.containsKey(id.toLowerCase(Locale.ROOT));
    }

    Set<String> ids() {
        return dialogs.keySet();
    }

    Optional<Drawn> get(String id) {
        return Optional.ofNullable(dialogs.get(id.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ showing

    boolean open(Player player, String id, Map<String, String> values) {
        Drawn d = dialogs.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        return show(player, d, d.first, values == null ? Map.of() : values);
    }

    private boolean show(Player player, Drawn d, String page, Map<String, String> values) {
        String json = d.pages.get(page);
        if (json == null) return false;
        json = filled(player, d, json, values);
        json = DialogBars.fill(json);
        lastShown.put(player.getUniqueId(), new Shown(d.id, page, Map.copyOf(values)));
        if (DialogPackets.show(player, json)) return true;
        Optional<String> inline = DialogSnbt.of(json);
        if (inline.isPresent()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:dialog show " + player.getName() + " " + inline.get());
        }
        engine.plugin().getLogger().warning("Could not show drawn dialog " + d.id + ": " + DialogPackets.lastRefusal());
        return false;
    }

    /** The placeholder pass: what it was opened with, the player's settings, the engine's own values. */
    private String filled(Player player, Drawn d, String json, Map<String, String> values) {
        boolean placeholders = DialogPlaceholders.any(json);
        boolean rows = DialogRows.any(json);
        if (!placeholders && !rows) return json;
        Map<String, String> given = new HashMap<>();
        values.forEach((k, v) -> given.put(k.toLowerCase(Locale.ROOT), v));
        Scope scope = new Scope(engine, player, d.id, given, Set.of(), null, null);
        Function<String, Optional<String>> direct = name -> {
            String mine = given.get(name.toLowerCase(Locale.ROOT));
            if (mine != null) return Optional.of(mine);
            Optional<String> stored = variables().get(player, name);
            if (stored.isPresent()) return stored;
            return Optional.ofNullable(scope.lookup(name));
        };
        Function<String, Optional<String>> lookup = name -> {
            Optional<String> found = direct.apply(name);
            return found.isPresent() ? found : DialogRows.item(name, given, direct);
        };
        String out = placeholders ? DialogPlaceholders.fill(json, lookup) : json;
        return rows ? DialogRows.strip(out, lookup) : out;
    }

    // ------------------------------------------------------------------ clicks

    /** {@code /dialogclick page <id|close>} and {@code /dialogclick var <name> <value> [page]}. */
    void click(Player player, String[] args) {
        if (args.length == 0) return;
        Shown shown = lastShown.get(player.getUniqueId());
        Drawn d = shown == null ? null : dialogs.get(shown.drawn);
        String command = CLICK + " " + String.join(" ", args);
        if (args[0].equalsIgnoreCase("page") && args.length >= 2 && args[1].equalsIgnoreCase("close")) {
            player.closeDialog();
            return;
        }
        if (d == null || !holds(d.pages.get(shown.page), command)) return;
        if (args[0].equalsIgnoreCase("page") && args.length == 2) {
            String page = args[1].toLowerCase(Locale.ROOT);
            Bukkit.getScheduler().runTask(engine.plugin(), () -> show(player, d, page, shown.values));
        } else if (args[0].equalsIgnoreCase("var") && args.length >= 3) {
            String name = args[1].toLowerCase(Locale.ROOT);
            String value = args[2];
            if (!d.vars.getOrDefault(name, Set.of()).contains(value)) return;
            variables().set(player, name, value);
            String page = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : shown.page;
            Bukkit.getScheduler().runTask(engine.plugin(), () -> show(player, d, page, shown.values));
        }
    }

    /** Whether a page's JSON holds a click running exactly {@code command}. */
    private static boolean holds(String json, String command) {
        if (json == null) return false;
        String quoted = GSON.toJson(command);
        String slashed = GSON.toJson("/" + command);
        return json.contains("\"command\":" + quoted) || json.contains("\"command\":" + slashed);
    }

    void forget(Player player) {
        lastShown.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        var map = Bukkit.getCommandMap();
        for (Command c : commands) c.unregister(map);
        commands.clear();
        for (Drawn d : dialogs.values()) {
            if (d.command == null || !d.command.matches("[a-z0-9_\\-]+")) continue;
            Command cmd = new Command(d.command) {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Only a player can open a dialog.");
                        return true;
                    }
                    if (d.permission != null && !p.hasPermission(d.permission)) {
                        p.sendMessage(Text.MM.deserialize("<red>You cannot open that."));
                        return true;
                    }
                    Map<String, String> values = new LinkedHashMap<>();
                    if (args.length > 0) values.put("target", args[0]);
                    open(p, d.id, values);
                    return true;
                }
            };
            map.register("dialogengine", cmd);
            commands.add(cmd);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    void shutdown() {
        var map = Bukkit.getCommandMap();
        for (Command c : commands) c.unregister(map);
        commands.clear();
    }
}
