package ai.resourcepack.dialogs.core;

import ai.resourcepack.dialogs.api.ClickContext;
import ai.resourcepack.dialogs.core.Defs.DialogDef;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One button press while its actions run. */
final class ClickCtx implements ClickContext {

    final Engine engine;
    final Player player;
    final DialogDef def;
    Session session;
    private final String buttonId;
    private final Map<String, String> inputs;
    private final String alias;
    private final Map<String, String> row;

    /** Something has answered the click: a dialog was shown or the screen was closed. */
    boolean responded;
    boolean stopped;

    ClickCtx(Engine engine, Player player, DialogDef def, Session session, String buttonId, Map<String, String> inputs,
             String alias, Map<String, String> row) {
        this.engine = engine;
        this.player = player;
        this.def = def;
        this.session = session;
        this.buttonId = buttonId;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.alias = alias;
        this.row = row == null ? Map.of() : row;
        // The click itself makes this the player's current dialog, values and all.
        engine.remember(player, session);
    }

    Scope scope() {
        Scope s = new Scope(engine, player, def.id(), session.values, session.untrusted, null, null);
        return alias == null || row.isEmpty() ? s : s.withRow(alias, row);
    }

    /**
     * Called when the actions are done. A dialog waiting for its response must
     * get one, or the player is left looking at "waiting for the server".
     */
    void finish() {
        if (responded || def.after() != DialogAfterAction.WAIT_FOR_RESPONSE || !player.isOnline()) return;
        responded = true;
        // Not straight away: an action may have asked a plugin to open the next
        // dialog a moment from now (after a database read, say). Closing first
        // would flash the world between the two.
        engine.closeUnlessShown(player);
    }

    @Override
    public Player viewer() {
        return player;
    }

    @Override
    public String dialogId() {
        return def.id();
    }

    @Override
    public String get(String key) {
        return session.values.get(key);
    }

    @Override
    public Map<String, String> values() {
        return Collections.unmodifiableMap(session.values);
    }

    @Override
    public String buttonId() {
        return buttonId;
    }

    @Override
    public Map<String, String> inputs() {
        return inputs;
    }

    @Override
    public Map<String, String> row() {
        return row;
    }

    @Override
    public void set(String key, String value) {
        set(key, value, false);
    }

    void set(String key, String value, boolean untrusted) {
        if (value == null) session.values.remove(key);
        else session.values.put(key, value);
        if (untrusted) session.untrusted.add(key);
        else session.untrusted.remove(key);
    }

    @Override
    public String resolve(String text) {
        return Text.fill(text, scope(), Text.Mode.RAW);
    }

    @Override
    public void open(String dialogId, Map<String, String> extra) {
        String id = engine.qualify(dialogId, def.namespace());
        Map<String, String> values = new LinkedHashMap<>(session.values);
        Set<String> untrusted = new HashSet<>(session.untrusted);
        values.remove("error");
        // A new dialog starts on its first page.
        values.remove("page");
        if (extra != null) {
            extra.forEach((k, v) -> {
                values.put(k, v);
                untrusted.remove(k);
            });
        }
        if (engine.openAsPlayer(player, id, values, untrusted, session)) responded = true;
    }

    /** {@link #open} for {@code [open]}, where a value may have come from a player's words. */
    void open(String dialogId, Map<String, String> extra, Set<String> untrustedExtra) {
        String id = engine.qualify(dialogId, def.namespace());
        Map<String, String> values = new LinkedHashMap<>(session.values);
        Set<String> untrusted = new HashSet<>(session.untrusted);
        values.remove("error");
        values.remove("page");
        extra.forEach((k, v) -> {
            values.put(k, v);
            if (untrustedExtra.contains(k)) untrusted.add(k);
            else untrusted.remove(k);
        });
        if (engine.openAsPlayer(player, id, values, untrusted, session)) responded = true;
    }

    @Override
    public void refresh() {
        if (engine.show(player, session.with(session.values, session.untrusted), true)) responded = true;
    }

    @Override
    public void back() {
        Session previous = session.previous;
        if (previous == null) {
            close();
            return;
        }
        if (engine.show(player, previous.with(previous.values, previous.untrusted), true)) responded = true;
    }

    @Override
    public void close() {
        player.closeDialog();
        responded = true;
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
