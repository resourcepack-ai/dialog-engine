package ai.resourcepack.dialogs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The resource pack drawn dialogs are drawn with: every drawn dialog's pack
 * files in one zip, served to players by a small built-in web server, or
 * exported for a server that ships its own pack.
 *
 * <p>Sent as an ADDITIONAL pack (1.20.3+), under an id of its own, so it sits
 * beside whatever pack the server already sends rather than replacing it. Only
 * built when there is a drawn dialog at all: a server that never uses
 * {@code /de edit} is never asked to download anything.
 */
final class DrawnPack {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("dialogengine:drawn".getBytes(StandardCharsets.UTF_8));

    private final Engine engine;
    private byte[] zip;
    private String sha1;
    private HttpServer server;
    private int servedPort = -1;

    DrawnPack(Engine engine) {
        this.engine = engine;
    }

    private org.bukkit.configuration.ConfigurationSection cfg() {
        var c = engine.plugin().getConfig().getConfigurationSection("pack");
        return c != null ? c : engine.plugin().getConfig().createSection("pack");
    }

    /** Builds the zip from every drawn dialog's {@code pack/} folder. Null when there is nothing to draw. */
    void build(File drawnRoot) {
        Map<String, byte[]> files = new TreeMap<>();
        JsonArray overlays = new JsonArray();
        int format = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        File[] namespaces = drawnRoot.listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File ns : namespaces) {
                File[] dirs = ns.listFiles(File::isDirectory);
                if (dirs == null) continue;
                for (File dir : dirs) {
                    Path pack = dir.toPath().resolve("pack");
                    if (!Files.isDirectory(pack)) continue;
                    try (var walk = Files.walk(pack)) {
                        for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                            String name = pack.relativize(p).toString().replace('\\', '/');
                            byte[] bytes = Files.readAllBytes(p);
                            if (name.equals("pack.mcmeta")) {
                                JsonObject meta = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                                JsonObject packObj = meta.getAsJsonObject("pack");
                                int f = packObj.get("pack_format").getAsInt();
                                format = Math.max(format, f);
                                min = Math.min(min, packObj.has("min_format") ? major(packObj.get("min_format")) : f);
                                max = Math.max(max, packObj.has("max_format") ? major(packObj.get("max_format")) : f);
                                if (meta.has("overlays")) {
                                    for (JsonElement e : meta.getAsJsonObject("overlays").getAsJsonArray("entries")) {
                                        if (!overlays.contains(e)) overlays.add(e);
                                        JsonObject o = e.getAsJsonObject();
                                        if (o.has("min_format")) min = Math.min(min, major(o.get("min_format")));
                                        if (o.has("max_format")) max = Math.max(max, major(o.get("max_format")));
                                    }
                                }
                                continue;
                            }
                            if (name.equals("pack.png")) continue;
                            files.put(name, bytes);
                        }
                    } catch (IOException | RuntimeException e) {
                        engine.plugin().getLogger().warning("Drawn dialog pack " + ns.getName() + "/" + dir.getName() + " could not be read: " + e.getMessage());
                    }
                }
            }
        }
        if (files.isEmpty()) {
            zip = null;
            sha1 = null;
            return;
        }
        JsonObject packObj = new JsonObject();
        packObj.addProperty("pack_format", format);
        if (min != Integer.MAX_VALUE) {
            JsonObject supported = new JsonObject();
            supported.addProperty("min_inclusive", Math.min(min, format));
            supported.addProperty("max_inclusive", Math.max(max, format));
            packObj.add("supported_formats", supported);
            packObj.addProperty("min_format", Math.min(min, format));
            packObj.addProperty("max_format", Math.max(max, format));
        }
        packObj.addProperty("description", "Dialog Engine: drawn dialogs");
        JsonObject meta = new JsonObject();
        meta.add("pack", packObj);
        if (!overlays.isEmpty()) {
            JsonObject o = new JsonObject();
            o.add("entries", overlays);
            meta.add("overlays", o);
        }
        files.put("pack.mcmeta", meta.toString().getBytes(StandardCharsets.UTF_8));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream out = new ZipOutputStream(bytes)) {
                for (var entry : files.entrySet()) {
                    ZipEntry z = new ZipEntry(entry.getKey());
                    z.setTime(0);
                    out.putNextEntry(z);
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
            zip = bytes.toByteArray();
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        } catch (Exception e) {
            zip = null;
            sha1 = null;
            engine.plugin().getLogger().warning("Could not build the drawn dialog pack: " + e.getMessage());
        }
        serve();
    }

    private static int major(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsInt() : e.getAsInt();
    }

    boolean present() {
        return zip != null;
    }

    /** Writes the zip to {@code drawn-pack.zip}, for a server that merges it into its own pack. */
    File export() throws IOException {
        File out = new File(engine.plugin().getDataFolder(), "drawn-pack.zip");
        Files.write(out.toPath(), zip);
        return out;
    }

    // ------------------------------------------------------------------ serving

    private boolean hosting() {
        return cfg().getBoolean("send", true) && cfg().getString("url", "").isBlank();
    }

    private synchronized void serve() {
        if (zip == null || !hosting()) return;
        int port = cfg().getInt("port", 8165);
        if (server != null && servedPort == port) return;
        stop();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                byte[] body = zip;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            servedPort = port;
            engine.plugin().getLogger().info("Serving the drawn dialog pack on port " + port + " as " + url());
        } catch (IOException e) {
            server = null;
            engine.plugin().getLogger().warning("Could not serve the drawn dialog pack on port " + port + ": " + e.getMessage()
                    + ". Set pack.port, or pack.url to a pack you host yourself.");
        }
    }

    /** Where players download it from. */
    String url() {
        String configured = cfg().getString("url", "");
        if (!configured.isBlank()) return configured;
        String host = cfg().getString("public-host", "");
        if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
        return "http://" + host + ":" + cfg().getInt("port", 8165) + "/drawn-" + sha1 + ".zip";
    }

    /** Sends it to a player, beside any pack the server already sends. */
    void send(Player player) {
        if (zip == null || !cfg().getBoolean("send", true)) return;
        try {
            // Added beside the server's own pack, never replacing it.
            String prompt = Platform.legacy(Text.MM.deserialize(cfg().getString("prompt", "<gray>This server's menus are drawn with a small resource pack.")));
            player.addResourcePack(PACK_ID, url(), HexFormat.of().parseHex(sha1), prompt, cfg().getBoolean("required", false));
        } catch (RuntimeException e) {
            engine.plugin().getLogger().warning("Could not send the drawn dialog pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    void sendAll() {
        for (Player p : Bukkit.getOnlinePlayers()) send(p);
    }

    synchronized void stop() {
        if (server != null) server.stop(0);
        server = null;
        servedPort = -1;
    }
}
