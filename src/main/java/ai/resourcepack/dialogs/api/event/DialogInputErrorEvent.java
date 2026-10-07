package ai.resourcepack.dialogs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Map;

/**
 * A button was pressed but an input failed its check ({@code required},
 * {@code min-length}, {@code pattern}...). The dialog is shown again with
 * {@link #getMessage()} on it and what the player typed still in place.
 * The message may be changed.
 */
public final class DialogInputErrorEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String dialogId;
    private final String inputKey;
    private final Map<String, String> inputs;
    private String message;

    public DialogInputErrorEvent(Player player, String dialogId, String inputKey, Map<String, String> inputs, String message) {
        super(player);
        this.dialogId = dialogId;
        this.inputKey = inputKey;
        this.inputs = inputs;
        this.message = message;
    }

    public String getDialogId() {
        return dialogId;
    }

    /** The first input that failed. */
    public String getInputKey() {
        return inputKey;
    }

    public Map<String, String> getInputs() {
        return inputs;
    }

    /** MiniMessage, shown on the dialog as {@code {error}}. */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
