package games.jvrcruz.siliconcraft.menu.slot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class OutputSlot extends SlotItemHandler {
    private final Component emptyTooltip;

    public OutputSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition, Component emptyTooltip) {
        super(itemHandler, index, xPosition, yPosition);
        this.emptyTooltip = emptyTooltip;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    public Component getEmptyTooltip() {
        return emptyTooltip;
    }
}
