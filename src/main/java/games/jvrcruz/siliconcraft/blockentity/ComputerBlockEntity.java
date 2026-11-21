package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.block.ComputerBlock;
import games.jvrcruz.siliconcraft.data.SocData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.network.SyncTerminalPacket;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class ComputerBlockEntity extends BlockEntity implements MenuProvider {
    private SocData socData = SocData.EMPTY;
    private final VirtualCpu cpu = new VirtualCpu();
    private List<String> lastSyncedOutput = Collections.emptyList();

    // Container data for syncing CPU registers:
    // 0: state (0=OFF, 1=RUNNING, 2=PAUSED, 3=HALTED, 4=ERROR)
    // 1: pc
    // 2: regA
    // 3: regB
    // 4: regC
    // 5: regD
    // 6: flagZ (0/1)
    // 7: flagS (0/1)
    // 8: cycles
    // 9: hasSoc (0/1)
    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> cpu.getState().ordinal();
                case 1 -> cpu.getPc();
                case 2 -> cpu.getRegA();
                case 3 -> cpu.getRegB();
                case 4 -> cpu.getRegC();
                case 5 -> cpu.getRegD();
                case 6 -> cpu.isFlagZ() ? 1 : 0;
                case 7 -> cpu.isFlagS() ? 1 : 0;
                case 8 -> cpu.getCycles();
                case 9 -> socData != null && !socData.instructions().isEmpty() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Read-only from container data side
        }

        @Override
        public int getCount() {
            return 10;
        }
    };

    public ComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.COMPUTER.get(), pos, blockState);
    }

    public SocData getSocData() {
        return socData;
    }

    public VirtualCpu getCpu() {
        return cpu;
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public void installSoc(SocData data) {
        this.socData = data;
        this.cpu.setTimeSupplier(() -> level != null ? level.getGameTime() : 0L);
        this.cpu.configure(
                new HashSet<>(data.instructions()),
                data.ramCapacity(),
                data.bios(),
                data.hasNvme(),
                data.storageCapacity()
        );
        setChanged();
    }

    public void removeSoc() {
        this.socData = SocData.EMPTY;
        this.cpu.powerOff();
        setChanged();
    }

    public void syncTerminal(Level level, BlockPos pos, BlockState state) {
        if (level == null || level.isClientSide) return;
        List<String> currentOutput = cpu.getTerminalOutput();
        if (!currentOutput.equals(lastSyncedOutput)) {
            lastSyncedOutput = new ArrayList<>(currentOutput);
            setChanged();
            level.sendBlockUpdated(pos, state, state, 2);
            if (level instanceof ServerLevel serverLevel) {
                PacketDistributor.sendToPlayersTrackingChunk(
                        serverLevel,
                        new ChunkPos(pos),
                        new SyncTerminalPacket(pos, currentOutput)
                );
            }
        }
    }

    public void togglePower() {
        if (socData == null || socData.instructions().isEmpty()) {
            cpu.powerOff();
            cpu.setTerminalOutput(List.of("[SYSTEM ERROR]", "No bootable SoC architecture installed!"));
            setChanged();
            if (level != null) {
                syncTerminal(level, worldPosition, getBlockState());
            }
            return;
        }

        this.cpu.setTimeSupplier(() -> level != null ? level.getGameTime() : 0L);
        cpu.togglePower();
        if (level != null) {
            boolean isRunning = (cpu.getState() == VirtualCpu.State.RUNNING);
            if (getBlockState().getValue(ComputerBlock.POWERED) != isRunning) {
                level.setBlock(worldPosition, getBlockState().setValue(ComputerBlock.POWERED, isRunning), 3);
            }
            syncTerminal(level, worldPosition, getBlockState());
        }
        setChanged();
    }

    public void resetCpu() {
        cpu.reset();
        setChanged();
        if (level != null) {
            syncTerminal(level, worldPosition, getBlockState());
        }
    }

    public void stepCpu() {
        cpu.step();
        setChanged();
        if (level != null) {
            syncTerminal(level, worldPosition, getBlockState());
        }
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        this.cpu.setTimeSupplier(level::getGameTime);
        if (cpu.getState() == VirtualCpu.State.RUNNING) {
            cpu.tick(10);
            if (!state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, true), 3);
            }
        } else {
            if (state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, false), 3);
            }
        }
        syncTerminal(level, pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (socData != null) {
            CompoundTag socTag = new CompoundTag();
            ListTag instTag = new ListTag();
            for (String s : socData.instructions()) instTag.add(StringTag.valueOf(s));
            socTag.put("Instructions", instTag);
            socTag.putInt("RamCapacity", socData.ramCapacity());
            ListTag biosTag = new ListTag();
            for (String s : socData.bios()) biosTag.add(StringTag.valueOf(s));
            socTag.put("Bios", biosTag);
            socTag.putBoolean("HasNvme", socData.hasNvme());
            socTag.putInt("StorageCapacity", socData.storageCapacity());
            tag.put("SocData", socTag);
        }

        ListTag terminalTag = new ListTag();
        for (String line : cpu.getTerminalOutput()) {
            terminalTag.add(StringTag.valueOf(line));
        }
        tag.put("TerminalOutput", terminalTag);
        tag.putInt("CpuState", cpu.getState().ordinal());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("SocData")) {
            CompoundTag socTag = tag.getCompound("SocData");
            List<String> instructions = new ArrayList<>();
            ListTag instTag = socTag.getList("Instructions", Tag.TAG_STRING);
            for (int i = 0; i < instTag.size(); i++) instructions.add(instTag.getString(i));

            int ramCap = socTag.getInt("RamCapacity");
            List<String> bios = new ArrayList<>();
            ListTag biosTag = socTag.getList("Bios", Tag.TAG_STRING);
            for (int i = 0; i < biosTag.size(); i++) bios.add(biosTag.getString(i));

            boolean hasNvme = socTag.getBoolean("HasNvme");
            int storageCap = socTag.getInt("StorageCapacity");

            installSoc(new SocData(instructions, ramCap, bios, hasNvme, storageCap));
        }

        if (tag.contains("TerminalOutput")) {
            List<String> lines = new ArrayList<>();
            ListTag terminalTag = tag.getList("TerminalOutput", Tag.TAG_STRING);
            for (int i = 0; i < terminalTag.size(); i++) {
                lines.add(terminalTag.getString(i));
            }
            cpu.setTerminalOutput(lines);
            lastSyncedOutput = new ArrayList<>(lines);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag, lookupProvider);
        }
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        loadAdditional(tag, lookupProvider);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siliconcraft.computer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ComputerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
