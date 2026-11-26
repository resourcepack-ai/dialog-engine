package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogruntime.DialogItemIcons;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPicturesTest {

    private static final String FONT = "assets/minecraft/font/dialog_items_0.json";
    private static final String FONT_JSON = """
            {"providers":[{"type":"space","advances":{}},
            {"type":"bitmap","file":"minecraft:font/dialog_items.png","height":16,"ascent":7,"chars":["\\ue000"]}]}""";

    @TempDir
    Path dir;

    private static void put(Map<String, byte[]> files, String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] png(int w, int h, int argb) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Half a picture: the left half coloured, the right clear. */
    private static byte[] leftHalf(int argb) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 8; x++) img.setRGB(x, y, argb);
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A Nexo-like pack: a paper whose custom model data picks a layered tool or a 3D block; an item_model ruby. */
    private Path pack() throws IOException {
        Map<String, byte[]> f = new TreeMap<>();
        put(f, "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"\"},\"overlays\":{\"entries\":["
                + "{\"formats\":[22,34],\"directory\":\"old\"},{\"formats\":[46,99],\"directory\":\"new\"}]}}");
        put(f, "assets/minecraft/items/paper.json", """
                {"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data","entries":[
                  {"threshold":1001,"model":{"type":"minecraft:model","model":"shop:item/tool"}},
                  {"threshold":1002,"model":{"type":"minecraft:model","model":"shop:block/crate"}}],
                 "fallback":{"type":"minecraft:model","model":"minecraft:item/paper"}}}""");
        put(f, "assets/shop/models/item/tool.json", """
                {"parent":"minecraft:item/handheld","textures":{"layer0":"shop:item/handle","layer1":"shop:item/head"}}""");
        put(f, "assets/shop/models/block/crate.json", """
                {"parent":"minecraft:block/cube_all","textures":{"all":"shop:block/crate"}}""");
        f.put("assets/shop/textures/item/handle.png", png(16, 16, 0xFF0000FF));
        f.put("assets/shop/textures/item/head.png", leftHalf(0xFFFF0000));
        // The ruby, defined only in the overlay a new client reads, and differently in an old one.
        put(f, "new/assets/nexo/items/ruby.json", """
                {"model":{"type":"minecraft:select","property":"minecraft:display_context",
                  "cases":[{"when":"gui","model":{"type":"minecraft:model","model":"nexo:item/ruby_icon"}}],
                  "fallback":{"type":"minecraft:model","model":"nexo:item/ruby_3d"}}}""");
        put(f, "old/assets/nexo/items/ruby.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"nexo:item/wrong\"}}");
        put(f, "assets/nexo/models/item/ruby_icon.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"nexo:item/ruby\"}}");
        // An animated strip of two frames: the first is green.
        BufferedImage strip = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) strip.setRGB(x, y, y < 16 ? 0xFF00FF00 : 0xFFFFFFFF);
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(strip, "png", out);
        f.put("assets/nexo/textures/item/ruby.png", out.toByteArray());
        Path zip = dir.resolve("Nexo").resolve("pack").resolve("pack.zip");
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : f.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return zip;
    }

    private static ItemPictures.Facts paper(float cmd) {
        return new ItemPictures.Facts("minecraft:paper", null, List.of(cmd), List.of(), List.of());
    }

    @Test
    void findsThePacksInOtherPluginsFolders() throws IOException {
        Path zip = pack();
        Files.createDirectories(dir.resolve("Other"));
        Files.writeString(dir.resolve("Other").resolve("notes.zip"), "not a zip");
        List<Path> found = ItemPictures.locate(dir.resolve("DialogEngine"), List.of(dir.resolve("Nexo"), dir.resolve("Other")));
        assertEquals(List.of(zip), found);
    }

    @Test
    void drawsALayeredToolAndAddsItToEveryIconFont() throws IOException {
        Path zip = pack();
        Map<String, byte[]> files = new TreeMap<>();
        put(files, FONT, FONT_JSON);
        ItemPictures pictures = new ItemPictures();
        ItemPictures.Built built = pictures.build(files, List.of(zip), Logger.getAnonymousLogger());

        // The tool and the ruby are drawn; the crate is a pile of cubes, and paper is the game's own.
        assertEquals(2, built.drawn(), built.toString());
        String tool = pictures.glyph(paper(1001));
        assertNotNull(tool);
        assertTrue(tool.charAt(0) >= ItemPictures.FIRST, tool);
        assertNull(pictures.glyph(paper(1002)), "a 3D model has no flat picture");
        // Below every threshold: the game's own paper, which is the vanilla icon.
        assertEquals(String.valueOf(DialogItemIcons.glyph("paper")), pictures.glyph(paper(5)));

        String ruby = pictures.glyph(new ItemPictures.Facts("minecraft:paper", "nexo:ruby", List.of(), List.of(), List.of()));
        assertNotNull(ruby, "the gui case of the new overlay's definition");
        assertFalse(ruby.equals(tool));
        assertNull(pictures.glyph(new ItemPictures.Facts("minecraft:paper", "nexo:missing", List.of(), List.of(), List.of())));

        // The font gained a provider at the sheet's height and ascent, naming both characters.
        JsonObject font = JsonParser.parseString(new String(files.get(FONT), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject mine = font.getAsJsonArray("providers").get(2).getAsJsonObject();
        assertEquals(ItemPictures.SHEET_REF, mine.get("file").getAsString());
        assertEquals(7, mine.get("ascent").getAsInt());
        assertEquals(16, mine.get("height").getAsInt());
        String row = mine.getAsJsonArray("chars").get(0).getAsString();
        assertEquals(ItemPictures.COLUMNS, row.length());
        assertTrue(row.indexOf(tool.charAt(0)) >= 0 && row.indexOf(ruby.charAt(0)) >= 0);

        // The sheet: the tool's head (red, left half) over its handle (blue), every cell 16 wide.
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(files.get(ItemPictures.SHEET)));
        int t = tool.charAt(0) - ItemPictures.FIRST;
        int ox = (t % ItemPictures.COLUMNS) * 16;
        int oy = (t / ItemPictures.COLUMNS) * 16;
        assertEquals(0xFFFF0000, sheet.getRGB(ox + 2, oy + 8));
        assertEquals(0xFF0000FF, sheet.getRGB(ox + 12, oy + 8));
        int r = ruby.charAt(0) - ItemPictures.FIRST;
        assertEquals(0xFF00FF00, sheet.getRGB((r % ItemPictures.COLUMNS) * 16 + 4, (r / ItemPictures.COLUMNS) * 16 + 12),
                "an animation's first frame");
    }

    @Test
    void addsNothingWithoutAnIconFont() throws IOException {
        Path zip = pack();
        Map<String, byte[]> files = new TreeMap<>();
        ItemPictures pictures = new ItemPictures();
        assertEquals(0, pictures.build(files, List.of(zip), Logger.getAnonymousLogger()).drawn());
        assertTrue(files.isEmpty());
        assertNull(pictures.glyph(paper(1001)));
    }

    @Test
    void readsAPackFolderToo() throws IOException {
        Path root = dir.resolve("Owner").resolve("mypack");
        Files.createDirectories(root.resolve("assets/minecraft/items"));
        Files.createDirectories(root.resolve("assets/minecraft/models/item"));
        Files.createDirectories(root.resolve("assets/minecraft/textures/item"));
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":46,\"description\":\"\"}}");
        Files.writeString(root.resolve("assets/minecraft/items/stick.json"), """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"wand","model":{"type":"minecraft:model","model":"item/wand",
                    "tints":[{"type":"minecraft:constant","value":-16711936}]}}],
                  "fallback":{"type":"minecraft:model","model":"item/stick"}}}""");
        Files.writeString(root.resolve("assets/minecraft/models/item/wand.json"), "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"item/wand\"}}");
        try (OutputStream o = Files.newOutputStream(root.resolve("assets/minecraft/textures/item/wand.png"))) {
            o.write(png(32, 32, 0xFFFFFFFF));
        }
        List<Path> found = ItemPictures.locate(dir.resolve("DialogEngine"), List.of(dir.resolve("Owner")));
        assertEquals(List.of(root), found);
        Map<String, byte[]> files = new TreeMap<>();
        put(files, FONT, FONT_JSON);
        ItemPictures pictures = new ItemPictures();
        assertEquals(1, pictures.build(files, found, Logger.getAnonymousLogger()).drawn());
        String wand = pictures.glyph(new ItemPictures.Facts("minecraft:stick", null, List.of(), List.of(), List.of("wand")));
        assertNotNull(wand);
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(files.get(ItemPictures.SHEET)));
        assertEquals(0xFF00FF00, sheet.getRGB(5, 5), "a white texture under a constant green tint, fitted from 32 to 16");
        assertEquals(String.valueOf(DialogItemIcons.glyph("stick")),
                pictures.glyph(new ItemPictures.Facts("minecraft:stick", null, List.of(), List.of(), List.of("other"))));
    }
}
