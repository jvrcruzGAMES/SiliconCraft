package games.jvrcruz.siliconcraft.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public final class MemoryCardItem extends Item {
    public MemoryCardItem(Properties properties) { super(properties); }

    public static UUID owner(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.hasUUID("SiliconOwner") ? data.getUUID("SiliconOwner") : null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack card = player.getItemInHand(hand);
        if (!level.isClientSide && owner(card) == null) {
            var tag = new CompoundTag();
            tag.putUUID("SiliconOwner", player.getUUID());
            tag.putString("SiliconOwnerName", player.getGameProfile().getName());
            card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            player.displayClientMessage(Component.translatable("message.siliconcraft.memory_card.bound"), true);
        }
        return InteractionResultHolder.sidedSuccess(card, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.hasUUID("SiliconOwner")) tooltip.add(Component.literal(tag.getString("SiliconOwnerName")));
        else tooltip.add(Component.translatable("message.siliconcraft.memory_card.unbound"));
    }
}
