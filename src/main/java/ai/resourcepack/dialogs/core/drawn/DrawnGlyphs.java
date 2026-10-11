package ai.resourcepack.dialogs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Keeps several drawn dialogs' pictures apart in one pack.
 *
 * <p>Studio draws each dialog on its own, and each drawing puts its picture's
 * tiles in {@code minecraft:default} from U+E000. Two of them in one pack would
 * be two fonts claiming the same characters, and one dialog would be drawn with
 * the other's tiles. So every drawn dialog after the first has its default-font
 * characters moved up past the ones before it: in its {@code default.json} as
 * {@link DrawnPack} builds the pack, and in its pages as {@link DrawnDialogs}
 * loads them. Both ask this class, which walks the folders in the same order
 * each time, so they agree. The first dialog is never moved, so a server with
 * one drawn dialog is drawn exactly as Studio drew it.
 */
final class DrawnGlyphs {

    static final String DEFAULT_FONT = "assets/minecraft/font/default.json";
    private static final int PUA_FIRST = 0xE000;
    private static final int PUA_LAST = 0xF8FF;

    private DrawnGlyphs() {
    }

    /** For each dialog folder that has to move, by {@code namespace/name}: old character to new. */
    static Map<String, Map<Integer, Integer>> moves(File drawnRoot) {
        Map<String, Map<Integer, Integer>> out = new HashMap<>();
        int next = PUA_FIRST;
        for (File ns : sorted(drawnRoot)) {
            for (File dir : sorted(ns)) {
                TreeSet<Integer> used = used(dir.toPath().resolve("pack").resolve(DEFAULT_FONT));
                if (used.isEmpty()) continue;
                int shift = Math.max(0, next - used.first());
                if (used.last() + shift > PUA_LAST) continue; // Out of room: left where Studio put it.
                if (shift > 0) {
                    Map<Integer, Integer> move = new HashMap<>();
                    for (int cp : used) move.put(cp, cp + shift);
                    out.put(ns.getName() + "/" + dir.getName(), move);
                }
                next = used.last() + shift + 1;
            }
        }
        return out;
    }

    private static File[] sorted(File dir) {
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    /** The Private Use Area characters a font file defines. */
    private static TreeSet<Integer> used(Path font) {
        TreeSet<Integer> out = new TreeSet<>();
        if (!Files.isRegularFile(font)) return out;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(font, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement p : json.getAsJsonArray("providers")) {
                JsonObject provider = p.getAsJsonObject();
                if (provider.get("chars") instanceof JsonArray rows) {
                    for (JsonElement row : rows) row.getAsString().codePoints().filter(DrawnGlyphs::pua).forEach(out::add);
                }
                if (provider.get("advances") instanceof JsonObject advances) {
                    for (String key : advances.keySet()) key.codePoints().filter(DrawnGlyphs::pua).forEach(out::add);
                }
            }
        } catch (Exception e) {
            // A font that cannot be read moves nothing; the pack build says what is wrong with it.
        }
        return out;
    }

    private static boolean pua(int cp) {
        return cp >= PUA_FIRST && cp <= PUA_LAST;
    }

    private static String move(String s, Map<Integer, Integer> move) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> b.appendCodePoint(move.getOrDefault(cp, cp)));
        return b.toString();
    }

    /** A dialog's {@code default.json}, its characters moved. */
    static JsonObject moveFont(JsonObject font, Map<Integer, Integer> move) {
        for (JsonElement p : font.getAsJsonArray("providers")) {
            JsonObject provider = p.getAsJsonObject();
            if (provider.get("chars") instanceof JsonArray rows) {
                JsonArray moved = new JsonArray();
                for (JsonElement row : rows) moved.add(move(row.getAsString(), move));
                provider.add("chars", moved);
            }
            if (provider.get("advances") instanceof JsonObject advances) {
                JsonObject moved = new JsonObject();
                for (Map.Entry<String, JsonElement> e : advances.entrySet()) moved.add(move(e.getKey(), move), e.getValue());
                provider.add("advances", moved);
            }
        }
        return font;
    }

    /**
     * A page's JSON with the characters moved wherever they are drawn in the
     * default font: in a text component with no font of its own, or one that
     * names the default, and that does not inherit another from its parent.
     */
    static JsonElement movePage(JsonElement e, Map<Integer, Integer> move) {
        return walk(e, null, move);
    }

    private static JsonElement walk(JsonElement e, String inherited, Map<Integer, Integer> move) {
        if (e instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) a.set(i, walk(a.get(i), inherited, move));
            return a;
        }
        if (!(e instanceof JsonObject o)) return e;
        boolean component = o.has("text") || o.has("extra") || o.has("translate");
        String font = component
                ? (o.get("font") instanceof JsonPrimitive f && f.isString() ? f.getAsString() : inherited)
                : null;
        boolean isDefault = font == null || font.equals("minecraft:default") || font.equals("default");
        for (String key : o.keySet().toArray(new String[0])) {
            JsonElement v = o.get(key);
            if (component && key.equals("text") && v instanceof JsonPrimitive p && p.isString()) {
                if (isDefault) o.addProperty("text", move(p.getAsString(), move));
            } else if (component && key.equals("extra")) {
                o.add(key, walk(v, font, move));
            } else {
                // Anything else (a hover's text, a click, a body entry) starts its own style.
                o.add(key, walk(v, null, move));
            }
        }
        return o;
    }
}
