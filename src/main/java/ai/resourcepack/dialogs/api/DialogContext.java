package ai.resourcepack.dialogs.api;

import org.bukkit.entity.Player;

import java.util.Map;

/** A dialog as it is on one player's screen: who sees it, which it is, and its values. */
public interface DialogContext {

    Player viewer();

    /** {@code namespace:name}. */
    String dialogId();

    /** A placeholder value: something the dialog was opened with, a value set by an action, or (after a click) what was typed. Null when unset. */
    String get(String key);

    /** Every value, read-only. */
    Map<String, String> values();
}
