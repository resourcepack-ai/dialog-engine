package ai.resourcepack.dialogs.core.drawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drawn dialog's "Open another dialog" clicks. Left to the client, an id is a
 * lookup in the server's dialog registry, which has none of Dialog Engine's
 * dialogs, and the game refuses the whole dialog holding one such click
 * ("Failed to get element ResourceKey[minecraft:dialog / studio:vipbilgi]").
 * So a link to one of ours is sent as an action click that opens it here.
 */
class DrawnLinksTest {

    private static final String PAGE = """
            {"type":"minecraft:notice","title":"Guide","body":[{"type":"minecraft:plain_message","contents":{"text":"","extra":[
              {"text":" ","click_event":{"action":"show_dialog","dialog":"studio:vipbilgi"}},
              {"text":" ","click_event":{"action":"show_dialog","dialog":"Studio:VipBilgi"}},
              {"text":" ","click_event":{"action":"show_dialog","dialog":"minecraft:server_links"}},
              {"text":" ","click_event":{"action":"show_dialog","dialog":{"type":"minecraft:notice","title":"Inline"}}},
              {"text":" ","click_event":{"action":"show_dialog","dialog":"studio:has space"}}]}}],
             "action":{"label":"VIP","action":{"type":"minecraft:show_dialog","dialog":"studio:vipbilgi"}}}
            """;

    private static String rewritten(List<List<String>> actions) {
        JsonElement json = JsonParser.parseString(PAGE);
        DrawnDialogs.rewrite(json, actions, Set.of(), new ArrayList<>(), link -> link.startsWith("studio:"));
        return json.toString();
    }

    @Test
    void aLinkToOneOfOursOpensItHere() {
        List<List<String>> actions = new ArrayList<>();
        String json = rewritten(actions);
        assertEquals(List.of(List.of("[open] studio:vipbilgi")), actions, "one action, however many clicks name it, in any case");
        assertTrue(json.contains("{\"action\":\"run_command\",\"command\":\"dialogclick act 0\"}"),
                "a body click keeps its keys in the order picture() reads: " + json);
        assertTrue(json.contains("{\"type\":\"minecraft:run_command\",\"command\":\"dialogclick act 0\"}"),
                "a footer button is the game's run_command action: " + json);
        assertTrue(!json.contains("studio:vipbilgi"), "no registry lookup is left for the game to refuse: " + json);
    }

    @Test
    void anythingElseIsLeftForTheGame() {
        String json = rewritten(new ArrayList<>());
        assertTrue(json.contains("\"dialog\":\"minecraft:server_links\""), "the registry may well have it");
        assertTrue(json.contains("\"dialog\":{\"type\":\"minecraft:notice\",\"title\":\"Inline\"}"), "a dialog written out needs no lookup");
        assertTrue(json.contains("\"dialog\":\"studio:has space\""), "not an id, so not an action's argument");
    }

    @Test
    void theOldRewriteOpensNothingItself() {
        JsonElement json = JsonParser.parseString(PAGE);
        List<List<String>> actions = new ArrayList<>();
        DrawnDialogs.rewrite(json, actions, Set.of(), new ArrayList<>());
        assertTrue(actions.isEmpty());
        assertTrue(json.toString().contains("\"dialog\":\"studio:vipbilgi\""));
    }
}
