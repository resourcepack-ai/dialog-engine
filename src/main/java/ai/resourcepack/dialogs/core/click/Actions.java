package ai.resourcepack.dialogs.core.click;

import ai.resourcepack.dialogs.api.ActionHandler;
import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.placeholder.Conditions;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import ai.resourcepack.dialogs.core.yaml.Defs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Runs a button's {@code actions:} in order. Each is {@code [tag] argument};
 * a line with no tag is a command the player runs. Placeholders are filled
 * as each action runs, so a {@code [set]} is seen by the actions after it.
 *
 * <p>A command is filled in {@link Text.Mode#COMMAND}: a value a player typed
 * goes in only as a name, number or id, or as the dialog's author filtered it.
 * One that does not is refused before anything runs, and a command that
 * fails stops the actions after it.
 */
public final class Actions {

    private static final Set<String> TEXT_TAGS = Set.of("message", "broadcast", "actionbar", "title");
    private static final Set<String> COMMAND_TAGS = Set.of("player", "command", "cmd", "console");

    private final Engine engine;

    public Actions(Engine engine) {
        this.engine = engine;
    }

    /** One action line, split into its tag ({@code player} when it has none) and what follows it. */
    private record Line(String tag, String arg) {
        static Line of(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                int close = trimmed.indexOf(']');
                if (close > 0) {
                    return new Line(trimmed.substring(1, close).trim().toLowerCase(Locale.ROOT), trimmed.substring(close + 1).trim());
                }
            }
            return new Line("player", trimmed);
        }
    }

    public void run(ClickCtx ctx, List<String> actions, int from) {
        if (from == 0 && !commandsSafe(ctx, actions)) {
            ctx.finish();
            return;
        }
        for (int i = from; i < actions.size(); i++) {
            if (ctx.stopped || !ctx.player.isOnline()) break;
            String line = actions.get(i);
            if (line == null || line.isBlank()) continue;
            Line parsed = Line.of(line);
            String tag = parsed.tag();
            String rawArg = parsed.arg();
            if (tag.equals("delay") || tag.equals("wait")) {
                long ticks = parseLong(Text.fill(rawArg, ctx.scope(), Text.Mode.RAW), 20);
                if (!ctx.responded) ctx.finish();
                int next = i + 1;
                Bukkit.getScheduler().runTaskLater(engine.plugin(), () -> run(ctx, actions, next), Math.max(1, ticks));
                return;
            }
            boolean command = COMMAND_TAGS.contains(tag);
            Text.Mode mode = TEXT_TAGS.contains(tag) ? Text.Mode.TEXT : command ? Text.Mode.COMMAND : Text.Mode.RAW;
            String arg;
            try {
                arg = Text.fill(rawArg, ctx.scope(), mode).trim();
            } catch (Text.Unsafe e) {
                // A value an earlier [set] made: the check before the first action could not see it.
                refuse(ctx, e.key);
                break;
            }
            try {
                if (!builtIn(ctx, tag, arg, rawArg)) {
                    ActionHandler handler = engine.customAction(tag);
                    if (handler == null) {
                        engine.warnOnce(ctx.def.id() + " has an action [" + tag + "] that nothing provides");
                        continue;
                    }
                    handler.run(ctx, arg);
                }
            } catch (RuntimeException e) {
                engine.plugin().getLogger().log(Level.WARNING, "Action [" + tag + "] on " + ctx.def.id() + " failed", e);
                // A command that threw has failed as surely as one that said so.
                if (command) failed(ctx, tag, arg);
            }
        }
        ctx.finish();
    }

    /**
     * Fills every command before the first action runs, so a typed value no
     * command may carry stops the click before anything has happened, not
     * halfway through it (the money taken, nothing given). Only up to the
     * first {@code [set]} or {@code [clear]}, which can change what the
     * commands after them hold; those are checked as they run.
     */
    private boolean commandsSafe(ClickCtx ctx, List<String> actions) {
        for (String line : actions) {
            if (line == null || line.isBlank()) continue;
            Line parsed = Line.of(line);
            if (parsed.tag().equals("set") || parsed.tag().equals("clear")) break;
            if (!COMMAND_TAGS.contains(parsed.tag())) continue;
            try {
                Text.fill(parsed.arg(), ctx.scope(), Text.Mode.COMMAND);
            } catch (Text.Unsafe e) {
                refuse(ctx, e.key);
                return false;
            }
        }
        return true;
    }

    /**
     * A command would have carried what a player typed in {@code key}, and it
     * holds more than a name's characters. Nothing more runs. When it is one
     * of this dialog's inputs the dialog is drawn again with the message on it,
     * as a failed check is; otherwise the player is told in chat.
     */
    private void refuse(ClickCtx ctx, String key) {
        ctx.stop();
        engine.warnOnce(ctx.def.id() + ": a command was not run because {" + key + "} held typed text with more than"
                + " letters, numbers, _ . and - in it. Where that is safe, write {" + key + "|raw}, or |json or |snbt"
                + " inside a quoted string.");
        Defs.InputDef input = null;
        for (Defs.InputDef in : ctx.def.inputs()) {
            if (in.key().equals(key)) input = in;
        }
        String label = input == null ? "" : Text.MM.stripTags(Text.fill(input.label(), ctx.scope(), Text.Mode.TEXT));
        if (label.isBlank()) label = key;
        String message = engine.message("unsafe-value", "{label} can only have letters, numbers, _ . and - in it.")
                .replace("{label}", label);
        if (input != null) {
            ctx.set("error", message, false);
            ctx.refresh();
        } else {
            Platform.send(ctx.player, Text.MM.deserialize(engine.validationFormat().replace("{error}", message)));
        }
    }

    /**
     * A command action failed: nothing after it runs, so a missing
     * {@code eco take} does not go on to the {@code give}. "Failed" is all the
     * server can say, which is less than it sounds: dispatchCommand is false
     * only for a command nobody registered (an exception is a
     * CommandException), and a command that ran and refused (not enough
     * money) is a success to it. That is why a shop checks the balance first.
     */
    private void failed(ClickCtx ctx, String tag, String command) {
        ctx.stop();
        // A player's own command has already told them why (unknown command, the error).
        if (!tag.equals("console")) return;
        engine.plugin().getLogger().warning(ctx.def.id() + ": [console] " + command + " failed, so the actions after it did not run");
        Platform.send(ctx.player, Text.MM.deserialize(engine.message("command-failed", "<red>That didn't work, so nothing more was done.")));
    }

    private boolean builtIn(ClickCtx ctx, String tag, String arg, String rawArg) {
        Player p = ctx.player;
        switch (tag) {
            case "player", "command", "cmd" -> {
                if (!arg.isEmpty() && !p.performCommand(stripSlash(arg))) failed(ctx, tag, arg);
            }
            case "console" -> {
                if (!arg.isEmpty() && !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(arg))) failed(ctx, tag, arg);
            }
            case "message", "msg" -> Platform.send(p, Text.MM.deserialize(arg));
            case "broadcast" -> Platform.broadcast(Text.MM.deserialize(arg));
            case "actionbar" -> Platform.actionBar(p, Text.MM.deserialize(arg));
            case "title" -> {
                int bar = arg.indexOf('|');
                Component title = Text.MM.deserialize(bar < 0 ? arg : arg.substring(0, bar).trim());
                Component sub = bar < 0 ? Component.empty() : Text.MM.deserialize(arg.substring(bar + 1).trim());
                Platform.title(p, title, sub);
            }
            case "sound" -> {
                String[] parts = arg.split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) return true;
                float volume = parts.length > 1 ? (float) parseDouble(parts[1], 1) : 1f;
                float pitch = parts.length > 2 ? (float) parseDouble(parts[2], 1) : 1f;
                Platform.sound(p, parts[0].toLowerCase(Locale.ROOT), volume, pitch);
            }
            case "open", "dialog" -> {
                // Split as written, then fill each piece: a value with spaces in it stays one value.
                List<String> rawTokens = tokens(rawArg);
                if (rawTokens.isEmpty()) return true;
                Map<String, String> extra = new LinkedHashMap<>();
                Set<String> untrusted = new HashSet<>();
                for (int i = 1; i < rawTokens.size(); i++) {
                    String raw = rawTokens.get(i);
                    int eq = raw.indexOf('=');
                    if (eq <= 0) continue;
                    String key = raw.substring(0, eq);
                    extra.put(key, Text.fill(raw.substring(eq + 1), ctx.scope(), Text.Mode.RAW));
                    if (mentionsUntrusted(raw, ctx)) untrusted.add(key);
                }
                ctx.open(Text.fill(rawTokens.get(0), ctx.scope(), Text.Mode.RAW), extra, untrusted);
            }
            case "back" -> ctx.back();
            case "refresh", "reopen" -> ctx.refresh();
            case "close" -> ctx.close();
            case "stop" -> ctx.stop();
            case "page" -> {
                int page = parseInt(ctx.get("page"), 1);
                int pages = parseInt(ctx.get("pages"), Integer.MAX_VALUE);
                String a = arg.toLowerCase(Locale.ROOT);
                int next = switch (a) {
                    case "next", "+" -> page + 1;
                    case "prev", "previous", "back", "-" -> page - 1;
                    case "first" -> 1;
                    case "last" -> pages;
                    default -> parseInt(a, page);
                };
                ctx.set("page", String.valueOf(Math.max(1, Math.min(pages, next))));
                ctx.refresh();
            }
            case "set" -> {
                for (String raw : tokens(rawArg)) {
                    int eq = raw.indexOf('=');
                    if (eq <= 0) continue;
                    ctx.set(raw.substring(0, eq), Text.fill(raw.substring(eq + 1), ctx.scope(), Text.Mode.RAW), mentionsUntrusted(raw, ctx));
                }
            }
            case "clear" -> {
                if (arg.isEmpty()) {
                    for (Defs.InputDef in : ctx.def.inputs()) {
                        ctx.set(in.key(), null);
                        ctx.set(in.key() + ".label", null);
                        ctx.set(in.key() + ".text", null);
                    }
                } else {
                    for (String key : arg.split("[\\s,]+")) ctx.set(key, null);
                }
            }
            case "url", "link" -> {
                // Mixed in with other actions, so it cannot be the button's own click:
                // hand the link over in chat instead.
                String url = arg.startsWith("http://") || arg.startsWith("https://") ? arg : "https://" + arg;
                Platform.send(p, Text.MM.deserialize(engine.message("link", "<gray>Open: <aqua><u>{url}</u></aqua>")
                        .replace("{url}", Text.MM.escapeTags(url))).clickEvent(ClickEvent.openUrl(url)));
            }
            case "copy", "suggest" -> engine.warnOnce(ctx.def.id() + ": [" + tag + "] only works as a button's only action");
            case "permission" -> {
                // [permission] node: stop here unless the player has it.
                if (!arg.isEmpty() && !p.hasPermission(arg)) {
                    Platform.send(p, Text.MM.deserialize(engine.message("no-permission", "<red>You can't do that.")));
                    ctx.stop();
                }
            }
            case "if" -> {
                // [if] condition: stop here unless it holds. Fill nothing first;
                // Conditions splits the operands before it fills them.
                if (!Conditions.test(rawArg, ctx.scope())) ctx.stop();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean mentionsUntrusted(String raw, ClickCtx ctx) {
        return mentionsAny(raw, ctx.session.untrusted);
    }

    /** Whether {@code raw} names any of {@code keys}: plain, as a choice, or filtered ({@code |raw} launders nothing). */
    static boolean mentionsAny(String raw, Set<String> keys) {
        for (String key : keys) {
            if (raw.contains("{" + key + "}") || raw.contains("{" + key + "?") || raw.contains("{" + key + "|")) return true;
        }
        return false;
    }

    /** Splits on spaces, keeping "quoted words" and key="quoted words" together. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    private static String stripSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
