package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n1.NCompiler;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
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

        @Override
        public void setSize(int size) {
            super.setSize(Math.max(size, 1));
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
        return compileAndAddProgram(player, programName, sourceLines, 2);
    }

    public boolean compileAndAddProgram(ServerPlayer player, String programName, List<String> sourceLines, int standard) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            player.displayClientMessage(Component.literal("Source code is empty!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        ArchitectureData arch = getInstalledArchitecture();
        if (arch == null || arch.instructions().isEmpty()) {
            player.displayClientMessage(Component.literal("Cannot compile: Missing Architecture Writeup in slot!").withStyle(ChatFormatting.RED), false);
            return false;
        }

        List<String> assembly;
        boolean success;
        List<String> errors;

        if (standard == 1) {
            NCompiler.CompileResult result = NCompiler.compile(sourceLines);
            success = result.success();
            assembly = result.assembly();
            errors = result.errors();
        } else {
            N2Compiler.CompileResult result = N2Compiler.compile(sourceLines);
            success = result.success();
            assembly = result.assembly();
            errors = result.errors();
        }

        if (!success) {
            String firstError = (errors == null || errors.isEmpty()) ? "Unknown syntax error" : errors.get(0);
            player.displayClientMessage(Component.literal("Compile Error: " + firstError).withStyle(ChatFormatting.RED), false);
            return false;
        }

        Assembler.AssemblerResult asmResult = Assembler.assemble(assembly);
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

        if (level instanceof ServerLevel serverLevel) {
            String baseName = (programName == null || programName.trim().isEmpty()) ? "compiled" : programName.trim();
            if (baseName.endsWith(".n2")) baseName = baseName.substring(0, baseName.length() - 3);
            else if (baseName.endsWith(".n")) baseName = baseName.substring(0, baseName.length() - 2);
            else if (baseName.endsWith(".asm")) baseName = baseName.substring(0, baseName.length() - 4);
            else if (baseName.endsWith(".s")) baseName = baseName.substring(0, baseName.length() - 2);
            else if (baseName.endsWith(".o")) baseName = baseName.substring(0, baseName.length() - 2);
            String validName = baseName + ".o";

            ProgramSavedData savedData = ProgramSavedData.get(serverLevel);
            ProgramEntry entry = new ProgramEntry(UUID.randomUUID().toString(), validName, arch.name(), asmResult.bytecode(), new ArrayList<>(asmResult.requiredInstructions()));
            savedData.addProgram(entry);

            PacketDistributor.sendToAllPlayers(new SyncProgramsPacket(savedData.getPrograms()));

            level.playSound(null, worldPosition, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0f, 1.2f);
            player.displayClientMessage(Component.literal("Compiled, assembled and added '" + validName + "' to Assembly Library!").withStyle(ChatFormatting.GREEN), false);
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
