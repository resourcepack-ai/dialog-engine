package ai.resourcepack.dialogs.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    private String base() {
        String url = plugin.getConfig().getString("edit.url", "https://studio.resourcepack.ai");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void say(CommandSender to, String mini) {
        to.sendMessage(Text.MM.deserialize("<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>" + mini));
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
        Map<String, Object> spec = null;
        File specFile = new File(dir, "spec.json");
        if (specFile.isFile()) {
            try {
                spec = GSON.fromJson(java.nio.file.Files.readString(specFile.toPath()), MAP);
            } catch (Exception e) {
                say(sender, "<red>" + path + "/spec.json could not be read: " + Text.MM.escapeTags(String.valueOf(e.getMessage())));
                return;
            }
        }

        EditWire.Open request = new EditWire.Open();
        request.client = "DialogEngine/" + plugin.getPluginMeta().getVersion();
        request.kind = "dialog";
        request.label = dialogId;
        request.minecraftVersion = Bukkit.getMinecraftVersion();
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
                sessions.put(opened.id, new Live(opened.id, opened.pullToken, parse(opened.expiresAt), dir, name, dialogId, opener, opened.revision));
                ensurePolling();
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.MM.deserialize(
                        "<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>Edit <white>" + dialogId + "</white>: "
                                + "<click:open_url:'" + opened.url + "'><hover:show_text:'Open the dialog editor'><#3670f8><u>open the editor</u></#3670f8></hover></click>"
                                + "<dark_gray>. The link works once and lasts three hours; press Send to server to bring it back here.</dark_gray>")));
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
        java.nio.file.Path dir = live.file.toPath();
        java.nio.file.Path pack = dir.resolve("pack");
        try {
            // Every file checked before anything is written: a path that leaves
            // the dialog's own pack folder means nothing is.
            Map<java.nio.file.Path, byte[]> files = new LinkedHashMap<>();
            for (EditWire.File f : pull.files == null ? List.<EditWire.File>of() : pull.files) {
                if (f.path == null || !f.path.startsWith("pack/") || !f.path.matches("[A-Za-z0-9._/\\-]+") || f.path.contains("..")) {
                    throw new IOException("refused a file outside the dialog's pack: " + f.path);
                }
                java.nio.file.Path target = dir.resolve(f.path).normalize();
                if (!target.startsWith(pack)) throw new IOException("refused " + f.path);
                files.put(target, "base64".equals(f.encoding)
                        ? java.util.Base64.getDecoder().decode(f.content)
                        : f.content.getBytes(StandardCharsets.UTF_8));
            }
            java.nio.file.Files.createDirectories(dir);
            if (java.nio.file.Files.isDirectory(pack)) {
                try (var walk = java.nio.file.Files.walk(pack)) {
                    for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
                }
            }
            for (var e : files.entrySet()) {
                java.nio.file.Files.createDirectories(e.getKey().getParent());
                java.nio.file.Files.write(e.getKey(), e.getValue());
            }
            Gson pretty = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            java.nio.file.Files.writeString(dir.resolve("spec.json"), pretty.toJson(pull.dialog.spec));
            java.nio.file.Files.writeString(dir.resolve("dialogs.json"), GSON.toJson(pull.dialog.built));
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
        for (String p : problems) tell.sendMessage(Text.MM.deserialize("<yellow> - </yellow><gray>" + Text.MM.escapeTags(p)));
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
        c.setRequestProperty("User-Agent", "DialogEngine/" + plugin.getPluginMeta().getVersion());
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
