package ai.resourcepack.dialogs.core.yaml;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code hide-button: true}: kept on a notice's one button, said and dropped on anything with more. */
class HideButtonTest {

    private static Defs.DialogDef parse(Loader loader, String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return loader.parse("test:menu", "test", "test.yml", config, null);
    }

    @Test
    void aNoticesOneButtonCanBeHidden() throws Exception {
        Loader loader = new Loader(Defs.After.SEAMLESS);
        assertTrue(parse(loader, "title: Hi\nhide-button: true\nbuttons:\n  - label: Go\n    actions: ['[close]']\n").hideButton());
        assertTrue(parse(loader, "title: Hi\nhide-button: true\n").hideButton());
        assertTrue(loader.problems.isEmpty(), loader.problems.toString());
    }

    @Test
    void moreThanOneButtonShowsThemAllAndSaysSo() throws Exception {
        Loader loader = new Loader(Defs.After.SEAMLESS);
        Defs.DialogDef def = parse(loader, "title: Hi\nhide-button: true\nbuttons:\n  - label: One\n  - label: Two\n");
        assertFalse(def.hideButton());
        assertTrue(loader.problems.stream().anyMatch(p -> p.contains("hide-button")), loader.problems.toString());
    }

    @Test
    void offUnlessAskedFor() throws Exception {
        assertFalse(parse(new Loader(Defs.After.SEAMLESS), "title: Hi\nbuttons:\n  - label: Go\n").hideButton());
    }
}
