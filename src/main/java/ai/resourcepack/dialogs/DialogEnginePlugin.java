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
        upgradeConfig();
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
        if (getConfig().getBoolean("samples", true) && drawnSamples.exists()) refreshDrawnSamples(drawnSamples);
        if (getConfig().getBoolean("samples", true) && !drawnSamples.exists()) {
            writeDrawnSamples();
            // Its picker, for a server that had the YAML samples before there were drawn ones.
            if (!new File(getDataFolder(), "dialogs/samples/drawn.yml").exists()) saveResource("dialogs/samples/drawn.yml", false);
        }

        engine.load();
        DialogEngine.set(engine);
        // Another plugin of ours (RP Engine) does not send its packs again over
        // this one's, which it would otherwise take for a stranger's.
        ai.resourcepack.common.shader.SiblingPacks.register(this, ai.resourcepack.dialogs.core.drawn.DrawnPack::isPack);
        // The other plugins' packs are read before every build; once more a
        // while after startup, for a plugin that builds its own as it enables.
        engine.drawnPack.watchLater(20L * 20);

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

    /**
     * Moves a config.yml written by an older version onto a default that has
     * changed since, once. Edited as text, so every comment and every other
     * line stays as the owner left it.
     *
     * <p>Version 2 (0.4.0): {@code default-after} went from {@code wait} to
     * {@code seamless}. A file without a {@code config-version} that still says
     * {@code wait} says it because that was the default, so it moves; one that
     * says {@code wait} after this has chosen it.
     */
    private void upgradeConfig() {
        File file = new File(getDataFolder(), "config.yml");
        try {
            String text = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String eol = text.contains("\r\n") ? "\r\n" : "\n";
            String out = text;
            java.util.regex.Matcher version = java.util.regex.Pattern.compile("(?m)^config-version:[ \\t]*(\\d+)").matcher(text);
            int from = version.find() ? Integer.parseInt(version.group(1)) : 1;
            if (from < 2) {
                String moved = out.replaceFirst("(?m)^default-after:[ \\t]*[\"']?wait[\"']?[ \\t]*(?=\\r?$)", "default-after: seamless");
                if (!moved.equals(out)) {
                    getLogger().info("config.yml: default-after is now seamless, the new default (a dialog stays on screen"
                            + " until the next one replaces it, with no waiting screen). Set it back to wait for the old behaviour.");
                }
                out = moved + (moved.endsWith("\n") ? "" : eol) + eol
                        + "# Which version of this file it is, so an update can move a setting whose" + eol
                        + "# default changed. Leave it alone." + eol
                        + "config-version: 2" + eol;
            }
            if (from < 3) {
                // Version 3 (0.5.0): shaders.merge and shaders.notify-players. New
                // settings, so a file that already has the section keeps it.
                if (!java.util.regex.Pattern.compile("(?m)^shaders:").matcher(out).find()) {
                    String section = String.join(eol,
                            "# A drawn dialog's live player heads replace the game's text shader, and so do",
                            "# some HUD plugins' packs (BetterHud's). A player's game keeps only one copy, so",
                            "# with merge on, Dialog Engine combines the other plugin's into the drawn pack",
                            "# so both draw. The console says what was combined, and what could not be.",
                            "shaders:",
                            "  merge: true",
                            "  # Also say it in chat to players with dialogengine.shaders (ops), when it",
                            "  # happens and when they join. False keeps it to the console.",
                            "  notify-players: true",
                            "", "");
                    java.util.regex.Matcher marker = java.util.regex.Pattern
                            .compile("(?m)^# Which version of this file it is").matcher(out);
                    out = marker.find() ? out.substring(0, marker.start()) + section + out.substring(marker.start())
                            : out + (out.endsWith("\n") ? "" : eol) + eol + section;
                }
                out = out.replaceFirst("(?m)^config-version:[ \\t]*\\d+", "config-version: 3");
            }
            if (out.equals(text)) return;
            java.nio.file.Files.writeString(file.toPath(), out, java.nio.charset.StandardCharsets.UTF_8);
            reloadConfig();
        } catch (java.io.IOException | RuntimeException e) {
            getLogger().warning("Could not update config.yml: " + e.getMessage());
        }
    }

    /**
     * The drawn samples already on disk, brought up to the jar's drawing when
     * nobody has edited them: a sample whose {@code dialog.yml} is the jar's,
     * but whose drawing is not, gets the jar's drawing (its old {@code pack/}
     * taken away first, so nothing from the old drawing is left in the pack).
     * Written once and never again before 0.4.3, which left every server on the
     * drawing it first installed, emoji clashes and all.
     */
    private void refreshDrawnSamples(File root) {
        java.util.List<String> files = sampleFiles();
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String f : files) if (f.contains("/")) names.add(f.substring(0, f.indexOf('/')));
        for (String name : names) {
            File dir = new File(root, name);
            try (java.io.InputStream yml = getResource("drawn/samples/" + name + "/dialog.yml");
                 java.io.InputStream built = getResource("drawn/samples/" + name + "/dialogs.json")) {
                if (yml == null || built == null || !dir.isDirectory()) continue;
                String jarYml = new String(yml.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                java.nio.file.Path diskYml = new File(dir, "dialog.yml").toPath();
                if (!java.nio.file.Files.isRegularFile(diskYml)
                        || !java.nio.file.Files.readString(diskYml, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").equals(jarYml)) {
                    continue; // Edited: the owner's now.
                }
                byte[] jarBuilt = built.readAllBytes();
                java.nio.file.Path diskBuilt = new File(dir, "dialogs.json").toPath();
                if (java.nio.file.Files.isRegularFile(diskBuilt) && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(diskBuilt), jarBuilt)) continue;
                java.nio.file.Path pack = new File(dir, "pack").toPath();
                if (java.nio.file.Files.isDirectory(pack)) {
                    try (var walk = java.nio.file.Files.walk(pack)) {
                        for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) java.nio.file.Files.delete(p);
                    }
                }
                for (String f : files) if (f.startsWith(name + "/")) saveResource("drawn/samples/" + f, true);
                getLogger().info("Updated the drawn sample " + name + " to this version's drawing.");
            } catch (java.io.IOException | IllegalArgumentException e) {
                getLogger().warning("Could not update the drawn sample " + name + ": " + e.getMessage());
            }
        }
    }

    /** The jar's {@code drawn/samples/files.txt}, one path per line. */
    private java.util.List<String> sampleFiles() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (java.io.InputStream list = getResource("drawn/samples/files.txt")) {
            if (list == null) return out;
            for (String line : new String(list.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim();
                if (!name.isEmpty() && !name.contains("..")) out.add(name);
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Could not read the drawn sample list: " + e.getMessage());
        }
        return out;
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

    /** Last, and only for a login that is going ahead: the player's own copies of the Quick Actions key's dialogs. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        engine.quick.prepare(event.getUniqueId(), event.getName());
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
