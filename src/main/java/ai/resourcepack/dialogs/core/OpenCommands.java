package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.core.Defs.DialogDef;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The commands dialogs declare ({@code command: shop}). Registered straight
 * into the server's command map so they can come and go on reload. A name
 * another plugin already has is left to that plugin, and the console says so.
 */
final class OpenCommands {

    private final Engine engine;
    private final JavaPlugin plugin;
    private final Map<String, DialogCommand> registered = new LinkedHashMap<>();

    OpenCommands(Engine engine, JavaPlugin plugin) {
        this.engine = engine;
        this.plugin = plugin;
    }

    void sync(Collection<DialogDef> defs) {
        clear();
        CommandMap map = Platform.commandMap();
        Set<String> taken = new HashSet<>();
        for (DialogDef def : defs) {
            if (def.command() == null) continue;
            if (taken.contains(def.command())) {
                engine.warnOnce(def.id() + " wants /" + def.command() + ", which another dialog already has");
                continue;
            }
            Command existing = map.getCommand(def.command());
            if (existing != null) {
                engine.warnOnce(def.id() + " wants /" + def.command() + ", which " + owner(existing) + " already has; it goes without");
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (String a : def.aliases()) {
                if (map.getCommand(a) == null && !taken.contains(a)) aliases.add(a);
            }
            DialogCommand cmd = new DialogCommand(def.command(), aliases, def.id());
            map.register("dialogengine", cmd);
            registered.put(def.command(), cmd);
            taken.add(def.command());
            taken.addAll(aliases);
        }
        refreshClients();
    }

    void clear() {
        if (registered.isEmpty()) return;
        CommandMap map = Platform.commandMap();
        for (DialogCommand cmd : registered.values()) unregister(map, cmd);
        registered.clear();
        refreshClients();
    }

    /**
     * Takes {@code cmd} out of the command map for real. {@link Command#unregister}
     * alone leaves it in the known commands, under its name and its
     * {@code dialogengine:} one, so it keeps running until a restart.
     */
    static void unregister(CommandMap map, Command cmd) {
        cmd.unregister(map);
        Platform.knownCommands(map).values().removeIf(c -> c == cmd);
    }

    private void refreshClients() {
        if (!plugin.isEnabled()) return;
        // So a removed command stops tab-completing and a new one starts.
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(Player::updateCommands));
    }

    private static String owner(Command c) {
        if (c instanceof org.bukkit.command.PluginIdentifiableCommand p) return p.getPlugin().getName();
        return "the server";
    }

    final class DialogCommand extends Command {

        private final String dialogId;

        DialogCommand(String name, List<String> aliases, String dialogId) {
            super(name, "Opens a dialog", "/" + name, aliases);
            this.dialogId = dialogId;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied) sender = proxied.getCallee();
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open dialogs. Use /de open " + dialogId + " <player>.");
                return true;
            }
            DialogDef def = engine.def(dialogId);
            if (def == null) return true;
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> untrusted = new HashSet<>();
            List<String> names = def.args();
            for (int i = 0; i < names.size() && i < args.length; i++) {
                // The last named argument takes the rest of the line.
                String value = i == names.size() - 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, i, args.length)) : args[i];
                values.put(names.get(i), value);
                untrusted.add(names.get(i));
            }
            if (args.length < names.size() && def.ifMissing() != null) {
                engine.openAsPlayer(player, def.ifMissing(), values, untrusted, null);
                return true;
            }
            engine.openAsPlayer(player, dialogId, values, untrusted, null);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            DialogDef def = engine.def(dialogId);
            if (def == null || args.length > def.args().size()) return List.of();
            String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player s && !s.canSee(p)) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(partial)) out.add(p.getName());
            }
            return out;
        }

        @Override
        public boolean testPermissionSilent(CommandSender target) {
            DialogDef def = engine.def(dialogId);
            return def == null || def.permission() == null || target.hasPermission(def.permission());
        }
    }
}
