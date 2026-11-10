package ai.resourcepack.dialogs.core.click;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which action arguments carry what a player typed. */
class ActionsTest {

    @Test
    void aFilteredMentionStillCarriesTheTypedValue() {
        assertTrue(Actions.mentionsAny("reason={typed|raw}", Set.of("typed")));
        assertTrue(Actions.mentionsAny("reason={typed?a:b|c}", Set.of("typed")));
        assertFalse(Actions.mentionsAny("reason={other}", Set.of("typed")));
    }

    /**
     * A value a plugin hands back stays a player's words when it is one. A
     * typed list's row is held by the drawing rather than the session
     * ({@code warps_2} of {@code warps=Spawn,<b>Hub}), and is found there too.
     */
    @Test
    void aValueThatIsATypedOneStaysTyped() {
        Map<String, String> held = Map.of("reason", "<red>griefing", "warps_2", "<b>Hub");
        Set<String> typed = Set.of("reason", "warps_2", "cleared");
        assertTrue(ClickCtx.carries("<red>griefing", typed, held::get), "the typed value itself");
        assertTrue(ClickCtx.carries("<b>Hub", typed, held::get), "a typed list's row, which only the drawing holds");
        assertTrue(ClickCtx.carries("Warped to <b>Hub", typed, held::get), "and one inside other words, tags and all");
        assertFalse(ClickCtx.carries("Warped to \\<b>Hub", typed, held::get), "escaped, it is the plugin doing it right");
        assertFalse(ClickCtx.carries("Spawn", typed, held::get), "nobody typed that");
    }
}
