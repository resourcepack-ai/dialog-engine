package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.core.Defs.DialogDef;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /dialogengine} ({@code /de}): list, open, info, reload. */
public final class AdminCommand implements TabExecutor {

    private final Engine engine;

    public AdminCommand(Engine engine) {
        this.engine = engine;
    }

    private void say(CommandSender to, String mini) {
        to.sendMessage(Text.MM.deserialize("<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>" + mini));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                engine.reload();
                List<String> problems = engine.problems();
                say(sender, "Loaded <white>" + engine.ids().size() + "</white> dialogs"
                        + (problems.isEmpty() ? "." : ", with <yellow>" + problems.size() + "</yellow> problems:"));
                for (String p : problems) sender.sendMessage(Text.MM.deserialize("<yellow> - </yellow><gray>" + Text.MM.escapeTags(p)));
            }
            case "edit" -> {
                if (args.length < 2) {
                    say(sender, "/de edit <namespace:id> <dark_gray>·</dark_gray> opens it in the dialog editor in your browser; a name that is not there yet is made.");
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!id.contains(":")) id = "server:" + id;
                engine.edits().open(sender, id);
            }
            case "list" -> {
                Map<String, List<String>> byNamespace = new LinkedHashMap<>();
                for (DialogDef d : engine.all().values()) {
                    byNamespace.computeIfAbsent(d.namespace(), k -> new ArrayList<>()).add(d.id().substring(d.id().indexOf(':') + 1)
                            + (d.command() != null ? " <dark_gray>(/" + d.command() + ")</dark_gray>" : ""));
                }
                say(sender, "<white>" + engine.ids().size() + "</white> dialogs:");
                byNamespace.forEach((ns, names) -> sender.sendMessage(Text.MM.deserialize(
                        "<#3670f8>" + ns + "</#3670f8><dark_gray>:</dark_gray> <gray>" + String.join("<dark_gray>,</dark_gray> ", names))));
            }
            case "open" -> {
                if (args.length < 2) {
                    say(sender, "/de open <id> [player] [key=value ...]");
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                Player target = sender instanceof Player p ? p : null;
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 2; i < args.length; i++) {
                    int eq = args[i].indexOf('=');
                    if (eq > 0) {
                        values.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                    } else {
                        Player p = Bukkit.getPlayerExact(args[i]);
                        if (p == null) {
                            say(sender, "<red>" + Text.MM.escapeTags(args[i]) + " is not online.");
                            return true;
                        }
                        target = p;
                    }
                }
                if (target == null) {
                    say(sender, "Say which player: /de open " + id + " <player>");
                    return true;
                }
                if (engine.def(id) == null) {
                    say(sender, "<red>There is no dialog called " + Text.MM.escapeTags(id) + ". <gray>/de list");
                    return true;
                }
                // Typed by staff, but still typed: escape it where it is printed.
                Session s = new Session(target.getUniqueId(), id, values, new HashSet<>(values.keySet()), null);
                if (!engine.show(target, s, false)) say(sender, "<red>It did not open (an event cancelled it, or it is broken).");
            }
            case "info" -> {
                if (args.length < 2) {
                    say(sender, "/de info <id>");
                    return true;
                }
                DialogDef d = engine.def(args[1].toLowerCase(Locale.ROOT));
                if (d == null) {
                    say(sender, "<red>No such dialog.");
                    return true;
                }
                say(sender, "<white>" + d.id() + "</white> from <white>" + Text.MM.escapeTags(d.source()) + "</white>");
                sender.sendMessage(Text.MM.deserialize("<gray> type " + d.type().name().toLowerCase(Locale.ROOT)
                        + ", " + d.buttons().size() + " buttons, " + d.inputs().size() + " inputs, " + d.body().size() + " body entries"
                        + (d.command() != null ? ", /" + d.command() : "")
                        + (d.permission() != null ? ", needs " + d.permission() : "")
                        + (d.args().isEmpty() ? "" : ", args " + String.join(" ", d.args()))));
            }
            default -> {
                say(sender, "<white>/de list</white> <dark_gray>·</dark_gray> <white>/de open <id> [player] [key=value]</white> <dark_gray>·</dark_gray> <white>/de info <id></white> <dark_gray>·</dark_gray> <white>/de edit <id></white> <dark_gray>·</dark_gray> <white>/de reload</white>");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("list", "open", "info", "edit", "reload")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("edit"))) {
            for (String id : engine.ids()) if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(id);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("open")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
        }
        return out;
    }
}
