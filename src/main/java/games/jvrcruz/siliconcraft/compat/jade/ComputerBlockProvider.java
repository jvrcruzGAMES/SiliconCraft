package games.jvrcruz.siliconcraft.compat.jade;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public class ComputerBlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    public static final ComputerBlockProvider INSTANCE = new ComputerBlockProvider();
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "computer_provider");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof ComputerBlockEntity computer) {
            boolean hasSoc = computer.getSocData() != null && !computer.getSocData().instructions().isEmpty();
            data.putBoolean("HasSoc", hasSoc);
            if (hasSoc) {
                data.putInt("InstructionCount", computer.getSocData().instructions().size());
                data.putInt("BiosLines", computer.getSocData().bios().size());
            }
            data.putInt("CpuState", computer.getCpu().getState().ordinal());
            data.putInt("Cycles", computer.getCpu().getCycles());
            data.putInt("Ram", computer.getEffectiveRamCapacity());
            data.putInt("Storage", computer.getEffectiveStorageCapacity());
            data.putInt("Drives", computer.getCpu().getStorageDriveCount());
            data.putInt("DisplayMode", computer.getCpu().getDisplayMode());
            data.putBoolean("HasNvme", computer.hasEffectiveNvme());
            if (computer.getCpu().getState() == VirtualCpu.State.ERROR) {
                data.putString("ErrorMessage", computer.getCpu().getErrorMessage());
            }
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains("CpuState")) return;

        int stateOrd = data.getInt("CpuState");
        VirtualCpu.State[] states = VirtualCpu.State.values();
        VirtualCpu.State state = states[Math.max(0, Math.min(stateOrd, states.length - 1))];

        Component stateBadge = switch (state) {
            case OFF -> Component.literal("[OFF]").withStyle(ChatFormatting.GRAY);
            case RUNNING -> Component.literal("[RUNNING]").withStyle(ChatFormatting.GREEN);
            case PAUSED -> Component.literal("[PAUSED]").withStyle(ChatFormatting.YELLOW);
            case HALTED -> Component.literal("[HALTED]").withStyle(ChatFormatting.GOLD);
            case ERROR -> Component.literal("[ERROR]").withStyle(ChatFormatting.RED);
        };

        tooltip.add(Component.literal("Status: ").withStyle(ChatFormatting.GRAY).append(stateBadge));

        if (state == VirtualCpu.State.ERROR && data.contains("ErrorMessage") && !data.getString("ErrorMessage").isEmpty()) {
            tooltip.add(Component.literal(data.getString("ErrorMessage")).withStyle(ChatFormatting.RED));
        }

        if (data.getBoolean("HasSoc")) {
            int ops = data.getInt("InstructionCount");
            int bios = data.getInt("BiosLines");
            tooltip.add(Component.literal("SoC Synthesized: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(ops + " opcodes").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(bios > 0 ? ", " + bios + " lines ROM" : "").withStyle(ChatFormatting.DARK_AQUA)));
        } else {
            tooltip.add(Component.literal("No SoC Installed (Right-click with SoC)").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        int ram = data.getInt("Ram");
        int storage = data.getInt("Storage");
        int drives = data.getInt("Drives");
        boolean hasNvme = data.getBoolean("HasNvme");

        tooltip.add(Component.literal("RAM: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ram + " Bytes").withStyle(ChatFormatting.WHITE)));

        if (storage > 0 || drives > 0) {
            String storageType = hasNvme ? "NVMe" : "Flash";
            tooltip.add(Component.literal("Storage (" + storageType + "): ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(storage + " KB (" + drives + " Drive" + (drives != 1 ? "s" : "") + ")").withStyle(ChatFormatting.WHITE)));
        }

        if (state == VirtualCpu.State.RUNNING || state == VirtualCpu.State.HALTED || state == VirtualCpu.State.PAUSED) {
            tooltip.add(Component.literal("Cycles: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format("%,d", data.getInt("Cycles"))).withStyle(ChatFormatting.WHITE)));

            int displayMode = data.getInt("DisplayMode");
            tooltip.add(Component.literal("Display: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(displayMode == 1 ? "Framebuffer Direct Pixel" : "Text Terminal").withStyle(ChatFormatting.AQUA)));
        }
    }
}
