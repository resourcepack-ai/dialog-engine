package ai.resourcepack.dialogs.core.drawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drawn dialog's "Run as console" clicks. Studio sends one as
 * {@code rp click <key>} and keeps the command out of the drawing; here it is a
 * {@code [console]} action, the command taken from dialog.yml by that key.
 */
class DrawnConsoleTest {

    /** Studio's {@code dialogConsoleKey("warp spawn {player}")}, and RP Engine's. */
    private static final String KEY = "f56969d3";

    private static final String PAGE = """
            {"type":"minecraft:notice","title":"Warps","body":[{"type":"minecraft:plain_message","contents":{"text":"","extra":[
              {"text":" ","click_event":{"action":"run_command","command":"rp click f56969d3"}},
              {"text":" ","click_event":{"action":"run_command","command":"rp click 0badc0de"}}]}}],
             "action":{"label":"Spawn","action":{"type":"minecraft:run_command","command":"rp click f56969d3"}}}
            """;

    @Test
    void theKeyIsStudios() {
        assertEquals(KEY, DrawnFiles.consoleKey("warp spawn {player}"));
    }

    @Test
    void dialogYmlSaysWhatEachKeyRuns() {
        Map<String, Object> spec = Map.of("buttons", List.of(
                Map.of("action", "console_command", "value", "/warp spawn {player}"),
                Map.of("action", "console_command", "value", "say $(typed)"),
                Map.of("action", "run_command", "value", "spawn")));
        assertEquals(Map.of(KEY, "warp spawn {player}"), DrawnFiles.consoleCommands(spec));
    }

    @Test
    void aConsoleClickIsAConsoleActionAndAnUnknownKeyRunsNothing() {
        JsonElement json = JsonParser.parseString(PAGE);
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        DrawnDialogs.rewrite(json, actions, Set.of(), Map.of(KEY, "warp spawn {player}"), refused, link -> false);
        assertEquals(List.of(List.of("[console] warp spawn {player}"), List.of()), actions);
        String text = json.toString();
        assertTrue(text.contains("{\"action\":\"run_command\",\"command\":\"dialogclick act 0\"}"), text);
        assertTrue(text.contains("{\"type\":\"minecraft:run_command\",\"command\":\"dialogclick act 0\"}"), text);
        assertTrue(text.contains("\"command\":\"dialogclick act 1\""), text);
        assertFalse(text.contains("rp click"), text);
        assertEquals(List.of("rp click 0badc0de"), refused);
        assertEquals("dialogclick act 0", DrawnDialogs.rewritten("rp click " + KEY, actions, Map.of(KEY, "warp spawn {player}")));
    }
}
