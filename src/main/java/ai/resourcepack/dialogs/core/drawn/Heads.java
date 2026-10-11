package ai.resourcepack.dialogs.core.drawn;

import org.bukkit.Bukkit;

/**
 * Whether this server's dialogs may carry the {@code player} text component
 * (1.21.9+). The build puts this in for RP Engine's own version check when it
 * copies RP Engine's dialog code into this package.
 */
final class Heads {

    private Heads() {
    }

    static boolean supported() {
        // "1.21.8-R0.1-SNAPSHOT" on Spigot and Paper alike (getMinecraftVersion is Paper's alone).
        String[] parts = Bukkit.getBukkitVersion().split("-")[0].split("[.]");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (major != 1) return major > 1;
        return minor > 21 || (minor == 21 && patch >= 9);
    }
}
