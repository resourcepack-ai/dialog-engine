package ai.resourcepack.dialogs.core;

import java.util.Map;

/**
 * The messages of a {@code /de edit} session, as Gson reads and writes them.
 *
 * <p>The other end is Studio's edit-session wire, the same one RP Engine's
 * {@code /rp edit} uses: a session of kind {@code dialog}, carrying ONE dialog
 * as JSON. Studio's wire check compares these classes with its own shapes field
 * for field, so a rename here is a rename there.
 */
final class EditWire {

    private EditWire() {
    }

    /** What opens a session. */
    static final class Open {
        String client;
        String kind;
        String label;
        Dialog dialog;
    }

    /** One dialog: where it lives, its key there, and what is under that key. */
    static final class Dialog {
        /** Relative to the plugin's folder: {@code dialogs/examples/welcome.yml}. */
        String path;
        String key;
        Map<String, Object> definition;
    }

    /** The answer to opening one. */
    static final class Opened {
        String id;
        String url;
        String pullToken;
        String expiresAt;
        long revision;
    }

    /** What polling answers. */
    static final class Status {
        String id;
        String kind;
        String label;
        long revision;
        String expiresAt;
        boolean opened;
    }

    /** What a pull answers: the dialog as it was sent from the editor. */
    static final class Pull {
        String id;
        String kind;
        long revision;
        Dialog dialog;
    }
}
