package ai.resourcepack.dialogs.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** PlaceholderAPI's {@code %placeholders%}, when it is installed. By reflection, so it is never a dependency. */
final class Papi {

    private static Method set;
    private static boolean probed;

    private Papi() {}

    static String apply(Player viewer, String text, Text.Mode mode) {
        Method m = method();
        if (m == null) return text;
        try {
            return (String) m.invoke(null, viewer, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }

    /** Whether PlaceholderAPI is installed, so a fill has to keep typed values away from it. */
    static boolean available() {
        return method() != null;
    }

    static void reset() {
        probed = false;
        set = null;
    }

    private static Method method() {
        if (probed) return set;
        probed = true;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return null;
        try {
            set = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            set = null;
        }
        return set;
    }
}
