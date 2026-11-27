package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
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

    public static boolean isCpuWriteup(ItemStack stack) {
        if (!stack.is(ModItems.ARCHITECTURE_WRITEUP.get())) return false;
        ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
        return data != null && !data.gpu() && !data.instructions().isEmpty();
    }

    public static boolean isGpuWriteup(ItemStack stack) {
        if (!stack.is(ModItems.ARCHITECTURE_WRITEUP.get())) return false;
        ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
        return data != null && data.gpu() && !data.instructions().isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
        if (data == null || (data.name().isEmpty() && data.instructions().isEmpty())) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.writeup.empty").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            String defaultName = data.gpu() ? "Custom-GPU" : "Custom-Arch";
            String archName = (data.name() != null && !data.name().isEmpty()) ? data.name() : defaultName;
            tooltip.add(Component.literal("Mode: " + (data.gpu() ? "GPU" : "CPU"))
                    .withStyle(data.gpu() ? ChatFormatting.AQUA : ChatFormatting.GREEN));
            tooltip.add(Component.literal("Architecture: " + archName).withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal((data.gpu() ? "GPU Ops: " : "Instructions: ") + data.instructions().size())
                    .withStyle(ChatFormatting.GOLD));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
