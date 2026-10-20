package ai.resourcepack.dialogs.core.click;

import ai.resourcepack.dialogs.api.ClickContext;
import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.Session;
import ai.resourcepack.dialogs.core.placeholder.Scope;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.yaml.Defs.DialogDef;
import ai.resourcepack.dialogs.core.yaml.Defs;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One button press while its actions run. */
public final class ClickCtx implements ClickContext {

    final Engine engine;
    final Player player;
    final DialogDef def;
    Session session;
    private final String buttonId;
    private final Map<String, String> inputs;
    private final String alias;
    private final Map<String, String> row;

    /** Something has answered the click: a dialog was shown or the screen was closed. */
    public boolean responded;
    boolean stopped;
    /**
     * What the screen the click came from was sent as, so what has to answer
     * it: the dialog's {@code after}, unless it went out as something else.
     */
    private Defs.After after;

    public ClickCtx(Engine engine, Player player, DialogDef def, Session session, String buttonId, Map<String, String> inputs,
             String alias, Map<String, String> row) {
        this.engine = engine;
        this.player = player;
        this.def = def;
        this.session = session;
        this.buttonId = buttonId;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.alias = alias;
        this.row = row == null ? Map.of() : row;
        this.after = def.after();
        // The click itself makes this the player's current dialog, values and all.
        engine.remember(player, session);
    }

    Scope scope() {
        Scope s = new Scope(engine, player, def.id(), session.values, session.untrusted, null, null);
        return alias == null || row.isEmpty() ? s : s.withRow(alias, row);
    }

    /** What the click's screen was sent as, when that is not its dialog's {@code after}. */
    public void answering(Defs.After sentAs) {
        if (sentAs != null) after = sentAs;
    }

    /** Whether the client is waiting on the engine to answer this click (wait or seamless). */
    boolean awaitsAnswer() {
        return !responded && after.awaitsAnswer();
    }

    /**
     * Called when the actions are done. A dialog waiting for its response must
     * get one, or the player is left looking at "waiting for the server", or
     * at a seamless dialog that never goes away.
     */
    public void finish() {
        if (!awaitsAnswer() || !player.isOnline()) return;
        responded = true;
        // Not straight away: an action may have asked a plugin to open the next
        // dialog a moment from now. Closing first would flash the world between
        // the two. See Engine.answer for how long.
        engine.answer(player, after);
    }

    /**
     * Answers the click by closing, now: before a command that may open a
     * screen of its own. The engine cannot see another plugin's dialog arrive,
     * so a close that came after it would take it away.
     */
    void closeNow() {
        if (!awaitsAnswer()) return;
        responded = true;
        engine.closeDialog(player);
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
        set(key, value, value != null && carriesUntrusted(value));
    }

    /**
     * Whether a value a plugin hands back carries words a player typed, so it
     * stays untrusted rather than being laundered into MiniMessage by
     * {@code ctx.set("target", ctx.get("typed"))}. A value is plain strings, so
     * this goes by what it holds: one that IS a typed value, or holds a typed
     * value that could do something (a tag, a line break) not as
     * {@link Engine#escape} left it. Holding a harmless one changes nothing
     * either way, and an escaped one is the plugin doing it right.
     */
    boolean carriesUntrusted(String value) {
        for (String key : session.untrusted) {
            String typed = session.values.get(key);
            if (typed == null || typed.isEmpty()) continue;
            if (value.equals(typed)) return true;
            if (!value.contains(typed)) continue;
            if (typed.indexOf('\n') >= 0 || typed.indexOf('\r') >= 0) return true;
            String escaped = Text.MM.escapeTags(typed);
            if (!escaped.equals(typed) && !value.contains(escaped)) return true;
        }
        return false;
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
        String id = engine.resolve(dialogId, def.namespace());
        Map<String, String> values = new LinkedHashMap<>(session.values);
        Set<String> untrusted = new HashSet<>(session.untrusted);
        values.remove("error");
        // A new dialog starts on its first page.
        values.remove("page");
        if (extra != null) {
            extra.forEach((k, v) -> {
                values.put(k, v);
                if (v != null && carriesUntrusted(v)) untrusted.add(k);
                else untrusted.remove(k);
            });
        }
        if (engine.openAsPlayer(player, id, values, untrusted, session)) responded = true;
    }

    /** {@link #open} for {@code [open]}, where a value may have come from a player's words. */
    void open(String dialogId, Map<String, String> extra, Set<String> untrustedExtra) {
        String id = engine.resolve(dialogId, def.namespace());
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
        engine.closeDialog(player);
        responded = true;
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
