package ai.resourcepack.dialogs.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The item a dialog's body shows, handed over by a plugin.
 *
 * <p>A body entry written {@code item: api.<name>} asks the provider registered
 * under that name with {@link DialogEngineAPI#registerItem} for its item each
 * time the dialog is drawn, so it can be anything a plugin can make: a custom
 * item with its own components, a head with a texture, the item a shop is
 * selling. {@code item: api.<name>:<argument>} also hands over an argument,
 * with its placeholders filled first, which is how one provider answers every
 * row of a {@code for-each}: {@code item: "api.shop:{row.id}"}.
 *
 * <p>The entry's own {@code name:}, {@code lore:}, {@code amount:},
 * {@code model:}, {@code custom-model-data:} and {@code glow:}, where it has
 * them, are put over the item you return; {@code description:},
 * {@code width:}, {@code height:}, {@code tooltip:} and {@code decorations:}
 * work as they do for any item.
 *
 * <pre>{@code
 * DialogEngine.api().registerItem(this, "shop", (viewer, context, id) -> shop.icon(id));
 * }</pre>
 */
@FunctionalInterface
public interface ItemProvider {

    /**
     * @param viewer   who the dialog is drawn for
     * @param context  the dialog and its values
     * @param argument what the entry wrote after {@code api.<name>:}, placeholders filled, or {@code ""} when nothing
     * @return the item to show (it is copied, never kept), or null to leave the entry out of the dialog
     */
    ItemStack item(Player viewer, DialogContext context, String argument);
}
