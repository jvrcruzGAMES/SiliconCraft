package games.jvrcruz.siliconcraft.item;

import games.jvrcruz.siliconcraft.data.StorageData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class StorageItem extends Item {
    public StorageItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        StorageData data = stack.get(ModDataComponents.STORAGE_DATA.get());
        boolean nvme = data != null && data.isNvme();
        int cap = (data != null) ? data.capacity() : StorageData.DRIVE_CAPACITY_KB;
        if (nvme) {
            tooltip.add(Component.translatable("tooltip.siliconcraft.storage.nvme").withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("tooltip.siliconcraft.storage.standard").withStyle(ChatFormatting.GRAY));
        }
        String capStr = (cap >= 1024) ? (cap / 1024) + " MB" : cap + " KB";
        tooltip.add(Component.translatable("tooltip.siliconcraft.storage.capacity", capStr).withStyle(ChatFormatting.YELLOW));
        games.jvrcruz.siliconcraft.data.RomData romData = stack.get(ModDataComponents.ROM_DATA.get());
        if (romData != null && romData.bytecode().length > 0) {
            int instrCount = romData.getInstructionCount();
            tooltip.add(Component.translatable("tooltip.siliconcraft.rom.lines", instrCount).withStyle(ChatFormatting.LIGHT_PURPLE));
            List<String> preview = games.jvrcruz.siliconcraft.vm.BinaryBytecode.previewInstructions(romData.bytecode(), 3);
            for (int i = 0; i < preview.size(); i++) {
                tooltip.add(Component.literal("  " + (i + 1) + ": " + preview.get(i)).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (instrCount > 3) {
                tooltip.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    public static byte[] getStorageBytes(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof StorageItem)) {
            return new byte[0];
        }
        net.minecraft.world.item.component.CustomData customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData != null) {
            net.minecraft.nbt.CompoundTag tag = customData.copyTag();
            if (tag.contains("BinaryStorage", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
                return tag.getByteArray("BinaryStorage");
            }
        }
        return new byte[0];
    }

    public static @Nullable UUID getDriveId(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var tag = data.copyTag();
        return tag.hasUUID("DriveId") ? tag.getUUID("DriveId") : null;
    }

    public static void setDriveId(ItemStack stack, UUID id) {
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("DriveId", id);
            tag.remove("BinaryStorage");
        });
    }
}
