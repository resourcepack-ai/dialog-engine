package ai.resourcepack.dialogs.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A drawn dialog's folder, {@code drawn/<namespace>/<name>/}:
 *
 * <ul>
 *   <li>{@code dialog.yml}: the dialog itself, Studio's document as YAML. The
 *   one file a person edits, by hand or with {@code /de edit}.</li>
 *   <li>{@code dialogs.json} and {@code pack/}: what Studio drew from it. Never
 *   edited; redrawn whenever {@code dialog.yml} no longer matches.</li>
 *   <li>{@code .built}: the hash of the {@code dialog.yml} they were drawn
 *   from, which is how a hand edit is noticed.</li>
 * </ul>
 *
 * A folder from 0.2.0's first builds has a {@code spec.json} instead, and is
 * moved over to {@code dialog.yml} the first time it is read.
 */
final class DrawnFiles {

    static final String SOURCE = "dialog.yml";
    private static final String BUILT = ".built";
    private static final String HEADER = """
            # A drawn dialog: Studio's dialog editor document, as YAML.
            # Edit it with /de edit, or by hand here and run /de reload: a changed
            # file is redrawn by Studio (resourcepack.ai), which needs the internet.
            # A clickable piece's command can be Dialog Engine actions, e.g.
            #   value: "[console] give {player} diamond; [message] <green>Here you go"
            """;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() {}.getType();

    private DrawnFiles() {
    }

    /** The dialog as Studio's document, or null when the folder has none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path dir) throws IOException {
        Path yml = dir.resolve(SOURCE);
        if (Files.isRegularFile(yml)) {
            Object loaded = yaml().load(Files.readString(yml, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> map)) throw new IOException(SOURCE + " is not a YAML map");
            return (Map<String, Object>) map;
        }
        Path json = dir.resolve("spec.json");
        if (Files.isRegularFile(json)) {
            Map<String, Object> spec = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), MAP);
            // Moved over: the drawing in this folder is already this document's.
            write(dir, spec);
            Files.delete(json);
            return spec;
        }
        return null;
    }

    /** Writes the document, and records the drawing beside it as drawn from it. */
    static void write(Path dir, Map<String, Object> spec) throws IOException {
        Files.createDirectories(dir);
        String text = HEADER + yaml().dump(tidy(spec));
        Files.writeString(dir.resolve(SOURCE), text, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(BUILT), hash(text), StandardCharsets.UTF_8);
    }

    /** Whether {@code dialog.yml} has changed since the drawing was made. */
    static boolean stale(Path dir) {
        try {
            Path yml = dir.resolve(SOURCE);
            if (!Files.isRegularFile(yml)) return false;
            if (!Files.isRegularFile(dir.resolve("dialogs.json"))) return true;
            Path built = dir.resolve(BUILT);
            return !Files.isRegularFile(built)
                    || !Files.readString(built).trim().equals(hash(Files.readString(yml, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes what Studio drew: every pack file, the pages, and the document.
     * Every file is checked before anything is written, because it came over
     * the network: one path leaving the dialog's own {@code pack/} folder and
     * nothing is.
     */
    static void writeDrawing(Path dir, EditWire.Pull pull, boolean writeSource) throws IOException {
        Path pack = dir.resolve("pack");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (EditWire.File f : pull.files == null ? List.<EditWire.File>of() : pull.files) {
            if (f.path == null || !f.path.startsWith("pack/") || !f.path.matches("[A-Za-z0-9._/\\-]+") || f.path.contains("..")) {
                throw new IOException("refused a file outside the dialog's pack: " + f.path);
            }
            Path target = dir.resolve(f.path).normalize();
            if (!target.startsWith(pack)) throw new IOException("refused " + f.path);
            files.put(target, "base64".equals(f.encoding)
                    ? Base64.getDecoder().decode(f.content)
                    : f.content.getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(dir);
        if (Files.isDirectory(pack)) {
            try (var walk = Files.walk(pack)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        for (var e : files.entrySet()) {
            Files.createDirectories(e.getKey().getParent());
            Files.write(e.getKey(), e.getValue());
        }
        Files.writeString(dir.resolve("dialogs.json"), GSON.toJson(pull.dialog.built));
        if (writeSource) {
            write(dir, pull.dialog.spec);
        } else {
            // Drawn from the file as it stands: record that, and leave the file (comments and all) alone.
            Files.writeString(dir.resolve(BUILT), hash(Files.readString(dir.resolve(SOURCE), StandardCharsets.UTF_8)));
        }
    }

    // ------------------------------------------------------------------ YAML

    private static Yaml yaml() {
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dump.setIndent(2);
        dump.setIndicatorIndent(0);
        dump.setWidth(120);
        dump.setSplitLines(false);
        LoaderOptions load = new LoaderOptions();
        load.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(load), new Representer(dump), dump, load);
    }

    /** Gson reads every number as a double; a whole one is written as a whole one. */
    private static Object tidy(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) return d.longValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), tidy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(tidy(v));
            return out;
        }
        return value;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
