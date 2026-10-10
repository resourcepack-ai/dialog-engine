package ai.resourcepack.dialogs.api;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a plugin can do with Dialog Engine. Main thread only, like the
 * rest of Bukkit.
 *
 * <p>Dialogs are written in YAML (see the README) and identified as
 * {@code namespace:name}. A plugin ships its own by handing the engine a
 * folder with {@link #registerFolder}; it extends what those dialogs can do
 * with its own {@link #registerAction actions}, {@link #registerPlaceholders
 * placeholders} and {@link #registerList lists}; and it hears what players do
 * through {@link ai.resourcepack.dialogs.api.event.DialogClickEvent} and
 * {@link ai.resourcepack.dialogs.api.event.DialogOpenEvent}.
 *
 * <p>Everything registered by a plugin is dropped when that plugin disables.
 */
public interface DialogEngineAPI {

    /** Opens a dialog on a player. False when it does not exist, the player is offline, or an event cancelled it. */
    default boolean open(Player player, String dialogId) {
        return open(player, dialogId, Map.of());
    }

    /**
     * Opens a dialog with values for its {@code {placeholders}}. These are
     * trusted: they are read as MiniMessage where the dialog prints them, so
     * pass anything a player typed through {@link #escape} first.
     */
    boolean open(Player player, String dialogId, Map<String, String> values);

    /** Closes whatever dialog the player has open. */
    void close(Player player);

    /** Opens the player's current dialog again with its current values. False when they have none. */
    boolean refresh(Player player);

    /** The id of the dialog the engine last showed the player, if it is still theirs. */
    Optional<String> current(Player player);

    /** The values the player's current dialog was drawn with: its placeholders, then what they typed. */
    Map<String, String> values(Player player);

    /** Every dialog id the engine has. */
    Set<String> ids();

    /** What a dialog is, or empty when there is no such dialog. */
    Optional<DialogInfo> info(String dialogId);

    /** True when the player may open the dialog themselves (its {@code permission:}). */
    boolean canOpen(Player player, String dialogId);

    /**
     * Loads every {@code .yml} in a folder (and its subfolders) as dialogs in
     * {@code namespace}, now and on every reload. This is how a plugin ships
     * dialogs a server owner can edit: copy defaults into your data folder,
     * then register that folder.
     */
    void registerFolder(Plugin owner, String namespace, File folder);

    /** Registers one dialog from a configuration section (handy for dialogs built in code). Replaced on reload only if registered again. */
    void registerDialog(Plugin owner, String dialogId, ConfigurationSection definition);

    /**
     * A new action dialogs can run: {@code [tag] argument} in a button's
     * {@code actions:}. The argument arrives with its placeholders filled.
     * Tags are case-insensitive; prefix them with your plugin's name to stay
     * clear of other plugins ({@code myplugin:give}).
     */
    void registerAction(Plugin owner, String tag, ActionHandler handler);

    /**
     * Placeholders starting with {@code prefix}: {@code {prefix_anything}} in
     * a dialog asks the resolver for {@code anything}. Values are read as
     * MiniMessage, so escape player-written text with {@link #escape}.
     */
    void registerPlaceholders(Plugin owner, String prefix, PlaceholderResolver resolver);

    /**
     * A list dialogs can repeat over with {@code for-each: name}: buttons,
     * body lines and an input's options. Each row is a map of fields, read in
     * the dialog as {@code {row.field}}.
     */
    void registerList(Plugin owner, String name, ListProvider provider);

    /** Drops everything a plugin registered. Done for you when it disables. */
    void unregister(Plugin owner);

    /** Escapes text so MiniMessage prints it as written. */
    String escape(String untrusted);

    /**
     * Parses MiniMessage with the engine's placeholder rules, for a plugin that
     * wants its own messages to match, and returns the game's JSON text
     * component. On Paper, {@code GsonComponentSerializer.gson().deserialize(json)}
     * makes it a Component; on Spigot, {@code ComponentSerializer.deserialize(json)}
     * makes it BungeeCord chat.
     */
    String textJson(Player viewer, String miniMessage, Map<String, String> values);

    /** Reads every dialog again, then fires {@link ai.resourcepack.dialogs.api.event.DialogEngineReloadEvent}. */
    void reload();
}
