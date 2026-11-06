package ai.resourcepack.dialogs.core.placeholder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every case in {@code dialog-conditions/cases.json}: the condition
 * language a dialog's {@code if:} is written in, which RP Engine's drawn
 * dialogs and the editor that writes them speak too. The file is shared with
 * RP Engine's test, so the ends answer every case alike.
 */
class ConditionsTest {

    /** A viewer who holds exactly these permissions, and is nothing else. */
    private static Player holding(Set<String> held) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args != null && args[0] instanceof String p) {
                        return held.contains(p);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void answersEverySharedCase() throws IOException {
        JsonArray cases;
        try (InputStream in = ConditionsTest.class.getResourceAsStream("/dialog-conditions/cases.json")) {
            cases = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("cases");
        }
        assertTrue(cases.size() > 0);
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (JsonElement element : cases) {
            JsonArray c = element.getAsJsonArray();
            String expression = c.get(0).getAsString();
            Map<String, String> values = new HashMap<>();
            c.get(1).getAsJsonObject().entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
            Set<String> held = new HashSet<>();
            c.get(2).getAsJsonArray().forEach(p -> held.add(p.getAsString()));
            boolean want = c.get(3).getAsBoolean();
            Scope scope = new Scope(null, holding(held), "test:dialog", values, Set.of(), null, null);
            boolean got = Conditions.test(expression, scope);
            if (want != got) wrong.add(expression + " with " + values + " and " + held + " is " + got);
        }
        assertEquals(java.util.List.of(), wrong);
    }
}
