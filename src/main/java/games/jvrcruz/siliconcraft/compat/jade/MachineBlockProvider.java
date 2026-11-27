package games.jvrcruz.siliconcraft.compat.jade;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.*;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElementHelper;

public class MachineBlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    public static final MachineBlockProvider INSTANCE = new MachineBlockProvider();
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "machine_provider");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        BlockEntity be = accessor.getBlockEntity();
        if (be instanceof BaseMachineBlockEntity machine) {
            data.putInt("Energy", machine.getEnergyStorage().getEnergyStored());
            data.putInt("MaxEnergy", machine.getEnergyStorage().getMaxEnergyStored());
            data.putInt("BurnTime", machine.getBurnTime());
            data.putInt("MaxBurnTime", machine.getMaxBurnTime());
        }

        if (be instanceof ChipForgeBlockEntity forge) {
            data.putString("MachineType", "chip_forge");
            data.putInt("Progress", forge.getDataAccess().get(0));
            data.putInt("MaxProgress", forge.getDataAccess().get(1));
            data.putBoolean("IsWorking", forge.getDataAccess().get(6) != 0);
            ItemStack blueprint = forge.getItemHandler().getStackInSlot(3);
            ArchitectureData arch = blueprint.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (arch != null) {
                data.putString("ArchName", arch.name());
                data.putInt("ArchOps", arch.instructions().size());
            }
        } else if (be instanceof MemoryForgeBlockEntity memForge) {
            data.putString("MachineType", "memory_forge");
            data.putInt("Progress", memForge.getDataAccess().get(2));
            data.putInt("MaxProgress", memForge.getDataAccess().get(3));
            data.putBoolean("IsWorking", memForge.getDataAccess().get(8) != 0);
            data.putInt("Mode", memForge.getMode());
            data.putBoolean("Nvme", memForge.isNvmeEnabled());
        } else if (be instanceof SocManufacturerBlockEntity socMan) {
            data.putString("MachineType", "soc_manufacturer");
            data.putInt("Progress", socMan.getDataAccess().get(0));
            data.putInt("MaxProgress", socMan.getDataAccess().get(1));
            data.putBoolean("IsWorking", socMan.getDataAccess().get(6) != 0);
        } else if (be instanceof GpuAssemblerBlockEntity gpuAssembler) {
            data.putString("MachineType", "gpu_assembler");
            data.putInt("Progress", gpuAssembler.getDataAccess().get(0));
            data.putInt("MaxProgress", gpuAssembler.getDataAccess().get(1));
            data.putBoolean("IsWorking", gpuAssembler.getDataAccess().get(6) != 0);
            data.putInt("RamCount", gpuAssembler.getInstalledRamCount());
            data.putInt("TargetVramKb", gpuAssembler.getTotalVramBytes() / 1024);
            ArchitectureData arch = gpuAssembler.getInstalledArchitecture();
            if (arch != null) {
                data.putString("ArchName", arch.name());
                data.putInt("ArchOps", arch.instructions().size());
            }
        } else if (be instanceof ProjectCompilerBlockEntity projectCompiler) {
            data.putString("MachineType", "project_compiler");
            ArchitectureData arch = projectCompiler.getInstalledArchitecture();
            if (arch != null) {
                data.putString("ArchName", arch.name());
                data.putInt("ArchOps", arch.instructions().size());
            }
        } else if (be instanceof CodeCompilerBlockEntity codeCompiler) {
            data.putString("MachineType", "code_compiler");
            ItemStack writeup = codeCompiler.getItemHandler().getStackInSlot(0);
            ArchitectureData arch = writeup.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (arch != null) {
                data.putString("ArchName", arch.name());
                data.putInt("ArchOps", arch.instructions().size());
            }
        } else if (be instanceof ProgrammerBlockEntity programmer) {
            data.putString("MachineType", "programmer");
            ItemStack targetStack = programmer.getItemHandler().getStackInSlot(1);
            if (!targetStack.isEmpty()) {
                data.putString("TargetItem", targetStack.getHoverName().getString());
            }
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();

        // Energy Display
        if (data.contains("Energy") && data.contains("MaxEnergy")) {
            int energy = data.getInt("Energy");
            int maxEnergy = data.getInt("MaxEnergy");
            if (maxEnergy > 0) {
                tooltip.add(Component.literal("Energy: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format("%,d / %,d FE", energy, maxEnergy)).withStyle(ChatFormatting.GREEN)));
            }
        }

        // Fuel Burn Time
        if (data.contains("BurnTime") && data.getInt("BurnTime") > 0) {
            tooltip.add(Component.literal("Fuel Burn: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(data.getInt("BurnTime") + " ticks").withStyle(ChatFormatting.GOLD)));
        }

        // Machine-specific Details & Progress
        if (data.contains("MachineType")) {
            String machineType = data.getString("MachineType");
            boolean isWorking = data.getBoolean("IsWorking");

            if (isWorking && data.contains("Progress") && data.contains("MaxProgress")) {
                int progress = data.getInt("Progress");
                int maxProgress = Math.max(1, data.getInt("MaxProgress"));
                int percent = (int) Math.round(((double) progress / maxProgress) * 100.0);
                tooltip.add(IElementHelper.get().progress(
                        (float) progress / maxProgress,
                        Component.literal(percent + "%"),
                        IElementHelper.get().progressStyle(),
                        BoxStyle.getNestedBox(),
                        false
                ));
            }

            switch (machineType) {
                case "chip_forge" -> {
                    if (data.contains("ArchName")) {
                        tooltip.add(Component.literal("Blueprint: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(data.getString("ArchName")).withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(" (" + data.getInt("ArchOps") + " opcodes)").withStyle(ChatFormatting.DARK_AQUA)));
                    }
                    if (isWorking) {
                        tooltip.add(Component.literal("Forging microchip...").withStyle(ChatFormatting.YELLOW));
                    }
                }
                case "memory_forge" -> {
                    int mode = data.getInt("Mode");
                    String type = switch (mode) {
                        case 0 -> "RAM Module";
                        case 1 -> "ROM Module";
                        case 2 -> data.getBoolean("Nvme") ? "NVMe Storage Drive" : "Flash Storage Drive";
                        default -> "Memory";
                    };
                    tooltip.add(Component.literal("Target: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(type).withStyle(ChatFormatting.AQUA)));
                    if (isWorking) {
                        tooltip.add(Component.literal("Forging " + type + "...").withStyle(ChatFormatting.YELLOW));
                    }
                }
                case "gpu_assembler" -> {
                    if (data.contains("ArchName")) {
                        tooltip.add(Component.literal("Blueprint: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(data.getString("ArchName")).withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(" (" + data.getInt("ArchOps") + " GPU ops)").withStyle(ChatFormatting.DARK_AQUA)));
                    }
                    int ramCount = data.getInt("RamCount");
                    int vramKb = data.getInt("TargetVramKb");
                    tooltip.add(Component.literal("Target VRAM: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(vramKb + " KB (" + ramCount + "/8 RAM)").withStyle(ChatFormatting.AQUA)));
                    if (isWorking) {
                        tooltip.add(Component.literal("Assembling GPU...").withStyle(ChatFormatting.YELLOW));
                    }
                }
                case "soc_manufacturer" -> {
                    if (isWorking) {
                        tooltip.add(Component.literal("Synthesizing System-on-Chip...").withStyle(ChatFormatting.YELLOW));
                    }
                }
                case "project_compiler", "code_compiler" -> {
                    if (data.contains("ArchName")) {
                        tooltip.add(Component.literal("Target Arch: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(data.getString("ArchName")).withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(" (" + data.getInt("ArchOps") + " opcodes)").withStyle(ChatFormatting.DARK_AQUA)));
                    } else {
                        tooltip.add(Component.literal("No Architecture Installed").withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
                case "programmer" -> {
                    if (data.contains("TargetItem")) {
                        tooltip.add(Component.literal("Module: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(data.getString("TargetItem")).withStyle(ChatFormatting.WHITE)));
                    }
                }
            }
        }
    }
}
