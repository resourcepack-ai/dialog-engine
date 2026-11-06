// GENERATED from Studio's description of this wire. Do not edit by hand.
package ai.resourcepack.dialogs.core.edit;

import java.util.List;
import java.util.Map;

/**
 * The messages of a {@code /de edit} session, as Gson reads and writes them.
 *
 * <p>The other end is Studio's edit-session wire, the same one RP Engine's
 * {@code /rp edit} uses: a session of kind {@code dialog}, carrying ONE dialog
 * as JSON.
 *
 * <p><strong>Generated.</strong> Studio writes this file from the same
 * description it writes its own side from, so the two cannot disagree about a
 * field's name or type. Do not edit it by hand.
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
        public List<Map<String, Object>> built;
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
        public List<File> files;
        public Dialog dialog;
    }

    /** One file of the pack that draws it. */
    public static final class File {
        public String path;
        public String encoding;
        public String content;
    }
}
