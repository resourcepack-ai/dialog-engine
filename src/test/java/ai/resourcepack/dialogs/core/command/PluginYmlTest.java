package ai.resourcepack.dialogs.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code plugin.yml} has to agree with itself and with the code.
 *
 * <p>The usage line, the node each subcommand checks, the children of
 * {@code dialogengine.admin} and the nodes the code asks for all say the same
 * thing, and nothing at runtime notices when one of them is missing an entry.
 * A node nobody declared is op-only with no description; one missing from
 * {@code admin} is a command the owner who granted the parent cannot run.
 * Read as text: the assertions are about what the file says.
 */
class PluginYmlTest {

    private static String pluginYml() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "plugin.yml"));
    }

    /** Every {@code dialogengine.x:} node declared under {@code permissions:}. */
    private static Set<String> declaredNodes(String yml) {
        Set<String> nodes = new LinkedHashSet<>();
        Matcher node = Pattern.compile("(?m)^  (dialogengine\\.[a-z.]+):\\R").matcher(yml);
        while (node.find()) nodes.add(node.group(1));
        return nodes;
    }

    @Test
    void theUsageLineIsTheSubcommandList() throws IOException {
        Matcher usage = Pattern.compile("usage: /de <([a-z|]+)>").matcher(pluginYml());
        assertTrue(usage.find(), "no usage line for /dialogengine");
        assertEquals(AdminCommand.SUBCOMMANDS, List.of(usage.group(1).split("\\|")));
    }

    @Test
    void everySubcommandHasADeclaredNode() throws IOException {
        Set<String> nodes = declaredNodes(pluginYml());
        for (String sub : AdminCommand.SUBCOMMANDS) {
            assertTrue(nodes.contains(AdminCommand.permissionFor(sub)), "/de " + sub + " has no declared node");
        }
        assertTrue(nodes.contains(AdminCommand.OPEN_OTHERS), AdminCommand.OPEN_OTHERS + " is not declared");
    }

    @Test
    void adminGrantsEverythingAndNothingChecksIt() throws IOException {
        String yml = pluginYml();
        int from = yml.indexOf("  dialogengine.admin:");
        int to = yml.indexOf("\n  dialogengine.", from + 1);
        String admin = yml.substring(from, to);
        for (String node : declaredNodes(yml)) {
            if (node.equals("dialogengine.admin")) continue;
            assertTrue(admin.contains("      " + node + ": true"), "dialogengine.admin does not grant " + node);
        }
        for (String node : nodesInCode()) {
            assertTrue(!node.equals("dialogengine.admin"), "the code checks dialogengine.admin, which is only a parent");
        }
    }

    @Test
    void everyNodeTheCodeChecksIsDeclared() throws IOException {
        Set<String> declared = declaredNodes(pluginYml());
        for (String node : nodesInCode()) {
            assertTrue(declared.contains(node), node + " is checked in the code but not declared in plugin.yml");
        }
    }

    @Test
    void theCommandItselfCarriesNoPermission() throws IOException {
        // A command gated on one node hides every subcommand from a player who
        // was given only another one.
        String yml = pluginYml();
        String block = yml.substring(yml.indexOf("  dialogengine:"), yml.indexOf("  dialogclick:"));
        assertEquals(-1, block.indexOf("\n    permission:"), "/dialogengine gates itself; each subcommand does that");
    }

    /** Every {@code "dialogengine.x"} string literal in the plugin's source. */
    private static Set<String> nodesInCode() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Pattern literal = Pattern.compile("\"(dialogengine\\.[a-z][a-z.]*[a-z])\"");
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = literal.matcher(Files.readString(file));
                while (m.find()) out.add(m.group(1));
            }
        }
        // permissionFor builds the subcommand nodes rather than spelling them.
        for (String sub : AdminCommand.SUBCOMMANDS) out.add(AdminCommand.permissionFor(sub));
        return out;
    }
}
