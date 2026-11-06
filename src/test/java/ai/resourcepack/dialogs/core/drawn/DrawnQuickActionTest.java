package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogs.core.yaml.Defs;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A drawn dialog's {@code quickAction}, in the shapes Studio writes into
 * dialog.yml ({@code drawn/quick-actions.json}, which Studio's own check holds
 * to what it writes), read as the launcher flag and label it means.
 */
class DrawnQuickActionTest {

    @Test
    void readsStudiosShapes() throws IOException {
        JsonObject root;
        try (InputStream in = DrawnQuickActionTest.class.getResourceAsStream("/drawn/quick-actions.json")) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (JsonElement element : root.getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            Map<String, Object> spec = new Gson().fromJson(c.get("spec"), new TypeToken<Map<String, Object>>() {}.getType());
            Defs.QuickAction quick = DrawnDialogs.quickAction(spec, "test");
            if (c.has("absent")) {
                assertNull(quick, c.toString());
                continue;
            }
            assertNotNull(quick, c.toString());
            assertEquals(c.get("launcher").getAsBoolean(), quick.launcher(), c.toString());
            assertEquals(c.get("label").getAsString(), quick.label(), c.toString());
        }
    }
}
