package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.common.quick.StandIn;
import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.Session;
import ai.resourcepack.dialogs.core.click.ClickCtx;
import ai.resourcepack.dialogs.core.command.OpenCommands;
import ai.resourcepack.dialogs.core.placeholder.Papi;
import ai.resourcepack.dialogs.core.placeholder.Scope;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Defs;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
 * (copied into this package by the build): the same placeholder and choice
 * pass, the same list rows, bars, heads and packet route. What is this
 * plugin's is where they are kept and the clicks they make. A drawn dialog's
 * page turns and bound controls click {@code /rp page} and {@code /rp var}, which are RP Engine's
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
public final class DrawnDialogs {

    /** The command every drawn dialog's clicks run, as the player. */
    static final String CLICK = "dialogclick";

    private static final Gson GSON = new Gson();
    static final Pattern ENGINE_CLICK = Pattern.compile("^(/?)(?:rp|rpe|rpengine) (page|var|slot) (.*)$");
    /** A "Run as console" click, as Studio writes it: {@code rp click <key>}, the command kept beside the dialog. */
    static final Pattern CONSOLE_CLICK = Pattern.compile("^/?(?:rp|rpe|rpengine) click ([0-9a-f]{8})$");
    private static final Pattern NEXT_ACTION = Pattern.compile(";\\s*(?=\\[)");
    /** An action click as it is kept, which {@link #showPage} gives the drawing's nonce. */
    private static final Pattern ACT_CLICK = Pattern.compile("(\"command\":\"/?" + CLICK + " act \\d+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One drawn dialog: its pages by Studio's id for each, the first first, the
     * action lists its clicks run, which pages stay up when clicked
     * ({@code after_action: none}), which wait for the server to answer
     * ({@code after_action: wait_for_response}), and which stay up until the
     * engine answers and are closed by it when nothing else does (Studio's
     * {@code closeAfterClick}: seamless).
     */
    public record Drawn(String id, File dir, String first, Map<String, String> pages, Map<String, Set<String>> vars,
                 String command, String permission, List<List<String>> actions, Set<String> staysUp, Set<String> waits,
                 Set<String> seamless, DialogDef def, Map<String, PageSounds> sounds, Map<String, Integer> itemIcons) {

        /** What a click on this page waits for: what has to answer it. */
        Defs.After answering(String page) {
            if (seamless.contains(page)) return Defs.After.SEAMLESS;
            if (waits.contains(page)) return Defs.After.WAIT_FOR_RESPONSE;
            return staysUp.contains(page) ? Defs.After.NONE : Defs.After.CLOSE;
        }
    }

    /** A click sound Studio sent (its {@code lib/dialogs/click-sound.ts}): an empty key is silence. */
    public record ClickSound(String key, float volume, float pitch) {}

    /**
     * What a click on one page's picture sounds like: its pieces' own sounds,
     * by the command as {@link #rewrite} sends it, else the dialog's ({@code
     * dialog}, null for the config's {@code click-sound}).
     */
    public record PageSounds(ClickSound dialog, Map<String, ClickSound> clicks) {}

    /**
     * The drawing a player was last shown. {@code nonce} is what its action
     * clicks carry, good for one of them (null once used); {@code permitted}
     * says the player held the dialog's permission when it was drawn, so losing
     * it since stops the clicks; {@code live} is false once the dialog has gone
     * from their screen, when it is kept only to reopen on the same page.
     */
    private record Shown(String drawn, String page, Session session, String nonce, boolean permitted, long at, boolean live, String json) {
        Shown used() {
            return new Shown(drawn, page, session, null, permitted, at, live, json);
        }

        Shown retired() {
            return new Shown(drawn, page, session, null, permitted, at, false, json);
        }
    }

    private final Engine engine;
    private DialogVariables variables;
    private final Map<String, Drawn> dialogs = new LinkedHashMap<>();
    private final Map<UUID, Shown> lastShown = new ConcurrentHashMap<>();
    /** Each player's own copies on the Quick Actions key, by drawn dialog: what {@link #adopt} reads their clicks against. */
    private final Map<UUID, Map<String, Copy>> quickCopies = new ConcurrentHashMap<>();
    private final List<Command> commands = new ArrayList<>();
    public final List<String> problems = new ArrayList<>();
    /** Drawn dialogs whose dialog.yml was edited since they were drawn. */
    private final Map<String, File> stale = new LinkedHashMap<>();
    private final Set<String> redrawing = ConcurrentHashMap.newKeySet();
    /** Who asked for the reload that found them, told when they are redrawn. */
    private CommandSender notify;

    /**
     * A copy of a dialog's first page drawn for a player as they joined, for the
     * Quick Actions key: the page as stored when it was drawn ({@code source}),
     * and as sent, with its own nonce.
     */
    private record Copy(String page, String source, Session session, String nonce, String json) {}

    public DrawnDialogs(Engine engine) {
        this.engine = engine;
    }

    /**
     * The player's items in a drawn dialog's live slots, and moving them: RP
     * Engine's own code, compiled in (DialogSlots, DialogItems). Made on first
     * use, once the engine has its plugin.
     */
    private DialogSlots slots;

    private DialogSlots slots() {
        if (slots == null) slots = new DialogSlots(engine.plugin());
        return slots;
    }

    /** A drawn dialog's id as RP Engine's slot code keys it. */
    private static ContentId contentId(String id) {
        return ContentId.parse(id.toLowerCase(Locale.ROOT))
                .or(() -> ContentId.parse("drawn:" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_")))
                .orElseThrow();
    }

    /** Players' settings, kept on the player; made on first use, once the engine has its plugin. */
    private DialogVariables variables() {
        if (variables == null) variables = new DialogVariables(engine.plugin());
        return variables;
    }

    /** A player's setting, as the API hands it out. */
    public Optional<String> setting(Player player, String name) {
        return variables().get(player, name);
    }

    public File folder() {
        return new File(engine.plugin().getDataFolder(), "drawn");
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        dialogs.clear();
        problems.clear();
        stale.clear();
        Map<String, Map<Integer, Integer>> moves = DrawnGlyphs.moves(folder());
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
                        // Drawn before Studio gave a picture its own font (2026-10-14):
                        // its characters are in minecraft:default, where emoji and HUD
                        // plugins put theirs too. Redrawn like an edited one, unless a
                        // plugin's jar brings it (that plugin's next version redraws it).
                        // An unedited sample was brought up to date from our own jar
                        // before this, so only an edited one is still here to redraw.
                        else if (!DrawnInstall.shipped(ns.toPath()) && DrawnFiles.inDefaultFont(dir.toPath())) stale.put(id, dir);
                        Drawn d = read(id, dir, moves.get(ns.getName() + "/" + dir.getName()));
                        if (d != null) dialogs.put(id, d);
                    } catch (Exception e) {
                        problems.add("drawn/" + ns.getName() + "/" + dir.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        unknownOpens();
        registerCommands();
    }

    /** {@code [open] <id>} or {@code [dialog] <id>}, the id first. */
    private static final Pattern OPEN_ACTION = Pattern.compile("^\\[(?:open|dialog)]\\s+(\\S+).*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * A click that opens a dialog this plugin has none called, said as the
     * dialogs load rather than to the first player who presses it. Only once
     * every drawn dialog is in, so one may open another drawn after it. An id
     * holding a placeholder is somebody's words, not a typo, and is passed over.
     */
    private void unknownOpens() {
        for (Drawn d : dialogs.values()) {
            Set<String> said = new LinkedHashSet<>();
            for (List<String> list : d.actions) {
                for (String action : list) {
                    Matcher m = OPEN_ACTION.matcher(action.trim());
                    if (!m.matches()) continue;
                    String target = m.group(1);
                    if (target.contains("{") || target.contains("%") || target.contains("$(")) continue;
                    if (engine.has(engine.resolve(target, d.def.namespace())) || !said.add(target)) continue;
                    problems.add("drawn/" + d.id.replace(':', '/') + ": a click opens \"" + target
                            + "\", which is none of Dialog Engine's dialogs (see /de list); pressing it says so");
                }
            }
        }
    }

    /**
     * {@code quickAction:} (or {@code quick-action:}) in dialog.yml: true, a
     * mode ({@code direct} or {@code launcher}), or a map with {@code mode},
     * {@code launcher: true} (Studio's own spelling) and {@code label}. Direct
     * is the default, as for a YAML dialog: the picture's glyphs are in the
     * drawn pack, which every player is sent as they join while a drawn dialog
     * is on the key ({@link DrawnPack#onKey}).
     */
    static Defs.QuickAction quickAction(Map<String, Object> spec, String id) {
        if (spec == null) return null;
        Object q = spec.containsKey("quickAction") ? spec.get("quickAction") : spec.get("quick-action");
        if (q == null || Boolean.FALSE.equals(q) || "false".equals(q)) return null;
        boolean launcher = "launcher".equals(q)
                || q instanceof Map<?, ?> m && (Boolean.TRUE.equals(m.get("launcher")) || "launcher".equals(m.get("mode")));
        String label = q instanceof Map<?, ?> m && m.get("label") instanceof String l ? l
                : spec.get("title") instanceof String t ? t : null;
        return new Defs.QuickAction(launcher, label);
    }

    /** {@code move}: the characters this dialog's picture was moved to ({@link DrawnGlyphs}), or null. */
    private Drawn read(String id, File dir, Map<Integer, Integer> move) throws Exception {
        File built = new File(dir, "dialogs.json");
        if (!built.isFile()) return null;
        JsonArray list = JsonParser.parseString(Files.readString(built.toPath(), StandardCharsets.UTF_8)).getAsJsonArray();
        Map<String, Object> spec = DrawnFiles.read(dir.toPath());
        // Only a command dialog.yml itself has may run: dialogs.json is Studio's
        // answer, and an answer that came over the network does not get to add one.
        Set<String> allowed = DrawnFiles.commands(spec);
        Map<String, String> consoles = DrawnFiles.consoleCommands(spec);
        Map<String, String> pages = new LinkedHashMap<>();
        Map<String, Set<String>> vars = new LinkedHashMap<>();
        Set<String> staysUp = new LinkedHashSet<>();
        Set<String> waits = new LinkedHashSet<>();
        Set<String> seamless = new LinkedHashSet<>();
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Map<String, JsonObject> written = new LinkedHashMap<>();
        Map<String, Integer> itemIcons = new HashMap<>();
        String first = null;
        String ns = id.substring(0, id.indexOf(':'));
        for (JsonElement e : list) {
            JsonObject page = e.getAsJsonObject();
            String studioId = "studio:" + page.get("id").getAsString();
            if (first == null) first = studioId;
            JsonElement json = page.get("json");
            if (json instanceof JsonObject o && o.get("after_action") instanceof JsonElement a && a.isJsonPrimitive()) {
                // Sent as none and closed by the engine after a click nothing
                // else answered: Studio says so beside the JSON.
                boolean closes = page.get("closeAfterClick") instanceof JsonElement c && c.isJsonPrimitive() && c.getAsBoolean();
                if ("none".equals(a.getAsString())) (closes ? seamless : staysUp).add(studioId);
                else if ("wait_for_response".equals(a.getAsString())) waits.add(studioId);
            }
            JsonElement rewritten = rewrite(json, actions, allowed, consoles, refused, link -> opensHere(link, ns));
            if (move != null) rewritten = DrawnGlyphs.movePage(rewritten, move);
            pages.put(studioId, GSON.toJson(rewritten));
            written.put(studioId, page);
            // The pack's own items' cells in its item sheet, by model id: what a
            // live slot draws one of the pack's items as (RP Engine's itemIcons).
            if (page.get("itemIcons") instanceof JsonObject icons) {
                for (Map.Entry<String, JsonElement> icon : icons.entrySet()) {
                    if (icon.getValue().isJsonPrimitive() && icon.getValue().getAsJsonPrimitive().isNumber()) {
                        itemIcons.put(icon.getKey(), icon.getValue().getAsInt());
                    }
                }
            }
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
        DialogDef def = new DialogDef(id, ns, "drawn/" + id.replace(':', '/') + "/" + DrawnFiles.SOURCE, id, null,
                Defs.Type.MULTI_ACTION, 1, true, false, Defs.After.NONE, List.of(), List.of(), List.of(), null,
                Map.of(), command, List.of(), permission, List.of(), null, null, Map.of(), quickAction(spec, id));
        // Once every page is rewritten: a sound's command is found among the
        // actions all of them made.
        Map<String, PageSounds> sounds = new LinkedHashMap<>();
        written.forEach((studioId, page) -> {
            PageSounds s = pageSounds(page, actions, consoles);
            if (s != null) sounds.put(studioId, s);
        });
        return new Drawn(id, dir, first, pages, vars, command, permission, List.copyOf(actions), Set.copyOf(staysUp),
                Set.copyOf(waits), Set.copyOf(seamless), def, java.util.Collections.unmodifiableMap(sounds), Map.copyOf(itemIcons));
    }

    /** A sound event as a server takes one; Studio's {@code CLICK_SOUND_EVENT} is the same rule. */
    private static final Pattern SOUND_EVENT = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]{1,200}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]+}");

    /** One page's click sounds from what Studio sent beside it, or null when it sent none. */
    static PageSounds pageSounds(JsonObject page, List<List<String>> actions) {
        return pageSounds(page, actions, Map.of());
    }

    /** {@link #pageSounds(JsonObject, List)}, a console click's sound found by the command dialog.yml gives its key. */
    static PageSounds pageSounds(JsonObject page, List<List<String>> actions, Map<String, String> consoles) {
        ClickSound dialog = page.get("clickSound") instanceof JsonObject o ? clickSound(o) : null;
        Map<String, ClickSound> clicks = new LinkedHashMap<>();
        if (page.get("clickSounds") instanceof JsonArray list) {
            for (JsonElement e : list) {
                if (!(e instanceof JsonObject o) || !(o.get("command") instanceof JsonPrimitive c) || !c.isString()) continue;
                ClickSound sound = clickSound(o);
                String key = rewritten(c.getAsString(), actions, consoles);
                if (sound != null && key != null) clicks.putIfAbsent(key, sound);
            }
        }
        return dialog == null && clicks.isEmpty() ? null : new PageSounds(dialog, java.util.Collections.unmodifiableMap(clicks));
    }

    /** A click sound, or null when its sound is not an event name: a typo keeps the click as it was. */
    static ClickSound clickSound(JsonObject o) {
        String key = o.get("sound") instanceof JsonPrimitive s && s.isString() ? s.getAsString().trim().toLowerCase(Locale.ROOT) : "";
        if (!key.isEmpty() && !SOUND_EVENT.matcher(key).matches()) return null;
        float volume = o.get("volume") instanceof JsonPrimitive v && v.isNumber() ? v.getAsFloat() : 0.25f;
        float pitch = o.get("pitch") instanceof JsonPrimitive p && p.isNumber() ? p.getAsFloat() : 1f;
        if (!Float.isFinite(volume)) volume = 0.25f;
        if (!Float.isFinite(pitch)) pitch = 1f;
        return new ClickSound(key, Math.max(0f, Math.min(1f, volume)), Math.max(0.5f, Math.min(2f, pitch)));
    }

    /** A Studio click's command as {@link #rewrite} sends it, slash off; null for one it refused. */
    static String rewritten(String command, List<List<String>> actions) {
        return rewritten(command, actions, Map.of());
    }

    static String rewritten(String command, List<List<String>> actions, Map<String, String> consoles) {
        String bare = command.startsWith("/") ? command.substring(1) : command;
        Matcher m = ENGINE_CLICK.matcher(bare);
        if (m.matches()) return CLICK + " " + m.group(2) + " " + m.group(3);
        Matcher console = CONSOLE_CLICK.matcher(bare);
        if (console.matches()) {
            String run = consoles.get(console.group(1));
            int n = run == null ? -1 : actions.indexOf(List.of("[console] " + run));
            return n < 0 ? null : CLICK + " act " + n;
        }
        int n = actions.indexOf(actions(bare));
        return n < 0 ? null : CLICK + " act " + n;
    }

    /** Whether {@code command} is the click written {@code held}: a placeholder was filled as it opened. */
    static boolean sameCommand(String held, String command) {
        String bare = command.startsWith("/") ? command.substring(1) : command;
        StringBuilder pattern = new StringBuilder();
        Matcher p = PLACEHOLDER.matcher(held);
        int at = 0;
        while (p.find()) {
            pattern.append(Pattern.quote(held.substring(at, p.start()))).append(".*?");
            at = p.end();
        }
        pattern.append(Pattern.quote(held.substring(at)));
        return Pattern.compile(pattern.toString(), Pattern.DOTALL).matcher(bare).matches();
    }

    /** A dialog id a {@code show_dialog} click may name, to be opened by this plugin: a resource location. */
    private static final Pattern DIALOG_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    /**
     * Whether a {@code show_dialog} click naming {@code link}, in a dialog in
     * {@code namespace}, is one of this plugin's to open: Studio's own ids
     * ({@code studio:<x>}, resolved when clicked by {@link Engine#resolve}), and
     * any id that is one of our dialogs. The drawn ones are asked of the folder,
     * since they are still loading.
     */
    private boolean opensHere(String link, String namespace) {
        if (link.startsWith("studio:")) return true;
        String id = link.contains(":") ? link : namespace + ":" + link;
        if (engine.has(id)) return true;
        int colon = id.indexOf(':');
        return new File(new File(new File(folder(), id.substring(0, colon)), id.substring(colon + 1)), "dialogs.json").isFile();
    }

    static JsonElement rewrite(JsonElement e, List<List<String>> actions, Set<String> allowed, List<String> refused) {
        return rewrite(e, actions, allowed, Map.of(), refused, link -> false);
    }

    static JsonElement rewrite(JsonElement e, List<List<String>> actions, Set<String> allowed, List<String> refused,
                               java.util.function.Predicate<String> ours) {
        return rewrite(e, actions, allowed, Map.of(), refused, ours);
    }

    /**
     * Every command a click runs, as this plugin's: RP Engine's page, var and
     * slot clicks as {@code /dialogclick page|var|slot}, and anything else as
     * {@code /dialogclick act <n>}, its actions kept in {@code actions}. A
     * command not in {@code allowed} (see {@link DrawnFiles#commands}) gets no
     * actions, and is named in {@code refused}.
     *
     * <p>A click that opens another dialog BY ID ({@code show_dialog}, a body
     * click or a footer button) is ours too when {@code ours} says the id is:
     * {@code /dialogclick act <n>} running {@code [open] <id>}. Left to the
     * client, an id is a lookup in the server's dialog registry, which has
     * none of this plugin's dialogs, and the game refuses the WHOLE dialog
     * holding such a click, not just the click. Studio writes one whenever
     * "Open another dialog" names a dialog that was not drawn in the same
     * session, which in a {@code /de edit} is every other one. An id that is
     * not ours is left as written: the registry may well have it.
     *
     * <p>A "Run as console" click ({@code rp click <key>}) is
     * {@code [console] <command>}, the command being the one {@code consoles}
     * (dialog.yml's, {@link DrawnFiles#consoleCommands}) has by that key; a key
     * it does not have runs nothing, like any command dialog.yml lacks.
     */
    static JsonElement rewrite(JsonElement e, List<List<String>> actions, Set<String> allowed, Map<String, String> consoles,
                               List<String> refused, java.util.function.Predicate<String> ours) {
        if (e instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) a.set(i, rewrite(a.get(i), actions, allowed, consoles, refused, ours));
        } else if (e instanceof JsonObject o) {
            boolean bodyOpens = o.get("action") instanceof JsonPrimitive act && "show_dialog".equals(act.getAsString());
            boolean buttonOpens = o.get("type") instanceof JsonPrimitive type
                    && ("minecraft:show_dialog".equals(type.getAsString()) || "show_dialog".equals(type.getAsString()));
            if ((bodyOpens || buttonOpens) && o.get("dialog") instanceof JsonPrimitive target && target.isString()) {
                String link = target.getAsString().trim().toLowerCase(Locale.ROOT);
                if (DIALOG_ID.matcher(link).matches() && ours.test(link)) {
                    List<String> list = List.of("[open] " + link);
                    int n = actions.indexOf(list);
                    if (n < 0) {
                        n = actions.size();
                        actions.add(list);
                    }
                    // In place, so the keys keep their order: picture() reads
                    // "action":"run_command","command": as written.
                    if (bodyOpens) o.addProperty("action", "run_command");
                    else o.addProperty("type", "minecraft:run_command");
                    o.remove("dialog");
                    o.addProperty("command", CLICK + " act " + n);
                    return e;
                }
            }
            boolean runs = (o.has("action") && o.get("action").isJsonPrimitive() && "run_command".equals(o.get("action").getAsString()))
                    || (o.has("type") && o.get("type").isJsonPrimitive() && "minecraft:run_command".equals(o.get("type").getAsString()));
            if (runs && o.has("command") && o.get("command").isJsonPrimitive()) {
                String command = o.get("command").getAsString();
                Matcher m = ENGINE_CLICK.matcher(command);
                Matcher console = CONSOLE_CLICK.matcher(command);
                if (m.matches()) {
                    o.addProperty("command", m.group(1) + CLICK + " " + m.group(2) + " " + m.group(3));
                } else if (console.matches()) {
                    String run = consoles.get(console.group(1));
                    List<String> list = run == null ? List.of() : List.of("[console] " + run);
                    if (run == null && !refused.contains(command)) refused.add(command);
                    int n = actions.indexOf(list);
                    if (n < 0) {
                        n = actions.size();
                        actions.add(list);
                    }
                    o.addProperty("command", (command.startsWith("/") ? "/" : "") + CLICK + " act " + n);
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
            for (String key : List.copyOf(o.keySet())) o.add(key, rewrite(o.get(key), actions, allowed, consoles, refused, ours));
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

    public boolean has(String id) {
        return dialogs.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> ids() {
        return dialogs.keySet();
    }

    public Optional<Drawn> get(String id) {
        return Optional.ofNullable(dialogs.get(id.toLowerCase(Locale.ROOT)));
    }

    /** The drawn dialog whose first page Studio calls {@code studioId} ({@code studio:<x>}), if there is one. */
    public Optional<String> byStudioId(String studioId) {
        for (Drawn d : dialogs.values()) {
            if (d.first.equals(studioId)) return Optional.of(d.id);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ showing

    /** Opens one by id, as a session of its own: through the engine, so it is shown the way every dialog is. */
    public boolean open(Player player, String id, Map<String, String> values, Set<String> untrusted) {
        Drawn d = dialogs.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        return engine.show(player, new Session(player.getUniqueId(), d.id, values == null ? Map.of() : values,
                untrusted == null ? Set.of() : untrusted, null), false);
    }

    /**
     * Shows a session's drawn dialog: on its first page, or (redrawn for the
     * same dialog) on the page the player was looking at.
     */
    public boolean show(Player player, Session session, boolean reopened) {
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
        String nonce = nonce();
        json = ACT_CLICK.matcher(json).replaceAll("$1 " + nonce + "\"");
        Shown before = lastShown.get(player.getUniqueId());
        json = filled(player, d, json, session.values);
        json = DialogBars.fill(json);
        // The player's own items into the live slots, after the placeholders and
        // bars, as RP Engine does: a slot reached by a page turn or a move keeps
        // what was picked up.
        if (DialogItems.perPlayer(json)) {
            ContentId cid = contentId(d.id);
            slots().shown(player, cid, before != null && before.drawn.equals(d.id));
            json = DialogItems.fill(json, slots().reader(player), slots().held(player, cid).orElse(null), d.itemIcons);
        }
        boolean permitted = d.permission == null || player.hasPermission(d.permission);
        lastShown.put(player.getUniqueId(), new Shown(d.id, page, session, nonce, permitted, System.currentTimeMillis(), true, json));
        if (Platform.showJson(player, json, engine.plugin().getLogger())) {
            engine.replacedScreen(player);
            return true;
        }
        engine.plugin().getLogger().warning("Could not show drawn dialog " + d.id + ".");
        return false;
    }

    private static String nonce() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A joining player's own copy of drawn dialog {@code id} for the Quick
     * Actions key: its first page drawn for {@code standIn}, as {@link
     * #showPage} draws one, but for somebody not in a world yet. Null when it
     * cannot be one, because it shows the player's own items (not known while
     * they join), and the key keeps the launcher for it.
     */
    public String drawQuick(Player standIn, String id) {
        Drawn d = dialogs.get(id.toLowerCase(Locale.ROOT));
        String source = d == null ? null : d.pages.get(d.first);
        if (source == null || DialogItems.perPlayer(source)) return null;
        String nonce = nonce();
        Session session = new Session(standIn.getUniqueId(), d.id, Map.of(), Set.of(), null);
        String json = ACT_CLICK.matcher(source).replaceAll("$1 " + nonce + "\"");
        json = DialogBars.fill(filled(standIn, d, json, session.values));
        quickCopies.computeIfAbsent(standIn.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(d.id, new Copy(d.first, source, session, nonce, json));
        return json;
    }

    /** Forgets a player's quick action copies: they left, or are joining again. */
    public void forgetQuick(UUID player) {
        quickCopies.remove(player);
    }

    /**
     * A click from one of the player's own copies on the Quick Actions key,
     * which the server never saw open (the key is the client's). When the
     * drawing on their screen as far as the server knows does not hold the
     * click and a copy does, the copy is what they were shown, its nonce good
     * again: every press of the key opens it afresh. Not for a copy of a page
     * changed since it was drawn, whose clicks may now mean something else.
     */
    private Shown adopt(Player player, String[] args, Shown shown) {
        Map<String, Copy> mine = quickCopies.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) return shown;
        String sent = CLICK + " " + String.join(" ", args);
        if (shown != null && shown.live && holds(shown.json, sent)) return shown;
        for (Map.Entry<String, Copy> e : mine.entrySet()) {
            Copy copy = e.getValue();
            Drawn d = dialogs.get(e.getKey());
            if (d == null || !copy.source.equals(d.pages.get(copy.page)) || !holds(copy.json, sent)) continue;
            Shown adopted = new Shown(d.id, copy.page, copy.session, copy.nonce, true, System.currentTimeMillis(), true, copy.json);
            lastShown.put(player.getUniqueId(), adopted);
            return adopted;
        }
        return shown;
    }

    /**
     * The placeholder pass, after the pieces this player is not to see
     * ("show when") are emptied, so nothing in one is looked up and no marker
     * in one is left for the passes after this.
     */
    private String filled(Player player, Drawn d, String json, Map<String, String> values) {
        boolean conditions = DialogConditions.any(json);
        boolean rows = DialogRows.any(json);
        if (!conditions && !rows && !DialogPlaceholders.any(json)) return json;
        Function<String, Optional<String>> lookup = lookup(player, d, values);
        String out = conditions ? DialogConditions.strip(json, lookup, player::hasPermission) : json;
        out = DialogPlaceholders.any(out) ? DialogPlaceholders.fill(out, lookup) : out;
        return rows ? DialogRows.strip(out, lookup) : out;
    }

    /**
     * A stored page with only the pieces this player may see in it, for checking
     * a click against: a button hidden from them was never theirs to press, and
     * typing its command by hand must not press it. Written back the way the page
     * was stored, so {@link #holds} matches it as it matches the page.
     */
    private String visible(Player player, Drawn d, String json, Map<String, String> values) {
        if (!DialogConditions.any(json)) return json;
        return GSON.toJson(JsonParser.parseString(DialogConditions.strip(json, lookup(player, d, values), player::hasPermission)));
    }

    /**
     * What a placeholder or a condition is answered from: what it was opened
     * with, the player's settings, the engine's own values, and last
     * PlaceholderAPI, which a YAML dialog's text has always reached through
     * {@code Text.fill} and a drawn one never did: {@code {vault_eco_balance}}
     * is {@code %vault_eco_balance%} there, as it is in RP Engine.
     */
    private Function<String, Optional<String>> lookup(Player player, Drawn d, Map<String, String> values) {
        Map<String, String> given = new HashMap<>();
        values.forEach((k, v) -> given.put(k.toLowerCase(Locale.ROOT), v));
        Scope scope = new Scope(engine, player, d.id, given, Set.of(), null, null);
        Function<String, Optional<String>> direct = name -> {
            String mine = given.get(name.toLowerCase(Locale.ROOT));
            if (mine != null) return Optional.of(mine);
            // RP Engine's built-ins ({level}, {xp_points}, {health}, ...) and then
            // PlaceholderAPI, in RP Engine's order, so a drawn dialog reads the
            // same on both plugins.
            Optional<String> builtIn = Placeholders.lookup(player, name, null);
            if (builtIn.isPresent()) return builtIn;
            // A player still joining has no settings to read yet: a bound control shows its default.
            Optional<String> stored = player instanceof StandIn ? Optional.empty() : variables().get(player, name);
            if (stored.isPresent()) return stored;
            String own = scope.lookup(name);
            if (own != null) return Optional.of(own);
            return Optional.ofNullable(Papi.lookup(player, name));
        };
        return name -> {
            Optional<String> found = direct.apply(name);
            return found.isPresent() ? found : DialogRows.item(name, given, direct);
        };
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
    public void click(Player player, String[] args) {
        if (args.length == 0 || !engine.allowClick(player)) return;
        UUID uuid = player.getUniqueId();
        Shown shown = adopt(player, args, lastShown.get(uuid));
        Drawn d = shown == null || !shown.live ? null : dialogs.get(shown.drawn);
        boolean act = args[0].equalsIgnoreCase("act");
        // An action click is held as written, without the nonce it was sent with.
        String command = CLICK + " " + String.join(" ", act && args.length == 3 ? Arrays.copyOf(args, 2) : args);
        // A click is checked against the page as stored and as the player saw it:
        // a switch, slider or dropdown's command names its next value through a
        // placeholder, so only the filled drawing holds it. Both without what was
        // hidden from this player.
        String json = d == null ? null : visible(player, d, d.pages.get(shown.page), shown.session.values);
        String seen = d == null ? null : shown.json;
        // A click on a page that waits has put the client on its "Waiting for
        // Server" screen, whatever is made of the click: Engine.closeDialog.
        if (d != null && d.waits.contains(shown.page)) engine.clickedWaiting(player);
        if (args[0].equalsIgnoreCase("page") && args.length >= 2 && args[1].equalsIgnoreCase("close")) {
            if (picture(json, command) || picture(seen, command)) clickSound(player, d, shown, command);
            engine.closeDialog(player);
            return;
        }
        if (d == null || !(holds(json, command) || holds(seen, command))) return;
        if (System.currentTimeMillis() - shown.at > engine.clickLifetime().toMillis()) {
            refuse(player, d, shown);
            return;
        }
        if (shown.permitted && d.permission != null && !player.hasPermission(d.permission)) {
            Platform.send(player, Text.MM.deserialize(engine.message("no-permission", "<red>You can't do that.")));
            refuse(player, d, shown);
            return;
        }
        if (act) {
            if (args.length != 3 || shown.nonce == null
                    || !MessageDigest.isEqual(shown.nonce.getBytes(StandardCharsets.UTF_8), args[2].getBytes(StandardCharsets.UTF_8))
                    || !lastShown.replace(uuid, shown, shown.used())) {
                return;
            }
        }
        if (picture(json, command) || picture(seen, command)) clickSound(player, d, shown, command);
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
        } else if (args[0].equalsIgnoreCase("slot") && args.length == 2) {
            // A live slot's click: picks a stack up or puts it down (RP Engine's
            // DialogSlots), then the page again with the items where they now are.
            Optional<DialogSlots.Key> key = DialogSlots.Key.parse(args[1]);
            if (key.isEmpty()) return;
            Bukkit.getScheduler().runTask(engine.plugin(), () -> {
                slots().click(player, contentId(d.id), key.get());
                showPage(player, d, shown.page, shown.session);
            });
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
                // A page that waits, or is seamless, is answered when its actions
                // end (ClickCtx.finish): by the next dialog, or by a close.
                ctx.answering(d.answering(shown.page));
                engine.run(ctx, d.actions.get(n));
                if (ctx.responded || !player.isOnline() || lastShown.get(uuid) == null || lastShown.get(uuid).nonce != null) return;
                // The nonce is spent. A page that stays up when clicked is drawn
                // again with a fresh one, so its next click works; any other
                // page closed itself on the click, and its drawing is over.
                if (d.staysUp.contains(shown.page)) {
                    showPage(player, d, shown.page, shown.session);
                } else {
                    retire(player);
                }
            });
        }
    }

    /**
     * A click that cannot go ahead ends the drawing. On a page that waits for
     * the server, or is seamless, it closes it too: the player would be left on
     * the client's waiting screen, or looking at a dialog that does nothing.
     */
    private void refuse(Player player, Drawn d, Shown shown) {
        if (d.answering(shown.page).awaitsAnswer()) engine.closeDialog(player);
        else retire(player);
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

    /**
     * The sound of a click on the picture: the clicked piece's own, else its
     * dialog's, as Studio drew them, else the config's {@code click-sound}.
     */
    private void clickSound(Player player, Drawn d, Shown shown, String command) {
        PageSounds sounds = d == null || shown == null ? null : d.sounds().get(shown.page);
        ClickSound sound = null;
        if (sounds != null) {
            for (Map.Entry<String, ClickSound> click : sounds.clicks().entrySet()) {
                if (sameCommand(click.getKey(), command)) {
                    sound = click.getValue();
                    break;
                }
            }
            if (sound == null) sound = sounds.dialog();
        }
        if (sound == null) {
            clickSound(player);
            return;
        }
        if (sound.key().isEmpty() || sound.volume() <= 0f) return;
        try {
            Platform.sound(player, sound.key(), sound.volume(), sound.pitch());
        } catch (RuntimeException e) {
            // A sound name that is not a key: no sound, rather than no click.
        }
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

    public void forget(Player player) {
        lastShown.remove(player.getUniqueId());
        if (slots != null) slots.forget(player);
    }

    /**
     * The player's drawn dialog has gone from their screen (closed, or replaced
     * by another dialog): none of its clicks work any more. The page they were
     * on is still remembered, for {@code [back]}.
     */
    public void retire(Player player) {
        lastShown.computeIfPresent(player.getUniqueId(), (k, s) -> s.live ? s.retired() : s);
        if (slots != null) slots.forget(player);
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
                    // {target} is who the command names, or (named nobody) the player themselves.
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("target", args.length > 0 ? args[0] : p.getName());
                    open(p, d.id, values, args.length > 0 ? values.keySet() : Set.of());
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
    public int staleCount() {
        return stale.size();
    }

    /** Tells {@code sender}, as well as the console, how the next redraw goes. */
    public void notifyNext(CommandSender sender) {
        notify = sender;
    }

    /**
     * Has Studio redraw every drawn dialog whose {@code dialog.yml} was edited
     * by hand, then reloads once they are all back. Until then the old drawing
     * keeps showing.
     */
    public void redrawStale() {
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

    public void shutdown() {
        var map = Platform.commandMap();
        for (Command c : commands) OpenCommands.unregister(map, c);
        commands.clear();
    }
}
