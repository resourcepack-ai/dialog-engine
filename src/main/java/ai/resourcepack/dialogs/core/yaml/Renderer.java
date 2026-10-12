package ai.resourcepack.dialogs.core.yaml;

import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.Session;
import ai.resourcepack.dialogs.core.click.Clicks;
import ai.resourcepack.dialogs.core.placeholder.Conditions;
import ai.resourcepack.dialogs.core.placeholder.Scope;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import ai.resourcepack.dialogs.core.yaml.Defs.BodyDef;
import ai.resourcepack.dialogs.core.yaml.Defs.BodyKind;
import ai.resourcepack.dialogs.core.yaml.Defs.ButtonDef;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Defs.ForEach;
import ai.resourcepack.dialogs.core.yaml.Defs.InputDef;
import ai.resourcepack.dialogs.core.yaml.Defs.OptionDef;
import ai.resourcepack.dialogs.core.yaml.Defs.Type;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a definition and a session into the game's dialog, for one player.
 * A dialog is drawn fresh every time it is shown: placeholders, conditions and
 * lists are all evaluated here, and every button gets a callback bound to the
 * session it was drawn from and to this drawing, so it dies when the next one
 * replaces it.
 *
 * <p>What comes out is the game's own dialog JSON, the same on every server;
 * {@link Platform} decides how it reaches the screen.
 */
public final class Renderer {

    private final Engine engine;

    public Renderer(Engine engine) {
        this.engine = engine;
    }

    /** What one drawing produced: the dialog, and the inputs on it (so a click knows what to read). */
    /** {@code after}: what the dialog was sent as, which is not always what it says. */
    public record Drawn(JsonObject dialog, List<InputDef> inputs, Map<String, Map<String, String>> optionLabels, Defs.After after) {}

    /** Draws it as drawing number {@code drawing} of the viewer's (see {@link Engine#onClick}). */
    public Drawn draw(DialogDef def, Session session, Player viewer, long drawing) {
        Scope scope = new Scope(engine, viewer, def.id(), session.values, session.untrusted, null, null);
        Map<String, List<Map<String, String>>> lists = new HashMap<>();
        paging(def, scope, session, lists);

        JsonArray body = new JsonArray();
        String error = session.values.get("error");
        boolean printsError = def.body().stream().anyMatch(b -> b.text() != null && b.text().contains("{error"));
        if (error != null && !error.isEmpty() && !printsError) {
            body.add(plain(Text.json(engine.validationFormat(), scope), 300));
        }
        for (BodyDef b : def.body()) {
            for (Scope s : expand(b.forEach(), scope, lists)) {
                if (!Conditions.test(b.condition(), s)) continue;
                body.add(body(b, s));
            }
        }

        JsonArray inputs = new JsonArray();
        List<InputDef> drawnInputs = new ArrayList<>();
        Map<String, Map<String, String>> optionLabels = new HashMap<>();
        for (InputDef in : def.inputs()) {
            if (!Conditions.test(in.condition(), scope)) continue;
            JsonObject built = input(in, scope, session, lists, optionLabels);
            if (built == null) continue;
            inputs.add(built);
            drawnInputs.add(in);
        }

        List<JsonObject> buttons = new ArrayList<>();
        for (ButtonDef b : def.buttons()) {
            for (Scope s : expand(b.forEach(), scope, lists)) {
                if (b.permission() != null && !viewer.hasPermission(b.permission())) continue;
                if (!Conditions.test(b.condition(), s)) continue;
                buttons.add(button(def, b, s, session, drawnInputs, optionLabels, viewer, drawing));
            }
        }
        JsonObject exit = null;
        if (def.exit() != null && Conditions.test(def.exit().condition(), scope)
                && (def.exit().permission() == null || viewer.hasPermission(def.exit().permission()))) {
            exit = button(def, def.exit(), scope, session, drawnInputs, optionLabels, viewer, drawing);
        }

        // A notice with no button gets the game's own Ok, which calls nothing
        // back: under seamless it would do nothing at all, the dialog being sent
        // as none. This one calls back, and the engine closes it.
        if (def.after() == Defs.After.SEAMLESS && buttons.isEmpty() && exit == null && def.type() != Type.LINKS) {
            exit = okButton(viewer, drawing);
        }

        JsonObject dialog = new JsonObject();
        type(dialog, def, buttons, exit);
        dialog.add("title", Text.json(def.title(), scope));
        if (def.externalTitle() != null) dialog.add("external_title", Text.json(def.externalTitle(), scope));
        if (!body.isEmpty()) dialog.add("body", body);
        if (!inputs.isEmpty()) dialog.add("inputs", inputs);
        dialog.addProperty("can_close_with_escape", def.escape());
        dialog.addProperty("pause", def.pause());
        // A link, copy or suggest button is the client's own: the server never
        // hears it was pressed, so under "wait" the client would show "Waiting
        // for Server" until the player gave up. A dialog with one closes instead.
        Defs.After after = def.after();
        if (after == Defs.After.WAIT_FOR_RESPONSE
                && (buttons.stream().anyMatch(Renderer::clientOnly) || (exit != null && clientOnly(exit)))) {
            after = Defs.After.CLOSE;
        }
        dialog.addProperty("after_action", after.json());
        return new Drawn(dialog, drawnInputs, optionLabels, after);
    }

    /** The game's Ok, calling back so the engine can close the screen. */
    private JsonObject okButton(Player viewer, long drawing) {
        JsonObject label = new JsonObject();
        label.addProperty("translate", "gui.ok");
        JsonObject button = new JsonObject();
        button.add("label", label);
        String token = engine.clicks().bind(viewer, engine.clickLifetime(), drawing, (clicker, payload) -> engine.closeDialog(clicker));
        button.add("action", Clicks.action(token));
        return button;
    }

    /** Whether pressing this button is something only the client does (see {@link #staticAction}). */
    private static boolean clientOnly(JsonObject button) {
        return button.get("action") instanceof JsonObject action
                && !"minecraft:dynamic/custom".equals(action.get("type").getAsString());
    }

    private static void type(JsonObject out, DialogDef def, List<JsonObject> buttons, JsonObject exit) {
        Type t = def.type();
        if (t == Type.LINKS) {
            out.addProperty("type", "minecraft:server_links");
            if (exit != null) out.add("exit_action", exit);
            out.addProperty("columns", def.columns());
            out.addProperty("button_width", 150);
            return;
        }
        if (t == Type.CONFIRMATION && buttons.size() >= 2) {
            out.addProperty("type", "minecraft:confirmation");
            out.add("yes", buttons.get(0));
            out.add("no", buttons.get(1));
            return;
        }
        if (t == Type.NOTICE || buttons.isEmpty() || (t == Type.AUTO && buttons.size() == 1 && exit == null)) {
            JsonObject only = !buttons.isEmpty() ? buttons.get(0) : exit;
            out.addProperty("type", "minecraft:notice");
            if (only != null) out.add("action", only);
            return;
        }
        out.addProperty("type", "minecraft:multi_action");
        JsonArray actions = new JsonArray();
        buttons.forEach(actions::add);
        out.add("actions", actions);
        out.addProperty("columns", def.columns());
        if (exit != null) out.add("exit_action", exit);
    }

    /** Fills {page}, {pages}, {has_next}, {has_prev} before anything reads them, from the first paged list. */
    private void paging(DialogDef def, Scope scope, Session session, Map<String, List<Map<String, String>>> lists) {
        ForEach paged = null;
        for (BodyDef b : def.body()) if (b.forEach() != null && b.forEach().perPage() > 0) { paged = b.forEach(); break; }
        if (paged == null) for (ButtonDef b : def.buttons()) if (b.forEach() != null && b.forEach().perPage() > 0) { paged = b.forEach(); break; }
        if (paged == null) return;
        List<Map<String, String>> rows = rows(paged.list(), scope, lists);
        int pages = Math.max(1, (rows.size() + paged.perPage() - 1) / paged.perPage());
        int page = 1;
        try {
            page = Integer.parseInt(session.values.getOrDefault("page", "1"));
        } catch (NumberFormatException ignored) {
            // a page someone typed: start at the first
        }
        page = Math.max(1, Math.min(pages, page));
        session.values.put("page", String.valueOf(page));
        session.values.put("pages", String.valueOf(pages));
        session.values.put("has_next", String.valueOf(page < pages));
        session.values.put("has_prev", String.valueOf(page > 1));
        session.values.put(paged.list() + ".count", String.valueOf(rows.size()));
    }

    /** One scope per row for a for-each (paged and limited), or just the dialog's scope. */
    private List<Scope> expand(ForEach each, Scope scope, Map<String, List<Map<String, String>>> lists) {
        if (each == null) return List.of(scope);
        List<Map<String, String>> rows = rows(each.list(), scope, lists);
        int from = 0;
        int to = rows.size();
        if (each.perPage() > 0) {
            int page = 1;
            try {
                page = Integer.parseInt(scope.values.getOrDefault("page", "1"));
            } catch (NumberFormatException ignored) {
                // already clamped by paging()
            }
            from = Math.min(rows.size(), (page - 1) * each.perPage());
            to = Math.min(rows.size(), from + each.perPage());
        }
        if (each.limit() > 0) to = Math.min(to, from + each.limit());
        List<Scope> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            Map<String, String> row = new LinkedHashMap<>(rows.get(i));
            row.putIfAbsent("index", String.valueOf(i + 1));
            row.putIfAbsent("first", String.valueOf(i == 0));
            row.putIfAbsent("last", String.valueOf(i == rows.size() - 1));
            out.add(scope.withRow(each.alias(), row));
        }
        return out;
    }

    private List<Map<String, String>> rows(String list, Scope scope, Map<String, List<Map<String, String>>> cache) {
        return cache.computeIfAbsent(list, l -> engine.rows(l, scope));
    }

    private static JsonObject plain(JsonElement text, int width) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:plain_message");
        o.add("contents", text);
        o.addProperty("width", width);
        return o;
    }

    private JsonObject body(BodyDef b, Scope s) {
        if (b.kind() == BodyKind.TEXT) {
            return plain(Text.json(b.text(), s), b.width());
        }
        JsonObject item = new JsonObject();
        item.addProperty("type", "minecraft:item");
        item.add("item", item(b, s));
        if (b.description() != null) {
            JsonObject d = new JsonObject();
            d.add("contents", Text.json(b.description(), s));
            d.addProperty("width", 200);
            item.add("description", d);
        }
        item.addProperty("show_decorations", b.decorations());
        item.addProperty("show_tooltip", b.tooltip());
        item.addProperty("width", b.itemWidth());
        item.addProperty("height", b.itemHeight());
        return item;
    }

    /** An item stack as the game writes one: id, count and the components a body item can carry. */
    private JsonObject item(BodyDef b, Scope s) {
        String raw = Text.fill(b.item(), s, Text.Mode.RAW).trim().toLowerCase(Locale.ROOT);
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem() || material.isAir()) material = Material.PAPER;
        JsonObject stack = new JsonObject();
        stack.addProperty("id", material.getKey().toString());
        stack.addProperty("count", Math.max(1, Math.min(99, b.amount())));
        JsonObject components = new JsonObject();
        if (b.name() != null) {
            components.add("minecraft:custom_name", Text.json(Text.component(b.name(), s).decoration(TextDecoration.ITALIC, false)));
        }
        if (!b.lore().isEmpty()) {
            JsonArray lore = new JsonArray();
            for (String line : b.lore()) {
                lore.add(Text.json(Text.component(line, s).decoration(TextDecoration.ITALIC, false)));
            }
            components.add("minecraft:lore", lore);
        }
        if (b.model() != null) {
            NamespacedKey key = NamespacedKey.fromString(Text.fill(b.model(), s, Text.Mode.RAW).trim().toLowerCase(Locale.ROOT));
            if (key != null) components.addProperty("minecraft:item_model", key.toString());
        }
        if (b.glow()) components.addProperty("minecraft:enchantment_glint_override", true);
        if (b.head() != null && material == Material.PLAYER_HEAD) {
            JsonObject profile = profile(Text.fill(b.head(), s, Text.Mode.RAW).trim());
            if (profile != null) components.add("minecraft:profile", profile);
        }
        if (!components.isEmpty()) stack.add("components", components);
        return stack;
    }

    /**
     * A head's profile. An online player's carries their skin (the textures
     * property, unsigned, which a head needs no signature for); anyone else is
     * named, and the client looks the skin up.
     */
    private static JsonObject profile(String name) {
        if (name.isEmpty() || name.startsWith("{")) return null;
        JsonObject profile = new JsonObject();
        Player online = Bukkit.getPlayerExact(name);
        UUID id = null;
        String shown = name;
        if (online != null) {
            id = online.getUniqueId();
            shown = online.getName();
            try {
                URL skin = online.getPlayerProfile().getTextures().getSkin();
                if (skin != null) {
                    String textures = "{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}";
                    JsonObject property = new JsonObject();
                    property.addProperty("name", "textures");
                    property.addProperty("value", Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)));
                    JsonArray properties = new JsonArray();
                    properties.add(property);
                    profile.add("properties", properties);
                }
            } catch (RuntimeException ignored) {
                // no skin to hand over: the name will do
            }
        } else {
            OfflinePlayer cached = cachedOffline(name);
            if (cached != null) {
                id = cached.getUniqueId();
                if (cached.getName() != null) shown = cached.getName();
            } else if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                return null;
            }
        }
        profile.addProperty("name", shown);
        if (id != null) {
            JsonArray ints = new JsonArray();
            long hi = id.getMostSignificantBits();
            long lo = id.getLeastSignificantBits();
            ints.add((int) (hi >> 32));
            ints.add((int) hi);
            ints.add((int) (lo >> 32));
            ints.add((int) lo);
            profile.add("id", ints);
        }
        return profile;
    }

    /** Someone who has played here, found without asking Mojang (getOfflinePlayer(name) can block on a lookup). */
    private static OfflinePlayer cachedOffline(String name) {
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private JsonObject input(InputDef in, Scope scope, Session session, Map<String, List<Map<String, String>>> lists,
                             Map<String, Map<String, String>> optionLabels) {
        JsonElement label = Text.json(in.label(), scope);
        String current = session.values.get(in.key());
        String initial = current != null ? current : (in.initial() == null ? null : Text.fill(in.initial(), scope, Text.Mode.RAW));
        JsonObject o = new JsonObject();
        o.addProperty("key", in.key());
        o.add("label", label);
        switch (in.kind()) {
            case TEXT -> {
                o.addProperty("type", "minecraft:text");
                o.addProperty("width", in.width());
                o.addProperty("label_visible", in.labelVisible());
                o.addProperty("max_length", in.maxLength());
                o.addProperty("initial", initial == null ? "" : clip(initial, in.maxLength()));
                if (in.lines() > 0 || in.height() > 0) {
                    JsonObject multiline = new JsonObject();
                    if (in.lines() > 0) multiline.addProperty("max_lines", in.lines());
                    if (in.height() > 0) multiline.addProperty("height", in.height());
                    o.add("multiline", multiline);
                }
                return o;
            }
            case TOGGLE -> {
                o.addProperty("type", "minecraft:boolean");
                o.addProperty("initial", Conditions.truthy(initial));
                if (in.onTrue() != null) o.addProperty("on_true", in.onTrue());
                if (in.onFalse() != null) o.addProperty("on_false", in.onFalse());
                return o;
            }
            case SLIDER -> {
                o.addProperty("type", "minecraft:number_range");
                o.addProperty("width", in.width());
                o.addProperty("label_format", in.format());
                o.addProperty("start", in.min());
                o.addProperty("end", in.max());
                if (in.step() != null) o.addProperty("step", in.step());
                if (initial != null) {
                    try {
                        float f = Float.parseFloat(initial.trim());
                        o.addProperty("initial", Math.max(in.min(), Math.min(in.max(), f)));
                    } catch (NumberFormatException ignored) {
                        // not a number: the slider starts at its middle
                    }
                }
                return o;
            }
            case OPTIONS -> {
                JsonArray entries = new JsonArray();
                Map<String, String> labels = new LinkedHashMap<>();
                List<OptionDef> defs = new ArrayList<>();
                List<Scope> scopes = new ArrayList<>();
                if (in.optionsForEach() != null) {
                    for (Scope s : expand(in.optionsForEach(), scope, lists)) {
                        defs.add(in.optionTemplate());
                        scopes.add(s);
                    }
                } else {
                    for (OptionDef od : in.options()) {
                        defs.add(od);
                        scopes.add(scope);
                    }
                }
                boolean anyInitial = false;
                for (int i = 0; i < defs.size(); i++) {
                    OptionDef od = defs.get(i);
                    Scope s = scopes.get(i);
                    String id = Text.fill(od.id(), s, Text.Mode.RAW).trim();
                    if (id.isEmpty() || labels.containsKey(id)) continue;
                    Component display = Text.component(od.text(), s);
                    boolean isInitial;
                    if (initial != null) isInitial = id.equalsIgnoreCase(initial.trim());
                    else isInitial = Conditions.truthy(Text.fill(od.initial() == null ? "" : od.initial(), s, Text.Mode.RAW));
                    if (isInitial && anyInitial) isInitial = false;
                    anyInitial |= isInitial;
                    labels.put(id, Platform.plain(display));
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", id);
                    entry.add("display", Text.json(display));
                    if (isInitial) entry.addProperty("initial", true);
                    entries.add(entry);
                }
                if (entries.isEmpty()) {
                    engine.warnOnce(scope.dialogId + " input " + in.key() + " has no options to show; it is left out");
                    return null;
                }
                optionLabels.put(in.key(), labels);
                o.addProperty("type", "minecraft:single_option");
                o.addProperty("width", in.width());
                o.addProperty("label_visible", in.labelVisible());
                o.add("options", entries);
                return o;
            }
            default -> {
                return null;
            }
        }
    }

    private JsonObject button(DialogDef def, ButtonDef b, Scope s, Session session, List<InputDef> inputs,
                              Map<String, Map<String, String>> optionLabels, Player viewer, long drawing) {
        JsonObject button = new JsonObject();
        button.add("label", Text.json(b.label(), s));
        if (b.tooltip() != null) button.add("tooltip", Text.json(b.tooltip(), s));
        button.addProperty("width", b.width());
        JsonObject action = staticAction(b, s);
        if (action == null) {
            // Every other button calls back, even one with no actions: a plugin
            // may own it through DialogClickEvent.
            Map<String, String> row = s.row == null ? Map.of() : Map.copyOf(s.row);
            String alias = s.alias;
            // The player who clicked, not the one drawn for: Clicks has checked they are
            // the same, and onClick checks again against the session.
            String token = engine.clicks().bind(viewer, engine.clickLifetime(), drawing,
                    (clicker, payload) -> engine.onClick(clicker, drawing, def, b, session, alias, row, inputs, optionLabels, payload));
            action = Clicks.action(token);
        }
        button.add("action", action);
        return button;
    }

    /**
     * A button whose only action is something the CLIENT does (open a link,
     * copy, suggest a command) has to be a static click: the server cannot
     * open a browser after the fact.
     */
    private JsonObject staticAction(ButtonDef b, Scope s) {
        if (b.actions().size() != 1) return null;
        String a = b.actions().get(0).trim();
        String lower = a.toLowerCase(Locale.ROOT);
        JsonObject action = new JsonObject();
        if (lower.startsWith("[url]")) {
            String arg = Text.fill(a.substring(5).trim(), s, Text.Mode.RAW);
            if (!arg.startsWith("http://") && !arg.startsWith("https://")) arg = "https://" + arg;
            action.addProperty("type", "minecraft:open_url");
            action.addProperty("url", arg);
            return action;
        }
        if (lower.startsWith("[copy]")) {
            action.addProperty("type", "minecraft:copy_to_clipboard");
            action.addProperty("value", Text.fill(a.substring(6).trim(), s, Text.Mode.RAW));
            return action;
        }
        if (lower.startsWith("[suggest]")) {
            String cmd = Text.fill(a.substring(9).trim(), s, Text.Mode.RAW);
            action.addProperty("type", "minecraft:suggest_command");
            action.addProperty("command", cmd.startsWith("/") ? cmd : "/" + cmd);
            return action;
        }
        return null;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
