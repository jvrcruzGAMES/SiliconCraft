package games.jvrcruz.siliconcraft.compat.jade;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public class SpeakerBlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    public static final SpeakerBlockProvider INSTANCE = new SpeakerBlockProvider();
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "speaker_provider");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        Level level = accessor.getLevel();
        BlockPos pos = accessor.getPosition();
        boolean connected = false;
        BlockPos computerPos = null;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (level.getBlockState(neighbor).is(ModBlocks.COMPUTER.get())) {
                connected = true;
                computerPos = neighbor;
                break;
            }
        }

        data.putBoolean("Connected", connected);
        if (connected && computerPos != null) {
            data.putInt("CompX", computerPos.getX());
            data.putInt("CompY", computerPos.getY());
            data.putInt("CompZ", computerPos.getZ());
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data.getBoolean("Connected")) {
            tooltip.add(Component.literal("Audio Output: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Connected to Computer").withStyle(ChatFormatting.GREEN)));
            tooltip.add(Component.literal("Connected PC: ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("[" + data.getInt("CompX") + ", " + data.getInt("CompY") + ", " + data.getInt("CompZ") + "]").withStyle(ChatFormatting.GRAY)));
        } else {
            tooltip.add(Component.literal("Audio Output: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Disconnected (Place next to Computer)").withStyle(ChatFormatting.RED)));
        }
    }
}
