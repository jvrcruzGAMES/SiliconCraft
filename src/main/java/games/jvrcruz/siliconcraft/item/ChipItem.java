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
            if (data.archName() != null && !data.archName().isEmpty()) {
                tooltip.add(Component.literal("Architecture: " + data.archName()).withStyle(ChatFormatting.YELLOW));
            }
            tooltip.add(Component.translatable("tooltip.siliconcraft.chip.instructions").withStyle(ChatFormatting.GOLD));
            String joined = String.join(", ", data.instructions());
            tooltip.add(Component.literal("  " + joined).withStyle(ChatFormatting.AQUA));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
