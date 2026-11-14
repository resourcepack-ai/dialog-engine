package ai.resourcepack.dialogs.core.placeholder;

import ai.resourcepack.dialogs.core.Engine;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * What Dialog Engine knows, as {@code %dialogengine_...%} placeholders, so a
 * scoreboard, a chat format, a hologram or another plugin's config can read a
 * player's dialog settings without an API.
 *
 * <p>The one that matters is {@code %dialogengine_setting_<name>%}: what a
 * drawn dialog's switch, slider, dropdown or segmented control bound to
 * {@code <name>} is set to for this player, or, until they have clicked it,
 * the default the dialog shows. {@code var_<name>} is the same, spelled as RP
 * Engine spells it, so a config moving between the two changes only the prefix.
 *
 * <p><strong>It answers questions and never performs actions.</strong> A
 * placeholder is evaluated wherever text is drawn, several times a tick on a
 * scoreboard and off the main thread for some plugins, so everything here is a
 * map lookup or a read of the player's data.
 *
 * <p><strong>This class is the only one that names PlaceholderAPI's
 * types</strong> ({@link Papi} reaches it by reflection), and it is only loaded
 * once the plugin has checked PlaceholderAPI is there, so a server without it
 * never loads it.
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final Engine engine;

    private PapiExpansion(Plugin plugin, Engine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    /** Registers the expansion. Call only when PlaceholderAPI is enabled. */
    public static boolean register(Plugin plugin, Engine engine) {
        return new PapiExpansion(plugin, engine).register();
    }

    @Override
    public String getIdentifier() {
        return "dialogengine";
    }

    @Override
    public String getAuthor() {
        return "ResourcePack AI";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Survives /papi reload, or every placeholder here would print as typed.
        return true;
    }

    /**
     * One placeholder. Null means "not one of ours", which PlaceholderAPI
     * prints as written: a typo shows rather than reading as an empty value.
     */
    @Override
    public String onRequest(OfflinePlayer who, String params) {
        String name = params == null ? "" : params.toLowerCase(Locale.ROOT);

        // Server-wide: answer for an offline player and the console too.
        switch (name) {
            case "version":
                return plugin.getDescription().getVersion();
            case "dialogs":
                return String.valueOf(engine.ids().size());
            default:
                break;
        }

        String setting = settingName(name);
        if (setting == null && !name.equals("open") && !name.equals("dialog")
                && !name.startsWith("value_") && !name.startsWith("can_open_")) {
            return null;
        }
        // A setting nobody declares and nobody holds is a typo: shown as written.
        if (setting != null && !engine.drawn.declares(setting) && !heldBy(who, setting)) {
            return null;
        }

        Player player = who == null ? null : who.getPlayer();
        if (player == null) {
            // Everything below is about somebody who is here. Empty rather
            // than "false", which would be a claim about an absent player.
            return "";
        }

        if (setting != null) {
            return engine.drawn.settingOrDefault(player, setting).orElse("");
        }
        switch (name) {
            case "open":
                return String.valueOf(engine.current(player).isPresent());
            case "dialog":
                return engine.current(player).orElse("");
            default:
                break;
        }
        if (name.startsWith("value_")) {
            String value = engine.values(player).get(params.substring("value_".length()));
            return value == null ? "" : value;
        }
        // can_open_<id>: whether this player may open that dialog, false for one that does not exist.
        String id = name.substring("can_open_".length());
        return String.valueOf(engine.has(id) && engine.canOpen(player, id));
    }

    /** {@code setting_x} or {@code var_x} → {@code x}; null for anything else. */
    private static String settingName(String name) {
        if (name.startsWith("setting_") && name.length() > "setting_".length()) return name.substring("setting_".length());
        if (name.startsWith("var_") && name.length() > "var_".length()) return name.substring("var_".length());
        return null;
    }

    /** Whether this player holds a value for the name, for a setting no loaded dialog declares any more. */
    private boolean heldBy(OfflinePlayer who, String setting) {
        Player player = who == null ? null : who.getPlayer();
        return player != null && engine.drawn.setting(player, setting).isPresent();
    }
}
