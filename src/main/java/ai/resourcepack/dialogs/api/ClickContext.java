package ai.resourcepack.dialogs.api;

import java.util.Map;

/**
 * A button press, handed to an {@link ActionHandler}. Values set here are
 * seen by the actions after this one and by any dialog opened from it.
 */
public interface ClickContext extends DialogContext {

    /** The button's {@code id:}, or its position ({@code "0"}, {@code "1"}...) when it has none. {@code "exit"} for the exit button. */
    String buttonId();

    /** What the player entered in the dialog's inputs, by input key. */
    Map<String, String> inputs();

    /** The for-each row the button was drawn for, or an empty map. */
    Map<String, String> row();

    /** Sets a value for the rest of this click (and any dialog it opens). Null removes it. */
    void set(String key, String value);

    /** Fills placeholders in a string the way an action's argument is filled. */
    String resolve(String text);

    /** Opens another dialog, carrying this one's values over plus {@code extra}. {@code [back]} returns here. */
    void open(String dialogId, Map<String, String> extra);

    /** Draws this dialog again with the current values. */
    void refresh();

    /** Goes back to the dialog this one was opened from, or closes when there is none. */
    void back();

    /** Closes the dialog. */
    void close();

    /** Stops the actions after this one. */
    void stop();
}
