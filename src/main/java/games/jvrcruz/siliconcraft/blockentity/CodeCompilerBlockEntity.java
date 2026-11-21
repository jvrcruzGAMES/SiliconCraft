package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.data.ArchitectureData;
import games.jvrcruz.siliconcraft.data.ProgramEntry;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.menu.CodeCompilerMenu;
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
import java.util.UUID;

public class CodeCompilerBlockEntity extends BlockEntity implements MenuProvider {
    private final ItemStackHandler itemHandler = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(ModItems.ARCHITECTURE_WRITEUP.get());
        }
    };

    public CodeCompilerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.CODE_COMPILER.get(), pos, blockState);
    }

    public ItemStackHandler getItemHandler() {
        return itemHandler;
    }

    public ArchitectureData getInstalledArchitecture() {
        ItemStack stack = itemHandler.getStackInSlot(0);
        if (!stack.isEmpty() && stack.is(ModItems.ARCHITECTURE_WRITEUP.get())) {
            ArchitectureData data = stack.get(ModDataComponents.ARCHITECTURE_DATA.get());
            if (data != null && !data.instructions().isEmpty()) {
                return data;
            }
        }
        return null;
    }

    public boolean compileAndAddProgram(ServerPlayer player, String programName, List<String> sourceLines) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            player.displayClientMessage(Component.literal("Source code is empty!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        ArchitectureData arch = getInstalledArchitecture();
        if (arch == null || arch.instructions().isEmpty()) {
            player.displayClientMessage(Component.literal("Cannot compile: Missing Architecture Writeup in slot!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        NCompiler.CompileResult result = NCompiler.compile(sourceLines);
        if (!result.success()) {
            String firstError = result.errors().isEmpty() ? "Unknown syntax error" : result.errors().get(0);
            player.displayClientMessage(Component.literal("Compile Error: " + firstError).withStyle(ChatFormatting.RED), false);
            return false;
        }

        List<String> missing = InstructionValidator.getMissingInstructions(result.requiredInstructions(), arch.instructions());
        if (!missing.isEmpty()) {
            String archName = arch.name().isEmpty() ? "Installed Arch" : arch.name();
            player.displayClientMessage(Component.literal("Cannot add program: Architecture '" + archName + "' is missing required instructions: " + missing).withStyle(ChatFormatting.RED), false);
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            String validName = (programName == null || programName.trim().isEmpty()) ? "compiled.asm" : programName.trim();
            if (!validName.endsWith(".asm") && !validName.endsWith(".s")) {
                validName += ".asm";
            }
            ProgramSavedData savedData = ProgramSavedData.get(serverLevel);
            ProgramEntry entry = new ProgramEntry(UUID.randomUUID().toString(), validName, arch.name(), result.assembly(), new ArrayList<>(result.requiredInstructions()));
            savedData.addProgram(entry);

            PacketDistributor.sendToAllPlayers(new SyncProgramsPacket(savedData.getPrograms()));

            level.playSound(null, worldPosition, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0f, 1.2f);
            player.displayClientMessage(Component.literal("Compiled and added '" + validName + "' to Assembly Library!").withStyle(ChatFormatting.GREEN), false);
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
        return Component.translatable("block.siliconcraft.code_compiler");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CodeCompilerMenu(containerId, playerInventory, this);
    }
}
