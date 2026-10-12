package ai.resourcepack.dialogs.core.drawn;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Drawn dialogs a plugin ships in its jar ({@code installDrawn}): the jar's
 * {@code drawn/<namespace>/} copied into {@code drawn/<namespace>/}.
 *
 * <p>{@code .shipped} in the namespace folder records which plugin and
 * version wrote it and the hash of each dialog's {@code dialog.yml} as
 * installed. The same version installs nothing. A new one replaces each
 * dialog whose {@code dialog.yml} still has the hash it was installed with,
 * whole folder at once (its drawing goes with it), and keeps every dialog
 * the server owner has changed, which a reload then redraws from their file
 * as any hand edit is. A dialog the plugin stopped shipping goes, unless it
 * was changed. The file's presence is also what marks the namespace as a
 * plugin's: its pack is sent on demand, like the samples' ({@link DrawnPack}).
 */
public final class DrawnInstall {

    public static final String STAMP = ".shipped";

    private DrawnInstall() {
    }

    /** Whether a namespace folder was written by a plugin. */
    public static boolean shipped(Path namespace) {
        return Files.isRegularFile(namespace.resolve(STAMP));
    }

    /** @return whether anything was written */
    public static boolean install(Plugin owner, String namespace, Path into) throws IOException {
        String version = owner.getName() + " " + owner.getDescription().getVersion();
        Path stamp = into.resolve(STAMP);
        Map<String, String> before = new LinkedHashMap<>();
        String had = null;
        if (Files.isRegularFile(stamp)) {
            List<String> lines = Files.readAllLines(stamp, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) had = lines.get(0).trim();
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                int tab = line.indexOf('\t');
                if (tab > 0) before.put(line.substring(tab + 1), line.substring(0, tab));
            }
        }
        if (version.equals(had)) return false;

        // Every file the jar lists, by dialog.
        String prefix = "drawn/" + namespace + "/";
        Map<String, List<String>> dialogs = new TreeMap<>();
        try (InputStream list = owner.getResource(prefix + "files.txt")) {
            if (list == null) throw new IOException(owner.getName() + "'s jar has no " + prefix + "files.txt");
            for (String line : new String(list.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim().replace('\\', '/');
                int slash = name.indexOf('/');
                if (name.isEmpty() || name.contains("..") || name.startsWith("/") || name.contains(":") || slash <= 0) continue;
                dialogs.computeIfAbsent(name.substring(0, slash), k -> new ArrayList<>()).add(name);
            }
        }
        Path root = into.toAbsolutePath().normalize();
        Map<String, String> after = new TreeMap<>();
        for (Map.Entry<String, List<String>> d : dialogs.entrySet()) {
            Path dir = root.resolve(d.getKey()).normalize();
            if (!dir.startsWith(root) || dir.equals(root)) continue;
            Path yml = dir.resolve(DrawnFiles.SOURCE);
            String installed = before.get(d.getKey());
            if (Files.isRegularFile(yml) && (installed == null || !installed.equals(hash(Files.readAllBytes(yml))))) {
                // The owner's now: changed since it was installed, or made by them under this name.
                owner.getLogger().info("Kept drawn/" + namespace + "/" + d.getKey() + "/ as it is: it was changed on this server."
                        + " Delete the folder to take this version's.");
                if (installed != null) after.put(d.getKey(), installed);
                continue;
            }
            delete(dir);
            for (String file : d.getValue()) {
                Path target = root.resolve(file).normalize();
                if (!target.startsWith(dir)) continue;
                try (InputStream in = owner.getResource(prefix + file)) {
                    if (in == null) throw new IOException(owner.getName() + "'s jar lists " + prefix + file + " but has no such file");
                    Files.createDirectories(target.getParent());
                    Path part = target.resolveSibling(target.getFileName() + ".part");
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.isRegularFile(yml)) after.put(d.getKey(), hash(Files.readAllBytes(yml)));
        }
        // Dialogs an earlier version shipped and this one does not.
        for (Map.Entry<String, String> old : before.entrySet()) {
            if (dialogs.containsKey(old.getKey())) continue;
            Path dir = root.resolve(old.getKey()).normalize();
            if (!dir.startsWith(root) || dir.equals(root)) continue;
            Path yml = dir.resolve(DrawnFiles.SOURCE);
            if (Files.isRegularFile(yml) && !old.getValue().equals(hash(Files.readAllBytes(yml)))) continue;
            delete(dir);
        }
        StringBuilder out = new StringBuilder(version).append('\n');
        after.forEach((name, h) -> out.append(h).append('\t').append(name).append('\n'));
        Files.createDirectories(root);
        Files.writeString(stamp, out, StandardCharsets.UTF_8);
        return true;
    }

    /** Line endings do not count: a checkout may have turned them into CRLF. */
    static String hash(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
