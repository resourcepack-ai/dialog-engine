package ai.resourcepack.dialogs.core.drawn;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a list's rows may run. dialog.yml holds a list as ONE template, and
 * Studio draws it once per row with the row's own spelling of every
 * placeholder: {@code {warps}} as {@code {warps_3}} in a list the server
 * fills, as {@code 3} in one that counts. A drawing's click only runs when
 * dialog.yml has its command, so {@link DrawnFiles} has to spell each row the
 * way Studio does, for a single command and for one run by key ("Run as
 * console", several commands).
 *
 * <p>The cases are Studio's own output ({@code drawn/list-rows.json}, which
 * Studio's {@code verify:dialogs} holds to what it draws). Before 0.7.1 a row's
 * console button and its several commands were keyed by the template alone and
 * did nothing, and a counting list's drawing was refused whole.
 */
class DrawnListRowsTest {

    private static final Gson GSON = new Gson();

    @Test
    void everyRowsClickIsACommandTheDocumentHas() throws IOException {
        JsonObject root;
        try (InputStream in = DrawnListRowsTest.class.getResourceAsStream("/drawn/list-rows.json")) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int rows = 0;
        for (JsonElement element : root.getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            String name = c.get("name").getAsString();
            Map<String, Object> document = GSON.fromJson(c.get("document"), new TypeToken<Map<String, Object>>() {}.getType());
            Map<String, Object> spec = Map.of("title", "Test", "document", document);

            Set<String> allowed = DrawnFiles.commands(spec);
            for (JsonElement command : c.getAsJsonArray("commands")) {
                assertTrue(allowed.contains(command.getAsString()), name + ": the drawing runs \"" + command.getAsString() + "\"");
                rows++;
            }
            Map<String, String> keyed = DrawnFiles.consoleCommands(spec);
            for (Map.Entry<String, JsonElement> key : c.getAsJsonObject("keyed").entrySet()) {
                assertEquals(key.getValue().getAsString(), keyed.get(key.getKey()), name + ": the drawing clicks key " + key.getKey());
                rows++;
            }
        }
        assertTrue(rows > 20, "the fixture was read");
    }

    /** The whole way: Studio's keyed click on a row becomes that row's own action list. */
    @Test
    void aRowsConsoleClickRunsThatRowsCommand() {
        Map<String, Object> spec = Map.of("document", Map.of("column", List.of(Map.of(
                "list", "player_list", "rows", 2,
                "column", List.of(Map.of("button", "Kick", "onClick", Map.of("action", "console_command", "value", "party kick {player_list}")))))));
        // Studio's keys for rows 1 and 2 (drawn/list-rows.json).
        String page = "{\"body\":[{\"click_event\":{\"action\":\"run_command\",\"command\":\"rp click 9d1456f7\"}},"
                + "{\"click_event\":{\"action\":\"run_command\",\"command\":\"rp click 9d1ca5bc\"}}]}";
        JsonElement json = JsonParser.parseString(page);
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        DrawnDialogs.rewrite(json, actions, DrawnFiles.commands(spec), DrawnFiles.consoleCommands(spec), refused, link -> false);
        assertEquals(List.of(), refused, "each row's key is one dialog.yml has");
        assertEquals(List.of(List.of("[console] party kick {player_list_1}"), List.of("[console] party kick {player_list_2}")), actions);
        assertEquals(Set.of("player_list_1", "player_list_2"), DrawnDialogs.rowNames(json.toString(), actions),
                "and the rows those actions name are kept as the page is drawn");
    }
}
