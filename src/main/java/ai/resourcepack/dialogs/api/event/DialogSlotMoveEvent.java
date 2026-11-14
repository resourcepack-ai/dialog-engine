package ai.resourcepack.dialogs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A player is about to move a stack between the item slots of a drawn dialog.
 * Cancellable: the items stay where they are.
 *
 * <p>A drawn dialog can show the player's inventory, their ender chest, an
 * item's own storage (a backpack) or a container a plugin registered, in slots
 * they click: the first click picks a stack up, the second puts it down,
 * merging with the same item or swapping with a different one. This is asked
 * before the second click moves anything, so a rule of yours (a soulbound
 * item, a locked slot, a trade window) can say no.
 *
 * <p>Slots are named {@code <container>/<index>}: {@code inv/0} to
 * {@code inv/35} the player's inventory in Bukkit's numbering (0-8 the
 * hotbar), {@code ender/0} to {@code ender/26} their ender chest,
 * {@code item/N} the held item's storage, {@code player.<name>/N} and
 * {@code api.<name>/N} the containers plugins named. The stacks are copies;
 * changing them changes nothing.
 */
public final class DialogSlotMoveEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String dialogId;
    private final String from;
    private final String to;
    private final ItemStack moving;
    private final ItemStack onto;
    private boolean cancelled;

    public DialogSlotMoveEvent(Player player, String dialogId, String from, String to, ItemStack moving, ItemStack onto) {
        super(player);
        this.dialogId = dialogId;
        this.from = from;
        this.to = to;
        this.moving = moving;
        this.onto = onto;
    }

    /** {@code namespace:name} of the dialog it happens in. */
    public String getDialogId() {
        return dialogId;
    }

    /** The slot the stack was picked up from, as {@code <container>/<index>}. */
    public String getFrom() {
        return from;
    }

    /** The slot it is being put down on. */
    public String getTo() {
        return to;
    }

    /** A copy of the stack being moved. */
    public ItemStack getMoving() {
        return moving;
    }

    /** A copy of what is in the slot it goes on, or null for an empty slot. */
    public ItemStack getOnto() {
        return onto;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
