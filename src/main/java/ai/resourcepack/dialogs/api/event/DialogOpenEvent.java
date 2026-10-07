package ai.resourcepack.dialogs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Map;

/**
 * A dialog is about to be drawn for a player: opened, refreshed, paged, or
 * returned to. Cancel it and nothing is shown. {@link #values()} is the live
 * map the dialog is about to be drawn with, so a listener may add to it.
 */
public final class DialogOpenEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String dialogId;
    private final Map<String, String> values;
    private final boolean reopened;
    private boolean cancelled;

    public DialogOpenEvent(Player player, String dialogId, Map<String, String> values, boolean reopened) {
        super(player);
        this.dialogId = dialogId;
        this.values = values;
        this.reopened = reopened;
    }

    public String getDialogId() {
        return dialogId;
    }

    /** The values the dialog will be drawn with. Mutable. */
    public Map<String, String> values() {
        return values;
    }

    /** True when this is the same dialog drawn again (refresh, page, a failed check), not a new one. */
    public boolean isReopened() {
        return reopened;
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
