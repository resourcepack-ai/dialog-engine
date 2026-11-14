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
 * {@code id:} and no actions, and switch on {@link #getButtonId()}. Everything
 * the player has done on the dialog comes with it: {@link #getInputs()} for
 * what they typed, chose and toggled, {@link #getSettings()} for a drawn
 * dialog's switches, sliders and dropdowns.
 *
 * <p><b>Drawn dialogs fire it too</b> (since 0.8.0), for every piece whose
 * click runs actions. Name the piece by writing {@code [event] <id>} as its
 * command (in the editor: When clicked, Run command), and that id is
 * {@link #getButtonId()}; one listener then answers a whole menu. A piece
 * without one is {@code act_<n>}. A drawn dialog has no inputs.
 */
public final class DialogClickEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ClickContext context;
    private final List<String> actions;
    private final Map<String, String> settings;
    private final boolean drawn;
    private boolean cancelled;

    public DialogClickEvent(ClickContext context, List<String> actions) {
        this(context, actions, Map.of(), false);
    }

    public DialogClickEvent(ClickContext context, List<String> actions, Map<String, String> settings, boolean drawn) {
        super(context.viewer());
        this.context = context;
        this.actions = actions;
        this.settings = settings;
        this.drawn = drawn;
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

    /**
     * A drawn dialog's settings as the player has them now, by name: what its
     * switches, sliders and dropdowns are set to, the default for one never
     * touched. Empty for a YAML dialog. Read-only.
     */
    public Map<String, String> getSettings() {
        return settings;
    }

    /** Whether the dialog is a drawn one (made in the browser editor) rather than YAML. */
    public boolean isDrawn() {
        return drawn;
    }

    /** The dialog's values: what it was opened with and what actions have set, plus what was typed. */
    public Map<String, String> getValues() {
        return context.values();
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
