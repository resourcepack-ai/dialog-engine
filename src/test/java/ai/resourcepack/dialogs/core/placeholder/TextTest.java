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

    @Test
    void safeIsANameANumberOrAnId() {
        assertTrue(Text.safe("Notch"));
        assertTrue(Text.safe("minecraft.diamond-1_2"));
        assertFalse(Text.safe("minecraft:diamond"));
        assertFalse(Text.safe("a b"));
    }
}
