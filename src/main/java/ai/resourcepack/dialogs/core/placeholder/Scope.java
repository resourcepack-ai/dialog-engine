package ai.resourcepack.dialogs.core.placeholder;

import ai.resourcepack.dialogs.api.DialogContext;
import ai.resourcepack.dialogs.core.Engine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Where a placeholder is looked up: the row a for-each is drawing, then the
 * dialog's own values, then the engine's built-ins, then the resolvers other
 * plugins registered. A name nothing knows is left as written.
 */
public final class Scope implements DialogContext {

    final Engine engine;
    public final Player viewer;
    public final String dialogId;
    public final Map<String, String> values;
    final Set<String> untrusted;
    public final String alias;
    public final Map<String, String> row;

    public Scope(Engine engine, Player viewer, String dialogId, Map<String, String> values, Set<String> untrusted,
          String alias, Map<String, String> row) {
        this.engine = engine;
        this.viewer = viewer;
        this.dialogId = dialogId;
        this.values = values;
        this.untrusted = untrusted;
        this.alias = alias;
        this.row = row;
    }

    public Scope withRow(String alias, Map<String, String> row) {
        return new Scope(engine, viewer, dialogId, values, untrusted, alias, row);
    }

    /** The value for {@code key}, or null when nothing knows it. */
    public String lookup(String key) {
        if (row != null && alias != null) {
            if (key.equals(alias)) return row.getOrDefault("value", row.getOrDefault("id", ""));
            if (key.startsWith(alias + ".")) {
                String field = key.substring(alias.length() + 1);
                String v = row.get(field);
                if (v != null) return v;
            }
        }
        String v = values.get(key);
        if (v != null) return v;
        v = builtIn(key);
        if (v != null) return v;
        return engine.resolvePlaceholder(viewer, this, key);
    }

    boolean isUntrusted(String key) {
        if (untrusted.contains(key)) return true;
        // A display name is whatever a nickname plugin let the player type, so
        // it is their words, like an input, unless the dialog set its own.
        return key.equals("player_display") && !values.containsKey(key);
    }

    private String builtIn(String key) {
        try {
            return builtInOf(key);
        } catch (RuntimeException e) {
            // A joining player's stand-in (core/quick) is in no world yet: what
            // needs one draws as nothing.
            return "";
        }
    }

    private String builtInOf(String key) {
        return switch (key) {
            case "player", "player_name" -> viewer.getName();
            case "player_uuid" -> viewer.getUniqueId().toString();
            case "player_display" -> org.bukkit.ChatColor.stripColor(viewer.getDisplayName());
            case "player_world" -> viewer.getWorld().getName();
            case "player_x" -> String.valueOf(viewer.getLocation().getBlockX());
            case "player_y" -> String.valueOf(viewer.getLocation().getBlockY());
            case "player_z" -> String.valueOf(viewer.getLocation().getBlockZ());
            case "player_health" -> String.valueOf((int) Math.ceil(viewer.getHealth()));
            case "player_level" -> String.valueOf(viewer.getLevel());
            case "player_gamemode" -> viewer.getGameMode().name().toLowerCase(java.util.Locale.ROOT);
            case "online" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            case "max_players" -> String.valueOf(Bukkit.getMaxPlayers());
            case "dialog" -> dialogId;
            default -> null;
        };
    }

    // DialogContext, for resolvers and list providers.

    @Override
    public Player viewer() {
        return viewer;
    }

    @Override
    public String dialogId() {
        return dialogId;
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public Map<String, String> values() {
        return Collections.unmodifiableMap(values);
    }
}
