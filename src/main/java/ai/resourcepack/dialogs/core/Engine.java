package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.api.ActionHandler;
import ai.resourcepack.dialogs.api.DialogContext;
import ai.resourcepack.dialogs.api.DialogEngineAPI;
import ai.resourcepack.dialogs.api.DialogInfo;
import ai.resourcepack.dialogs.api.ListProvider;
import ai.resourcepack.dialogs.api.PlaceholderResolver;
import ai.resourcepack.dialogs.api.event.DialogClickEvent;
import ai.resourcepack.dialogs.api.event.DialogEngineReloadEvent;
import ai.resourcepack.dialogs.api.event.DialogInputErrorEvent;
import ai.resourcepack.dialogs.api.event.DialogOpenEvent;
import ai.resourcepack.dialogs.core.click.Actions;
import ai.resourcepack.dialogs.core.click.ClickCtx;
import ai.resourcepack.dialogs.core.click.Clicks;
import ai.resourcepack.dialogs.core.command.OpenCommands;
import ai.resourcepack.dialogs.core.drawn.DrawnDialogs;
import ai.resourcepack.dialogs.core.drawn.DrawnInstall;
import ai.resourcepack.dialogs.core.drawn.DrawnPack;
import ai.resourcepack.dialogs.core.edit.EditSessions;
import ai.resourcepack.dialogs.core.placeholder.BuiltInLists;
import ai.resourcepack.dialogs.core.placeholder.Conditions;
import ai.resourcepack.dialogs.core.placeholder.Papi;
import ai.resourcepack.dialogs.core.placeholder.Scope;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import ai.resourcepack.dialogs.core.yaml.Defs.ButtonDef;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Defs.InputDef;
import ai.resourcepack.dialogs.core.yaml.Defs.InputKind;
import ai.resourcepack.dialogs.core.yaml.Defs;
import ai.resourcepack.dialogs.core.yaml.Loader;
import ai.resourcepack.dialogs.core.yaml.Renderer;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The engine: every dialog, every registration, and what each player is looking at. */
public final class Engine implements DialogEngineAPI {

    private record Folder(Plugin owner, String namespace, File folder) {}

    private record CodeDialog(Plugin owner, String id, ConfigurationSection section) {}

    private record Owned<T>(Plugin owner, T value) {}

    private final JavaPlugin plugin;
    private final Renderer renderer = new Renderer(this);
    private final Actions actions = new Actions(this);
    private final Clicks clicks = new Clicks();
    private final OpenCommands commands;

    private final List<Folder> folders = new ArrayList<>();
    private final Map<String, CodeDialog> codeDialogs = new LinkedHashMap<>();
    private final Map<String, Owned<ActionHandler>> customActions = new ConcurrentHashMap<>();
    private final List<Owned<Map.Entry<String, PlaceholderResolver>>> resolvers = new ArrayList<>();
    private final Map<String, Owned<ListProvider>> lists = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Map<String, String> jsonCache = new ConcurrentHashMap<>();
    /**
     * Counts every dialog shown to a player, so a delayed close can tell whether
     * one came since. It is also the number of the drawing on their screen: a
     * button only works while the drawing it was bound to is the latest.
     */
    private final Map<UUID, Long> shown = new ConcurrentHashMap<>();
    /** Each player's clicks this second: {second, count}. */
    private final Map<UUID, long[]> clickRate = new ConcurrentHashMap<>();
    /**
     * How many clicks (button presses and drawn-dialog clicks) a player gets a
     * second. Each one can redraw a dialog, which is main-thread work (lists,
     * placeholders), so a modified client sending hundreds must not get
     * hundreds. Far above what a hand can do.
     */
    private static final int CLICKS_PER_SECOND = 10;
    private long closeDelay = 10;

    private volatile Map<String, DialogDef> dialogs = Map.of();
    private List<String> problems = List.of();

    private Duration clickLifetime = Duration.ofMinutes(30);
    private String validationFormat = "<red>{error}";
    private Defs.After defaultAfter = Defs.After.WAIT_FOR_RESPONSE;

    public Engine(JavaPlugin plugin) {
        this.plugin = plugin;
        this.commands = new OpenCommands(this, plugin);
        folders.add(new Folder(plugin, null, new File(plugin.getDataFolder(), "dialogs")));
    }

    // ------------------------------------------------------------------ loading

    /** Reads config.yml and every dialog. Returns the problems found. */
    public List<String> load() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();
        clickLifetime = Duration.ofMinutes(Math.max(1, cfg.getLong("click-lifetime-minutes", 30)));
        validationFormat = cfg.getString("validation-format", "<red>{error}");
        closeDelay = Math.max(0, cfg.getLong("close-delay-ticks", 10));
        defaultAfter = switch (cfg.getString("default-after", "wait").toLowerCase(Locale.ROOT)) {
            case "close" -> Defs.After.CLOSE;
            case "none", "stay" -> Defs.After.NONE;
            default -> Defs.After.WAIT_FOR_RESPONSE;
        };
        Papi.reset();
        jsonCache.clear();
        warned.clear();

        Loader loader = new Loader(defaultAfter);
        Map<String, DialogDef> next = new LinkedHashMap<>();
        for (Folder f : List.copyOf(folders)) {
            if (f.namespace() == null) {
                // The engine's own folder: dialogs/<namespace>/..., and files at the top are "server".
                File[] children = f.folder().listFiles();
                if (children == null) continue;
                java.util.Arrays.sort(children);
                Map<String, DialogDef> top = new LinkedHashMap<>();
                File holder = f.folder();
                for (File child : children) {
                    if (child.isDirectory()) {
                        String ns = child.getName().toLowerCase(Locale.ROOT);
                        if (!ns.matches("[a-z0-9_.\\-]+")) {
                            loader.problems.add("dialogs/" + child.getName() + ": a folder name is a namespace (a-z, 0-9, _ . -)");
                            continue;
                        }
                        next.putAll(loader.loadFolder(ns, child));
                    }
                }
                top.putAll(loadTopLevel(loader, holder));
                next.putAll(top);
            } else {
                next.putAll(loader.loadFolder(f.namespace(), f.folder()));
            }
        }
        for (CodeDialog c : codeDialogs.values()) {
            String ns = c.id().substring(0, c.id().indexOf(':'));
            DialogDef def = loader.parse(c.id(), ns, c.owner().getName(), c.section(), c.owner().getDataFolder());
            if (def != null) next.put(c.id(), def);
        }
        dialogs = Collections.unmodifiableMap(next);
        problems = List.copyOf(loader.problems);
        for (String p : problems) plugin.getLogger().warning(p);
        commands.sync(dialogs.values());
        drawn.load();
        for (String p : drawn.problems) plugin.getLogger().warning(p);
        drawnPack.build(drawn.folder());
        drawn.redrawStale();
        return problems;
    }

    /** Drawn dialogs: Studio's, run on RP Engine's dialog code (DrawnDialogs). */
    public final DrawnDialogs drawn = new DrawnDialogs(this);
    /** The pack they are drawn with. */
    public final DrawnPack drawnPack = new DrawnPack(this);

    /** {@code /dialogclick}: what a drawn dialog's clicks run. */
    public void drawnClick(Player player, String[] args) {
        drawn.click(player, args);
    }

    /** Sends the drawn dialog pack to a player joining, when there is one they should have. */
    public void sendDrawnPack(Player player) {
        if (drawnPack.onJoin()) drawnPack.send(player);
    }

    /** Drawn dialogs waiting for their pack to reach a player. */
    private final Map<UUID, Runnable> waitingForPack = new ConcurrentHashMap<>();

    /**
     * What a player's client made of the drawn dialog pack. A dialog opened
     * before they had it opens once it has loaded; one they declined or could
     * not download says so, and the next one opened offers the pack again.
     */
    public void packStatus(Player player, UUID pack, String status) {
        if (!DrawnPack.isPack(pack)) return;
        switch (status) {
            case "SUCCESSFULLY_LOADED" -> {
                Runnable open = waitingForPack.remove(player.getUniqueId());
                if (open != null) open.run();
            }
            case "ACCEPTED", "DOWNLOADED" -> {
                // Still on its way.
            }
            case "DECLINED" -> {
                drawnPack.refused(player);
                if (waitingForPack.remove(player.getUniqueId()) != null) {
                    tell(player, message("pack-declined", "<red>This menu is drawn with a resource pack, and you declined it."));
                }
            }
            default -> {
                drawnPack.refused(player);
                if (waitingForPack.remove(player.getUniqueId()) != null) {
                    tell(player, message("pack-failed", "<red>This menu's resource pack could not be loaded."));
                    if (player.hasPermission("dialogengine.pack.notify")) {
                        tell(player, "<gray>Players download it from <white>" + Text.MM.escapeTags(drawnPack.url())
                                + "</white>. If that is not this server's address, set <white>pack.public-host</white> in Dialog Engine's config.yml.");
                    }
                }
            }
        }
    }

    private Map<String, DialogDef> loadTopLevel(Loader loader, File folder) {
        // Only the .yml files directly in dialogs/, as namespace "server".
        File tmp = folder;
        Map<String, DialogDef> out = new LinkedHashMap<>();
        File[] files = tmp.listFiles(f -> f.isFile() && (f.getName().endsWith(".yml") || f.getName().endsWith(".yaml")));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File file : files) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try {
                yaml.load(file);
            } catch (Exception e) {
                loader.problems.add("dialogs/" + file.getName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
                continue;
            }
            for (String key : yaml.getKeys(false)) {
                ConfigurationSection s = yaml.getConfigurationSection(key);
                if (s == null) continue;
                DialogDef def = loader.parse("server:" + key.toLowerCase(Locale.ROOT), "server", "dialogs/" + file.getName(), s, folder);
                if (def != null) out.put(def.id(), def);
            }
        }
        return out;
    }

    public List<String> problems() {
        return problems;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public Clicks clicks() {
        return clicks;
    }

    /** Starts listening for button presses. Returns which server's click event it found, or null for none. */
    public String listenForClicks() {
        return clicks.listen(plugin);
    }

    /** How dialogs reach the screen on this server, for the startup line. */
    public String route() {
        return Platform.describe();
    }

    /** Sends MiniMessage to a player or the console, the way every message of ours goes. */
    public static void tell(org.bukkit.command.CommandSender to, String miniMessage) {
        Platform.send(to, Text.MM.deserialize(miniMessage));
    }

    public Duration clickLifetime() {
        return clickLifetime;
    }

    public String validationFormat() {
        return validationFormat;
    }

    public String message(String key, String fallback) {
        return plugin.getConfig().getString("messages." + key, fallback);
    }

    public void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warning(message);
    }

    public DialogDef def(String id) {
        return dialogs.get(id);
    }

    /** "confirm" inside namespace "myplugin" is "myplugin:confirm". */
    public String qualify(String id, String namespace) {
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        if (trimmed.contains(":") || namespace == null) return trimmed;
        return namespace + ":" + trimmed;
    }

    private EditSessions edits;

    /** {@code /de edit}'s sessions, made on first use. */
    public synchronized EditSessions edits() {
        if (edits == null) edits = new EditSessions(this);
        return edits;
    }

    public void shutdown() {
        if (edits != null) edits.shutdown();
        drawn.shutdown();
        drawnPack.stop();
        commands.clear();
        sessions.clear();
        clicks.clear();
    }

    public void forget(Player player) {
        drawn.forget(player);
        drawnPack.forget(player);
        waitingForPack.remove(player.getUniqueId());
        sessions.remove(player.getUniqueId());
        shown.remove(player.getUniqueId());
        clickRate.remove(player.getUniqueId());
        clicks.forget(player.getUniqueId());
    }

    /** Closes the player's dialog, and with it every button it had. */
    public void closeDialog(Player player) {
        Platform.closeDialog(player);
        clicks.forget(player.getUniqueId());
        drawn.retire(player);
    }

    /**
     * Whether a click from this player may go ahead: at most
     * {@link #CLICKS_PER_SECOND} a second. The rest are dropped.
     */
    public boolean allowClick(Player player) {
        long second = System.currentTimeMillis() / 1000;
        long[] rate = clickRate.compute(player.getUniqueId(), (k, r) -> {
            if (r == null || r[0] != second) return new long[] {second, 1};
            r[1]++;
            return r;
        });
        return rate[1] <= CLICKS_PER_SECOND;
    }

    /** Closes the player's dialog after close-delay-ticks, unless another dialog reaches them first. */
    public void closeUnlessShown(Player player) {
        long before = shown.getOrDefault(player.getUniqueId(), 0L);
        Runnable close = () -> {
            if (player.isOnline() && shown.getOrDefault(player.getUniqueId(), 0L) == before) closeDialog(player);
        };
        if (closeDelay == 0) close.run();
        else Bukkit.getScheduler().runTaskLater(plugin, close, closeDelay);
    }

    // ------------------------------------------------------------------ showing

    /** Draws a session's dialog. The one place a dialog reaches a player. */
    public boolean show(Player player, Session session, boolean reopened) {
        // A session is one player's: drawn for anybody else, its values would be theirs to act on.
        if (!player.getUniqueId().equals(session.viewer)) return false;
        if (drawn.has(session.dialogId)) {
            if (!player.isOnline()) return false;
            if (drawnPack.needs(player)) {
                // Drawn before the pack reaches them, it would be a box of missing glyphs: send it, and open this once it has loaded.
                // Not sent (the console says why): shown anyway, since its buttons still work.
                waitingForPack.put(player.getUniqueId(), () -> {
                    if (player.isOnline()) show(player, session, reopened);
                });
                if (drawnPack.send(player)) {
                    tell(player, message("pack-loading", "<gray>Loading this menu's resource pack..."));
                    return true;
                }
                waitingForPack.remove(player.getUniqueId());
            }
            DialogOpenEvent event = new DialogOpenEvent(player, session.dialogId, session.values, reopened);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !drawn.show(player, session, reopened)) return false;
            sessions.put(player.getUniqueId(), session);
            shown.merge(player.getUniqueId(), 1L, Long::sum);
            // The YAML dialog it replaced is gone from the screen, and its buttons with it.
            clicks.forget(player.getUniqueId());
            return true;
        }
        DialogDef def = dialogs.get(session.dialogId);
        if (def == null || !player.isOnline()) return false;
        def.defaults().forEach(session.values::putIfAbsent);
        DialogOpenEvent event = new DialogOpenEvent(player, def.id(), session.values, reopened);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        try {
            long drawing = shown.getOrDefault(player.getUniqueId(), 0L) + 1;
            if (def.json() != null) {
                showJson(player, def, session);
            } else {
                Renderer.Drawn drawn = renderer.draw(def, session, player, drawing);
                if (!Platform.showOwn(player, drawn.dialog())) throw new IllegalStateException("no route to the screen");
            }
            sessions.put(player.getUniqueId(), session);
            shown.put(player.getUniqueId(), drawing);
            // Only the screen the player now has can be pressed: the one before is gone.
            clicks.retire(player.getUniqueId(), drawing);
            drawn.retire(player);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not show " + def.id() + " to " + player.getName() + ": " + e.getMessage(), e);
            Platform.send(player, Text.MM.deserialize(message("broken", "<red>That dialog could not be shown. The console says why.")));
            return false;
        }
    }

    private void showJson(Player player, DialogDef def, Session session) {
        String raw = jsonCache.computeIfAbsent(def.json(), path -> {
            try {
                return Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path, e);
            }
        });
        Scope scope = new Scope(this, player, def.id(), session.values, session.untrusted, null, null);
        String filled = Text.fill(raw, scope, Text.Mode.JSON);
        // Minified, so the inline argument is one line. The game's SNBT reads JSON objects.
        String json = new Gson().toJson(JsonParser.parseString(filled));
        Platform.showJson(player, json, null);
    }

    /** Opens a dialog the way a player asked for it: with the permission check, and with their words untrusted. */
    public boolean openAsPlayer(Player player, String id, Map<String, String> values, Set<String> untrusted, Session previous) {
        DialogDef def = dialogs.get(id);
        if (def == null && drawn.has(id)) def = drawn.get(id).map(DrawnDialogs.Drawn::def).orElse(null);
        if (def == null) {
            Platform.send(player, Text.MM.deserialize(message("unknown-dialog", "<red>There is no dialog called {id}.").replace("{id}", Text.MM.escapeTags(id))));
            return false;
        }
        if (def.permission() != null && !player.hasPermission(def.permission())) {
            Platform.send(player, Text.MM.deserialize(message("no-permission", "<red>You can't open that.")));
            return false;
        }
        Session s = previous == null
                ? new Session(player.getUniqueId(), id, values, untrusted, null)
                : previous.next(id, values, untrusted);
        return show(player, s, false);
    }

    // ------------------------------------------------------------------ clicks

    /**
     * A button press. {@code drawing} is the drawing the button was bound to,
     * and the press only counts while that is still the one on the player's
     * screen. Whatever hid the button when it was drawn ({@code permission:},
     * {@code if:}) is asked again, because a button can be pressed up to
     * {@code click-lifetime-minutes} later and the answer may have changed.
     */
    public void onClick(Player player, long drawing, DialogDef def, ButtonDef button, Session session, String alias, Map<String, String> row,
                 List<InputDef> inputs, Map<String, Map<String, String>> optionLabels, Clicks.Payload view) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> onClick(player, drawing, def, button, session, alias, row, inputs, optionLabels, view));
            return;
        }
        if (!player.isOnline() || !player.getUniqueId().equals(session.viewer)) return;
        // An old screen (a newer one has been sent, so the player is about to see
        // it, and closing would close that), or too many clicks: nothing happens.
        if (shown.getOrDefault(player.getUniqueId(), 0L) != drawing || !allowClick(player)) return;
        Scope drawnScope = new Scope(this, player, def.id(), session.values, session.untrusted, null, null);
        if (alias != null && !row.isEmpty()) drawnScope = drawnScope.withRow(alias, row);
        if ((button.permission() != null && !player.hasPermission(button.permission())) || !Conditions.test(button.condition(), drawnScope)) {
            Platform.send(player, Text.MM.deserialize(message("no-permission", "<red>You can't do that.")));
            if (def.after() == Defs.After.WAIT_FOR_RESPONSE) closeUnlessShown(player);
            return;
        }

        Map<String, String> typed = new LinkedHashMap<>();
        Set<String> untrusted = new HashSet<>(session.untrusted);
        Map<String, String> values = new LinkedHashMap<>(session.values);
        for (InputDef in : inputs) {
            String key = in.key();
            String v = null;
            if (view != null) {
                switch (in.kind()) {
                    case TEXT -> {
                        // The client enforces max-length, so a modified one need not:
                        // clip here, before validation, actions or a pattern see it.
                        v = view.text(key);
                        if (v != null && v.length() > in.maxLength()) v = v.substring(0, in.maxLength());
                    }
                    case OPTIONS -> {
                        v = view.text(key);
                        Map<String, String> labels = optionLabels.getOrDefault(key, Map.of());
                        // A modified client can send anything: only an option we drew counts.
                        if (v != null && !labels.containsKey(v)) v = null;
                        if (v != null) values.put(key + ".label", labels.get(v));
                    }
                    case TOGGLE -> {
                        Boolean b = view.bool(key);
                        if (b != null) {
                            v = b.toString();
                            String text = b ? in.onTrue() : in.onFalse();
                            if (text != null) values.put(key + ".text", text);
                        }
                    }
                    case SLIDER -> {
                        Float f = view.number(key);
                        if (f != null && !f.isNaN()) v = Text.number(Math.max(in.min(), Math.min(in.max(), f)));
                    }
                }
            }
            if (v == null) continue;
            typed.put(key, v);
            values.put(key, v);
            if (in.kind() == InputKind.TEXT) untrusted.add(key);
            else untrusted.remove(key);
        }
        values.remove("error");

        if (button.validate()) {
            for (InputDef in : inputs) {
                String failure = validate(in, typed.get(in.key()), player, values, untrusted, def);
                if (failure == null) continue;
                DialogInputErrorEvent event = new DialogInputErrorEvent(player, def.id(), in.key(), Map.copyOf(typed), failure);
                Bukkit.getPluginManager().callEvent(event);
                values.put("error", event.getMessage());
                untrusted.remove("error");
                show(player, session.with(values, untrusted), true);
                return;
            }
        }

        ClickCtx ctx = new ClickCtx(this, player, def, session.with(values, untrusted), button.id(), typed, alias, row);
        List<String> list = new ArrayList<>(button.actions());
        DialogClickEvent event = new DialogClickEvent(ctx, list);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            ctx.finish();
            return;
        }
        actions.run(ctx, list, 0);
    }

    private String validate(InputDef in, String value, Player player, Map<String, String> values, Set<String> untrusted, DialogDef def) {
        if (in.kind() != InputKind.TEXT) return null;
        String v = value == null ? "" : value;
        Scope scope = new Scope(this, player, def.id(), values, untrusted, null, null);
        String label = Text.MM.stripTags(Text.fill(in.label(), scope, Text.Mode.TEXT));
        if (label.isBlank()) label = in.key();
        String custom = in.error();
        if (in.required() && v.isBlank()) {
            return custom != null ? custom : message("required", "{label} can't be empty.").replace("{label}", label);
        }
        if (!v.isBlank() && in.minLength() > 0 && v.length() < in.minLength()) {
            return custom != null ? custom : message("too-short", "{label} needs at least {min} characters.")
                    .replace("{label}", label).replace("{min}", String.valueOf(in.minLength()));
        }
        if (!v.isBlank() && in.pattern() != null) {
            try {
                if (!Pattern.compile(in.pattern()).matcher(v).matches()) {
                    return custom != null ? custom : message("invalid", "{label} isn't valid.").replace("{label}", label);
                }
            } catch (PatternSyntaxException e) {
                warnOnce(def.id() + " input " + in.key() + " has a bad pattern: " + e.getDescription());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ lookups used while drawing

    public String resolvePlaceholder(Player viewer, DialogContext context, String key) {
        List<Owned<Map.Entry<String, PlaceholderResolver>>> snapshot;
        synchronized (resolvers) {
            snapshot = List.copyOf(resolvers);
        }
        for (Owned<Map.Entry<String, PlaceholderResolver>> r : snapshot) {
            String prefix = r.value().getKey();
            if (key.length() > prefix.length() && key.regionMatches(true, 0, prefix, 0, prefix.length())) {
                try {
                    String v = r.value().getValue().resolve(viewer, context, key.substring(prefix.length()));
                    if (v != null) return v;
                } catch (RuntimeException e) {
                    warnOnce("Placeholder " + key + " from " + r.owner().getName() + " threw " + e);
                }
            }
        }
        return null;
    }

    public List<Map<String, String>> rows(String list, Scope scope) {
        DialogDef def = dialogs.get(scope.dialogId);
        if (def != null && def.lists().containsKey(list)) return def.lists().get(list);
        Owned<ListProvider> provider = lists.get(list.toLowerCase(Locale.ROOT));
        if (provider != null) {
            try {
                List<Map<String, String>> rows = provider.value().rows(scope.viewer, scope);
                return rows == null ? List.of() : rows;
            } catch (RuntimeException e) {
                warnOnce("List " + list + " from " + provider.owner().getName() + " threw " + e);
                return List.of();
            }
        }
        List<Map<String, String>> builtIn = BuiltInLists.rows(list, scope);
        if (builtIn != null) return builtIn;
        warnOnce(scope.dialogId + " repeats over a list called " + list + ", which nothing provides");
        return List.of();
    }

    public ActionHandler customAction(String tag) {
        Owned<ActionHandler> h = customActions.get(tag);
        return h == null ? null : h.value();
    }

    /** Runs an action list, as a button's. */
    public void run(ClickCtx ctx, List<String> list) {
        actions.run(ctx, list, 0);
    }

    public void remember(Player player, Session session) {
        sessions.put(player.getUniqueId(), session);
    }

    Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ------------------------------------------------------------------ API

    @Override
    public boolean open(Player player, String dialogId, Map<String, String> values) {
        if (player == null || !player.isOnline()) return false;
        String id = dialogId.toLowerCase(Locale.ROOT);
        if (drawn.has(id)) return drawn.open(player, id, values, Set.of());
        if (!dialogs.containsKey(id)) return false;
        return show(player, new Session(player.getUniqueId(), id, values == null ? Map.of() : values, Set.of(), null), false);
    }

    @Override
    public void close(Player player) {
        sessions.remove(player.getUniqueId());
        closeDialog(player);
    }

    @Override
    public boolean refresh(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return false;
        return show(player, s.with(s.values, s.untrusted), true);
    }

    @Override
    public Optional<String> current(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? Optional.empty() : Optional.of(s.dialogId);
    }

    @Override
    public Map<String, String> values(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(s.values));
    }

    @Override
    public Set<String> ids() {
        if (drawn.ids().isEmpty()) return Collections.unmodifiableSet(dialogs.keySet());
        Set<String> all = new java.util.LinkedHashSet<>(dialogs.keySet());
        all.addAll(drawn.ids());
        return Collections.unmodifiableSet(all);
    }

    @Override
    public Optional<DialogInfo> info(String dialogId) {
        DialogDef d = dialogs.get(dialogId.toLowerCase(Locale.ROOT));
        if (d == null) return Optional.empty();
        return Optional.of(new DialogInfo(d.id(), d.source(), Optional.ofNullable(d.command()), Optional.ofNullable(d.permission()),
                d.args(), d.inputs().stream().map(InputDef::key).toList()));
    }

    @Override
    public boolean canOpen(Player player, String dialogId) {
        if (drawn.has(dialogId)) return drawn.get(dialogId).map(d -> d.permission() == null || player.hasPermission(d.permission())).orElse(false);
        DialogDef d = dialogs.get(dialogId.toLowerCase(Locale.ROOT));
        return d != null && (d.permission() == null || player.hasPermission(d.permission()));
    }

    @Override
    public void registerFolder(Plugin owner, String namespace, File folder) {
        String ns = namespace.toLowerCase(Locale.ROOT);
        if (!ns.matches("[a-z0-9_.\\-]+")) throw new IllegalArgumentException("namespace must be a-z, 0-9, _ . -");
        folders.removeIf(f -> ns.equals(f.namespace()));
        folders.add(new Folder(owner, ns, folder));
        load();
    }

    @Override
    public boolean installDrawn(Plugin owner, String namespace) {
        String ns = namespace.toLowerCase(Locale.ROOT);
        if (!ns.matches("[a-z0-9_.\\-]+") || ns.equals("samples")) throw new IllegalArgumentException("namespace must be a-z, 0-9, _ . - and not samples");
        boolean changed;
        try {
            changed = DrawnInstall.install(owner, ns, new File(drawn.folder(), ns).toPath());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning(owner.getName() + "'s drawn dialogs could not be installed: " + e.getMessage());
            return false;
        }
        if (changed) {
            plugin.getLogger().info("Installed " + owner.getName() + " " + owner.getDescription().getVersion() + "'s drawn dialogs into drawn/" + ns + "/.");
            reload();
        }
        return changed;
    }

    @Override
    public Optional<String> setting(Player player, String name) {
        return drawn.setting(player, name);
    }

    @Override
    public void registerDialog(Plugin owner, String dialogId, ConfigurationSection definition) {
        String id = dialogId.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) throw new IllegalArgumentException("a dialog id is namespace:name");
        codeDialogs.put(id, new CodeDialog(owner, id, definition));
        load();
    }

    @Override
    public void registerAction(Plugin owner, String tag, ActionHandler handler) {
        customActions.put(tag.toLowerCase(Locale.ROOT).replace("[", "").replace("]", ""), new Owned<>(owner, handler));
    }

    @Override
    public void registerPlaceholders(Plugin owner, String prefix, PlaceholderResolver resolver) {
        synchronized (resolvers) {
            resolvers.removeIf(r -> r.value().getKey().equalsIgnoreCase(prefix));
            resolvers.add(new Owned<>(owner, Map.entry(prefix, resolver)));
            // Longest prefix first, so "shop_vip_" beats "shop_".
            resolvers.sort((a, b) -> b.value().getKey().length() - a.value().getKey().length());
        }
    }

    @Override
    public void registerList(Plugin owner, String name, ListProvider provider) {
        lists.put(name.toLowerCase(Locale.ROOT), new Owned<>(owner, provider));
    }

    @Override
    public void unregister(Plugin owner) {
        boolean dialogsChanged = folders.removeIf(f -> f.owner() == owner && f.namespace() != null)
                | codeDialogs.values().removeIf(c -> c.owner() == owner);
        customActions.values().removeIf(o -> o.owner() == owner);
        lists.values().removeIf(o -> o.owner() == owner);
        synchronized (resolvers) {
            resolvers.removeIf(o -> o.owner() == owner);
        }
        if (dialogsChanged && plugin.isEnabled()) load();
    }

    @Override
    public String escape(String untrusted) {
        return untrusted == null ? "" : Text.MM.escapeTags(untrusted);
    }

    @Override
    public String textJson(Player viewer, String miniMessage, Map<String, String> values) {
        Scope scope = new Scope(this, viewer, "", new LinkedHashMap<>(values == null ? Map.of() : values), Set.of(), null, null);
        return Text.json(miniMessage, scope).toString();
    }

    @Override
    public void reload() {
        load();
        Bukkit.getPluginManager().callEvent(new DialogEngineReloadEvent(dialogs.size()));
    }

    OpenCommands commands() {
        return commands;
    }

    public Map<String, DialogDef> all() {
        return dialogs;
    }
}
