package ai.resourcepack.dialogs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Map;

/**
 * A dialog has left a player's screen. Fired once per dialog, after it has
 * gone, so it cannot be cancelled: open another from here if the player is to
 * stay in a menu.
 *
 * <p>Redrawing the same dialog (a refresh, a page, a failed check, a drawn
 * dialog's switch) is not a close. Opening a different one is, with
 * {@link Reason#REPLACED}, fired after the new one is on the screen.
 *
 * <p><b>Escape.</b> The game tells the server about Escape only by pressing
 * the dialog's exit button for the player, so a YAML dialog with an
 * {@code exit:} button (Escape presses it) and every drawn dialog report it as
 * {@link Reason#EXIT}. A YAML dialog with buttons and no {@code exit:} closes
 * with Escape without the server hearing anything; give it an {@code exit:}
 * (it needs no actions) when you need to know. Until then the engine still
 * thinks it is open, and its close is reported with whatever happens next.
 */
public final class DialogCloseEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why the dialog went. */
    public enum Reason {
        /**
         * The player pressed the exit button, or Escape, which presses it for
         * them ({@code exit:}, a confirmation's {@code cancel:}, a notice's only
         * button, a drawn dialog's Close). {@link #getButtonId()} is the button.
         */
        EXIT,
        /**
         * A button was pressed and nothing came after it: its actions ran (or
         * there were none) and closed the dialog, by {@code [close]}, by
         * {@code after: close}, or because nothing else answered the click.
         * {@link #getButtonId()} is the button.
         */
        BUTTON,
        /** Another dialog took its place. {@link #getNextDialogId()} is that one. */
        REPLACED,
        /**
         * Something that is not a dialog took the screen: a chest or other
         * container, the death screen, or another world's loading screen. Folia
         * tells plugins nothing about a move to another world, so there that one
         * is not reported.
         */
        SCREEN,
        /** A plugin closed it ({@code DialogEngineAPI.close}). */
        PLUGIN,
        /** The player left the server. */
        QUIT
    }

    private final String dialogId;
    private final Map<String, String> values;
    private final Map<String, String> settings;
    private final Reason reason;
    private final String buttonId;
    private final String nextDialogId;

    public DialogCloseEvent(Player player, String dialogId, Map<String, String> values, Map<String, String> settings,
                            Reason reason, String buttonId, String nextDialogId) {
        super(player);
        this.dialogId = dialogId;
        this.values = values;
        this.settings = settings;
        this.reason = reason;
        this.buttonId = buttonId;
        this.nextDialogId = nextDialogId;
    }

    /** {@code namespace:name} of the dialog that closed. */
    public String getDialogId() {
        return dialogId;
    }

    /**
     * Its values as it was left: what it was opened with, what its actions
     * {@code [set]}, and what was typed into its inputs by the last button
     * pressed. Read-only.
     */
    public Map<String, String> getValues() {
        return values;
    }

    /**
     * A drawn dialog's settings (its switches, sliders and dropdowns) as the
     * player left them, by name; the default for one never set. Empty for a
     * YAML dialog. Read-only.
     */
    public Map<String, String> getSettings() {
        return settings;
    }

    public Reason getReason() {
        return reason;
    }

    /** The button whose press closed it, for {@link Reason#EXIT} and {@link Reason#BUTTON}; null otherwise. */
    public String getButtonId() {
        return buttonId;
    }

    /** The dialog that replaced it, for {@link Reason#REPLACED}; null otherwise. */
    public String getNextDialogId() {
        return nextDialogId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
