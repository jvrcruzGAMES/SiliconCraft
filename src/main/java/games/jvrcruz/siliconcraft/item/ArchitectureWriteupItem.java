package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ArchitectureWriteupItem extends Item {
    public ArchitectureWriteupItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
        if (data == null || (data.name().isEmpty() && data.instructions().isEmpty())) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.writeup.empty").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            String archName = data.name().isEmpty() ? "Custom-Arch" : data.name();
            tooltip.add(Component.literal("Architecture: " + archName).withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("Instructions (" + data.instructions().size() + "):").withStyle(ChatFormatting.GOLD));
            String joined = String.join(", ", data.instructions());
            tooltip.add(Component.literal("  " + joined).withStyle(ChatFormatting.AQUA));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
