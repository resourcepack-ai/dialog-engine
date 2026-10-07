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

    private static final List<String> EXAMPLES = List.of("welcome.yml", "settings.yml", "players.yml");

    private Engine engine;

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
        File dialogs = new File(getDataFolder(), "dialogs");
        if (!dialogs.exists()) {
            for (String name : EXAMPLES) saveResource("dialogs/examples/" + name, false);
        }

        engine = new Engine(this);
        engine.load();
        DialogEngine.set(engine);

        PluginCommand de = getCommand("dialogengine");
        if (de != null) {
            AdminCommand admin = new AdminCommand(engine);
            de.setExecutor(admin);
            de.setTabCompleter(admin);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + engine.ids().size() + " dialogs.");
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.shutdown();
        DialogEngine.set(null);
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
