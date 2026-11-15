package ai.resourcepack.dialogs.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A plugin's own container, shown in a drawn dialog's item slots.
 *
 * <p>A dialog drawn in the browser editor can have a grid of slots set to show
 * a container "from a plugin" by a name: {@code shop}, {@code bank},
 * {@code quiver}. Register one under that name with
 * {@link DialogEngineAPI#registerContainer} and Dialog Engine shows what your
 * {@link #inventory} holds as the dialog opens, each item with its own
 * picture, count and tooltip. If the grid lets items move, a player moves them
 * between your container and their own inventory with two clicks, and the
 * change is made in the inventory you hand over.
 *
 * <p>The inventory is yours: it is read as each dialog opens and written as
 * each move happens, on the player's thread, and never kept. Keep it live for
 * as long as it can be shown. A bank whose inventory is loaded in
 * {@link #inventory} and saved in {@link #changed} is the usual shape. A move
 * reads both slots at that moment, never the picture the player clicked, so
 * nothing a stale screen asks for can duplicate an item.
 *
 * <pre>{@code
 * DialogEngine.api().registerContainer(this, "bank", new DialogContainer() {
 *     public Inventory inventory(Player viewer) { return banks.of(viewer); }
 *     public void changed(Player viewer) { banks.save(viewer); }
 * });
 * }</pre>
 */
public interface DialogContainer {

    /**
     * What {@code viewer} sees under this name, or null when they have none:
     * the grid then shows empty slots and its clicks do nothing.
     */
    Inventory inventory(Player viewer);

    /** Whether {@code viewer} may take {@code stack} out of {@code slot}. Every slot, by default. */
    default boolean mayTake(Player viewer, int slot, ItemStack stack) {
        return true;
    }

    /** Whether {@code viewer} may put {@code stack} in {@code slot}. Anything, by default. */
    default boolean mayPlace(Player viewer, int slot, ItemStack stack) {
        return true;
    }

    /** A move changed {@code viewer}'s inventory under this name. Called after it, for saving. */
    default void changed(Player viewer) {
    }
}
