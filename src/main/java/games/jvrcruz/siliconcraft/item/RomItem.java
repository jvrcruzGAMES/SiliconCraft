package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.RomData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class RomItem extends Item {
    public RomItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RomData data = stack.get(ModDataComponents.ROM_DATA.get());
        if (data == null || data.lines().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.rom.empty").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            tooltip.add(Component.translatable("tooltip.siliconcraft.rom.lines", data.lines().size()).withStyle(ChatFormatting.LIGHT_PURPLE));
            int show = Math.min(data.lines().size(), 3);
            for (int i = 0; i < show; i++) {
                tooltip.add(Component.literal("  " + (i + 1) + ": " + data.lines().get(i)).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (data.lines().size() > 3) {
                tooltip.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
