package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogruntime.DialogItemIcons;
import ai.resourcepack.dialogruntime.DialogItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The pictures of the items other plugins' packs add, for a drawn dialog's
 * live item slots.
 *
 * <h2>What it fixes</h2>
 *
 * A slot showing the player's inventory draws each stack as a character of an
 * icon font Studio ships: every vanilla item, and the drawing's own pack items.
 * A stack wearing a model of its own from anywhere else (ItemsAdder, Nexo,
 * Oraxen, a pack the owner made) used to be the "no picture" icon, because
 * Studio never saw that pack. The server has it, though: so while the drawn
 * pack is built, this reads those packs, draws every flat item model in them
 * (one layer or several, a layered tool included) at 16 by 16, puts them in a
 * sheet of its own and adds that sheet to every icon font, from
 * {@link #FIRST}. Filling a slot then works out which model the client will
 * draw for the stack and writes that character.
 *
 * <h2>What the client would draw</h2>
 *
 * The item definition the stack names ({@code item_model}, else its
 * material's), evaluated as the game does for an item in a GUI: custom model
 * data's floats, flags and strings pick the branch, {@code display_context}
 * is {@code gui}, anything else that depends on the world (being used,
 * damage, the time) takes its resting branch. A model that is a pile of
 * cubes is left as the "no picture" icon: only {@code item/generated} and its
 * kin are flat pictures. A model of the game's own that no pack replaces is
 * the vanilla icon, so an item whose custom model data a pack's definition
 * passes over is drawn as what it looks like. An item no pack here defines
 * stays the "no picture" icon: its picture is in a pack this server does not
 * hold (one sent from {@code server.properties}, say), or nowhere.
 *
 * <h2>Why every picture moves nothing</h2>
 *
 * Each cell carries an all-but-transparent pixel in its top-right corner, as
 * Studio's sheet does, so every character is 16 wide and advances exactly as
 * the icons the step back after it was made for.
 */
public final class ItemPictures implements DialogItems.Pictures {

    /** The first character a picture gets in an icon font; Studio's characters end at {@code 0xE9FF}. */
    static final int FIRST = 0xEA00;
    /** The last: the end of the private use area. */
    static final int LAST = 0xF8FF;
    static final int CELL = 16;
    static final int COLUMNS = 32;
    static final String SHEET = "assets/dialogengine/textures/font/item_pictures.png";
    static final String SHEET_REF = "dialogengine:font/item_pictures.png";
    /** Studio's icon fonts: one per line offset and scale. */
    static final Pattern ICON_FONT = Pattern.compile("assets/minecraft/font/dialog_items_(?:\\d+x_)?-?\\d+\\.json");
    /** The oldest pack format a dialog is drawn for (1.21.6): overlays only for older clients are not read. */
    static final int DIALOG_FORMAT = 63;

    /** How deep under a plugin's folder a pack is looked for, and how many entries the walk may cost. */
    private static final int MAX_DEPTH = 5;
    private static final int ENTRY_BUDGET = 20_000;
    private static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;

    /** What the last build found: a picture's key to the character that draws it. */
    private volatile Map<String, String> glyphs = Map.of();
    private volatile Index index = Index.EMPTY;
    private final Map<Facts, String> answers = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ asked while filling

    /** What decides an item's model, read off a stack. */
    record Facts(String material, String model, List<Float> floats, List<Boolean> flags, List<String> strings) {
    }

    @Override
    public String of(ItemStack stack) {
        if (index == Index.EMPTY) return null;
        Facts facts = facts(stack);
        if (facts == null) return null;
        String answer = answers.computeIfAbsent(facts, f -> {
            String found = glyph(f);
            return found == null ? "" : found;
        });
        return answer.isEmpty() ? null : answer;
    }

    static Facts facts(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        String material = stack.getType().getKey().toString();
        if (meta == null) return new Facts(material, null, List.of(), List.of(), List.of());
        String model = null;
        try {
            if (meta.hasItemModel()) {
                NamespacedKey key = meta.getItemModel();
                if (key != null) model = key.toString();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An API without item models: the material's definition.
        }
        List<Float> floats = List.of();
        List<Boolean> flags = List.of();
        List<String> strings = List.of();
        try {
            if (meta.hasCustomModelData()) {
                CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                floats = List.copyOf(cmd.getFloats());
                flags = List.copyOf(cmd.getFlags());
                strings = List.copyOf(cmd.getStrings());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No component API: nothing to pick a branch by.
        }
        return new Facts(material, model, floats, flags, strings);
    }

    /** The character for these facts, or null. */
    String glyph(Facts f) {
        // No pack read: nothing is known about what the client draws, so the "no picture" icon.
        if (index == Index.EMPTY) return null;
        Leaf leaf = index.leaf(f);
        if (leaf == null) return null;
        String own = glyphs.get(leaf.key());
        if (own != null) return own;
        // The game's own model, untouched by any pack: the vanilla icon.
        String model = id(leaf.model());
        if (model.startsWith("minecraft:item/") && !index.hasModel(model) && leaf.tints().isEmpty()) {
            String item = model.substring("minecraft:item/".length());
            if (DialogItemIcons.has(item)) return String.valueOf(DialogItemIcons.glyph(item));
        }
        return null;
    }

    // ------------------------------------------------------------------ the build

    /** What a build adds to the drawn pack, and how many pictures it drew of how many found. */
    record Built(Map<String, byte[]> files, int drawn, int found, List<String> packs) {
    }

    /**
     * Reads {@code packs}, draws their items, and adds the sheet and a provider
     * to every icon font in {@code files} (which it changes). Does nothing when
     * {@code files} has no icon font: no drawing shows a player's items.
     */
    Built build(Map<String, byte[]> files, List<Path> packs, java.util.logging.Logger log) {
        answers.clear();
        List<String> fonts = new ArrayList<>();
        for (String name : files.keySet()) if (ICON_FONT.matcher(name).matches()) fonts.add(name);
        if (fonts.isEmpty() || packs.isEmpty()) {
            glyphs = Map.of();
            index = Index.EMPTY;
            return new Built(Map.of(), 0, 0, List.of());
        }
        List<Pack> read = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            for (Path p : packs) {
                try {
                    Pack pack = Pack.open(p);
                    if (pack != null) {
                        read.add(pack);
                        names.add(p.toString());
                    }
                } catch (IOException | RuntimeException e) {
                    log.warning("Could not read the resource pack " + p + " for item pictures: " + e.getMessage());
                }
            }
            Index idx = new Index(read);
            Map<String, BufferedImage> pictures = new TreeMap<>();
            Set<String> seen = new TreeSet<>();
            for (Leaf leaf : idx.everyLeaf()) {
                if (!seen.add(leaf.key())) continue;
                BufferedImage img = idx.draw(leaf);
                if (img != null) pictures.put(leaf.key(), img);
            }
            Map<String, String> glyphsNow = new HashMap<>();
            List<BufferedImage> cells = new ArrayList<>();
            for (Map.Entry<String, BufferedImage> e : pictures.entrySet()) {
                if (FIRST + cells.size() > LAST) break;
                glyphsNow.put(e.getKey(), String.valueOf((char) (FIRST + cells.size())));
                cells.add(e.getValue());
            }
            Map<String, byte[]> added = new LinkedHashMap<>();
            if (!cells.isEmpty()) {
                added.put(SHEET, sheet(cells));
                List<String> rows = rows(cells.size());
                for (String font : fonts) {
                    byte[] withPictures = addProvider(files.get(font), rows);
                    if (withPictures != null) added.put(font, withPictures);
                }
                files.putAll(added);
            }
            glyphs = Map.copyOf(glyphsNow);
            index = idx.withoutPacks();
            return new Built(added, cells.size(), pictures.size(), names);
        } finally {
            for (Pack p : read) p.close();
        }
    }

    /** The sheet: {@link #COLUMNS} cells a row, each with its corner pixel. */
    static byte[] sheet(List<BufferedImage> cells) {
        int rows = (cells.size() + COLUMNS - 1) / COLUMNS;
        BufferedImage out = new BufferedImage(COLUMNS * CELL, rows * CELL, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < cells.size(); i++) {
            int ox = (i % COLUMNS) * CELL;
            int oy = (i / COLUMNS) * CELL;
            BufferedImage c = cells.get(i);
            for (int y = 0; y < CELL; y++) for (int x = 0; x < CELL; x++) out.setRGB(ox + x, oy + y, c.getRGB(x, y));
            // Every glyph 16 wide: an alpha-1 pixel the text shader discards.
            int corner = out.getRGB(ox + CELL - 1, oy);
            if ((corner >>> 24) == 0) out.setRGB(ox + CELL - 1, oy, 0x01000000);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The provider's rows of characters, the last padded with nothing, as a bitmap provider needs. */
    static List<String> rows(int count) {
        List<String> rows = new ArrayList<>();
        for (int r = 0; r * COLUMNS < count; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < COLUMNS; c++) {
                int i = r * COLUMNS + c;
                row.append(i < count ? (char) (FIRST + i) : '\u0000');
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /**
     * An icon font with the pictures added, at the height and ascent of the
     * font's own item sheet; null when it has none to copy.
     */
    static byte[] addProvider(byte[] font, List<String> rows) {
        try {
            JsonObject root = JsonParser.parseString(new String(font, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray providers = root.getAsJsonArray("providers");
            JsonObject sheet = null;
            for (JsonElement e : providers) {
                if (e.isJsonObject() && e.getAsJsonObject().get("file") instanceof JsonElement f && f.isJsonPrimitive()
                        && f.getAsString().endsWith("font/dialog_items.png")) {
                    sheet = e.getAsJsonObject();
                }
            }
            if (sheet == null) return null;
            for (int i = providers.size() - 1; i >= 0; i--) {
                JsonElement e = providers.get(i);
                if (e.isJsonObject() && e.getAsJsonObject().get("file") instanceof JsonElement f && f.isJsonPrimitive()
                        && SHEET_REF.equals(f.getAsString())) {
                    providers.remove(i);
                }
            }
            JsonObject mine = new JsonObject();
            mine.addProperty("type", "bitmap");
            mine.addProperty("file", SHEET_REF);
            mine.add("height", sheet.get("height") == null ? new com.google.gson.JsonPrimitive(CELL) : sheet.get("height"));
            mine.add("ascent", sheet.get("ascent"));
            JsonArray chars = new JsonArray();
            for (String row : rows) chars.add(row);
            mine.add("chars", chars);
            providers.add(mine);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ finding the packs

    /**
     * Every resource pack on this server worth reading for items: whatever is
     * in {@code own/item-packs/}, then each other plugin's folder, as deep as
     * {@link #MAX_DEPTH}, a zip or a folder with a {@code pack.mcmeta} that
     * has {@code assets} beside it.
     */
    static List<Path> locate(Path ownFolder, Collection<Path> otherFolders) {
        List<Path> out = new ArrayList<>();
        Path extra = ownFolder.resolve("item-packs");
        if (Files.isDirectory(extra)) {
            try (var list = Files.list(extra)) {
                list.sorted().forEach(p -> {
                    if (isPack(p)) out.add(p);
                });
            } catch (IOException ignored) {
                // Nothing there to read.
            }
        }
        for (Path folder : otherFolders) {
            if (!Files.isDirectory(folder)) continue;
            int[] budget = {ENTRY_BUDGET};
            try {
                Files.walkFileTree(folder, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (--budget[0] < 0) return FileVisitResult.TERMINATE;
                        if (!dir.equals(folder) && Files.isRegularFile(dir.resolve("pack.mcmeta")) && Files.isDirectory(dir.resolve("assets"))) {
                            out.add(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (--budget[0] < 0) return FileVisitResult.TERMINATE;
                        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip") && attrs.size() < MAX_ZIP_BYTES && isPack(file)) {
                            out.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // A folder that cannot be walked has no pack for us.
            }
        }
        return out;
    }

    /** What the packs are, as one string: their paths, sizes and times, so a rebuild is only done for a change. */
    static String fingerprint(List<Path> packs) {
        StringBuilder out = new StringBuilder();
        for (Path p : packs) {
            out.append(p).append('|');
            try {
                if (Files.isDirectory(p)) {
                    out.append(Files.getLastModifiedTime(p.resolve("pack.mcmeta")).toMillis()).append('|');
                    // A folder's items change without its metadata: its item definitions say when.
                    try (var walk = Files.walk(p.resolve("assets"), 3)) {
                        walk.forEach(f -> {
                            try {
                                out.append(Files.getLastModifiedTime(f).toMillis()).append(',');
                            } catch (IOException ignored) {
                                // Gone since the walk found it.
                            }
                        });
                    }
                } else {
                    out.append(Files.size(p)).append('|').append(Files.getLastModifiedTime(p).toMillis());
                }
            } catch (IOException ignored) {
                out.append('?');
            }
            out.append(';');
        }
        return out.toString();
    }

    private static boolean isPack(Path p) {
        if (Files.isDirectory(p)) return Files.isRegularFile(p.resolve("pack.mcmeta"));
        if (!Files.isRegularFile(p) || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) return false;
        try (ZipFile zip = new ZipFile(p.toFile())) {
            return zip.getEntry("pack.mcmeta") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ one pack

    /**
     * One pack's item definitions and models, read whole (they are small), and
     * its textures, read when asked. A file in an overlay a 1.21.6 or newer
     * client reads stands in for the pack's own; a later overlay over an earlier.
     */
    static final class Pack implements AutoCloseable {
        final Map<String, String> json = new HashMap<>();
        /** Texture path to where its bytes are: the entry's name in the zip, or the file. */
        final Map<String, String> textures = new HashMap<>();
        private final ZipFile zip;
        private final Path folder;

        private Pack(ZipFile zip, Path folder) {
            this.zip = zip;
            this.folder = folder;
        }

        static Pack open(Path p) throws IOException {
            if (Files.isDirectory(p)) {
                Pack pack = new Pack(null, p);
                Map<String, Path> files = new TreeMap<>();
                try (var walk = Files.walk(p, 16)) {
                    for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        files.put(p.relativize(f).toString().replace('\\', '/'), f);
                    }
                }
                String meta = files.containsKey("pack.mcmeta") ? Files.readString(files.get("pack.mcmeta")) : null;
                List<String> overlays = overlays(meta);
                Map<String, Integer> rank = new HashMap<>();
                for (Map.Entry<String, Path> e : files.entrySet()) {
                    pack.take(e.getKey(), overlays, rank, () -> Files.readString(e.getValue()), e.getValue().toString());
                }
                return pack;
            }
            ZipFile zip = new ZipFile(p.toFile());
            try {
                Pack pack = new Pack(zip, null);
                ZipEntry metaEntry = zip.getEntry("pack.mcmeta");
                String meta = metaEntry == null ? null : new String(zip.getInputStream(metaEntry).readAllBytes(), StandardCharsets.UTF_8);
                List<String> overlays = overlays(meta);
                Map<String, Integer> rank = new HashMap<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().replace('\\', '/');
                    pack.take(name, overlays, rank, () -> {
                        if (entry.getSize() > 1024 * 1024) return null;
                        try (var in = zip.getInputStream(entry)) {
                            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }, entry.getName());
                }
                return pack;
            } catch (IOException | RuntimeException e) {
                zip.close();
                throw e;
            }
        }

        private interface Text {
            String read() throws IOException;
        }

        private void take(String name, List<String> overlays, Map<String, Integer> rank, Text text, String where) throws IOException {
            int r = 0;
            String path = name;
            for (int i = 0; i < overlays.size(); i++) {
                String dir = overlays.get(i) + "/";
                if (name.startsWith(dir)) {
                    r = i + 1;
                    path = name.substring(dir.length());
                    break;
                }
            }
            if (!path.startsWith("assets/")) return;
            if (rank.getOrDefault(path, -1) >= r) return;
            boolean definition = path.matches("assets/[^/]+/(?:items|models)/.+\\.json");
            boolean texture = path.matches("assets/[^/]+/textures/.+\\.png");
            if (!definition && !texture) return;
            rank.put(path, r);
            if (texture) {
                textures.put(path, where);
            } else {
                String s = text.read();
                if (s != null) json.put(path, s);
            }
        }

        byte[] texture(String path) {
            String where = textures.get(path);
            if (where == null) return null;
            try {
                if (zip != null) {
                    ZipEntry e = zip.getEntry(where);
                    if (e == null || e.getSize() > 8L * 1024 * 1024) return null;
                    try (var in = zip.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }
                Path f = Path.of(where);
                return Files.size(f) > 8L * 1024 * 1024 ? null : Files.readAllBytes(f);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public void close() {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Closing is all that was left.
                }
            }
        }

        /** The overlays a client of {@link #DIALOG_FORMAT} or newer reads, in the order they are declared. */
        static List<String> overlays(String meta) {
            List<String> out = new ArrayList<>();
            if (meta == null) return out;
            try {
                JsonObject root = JsonParser.parseString(meta).getAsJsonObject();
                if (!(root.get("overlays") instanceof JsonObject o) || !(o.get("entries") instanceof JsonArray entries)) return out;
                for (JsonElement e : entries) {
                    if (!(e instanceof JsonObject entry) || !(entry.get("directory") instanceof JsonElement d) || !d.isJsonPrimitive()) continue;
                    if (maxFormat(entry) >= DIALOG_FORMAT) out.add(d.getAsString());
                }
            } catch (RuntimeException ignored) {
                // Metadata the game could not read either: no overlays.
            }
            return out;
        }

        private static int maxFormat(JsonObject entry) {
            try {
                if (entry.get("max_format") instanceof JsonElement m) return m.isJsonArray() ? m.getAsJsonArray().get(0).getAsInt() : m.getAsInt();
                JsonElement f = entry.get("formats");
                if (f == null) return Integer.MAX_VALUE;
                if (f.isJsonPrimitive()) return f.getAsInt();
                if (f.isJsonArray()) {
                    JsonArray a = f.getAsJsonArray();
                    return a.get(a.size() - 1).getAsInt();
                }
                if (f.isJsonObject() && f.getAsJsonObject().get("max_inclusive") instanceof JsonElement m) return m.getAsInt();
            } catch (RuntimeException ignored) {
                // Unreadable: taken to apply, as nothing says it does not.
            }
            return Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ every pack together

    /** A model to draw and the tints its definition puts on each layer: what one picture is. */
    record Leaf(String model, List<Integer> tints) {
        String key() {
            return id(model) + (tints.isEmpty() ? "" : "|" + tints);
        }
    }

    /**
     * Every pack's definitions and models together, the first pack read
     * having a path; its textures while the packs are open.
     */
    static final class Index {
        static final Index EMPTY = new Index(List.of());

        private final Map<String, JsonObject> json = new HashMap<>();
        private final List<Pack> packs;

        Index(List<Pack> packs) {
            this.packs = packs;
            for (Pack p : packs) {
                for (Map.Entry<String, String> e : p.json.entrySet()) {
                    if (json.containsKey(e.getKey())) continue;
                    try {
                        if (JsonParser.parseString(e.getValue()) instanceof JsonObject o) json.put(e.getKey(), o);
                    } catch (RuntimeException ignored) {
                        // A file the client cannot read draws nothing either.
                    }
                }
            }
        }

        private Index(Map<String, JsonObject> json) {
            this.packs = List.of();
            this.json.putAll(json);
        }

        /** The same, for asking after the packs are closed: no textures. */
        Index withoutPacks() {
            return new Index(json);
        }

        boolean hasModel(String model) {
            return json.containsKey(path(model, "models"));
        }

        /** The model a client draws for these facts in a GUI, or null. */
        Leaf leaf(Facts f) {
            String definition = id(f.model() != null ? f.model() : f.material());
            JsonObject def = json.get(path(definition, "items"));
            // No pack here defines it: whatever draws it is a pack this server does not hold (or
            // none, and its model data changes nothing), so nothing can be said. The question mark.
            if (def == null) return null;
            return evaluate(def.get("model"), f, 0);
        }

        private Leaf evaluate(JsonElement e, Facts f, int depth) {
            if (!(e instanceof JsonObject node) || depth > 32) return null;
            String type = id(str(node, "type"));
            switch (type) {
                case "minecraft:model": {
                    String model = str(node, "model");
                    return model == null ? null : new Leaf(model, tints(node.get("tints")));
                }
                case "minecraft:composite": {
                    // The first part with a picture: one icon cannot be two models.
                    if (node.get("models") instanceof JsonArray parts) {
                        for (JsonElement part : parts) {
                            Leaf l = evaluate(part, f, depth + 1);
                            if (l != null) return l;
                        }
                    }
                    return null;
                }
                case "minecraft:condition": {
                    boolean value = false;
                    if ("minecraft:custom_model_data".equals(id(str(node, "property")))) {
                        int i = integer(node, "index", 0);
                        value = i < f.flags().size() && Boolean.TRUE.equals(f.flags().get(i));
                    }
                    return evaluate(node.get(value ? "on_true" : "on_false"), f, depth + 1);
                }
                case "minecraft:select": {
                    String property = id(str(node, "property"));
                    String value = null;
                    if ("minecraft:custom_model_data".equals(property)) {
                        int i = integer(node, "index", 0);
                        value = i < f.strings().size() ? f.strings().get(i) : null;
                    } else if ("minecraft:display_context".equals(property)) {
                        value = "gui";
                    } else if ("minecraft:charge_type".equals(property)) {
                        value = "none";
                    }
                    if (value != null && node.get("cases") instanceof JsonArray cases) {
                        for (JsonElement c : cases) {
                            if (c instanceof JsonObject kase && matches(kase.get("when"), value)) {
                                return evaluate(kase.get("model"), f, depth + 1);
                            }
                        }
                    }
                    return evaluate(node.get("fallback"), f, depth + 1);
                }
                case "minecraft:range_dispatch": {
                    float value = 0;
                    if ("minecraft:custom_model_data".equals(id(str(node, "property")))) {
                        int i = integer(node, "index", 0);
                        value = i < f.floats().size() && f.floats().get(i) != null ? f.floats().get(i) : 0;
                    }
                    float scale = node.get("scale") instanceof JsonElement s && s.isJsonPrimitive() ? s.getAsFloat() : 1;
                    value *= scale;
                    JsonElement best = null;
                    float bestAt = Float.NEGATIVE_INFINITY;
                    if (node.get("entries") instanceof JsonArray entries) {
                        for (JsonElement en : entries) {
                            if (!(en instanceof JsonObject entry) || !(entry.get("threshold") instanceof JsonElement t) || !t.isJsonPrimitive()) continue;
                            float at = t.getAsFloat();
                            if (at <= value && at >= bestAt) {
                                best = entry.get("model");
                                bestAt = at;
                            }
                        }
                    }
                    return evaluate(best != null ? best : node.get("fallback"), f, depth + 1);
                }
                default:
                    // special (a chest, a shield), empty, a bundle's selected item: no flat picture.
                    return null;
            }
        }

        private static boolean matches(JsonElement when, String value) {
            if (when == null) return false;
            if (when.isJsonArray()) {
                for (JsonElement w : when.getAsJsonArray()) if (w.isJsonPrimitive() && value.equals(w.getAsString())) return true;
                return false;
            }
            if (!when.isJsonPrimitive()) return false;
            String w = when.getAsString();
            return value.equals(w) || value.equals(id(w));
        }

        /**
         * Each layer's tint as its definition gives it, from what is fixed:
         * a constant, or the default of one read off the stack (a dye, a
         * potion, custom model data's colours). White for anything else.
         */
        private static List<Integer> tints(JsonElement e) {
            if (!(e instanceof JsonArray list) || list.isEmpty()) return List.of();
            List<Integer> out = new ArrayList<>();
            boolean any = false;
            for (JsonElement t : list) {
                int rgb = 0xFFFFFF;
                if (t instanceof JsonObject tint) {
                    JsonElement v = "minecraft:constant".equals(id(str(tint, "type"))) ? tint.get("value") : tint.get("default");
                    Integer c = color(v);
                    if (c != null) rgb = c;
                }
                any |= rgb != 0xFFFFFF;
                out.add(rgb);
            }
            return any ? List.copyOf(out) : List.of();
        }

        private static Integer color(JsonElement v) {
            if (v == null) return null;
            try {
                if (v.isJsonPrimitive()) return v.getAsInt() & 0xFFFFFF;
                if (v.isJsonArray() && v.getAsJsonArray().size() >= 3) {
                    JsonArray a = v.getAsJsonArray();
                    int r = Math.round(a.get(0).getAsFloat() * 255);
                    int g = Math.round(a.get(1).getAsFloat() * 255);
                    int b = Math.round(a.get(2).getAsFloat() * 255);
                    return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
                }
            } catch (RuntimeException ignored) {
                // Not a colour.
            }
            return null;
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }

        /**
         * Every model any pack's definitions can show in a GUI, every branch
         * that custom model data could pick: what is drawn ahead of time.
         */
        List<Leaf> everyLeaf() {
            List<Leaf> out = new ArrayList<>();
            for (Map.Entry<String, JsonObject> e : json.entrySet()) {
                if (e.getKey().matches("assets/[^/]+/items/.+\\.json")) collect(e.getValue().get("model"), out, 0);
            }
            return out;
        }

        private void collect(JsonElement e, List<Leaf> out, int depth) {
            if (!(e instanceof JsonObject node) || depth > 32) return;
            String type = id(str(node, "type"));
            switch (type) {
                case "minecraft:model" -> {
                    String model = str(node, "model");
                    if (model != null) out.add(new Leaf(model, tints(node.get("tints"))));
                }
                case "minecraft:composite" -> {
                    if (node.get("models") instanceof JsonArray parts) for (JsonElement p : parts) collect(p, out, depth + 1);
                }
                case "minecraft:condition" -> {
                    collect(node.get("on_true"), out, depth + 1);
                    collect(node.get("on_false"), out, depth + 1);
                }
                case "minecraft:select" -> {
                    String property = id(str(node, "property"));
                    boolean gui = "minecraft:display_context".equals(property);
                    boolean guiCase = false;
                    if (node.get("cases") instanceof JsonArray cases) {
                        for (JsonElement c : cases) {
                            if (!(c instanceof JsonObject kase)) continue;
                            if (gui && !matches(kase.get("when"), "gui")) continue;
                            guiCase |= gui;
                            collect(kase.get("model"), out, depth + 1);
                        }
                    }
                    if (!guiCase) collect(node.get("fallback"), out, depth + 1);
                }
                case "minecraft:range_dispatch" -> {
                    if (node.get("entries") instanceof JsonArray entries) {
                        for (JsonElement en : entries) if (en instanceof JsonObject entry) collect(entry.get("model"), out, depth + 1);
                    }
                    collect(node.get("fallback"), out, depth + 1);
                }
                default -> {
                    // Nothing flat to draw.
                }
            }
        }

        /** The model's picture at 16 by 16, or null when it is not a flat item or its textures are not here. */
        BufferedImage draw(Leaf leaf) {
            Map<String, String> textures = new HashMap<>();
            String model = id(leaf.model());
            boolean flat = false;
            for (int depth = 0; depth < 16 && model != null; depth++) {
                if (isGenerated(model) && !json.containsKey(path(model, "models"))) {
                    flat = true;
                    break;
                }
                JsonObject m = json.get(path(model, "models"));
                if (m == null) {
                    // A model of the game's own that no pack has: an item's is a picture.
                    flat = model.startsWith("minecraft:item/");
                    break;
                }
                if (m.has("elements")) return null;
                if (m.get("textures") instanceof JsonObject t) {
                    for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                        if (e.getValue().isJsonPrimitive()) textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
                    }
                }
                String parent = str(m, "parent");
                if (parent == null) {
                    flat = true;
                    break;
                }
                model = id(parent);
            }
            if (!flat) return null;
            List<int[]> layers = new ArrayList<>();
            List<int[]> sizes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String ref = resolve(textures, "layer" + i);
                if (ref == null) break;
                BufferedImage img = texture(ref);
                if (img == null) return null;
                int w = img.getWidth();
                int h = img.getHeight();
                // An animation's strip of frames: the first.
                if (h > w && h % w == 0) h = w;
                int[] px = new int[w * h];
                img.getRGB(0, 0, w, h, px, 0, w);
                if (i < leaf.tints().size()) tint(px, leaf.tints().get(i));
                layers.add(px);
                sizes.add(new int[] {w, h});
            }
            if (layers.isEmpty()) return null;
            int w = 0;
            int h = 0;
            for (int[] s : sizes) {
                w = Math.max(w, s[0]);
                h = Math.max(h, s[1]);
            }
            int[] composed = new int[w * h];
            for (int l = 0; l < layers.size(); l++) {
                int lw = sizes.get(l)[0];
                int lh = sizes.get(l)[1];
                int[] px = layers.get(l);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int src = px[(y * lh / h) * lw + (x * lw / w)];
                        composed[y * w + x] = over(composed[y * w + x], src);
                    }
                }
            }
            return fit(composed, w, h);
        }

        private BufferedImage texture(String ref) {
            String p = path(id(ref), "textures").replaceAll("\\.json$", ".png");
            for (Pack pack : packs) {
                byte[] bytes = pack.texture(p);
                if (bytes == null) continue;
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img != null) return img;
                } catch (IOException | RuntimeException ignored) {
                    // Not a picture after all.
                }
            }
            return null;
        }

        private static String resolve(Map<String, String> textures, String key) {
            String v = textures.get(key);
            for (int i = 0; i < 8 && v != null && v.startsWith("#"); i++) v = textures.get(v.substring(1));
            return v == null || v.startsWith("#") ? null : v;
        }

        private static boolean isGenerated(String model) {
            return model.equals("minecraft:builtin/generated") || model.equals("minecraft:item/generated")
                    || model.startsWith("minecraft:item/handheld");
        }
    }

    /** Studio's rule for fitting a picture to a cell ({@code fitItemPicture}): an average over each cell's share, opaque or not there at all. */
    static BufferedImage fit(int[] px, int w, int h) {
        BufferedImage out = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                int x0 = x * w / CELL;
                int x1 = Math.max(x0 + 1, (x + 1) * w / CELL);
                int y0 = y * h / CELL;
                int y1 = Math.max(y0 + 1, (y + 1) * h / CELL);
                long r = 0;
                long g = 0;
                long b = 0;
                long a = 0;
                int count = 0;
                for (int sy = y0; sy < y1 && sy < h; sy++) {
                    for (int sx = x0; sx < x1 && sx < w; sx++) {
                        int p = px[sy * w + sx];
                        int alpha = p >>> 24;
                        r += (long) ((p >> 16) & 0xFF) * alpha;
                        g += (long) ((p >> 8) & 0xFF) * alpha;
                        b += (long) (p & 0xFF) * alpha;
                        a += alpha;
                        count++;
                    }
                }
                if (count == 0 || (double) a / count < 96) continue;
                int rr = (int) Math.round((double) r / a);
                int gg = (int) Math.round((double) g / a);
                int bb = (int) Math.round((double) b / a);
                out.setRGB(x, y, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
            }
        }
        return out;
    }

    private static void tint(int[] px, int rgb) {
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = ((p >> 16) & 0xFF) * tr / 255;
            int g = ((p >> 8) & 0xFF) * tg / 255;
            int b = (p & 0xFF) * tb / 255;
            px[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    /** {@code top} drawn over {@code bottom}, straight alpha. */
    static int over(int bottom, int top) {
        int ta = top >>> 24;
        if (ta == 255) return top;
        if (ta == 0) return bottom;
        int ba = bottom >>> 24;
        int a = ta + ba * (255 - ta) / 255;
        if (a == 0) return 0;
        int r = (((top >> 16) & 0xFF) * ta + ((bottom >> 16) & 0xFF) * ba * (255 - ta) / 255) / a;
        int g = (((top >> 8) & 0xFF) * ta + ((bottom >> 8) & 0xFF) * ba * (255 - ta) / 255) / a;
        int b = ((top & 0xFF) * ta + (bottom & 0xFF) * ba * (255 - ta) / 255) / a;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ ids

    /** {@code ruby} to {@code minecraft:ruby}; lower case, as the game reads one. */
    static String id(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.contains(":") ? t : "minecraft:" + t;
    }

    /** {@code nexo:item/ruby} in {@code models} to {@code assets/nexo/models/item/ruby.json}. */
    static String path(String id, String kind) {
        String full = id(id);
        int colon = full.indexOf(':');
        return "assets/" + full.substring(0, colon) + "/" + kind + "/" + full.substring(colon + 1) + ".json";
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** For tests: the character drawing a model, or null. */
    String glyphOf(String key) {
        return glyphs.get(key);
    }
}
