package ai.resourcepack.dialogs.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Every button that calls back. A drawn button carries a {@code dynamic/custom}
 * action with the id {@link #ID} and a random token; the client sends the token
 * back with the dialog's inputs, and the token finds the click it was drawn for.
 * A token belongs to one player and one drawing, and expires, so a click can
 * only ever do what that player was shown on the screen they are looking at:
 * a new drawing retires every token of the one before ({@link #retire}).
 *
 * <p>Both servers raise an event for such a click, under different names:
 * Spigot's {@code org.bukkit.event.player.PlayerCustomClickEvent} (JSON
 * payload) and Paper's {@code io.papermc.paper.event.player.PlayerCustomClickEvent}
 * (a response view). Each is listened to by reflection, whichever exists.
 */
final class Clicks {

    /** The custom click id every Dialog Engine button sends. */
    static final String ID = "dialogengine:click";
    /** Where the token rides in the payload, beside the inputs. Loader keeps inputs off this key. */
    static final String TOKEN = "dialogengine_click";

    /** What came back with a click: the dialog's inputs, by key. */
    interface Payload {
        String text(String key);

        Boolean bool(String key);

        Float number(String key);
    }

    private record Bound(UUID player, long drawing, long expires, BiConsumer<Player, Payload> action) {}

    private final Map<String, Bound> bound = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** A token for one button of drawing {@code drawing}; the action is handed the player who clicked. */
    String bind(Player player, Duration lifetime, long drawing, BiConsumer<Player, Payload> action) {
        if (bound.size() > 2048) purge();
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        bound.put(token, new Bound(player.getUniqueId(), drawing, System.currentTimeMillis() + lifetime.toMillis(), action));
        return token;
    }

    /** Drops every token of the player's except drawing {@code keep}'s: a new screen replaced the old one. */
    void retire(UUID player, long keep) {
        bound.values().removeIf(b -> b.player.equals(player) && b.drawing != keep);
    }

    /** The action JSON a button carries. */
    static JsonObject action(String token) {
        JsonObject additions = new JsonObject();
        additions.addProperty(TOKEN, token);
        JsonObject action = new JsonObject();
        action.addProperty("type", "minecraft:dynamic/custom");
        action.addProperty("id", ID);
        action.add("additions", additions);
        return action;
    }

    void forget(UUID player) {
        bound.values().removeIf(b -> b.player.equals(player));
    }

    void clear() {
        bound.clear();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        bound.values().removeIf(b -> b.expires < now);
    }

    private void handle(Player player, String id, Payload payload) {
        if (player == null || !ID.equals(id)) return;
        String token = payload.text(TOKEN);
        if (token == null) return;
        Bound b = bound.get(token);
        if (b == null || !b.player.equals(player.getUniqueId())) return;
        if (b.expires < System.currentTimeMillis()) {
            bound.remove(token);
            return;
        }
        b.action.accept(player, payload);
    }

    // ------------------------------------------------------------------ listening

    /** Listens to whichever custom click event this server raises. Returns its name, or null for none. */
    String listen(Plugin plugin) {
        Listener holder = new Listener() {};
        Class<? extends Event> paper = eventClass("io.papermc.paper.event.player.PlayerCustomClickEvent");
        if (paper != null) {
            Bukkit.getPluginManager().registerEvent(paper, holder, EventPriority.NORMAL, (l, event) -> onPaper(event), plugin, true);
            return "Paper";
        }
        Class<? extends Event> spigot = eventClass("org.bukkit.event.player.PlayerCustomClickEvent");
        if (spigot != null) {
            Bukkit.getPluginManager().registerEvent(spigot, holder, EventPriority.NORMAL, (l, event) -> onSpigot(event), plugin, true);
            return "Spigot";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> eventClass(String name) {
        try {
            Class<?> c = Class.forName(name);
            return Event.class.isAssignableFrom(c) ? (Class<? extends Event>) c : null;
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    // Spigot: getPlayer(), getId() (a NamespacedKey), getData() (the payload as JSON).
    private void onSpigot(Event event) {
        try {
            Player player = (Player) call(event, "getPlayer");
            Object id = call(event, "getId");
            Object data = call(event, "getData");
            if (id == null || !(data instanceof JsonObject json)) return;
            handle(player, id.toString(), new JsonPayload(json));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Bukkit.getLogger().fine("[DialogEngine] Could not read a click: " + e);
        }
    }

    // Paper: getIdentifier() (an Adventure Key), getDialogResponseView(), and
    // the player through getCommonConnection().getPlayer() in the game phase.
    private void onPaper(Event event) {
        try {
            Object key = call(event, "getIdentifier");
            if (key == null) return;
            String id = (String) call(key, "asString");
            if (!ID.equals(id)) return;
            Object connection = call(event, "getCommonConnection");
            Method getPlayer = method(connection.getClass(), "getPlayer");
            if (getPlayer == null) return;
            Object player = getPlayer.invoke(connection);
            Object view = call(event, "getDialogResponseView");
            if (!(player instanceof Player p) || view == null) return;
            handle(p, id, new ViewPayload(view));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Bukkit.getLogger().fine("[DialogEngine] Could not read a click: " + e);
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = method(target.getClass(), name);
        if (m == null) throw new NoSuchMethodException(name);
        return m.invoke(target);
    }

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static Method method(Class<?> type, String name, Class<?>... args) {
        String key = type.getName() + "#" + name + args.length;
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        try {
            Method m = type.getMethod(name, args);
            m.setAccessible(true);
            METHODS.put(key, m);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /** Spigot's payload: the additions and inputs as one JSON object (a toggle is a 0/1 byte, a slider a number). */
    record JsonPayload(JsonObject json) implements Payload {
        @Override
        public String text(String key) {
            JsonElement e = json.get(key);
            return e instanceof JsonPrimitive p ? p.getAsString() : null;
        }

        @Override
        public Boolean bool(String key) {
            JsonElement e = json.get(key);
            if (!(e instanceof JsonPrimitive p)) return null;
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            String s = p.getAsString();
            if (s.equalsIgnoreCase("true") || s.equals("1b") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0b") || s.equals("0")) return false;
            return null;
        }

        @Override
        public Float number(String key) {
            JsonElement e = json.get(key);
            if (!(e instanceof JsonPrimitive p)) return null;
            try {
                return p.isNumber() ? p.getAsFloat() : Float.parseFloat(p.getAsString().replaceAll("[fFdD]$", ""));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /** Paper's DialogResponseView, by reflection: getText, getBoolean, getFloat. */
    private static final class ViewPayload implements Payload {
        private final Object view;

        ViewPayload(Object view) {
            this.view = view;
        }

        private Object get(String method, String key) {
            Method m = method(view.getClass(), method, String.class);
            if (m == null) return null;
            try {
                return m.invoke(view, key);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public String text(String key) {
            return get("getText", key) instanceof String s ? s : null;
        }

        @Override
        public Boolean bool(String key) {
            return get("getBoolean", key) instanceof Boolean b ? b : null;
        }

        @Override
        public Float number(String key) {
            return get("getFloat", key) instanceof Float f ? f : null;
        }
    }
}
