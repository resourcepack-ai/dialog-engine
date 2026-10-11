package ai.resourcepack.dialogs.core.drawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drawn dialog's click sounds. Studio keys a piece's sound by the command its
 * click runs as Studio wrote it; this engine rewrites every click before it is
 * sent, so the sound has to be found under the command the rewrite sent, or
 * every piece would fall back to the dialog's sound.
 */
class DrawnSoundsTest {

    private static final String PAGE = """
            {"id":"shop","name":"Shop",
             "json":{"type":"minecraft:notice","title":"Shop","body":[{"type":"minecraft:plain_message","contents":{"text":"","extra":[
               {"text":" ","click_event":{"action":"run_command","command":"rp page studio:shop.ranks"}},
               {"text":" ","click_event":{"action":"run_command","command":"[message] Bought!"}},
               {"text":" ","click_event":{"action":"run_command","command":"rp var mode {mode?a:b|b:a}"}}]}}]},
             "clickSound":{"sound":"custom.pop","volume":0.6,"pitch":1.4},
             "clickSounds":[
               {"command":"rp page studio:shop.ranks","sound":"","volume":0.25,"pitch":1},
               {"command":"[message] Bought!","sound":"entity.experience_orb.pickup","volume":1,"pitch":1.5},
               {"command":"rp var mode {mode?a:b|b:a}","sound":"block.lever.click","volume":0.5,"pitch":1},
               {"command":"say not in this drawing","sound":"entity.cat.purr","volume":1,"pitch":1},
               {"command":"rp page studio:shop","sound":"NOT A SOUND","volume":1,"pitch":1}]}
            """;

    private DrawnDialogs.PageSounds sounds() {
        JsonObject page = JsonParser.parseString(PAGE).getAsJsonObject();
        List<List<String>> actions = new ArrayList<>();
        JsonElement json = page.get("json").deepCopy();
        DrawnDialogs.rewrite(json, actions, Set.of("[message] Bought!"), new ArrayList<>());
        return DrawnDialogs.pageSounds(page, actions);
    }

    @Test
    void aPiecesSoundIsFoundUnderTheCommandTheRewriteSent() {
        DrawnDialogs.PageSounds sounds = sounds();
        assertEquals("custom.pop", sounds.dialog().key());
        assertTrue(sounds.clicks().get("dialogclick page studio:shop.ranks").key().isEmpty(), "a silent piece stays silent");
        assertEquals("entity.experience_orb.pickup", sounds.clicks().get("dialogclick act 0").key());
        assertEquals(1.5f, sounds.clicks().get("dialogclick act 0").pitch());
        assertEquals(3, sounds.clicks().size(), "a command no click runs, and a sound that is not one, are left out");
    }

    @Test
    void aFilledPlaceholderStillFindsItsSound() {
        DrawnDialogs.PageSounds sounds = sounds();
        String held = sounds.clicks().keySet().stream().filter(k -> k.startsWith("dialogclick var")).findFirst().orElseThrow();
        assertTrue(DrawnDialogs.sameCommand(held, "dialogclick var mode b"));
        assertFalse(DrawnDialogs.sameCommand(held, "dialogclick page studio:shop"));
    }

    @Test
    void aPageWithNoSoundsHasNone() {
        JsonObject page = JsonParser.parseString("{\"id\":\"x\",\"json\":{}}").getAsJsonObject();
        assertNull(DrawnDialogs.pageSounds(page, List.of()));
    }
}
