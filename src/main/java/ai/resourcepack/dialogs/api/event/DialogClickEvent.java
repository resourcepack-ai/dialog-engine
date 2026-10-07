package ai.resourcepack.dialogs.api.event;

import ai.resourcepack.dialogs.api.ClickContext;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.List;
import java.util.Map;

/**
 * A player pressed a button on a dialog, after its inputs passed their checks
 * and before any of its actions run. Cancel it and none of them do; the
 * dialog stays as the button left it (use the context to close or refresh).
 *
 * <p>This is the event to listen to when your plugin owns a dialog outright
 * and would rather handle its buttons in Java than in YAML: give a button an
 * {@code id:} and no actions, and switch on {@link #getButtonId()}.
 */
public final class DialogClickEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ClickContext context;
    private final List<String> actions;
    private boolean cancelled;

    public DialogClickEvent(ClickContext context, List<String> actions) {
        super(context.viewer());
        this.context = context;
        this.actions = actions;
    }

    public String getDialogId() {
        return context.dialogId();
    }

    public String getButtonId() {
        return context.buttonId();
    }

    /** What was typed, chosen or toggled, by input key. */
    public Map<String, String> getInputs() {
        return context.inputs();
    }

    /** The click itself: values, the row, and open/refresh/close. */
    public ClickContext getContext() {
        return context;
    }

    /** The actions about to run, as written. Mutable: add, remove or replace them. */
    public List<String> getActions() {
        return actions;
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
