package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.GpuData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class GpuItem extends Item {
    public GpuItem(Properties properties) {
        super(properties);
    }

    public static GpuData getGpuData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return GpuData.EMPTY;
        GpuData data = stack.get(ModDataComponents.GPU_DATA.get());
        if (data != null && data.isPresent()) {
            return data;
        }
        return GpuData.DEFAULT;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GpuData data = getGpuData(stack);
        String archName = data.archName().isEmpty() ? "Dedicated GPU" : data.archName();
        tooltip.add(Component.translatable("tooltip.siliconcraft.gpu.arch", archName).withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.siliconcraft.gpu.vram", GpuData.formatVram(data.vramCapacity())).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.siliconcraft.gpu.cores", data.computeUnits()).withStyle(ChatFormatting.GREEN));
        if (!data.features().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.gpu.ops", data.features().size()).withStyle(ChatFormatting.GOLD));
        }
        tooltip.add(Component.translatable("tooltip.siliconcraft.gpu.limit").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
