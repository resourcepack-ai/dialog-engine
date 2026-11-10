package ai.resourcepack.dialogs.core.placeholder;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a player typed, filled into a command: refused unless it is a name, or filtered. */
class TextTest {

    /** {@code typed_*} keys are what a player typed; the rest came from the dialog. No server, no PlaceholderAPI. */
    private static Scope scope(Map<String, String> values) {
        Set<String> untrusted = Set.copyOf(values.keySet().stream().filter(k -> k.startsWith("typed")).toList());
        return new Scope(null, null, "test:dialog", values, untrusted, null, null);
    }

    private static String command(String raw, Map<String, String> values) {
        return Text.fill(raw, scope(values), Text.Mode.COMMAND);
    }

    @Test
    void aTypedNameGoesIntoACommand() {
        assertEquals("kill Steve_2", command("kill {typed}", Map.of("typed", "Steve_2")));
        assertEquals("give x 12.5 -3", command("give x {typed_a} {typed_b}", Map.of("typed_a", "12.5", "typed_b", "-3")));
        assertEquals("say ", command("say {typed}", Map.of("typed", "")));
    }

    @Test
    void aTypedSelectorSpaceOrQuoteIsRefused() {
        for (String bad : new String[] {"@a", "@e[type=player]", "Steve Alex", "a\"b", "a'b", "{x}", "a;b", "é", "a\nb"}) {
            Text.Unsafe e = assertThrows(Text.Unsafe.class, () -> command("kill {typed}", Map.of("typed", bad)), bad);
            assertEquals("typed", e.key);
        }
    }

    @Test
    void aChoiceValueIsCheckedToo() {
        assertThrows(Text.Unsafe.class, () -> command("kill {typed?x:no|$value}", Map.of("typed", "@a")));
        assertEquals("kill no", command("kill {typed?x:no|$value}", Map.of("typed", "x")));
    }

    @Test
    void whatTheDialogSaysIsNotChecked() {
        assertEquals("eco give @a 5 coins", command("eco give {who} {n}", Map.of("who", "@a", "n", "5 coins")));
    }

    @Test
    void rawHandsItOverOnOneLine() {
        assertEquals("staffchat it was @a and \"me\"", command("staffchat {typed|raw}", Map.of("typed", "it was @a and \"me\"")));
        assertEquals("staffchat two lines", command("staffchat {typed|raw}", Map.of("typed", "two\nlines")));
    }

    @Test
    void jsonStaysInsideItsString() {
        String out = command("tellraw @a {\"text\":\"{typed|json}\"}", Map.of("typed", "\",\"click_event\":{\"action\":\"run_command\"}\\\nx"));
        assertEquals("tellraw @a {\"text\":\"\\\",\\\"click_event\\\":{\\\"action\\\":\\\"run_command\\\"}\\\\\\nx\"}", out);
    }

    @Test
    void snbtStaysInsideEitherQuote() {
        String out = command("give {p} paper[custom_name='{typed|snbt}']", Map.of("p", "Steve", "typed", "it's \"x\" \\"));
        assertEquals("give Steve paper[custom_name='it\\'s \\\"x\\\" \\\\']", out);
    }

    @Test
    void anUnknownFilterIsLeftAsWritten() {
        assertEquals("say {typed|upper}", command("say {typed|upper}", Map.of("typed", "@a")));
    }

    @Test
    void outsideACommandNothingIsRefused() {
        assertEquals("@a and Alex", Text.fill("{typed}", scope(Map.of("typed", "@a and Alex")), Text.Mode.RAW));
        assertEquals("\\<red>hi", Text.fill("{typed}", scope(Map.of("typed", "<red>hi")), Text.Mode.TEXT));
        // A filter in a raw JSON dialog escapes for its own string, then the dialog's.
        assertEquals("a\\\\\\\"b", Text.fill("{typed|json}", scope(Map.of("typed", "a\"b")), Text.Mode.JSON));
    }

    /**
     * A drawn dialog's click asks what its picture was filled from, last
     * ({@link Scope#withMore}): a list row PlaceholderAPI answered printed the
     * player's name and ran "party invite {player_list_1}" as written.
     */
    @Test
    void aNameOnlyThePictureKnowsIsAskedLast() {
        Scope scope = scope(Map.of("target", "Steve")).withMore(name -> switch (name) {
            case "player_list_1" -> "Alex";
            case "target" -> "Nobody";
            default -> null;
        });
        assertEquals("party invite Alex", Text.fill("party invite {player_list_1}", scope, Text.Mode.COMMAND));
        assertEquals("kick Steve", Text.fill("kick {target}", scope, Text.Mode.COMMAND), "the dialog's own value still comes first");
        assertEquals("say {nobody_knows}", Text.fill("say {nobody_knows}", scope, Text.Mode.COMMAND), "a name nothing knows is left as written");
        assertEquals("invite Alex", Text.fill("invite {player_list_1}", scope.withRow("row", Map.of("value", "x")), Text.Mode.COMMAND),
                "a row's scope asks it too");
        assertEquals("party invite {player_list_1}", command("party invite {player_list_1}", Map.of("target", "Steve")),
                "and without it, as before");
    }

    /**
     * A row of a list somebody typed ({@code warps=Spawn,@a} when opening) is
     * answered from what the drawing kept, like any row, but it is still their
     * words: the click names it untrusted, so a command takes it only as a name.
     */
    @Test
    void aRowOfATypedListIsStillWhatAPlayerTyped() {
        Map<String, String> rows = Map.of("warps_1", "Spawn", "warps_2", "@a", "warps_3", "<red>Hub");
        Scope scope = new Scope(null, null, "test:dialog", Map.of(), rows.keySet(), null, null).withMore(rows::get);
        assertEquals("warp Spawn", Text.fill("warp {warps_1}", scope, Text.Mode.COMMAND));
        Text.Unsafe e = assertThrows(Text.Unsafe.class, () -> Text.fill("warp {warps_2}", scope, Text.Mode.COMMAND));
        assertEquals("warps_2", e.key);
        assertEquals("warp @a", Text.fill("warp {warps_2|raw}", scope, Text.Mode.COMMAND), "unless the author says it is safe there");
        assertEquals("Going to \\<red>Hub", Text.fill("Going to {warps_3}", scope, Text.Mode.TEXT), "and it is escaped where it is printed");
    }

    @Test
    void safeIsANameANumberOrAnId() {
        assertTrue(Text.safe("Notch"));
        assertTrue(Text.safe("minecraft.diamond-1_2"));
        assertFalse(Text.safe("minecraft:diamond"));
        assertFalse(Text.safe("a b"));
    }
}
