package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.core.Defs.BodyDef;
import ai.resourcepack.dialogs.core.Defs.BodyKind;
import ai.resourcepack.dialogs.core.Defs.ButtonDef;
import ai.resourcepack.dialogs.core.Defs.DialogDef;
import ai.resourcepack.dialogs.core.Defs.ForEach;
import ai.resourcepack.dialogs.core.Defs.InputDef;
import ai.resourcepack.dialogs.core.Defs.OptionDef;
import ai.resourcepack.dialogs.core.Defs.Type;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a definition and a session into the game's dialog, for one player.
 * A dialog is drawn fresh every time it is shown: placeholders, conditions and
 * lists are all evaluated here, and every button gets a callback bound to the
 * session it was drawn from.
 */
final class Renderer {

    private final Engine engine;

    Renderer(Engine engine) {
        this.engine = engine;
    }

    /** What one drawing produced: the dialog, and the inputs on it (so a click knows what to read). */
    record Drawn(Dialog dialog, List<InputDef> inputs, Map<String, Map<String, String>> optionLabels) {}

    Drawn draw(DialogDef def, Session session, Player viewer) {
        Scope scope = new Scope(engine, viewer, def.id(), session.values, session.untrusted, null, null);
        Map<String, List<Map<String, String>>> lists = new HashMap<>();
        paging(def, scope, session, lists);

        Component title = Text.component(def.title(), scope);

        List<DialogBody> body = new ArrayList<>();
        String error = session.values.get("error");
        boolean printsError = def.body().stream().anyMatch(b -> b.text() != null && b.text().contains("{error"));
        if (error != null && !error.isEmpty() && !printsError) {
            body.add(DialogBody.plainMessage(Text.component(engine.validationFormat(), scope), 300));
        }
        for (BodyDef b : def.body()) {
            for (Scope s : expand(b.forEach(), scope, lists)) {
                if (!Conditions.test(b.condition(), s)) continue;
                body.add(body(b, s));
            }
        }

        List<DialogInput> inputs = new ArrayList<>();
        List<InputDef> drawnInputs = new ArrayList<>();
        Map<String, Map<String, String>> optionLabels = new HashMap<>();
        for (InputDef in : def.inputs()) {
            if (!Conditions.test(in.condition(), scope)) continue;
            DialogInput built = input(in, scope, session, lists, optionLabels);
            if (built == null) continue;
            inputs.add(built);
            drawnInputs.add(in);
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (ButtonDef b : def.buttons()) {
            for (Scope s : expand(b.forEach(), scope, lists)) {
                if (b.permission() != null && !viewer.hasPermission(b.permission())) continue;
                if (!Conditions.test(b.condition(), s)) continue;
                buttons.add(button(def, b, s, session, drawnInputs, optionLabels));
            }
        }
        ActionButton exit = null;
        if (def.exit() != null && Conditions.test(def.exit().condition(), scope)
                && (def.exit().permission() == null || viewer.hasPermission(def.exit().permission()))) {
            exit = button(def, def.exit(), scope, session, drawnInputs, optionLabels);
        }

        DialogBase.Builder base = DialogBase.builder(title)
                .canCloseWithEscape(def.escape())
                .pause(def.pause())
                .afterAction(def.after())
                .body(body)
                .inputs(inputs);
        if (def.externalTitle() != null) base.externalTitle(Text.component(def.externalTitle(), scope));
        DialogBase builtBase = base.build();

        DialogType type = type(def, buttons, exit);
        Dialog dialog = Dialog.create(f -> f.empty().base(builtBase).type(type));
        return new Drawn(dialog, drawnInputs, optionLabels);
    }

    private static DialogType type(DialogDef def, List<ActionButton> buttons, ActionButton exit) {
        Type t = def.type();
        if (t == Type.LINKS) return DialogType.serverLinks(exit, def.columns(), 150);
        if (t == Type.CONFIRMATION && buttons.size() >= 2) return DialogType.confirmation(buttons.get(0), buttons.get(1));
        if (t == Type.NOTICE || buttons.isEmpty() || (t == Type.AUTO && buttons.size() == 1 && exit == null)) {
            ActionButton only = !buttons.isEmpty() ? buttons.get(0) : exit;
            return only == null ? DialogType.notice() : DialogType.notice(only);
        }
        var multi = DialogType.multiAction(buttons).columns(def.columns());
        if (exit != null) multi.exitAction(exit);
        return multi.build();
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

    private DialogBody body(BodyDef b, Scope s) {
        if (b.kind() == BodyKind.TEXT) {
            return DialogBody.plainMessage(Text.component(b.text(), s), b.width());
        }
        var item = DialogBody.item(item(b, s))
                .showTooltip(b.tooltip())
                .showDecorations(b.decorations())
                .width(b.itemWidth())
                .height(b.itemHeight());
        if (b.description() != null) item.description(DialogBody.plainMessage(Text.component(b.description(), s), 200));
        return item.build();
    }

    private ItemStack item(BodyDef b, Scope s) {
        String raw = Text.fill(b.item(), s, Text.Mode.RAW).trim().toLowerCase(Locale.ROOT);
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem()) material = Material.PAPER;
        ItemStack stack = new ItemStack(material, b.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (b.name() != null) meta.displayName(Text.component(b.name(), s).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (!b.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : b.lore()) {
                lore.add(Text.component(line, s).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        if (b.model() != null) {
            NamespacedKey key = NamespacedKey.fromString(Text.fill(b.model(), s, Text.Mode.RAW).trim().toLowerCase(Locale.ROOT));
            if (key != null) meta.setItemModel(key);
        }
        if (b.glow()) meta.setEnchantmentGlintOverride(true);
        if (b.head() != null && meta instanceof SkullMeta skull) {
            String name = Text.fill(b.head(), s, Text.Mode.RAW).trim();
            PlayerProfile profile = profile(name);
            if (profile != null) skull.setPlayerProfile(profile);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static PlayerProfile profile(String name) {
        if (name.isEmpty() || name.startsWith("{")) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getPlayerProfile();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return Bukkit.createProfile(cached.getUniqueId(), cached.getName());
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return null;
        return Bukkit.createProfile(name);
    }

    private DialogInput input(InputDef in, Scope scope, Session session, Map<String, List<Map<String, String>>> lists,
                              Map<String, Map<String, String>> optionLabels) {
        Component label = Text.component(in.label(), scope);
        String current = session.values.get(in.key());
        String initial = current != null ? current : (in.initial() == null ? null : Text.fill(in.initial(), scope, Text.Mode.RAW));
        switch (in.kind()) {
            case TEXT -> {
                var t = DialogInput.text(in.key(), label)
                        .width(in.width())
                        .labelVisible(in.labelVisible())
                        .maxLength(in.maxLength())
                        .initial(initial == null ? "" : clip(initial, in.maxLength()));
                if (in.lines() > 0 || in.height() > 0) {
                    t.multiline(TextDialogInput.MultilineOptions.create(in.lines() > 0 ? in.lines() : null,
                            in.height() > 0 ? in.height() : null));
                }
                return t.build();
            }
            case TOGGLE -> {
                var b = DialogInput.bool(in.key(), label).initial(Conditions.truthy(initial));
                if (in.onTrue() != null) b.onTrue(in.onTrue());
                if (in.onFalse() != null) b.onFalse(in.onFalse());
                return b.build();
            }
            case SLIDER -> {
                var r = DialogInput.numberRange(in.key(), label, in.min(), in.max())
                        .width(in.width())
                        .labelFormat(in.format());
                if (in.step() != null) r.step(in.step());
                if (initial != null) {
                    try {
                        float f = Float.parseFloat(initial.trim());
                        r.initial(Math.max(in.min(), Math.min(in.max(), f)));
                    } catch (NumberFormatException ignored) {
                        // not a number: the slider starts at its middle
                    }
                }
                return r.build();
            }
            case OPTIONS -> {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                Map<String, String> labels = new LinkedHashMap<>();
                List<OptionDef> defs = new ArrayList<>();
                List<Scope> scopes = new ArrayList<>();
                if (in.optionsForEach() != null) {
                    for (Scope s : expand(in.optionsForEach(), scope, lists)) {
                        defs.add(in.optionTemplate());
                        scopes.add(s);
                    }
                } else {
                    for (OptionDef o : in.options()) {
                        defs.add(o);
                        scopes.add(scope);
                    }
                }
                boolean anyInitial = false;
                for (int i = 0; i < defs.size(); i++) {
                    OptionDef o = defs.get(i);
                    Scope s = scopes.get(i);
                    String id = Text.fill(o.id(), s, Text.Mode.RAW).trim();
                    if (id.isEmpty() || labels.containsKey(id)) continue;
                    Component display = Text.component(o.text(), s);
                    boolean isInitial;
                    if (initial != null) isInitial = id.equalsIgnoreCase(initial.trim());
                    else isInitial = Conditions.truthy(Text.fill(o.initial() == null ? "" : o.initial(), s, Text.Mode.RAW));
                    if (isInitial && anyInitial) isInitial = false;
                    anyInitial |= isInitial;
                    labels.put(id, net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display));
                    entries.add(SingleOptionDialogInput.OptionEntry.create(id, display, isInitial));
                }
                if (entries.isEmpty()) {
                    engine.warnOnce(scope.dialogId + " input " + in.key() + " has no options to show; it is left out");
                    return null;
                }
                optionLabels.put(in.key(), labels);
                return DialogInput.singleOption(in.key(), label, entries)
                        .width(in.width())
                        .labelVisible(in.labelVisible())
                        .build();
            }
            default -> {
                return null;
            }
        }
    }

    private ActionButton button(DialogDef def, ButtonDef b, Scope s, Session session, List<InputDef> inputs,
                                Map<String, Map<String, String>> optionLabels) {
        var builder = ActionButton.builder(Text.component(b.label(), s)).width(b.width());
        if (b.tooltip() != null) builder.tooltip(Text.component(b.tooltip(), s));
        DialogAction action = staticAction(b, s);
        if (action == null) {
            // Every other button calls back, even one with no actions: a plugin
            // may own it through DialogClickEvent.
            Map<String, String> row = s.row == null ? Map.of() : Map.copyOf(s.row);
            String alias = s.alias;
            action = DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p) {
                    engine.onClick(p, def, b, session, alias, row, inputs, optionLabels, view);
                }
            }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(engine.clickLifetime()).build());
        }
        if (action != null) builder.action(action);
        return builder.build();
    }

    /**
     * A button whose only action is something the CLIENT does (open a link,
     * copy, suggest a command) has to be a static click: the server cannot
     * open a browser after the fact.
     */
    private DialogAction staticAction(ButtonDef b, Scope s) {
        if (b.actions().size() != 1) return null;
        String a = b.actions().get(0).trim();
        String lower = a.toLowerCase(Locale.ROOT);
        String arg;
        if (lower.startsWith("[url]")) {
            arg = Text.fill(a.substring(5).trim(), s, Text.Mode.RAW);
            if (!arg.startsWith("http://") && !arg.startsWith("https://")) arg = "https://" + arg;
            return DialogAction.staticAction(ClickEvent.openUrl(arg));
        }
        if (lower.startsWith("[copy]")) {
            return DialogAction.staticAction(ClickEvent.copyToClipboard(Text.fill(a.substring(6).trim(), s, Text.Mode.RAW)));
        }
        if (lower.startsWith("[suggest]")) {
            String cmd = Text.fill(a.substring(9).trim(), s, Text.Mode.RAW);
            return DialogAction.staticAction(ClickEvent.suggestCommand(cmd.startsWith("/") ? cmd : "/" + cmd));
        }
        return null;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
