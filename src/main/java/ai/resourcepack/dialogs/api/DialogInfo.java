package ai.resourcepack.dialogs.api;

import java.util.List;
import java.util.Optional;

/**
 * What a dialog is, as its definition says.
 *
 * @param id         {@code namespace:name}
 * @param source     the file it came from, or the plugin that registered it
 * @param command    the command players open it with, if any
 * @param permission what a player needs to open it themselves, if anything
 * @param args       the command's argument names, which become placeholders
 * @param inputs     its input keys
 */
public record DialogInfo(String id, String source, Optional<String> command, Optional<String> permission,
                         List<String> args, List<String> inputs) {
}
