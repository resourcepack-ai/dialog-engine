package ai.resourcepack.dialogs.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** The rows a {@code for-each:} repeats over. */
@FunctionalInterface
public interface ListProvider {

    /**
     * @return one map per row; its keys are read as {@code {row.key}}. Values
     *         are MiniMessage where they are printed, so escape player text.
     */
    List<Map<String, String>> rows(Player viewer, DialogContext context);
}
