package ai.resourcepack.dialogs.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /de edit <id>}: one of the engine's own dialogs, opened in Studio's
 * dialog editor in the browser, and written back here when Send is pressed.
 *
 * <h2>How</h2>
 * The same edit sessions RP Engine's {@code /rp edit} opens, with the kind
 * {@code dialog}: the dialog goes up as JSON (the YAML under its key, read by
 * Bukkit), the link comes back for chat, and every few seconds the session's
 * revision is asked for. When it moves, the dialog is pulled and written back
 * under its key in the file it came from — the rest of the file, its comments
 * included, left as it was — and the engine reloads.
 *
 * <h2>What it may touch</h2>
 * Only a {@code .yml} inside this plugin's {@code dialogs/} folder, and only
 * the key it sent. What comes back names its path and key, and both are checked
 * against what went out, because the answer comes over the network.
 */
final class EditSessions {

    private static final long POLL_TICKS = 60L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final java.lang.reflect.Type MAP = new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType();

    private record Live(String id, String pullToken, Instant expires, File file, String key, String dialogId,
                        UUID opener, long applied) {
        Live withApplied(long revision) {
            return new Live(id, pullToken, expires, file, key, dialogId, opener, revision);
        }
    }

    private final Engine engine;
    private final JavaPlugin plugin;
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();
    private BukkitTask poller;

    EditSessions(Engine engine) {
        this.engine = engine;
        this.plugin = engine.plugin();
    }

    /**
     * {@code edit.url}, which must be https: what comes back from it is written
     * into the plugin's folder and decides what a drawn dialog's clicks run, so
     * nobody between here and there may change it. Plain http only reaches a
     * Studio on this machine.
     */
    private String base() throws IOException {
        String url = plugin.getConfig().getString("edit.url", "https://studio.resourcepack.ai");
        url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("edit.url is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") || (scheme.equals("http") && local(uri.getHost()))) return url;
        throw new IOException("edit.url must start with https:// (http:// only for localhost)");
    }

    private static boolean local(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private void say(CommandSender to, String mini) {
        Platform.send(to, Text.MM.deserialize("<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>" + mini));
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens a session on {@code dialogId}: a drawn dialog, edited in Studio's
     * own dialog editor. A plain YAML dialog is edited in its file, and a name
     * held by one is not taken; any other name is a drawn dialog, new or not.
     */
    void open(CommandSender sender, String dialogId) {
        if (!dialogId.matches("[a-z0-9_\\-]+:[a-z0-9_\\-]+")) {
            say(sender, "Name a dialog as namespace:name, like <white>server:shop</white> (a-z, 0-9, _ and -).");
            return;
        }
        if (engine.all().containsKey(dialogId)) {
            say(sender, "<white>" + dialogId + "</white> is a YAML dialog, so it is edited in its file. "
                    + "Give a new name to draw one in the editor: <white>/de edit " + dialogId + "_drawn</white>");
            return;
        }
        String namespace = dialogId.substring(0, dialogId.indexOf(':'));
        String name = dialogId.substring(dialogId.indexOf(':') + 1);
        String path = "drawn/" + namespace + "/" + name;
        File dir = new File(plugin.getDataFolder(), path);
        Map<String, Object> spec;
        try {
            spec = DrawnFiles.read(dir.toPath());
        } catch (Exception e) {
            say(sender, "<red>" + path + "/" + DrawnFiles.SOURCE + " could not be read: " + Text.MM.escapeTags(String.valueOf(e.getMessage())));
            return;
        }
        EditWire.Open request = new EditWire.Open();
        request.client = "DialogEngine/" + plugin.getDescription().getVersion();
        request.kind = "dialog";
        request.label = dialogId;
        request.minecraftVersion = Platform.minecraftVersion();
        request.dialog = new EditWire.Dialog();
        request.dialog.path = path;
        request.dialog.key = name;
        request.dialog.spec = spec;
        String body = GSON.toJson(request);
        UUID opener = sender instanceof Player p ? p.getUniqueId() : null;
        boolean fresh = spec == null;
        say(sender, (fresh ? "Starting <white>" + dialogId + "</white>" : "Opening <white>" + dialogId + "</white>") + " in the dialog editor...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EditWire.Opened opened = GSON.fromJson(request("POST", "/api/rpengine/session", body, null), EditWire.Opened.class);
                String link = opened.url == null ? "" : opened.url;
                if (!link.startsWith("https://") && !link.startsWith("http://")) throw new IOException("Studio answered with no link");
                sessions.put(opened.id, new Live(opened.id, opened.pullToken, parse(opened.expiresAt), dir, name, dialogId, opener, opened.revision));
                ensurePolling();
                // The link goes in as a click event, never into the MiniMessage: it came over the network.
                Component editor = Text.MM.deserialize("<hover:show_text:'Open the dialog editor'><#3670f8><u>open the editor</u></#3670f8></hover>")
                        .clickEvent(ClickEvent.openUrl(link));
                Bukkit.getScheduler().runTask(plugin, () -> Platform.send(sender, Text.MM.deserialize(
                        "<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>Edit <white>" + dialogId + "</white>: "
                                + "<editor><dark_gray>. The link works once and lasts three hours; press Send to server to bring it back here.</dark_gray>",
                        Placeholder.component("editor", editor))));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> say(sender, "<red>Could not open the editor: " + Text.MM.escapeTags(String.valueOf(e.getMessage()))));
            }
        });
    }

    // ------------------------------------------------------------------ polling

    private synchronized void ensurePolling() {
        if (poller != null) return;
        poller = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, POLL_TICKS, POLL_TICKS);
    }

    private void tick() {
        Instant now = Instant.now();
        for (Live live : List.copyOf(sessions.values())) {
            if (live.expires.isBefore(now)) {
                sessions.remove(live.id);
                continue;
            }
            try {
                EditWire.Status status = GSON.fromJson(request("GET", "/api/rpengine/session/" + live.id, null, live.pullToken), EditWire.Status.class);
                if (status.revision <= live.applied) continue;
                EditWire.Pull pull = GSON.fromJson(request("GET", "/api/rpengine/session/" + live.id + "/files", null, live.pullToken), EditWire.Pull.class);
                Bukkit.getScheduler().runTask(plugin, () -> apply(live, pull));
            } catch (Gone gone) {
                sessions.remove(live.id);
            } catch (Exception e) {
                // A network blip: asked again next tick.
            }
        }
        synchronized (this) {
            if (sessions.isEmpty() && poller != null) {
                poller.cancel();
                poller = null;
            }
        }
    }

    /** Writes a pulled drawn dialog into its folder, and reloads. On the main thread. */
    private void apply(Live live, EditWire.Pull pull) {
        Player opener = live.opener == null ? null : Bukkit.getPlayer(live.opener);
        CommandSender tell = opener != null ? opener : Bukkit.getConsoleSender();
        String expected = plugin.getDataFolder().toPath().relativize(live.file.toPath()).toString().replace('\\', '/');
        if (pull.dialog == null || pull.dialog.spec == null || pull.dialog.built == null || pull.dialog.built.isEmpty()
                || !expected.equals(pull.dialog.path) || !live.key.equals(pull.dialog.key)) {
            say(tell, "<red>The editor sent back something for a different dialog; nothing was written.");
            return;
        }
        // What the edit makes clicks run that the dialog did not before, said out
        // loud: the link was handed out, and whoever holds it can write these.
        List<String> added;
        try {
            added = DrawnFiles.invented(GSON.toJsonTree(pull.dialog.built), DrawnFiles.commands(DrawnFiles.read(live.file.toPath())));
        } catch (Exception e) {
            added = List.of();
        }
        try {
            DrawnFiles.writeDrawing(live.file.toPath(), pull, true);
        } catch (Exception e) {
            say(tell, "<red>Could not write " + Text.MM.escapeTags(expected) + ": " + Text.MM.escapeTags(String.valueOf(e.getMessage())));
            return;
        }
        sessions.computeIfPresent(live.id, (k, v) -> v.withApplied(pull.revision));
        engine.reload();
        engine.drawnPack.sendAll();
        List<String> problems = engine.drawn.problems.stream().filter(p -> p.contains(live.dialogId.replace(':', '/'))).toList();
        say(tell, "Saved <white>" + live.dialogId + "</white> from the editor"
                + (problems.isEmpty() ? ". <click:run_command:'/de open " + live.dialogId + "'><#3670f8><u>Open it</u></#3670f8></click>" : ", with problems:"));
        for (String p : problems) Platform.send(tell, Text.MM.deserialize("<yellow> - </yellow><gray>" + Text.MM.escapeTags(p)));
        if (!added.isEmpty()) {
            say(tell, "Its clicks now also run:");
            for (String c : added) {
                Platform.send(tell, Text.MM.deserialize("<yellow> - </yellow><white>" + Text.MM.escapeTags(c)));
                plugin.getLogger().info(live.dialogId + " was edited to run: " + c);
            }
        }
    }

    // ------------------------------------------------------------------ redrawing

    /**
     * Redraws a drawn dialog whose {@code dialog.yml} was edited by hand: the
     * document goes to Studio, which draws it as its editor would and answers
     * with what a pull would have been. Off the main thread; {@code done} runs
     * on it, with null on success or why it failed.
     */
    void redraw(String dialogId, File dir, java.util.function.Consumer<String> done) {
        Map<String, Object> spec;
        try {
            spec = DrawnFiles.read(dir.toPath());
        } catch (Exception e) {
            done.accept(DrawnFiles.SOURCE + " could not be read: " + e.getMessage());
            return;
        }
        if (spec == null) {
            done.accept("there is no " + DrawnFiles.SOURCE);
            return;
        }
        String name = dialogId.substring(dialogId.indexOf(':') + 1);
        String path = plugin.getDataFolder().toPath().relativize(dir.toPath()).toString().replace('\\', '/');
        EditWire.Open request = new EditWire.Open();
        request.client = "DialogEngine/" + plugin.getDescription().getVersion();
        request.kind = "dialog";
        request.label = dialogId;
        request.minecraftVersion = Platform.minecraftVersion();
        request.dialog = new EditWire.Dialog();
        request.dialog.path = path;
        request.dialog.key = name;
        request.dialog.spec = spec;
        String body = GSON.toJson(request);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String error = null;
            try {
                EditWire.Pull pull = GSON.fromJson(request("POST", "/api/rpengine/build", body, null), EditWire.Pull.class);
                if (pull.dialog == null || pull.dialog.built == null || pull.dialog.built.isEmpty()) throw new IOException("Studio drew nothing");
                DrawnFiles.writeDrawing(dir.toPath(), pull, false);
            } catch (Gone gone) {
                error = "this Studio cannot redraw dialogs yet";
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            String result = error;
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(result));
        });
    }

    void shutdown() {
        if (poller != null) poller.cancel();
        sessions.clear();
    }

    // ------------------------------------------------------------------ HTTP

    /** A session that is no longer there. */
    private static final class Gone extends Exception {}

    private String request(String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "DialogEngine/" + plugin.getDescription().getVersion());
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code == 404 || code == 410) throw new Gone();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        if (code >= 400) {
            String error = Optional.ofNullable(GSON.fromJson(text, Map.class)).map(m -> String.valueOf(m.get("error"))).orElse("HTTP " + code);
            throw new IOException(error);
        }
        return text;
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 4 * 1024 * 1024) throw new IOException("answer too big");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static Instant parse(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return Instant.now().plusSeconds(3 * 3600);
        }
    }
}
