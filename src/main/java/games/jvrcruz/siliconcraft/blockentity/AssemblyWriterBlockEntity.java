package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.AssemblyWriterMenu;
import games.jvrcruz.siliconcraft.network.SyncProgramsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AssemblyWriterBlockEntity extends BlockEntity implements MenuProvider {
    private final ItemStackHandler itemHandler = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return games.jvrcruz.siliconcraft.item.ArchitectureWriteupItem.isCpuWriteup(stack);
        }

        @Override
        public void setSize(int size) {
            super.setSize(Math.max(size, 1));
        }
    };

    public AssemblyWriterBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.ASSEMBLY_WRITER.get(), pos, blockState);
    }

    public ItemStackHandler getItemHandler() {
        return itemHandler;
    }

    public ArchitectureData getInstalledArchitecture() {
        ItemStack stack = itemHandler.getStackInSlot(0);
        if (!stack.isEmpty() && stack.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
            ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (data != null && !data.gpu() && !data.instructions().isEmpty()) {
                return data;
            }
        }
        return null;
    }

    public boolean addAssemblyProgram(ServerPlayer player, String programName, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            player.displayClientMessage(Component.literal("Assembly program is empty!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        ArchitectureData arch = getInstalledArchitecture();
        if (arch == null || arch.instructions().isEmpty()) {
            player.displayClientMessage(Component.literal("Cannot add program: Missing Architecture Writeup in slot!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        Assembler.AssemblerResult asmResult = Assembler.assemble(lines);
        if (!asmResult.success()) {
            String firstError = asmResult.errors().isEmpty() ? "Unknown assembly error" : asmResult.errors().get(0);
            player.displayClientMessage(Component.literal("Assemble Error: " + firstError).withStyle(ChatFormatting.RED), false);
            return false;
        }

        List<String> missing = InstructionValidator.getMissingInstructions(asmResult.requiredInstructions(), arch.instructions());

        if (!missing.isEmpty()) {
            String archName = arch.name().isEmpty() ? "Installed Arch" : arch.name();
            player.displayClientMessage(Component.literal("Cannot add program: Architecture '" + archName + "' is missing required instructions: " + missing).withStyle(ChatFormatting.RED), false);
            return false;
        }

        // Add to persistent world programs library
        if (level instanceof ServerLevel serverLevel) {
            String baseName = (programName == null || programName.trim().isEmpty()) ? "program" : programName.trim();
            if (baseName.endsWith(".asm")) baseName = baseName.substring(0, baseName.length() - 4);
            else if (baseName.endsWith(".s")) baseName = baseName.substring(0, baseName.length() - 2);
            else if (baseName.endsWith(".n")) baseName = baseName.substring(0, baseName.length() - 2);
            else if (baseName.endsWith(".o")) baseName = baseName.substring(0, baseName.length() - 2);
            String validName = baseName + ".o";

            ProgramSavedData savedData = ProgramSavedData.get(serverLevel);
            ProgramEntry entry = new ProgramEntry(UUID.randomUUID().toString(), validName, arch.name(), asmResult.bytecode(), new ArrayList<>(asmResult.requiredInstructions()));
            savedData.addProgram(entry);

            // Sync updated library to all players on server
            PacketDistributor.sendToAllPlayers(new SyncProgramsPacket(savedData.getPrograms()));

            level.playSound(null, worldPosition, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0f, 1.0f);
            player.displayClientMessage(Component.literal("Assembled and added '" + validName + "' to Assembly Library!").withStyle(ChatFormatting.GREEN), false);
            return true;
        }

        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.assembly_writer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AssemblyWriterMenu(containerId, playerInventory, this);
    }
}
