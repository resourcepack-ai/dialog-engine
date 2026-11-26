package ai.resourcepack.dialogs.core.yaml;

import ai.resourcepack.dialogs.core.placeholder.Scope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code item: api.<name>}: a plugin's stack, with what the body entry writes put over it. */
class ProvidedItemTest {

    private static final Scope SCOPE = new Scope(null, null, "test:dialog", Map.of("gem", "ruby"), Set.of(), null, null);

    /** A plugin's ruby as the server's codec writes it. */
    private static JsonObject ruby() {
        return JsonParser.parseString("{\"id\":\"minecraft:paper\",\"count\":3,\"components\":{"
                + "\"minecraft:item_model\":\"shop:ruby\",\"minecraft:custom_name\":{\"text\":\"Ruby\"}}}").getAsJsonObject();
    }

    private static Defs.BodyDef entry(String name, List<String> lore, String model, int amount, boolean glow) {
        return new Defs.BodyDef(Defs.BodyKind.ITEM, null, 200, "api.shop:{gem}", null, name, lore, model,
                Defs.ModelData.NONE, amount, glow, true, true, 16, 16, null, null, null);
    }

    @Test
    void anEntryThatWritesNothingShowsThePluginsItemAsItIs() {
        JsonObject shown = Renderer.overlaid(ruby(), entry(null, List.of(), null, 0, false), SCOPE);
        assertEquals(ruby(), shown);
    }

    @Test
    void whatTheEntryWritesIsPutOverIt() {
        JsonObject shown = Renderer.overlaid(ruby(), entry("<gold>{gem}", List.of("<gray>Rare"), "shop:ruby_big", 1, true), SCOPE);
        JsonObject components = shown.getAsJsonObject("components");
        assertEquals(1, shown.get("count").getAsInt());
        assertEquals("shop:ruby_big", components.get("minecraft:item_model").getAsString());
        assertTrue(components.get("minecraft:custom_name").toString().contains("ruby"));
        assertTrue(components.has("minecraft:lore"));
        assertTrue(components.get("minecraft:enchantment_glint_override").getAsBoolean());
    }

    @Test
    void aCountIsKeptToWhatABodyItemCanShow() {
        JsonObject stack = ruby();
        stack.addProperty("count", 400);
        assertEquals(99, Renderer.overlaid(stack, entry(null, List.of(), null, 0, false), SCOPE).get("count").getAsInt());
        stack.remove("count");
        assertEquals(1, Renderer.overlaid(stack, entry(null, List.of(), null, 0, false), SCOPE).get("count").getAsInt());
    }

    @Test
    void namesAreWhatAContainerNameIs() {
        assertTrue(Renderer.isItemName("shop_icon2"));
        assertFalse(Renderer.isItemName("Shop"));
        assertFalse(Renderer.isItemName("shop:ruby"));
        assertFalse(Renderer.isItemName(""));
        assertFalse(Renderer.isItemName(null));
    }
}
