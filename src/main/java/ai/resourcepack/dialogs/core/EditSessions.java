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

    /** Opens a session on {@code dialogId}, making the dialog first when there is none. */
    void open(CommandSender sender, String dialogId) {
        if (!dialogId.matches("[a-z0-9_.\\-]+:[a-z0-9_.\\-]+")) {
            say(sender, "Name a dialog as namespace:id, like <white>server:shop</white>.");
            return;
        }
        String namespace = dialogId.substring(0, dialogId.indexOf(':'));
        String name = dialogId.substring(dialogId.indexOf(':') + 1);
        File dialogs = new File(plugin.getDataFolder(), "dialogs");
        Found found = find(dialogs, namespace, name);
        boolean created = false;
        if (found == null) {
            if (engine.all().containsKey(dialogId)) {
                say(sender, "<white>" + dialogId + "</white> belongs to another plugin, so it is edited there.");
                return;
            }
            found = create(dialogs, namespace, name);
            if (found == null) {
                say(sender, "Could not make a file for <white>" + dialogId + "</white>.");
                return;
            }
            created = true;
        }

        EditWire.Open request = new EditWire.Open();
        request.client = "DialogEngine/" + plugin.getPluginMeta().getVersion();
        request.kind = "dialog";
        request.label = dialogId;
        request.dialog = new EditWire.Dialog();
        request.dialog.path = plugin.getDataFolder().toPath().relativize(found.file.toPath()).toString().replace('\\', '/');
        request.dialog.key = found.key;
        request.dialog.definition = toPlain(found.section);
        String body = GSON.toJson(request);
        UUID opener = sender instanceof Player p ? p.getUniqueId() : null;
        Found target = found;
        boolean made = created;
        say(sender, (made ? "Made <white>" + dialogId + "</white> and opening" : "Opening <white>" + dialogId + "</white>") + " in the editor...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EditWire.Opened opened = GSON.fromJson(request("POST", "/api/rpengine/session", body, null), EditWire.Opened.class);
                sessions.put(opened.id, new Live(opened.id, opened.pullToken, parse(opened.expiresAt), target.file, target.key,
                        dialogId, opener, opened.revision));
                ensurePolling();
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.MM.deserialize(
                        "<#3670f8>Dialog Engine</#3670f8> <dark_gray>»</dark_gray> <gray>Edit <white>" + dialogId + "</white>: "
                                + "<click:open_url:'" + opened.url + "'><hover:show_text:'Open the dialog editor'><#3670f8><u>open the editor</u></#3670f8></hover></click>"
                                + "<dark_gray>. The link works once and lasts three hours; press Send to server to write it back.</dark_gray>")));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> say(sender, "<red>Could not open the editor: " + Text.MM.escapeTags(String.valueOf(e.getMessage()))));
            }
        });
    }

    private record Found(File file, String key, ConfigurationSection section) {}

    /** The file and key a dialog of the engine's own folder lives under, or null. */
    private Found find(File dialogs, String namespace, String name) {
        List<File> files = new ArrayList<>();
        if (namespace.equals("server")) {
            File[] top = dialogs.listFiles((dir, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
            if (top != null) files.addAll(List.of(top));
        }
        collect(new File(dialogs, namespace), files);
        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                if (key.toLowerCase(Locale.ROOT).equals(name) && yaml.isConfigurationSection(key)) {
                    return new Found(file, key, yaml.getConfigurationSection(key));
                }
            }
        }
        return null;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".yml") || f.getName().endsWith(".yaml")) out.add(f);
        }
    }

    /** A new dialog in a file of its own: {@code dialogs/<namespace>/<name>.yml}. */
    private Found create(File dialogs, String namespace, String name) {
        File dir = namespace.equals("server") ? dialogs : new File(dialogs, namespace);
        File file = new File(dir, name + ".yml");
        if (file.exists()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection s = yaml.createSection(name);
        s.set("title", "<gold><bold>" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1));
        s.set("body", List.of("<gray>Made with /de edit. Change everything in the editor."));
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("label", "<green>Okay");
        button.put("actions", List.of("[message] <green>Clicked!", "[close]"));
        s.set("buttons", List.of(button));
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("label", "Close");
        s.set("exit", exit);
        try {
            dir.mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            return null;
        }
        return new Found(file, name, yaml.getConfigurationSection(name));
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

    /** Writes a pulled dialog back under its key, and reloads. On the main thread. */
    private void apply(Live live, EditWire.Pull pull) {
        Player opener = live.opener == null ? null : Bukkit.getPlayer(live.opener);
        CommandSender tell = opener != null ? opener : Bukkit.getConsoleSender();
        String expected = plugin.getDataFolder().toPath().relativize(live.file.toPath()).toString().replace('\\', '/');
        if (pull.dialog == null || pull.dialog.definition == null || !expected.equals(pull.dialog.path) || !live.key.equals(pull.dialog.key)) {
            say(tell, "<red>The editor sent back something for a different dialog; nothing was written.");
            return;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(live.file);
            // Comments hang off the keys they sit above, and replacing the
            // section drops every one of them: kept for each line still there.
            Map<String, List<String>> above = new LinkedHashMap<>();
            Map<String, List<String>> inline = new LinkedHashMap<>();
            List<String> paths = new ArrayList<>(List.of(live.key));
            ConfigurationSection old = yaml.getConfigurationSection(live.key);
            if (old != null) for (String sub : old.getKeys(true)) paths.add(live.key + "." + sub);
            for (String path : paths) {
                above.put(path, yaml.getComments(path));
                inline.put(path, yaml.getInlineComments(path));
            }
            yaml.set(live.key, null);
            yaml.createSection(live.key, wholeNumbers(pull.dialog.definition));
            for (String path : paths) {
                if (!yaml.contains(path)) continue;
                if (!above.get(path).isEmpty()) yaml.setComments(path, above.get(path));
                if (!inline.get(path).isEmpty()) yaml.setInlineComments(path, inline.get(path));
            }
            yaml.save(live.file);
        } catch (Exception e) {
            say(tell, "<red>Could not write " + Text.MM.escapeTags(expected) + ": " + Text.MM.escapeTags(String.valueOf(e.getMessage())));
            return;
        }
        sessions.computeIfPresent(live.id, (k, v) -> v.withApplied(pull.revision));
        engine.reload();
        List<String> problems = engine.problems().stream().filter(p -> p.contains(live.dialogId) || p.contains(live.file.getName())).toList();
        say(tell, "Saved <white>" + live.dialogId + "</white> from the editor"
                + (problems.isEmpty() ? ". <click:run_command:'/de open " + live.dialogId + "'><#3670f8><u>Open it</u></#3670f8></click>" : ", with problems:"));
        for (String p : problems) tell.sendMessage(Text.MM.deserialize("<yellow> - </yellow><gray>" + Text.MM.escapeTags(p)));
    }

    void shutdown() {
        if (poller != null) poller.cancel();
        sessions.clear();
    }

    // ------------------------------------------------------------------ JSON

    /** A section as plain maps and lists, for Gson. */
    private static Map<String, Object> toPlain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) out.put(key, plain(section.get(key)));
        return out;
    }

    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection s) return toPlain(s);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> l) return l.stream().map(EditSessions::plain).toList();
        return value;
    }

    /** Gson reads every number as a double; YAML wants {@code 2}, not {@code 2.0}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> wholeNumbers(Map<String, Object> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, whole(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object whole(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue() == d.intValue() ? (Object) d.intValue() : (Object) d.longValue();
        if (v instanceof Map<?, ?> m) return wholeNumbers((Map<String, Object>) m);
        if (v instanceof List<?> l) return l.stream().map(EditSessions::whole).toList();
        return v;
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
