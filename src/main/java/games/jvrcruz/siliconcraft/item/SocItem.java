package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.SocData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class SocItem extends Item {
    public SocItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        SocData data = stack.get(ModDataComponents.SOC_DATA.get());
        if (data == null || data.instructions().isEmpty()) {
            tooltip.add(Component.literal("Unconfigured SoC Architecture").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            tooltip.add(Component.translatable("tooltip.siliconcraft.soc.cpu", data.instructions().size()).withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("  " + String.join(", ", data.instructions())).withStyle(ChatFormatting.DARK_AQUA));
            String ramStr = (data.ramCapacity() >= 1024) ? (data.ramCapacity() / 1024) + " KB" : data.ramCapacity() + " Bytes";
            tooltip.add(Component.translatable("tooltip.siliconcraft.soc.ram", ramStr).withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.siliconcraft.soc.bios", data.bios().size()).withStyle(ChatFormatting.LIGHT_PURPLE));
            if (data.hasNvme()) {
                String capStr = (data.storageCapacity() >= 1024) ? (data.storageCapacity() / 1024) + " MB" : data.storageCapacity() + " KB";
                tooltip.add(Component.translatable("tooltip.siliconcraft.soc.nvme", capStr).withStyle(ChatFormatting.AQUA));
            } else if (data.storageCapacity() > 0) {
                String capStr = (data.storageCapacity() >= 1024) ? (data.storageCapacity() / 1024) + " MB" : data.storageCapacity() + " KB";
                tooltip.add(Component.translatable("tooltip.siliconcraft.soc.flash", capStr).withStyle(ChatFormatting.AQUA));
            } else {
                tooltip.add(Component.translatable("tooltip.siliconcraft.soc.no_storage").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
