package ai.resourcepack.dialogs.core.edit;

import java.util.Map;

/**
 * The messages of a {@code /de edit} session, as Gson reads and writes them.
 *
 * <p>The other end is Studio's edit-session wire, the same one RP Engine's
 * {@code /rp edit} uses: a session of kind {@code dialog}, carrying ONE dialog
 * as JSON. Studio reads and writes these field names exactly, so renaming one
 * here breaks {@code /de edit} until Studio is changed to match.
 */
public final class EditWire {

    private EditWire() {
    }

    /** What opens a session. */
    public static final class Open {
        public String client;
        public String kind;
        public String label;
        public String minecraftVersion;
        public Dialog dialog;
    }

    /** A drawn dialog: where it is kept, its name, Studio's spec, and (on a pull) every page as built. */
    public static final class Dialog {
        /** Relative to the plugin's folder: {@code drawn/server/shop}. */
        public String path;
        public String key;
        public Map<String, Object> spec;
        public java.util.List<Map<String, Object>> built;
    }

    /** One file of the pack that draws it. */
    public static final class File {
        public String path;
        public String encoding;
        public String content;
    }

    /** The answer to opening one. */
    public static final class Opened {
        public String id;
        public String url;
        public String pullToken;
        public String expiresAt;
        public long revision;
    }

    /** What polling answers. */
    public static final class Status {
        public String id;
        public String kind;
        public String label;
        public long revision;
        public String expiresAt;
        public boolean opened;
    }

    /** What a pull answers: the dialog as it was sent from the editor. */
    public static final class Pull {
        public String id;
        public String kind;
        public long revision;
        public java.util.List<File> files;
        public Dialog dialog;
    }
}
