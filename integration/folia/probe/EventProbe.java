package probe;

import ai.resourcepack.dialogs.api.event.DialogClickEvent;
import ai.resourcepack.dialogs.api.event.DialogCloseEvent;
import ai.resourcepack.dialogs.api.event.DialogSettingChangeEvent;
import ai.resourcepack.dialogs.api.event.DialogSlotMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.TreeMap;

/**
 * Logs every Dialog Engine event a plugin can listen to, one line each, so the
 * session can check what a plugin would have been told. Built by run.mjs.
 * It also asserts the event arrives on a thread that owns the player, which
 * on Folia is the region the player is in.
 */
public final class EventProbe extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * {@code probetp <player> <world> <x> <y> <z>}: a plugin's teleport, as a
     * warp plugin makes one (Paper's {@code teleportAsync}, which is what Folia
     * allows), for the close a world change is reported as.
     */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equals("probechest")) return chest(args[0]);
        org.bukkit.entity.Player player = Bukkit.getPlayerExact(args[0]);
        org.bukkit.World world = Bukkit.getWorld(args[1]);
        if (player == null || world == null) return false;
        org.bukkit.Location to = new org.bukkit.Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        try {
            org.bukkit.entity.Entity.class.getMethod("teleportAsync", org.bukkit.Location.class).invoke(player, to);
            log("teleportAsync " + player.getName() + " " + world.getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    /** {@code probechest <player>}: opens a chest for them on their own thread, as a plugin's menu would. */
    private boolean chest(String name) {
        org.bukkit.entity.Player player = Bukkit.getPlayerExact(name);
        if (player == null) return false;
        Runnable open = () -> {
            log("chest " + player.getName());
            player.openInventory(Bukkit.createInventory(null, 9, "probe"));
        };
        try {
            // Paper and Folia: the player's own scheduler. Spigot has none, and is single-threaded.
            Class<?> type = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Object scheduler = org.bukkit.entity.Entity.class.getMethod("getScheduler").invoke(player);
            java.util.function.Consumer<Object> task = t -> open.run();
            type.getMethod("run", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class)
                    .invoke(scheduler, this, task, null);
        } catch (ReflectiveOperationException e) {
            open.run();
        }
        return true;
    }

    private void log(String line) {
        getLogger().info("EVENT " + line);
    }

    private static String sorted(Map<String, String> map) {
        return new TreeMap<>(map).toString();
    }

    private static String thread(org.bukkit.entity.Player player) {
        // Folia: Bukkit.isOwnedByCurrentRegion(Entity), read by reflection so this compiles against spigot-api.
        try {
            Object owned = Bukkit.class.getMethod("isOwnedByCurrentRegion", org.bukkit.entity.Entity.class).invoke(null, player);
            return Boolean.TRUE.equals(owned) ? "owner" : "WRONG-THREAD";
        } catch (ReflectiveOperationException e) {
            return Bukkit.isPrimaryThread() ? "owner" : "WRONG-THREAD";
        }
    }

    @EventHandler
    public void onClick(DialogClickEvent e) {
        log("click dialog=" + e.getDialogId() + " button=" + e.getButtonId() + " drawn=" + e.isDrawn()
                + " inputs=" + sorted(e.getInputs()) + " settings=" + sorted(e.getSettings()) + " on=" + thread(e.getPlayer()));
    }

    @EventHandler
    public void onClose(DialogCloseEvent e) {
        log("close dialog=" + e.getDialogId() + " reason=" + e.getReason() + " button=" + e.getButtonId()
                + " next=" + e.getNextDialogId() + " player=" + e.getPlayer().getName() + " settings=" + sorted(e.getSettings())
                + " on=" + thread(e.getPlayer()));
    }

    @EventHandler
    public void onSetting(DialogSettingChangeEvent e) {
        log("setting dialog=" + e.getDialogId() + " name=" + e.getName() + " old=" + e.getOldValue() + " new=" + e.getNewValue()
                + " on=" + thread(e.getPlayer()));
    }

    @EventHandler
    public void onMove(DialogSlotMoveEvent e) {
        log("move dialog=" + e.getDialogId() + " from=" + e.getFrom() + " to=" + e.getTo() + " on=" + thread(e.getPlayer()));
    }
}
