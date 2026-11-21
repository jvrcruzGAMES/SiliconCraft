package games.jvrcruz.siliconcraft.menu.slot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.function.Predicate;

public class FilteredSlot extends SlotItemHandler {
    private final Predicate<ItemStack> filter;
    private final Component emptyTooltip;

    public FilteredSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition, Predicate<ItemStack> filter, Component emptyTooltip) {
        super(itemHandler, index, xPosition, yPosition);
        this.filter = filter;
        this.emptyTooltip = emptyTooltip;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return filter.test(stack);
    }

    public Component getEmptyTooltip() {
        return emptyTooltip;
    }
}
