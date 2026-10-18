package ai.resourcepack.dialogs.core.quick;

import ai.resourcepack.common.quick.JoinCopies;
import ai.resourcepack.common.quick.QuickActionPack;
import ai.resourcepack.common.quick.StandIn;
import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.Session;
import ai.resourcepack.dialogs.core.click.Clicks;
import ai.resourcepack.dialogs.core.drawn.DialogPackets;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import ai.resourcepack.dialogs.core.yaml.Defs;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Renderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * {@code quick-action:}, dialogs on the game's Quick Actions key (G).
 *
 * <p>The key is the CLIENT's: it opens a dialog out of the registry the player
 * was sent as they joined, and the server never hears of the press. So every
 * dialog on it is a file in a datapack ({@link QuickActionPack}, read when the
 * server starts, so a change needs a restart), and there are two ways to make
 * that file a dialog a player can use:
 *
 * <ul>
 *   <li><b>direct</b>, the default: the key opens the dialog itself. Each
 *       player's copy is drawn for them as they join ({@link #prepare}, from
 *       the pre-login event) and swapped into the registry packet they are
 *       sent ({@link JoinCopies}). Its values are what was true then, and its
 *       buttons are pinned to that player until they leave ({@link Clicks}).
 *       Paper only.</li>
 *   <li><b>launcher</b>: the key opens a one-button screen that asks the
 *       server for the dialog, which is then drawn fresh like any other. Also
 *       what a direct dialog falls back to wherever a copy cannot be made:
 *       Spigot, a dialog with a {@code permission:} (a joining player's
 *       permissions are not known yet), a JSON or drawn dialog, or a copy the
 *       game would not read.</li>
 * </ul>
 *
 * The file in the datapack is always the launcher, so the fallback is simply
 * the copy not being swapped in.
 */
public final class QuickActions {

    private static final String PACK = "dialogengine_quick";
    private static final String NAMESPACE = "dialogengine";
    /** A placeholder in a label: the file is everybody's, so it is left out rather than shown in braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");

    private final Engine engine;
    /** Registry id to the dialog on the key there, in the order the key lists them. */
    private volatile Map<String, DialogDef> onKey = Map.of();
    private JoinCopies copies;
    private boolean installTried;
    /** What each pinned drawing was sent as, by player: a click on it answers like its screen. */
    private final Map<UUID, Map<Long, Defs.After>> sentAs = new ConcurrentHashMap<>();
    /** Pinned drawings count down from -1, so they never meet a screen's number. */
    private final AtomicLong pins = new AtomicLong();

    public QuickActions(Engine engine) {
        this.engine = engine;
        engine.clicks().onQuick(this::launch);
    }

    /** After every load: writes the datapack and says what changed. */
    public void load(Collection<DialogDef> dialogs) {
        Map<String, DialogDef> next = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        for (DialogDef def : dialogs) {
            if (def.quick() == null) continue;
            String id = NAMESPACE + ":quick/" + path(def.id());
            String launcher = launcher(def).toString();
            String refused = DialogPackets.refusal(launcher);
            if (refused != null) {
                engine.plugin().getLogger().warning(def.id() + " is left off the Quick Actions key: the game will not read its launcher ("
                        + refused + ")");
                continue;
            }
            next.put(id, def);
            files.put(id, launcher);
        }
        onKey = Map.copyOf(next);

        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
        try {
            boolean changed = QuickActionPack.write(worlds.get(0), PACK, "Dialog Engine quick actions. Generated; edits are overwritten.", files);
            if (changed) {
                engine.plugin().getLogger().info(files.isEmpty()
                        ? "Nothing is on the Quick Actions key any more. That takes effect when the server next restarts."
                        : "The Quick Actions key (G) now opens " + String.join(", ", next.values().stream().map(DialogDef::id).toList())
                        + ". That takes effect when the server next restarts.");
            }
        } catch (IOException | RuntimeException e) {
            engine.plugin().getLogger().log(Level.WARNING, "Could not write the quick actions datapack", e);
        }

        boolean direct = next.values().stream().anyMatch(this::directable);
        if (direct && !installTried) {
            installTried = true;
            copies = JoinCopies.install(engine.plugin());
            if (copies == null) {
                engine.plugin().getLogger().info("This server cannot give each player their own copy of a quick action"
                        + " (that needs Paper), so the Quick Actions key opens a launcher for every dialog on it.");
            }
        }
    }

    /**
     * Draws a joining player's copies, ready for the registry packet. Called
     * from the pre-login event, which is off the main thread and may wait:
     * the drawing itself is on the main thread, like every other.
     */
    public void prepare(UUID player, String name) {
        if (copies == null || onKey.values().stream().noneMatch(this::directable)) return;
        Map<String, String> drawn;
        try {
            drawn = Bukkit.getScheduler().callSyncMethod(engine.plugin(), () -> draw(player, name)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            engine.warnOnce("Could not draw a joining player's quick actions in time, so they get the launcher: " + e);
            return;
        }
        copies.put(player, drawn).forEach((id, why) ->
                engine.warnOnce("The Quick Actions key opens the launcher for " + onKey.get(id).id() + ": the game will not read it as drawn (" + why + ")"));
        // A login refused after this point never reaches quit, so its copies go here.
        Bukkit.getScheduler().runTaskLater(engine.plugin(), () -> {
            if (Bukkit.getPlayer(player) == null) forget(player);
        }, 20L * 60);
    }

    private Map<String, String> draw(UUID player, String name) {
        engine.clicks().forgetPinned(player);
        sentAs.remove(player);
        Player standIn = StandIn.of(player, name);
        Map<String, String> out = new LinkedHashMap<>();
        Map<Long, Defs.After> after = new ConcurrentHashMap<>();
        onKey.forEach((id, def) -> {
            if (!directable(def)) return;
            try {
                long drawing = -pins.incrementAndGet();
                Session session = new Session(player, def.id(), def.defaults(), Set.of(), null);
                Renderer.Drawn d = engine.renderer().draw(def, session, standIn, drawing);
                JsonObject dialog = d.dialog();
                // The key's list names it: by its label, else by its title as drawn for them.
                if (def.quick().label() != null) dialog.add("external_title", label(def));
                else if (!dialog.has("external_title")) dialog.add("external_title", dialog.get("title").deepCopy());
                after.put(drawing, d.after());
                out.put(id, dialog.toString());
            } catch (RuntimeException e) {
                engine.warnOnce("Could not draw " + def.id() + " for the Quick Actions key, so it opens the launcher: " + e);
            }
        });
        sentAs.put(player, after);
        return out;
    }

    /** What a click on pinned drawing {@code drawing} answers like. */
    public Defs.After sentAs(UUID player, long drawing, Defs.After otherwise) {
        Map<Long, Defs.After> mine = sentAs.get(player);
        Defs.After after = mine == null ? null : mine.get(drawing);
        return after == null ? otherwise : after;
    }

    public void forget(UUID player) {
        sentAs.remove(player);
        if (copies != null) copies.forget(player);
    }

    public void shutdown() {
        if (copies != null) copies.close();
        copies = null;
        sentAs.clear();
    }

    /** A launcher's button: open the dialog it names, if it is one on the key, as if the player had asked. */
    private void launch(Player player, String dialogId) {
        Bukkit.getScheduler().runTask(engine.plugin(), () -> {
            if (!player.isOnline()) return;
            boolean onIt = onKey.values().stream().anyMatch(d -> d.id().equals(dialogId));
            if (!onIt || !engine.allowClick(player) || !engine.openAsPlayer(player, dialogId, Map.of(), Set.of(), null)) {
                engine.closeDialog(player);
            }
        });
    }

    /** Whether a player can be given their own copy of it as they join. */
    private boolean directable(DialogDef def) {
        return !def.quick().launcher() && def.permission() == null && def.json() == null && !engine.drawn.has(def.id());
    }

    /** The file: the dialog's name, and one button that asks for it. */
    private JsonObject launcher(DialogDef def) {
        JsonObject additions = new JsonObject();
        additions.addProperty(Clicks.QUICK_DIALOG, def.id());
        JsonObject action = new JsonObject();
        action.addProperty("type", "minecraft:dynamic/custom");
        action.addProperty("id", Clicks.QUICK_ID);
        action.add("additions", additions);
        JsonObject button = new JsonObject();
        button.add("label", Text.json(Text.MM.deserialize(engine.message("quick-open", "Open"))));
        button.add("action", action);
        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "minecraft:notice");
        JsonElement label = label(def);
        dialog.add("title", label);
        dialog.add("external_title", label);
        dialog.add("action", button);
        // The dialog it opens replaces this screen; closing first would flash the world.
        dialog.addProperty("after_action", "none");
        dialog.addProperty("pause", false);
        return dialog;
    }

    /** What the key's list calls it: the label, else the dialog's title, with any placeholder left out. */
    private static JsonElement label(DialogDef def) {
        String raw = def.quick().label() != null ? def.quick().label()
                : def.externalTitle() != null ? def.externalTitle() : def.title();
        String plain = PLACEHOLDER.matcher(raw == null ? "" : raw).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        if (Platform.plain(Text.MM.deserialize(plain)).isBlank()) plain = Text.MM.escapeTags(def.id());
        return Text.json(Text.MM.deserialize(plain));
    }

    /** A dialog id as a path in the datapack: {@code server:menu} is {@code quick/server/menu}. */
    static String path(String id) {
        return id.toLowerCase(Locale.ROOT).replace(':', '/').replaceAll("[^a-z0-9_./-]", "_").replace("..", "_");
    }
}
