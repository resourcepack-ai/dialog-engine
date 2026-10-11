package ai.resourcepack.dialogs.core;

import java.util.Map;

/**
 * The messages of a {@code /de edit} session, as Gson reads and writes them.
 *
 * <p>The other end is Studio's edit-session wire, the same one RP Engine's
 * {@code /rp edit} uses: a session of kind {@code dialog}, carrying ONE dialog
 * as JSON. Studio reads and writes these field names exactly, so renaming one
 * here breaks {@code /de edit} until Studio is changed to match.
 */
final class EditWire {

    private EditWire() {
    }

    /** What opens a session. */
    static final class Open {
        String client;
        String kind;
        String label;
        String minecraftVersion;
        Dialog dialog;
    }

    /** A drawn dialog: where it is kept, its name, Studio's spec, and (on a pull) every page as built. */
    static final class Dialog {
        /** Relative to the plugin's folder: {@code drawn/server/shop}. */
        String path;
        String key;
        Map<String, Object> spec;
        java.util.List<Map<String, Object>> built;
    }

    /** One file of the pack that draws it. */
    static final class File {
        String path;
        String encoding;
        String content;
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
        java.util.List<File> files;
        Dialog dialog;
    }
}
