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

    /**
     * Several commands on one button, one per line: a console button's are
     * {@code [console]} actions in order, and a "Run command" with more than
     * one is Studio's {@code rp run <key>}, {@code [player]} actions in order.
     */
    @Test
    void severalCommandsAreOneActionListInOrder() {
        String food = "give {player} bread\ngive {player} torch 16";
        String go = "spawn\nkit starter";
        Map<String, Object> spec = Map.of("buttons", List.of(
                Map.of("action", "console_command", "value", "give {player} bread\n\n/give {player} torch 16"),
                Map.of("action", "run_command", "value", "/spawn\r\nkit starter"),
                Map.of("action", "run_command", "value", "shop")));
        Map<String, String> consoles = DrawnFiles.consoleCommands(spec);
        String foodKey = DrawnFiles.consoleKey(food);
        String goKey = DrawnFiles.consoleKey(go);
        assertEquals(Map.of(foodKey, food, goKey, go), consoles);

        String page = "{\"type\":\"minecraft:multi_action\",\"title\":\"Kit\",\"actions\":["
                + "{\"label\":\"Food\",\"action\":{\"type\":\"minecraft:run_command\",\"command\":\"rp click " + foodKey + "\"}},"
                + "{\"label\":\"Go\",\"action\":{\"type\":\"minecraft:run_command\",\"command\":\"rp run " + goKey + "\"}}]}";
        JsonElement json = JsonParser.parseString(page);
        // Studio's own clicks by key are not commands the drawing invented.
        assertEquals(List.of(), DrawnFiles.invented(json, DrawnFiles.commands(spec)));
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        DrawnDialogs.rewrite(json, actions, Set.of(), consoles, refused, link -> false);
        assertEquals(List.of(
                List.of("[console] give {player} bread", "[console] give {player} torch 16"),
                List.of("[player] spawn", "[player] kit starter")), actions);
        assertEquals(List.of(), refused);
        assertFalse(json.toString().contains("rp run"), json.toString());
        assertEquals("dialogclick act 1", DrawnDialogs.rewritten("rp run " + goKey, actions, consoles));
        // A key clicked one way never runs the other way's actions.
        assertEquals(null, DrawnDialogs.rewritten("rp run " + foodKey, actions, consoles));
    }
}
