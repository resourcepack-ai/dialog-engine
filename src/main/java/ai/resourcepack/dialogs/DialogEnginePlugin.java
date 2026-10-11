package ai.resourcepack.dialogs;

import ai.resourcepack.dialogs.api.DialogEngine;
import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.command.AdminCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Dialog Engine: Minecraft's dialog screens, written in YAML and driven from
 * Java. Runs on Spigot and Paper 1.21.7 or newer, from one jar.
 */
public final class DialogEnginePlugin extends JavaPlugin implements Listener {

    /** The sample dialogs, written into dialogs/samples/ unless config.yml says samples: false. */
    private static final List<String> SAMPLES = List.of("index.yml", "welcome.yml", "rules.yml", "shop.yml",
            "kits.yml", "warps.yml", "players.yml", "profile.yml", "report.yml", "settings.yml", "drawn.yml");

    private Engine engine;

    /** This plugin's SpigotMC resource id: the number in its page's URL. */
    private static final int SPIGOT_RESOURCE = 139513;

    private ai.resourcepack.common.update.UpdateCheck updates;

    private ai.resourcepack.common.telemetry.Heartbeat heartbeat;

    @Override
    public void onEnable() {
        engine = new Engine(this);
        String clicks = engine.listenForClicks();
        if (clicks == null) {
            getLogger().severe("Dialog Engine needs Spigot or Paper 1.21.7 or newer (dialogs arrived in Minecraft 1.21.6). Disabling.");
            engine = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        // The format, written for an AI agent to read. Replaced on every start,
        // so it always describes the version that is running.
        saveResource("AI-GUIDE.md", true);
        // The samples are written once, when their folder is missing: deleting
        // one file keeps it deleted, deleting the folder brings them all back
        // unless samples: false.
        File samples = new File(getDataFolder(), "dialogs/samples");
        if (getConfig().getBoolean("samples", true) && !samples.exists()) {
            for (String name : SAMPLES) saveResource("dialogs/samples/" + name, false);
        }
        // The drawn samples (a punishment panel, a settings menu), the same way:
        // Studio's drawings, shipped drawn, so they work without the internet.
        File drawnSamples = new File(getDataFolder(), "drawn/samples");
        if (getConfig().getBoolean("samples", true) && !drawnSamples.exists()) {
            writeDrawnSamples();
            // Its picker, for a server that had the YAML samples before there were drawn ones.
            if (!new File(getDataFolder(), "dialogs/samples/drawn.yml").exists()) saveResource("dialogs/samples/drawn.yml", false);
        }

        engine.load();
        DialogEngine.set(engine);

        PluginCommand click = getCommand("dialogclick");
        if (click != null) {
            click.setExecutor((sender, command, label, args) -> {
                if (sender instanceof org.bukkit.entity.Player p) engine.drawnClick(p, args);
                return true;
            });
            click.setTabCompleter((sender, command, label, args) -> java.util.List.of());
        }
        PluginCommand de = getCommand("dialogengine");
        if (de != null) {
            AdminCommand admin = new AdminCommand(engine);
            de.setExecutor(admin);
            de.setTabCompleter(admin);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + engine.ids().size() + " dialogs. Showing them through " + engine.route() + ", clicks through " + clicks + "'s event.");

        updates = ai.resourcepack.common.update.UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("Dialog Engine")
                .permission("dialogengine.update")
                .enabledWhen(() -> getConfig().getBoolean("update-check", true))
                .player((player, update) -> Engine.tell(player, "<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> "
                                + "<gray>Dialog Engine <#3670f8>" + update.latest() + "</#3670f8> is out (you have "
                                + update.running() + "). <#3670f8><u><click:open_url:'" + update.url()
                                + "'><hover:show_text:'" + update.url() + "'>Get it on SpigotMC</hover></click></u>"))
                .start();

        // Anonymous usage counting, the only telemetry this plugin has: a
        // random id, the online player count and the names of plugins that
        // depend on this one. See Heartbeat for exactly what goes.
        heartbeat = ai.resourcepack.common.telemetry.Heartbeat.of(this, "dialog-engine")
                .studio(getConfig().getString("edit.url", "https://studio.resourcepack.ai"))
                .enabledWhen(() -> getConfig().getBoolean("telemetry", true))
                .start();
    }

    /** Every file listed in the jar's {@code drawn/samples/files.txt}. */
    private void writeDrawnSamples() {
        try (java.io.InputStream list = getResource("drawn/samples/files.txt")) {
            if (list == null) return;
            for (String line : new String(list.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.contains("..")) continue;
                saveResource("drawn/samples/" + name, false);
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            getLogger().warning("Could not write the drawn sample dialogs: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (heartbeat != null) heartbeat.stop();
        if (engine != null) engine.shutdown();
        DialogEngine.set(null);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        engine.sendDrawnPack(event.getPlayer());
    }

    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        engine.packStatus(event.getPlayer(), event.getID(), event.getStatus().name());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        engine.forget(event.getPlayer());
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != this) engine.unregister(event.getPlugin());
    }
}
