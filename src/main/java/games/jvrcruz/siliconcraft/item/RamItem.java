package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.RamData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class RamItem extends Item {
    public RamItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RamData data = stack.get(ModDataComponents.RAM_DATA.get());
        int cap = (data != null) ? data.capacity() : 256;
        tooltip.add(Component.translatable("tooltip.siliconcraft.ram.capacity", cap).withStyle(ChatFormatting.GREEN));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
