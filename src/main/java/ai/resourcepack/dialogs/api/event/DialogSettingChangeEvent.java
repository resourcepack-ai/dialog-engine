package ai.resourcepack.dialogs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player flipped a switch, moved a slider or picked from a dropdown on a
 * drawn dialog, and the setting it is bound to is about to change. Fired
 * before the value is kept and the dialog drawn again with it.
 *
 * <p>Cancel it and the setting keeps its old value; the dialog is drawn again
 * showing that. The new value is always one the dialog offers.
 */
public final class DialogSettingChangeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String dialogId;
    private final String name;
    private final String oldValue;
    private final String newValue;
    private boolean cancelled;

    public DialogSettingChangeEvent(Player player, String dialogId, String name, String oldValue, String newValue) {
        super(player);
        this.dialogId = dialogId;
        this.name = name;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    /** {@code namespace:name} of the dialog it was changed on. */
    public String getDialogId() {
        return dialogId;
    }

    /** The setting's name, as {@code dialogs.setting(player, name)} reads it. */
    public String getName() {
        return name;
    }

    /** What it was: the player's value, or the dialog's default when they had never set it. */
    public String getOldValue() {
        return oldValue;
    }

    /** What it is about to be. */
    public String getNewValue() {
        return newValue;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
