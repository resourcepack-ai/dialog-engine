package ai.resourcepack.dialogs;

import ai.resourcepack.dialogs.api.DialogEngine;
import ai.resourcepack.dialogs.core.AdminCommand;
import ai.resourcepack.dialogs.core.Engine;
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
 * Java. Needs Paper 1.21.7 or newer: the dialog API it builds on is Paper's.
 */
public final class DialogEnginePlugin extends JavaPlugin implements Listener {

    /** The sample dialogs, written into dialogs/samples/ unless config.yml says samples: false. */
    private static final List<String> SAMPLES = List.of("index.yml", "welcome.yml", "rules.yml", "shop.yml",
            "kits.yml", "warps.yml", "players.yml", "profile.yml", "report.yml", "settings.yml");

    private Engine engine;

    /** This plugin's SpigotMC resource id: the number in its page's URL. */
    private static final int SPIGOT_RESOURCE = 139452;

    private ai.resourcepack.common.update.UpdateCheck updates;

    @Override
    public void onEnable() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
        } catch (ClassNotFoundException e) {
            getLogger().severe("Dialog Engine needs Paper 1.21.7 or newer (dialogs arrived in Minecraft 1.21.6, Paper's API for them in 1.21.7). Disabling.");
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

        engine = new Engine(this);
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
        getLogger().info("Loaded " + engine.ids().size() + " dialogs.");

        updates = ai.resourcepack.common.update.UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("Dialog Engine")
                .permission("dialogengine.update")
                .enabledWhen(() -> getConfig().getBoolean("update-check", true))
                .player((player, update) -> player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage
                        .miniMessage().deserialize("<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> "
                                + "<gray>Dialog Engine <#3670f8>" + update.latest() + "</#3670f8> is out (you have "
                                + update.running() + "). <#3670f8><u><click:open_url:'" + update.url()
                                + "'><hover:show_text:'" + update.url() + "'>Get it on SpigotMC</hover></click></u>")))
                .start();
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (engine != null) engine.shutdown();
        DialogEngine.set(null);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        engine.sendDrawnPack(event.getPlayer());
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
