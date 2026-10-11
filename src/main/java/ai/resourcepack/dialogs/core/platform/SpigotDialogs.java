package ai.resourcepack.dialogs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.dialog.ConfirmationDialog;
import net.md_5.bungee.api.dialog.Dialog;
import net.md_5.bungee.api.dialog.DialogBase;
import net.md_5.bungee.api.dialog.MultiActionDialog;
import net.md_5.bungee.api.dialog.NoticeDialog;
import net.md_5.bungee.api.dialog.ServerLinksDialog;
import net.md_5.bungee.api.dialog.action.Action;
import net.md_5.bungee.api.dialog.action.ActionButton;
import net.md_5.bungee.api.dialog.action.CustomClickAction;
import net.md_5.bungee.api.dialog.action.StaticAction;
import net.md_5.bungee.api.dialog.body.DialogBody;
import net.md_5.bungee.api.dialog.body.PlainMessageBody;
import net.md_5.bungee.api.dialog.input.BooleanInput;
import net.md_5.bungee.api.dialog.input.DialogInput;
import net.md_5.bungee.api.dialog.input.InputOption;
import net.md_5.bungee.api.dialog.input.NumberRangeInput;
import net.md_5.bungee.api.dialog.input.SingleOptionInput;
import net.md_5.bungee.api.dialog.input.TextInput;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Spigot's route: the dialog JSON {@link Renderer} wrote, rebuilt as Spigot's
 * own dialog objects (BungeeCord's dialog API) and shown with
 * {@code Player.showDialog}. Loaded only on a server that has that method.
 *
 * <p>It reads only the shapes Renderer writes, not every dialog the game can
 * describe. Spigot's API has no item body, so {@link ItemBody} adds one:
 * Spigot writes its dialog objects out field by field with Gson before the
 * server decodes them, and a subclass's fields are written the same way.
 */
final class SpigotDialogs {

    private SpigotDialogs() {}

    static void show(Player player, JsonObject json) {
        player.showDialog(dialog(json));
    }

    static Dialog dialog(JsonObject j) {
        DialogBase base = new DialogBase(text(j.get("title")));
        if (j.has("external_title")) base.externalTitle(text(j.get("external_title")));
        base.canCloseWithEscape(bool(j, "can_close_with_escape"));
        base.pause(bool(j, "pause"));
        base.afterAction(switch (str(j, "after_action", "close")) {
            case "none" -> DialogBase.AfterAction.NONE;
            case "wait_for_response" -> DialogBase.AfterAction.WAIT_FOR_RESPONSE;
            default -> DialogBase.AfterAction.CLOSE;
        });
        List<DialogBody> body = new ArrayList<>();
        for (JsonElement e : arr(j, "body")) body.add(body(e.getAsJsonObject()));
        base.body(body);
        List<DialogInput> inputs = new ArrayList<>();
        for (JsonElement e : arr(j, "inputs")) inputs.add(input(e.getAsJsonObject()));
        base.inputs(inputs);

        String type = str(j, "type", "minecraft:notice");
        return switch (type) {
            case "minecraft:confirmation" -> new ConfirmationDialog(base, button(j.getAsJsonObject("yes")), button(j.getAsJsonObject("no")));
            case "minecraft:multi_action" -> {
                List<ActionButton> actions = new ArrayList<>();
                for (JsonElement e : arr(j, "actions")) actions.add(button(e.getAsJsonObject()));
                yield new MultiActionDialog(base, actions, integer(j, "columns"),
                        j.has("exit_action") ? button(j.getAsJsonObject("exit_action")) : null);
            }
            case "minecraft:server_links" -> new ServerLinksDialog(base,
                    j.has("exit_action") ? button(j.getAsJsonObject("exit_action")) : null,
                    integer(j, "columns"), integer(j, "button_width"));
            default -> j.has("action") ? new NoticeDialog(base, button(j.getAsJsonObject("action"))) : new NoticeDialog(base);
        };
    }

    private static ActionButton button(JsonObject b) {
        ActionButton button = new ActionButton(text(b.get("label")), b.has("action") ? action(b.getAsJsonObject("action")) : null);
        if (b.has("tooltip")) button.tooltip(text(b.get("tooltip")));
        if (b.has("width")) button.width(b.get("width").getAsInt());
        return button;
    }

    private static Action action(JsonObject a) {
        String type = str(a, "type", "");
        if (type.startsWith("minecraft:")) type = type.substring("minecraft:".length());
        return switch (type) {
            case "open_url" -> new StaticAction(new ClickEvent(ClickEvent.Action.OPEN_URL, str(a, "url", "")));
            case "copy_to_clipboard" -> new StaticAction(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, str(a, "value", "")));
            case "suggest_command" -> new StaticAction(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, str(a, "command", "")));
            case "dynamic/custom" -> {
                CustomClickAction custom = new CustomClickAction(str(a, "id", ""));
                if (a.has("additions")) custom.additions(a.get("additions"));
                yield custom;
            }
            default -> throw new IllegalArgumentException("SpigotDialogs cannot carry a " + type + " action");
        };
    }

    private static DialogBody body(JsonObject b) {
        if ("minecraft:item".equals(str(b, "type", ""))) {
            ItemBody item = new ItemBody();
            item.item = b.getAsJsonObject("item");
            if (b.has("description")) {
                JsonObject d = b.getAsJsonObject("description");
                item.description = new PlainMessageBody(text(d.get("contents")), integer(d, "width"));
            }
            item.showDecorations = bool(b, "show_decorations");
            item.showTooltip = bool(b, "show_tooltip");
            item.width = integer(b, "width");
            item.height = integer(b, "height");
            return item;
        }
        return new PlainMessageBody(text(b.get("contents")), integer(b, "width"));
    }

    private static DialogInput input(JsonObject in) {
        String key = str(in, "key", "");
        BaseComponent label = text(in.get("label"));
        return switch (str(in, "type", "")) {
            case "minecraft:boolean" -> new BooleanInput(key, label, bool(in, "initial"), str(in, "on_true", "true"), str(in, "on_false", "false"));
            case "minecraft:number_range" -> {
                // By setter: the long constructor's last two Floats are (step, initial), easily swapped.
                NumberRangeInput range = new NumberRangeInput(key, label, in.get("start").getAsFloat(), in.get("end").getAsFloat());
                range.width(integer(in, "width"));
                range.labelFormat(str(in, "label_format", "options.generic_value"));
                range.step(number(in, "step"));
                range.initial(number(in, "initial"));
                yield range;
            }
            case "minecraft:single_option" -> {
                List<InputOption> options = new ArrayList<>();
                for (JsonElement e : arr(in, "options")) {
                    JsonObject o = e.getAsJsonObject();
                    options.add(new InputOption(str(o, "id", ""), o.has("display") ? text(o.get("display")) : null, bool(o, "initial")));
                }
                yield new SingleOptionInput(key, integer(in, "width"), label, bool(in, "label_visible"), options);
            }
            default -> {
                TextInput.Multiline multiline = null;
                if (in.has("multiline")) {
                    JsonObject m = in.getAsJsonObject("multiline");
                    multiline = new TextInput.Multiline(integer(m, "max_lines"), integer(m, "height"));
                }
                yield new TextInput(key, integer(in, "width"), label, bool(in, "label_visible"), str(in, "initial", ""),
                        integer(in, "max_length"), multiline);
            }
        };
    }

    /** A text component as JSON, back to BungeeCord chat (through Adventure, which reads every shape it writes). */
    private static BaseComponent text(JsonElement json) {
        if (json == null || json.isJsonNull()) return new TextComponent("");
        BaseComponent[] parts = BungeeComponentSerializer.get().serialize(GsonComponentSerializer.gson().deserializeFromTree(json));
        return parts.length == 1 ? parts[0] : new TextComponent(parts);
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static Boolean bool(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsBoolean() : null;
    }

    private static Integer integer(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : null;
    }

    private static Float number(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsFloat() : null;
    }

    /** {@code minecraft:item}, which BungeeCord's dialog API lacks. Written by Gson field by field, as Spigot's own bodies are. */
    static final class ItemBody extends DialogBody {
        JsonObject item;
        PlainMessageBody description;
        @SerializedName("show_decorations")
        Boolean showDecorations;
        @SerializedName("show_tooltip")
        Boolean showTooltip;
        Integer width;
        Integer height;

        ItemBody() {
            super("minecraft:item");
        }
    }
}
