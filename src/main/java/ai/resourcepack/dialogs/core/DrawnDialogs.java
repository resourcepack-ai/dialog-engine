package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.core.Defs.DialogDef;
import ai.resourcepack.dialogs.drawn.DialogBars;
import ai.resourcepack.dialogs.drawn.DialogPlaceholders;
import ai.resourcepack.dialogs.drawn.DialogRows;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
 *
 * <p>Every OTHER click a drawn dialog makes is a Dialog Engine action list,
 * exactly as a YAML button's {@code actions:}: the command Studio wrote is
 * taken out of the dialog as it loads, kept here, and replaced by
 * {@code /dialogclick act <n>}, sent with a nonce drawn for each showing and
 * good for one click, so typing one into chat does nothing. Only a command
 * the dialog's {@code dialog.yml} has is kept: Studio drew {@code dialogs.json},
 * and it does not get to add one. So {@code [console]}, {@code [open]},
 * {@code [message]} and the rest work on a drawn dialog, placeholders are
 * filled when it is clicked rather than baked into what the player holds, and
 * a drawn dialog is a session like any other ({@code [back]}, the API's events).
 * Several actions in one command are written {@code [a] ...; [b] ...}.
 */
final class DrawnDialogs {

    /** The command every drawn dialog's clicks run, as the player. */
    static final String CLICK = "dialogclick";

    private static final Gson GSON = new Gson();
    static final Pattern ENGINE_CLICK = Pattern.compile("^(/?)(?:rp|rpe|rpengine) (page|var|slot) (.*)$");
    private static final Pattern NEXT_ACTION = Pattern.compile(";\\s*(?=\\[)");
    /** An action click as it is kept, which {@link #showPage} gives the drawing's nonce. */
    private static final Pattern ACT_CLICK = Pattern.compile("(\"command\":\"/?" + CLICK + " act \\d+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One drawn dialog: its pages by Studio's id for each, the first first, the
     * action lists its clicks run, and which pages stay up when clicked
     * ({@code after_action: none}).
     */
    record Drawn(String id, File dir, String first, Map<String, String> pages, Map<String, Set<String>> vars,
                 String command, String permission, List<List<String>> actions, Set<String> staysUp, DialogDef def) {}

    /**
     * The drawing a player was last shown. {@code nonce} is what its action
     * clicks carry, good for one of them (null once used); {@code permitted}
     * says the player held the dialog's permission when it was drawn, so losing
     * it since stops the clicks; {@code live} is false once the dialog has gone
     * from their screen, when it is kept only to reopen on the same page.
     */
    private record Shown(String drawn, String page, Session session, String nonce, boolean permitted, long at, boolean live) {
        Shown used() {
            return new Shown(drawn, page, session, null, permitted, at, live);
        }

        Shown retired() {
            return new Shown(drawn, page, session, null, permitted, at, false);
        }
    }

    private final Engine engine;
    private DialogVariables variables;
    private final Map<String, Drawn> dialogs = new LinkedHashMap<>();
    private final Map<UUID, Shown> lastShown = new ConcurrentHashMap<>();
    private final List<Command> commands = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    /** Drawn dialogs whose dialog.yml was edited since they were drawn. */
    private final Map<String, File> stale = new LinkedHashMap<>();
    private final Set<String> redrawing = ConcurrentHashMap.newKeySet();
    /** Who asked for the reload that found them, told when they are redrawn. */
    private CommandSender notify;

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
        stale.clear();
        File[] namespaces = folder().listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File ns : namespaces) {
                File[] names = ns.listFiles(File::isDirectory);
                if (names == null) continue;
                for (File dir : names) {
                    String id = ns.getName().toLowerCase(Locale.ROOT) + ":" + dir.getName().toLowerCase(Locale.ROOT);
                    try {
                        DrawnFiles.read(dir.toPath());
                        if (DrawnFiles.stale(dir.toPath())) stale.put(id, dir);
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
        Map<String, Object> spec = DrawnFiles.read(dir.toPath());
        // Only a command dialog.yml itself has may run: dialogs.json is Studio's
        // answer, and an answer that came over the network does not get to add one.
        Set<String> allowed = DrawnFiles.commands(spec);
        Map<String, String> pages = new LinkedHashMap<>();
        Map<String, Set<String>> vars = new LinkedHashMap<>();
        Set<String> staysUp = new LinkedHashSet<>();
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        String first = null;
        for (JsonElement e : list) {
            JsonObject page = e.getAsJsonObject();
            String studioId = "studio:" + page.get("id").getAsString();
            if (first == null) first = studioId;
            JsonElement json = page.get("json");
            if (json instanceof JsonObject o && o.get("after_action") instanceof JsonElement a && a.isJsonPrimitive()
                    && "none".equals(a.getAsString())) {
                staysUp.add(studioId);
            }
            pages.put(studioId, GSON.toJson(rewrite(json, actions, allowed, refused)));
            if (page.has("vars")) {
                for (JsonElement v : page.getAsJsonArray("vars")) {
                    Set<String> values = vars.computeIfAbsent(v.getAsJsonObject().get("name").getAsString(), k -> new LinkedHashSet<>());
                    for (JsonElement value : v.getAsJsonObject().getAsJsonArray("values")) values.add(value.getAsString());
                }
            }
        }
        if (first == null) return null;
        for (String r : refused) {
            problems.add("drawn/" + id.replace(':', '/') + ": a click runs \"" + r + "\", which its " + DrawnFiles.SOURCE
                    + " does not have; that click does nothing");
        }
        String command = null;
        String permission = null;
        if (spec != null) {
            if (spec.get("command") instanceof String c && !c.isBlank()) command = c.replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            if (spec.get("commandPermission") instanceof String c && !c.isBlank()) permission = c;
        }
        String ns = id.substring(0, id.indexOf(':'));
        DialogDef def = new DialogDef(id, ns, "drawn/" + id.replace(':', '/') + "/" + DrawnFiles.SOURCE, id, null,
                Defs.Type.MULTI_ACTION, 1, true, false, Defs.After.NONE, List.of(), List.of(), List.of(), null,
                Map.of(), command, List.of(), permission, List.of(), null, null, Map.of());
        return new Drawn(id, dir, first, pages, vars, command, permission, List.copyOf(actions), Set.copyOf(staysUp), def);
    }

    /**
     * Every command a click runs, as this plugin's: RP Engine's page, var and
     * slot clicks as {@code /dialogclick page|var|slot}, and anything else as
     * {@code /dialogclick act <n>}, its actions kept in {@code actions}. A
     * command not in {@code allowed} (see {@link DrawnFiles#commands}) gets no
     * actions, and is named in {@code refused}.
     */
    static JsonElement rewrite(JsonElement e, List<List<String>> actions, Set<String> allowed, List<String> refused) {
        if (e instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) a.set(i, rewrite(a.get(i), actions, allowed, refused));
        } else if (e instanceof JsonObject o) {
            boolean runs = (o.has("action") && o.get("action").isJsonPrimitive() && "run_command".equals(o.get("action").getAsString()))
                    || (o.has("type") && o.get("type").isJsonPrimitive() && "minecraft:run_command".equals(o.get("type").getAsString()));
            if (runs && o.has("command") && o.get("command").isJsonPrimitive()) {
                String command = o.get("command").getAsString();
                Matcher m = ENGINE_CLICK.matcher(command);
                if (m.matches()) {
                    o.addProperty("command", m.group(1) + CLICK + " " + m.group(2) + " " + m.group(3));
                } else {
                    String slash = command.startsWith("/") ? "/" : "";
                    List<String> list = actions(command.substring(slash.length()));
                    if (!allowed.contains(DrawnFiles.commandText(command))) {
                        if (!refused.contains(command)) refused.add(command);
                        list = List.of();
                    }
                    int n = actions.indexOf(list);
                    if (n < 0) {
                        n = actions.size();
                        actions.add(list);
                    }
                    o.addProperty("command", slash + CLICK + " act " + n);
                }
            }
            for (String key : List.copyOf(o.keySet())) o.add(key, rewrite(o.get(key), actions, allowed, refused));
        }
        return e;
    }

    /** A click's command as actions: {@code [tag] ...; [tag] ...}, or one command the player runs. */
    static List<String> actions(String command) {
        String trimmed = command.trim();
        if (!trimmed.startsWith("[")) return List.of(trimmed);
        List<String> out = new ArrayList<>();
        for (String part : NEXT_ACTION.split(trimmed)) if (!part.isBlank()) out.add(part.trim());
        return List.copyOf(out);
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

    /** Opens one by id, as a session of its own: through the engine, so it is shown the way every dialog is. */
    boolean open(Player player, String id, Map<String, String> values, Set<String> untrusted) {
        Drawn d = dialogs.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        return engine.show(player, new Session(player.getUniqueId(), d.id, values == null ? Map.of() : values,
                untrusted == null ? Set.of() : untrusted, null), false);
    }

    /**
     * Shows a session's drawn dialog: on its first page, or (redrawn for the
     * same dialog) on the page the player was looking at.
     */
    boolean show(Player player, Session session, boolean reopened) {
        Drawn d = dialogs.get(session.dialogId);
        if (d == null) return false;
        Shown before = lastShown.get(player.getUniqueId());
        String page = reopened && before != null && before.drawn.equals(d.id) && d.pages.containsKey(before.page) ? before.page : d.first;
        return showPage(player, d, page, session);
    }

    private boolean showPage(Player player, Drawn d, String page, Session session) {
        String json = d.pages.get(page);
        if (json == null) return false;
        // This drawing's nonce, on every action click: a click typed into chat
        // without it does nothing, and with it works once.
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        json = ACT_CLICK.matcher(json).replaceAll("$1 " + nonce + "\"");
        json = filled(player, d, json, session.values);
        json = DialogBars.fill(json);
        boolean permitted = d.permission == null || player.hasPermission(d.permission);
        lastShown.put(player.getUniqueId(), new Shown(d.id, page, session, nonce, permitted, System.currentTimeMillis(), true));
        if (Platform.showJson(player, json, engine.plugin().getLogger())) return true;
        engine.plugin().getLogger().warning("Could not show drawn dialog " + d.id + ".");
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

    /**
     * {@code /dialogclick page <id|close>}, {@code var <name> <value> [page]} and
     * {@code act <n> <nonce>}. Anybody can type these, so each is honoured only
     * when the drawing on the player's screen holds exactly it, while that
     * drawing is younger than {@code click-lifetime-minutes}, and (when they
     * held it then) while they still hold the dialog's permission. An action
     * click also needs the drawing's nonce, and uses it up.
     */
    void click(Player player, String[] args) {
        if (args.length == 0 || !engine.allowClick(player)) return;
        UUID uuid = player.getUniqueId();
        Shown shown = lastShown.get(uuid);
        Drawn d = shown == null || !shown.live ? null : dialogs.get(shown.drawn);
        boolean act = args[0].equalsIgnoreCase("act");
        // An action click is held as written, without the nonce it was sent with.
        String command = CLICK + " " + String.join(" ", act && args.length == 3 ? Arrays.copyOf(args, 2) : args);
        String json = d == null ? null : d.pages.get(shown.page);
        if (args[0].equalsIgnoreCase("page") && args.length >= 2 && args[1].equalsIgnoreCase("close")) {
            if (picture(json, command)) clickSound(player);
            engine.closeDialog(player);
            return;
        }
        if (d == null || !holds(json, command)) return;
        if (System.currentTimeMillis() - shown.at > engine.clickLifetime().toMillis()) {
            retire(player);
            return;
        }
        if (shown.permitted && d.permission != null && !player.hasPermission(d.permission)) {
            Platform.send(player, Text.MM.deserialize(engine.message("no-permission", "<red>You can't do that.")));
            retire(player);
            return;
        }
        if (act) {
            if (args.length != 3 || shown.nonce == null
                    || !MessageDigest.isEqual(shown.nonce.getBytes(StandardCharsets.UTF_8), args[2].getBytes(StandardCharsets.UTF_8))
                    || !lastShown.replace(uuid, shown, shown.used())) {
                return;
            }
        }
        if (picture(json, command)) clickSound(player);
        if (args[0].equalsIgnoreCase("page") && args.length == 2) {
            String page = args[1].toLowerCase(Locale.ROOT);
            Bukkit.getScheduler().runTask(engine.plugin(), () -> showPage(player, d, page, shown.session));
        } else if (args[0].equalsIgnoreCase("var") && args.length >= 3) {
            String name = args[1].toLowerCase(Locale.ROOT);
            String value = args[2];
            if (!d.vars.getOrDefault(name, Set.of()).contains(value)) return;
            variables().set(player, name, value);
            String page = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : shown.page;
            Bukkit.getScheduler().runTask(engine.plugin(), () -> showPage(player, d, page, shown.session));
        } else if (act) {
            int n;
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                return;
            }
            if (n < 0 || n >= d.actions.size()) return;
            Bukkit.getScheduler().runTask(engine.plugin(), () -> {
                // The player's settings are values too, so an action can say {silent}.
                Map<String, String> values = new LinkedHashMap<>(shown.session.values);
                for (String var : d.vars.keySet()) variables().get(player, var).ifPresent(v -> values.putIfAbsent(var, v));
                Session session = shown.session.with(values, shown.session.untrusted);
                ClickCtx ctx = new ClickCtx(engine, player, d.def, session, "act_" + n, Map.of(), null, null);
                engine.run(ctx, d.actions.get(n));
                if (ctx.responded || !player.isOnline() || lastShown.get(uuid) == null || lastShown.get(uuid).nonce != null) return;
                // The nonce is spent. A page that stays up when clicked is drawn
                // again with a fresh one, so its next click works; any other
                // page closed itself on the click, and its drawing is over.
                if (d.staysUp.contains(shown.page)) showPage(player, d, shown.page, shown.session);
                else retire(player);
            });
        }
    }

    /**
     * Whether the click was on the picture: a run of the body's text, which the
     * game does not click for. A footer button is the game's own and does.
     */
    private static boolean picture(String json, String command) {
        if (json == null) return false;
        return json.contains("\"action\":\"run_command\",\"command\":" + GSON.toJson(command))
                || json.contains("\"action\":\"run_command\",\"command\":" + GSON.toJson("/" + command));
    }

    /** The game's own button click, which a click on the picture does not make by itself. */
    private void clickSound(Player player) {
        String sound = engine.plugin().getConfig().getString("click-sound", "ui.button.click");
        if (sound == null || sound.isBlank()) return;
        try {
            Platform.sound(player, sound.toLowerCase(Locale.ROOT), 0.25f, 1f);
        } catch (RuntimeException e) {
            // A sound name that is not a key: no sound, rather than no click.
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

    /**
     * The player's drawn dialog has gone from their screen (closed, or replaced
     * by another dialog): none of its clicks work any more. The page they were
     * on is still remembered, for {@code [back]}.
     */
    void retire(Player player) {
        lastShown.computeIfPresent(player.getUniqueId(), (k, s) -> s.live ? s.retired() : s);
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        var map = Platform.commandMap();
        // Out of the known commands too, or the last load's command (and the
        // permission it was built with) keeps the name until a restart.
        for (Command c : commands) OpenCommands.unregister(map, c);
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
                        Platform.send(p, Text.MM.deserialize("<red>You cannot open that."));
                        return true;
                    }
                    Map<String, String> values = new LinkedHashMap<>();
                    if (args.length > 0) values.put("target", args[0]);
                    open(p, d.id, values, values.keySet());
                    return true;
                }
            };
            map.register("dialogengine", cmd);
            commands.add(cmd);
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    // ------------------------------------------------------------------ redrawing

    /** How many edited drawn dialogs the last load found. */
    int staleCount() {
        return stale.size();
    }

    /** Tells {@code sender}, as well as the console, how the next redraw goes. */
    void notifyNext(CommandSender sender) {
        notify = sender;
    }

    /**
     * Has Studio redraw every drawn dialog whose {@code dialog.yml} was edited
     * by hand, then reloads once they are all back. Until then the old drawing
     * keeps showing.
     */
    void redrawStale() {
        CommandSender tell = notify != null ? notify : Bukkit.getConsoleSender();
        notify = null;
        Map<String, File> todo = new LinkedHashMap<>(stale);
        todo.keySet().removeIf(redrawing::contains);
        if (todo.isEmpty()) return;
        int[] left = {todo.size()};
        boolean[] any = {false};
        todo.forEach((id, dir) -> {
            redrawing.add(id);
            engine.edits().redraw(id, dir, error -> {
                redrawing.remove(id);
                if (error == null) {
                    any[0] = true;
                    say(tell, "Redrew <white>" + id + "</white> from its " + DrawnFiles.SOURCE + ".");
                } else {
                    say(tell, "<red>Could not redraw " + id + ": " + Text.MM.escapeTags(error) + ".</red> The old drawing stays until it can.");
                }
                if (--left[0] == 0 && any[0]) {
                    engine.reload();
                    engine.drawnPack.sendAll();
                }
            });
        });
    }

    private void say(CommandSender to, String mini) {
        // The console always hears it: whoever asked may have gone (an RCON reload has).
        engine.plugin().getLogger().info(Platform.plain(Text.MM.deserialize(mini)));
        if (to instanceof Player p && p.isOnline()) Platform.send(p, Text.MM.deserialize("<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>" + mini));
    }

    void shutdown() {
        var map = Platform.commandMap();
        for (Command c : commands) OpenCommands.unregister(map, c);
        commands.clear();
    }
}
