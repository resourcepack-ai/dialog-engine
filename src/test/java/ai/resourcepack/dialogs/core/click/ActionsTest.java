package ai.resourcepack.dialogs.core.click;

import org.junit.jupiter.api.Test;

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
}
