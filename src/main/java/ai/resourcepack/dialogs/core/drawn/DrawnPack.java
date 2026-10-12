package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogs.core.Engine;
import ai.resourcepack.dialogs.core.placeholder.Text;
import ai.resourcepack.dialogs.core.platform.Platform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
public final class DrawnPack {

    /** The namespace the sample drawn dialogs are written into. */
    static final String SAMPLES = "samples";

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("dialogengine:drawn".getBytes(StandardCharsets.UTF_8));

    private final Engine engine;
    private byte[] zip;
    private String sha1;
    private Served server;
    private int servedPort = -1;
    private String servedBind;
    /** Whether a drawn dialog other than the samples and plugins' ({@link DrawnInstall}) is here, which is what sends the pack on join. */
    private boolean ownDialogs;
    /** Players sent the pack since they joined, whatever they made of it so far. */
    private final java.util.Set<UUID> offered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DrawnPack(Engine engine) {
        this.engine = engine;
    }

    private org.bukkit.configuration.ConfigurationSection cfg() {
        var c = engine.plugin().getConfig().getConfigurationSection("pack");
        return c != null ? c : engine.plugin().getConfig().createSection("pack");
    }

    /** Builds the zip from every drawn dialog's {@code pack/} folder. Null when there is nothing to draw. */
    public void build(File drawnRoot) {
        Map<String, byte[]> files = new TreeMap<>();
        JsonArray overlays = new JsonArray();
        int format = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        boolean own = false;
        Map<String, Map<Integer, Integer>> moves = DrawnGlyphs.moves(drawnRoot);
        File[] namespaces = drawnRoot.listFiles(File::isDirectory);
        if (namespaces != null) {
            for (File ns : namespaces) {
                File[] dirs = ns.listFiles(File::isDirectory);
                if (dirs == null) continue;
                for (File dir : dirs) {
                    Path pack = dir.toPath().resolve("pack");
                    if (!Files.isDirectory(pack)) continue;
                    if (!ns.getName().equalsIgnoreCase(SAMPLES) && !DrawnInstall.shipped(ns.toPath())) own = true;
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
                            Map<Integer, Integer> move = moves.get(ns.getName() + "/" + dir.getName());
                            if (move != null && name.equals(DrawnGlyphs.DEFAULT_FONT)) {
                                JsonObject font = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                                bytes = DrawnGlyphs.moveFont(font, move).toString().getBytes(StandardCharsets.UTF_8);
                            }
                            files.merge(name, bytes, (had, mine) -> combine(name, had, mine));
                        }
                    } catch (IOException | RuntimeException e) {
                        engine.plugin().getLogger().warning("Drawn dialog pack " + ns.getName() + "/" + dir.getName() + " could not be read: " + e.getMessage());
                    }
                }
            }
        }
        ownDialogs = own;
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
        // The samples and plugins' alone are not a reason to open a port: that waits for the first one opened.
        if (ownDialogs || server != null) serve();
    }

    /**
     * Two dialogs' copies of one file. Fonts and language files are made of
     * entries, and each dialog's are wanted: a font's providers are put
     * together (the first to define a character keeps it, as in the game), a
     * language file's keys likewise. Anything else is the same file in every
     * drawing (a shader, the button sprites), and the last read is kept.
     */
    private static byte[] combine(String name, byte[] had, byte[] mine) {
        if (java.util.Arrays.equals(had, mine) || !name.endsWith(".json")) return mine;
        try {
            JsonObject a = JsonParser.parseString(new String(had, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject b = JsonParser.parseString(new String(mine, StandardCharsets.UTF_8)).getAsJsonObject();
            if (name.matches("assets/[^/]+/font/[^/]+\\.json") &&a.has("providers") && b.has("providers")) {
                JsonArray providers = a.getAsJsonArray("providers");
                for (JsonElement p : b.getAsJsonArray("providers")) if (!providers.contains(p)) providers.add(p);
                return a.toString().getBytes(StandardCharsets.UTF_8);
            }
            if (name.matches("assets/[^/]+/lang/[^/]+\\.json")) {
                for (var e : b.entrySet()) if (!a.has(e.getKey())) a.add(e.getKey(), e.getValue());
                return a.toString().getBytes(StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            // Not JSON after all: kept as the last one read, as before.
        }
        return mine;
    }

    private static int major(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsInt() : e.getAsInt();
    }

    public boolean present() {
        return zip != null;
    }

    /** Writes the zip to {@code drawn-pack.zip}, for a server that merges it into its own pack. */
    public File export() throws IOException {
        File out = new File(engine.plugin().getDataFolder(), "drawn-pack.zip");
        Files.write(out.toPath(), zip);
        return out;
    }

    // ------------------------------------------------------------------ serving

    private boolean hosting() {
        return cfg().getBoolean("send", true) && cfg().getString("url", "").isBlank();
    }

    /** Downloads served at once; more wait in a short queue, and past that are turned away. */
    private static final int THREADS = 4;
    private static final int QUEUE = 32;
    /** How long a client gets to send its request, and to take the whole pack. */
    private static final int REQUEST_TIMEOUT_MS = 10_000;
    private static final long DOWNLOAD_TIMEOUT_MS = 120_000;

    /**
     * Serves the zip over plain HTTP: one GET (or HEAD) per connection, then
     * closed. Small and strict on purpose, because anybody on the internet can
     * reach it: a fixed number of downloads at once, a queue that turns the
     * rest away, ten seconds to send a request and two minutes to take the
     * pack, so a slow or idle connection holds one worker for a bounded time
     * and never the server.
     */
    private synchronized void serve() {
        if (zip == null || !hosting()) return;
        int port = cfg().getInt("port", 8165);
        String bind = cfg().getString("bind", "").trim();
        if (server != null && servedPort == port && bind.equals(servedBind)) return;
        stop();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(bind.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bind, port), 50);
            ThreadPoolExecutor workers = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(QUEUE), daemon("DialogEngine pack"), new ThreadPoolExecutor.AbortPolicy());
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(daemon("DialogEngine pack watchdog"));
            Served served = new Served(socket, workers, watchdog);
            server = served;
            Thread acceptor = daemon("DialogEngine pack server").newThread(() -> accept(served));
            acceptor.start();
            servedPort = port;
            servedBind = bind;
            engine.plugin().getLogger().info("Serving the drawn dialog pack on port " + port + " as " + url());
        } catch (IOException | RuntimeException e) {
            server = null;
            engine.plugin().getLogger().warning("Could not serve the drawn dialog pack on port " + port + ": " + e.getMessage()
                    + ". Set pack.port, or pack.url to a pack you host yourself.");
        }
    }

    private record Served(ServerSocket socket, ThreadPoolExecutor workers, ScheduledExecutorService watchdog) {
        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            workers.shutdownNow();
            watchdog.shutdownNow();
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private void accept(Served s) {
        while (!s.socket().isClosed()) {
            Socket client;
            try {
                client = s.socket().accept();
            } catch (IOException e) {
                if (s.socket().isClosed()) return;
                continue;
            }
            try {
                s.workers().execute(() -> answer(s, client));
            } catch (RejectedExecutionException full) {
                closeQuietly(client);
            }
        }
    }

    private void answer(Served s, Socket client) {
        // Whatever happens, the connection is closed when its time is up: a
        // write blocked on a client that stopped reading ends with it.
        ScheduledFuture<?> deadline = s.watchdog().schedule(() -> closeQuietly(client), DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (client) {
            client.setSoTimeout(REQUEST_TIMEOUT_MS);
            String request = readHead(new java.io.BufferedInputStream(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            String[] line = request == null ? new String[0] : request.split(" ");
            boolean head = line.length >= 2 && line[0].equals("HEAD");
            byte[] body = zip;
            if (line.length < 2 || !(head || line[0].equals("GET"))) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else if (body == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (!head) out.write(body);
            }
            out.flush();
        } catch (IOException ignored) {
            // A client that went away, or ran out of time.
        } finally {
            deadline.cancel(false);
        }
    }

    /** The request line, after reading the headers up to the blank line; null when there is none. At most 8 KB. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        String first = null;
        int total = 0;
        int c;
        while ((c = in.read()) >= 0) {
            if (++total > 8192) return null;
            if (c == '\n') {
                String l = line.toString().strip();
                if (l.isEmpty()) return first;
                if (first == null) first = l;
                line.setLength(0);
            } else {
                line.append((char) c);
            }
        }
        return first;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** Where players download it from. */
    public String url() {
        String configured = cfg().getString("url", "");
        if (!configured.isBlank()) return configured;
        String host = cfg().getString("public-host", "");
        if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
        return "http://" + host + ":" + cfg().getInt("port", 8165) + "/drawn-" + sha1 + ".zip";
    }

    /**
     * Whether a player joining is sent it. Not when the only drawn dialogs are
     * the samples and those plugins ship: a server that has never made one of
     * its own does not ask every player to download a pack, and their pack is
     * sent when one is opened ({@link #needs}).
     */
    public boolean onJoin() {
        return ownDialogs;
    }

    /** Whether showing a player a drawn dialog has to wait for the pack to reach them first. */
    public boolean needs(Player player) {
        return zip != null && cfg().getBoolean("send", true) && !offered.contains(player.getUniqueId());
    }

    /** The player declined it or could not load it: the next drawn dialog they open offers it again. */
    public void refused(Player player) {
        offered.remove(player.getUniqueId());
    }

    public void forget(Player player) {
        offered.remove(player.getUniqueId());
    }

    public static boolean isPack(UUID id) {
        return PACK_ID.equals(id);
    }

    /**
     * Sends it to a player, beside any pack the server already sends. False
     * when it was not sent: there is none, sending is off, or this server was
     * to serve it and could not (the console has said why).
     */
    public boolean send(Player player) {
        if (zip == null || !cfg().getBoolean("send", true)) return false;
        serve();
        if (hosting() && server == null) return false;
        offered.add(player.getUniqueId());
        try {
            // Added beside the server's own pack, never replacing it.
            String prompt = Platform.legacy(Text.MM.deserialize(cfg().getString("prompt", "<gray>This server's menus are drawn with a small resource pack.")));
            player.addResourcePack(PACK_ID, url(), HexFormat.of().parseHex(sha1), prompt, cfg().getBoolean("required", false));
        } catch (RuntimeException e) {
            engine.plugin().getLogger().warning("Could not send the drawn dialog pack to " + player.getName() + ": " + e.getMessage());
            offered.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** After a rebuild: everybody, or (samples only) whoever has it already. */
    public void sendAll() {
        for (Player p : Bukkit.getOnlinePlayers()) if (ownDialogs || offered.contains(p.getUniqueId())) send(p);
    }

    public synchronized void stop() {
        if (server != null) server.close();
        server = null;
        servedPort = -1;
        servedBind = null;
    }
}
