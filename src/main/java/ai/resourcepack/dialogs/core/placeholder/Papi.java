package ai.resourcepack.dialogs.core.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** PlaceholderAPI's {@code %placeholders%}, when it is installed. By reflection, so it is never a dependency. */
public final class Papi {

    private static Method set;
    private static boolean probed;

    private Papi() {}

    static String apply(Player viewer, String text, Text.Mode mode) {
        Method m = method();
        if (m == null) return text;
        try {
            return (String) m.invoke(null, who(viewer), text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }

    /**
     * One placeholder by its name, {@code vault_eco_balance} for
     * {@code %vault_eco_balance%}: what PlaceholderAPI answers, or null when it
     * is not installed or nothing answers (it hands back the placeholder as
     * written). How a drawn dialog's {@code {name}} reaches it.
     */
    public static String lookup(Player viewer, String name) {
        Method m = method();
        if (m == null || viewer == null || name == null || name.isEmpty()) return null;
        String wrapped = "%" + name + "%";
        try {
            Object value = m.invoke(null, who(viewer), wrapped);
            String text = value == null ? null : value.toString();
            return text == null || text.equals(wrapped) ? null : text;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Who PlaceholderAPI is asked about: a joining player's stand-in is handed
     * over as the offline player it stands in for, which expansions know how
     * to answer, rather than as a Player with nothing behind it.
     */
    private static OfflinePlayer who(Player viewer) {
        return ai.resourcepack.common.quick.StandIn.forPlaceholders(viewer);
    }

    /** Whether PlaceholderAPI is installed, so a fill has to keep typed values away from it. */
    static boolean available() {
        return method() != null;
    }

    public static void reset() {
        probed = false;
        set = null;
    }

    private static Method method() {
        if (probed) return set;
        probed = true;
        // No server: a unit test, filling text with nothing installed.
        if (Bukkit.getServer() == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return null;
        try {
            set = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            set = null;
        }
        return set;
    }
}
