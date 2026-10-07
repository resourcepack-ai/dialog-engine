package ai.resourcepack.dialogs.api;

import org.bukkit.entity.Player;

/** Answers {@code {prefix_key}} placeholders. */
@FunctionalInterface
public interface PlaceholderResolver {

    /**
     * @param viewer  who the dialog is drawn for
     * @param context the dialog and its values ({@code context.get("target")} for a dialog about somebody)
     * @param key     the part after the prefix: {@code status} for {@code {myplugin_status}}
     * @return the value, or null to leave the placeholder as written
     */
    String resolve(Player viewer, DialogContext context, String key);
}
