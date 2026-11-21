package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.StorageData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class StorageItem extends Item {
    public StorageItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        StorageData data = stack.get(ModDataComponents.STORAGE_DATA.get());
        boolean nvme = data != null && data.isNvme();
        int cap = (data != null) ? data.capacity() : 512;
        if (nvme) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.storage.nvme").withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("tooltip.siliconcraft.storage.standard").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.siliconcraft.storage.capacity", cap).withStyle(ChatFormatting.YELLOW));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
