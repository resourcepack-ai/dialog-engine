package ai.resourcepack.dialogs.core.drawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command on a list row. Studio draws row 3 of a list called
 * {@code player_list} with {@code {player_list_3}} in its words and in its
 * click, and the picture answered that name (from PlaceholderAPI, say) while
 * the click ran {@code party invite {player_list_3}} as written: "{player_list_1}
 * is offline". The page's clicks say which rows they name, the drawing keeps
 * what each showed, and the click's actions are answered with it.
 */
class DrawnRowsTest {

    private static final String PAGE = """
            {"type":"minecraft:notice","title":"Party","body":[{"type":"minecraft:plain_message","contents":{"text":"","extra":[
              {"text":"{player_list_1}","click_event":{"action":"run_command","command":"/party invite {player_list_1}"}},
              {"text":"{player_list_2}","click_event":{"action":"run_command","command":"/party invite {player_list_2}"}},
              {"text":"{player_list_3}","click_event":{"action":"run_command","command":"/[console] warn {player_list_3|raw} {player_list_3_note?a:Yes|No}; [message] Warned {player}"}},
              {"text":"Close","click_event":{"action":"run_command","command":"/rp page close"}},
              {"text":"Help","click_event":{"action":"run_command","command":"/help {player}"}}]}}]}
            """;

    private static final Set<String> ALLOWED = Set.of(
            "party invite {player_list_1}",
            "party invite {player_list_2}",
            "[console] warn {player_list_3|raw} {player_list_3_note?a:Yes|No}; [message] Warned {player}",
            "help {player}");

    @Test
    void aPagesClicksSayWhichRowsTheyName() {
        List<List<String>> actions = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        JsonElement json = JsonParser.parseString(PAGE);
        DrawnDialogs.rewrite(json, actions, ALLOWED, refused);
        assertEquals(List.of(), refused, "every command is one the dialog has");
        assertEquals(Set.of("player_list_1", "player_list_2", "player_list_3", "player_list_3_note"),
                DrawnDialogs.rowNames(json.toString(), actions),
                "each row's numbered name, a second field and a filtered or chosen one included; {player} is nobody's row");
    }

    @Test
    void onlyThePagesOwnClicksCount() {
        List<List<String>> actions = List.of(List.of("party invite {player_list_1}"), List.of("party invite {player_list_9}"));
        String page = "{\"click_event\":{\"action\":\"run_command\",\"command\":\"/dialogclick act 0\"}}";
        assertEquals(Set.of("player_list_1"), DrawnDialogs.rowNames(page, actions), "another page's action is not this page's row");
        assertEquals(Set.of(), DrawnDialogs.rowNames("{\"command\":\"dialogclick act 7\"}", actions), "an action the dialog does not have names nothing");
        assertEquals(Set.of(), DrawnDialogs.rowNames("{\"command\":\"dialogclick page close\"}", actions));
    }

    @Test
    void aRowShownIsTheRowActedOn() {
        // What the drawing kept: row 1 showed Alex. PlaceholderAPI is not asked again for it.
        Function<String, String> answers = DrawnDialogs.pictureAnswers(null, Map.of("player_list_1", "Alex"));
        assertEquals("Alex", answers.apply("player_list_1"));
        assertNull(answers.apply("displayname"), "a nickname is a player's words, and what this answers is trusted");
    }

    @Test
    void aListAPlayerTypedIsNotARowsAnswer() {
        assertTrue(DrawnDialogs.fromTyped("warps_3", Set.of("warps")), "warps=a,b from /de open is typed, and so is its third item");
        assertTrue(DrawnDialogs.fromTyped("Warps_3_note", Set.of("warps")));
        assertFalse(DrawnDialogs.fromTyped("warps_3", Set.of("target")), "another typed value says nothing about this list");
        assertFalse(DrawnDialogs.fromTyped("target", Set.of("target")), "not a row at all");
    }
}
