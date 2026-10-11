package ai.resourcepack.dialogs.core;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The lists every server has: {@code online_players}, {@code other_players} and {@code worlds}. */
final class BuiltInLists {

    private BuiltInLists() {}

    static List<Map<String, String>> rows(String list, Scope scope) {
        return switch (list) {
            case "online_players" -> players(scope.viewer, true);
            case "other_players" -> players(scope.viewer, false);
            case "worlds" -> {
                List<Map<String, String>> out = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("name", w.getName());
                    row.put("id", w.getName());
                    row.put("players", String.valueOf(w.getPlayers().size()));
                    row.put("environment", w.getEnvironment().name().toLowerCase(java.util.Locale.ROOT));
                    out.add(row);
                }
                yield out;
            }
            default -> null;
        };
    }

    private static List<Map<String, String>> players(Player viewer, boolean includeViewer) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, String>> out = new ArrayList<>();
        for (Player p : players) {
            if (!includeViewer && p.equals(viewer)) continue;
            // A vanished player stays vanished in a dialog too.
            if (!viewer.canSee(p)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", p.getName());
            row.put("id", p.getName());
            row.put("uuid", p.getUniqueId().toString());
            row.put("world", p.getWorld().getName());
            row.put("ping", String.valueOf(p.getPing()));
            out.add(row);
        }
        return out;
    }
}
