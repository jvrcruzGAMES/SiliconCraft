package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.block.ComputerBlock;
import games.jvrcruz.siliconcraft.data.SocData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.SyncFramebufferPacket;
import games.jvrcruz.siliconcraft.network.SyncTerminalPacket;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
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
import games.jvrcruz.siliconcraft.data.RamData;
import games.jvrcruz.siliconcraft.data.RomData;
import games.jvrcruz.siliconcraft.data.StorageData;
import games.jvrcruz.siliconcraft.init.ModDataComponents;
import games.jvrcruz.siliconcraft.init.ModItems;
import games.jvrcruz.siliconcraft.item.StorageItem;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class ComputerBlockEntity extends BlockEntity implements MenuProvider {
    private SocData socData = SocData.EMPTY;
    private final VirtualCpu cpu = new VirtualCpu();
    private List<String> lastSyncedOutput = Collections.emptyList();
    private byte[] onboardStorage = new byte[0];

    // Inventory: 6 slots (0-1: NVMe storage drives max 1 each; 2-5: RAM modules max 1 each)
    private final ItemStackHandler itemHandler = new ItemStackHandler(6) {
        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == 0 || slot == 1) {
                return stack.is(ModItems.STORAGE.get());
            } else if (slot >= 2 && slot <= 5) {
                return stack.is(ModItems.RAM.get());
            }
            return false;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            recalculateHardwareCapabilities();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    };

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
    // 10: totalRam (effective)
    // 11: hasNvme (effective 0/1)
    // 12: totalStorage (effective)
    // 13: socRam (base bundled in SoC)
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
                case 10 -> getEffectiveRamCapacity();
                case 11 -> hasEffectiveNvme() ? 1 : 0;
                case 12 -> getEffectiveStorageCapacity();
                case 13 -> socData != null ? socData.ramCapacity() : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Read-only from container data side
        }

        @Override
        public int getCount() {
            return 14;
        }
    };

    public ComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.COMPUTER.get(), pos, blockState);
        this.cpu.setDriveWriteListener((drive, data) -> {
            if (drive == 1) {
                ItemStack stack = itemHandler.getStackInSlot(0);
                if (!stack.isEmpty() && stack.is(ModItems.STORAGE.get())) {
                    StorageItem.setStorageBytes(stack, data);
                    setChanged();
                }
            } else if (drive == 2) {
                ItemStack stack = itemHandler.getStackInSlot(1);
                if (!stack.isEmpty() && stack.is(ModItems.STORAGE.get())) {
                    StorageItem.setStorageBytes(stack, data);
                    setChanged();
                }
            } else if (drive == 0) {
                onboardStorage = data;
                setChanged();
            }
        });
    }

    public ItemStackHandler getItemHandler() {
        return itemHandler;
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

    public int getEffectiveRamCapacity() {
        int ram = (socData != null ? socData.ramCapacity() : 0);
        for (int i = 2; i <= 5; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.RAM.get())) {
                RamData rd = stack.get(ModDataComponents.RAM_DATA.get());
                ram += (rd != null ? rd.capacity() : 256);
            }
        }
        return ram;
    }

    public boolean hasEffectiveNvme() {
        if (socData != null && socData.hasNvme()) return true;
        for (int i = 0; i <= 1; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.STORAGE.get())) {
                StorageData sd = stack.get(ModDataComponents.STORAGE_DATA.get());
                if (sd != null && sd.isNvme()) return true;
            }
        }
        return false;
    }

    public int getEffectiveStorageCapacity() {
        int cap = (socData != null ? socData.storageCapacity() : 0);
        for (int i = 0; i <= 1; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.STORAGE.get())) {
                StorageData sd = stack.get(ModDataComponents.STORAGE_DATA.get());
                if (sd != null) {
                    cap += sd.capacity();
                }
            }
        }
        return cap;
    }

    public void recalculateHardwareCapabilities() {
        cpu.updateHardware(getEffectiveRamCapacity(), hasEffectiveNvme(), getEffectiveStorageCapacity());

        // Sync Drive 0: Onboard SoC Flash
        if (socData != null && socData.storageCapacity() > 0) {
            int targetBytes = (int) Math.min(16 * 1024 * 1024L, (long) socData.storageCapacity() * 1024L);
            if (onboardStorage == null || onboardStorage.length != targetBytes) {
                byte[] newSt = new byte[targetBytes];
                if (onboardStorage != null) {
                    System.arraycopy(onboardStorage, 0, newSt, 0, Math.min(onboardStorage.length, targetBytes));
                }
                onboardStorage = newSt;
            }
            cpu.setStorageDrive(0, onboardStorage);
        } else {
            cpu.setStorageDrive(0, new byte[0]);
        }

        // Sync Drive 1: Storage Slot 1 (itemHandler slot 0)
        ItemStack slot0 = itemHandler.getStackInSlot(0);
        if (!slot0.isEmpty() && slot0.is(ModItems.STORAGE.get())) {
            cpu.setStorageDrive(1, StorageItem.getStorageBytes(slot0));
            RomData rd = slot0.get(ModDataComponents.ROM_DATA.get());
            cpu.setDriveProgram(1, rd != null ? rd.lines() : Collections.emptyList());
        } else {
            cpu.setStorageDrive(1, new byte[0]);
            cpu.setDriveProgram(1, Collections.emptyList());
        }

        // Sync Drive 2: Storage Slot 2 (itemHandler slot 1)
        ItemStack slot1 = itemHandler.getStackInSlot(1);
        if (!slot1.isEmpty() && slot1.is(ModItems.STORAGE.get())) {
            cpu.setStorageDrive(2, StorageItem.getStorageBytes(slot1));
            RomData rd = slot1.get(ModDataComponents.ROM_DATA.get());
            cpu.setDriveProgram(2, rd != null ? rd.lines() : Collections.emptyList());
        } else {
            cpu.setStorageDrive(2, new byte[0]);
            cpu.setDriveProgram(2, Collections.emptyList());
        }
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
                itemHandler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    public void installSoc(SocData data) {
        this.socData = data;
        this.cpu.setTimeSupplier(() -> level != null ? level.getGameTime() : 0L);
        this.cpu.configure(
                new HashSet<>(data.instructions()),
                getEffectiveRamCapacity(),
                data.bios(),
                hasEffectiveNvme(),
                getEffectiveStorageCapacity()
        );
        recalculateHardwareCapabilities();
        setChanged();
    }

    public void removeSoc() {
        this.socData = SocData.EMPTY;
        this.cpu.powerOff();
        this.onboardStorage = new byte[0];
        recalculateHardwareCapabilities();
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
        syncDisplayAndAudio(level, pos);
    }

    private void syncDisplayAndAudio(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;

        // Sync Framebuffer if dirty to any players looking at this screen
        if (cpu.isFbDirty()) {
            cpu.setFbDirty(false);
            byte[] compressedFb = null;
            for (ServerPlayer player : serverLevel.players()) {
                if (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this) {
                    if (compressedFb == null) {
                        compressedFb = SyncFramebufferPacket.compress(cpu.getFramebuffer());
                    }
                    PacketDistributor.sendToPlayer(player, new SyncFramebufferPacket(pos, cpu.getDisplayMode(), compressedFb));
                }
            }
        }

        // Process audio buffer
        byte[] audioSamples = cpu.pollAudioSamples();
        if (audioSamples != null && audioSamples.length > 0) {
            // Check for adjacent Speaker block
            BlockPos speakerPos = null;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (level.getBlockState(neighbor).is(ModBlocks.SPEAKER.get())) {
                    speakerPos = neighbor;
                    break;
                }
            }

            if (speakerPos != null) {
                // Broadcast in the world at speaker position
                PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(speakerPos),
                        new PlayAudioPacket(speakerPos, audioSamples, 8000, true));
            } else {
                // Audio plays only on computer screen by default (for players viewing this computer)
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this) {
                        PacketDistributor.sendToPlayer(player, new PlayAudioPacket(pos, audioSamples, 8000, false));
                    }
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", itemHandler.serializeNBT(registries));
        tag.putInt("DisplayMode", cpu.getDisplayMode());
        tag.putIntArray("Framebuffer", cpu.getFramebuffer());
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
        if (onboardStorage != null && onboardStorage.length > 0) {
            tag.putByteArray("OnboardStorage", onboardStorage);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
        }

        if (tag.contains("OnboardStorage", Tag.TAG_BYTE_ARRAY)) {
            onboardStorage = tag.getByteArray("OnboardStorage");
        } else if (tag.contains("BinaryStorage", Tag.TAG_BYTE_ARRAY)) {
            onboardStorage = tag.getByteArray("BinaryStorage");
        }

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
        } else {
            recalculateHardwareCapabilities();
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

        if (tag.contains("DisplayMode")) {
            cpu.setDisplayMode(tag.getInt("DisplayMode"));
        }
        if (tag.contains("Framebuffer", Tag.TAG_INT_ARRAY)) {
            int[] fb = tag.getIntArray("Framebuffer");
            if (fb.length == cpu.getFramebuffer().length) {
                System.arraycopy(fb, 0, cpu.getFramebuffer(), 0, fb.length);
                cpu.setFbDirty(true);
            }
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
        if (player instanceof ServerPlayer serverPlayer) {
            byte[] compressed = SyncFramebufferPacket.compress(cpu.getFramebuffer());
            PacketDistributor.sendToPlayer(serverPlayer, new SyncFramebufferPacket(worldPosition, cpu.getDisplayMode(), compressed));
        }
        return new ComputerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
