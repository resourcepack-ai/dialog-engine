package ai.resourcepack.dialogs.core.platform;

import ai.resourcepack.dialogs.core.drawn.DialogPackets;
import ai.resourcepack.dialogs.core.drawn.DialogSnbt;
import ai.resourcepack.dialogs.core.yaml.Renderer;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Everything that differs between Spigot and Paper, in one place. The plugin
 * compiles against spigot-api, which Paper also implements, so the rest of the
 * code is the same on both; what is left is how a dialog reaches the screen
 * and how one is taken off it.
 *
 * <p>Text is Adventure throughout (shaded and relocated, so it is ours on both
 * servers) and leaves as BungeeCord chat, which both servers accept.
 */
public final class Platform {

    private Platform() {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    // ------------------------------------------------------------------ chat

    public static void send(CommandSender to, Component message) {
        if (to instanceof Player p) {
            p.spigot().sendMessage(BungeeComponentSerializer.get().serialize(message));
        } else {
            to.sendMessage(LEGACY.serialize(message));
        }
    }

    public static void broadcast(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) send(p, message);
        send(Bukkit.getConsoleSender(), message);
    }

    public static void actionBar(Player player, Component message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, BungeeComponentSerializer.get().serialize(message));
    }

    public static void title(Player player, Component title, Component subtitle) {
        player.sendTitle(LEGACY.serialize(title), LEGACY.serialize(subtitle), 10, 70, 20);
    }

    /** For a resource pack prompt and the like, which the Bukkit API takes as a legacy string. */
    public static String legacy(Component text) {
        return LEGACY.serialize(text);
    }

    public static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    public static void sound(Player player, String key, float volume, float pitch) {
        player.playSound(player.getLocation(), key, SoundCategory.MASTER, volume, pitch);
    }

    // ------------------------------------------------------------------ server

    /** The server's command map. Paper has {@code Bukkit.getCommandMap()}; both servers have CraftServer's. */
    public static org.bukkit.command.CommandMap commandMap() {
        try {
            return (org.bukkit.command.CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach the command map", e);
        }
    }

    /** The command map's own table, which {@code Command.unregister} leaves its entries in. */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, org.bukkit.command.Command> knownCommands(org.bukkit.command.CommandMap map) {
        try {
            return (java.util.Map<String, org.bukkit.command.Command>) map.getClass().getMethod("getKnownCommands").invoke(map);
        } catch (ReflectiveOperationException ignored) {
            // Spigot: no getter, only the protected field
        }
        for (Class<?> c = map.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (java.util.Map<String, org.bukkit.command.Command>) f.get(map);
            } catch (ReflectiveOperationException ignored) {
                // up a class
            }
        }
        return new java.util.HashMap<>();
    }

    /**
     * "1.21.8", from "1.21.8-R0.1-SNAPSHOT" (getMinecraftVersion is Paper's
     * alone), and "26.3" from Paper 26.x's "26.3.build.12-stable": the numbers
     * and dots it starts with, up to the first thing that is neither.
     */
    public static String minecraftVersion() {
        return leadingVersion(Bukkit.getBukkitVersion());
    }

    /** The release a version string starts with: its leading digits and dots, without a trailing dot. */
    public static String leadingVersion(String raw) {
        if (raw == null) return "";
        int end = 0;
        while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) end++;
        String out = raw.substring(0, end);
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** {@link #minecraftVersion()} as numbers: {major, minor, patch}, a part it does not have 0. */
    public static int[] minecraftVersionNumbers() {
        String[] parts = minecraftVersion().split("[.]");
        int[] out = new int[3];
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ dialogs

    /** Spigot 1.21.6+: {@code Player.showDialog(net.md_5.bungee.api.dialog.Dialog)}. Paper has no such method. */
    private static final boolean SPIGOT_DIALOGS = hasSpigotDialogs();

    private static boolean hasSpigotDialogs() {
        try {
            Player.class.getMethod("showDialog", Class.forName("net.md_5.bungee.api.dialog.Dialog"));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static String describe() {
        if (SPIGOT_DIALOGS) return "Spigot's dialog API";
        return "the server's dialog codec";
    }

    /**
     * Shows a dialog Dialog Engine drew (one {@link Renderer} wrote): through
     * Spigot's dialog API on Spigot, the server's own codec on Paper, and the
     * vanilla {@code /dialog show} command when neither is there.
     */
    public static boolean showOwn(Player player, JsonObject dialog) {
        if (SPIGOT_DIALOGS) {
            SpigotDialogs.show(player, dialog);
            return true;
        }
        return showJson(player, dialog.toString(), null);
    }

    /**
     * Shows any dialog JSON (a raw file, or one of Studio's): the server's
     * codec where it can be reached by name (Paper), otherwise the vanilla
     * command, which carries it inline as SNBT. Returns false, having said why
     * in the log, when neither will take it.
     *
     * <p>A dialog the codec READ and refused is not tried as a command: the
     * command decodes the same SNBT with the same codec against the same
     * registries, so it can only refuse it again, in words that name the
     * command rather than the dialog.
     */
    public static boolean showJson(Player player, String json, Logger log) {
        if (DialogPackets.show(player, json)) return true;
        String refusal = DialogPackets.lastRefusal();
        String why;
        if (refusal != null) {
            why = refusal;
        } else {
            Optional<String> inline = DialogSnbt.of(json);
            if (inline.isEmpty()) {
                why = "the server's codec could not be reached and the dialog has text the /dialog command cannot carry (a line break, most likely)";
            } else {
                why = dialogCommand(player, "show @s " + inline.get());
                if (why == null) return true;
            }
        }
        why = explain(why);
        if (log != null) log.warning("Could not show a dialog to " + player.getName() + ": " + why);
        else throw new IllegalStateException(why);
        return false;
    }

    /**
     * Runs {@code /dialog <rest>} for one player from the console: null when it
     * ran, else what the game said was wrong. {@code rest} starts {@code show @s}
     * or {@code clear @s}.
     *
     * <p>The player is {@code @s} under {@code execute as <uuid>}, never named in
     * {@code /dialog} itself. Not by name: on an offline-mode server a name is
     * whatever the client said, and one called {@code @a} would show every
     * dialog to the whole server. Not by UUID either: {@code /dialog} takes
     * PLAYERS, and the game's parser refuses any UUID for a players-only
     * argument, because it cannot know at parse time that the entity is a
     * player ("Only players may be affected by this command, but the provided
     * selector includes entities"). Written that way this route never opened a
     * dialog. {@code @s} is the one selector such an argument accepts.
     */
    private static String dialogCommand(Player player, String rest) {
        String command = "minecraft:execute as " + player.getUniqueId() + " run minecraft:dialog " + rest;
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) ? null : "the server did not run /dialog";
        } catch (RuntimeException e) {
            // Bukkit wraps the game's refusal in "Unhandled exception executing
            // '<the whole command>'", which says nothing about the dialog: the
            // cause is the part worth reading.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            return message.length() > 300 ? message.substring(0, 300) + "…" : message;
        }
    }

    /** The codec's words, plus what they mean when they are about a dialog a click opens. */
    private static String explain(String why) {
        int at = why.indexOf("ResourceKey[minecraft:dialog / ");
        if (at < 0) return why;
        int end = why.indexOf(']', at);
        String id = end < 0 ? "" : why.substring(at + "ResourceKey[minecraft:dialog / ".length(), end);
        return why + " (a click in it opens the dialog " + id + ", which this server does not have, and the game"
                + " refuses the whole dialog over one such click: point it at one of Dialog Engine's dialogs, or take it out)";
    }

    private static final Method CLOSE = closeMethod();

    private static Method closeMethod() {
        // Spigot names it clearDialog; Paper's is Adventure's closeDialog.
        for (String name : new String[] {"clearDialog", "closeDialog"}) {
            try {
                return Player.class.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // try the other
            }
        }
        return null;
    }

    /**
     * Closes whatever screen the client has, dialog or not: the server closing
     * the player's inventory, which the client answers by setting no screen at
     * all. The one way to take away the client's "Waiting for Server" screen,
     * which the game's clear_dialog does not touch.
     */
    public static void closeScreen(Player player) {
        try {
            player.closeInventory();
        } catch (RuntimeException ignored) {
            // nothing more to try
        }
    }

    public static void closeDialog(Player player) {
        if (CLOSE != null) {
            try {
                CLOSE.invoke(player);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through to the command
            }
        }
        dialogCommand(player, "clear @s");
    }
}
