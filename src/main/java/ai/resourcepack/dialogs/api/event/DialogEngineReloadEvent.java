package ai.resourcepack.dialogs.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Every dialog has just been read again ({@code /de reload} or {@code reload()}). */
public final class DialogEngineReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int dialogs;

    public DialogEngineReloadEvent(int dialogs) {
        this.dialogs = dialogs;
    }

    /** How many dialogs are loaded now. */
    public int getDialogCount() {
        return dialogs;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
