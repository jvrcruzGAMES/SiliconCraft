package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.ChipData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ChipItem extends Item {
    public ChipItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ChipData data = stack.get(ModDataComponents.CHIP_DATA.get());
        if (data == null || data.instructions().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.chip.empty").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            String archName = (data.archName() != null && !data.archName().isEmpty()) ? data.archName() : "Custom-Arch";
            tooltip.add(Component.literal("Architecture: " + archName).withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("Instructions: " + data.instructions().size()).withStyle(ChatFormatting.GOLD));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
