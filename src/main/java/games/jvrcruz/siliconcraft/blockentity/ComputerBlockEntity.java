package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.network.StopAudioPacket;

import games.jvrcruz.siliconcraft.block.ComputerBlock;
import games.jvrcruz.siliconcraft.data.SocData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.SyncFramebufferPacket;
import games.jvrcruz.siliconcraft.network.SyncTerminalPacket;
import games.jvrcruz.siliconcraft.vm.ComputerHost;
import games.jvrcruz.siliconcraft.vm.ComputerThreadManager;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ComputerBlockEntity extends BlockEntity implements MenuProvider, ComputerHost {
    private SocData socData = SocData.EMPTY;
    private final VirtualCpu cpu = new VirtualCpu();
    private List<String> lastSyncedOutput = Collections.emptyList();
    private byte[] onboardStorage = new byte[0];
    private volatile long lastGameTime = 0L;

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
        this.cpu.setTimeSupplier(() -> lastGameTime);
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
                ram += (rd != null ? rd.capacity() : 1024 * 1024);
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
            long capBytes = (long) socData.storageCapacity() * 1024L;
            cpu.setDriveCapacity(0, (int) Math.min(Integer.MAX_VALUE, capBytes));
            cpu.setStorageDrive(0, onboardStorage != null ? onboardStorage : new byte[0]);
        } else {
            cpu.setDriveCapacity(0, 0);
            cpu.setStorageDrive(0, new byte[0]);
        }

        // Sync Drive 1: Storage Slot 1 (itemHandler slot 0)
        ItemStack slot0 = itemHandler.getStackInSlot(0);
        if (!slot0.isEmpty() && slot0.is(ModItems.STORAGE.get())) {
            StorageData sd = slot0.get(ModDataComponents.STORAGE_DATA.get());
            int capKb = (sd != null ? sd.capacity() : 1024 * 1024);
            cpu.setDriveCapacity(1, (int) Math.min(Integer.MAX_VALUE, (long) capKb * 1024L));
            cpu.setStorageDrive(1, StorageItem.getStorageBytes(slot0));
            RomData rd = slot0.get(ModDataComponents.ROM_DATA.get());
            cpu.setDriveProgram(1, rd != null ? rd.lines() : Collections.emptyList());
        } else {
            cpu.setDriveCapacity(1, 0);
            cpu.setStorageDrive(1, new byte[0]);
            cpu.setDriveProgram(1, Collections.emptyList());
        }

        // Sync Drive 2: Storage Slot 2 (itemHandler slot 1)
        ItemStack slot1 = itemHandler.getStackInSlot(1);
        if (!slot1.isEmpty() && slot1.is(ModItems.STORAGE.get())) {
            StorageData sd = slot1.get(ModDataComponents.STORAGE_DATA.get());
            int capKb = (sd != null ? sd.capacity() : 1024 * 1024);
            cpu.setDriveCapacity(2, (int) Math.min(Integer.MAX_VALUE, (long) capKb * 1024L));
            cpu.setStorageDrive(2, StorageItem.getStorageBytes(slot1));
            RomData rd = slot1.get(ModDataComponents.ROM_DATA.get());
            cpu.setDriveProgram(2, rd != null ? rd.lines() : Collections.emptyList());
        } else {
            cpu.setDriveCapacity(2, 0);
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
        stopAudio();
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
            stopAudio();
            cpu.setTerminalOutput(List.of("[SYSTEM ERROR]", "No bootable SoC architecture installed!"));
            setChanged();
            if (level != null) {
                syncTerminal(level, worldPosition, getBlockState());
            }
            return;
        }

        cpu.togglePower();
        if (cpu.getState() == VirtualCpu.State.RUNNING) {
            ComputerThreadManager.register(this);
        } else {
            ComputerThreadManager.unregister(this);
            stopAudio();
        }

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


    public void stopAudio() {
        synchronized (cpu.getExecutionLock()) {
            cpu.clearAudioQueue();
        }
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;

        BlockPos speakerPos = null;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = worldPosition.relative(dir);
            if (level.getBlockState(neighbor).is(ModBlocks.SPEAKER.get())) {
                speakerPos = neighbor;
                break;
            }
        }

        StopAudioPacket packet = new StopAudioPacket(worldPosition);

        for (ServerPlayer player : serverLevel.players()) {
            if (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }

        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition), packet);
        if (speakerPos != null) {
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(speakerPos), packet);
            double maxDistSq = 36.0 * 36.0;
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(speakerPos.getX() + 0.5, speakerPos.getY() + 0.5, speakerPos.getZ() + 0.5) <= maxDistSq) {
                    PacketDistributor.sendToPlayer(player, packet);
                }
            }
        }
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        this.lastGameTime = level.getGameTime();
        if (cpu.getState() == VirtualCpu.State.RUNNING) {
            ComputerThreadManager.register(this);
            if (!state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, true), 3);
            }
        } else {
            ComputerThreadManager.unregister(this);
            if (state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, false), 3);
                stopAudio();
            }
        }
        syncTerminal(level, pos, state);
        syncDisplayAndAudio(level, pos);
    }

    public void tickAsyncCpu(int cycles) {
        if (cpu.getState() != VirtualCpu.State.RUNNING) {
            return;
        }
        cpu.tick(cycles);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            if (cpu.getState() == VirtualCpu.State.RUNNING) {
                ComputerThreadManager.register(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ComputerThreadManager.unregister(this);
        stopAudio();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        ComputerThreadManager.unregister(this);
        stopAudio();
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
                // Speaker connected: broadcast to nearby players (within 32 blocks) and anyone with screen open
                Set<ServerPlayer> targets = new HashSet<>();
                double maxDistSq = 32.0 * 32.0;
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceToSqr(speakerPos.getX() + 0.5, speakerPos.getY() + 0.5, speakerPos.getZ() + 0.5) <= maxDistSq) {
                        targets.add(player);
                    } else if (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this) {
                        targets.add(player);
                    }
                }
                for (ServerPlayer player : targets) {
                    PacketDistributor.sendToPlayer(player, new PlayAudioPacket(pos, speakerPos, audioSamples, 8000, true));
                }
            } else {
                // No speaker: Audio plays ONLY on computer screen (players with screen open)
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this) {
                        PacketDistributor.sendToPlayer(player, new PlayAudioPacket(pos, pos, audioSamples, 8000, false));
                    }
                }
            }
        }
    }

    private void saveCpuStateToNbt(CompoundTag cpuTag) {
        cpuTag.putInt("State", cpu.getState().ordinal());
        cpuTag.putInt("Pc", cpu.getPc());
        cpuTag.putInt("RegA", cpu.getRegA());
        cpuTag.putInt("RegB", cpu.getRegB());
        cpuTag.putInt("RegC", cpu.getRegC());
        cpuTag.putInt("RegD", cpu.getRegD());
        cpuTag.putBoolean("FlagZ", cpu.isFlagZ());
        cpuTag.putBoolean("FlagS", cpu.isFlagS());
        cpuTag.putInt("Cycles", cpu.getCycles());
        cpuTag.putString("ErrorMessage", cpu.getErrorMessage() != null ? cpu.getErrorMessage() : "");
        cpuTag.putBoolean("InterruptsEnabled", cpu.isInterruptsEnabled());
        cpuTag.putBoolean("InInterruptHandler", cpu.isInInterruptHandler());
        cpuTag.putInt("TimerHz", cpu.getTimerHz());
        cpuTag.putInt("TimerIntervalCycles", cpu.getTimerIntervalCycles());
        cpuTag.putInt("SleepTicksRemaining", cpu.getSleepTicksRemaining());

        ListTag callStackTag = new ListTag();
        for (int v : cpu.getCallStack()) {
            CompoundTag elem = new CompoundTag();
            elem.putInt("V", v);
            callStackTag.add(elem);
        }
        cpuTag.put("CallStack", callStackTag);

        ListTag dataStackTag = new ListTag();
        for (int v : cpu.getDataStack()) {
            CompoundTag elem = new CompoundTag();
            elem.putInt("V", v);
            dataStackTag.add(elem);
        }
        cpuTag.put("DataStack", dataStackTag);

        ListTag intStackTag = new ListTag();
        for (VirtualCpu.CpuContext ctx : cpu.getInterruptStack()) {
            CompoundTag ctxTag = new CompoundTag();
            ctxTag.putInt("Pc", ctx.pc());
            ctxTag.putInt("RegA", ctx.regA());
            ctxTag.putInt("RegB", ctx.regB());
            ctxTag.putInt("RegC", ctx.regC());
            ctxTag.putInt("RegD", ctx.regD());
            ctxTag.putBoolean("FlagZ", ctx.flagZ());
            ctxTag.putBoolean("FlagS", ctx.flagS());
            ctxTag.putBoolean("RestoreRegs", ctx.restoreRegs());
            intStackTag.add(ctxTag);
        }
        cpuTag.put("InterruptStack", intStackTag);

        CompoundTag ivtTag = new CompoundTag();
        for (Map.Entry<Integer, Integer> entry : cpu.getIvt().entrySet()) {
            ivtTag.putInt(String.valueOf(entry.getKey()), entry.getValue());
        }
        cpuTag.put("IVT", ivtTag);

        ListTag progTag = new ListTag();
        for (String line : cpu.getProgram()) {
            progTag.add(StringTag.valueOf(line));
        }
        cpuTag.put("Program", progTag);

        byte[] compressedRam = cpu.getCompressedRam();
        if (compressedRam != null && compressedRam.length > 0) {
            cpuTag.putByteArray("Ram", compressedRam);
        }
    }

    private void loadCpuStateFromNbt(CompoundTag cpuTag) {
        if (cpuTag.contains("Program")) {
            List<String> prog = new ArrayList<>();
            ListTag progTag = cpuTag.getList("Program", Tag.TAG_STRING);
            for (int i = 0; i < progTag.size(); i++) {
                prog.add(progTag.getString(i));
            }
            cpu.loadProgramImage(prog);
        }

        if (cpuTag.contains("Ram", Tag.TAG_BYTE_ARRAY)) {
            cpu.loadCompressedRam(cpuTag.getByteArray("Ram"));
        }

        if (cpuTag.contains("Pc")) cpu.setPc(cpuTag.getInt("Pc"));
        if (cpuTag.contains("RegA")) cpu.setRegA(cpuTag.getInt("RegA"));
        if (cpuTag.contains("RegB")) cpu.setRegB(cpuTag.getInt("RegB"));
        if (cpuTag.contains("RegC")) cpu.setRegC(cpuTag.getInt("RegC"));
        if (cpuTag.contains("RegD")) cpu.setRegD(cpuTag.getInt("RegD"));
        if (cpuTag.contains("FlagZ")) cpu.setFlagZ(cpuTag.getBoolean("FlagZ"));
        if (cpuTag.contains("FlagS")) cpu.setFlagS(cpuTag.getBoolean("FlagS"));
        if (cpuTag.contains("Cycles")) cpu.setCycles(cpuTag.getInt("Cycles"));
        if (cpuTag.contains("ErrorMessage")) cpu.setErrorMessage(cpuTag.getString("ErrorMessage"));
        if (cpuTag.contains("InterruptsEnabled")) cpu.setInterruptsEnabled(cpuTag.getBoolean("InterruptsEnabled"));
        if (cpuTag.contains("InInterruptHandler")) cpu.setInInterruptHandler(cpuTag.getBoolean("InInterruptHandler"));
        if (cpuTag.contains("TimerHz")) cpu.setTimerHz(cpuTag.getInt("TimerHz"));
        if (cpuTag.contains("TimerIntervalCycles")) cpu.setTimerIntervalCycles(cpuTag.getInt("TimerIntervalCycles"));
        if (cpuTag.contains("SleepTicksRemaining")) cpu.setSleepTicksRemaining(cpuTag.getInt("SleepTicksRemaining"));

        if (cpuTag.contains("CallStack")) {
            List<Integer> list = new ArrayList<>();
            ListTag callTag = cpuTag.getList("CallStack", Tag.TAG_COMPOUND);
            for (int i = 0; i < callTag.size(); i++) {
                list.add(callTag.getCompound(i).getInt("V"));
            }
            cpu.setCallStack(list);
        }

        if (cpuTag.contains("DataStack")) {
            List<Integer> list = new ArrayList<>();
            ListTag dataTag = cpuTag.getList("DataStack", Tag.TAG_COMPOUND);
            for (int i = 0; i < dataTag.size(); i++) {
                list.add(dataTag.getCompound(i).getInt("V"));
            }
            cpu.setDataStack(list);
        }

        if (cpuTag.contains("InterruptStack")) {
            List<VirtualCpu.CpuContext> list = new ArrayList<>();
            ListTag intTag = cpuTag.getList("InterruptStack", Tag.TAG_COMPOUND);
            for (int i = 0; i < intTag.size(); i++) {
                CompoundTag c = intTag.getCompound(i);
                list.add(new VirtualCpu.CpuContext(
                        c.getInt("Pc"),
                        c.getInt("RegA"),
                        c.getInt("RegB"),
                        c.getInt("RegC"),
                        c.getInt("RegD"),
                        c.getBoolean("FlagZ"),
                        c.getBoolean("FlagS"),
                        c.getBoolean("RestoreRegs")
                ));
            }
            cpu.setInterruptStack(list);
        }

        if (cpuTag.contains("IVT")) {
            CompoundTag ivtTag = cpuTag.getCompound("IVT");
            Map<Integer, Integer> map = new HashMap<>();
            for (String key : ivtTag.getAllKeys()) {
                try {
                    map.put(Integer.parseInt(key), ivtTag.getInt(key));
                } catch (NumberFormatException ignored) {}
            }
            cpu.setIvt(map);
        }

        if (cpuTag.contains("State")) {
            int stateOrd = cpuTag.getInt("State");
            VirtualCpu.State[] states = VirtualCpu.State.values();
            if (stateOrd >= 0 && stateOrd < states.length) {
                cpu.setState(states[stateOrd]);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        synchronized (cpu.getExecutionLock()) {
            tag.put("Inventory", itemHandler.serializeNBT(registries));
            tag.putInt("DisplayMode", cpu.getDisplayMode());

            byte[] compressedFb = SyncFramebufferPacket.compress(cpu.getFramebuffer());
            if (compressedFb != null && compressedFb.length > 0) {
                tag.putByteArray("FramebufferCompressed", compressedFb);
            }

            if (socData != null && !socData.instructions().isEmpty()) {
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

            CompoundTag cpuTag = new CompoundTag();
            saveCpuStateToNbt(cpuTag);
            tag.put("CpuData", cpuTag);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        synchronized (cpu.getExecutionLock()) {
            if (tag.contains("Inventory")) {
                itemHandler.deserializeNBT(registries, tag.getCompound("Inventory"));
                if (itemHandler.getSlots() < 6) {
                    itemHandler.setSize(6);
                }
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

                this.socData = new SocData(instructions, ramCap, bios, hasNvme, storageCap);
                this.cpu.setSupportedInstructions(instructions);
            } else {
                this.socData = SocData.EMPTY;
                this.cpu.setSupportedInstructions(Collections.emptyList());
            }

            recalculateHardwareCapabilities();

            if (tag.contains("CpuData")) {
                loadCpuStateFromNbt(tag.getCompound("CpuData"));
            } else {
                if (tag.contains("CpuState")) {
                    int stateOrd = tag.getInt("CpuState");
                    VirtualCpu.State[] states = VirtualCpu.State.values();
                    if (stateOrd >= 0 && stateOrd < states.length) {
                        cpu.setState(states[stateOrd]);
                    }
                }
                if (socData != null && !socData.instructions().isEmpty() && cpu.getProgram().isEmpty()) {
                    cpu.loadProgramImage(socData.bios());
                }
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
            if (tag.contains("FramebufferCompressed", Tag.TAG_BYTE_ARRAY)) {
                int[] fb = SyncFramebufferPacket.decompress(tag.getByteArray("FramebufferCompressed"));
                if (fb != null && fb.length == cpu.getFramebuffer().length) {
                    System.arraycopy(fb, 0, cpu.getFramebuffer(), 0, fb.length);
                    cpu.setFbDirty(true);
                }
            } else if (tag.contains("Framebuffer", Tag.TAG_INT_ARRAY)) {
                int[] fb = tag.getIntArray("Framebuffer");
                if (fb.length == cpu.getFramebuffer().length) {
                    System.arraycopy(fb, 0, cpu.getFramebuffer(), 0, fb.length);
                    cpu.setFbDirty(true);
                }
            }

            if (level != null && !level.isClientSide) {
                if (cpu.getState() == VirtualCpu.State.RUNNING) {
                    ComputerThreadManager.register(this);
                } else {
                    ComputerThreadManager.unregister(this);
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", itemHandler.serializeNBT(registries));
        tag.putInt("DisplayMode", cpu.getDisplayMode());
        tag.putInt("CpuState", cpu.getState().ordinal());
        tag.putString("ErrorMessage", cpu.getErrorMessage() != null ? cpu.getErrorMessage() : "");

        if (socData != null && !socData.instructions().isEmpty()) {
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
        if (tag.contains("Inventory")) {
            itemHandler.deserializeNBT(lookupProvider, tag.getCompound("Inventory"));
            if (itemHandler.getSlots() < 6) {
                itemHandler.setSize(6);
            }
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

            this.socData = new SocData(instructions, ramCap, bios, hasNvme, storageCap);
        } else {
            this.socData = SocData.EMPTY;
        }
        recalculateHardwareCapabilities();

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
        if (tag.contains("CpuState")) {
            int stateOrd = tag.getInt("CpuState");
            VirtualCpu.State[] states = VirtualCpu.State.values();
            if (stateOrd >= 0 && stateOrd < states.length) {
                cpu.setState(states[stateOrd]);
            }
        }
        if (tag.contains("ErrorMessage")) {
            cpu.setErrorMessage(tag.getString("ErrorMessage"));
        }
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
