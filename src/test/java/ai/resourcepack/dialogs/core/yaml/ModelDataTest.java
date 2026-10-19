package ai.resourcepack.dialogs.core.yaml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A body item's custom-model-data: as written in YAML, and as the component the game reads. */
class ModelDataTest {

    private static final UnaryOperator<String> AS_WRITTEN = v -> v;

    /** What a body entry with this custom-model-data: sends, as JSON text, or null for no component. */
    private static String sent(Object written, UnaryOperator<String> fill) {
        var data = Loader.modelData(Loader.section(Map.of("item", "paper", "custom-model-data", written))).json(fill);
        return data == null ? null : data.toString();
    }

    @Test
    void aNumberIsAPluginsCustomModelData() {
        // What 1.21.4 made of the old whole number: the first float.
        assertEquals("{\"floats\":[1001.0]}", sent(1001, AS_WRITTEN));
    }

    @Test
    void aListIsSortedByWhatEachValueSays() {
        assertEquals("{\"floats\":[2.5],\"flags\":[true],\"strings\":[\"ruby\"]}", sent(List.of("ruby", true, 2.5), AS_WRITTEN));
    }

    @Test
    void aPlaceholderIsReadOnceItIsFilled() {
        UnaryOperator<String> fill = v -> v.replace("{tier}", "3").replace("{gem}", "emerald");
        assertEquals("{\"floats\":[3.0],\"strings\":[\"emerald\"]}", sent(List.of("{tier}", "{gem}"), fill));
        // Filled to nothing, it sends no component rather than an empty one.
        assertNull(sent("{gone}", v -> v.replace("{gone}", "")));
    }

    @Test
    void theGamesOwnListsKeepOnlyTheirKind() {
        Map<String, Object> lists = Map.of(
                "floats", List.of(1.5, "x", "NaN"),
                "flags", List.of("TRUE", "nope"),
                "strings", List.of("", "a"),
                "colors", List.of("#ff0000", 16777216, 255));
        assertEquals("{\"floats\":[1.5],\"flags\":[true],\"strings\":[\"a\"],\"colors\":[16711680,255]}", sent(lists, AS_WRITTEN));
    }

    @Test
    void noneSendsNothing() {
        assertNull(Defs.ModelData.NONE.json(AS_WRITTEN));
        assertNull(Loader.modelData(Loader.section(Map.of("item", "paper"))).json(AS_WRITTEN));
    }
}
