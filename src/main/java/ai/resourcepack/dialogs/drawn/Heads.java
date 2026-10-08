package ai.resourcepack.dialogs.drawn;

import org.bukkit.Bukkit;

/** Whether this server's dialogs may carry the {@code player} text component (1.21.9+). */
final class Heads {

    private Heads() {
    }

    static boolean supported() {
        String[] parts = Bukkit.getMinecraftVersion().split("[.]");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (major != 1) return major > 1;
        return minor > 21 || (minor == 21 && patch >= 9);
    }
}
