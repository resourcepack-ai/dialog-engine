package ai.resourcepack.dialogs.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One drawing of one dialog for one player: which dialog, the values it was
 * drawn with, and the dialog it was opened from (for {@code [back]}).
 *
 * <p>Every button on a drawn dialog holds the session it was drawn from, so a
 * click always acts on the values that player saw, never on whatever another
 * dialog has done to the player since.
 */
final class Session {

    private static final int MAX_BACK = 16;

    final UUID viewer;
    final String dialogId;
    final LinkedHashMap<String, String> values;
    /** Keys whose values a player typed: escaped where printed, flattened to one line in commands. */
    final Set<String> untrusted;
    final Session previous;

    Session(UUID viewer, String dialogId, Map<String, String> values, Set<String> untrusted, Session previous) {
        this.viewer = viewer;
        this.dialogId = dialogId;
        this.values = new LinkedHashMap<>(values);
        this.untrusted = new HashSet<>(untrusted);
        this.previous = trim(previous);
    }

    /** The same dialog with different values (refresh, page, failed check). */
    Session with(Map<String, String> newValues, Set<String> newUntrusted) {
        return new Session(viewer, dialogId, newValues, newUntrusted, previous);
    }

    /** Another dialog opened from this one, carrying its values. */
    Session next(String id, Map<String, String> newValues, Set<String> newUntrusted) {
        return new Session(viewer, id, newValues, newUntrusted, this);
    }

    private static Session trim(Session previous) {
        // Keep the back chain short: a menu clicked round in circles must not grow without end.
        Session s = previous;
        int depth = 0;
        while (s != null) {
            if (++depth >= MAX_BACK) {
                return previous == null ? null
                        : new Session(previous.viewer, previous.dialogId, previous.values, previous.untrusted, null);
            }
            s = s.previous;
        }
        return previous;
    }
}
