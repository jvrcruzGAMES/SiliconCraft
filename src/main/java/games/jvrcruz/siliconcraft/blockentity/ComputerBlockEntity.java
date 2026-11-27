package games.jvrcruz.siliconcraft.blockentity;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.NetworkConfig;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.network.StopAudioPacket;

import games.jvrcruz.siliconcraft.block.ComputerBlock;
import games.jvrcruz.siliconcraft.data.SocData;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.network.DisplayFramePacket;
import games.jvrcruz.siliconcraft.network.PlayAudioPacket;
import games.jvrcruz.siliconcraft.network.FramebufferCodec;
import games.jvrcruz.siliconcraft.network.SyncTerminalPacket;
import games.jvrcruz.siliconcraft.vm.ComputerHost;
import games.jvrcruz.siliconcraft.vm.ComputerThreadManager;
import games.jvrcruz.siliconcraft.vm.DisplayStreamSession;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import games.jvrcruz.siliconcraft.vm.DriveStorage;
import games.jvrcruz.siliconcraft.vm.PeripheralBus;
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
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
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

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ComputerBlockEntity extends BlockEntity implements MenuProvider, ComputerHost {
    private SocData socData = SocData.EMPTY;
    private final VirtualCpu cpu = new VirtualCpu();
    private final DisplayStreamSession displayStreamSession = new DisplayStreamSession();
    private final CopyOnWriteArrayList<ServerPlayer> activeViewers = new CopyOnWriteArrayList<>();
    private volatile List<ServerPlayer> terminalTrackers = List.of();
    private volatile @Nullable BlockPos cachedSpeakerPos = null;
    private volatile List<ServerPlayer> speakerAudioListeners = List.of();
    private volatile boolean worldTerminalDirty = false;
    private List<String> lastSyncedOutput = Collections.emptyList();
    private byte[] onboardStorage = new byte[0];
    private @Nullable UUID onboardDriveId;
    private final Set<UUID> audioListeners = ConcurrentHashMap.newKeySet();
    private record WrappedPeripheral(SerialNetwork.Endpoint endpoint, ComputerPeripheral peripheral) {}
    private final Map<Integer, WrappedPeripheral> wrappedPeripherals = new HashMap<>();
    private final SerialNetwork serialNetwork;
    private long peripheralSession;
    private volatile long lastGameTime = 0L;

    // Inventory: 11 slots (0-1: storage drives max 1 each; 2-9: RAM modules max 1 each; 10: GPU max 1)
    public static final int STORAGE_SLOTS = 2;
    public static final int RAM_SLOTS = 8;
    public static final int GPU_SLOTS = 1;
    public static final int GPU_SLOT = STORAGE_SLOTS + RAM_SLOTS;
    public static final int HARDWARE_SLOTS = STORAGE_SLOTS + RAM_SLOTS + GPU_SLOTS;
    private final ItemStackHandler itemHandler = new ItemStackHandler(HARDWARE_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == 0 || slot == 1) {
                return stack.is(ModItems.STORAGE.get());
            } else if (slot >= STORAGE_SLOTS && slot < GPU_SLOT) {
                return stack.is(ModItems.RAM.get());
            } else if (slot == GPU_SLOT) {
                return stack.is(ModItems.GPU.get()) && !hasSocGpu();
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

    // Container data for syncing CPU registers and hardware status:
    // 0..13: existing CPU/RAM/Storage fields
    // 14: hasGpu (0/1), 15: gpuVramKb, 16: graphicsHandler ordinal (0=AUTO,1=CPU,2=GPU), 17: isUsingGpu (0/1)
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
                case 14 -> hasEffectiveGpu() ? 1 : 0;
                case 15 -> getEffectiveGpuData().vramCapacity() / 1024;
                case 16 -> cpu.getGraphicsHandler().ordinal();
                case 17 -> cpu.isUsingGpu() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Read-only from container data side
        }

        @Override
        public int getCount() {
            return 18;
        }
    };

    public ComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.COMPUTER.get(), pos, blockState);
        serialNetwork = new SerialNetwork(pos);
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

    public boolean hasSocGpu() {
        return socData != null && socData.gpu() != null && socData.gpu().isPresent();
    }

    public games.jvrcruz.siliconcraft.data.GpuData getEffectiveGpuData() {
        if (hasSocGpu()) {
            return socData.gpu();
        }
        ItemStack gpuStack = itemHandler.getStackInSlot(GPU_SLOT);
        if (!gpuStack.isEmpty() && gpuStack.is(ModItems.GPU.get())) {
            return gpuStack.getOrDefault(ModDataComponents.GPU_DATA.get(), games.jvrcruz.siliconcraft.data.GpuData.DEFAULT);
        }
        return games.jvrcruz.siliconcraft.data.GpuData.EMPTY;
    }

    public boolean hasEffectiveGpu() {
        return getEffectiveGpuData().isPresent();
    }

    public void cycleGraphicsHandler() {
        synchronized (cpu.getExecutionLock()) {
            cpu.cycleGraphicsHandler();
        }
        ComputerThreadManager.wakeSync(this);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int getEffectiveRamCapacity() {
        int ram = (socData != null ? socData.ramCapacity() : 0);
        for (int i = STORAGE_SLOTS; i < GPU_SLOT; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.RAM.get())) {
                RamData rd = stack.get(ModDataComponents.RAM_DATA.get());
                ram += (rd != null ? rd.capacity() : RamData.MODULE_CAPACITY);
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
        int cap = socData != null ? StorageData.upgradedCapacity(socData.storageCapacity()) : 0;
        for (int i = 0; i <= 1; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(ModItems.STORAGE.get())) {
                StorageData sd = stack.get(ModDataComponents.STORAGE_DATA.get());
                cap += (sd != null ? sd.capacity() : StorageData.DRIVE_CAPACITY_KB);
            }
        }
        return cap;
    }

    public void recalculateHardwareCapabilities() {
        synchronized (cpu.getExecutionLock()) {
            cpu.updateHardware(getEffectiveRamCapacity(), hasEffectiveNvme(), getEffectiveStorageCapacity());
            cpu.configureGpu(getEffectiveGpuData());
            if (!(level instanceof ServerLevel serverLevel)) return;

            int onboardKb = socData == null ? 0 : StorageData.upgradedCapacity(socData.storageCapacity());
            if (onboardKb > 0) {
                UUID id = onboardDriveId != null ? onboardDriveId : UUID.randomUUID();
                DriveStorage drive = openDrive(serverLevel, id, onboardStorage);
                if (drive != null) {
                    if (!id.equals(onboardDriveId) || onboardStorage.length > 0) setChanged();
                    onboardDriveId = id;
                    onboardStorage = new byte[0];
                }
                cpu.attachDrive(0, drive, drive == null ? 0 : (long) onboardKb * 1024);
            } else {
                cpu.attachDrive(0, null, 0);
            }

            for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
                int index = slot + 1;
                ItemStack stack = itemHandler.getStackInSlot(slot);
                if (!stack.is(ModItems.STORAGE.get())) {
                    cpu.attachDrive(index, null, 0);
                    cpu.setDriveBinary(index, new byte[0]);
                    continue;
                }

                int kb = stack.getOrDefault(ModDataComponents.STORAGE_DATA.get(), StorageData.DEFAULT).capacity();
                UUID id = StorageItem.getDriveId(stack);
                UUID backingId = id != null ? id : UUID.randomUUID();
                DriveStorage drive = openDrive(serverLevel, backingId, id == null ? StorageItem.getStorageBytes(stack) : new byte[0]);
                if (drive != null && id == null) {
                    StorageItem.setDriveId(stack, backingId);
                    setChanged();
                }
                cpu.attachDrive(index, drive, drive == null ? 0 : (long) kb * 1024);
                RomData rom = stack.get(ModDataComponents.ROM_DATA.get());
                cpu.setDriveBinary(index, rom != null ? rom.bytecode() : new byte[0]);
            }
        }
    }

    private DriveStorage openDrive(ServerLevel level, UUID id, byte[] legacy) {
        DriveStorage drive = DriveStorage.onDisk(level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("siliconcraft").resolve("drives").resolve(id + ".bin"));
        if (legacy.length > 0) {
            try {
                drive.write(0, legacy, legacy.length);
            } catch (UncheckedIOException e) {
                Siliconcraft.LOGGER.error("Could not migrate drive {} at {}", id, worldPosition, e);
                return null;
            }
        }
        return drive;
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
        if (hasActiveViewers()) {
            ComputerThreadManager.unregisterExecution(this);
        } else {
            ComputerThreadManager.unregister(this);
        }
        stopAudio();
        this.onboardStorage = new byte[0];
        this.onboardDriveId = null;
        recalculateHardwareCapabilities();
        ComputerThreadManager.wakeSync(this);
        setChanged();
    }

    private volatile long lastWorldTerminalVersion = -1L;

    public void syncTerminal(Level level, BlockPos pos, BlockState state) {
        if (level == null || level.isClientSide) return;
        long ver = cpu.getTerminalVersion();
        if (ver == lastWorldTerminalVersion && !worldTerminalDirty) {
            return;
        }
        List<String> currentOutput = cpu.getTerminalOutput();
        lastWorldTerminalVersion = ver;
        worldTerminalDirty = false;
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

    public boolean hasActiveViewers() {
        return !activeViewers.isEmpty();
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
            ComputerThreadManager.wakeSync(this);
            return;
        }

        cpu.togglePower();
        if (cpu.getState() == VirtualCpu.State.RUNNING) {
            ComputerThreadManager.register(this);
        } else {
            if (hasActiveViewers()) {
                ComputerThreadManager.unregisterExecution(this);
            } else {
                ComputerThreadManager.unregister(this);
            }
            stopAudio();
        }

        if (level != null) {
            boolean isRunning = (cpu.getState() == VirtualCpu.State.RUNNING);
            if (getBlockState().getValue(ComputerBlock.POWERED) != isRunning) {
                level.setBlock(worldPosition, getBlockState().setValue(ComputerBlock.POWERED, isRunning), 3);
            }
            syncTerminal(level, worldPosition, getBlockState());
        }
        ComputerThreadManager.wakeSync(this);
        setChanged();
    }

    public void resetCpu() {
        cpu.reset();
        setChanged();
        if (level != null) {
            syncTerminal(level, worldPosition, getBlockState());
        }
        ComputerThreadManager.wakeSync(this);
    }

    public void stepCpu() {
        cpu.step();
        setChanged();
        if (level != null) {
            syncTerminal(level, worldPosition, getBlockState());
        }
        ComputerThreadManager.wakeSync(this);
    }

    public void stopAudio() {
        synchronized (cpu.getExecutionLock()) {
            cpu.clearAudioQueue();
        }
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;

        StopAudioPacket packet = new StopAudioPacket(worldPosition);
        for (ServerPlayer player : serverLevel.players()) {
            if (audioListeners.contains(player.getUUID())
                    || player.containerMenu instanceof ComputerMenu menu && menu.getBlockEntity() == this) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
        audioListeners.clear();
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        this.lastGameTime = level.getGameTime();
        if (level instanceof ServerLevel) cpu.getNetworkPort().setPolicy(NetworkConfig.policy());
        if (cpu.getState() == VirtualCpu.State.RUNNING) {
            ComputerThreadManager.register(this);
            if (!state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, true), 3);
            }
        } else {
            if (hasActiveViewers()) {
                ComputerThreadManager.unregisterExecution(this);
                ComputerThreadManager.registerSync(this);
            } else {
                ComputerThreadManager.unregister(this);
            }
            if (state.getValue(ComputerBlock.POWERED)) {
                level.setBlock(pos, state.setValue(ComputerBlock.POWERED, false), 3);
                stopAudio();
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            servicePeripheral(serverLevel);
            refreshSyncSnapshots(serverLevel, pos);
        }
        long ver = cpu.getTerminalVersion();
        if (this.worldTerminalDirty || ver != this.lastWorldTerminalVersion) {
            this.worldTerminalDirty = false;
            this.lastWorldTerminalVersion = ver;
            List<String> currentOutput = cpu.getTerminalOutput();
            if (!currentOutput.equals(lastSyncedOutput)) {
                lastSyncedOutput = new ArrayList<>(currentOutput);
                setChanged();
                level.sendBlockUpdated(pos, state, state, 2);
                if (!hasActiveViewers() && level instanceof ServerLevel serverLevel) {
                    PacketDistributor.sendToPlayersTrackingChunk(
                            serverLevel,
                            new ChunkPos(pos),
                            new SyncTerminalPacket(pos, currentOutput)
                    );
                }
            }
        }
    }

    private void refreshSyncSnapshots(ServerLevel serverLevel, BlockPos pos) {
        if (!activeViewers.isEmpty()) {
            for (ServerPlayer viewer : activeViewers) {
                if (!isPlayerConnected(viewer)
                        || !(viewer.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this)) {
                    activeViewers.remove(viewer);
                    displayStreamSession.removeViewer(viewer.getUUID());
                }
            }
        }

        List<ServerPlayer> tracking = serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false);
        this.terminalTrackers = tracking.isEmpty() ? List.of() : List.copyOf(tracking);

        BlockPos speakerPos = null;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            LevelChunk chunk = SerialNetwork.loadedChunk(serverLevel, pos, neighbor);
            if (chunk != null && chunk.getBlockState(neighbor).is(ModBlocks.SPEAKER.get())) {
                speakerPos = neighbor;
                break;
            }
        }
        this.cachedSpeakerPos = speakerPos;

        if (speakerPos != null) {
            Vec3 source = SubLevelCoordinates.worldPosition(serverLevel, Vec3.atCenterOf(speakerPos));
            double maxDistSq = 32.0 * 32.0;
            List<ServerPlayer> nearby = new ArrayList<>();
            for (ServerPlayer player : serverLevel.players()) {
                if (SubLevelCoordinates.worldPosition(serverLevel, player.position()).distanceToSqr(source) <= maxDistSq
                        || (player.containerMenu instanceof ComputerMenu cMenu && cMenu.getBlockEntity() == this)) {
                    nearby.add(player);
                }
            }
            this.speakerAudioListeners = nearby.isEmpty() ? List.of() : List.copyOf(nearby);
        } else {
            this.speakerAudioListeners = List.of();
        }
    }

    private void servicePeripheral(ServerLevel level) {
        PeripheralBus bus = cpu.getPeripheralBus();
        PeripheralBus.Request request = bus.pendingRequest();
        if (request == null) {
            serialNetwork.cancelScan();
            return;
        }
        if (request.session() != peripheralSession) {
            wrappedPeripherals.clear();
            serialNetwork.reset();
            peripheralSession = request.session();
        }
        int port = request.side();
        boolean discovering = request.operation() == PeripheralBus.Operation.SCAN;
        if (port < 0 && !discovering) {
            bus.complete(request, PeripheralBus.FAILED, 0);
            return;
        }

        try {
            if (discovering || port >= SerialNetwork.FIRST_WIRED_PORT) {
                if (!serialNetwork.advance(level)) return;
                wrappedPeripherals.keySet().removeIf(address -> address >= SerialNetwork.FIRST_WIRED_PORT
                        && serialNetwork.endpoint(address) == null);
                if (discovering) {
                    bus.complete(request, PeripheralBus.OK, serialNetwork.nextPort(port));
                    return;
                }
            }

            SerialNetwork.Endpoint endpoint = serialNetwork.endpoint(port);
            ComputerPeripheral peripheral = endpoint == null ? null : serialNetwork.resolve(level, endpoint);
            WrappedPeripheral wrappedDevice = wrappedPeripherals.get(port);
            if (wrappedDevice != null && (wrappedDevice.peripheral() != peripheral || !wrappedDevice.endpoint().equals(endpoint))) {
                wrappedPeripherals.remove(port);
            }
            if (peripheral == null) {
                bus.complete(request, PeripheralBus.MISSING, 0);
                return;
            }

            boolean automatic = peripheral.autoWrap();
            boolean wrapped = automatic || wrappedPeripherals.containsKey(port);
            switch (request.operation()) {
                case INFO -> {
                    int flags = PeripheralBus.PRESENT;
                    if (wrapped) flags |= PeripheralBus.WRAPPED;
                    if (automatic) flags |= PeripheralBus.AUTOMATIC;
                    bus.complete(request, PeripheralBus.OK, flags);
                }
                case WRAP -> {
                    if (!automatic) {
                        wrappedPeripherals.put(port, new WrappedPeripheral(endpoint, peripheral));
                    }
                    bus.complete(request, PeripheralBus.OK, 1);
                }
                case CALL, CALL_STRING -> {
                    if (!wrapped) {
                        bus.complete(request, PeripheralBus.UNWRAPPED, 0);
                        return;
                    }
                    if (bus.pendingRequest() != request) return;
                    var context = new ComputerPeripheral.Context(level, worldPosition, endpoint.pos(), endpoint.face());
                    var result = request.operation() == PeripheralBus.Operation.CALL_STRING
                            ? peripheral.invokeString(context, request.method(), request.text(), request.arg1())
                            : peripheral.invoke(context, request.method(), request.arg0(), request.arg1());
                    if (result.isEmpty()) {
                        bus.complete(request, PeripheralBus.UNSUPPORTED, 0);
                    } else {
                        var response = result.get();
                        if (response.text().length() > PeripheralBus.MAX_TEXT_RESULT) {
                            bus.complete(request, PeripheralBus.FAILED, 0);
                        } else {
                            bus.complete(request, PeripheralBus.OK, response.value(), response.text());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            serialNetwork.cancelScan();
            Siliconcraft.LOGGER.debug("Peripheral request on port {} failed at {}", port, worldPosition, e);
            bus.complete(request, PeripheralBus.FAILED, 0);
        }
    }

    @Override
    public void tickAsyncCpu(int cycles) {
        if (cpu.getState() != VirtualCpu.State.RUNNING) {
            return;
        }
        cpu.tick(cycles, 1_500_000L);
    }

    @Override
    public boolean hasPendingSyncWork() {
        return (!activeViewers.isEmpty() && cpu.isAnyFbDirty()) || cpu.hasPendingAudio();
    }

    @Override
    public boolean tickAsyncSync(long nowNanos) {
        boolean didWork = false;
        List<ServerPlayer> viewers = collectLiveViewers();
        if (!viewers.isEmpty()) {
            List<UUID> viewerIds = new ArrayList<>(viewers.size());
            for (ServerPlayer viewer : viewers) {
                viewerIds.add(viewer.getUUID());
            }
            if (displayStreamSession.tickDisplay(cpu, viewerIds, nowNanos,
                    (frame, broadcastAll) -> emitFrameChunks(viewers, frame))) {
                didWork = true;
            }
        }

        if (displayStreamSession.tickTerminal(cpu, lines -> {
            this.worldTerminalDirty = true;
            emitTerminalPacket(new SyncTerminalPacket(worldPosition, lines), viewers);
        })) {
            didWork = true;
        }

        byte[] audioSamples = cpu.pollAudioSamples();
        if (audioSamples != null && audioSamples.length > 0) {
            didWork = true;
            BlockPos speakerPos = this.cachedSpeakerPos;
            if (speakerPos != null) {
                PlayAudioPacket pkt = new PlayAudioPacket(worldPosition, speakerPos, audioSamples, 8000, true);
                for (ServerPlayer player : this.speakerAudioListeners) {
                    sendAudioDirect(player, pkt);
                }
            } else if (!viewers.isEmpty()) {
                PlayAudioPacket pkt = new PlayAudioPacket(worldPosition, worldPosition, audioSamples, 8000, false);
                for (ServerPlayer viewer : viewers) {
                    sendAudioDirect(viewer, pkt);
                }
            }
        }
        return didWork;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            recalculateHardwareCapabilities();
            cpu.getNetworkPort().setPolicy(NetworkConfig.policy());
            if (cpu.getState() == VirtualCpu.State.RUNNING) {
                ComputerThreadManager.register(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ComputerThreadManager.unregister(this);
        activeViewers.clear();
        terminalTrackers = List.of();
        speakerAudioListeners = List.of();
        displayStreamSession.resetBaseline();
        cpu.getNetworkPort().reset();
        cpu.getPeripheralBus().reset();
        wrappedPeripherals.clear();
        serialNetwork.reset();
        stopAudio();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        ComputerThreadManager.unregister(this);
        activeViewers.clear();
        terminalTrackers = List.of();
        speakerAudioListeners = List.of();
        displayStreamSession.resetBaseline();
        cpu.getNetworkPort().reset();
        cpu.getPeripheralBus().reset();
        wrappedPeripherals.clear();
        serialNetwork.reset();
        stopAudio();
    }

    private List<ServerPlayer> collectLiveViewers() {
        if (activeViewers.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> live = new ArrayList<>(activeViewers.size());
        for (ServerPlayer player : activeViewers) {
            if (isPlayerConnected(player)) {
                live.add(player);
            }
        }
        return live;
    }

    private void emitFrameChunks(List<ServerPlayer> targets, DisplayStreamSession.StreamFrame frame) {
        if (targets.isEmpty()) return;
        List<byte[]> chunks = frame.chunks();
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            DisplayFramePacket pkt = new DisplayFramePacket(
                    worldPosition,
                    frame.mode(),
                    frame.frameId(),
                    i,
                    total,
                    frame.keyframe(),
                    chunks.get(i)
            );
            for (ServerPlayer player : targets) {
                sendPayloadDirect(player, pkt);
            }
        }
    }

    private void emitTerminalPacket(SyncTerminalPacket packet, List<ServerPlayer> liveViewers) {
        List<ServerPlayer> trackers = this.terminalTrackers;
        for (ServerPlayer tracker : trackers) {
            sendPayloadDirect(tracker, packet);
        }
        for (ServerPlayer viewer : liveViewers) {
            if (!trackers.contains(viewer)) {
                sendPayloadDirect(viewer, packet);
            }
        }
    }

    private static boolean isPlayerConnected(ServerPlayer player) {
        return player != null && !player.hasDisconnected()
                && player.connection != null && player.connection.isAcceptingMessages();
    }

    private static void sendPayloadDirect(ServerPlayer player, CustomPacketPayload payload) {
        if (isPlayerConnected(player)) {
            player.connection.send(new ClientboundCustomPayloadPacket(payload));
        }
    }

    public void broadcastSpeakerAudio(ServerLevel level, BlockPos speakerPos, byte[] audioSamples) {
        Vec3 source = SubLevelCoordinates.worldPosition(level, Vec3.atCenterOf(speakerPos));
        double maxDistSq = 32.0 * 32.0;
        PlayAudioPacket packet = new PlayAudioPacket(worldPosition, speakerPos, audioSamples, 8000, true);
        for (ServerPlayer player : level.players()) {
            if (SubLevelCoordinates.worldPosition(level, player.position()).distanceToSqr(source) <= maxDistSq
                    || player.containerMenu instanceof ComputerMenu menu && menu.getBlockEntity() == this) {
                sendAudioDirect(player, packet);
            }
        }
    }

    private void sendAudioDirect(ServerPlayer player, PlayAudioPacket packet) {
        if (isPlayerConnected(player)) {
            player.connection.send(new ClientboundCustomPayloadPacket(packet));
            audioListeners.add(player.getUUID());
        }
    }

    public void onViewerClosed(ServerPlayer player) {
        if (player == null) return;
        activeViewers.remove(player);
        displayStreamSession.removeViewer(player.getUUID());
        if (cpu.getState() != VirtualCpu.State.RUNNING && !hasActiveViewers()) {
            ComputerThreadManager.unregister(this);
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

        byte[] programBinary = cpu.getBinaryImage();
        if (programBinary.length > 0) {
            cpuTag.putByteArray("ProgramBinary", programBinary);
        }

        byte[] compressedRam = cpu.getCompressedRam();
        if (compressedRam != null && compressedRam.length > 0) {
            cpuTag.putByteArray("Ram", compressedRam);
        }
    }

    private void loadCpuStateFromNbt(CompoundTag cpuTag) {
        if (cpuTag.contains("ProgramBinary", Tag.TAG_BYTE_ARRAY)) {
            cpu.loadBinaryImage(cpuTag.getByteArray("ProgramBinary"));
        } else if (cpuTag.contains("Program")) {
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

    private static final int MAX_UPDATE_TERMINAL_LINES = 60;
    private static final int MAX_UPDATE_TERMINAL_CHARS = 32_768;
    private static final int MAX_UPDATE_ERROR_CHARS = 1024;

    private static CompoundTag serializeSocTag(SocData data) {
        return serializeSocTag(data, true);
    }

    static CompoundTag serializeSocTag(SocData data, boolean includeBios) {
        CompoundTag socTag = new CompoundTag();
        ListTag instTag = new ListTag();
        for (String s : data.instructions()) instTag.add(StringTag.valueOf(s));
        socTag.put("Instructions", instTag);
        socTag.putInt("RamCapacity", data.ramCapacity());
        if (includeBios && data.bios().length > 0) {
            socTag.putByteArray("BiosBinary", data.bios());
        }
        socTag.putBoolean("HasNvme", data.hasNvme());
        socTag.putInt("StorageCapacity", data.storageCapacity());
        if (data.gpu() != null && data.gpu().isPresent()) {
            CompoundTag gpuTag = new CompoundTag();
            gpuTag.putBoolean("Present", true);
            gpuTag.putString("ArchName", data.gpu().archName());
            gpuTag.putInt("VramCapacity", data.gpu().vramCapacity());
            gpuTag.putInt("ComputeUnits", data.gpu().computeUnits());
            ListTag featTag = new ListTag();
            for (String f : data.gpu().features()) featTag.add(StringTag.valueOf(f));
            gpuTag.put("Features", featTag);
            socTag.put("Gpu", gpuTag);
        }
        return socTag;
    }

    static SocData deserializeSocTag(CompoundTag socTag) {
        List<String> instructions = new ArrayList<>();
        ListTag instTag = socTag.getList("Instructions", Tag.TAG_STRING);
        for (int i = 0; i < instTag.size(); i++) instructions.add(instTag.getString(i));

        int ramCap = socTag.getInt("RamCapacity");
        byte[] bios = new byte[0];
        if (socTag.contains("BiosBinary", Tag.TAG_BYTE_ARRAY)) {
            bios = socTag.getByteArray("BiosBinary");
        } else if (socTag.contains("Bios", Tag.TAG_LIST)) {
            List<String> biosLines = new ArrayList<>();
            ListTag biosTag = socTag.getList("Bios", Tag.TAG_STRING);
            for (int i = 0; i < biosTag.size(); i++) biosLines.add(biosTag.getString(i));
            if (!biosLines.isEmpty()) {
                bios = games.jvrcruz.siliconcraft.compiler.Assembler.assemble(biosLines).bytecode();
            }
        }

        boolean hasNvme = socTag.getBoolean("HasNvme");
        int storageCap = socTag.getInt("StorageCapacity");

        games.jvrcruz.siliconcraft.data.GpuData gpuData = games.jvrcruz.siliconcraft.data.GpuData.EMPTY;
        if (socTag.contains("Gpu", Tag.TAG_COMPOUND)) {
            CompoundTag gpuTag = socTag.getCompound("Gpu");
            if (gpuTag.getBoolean("Present")) {
                List<String> features = new ArrayList<>();
                ListTag featTag = gpuTag.getList("Features", Tag.TAG_STRING);
                for (int i = 0; i < featTag.size(); i++) features.add(featTag.getString(i));
                gpuData = new games.jvrcruz.siliconcraft.data.GpuData(
                        true,
                        gpuTag.getString("ArchName"),
                        gpuTag.getInt("VramCapacity"),
                        gpuTag.getInt("ComputeUnits"),
                        features
                );
            }
        }
        return new SocData(instructions, ramCap, bios, hasNvme, storageCap, gpuData);
    }

    static CompoundTag serializeLightInventory(ItemStackHandler handler, HolderLookup.Provider registries) {
        ItemStackHandler light = new ItemStackHandler(handler.getSlots());
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                ItemStack copy = stack.copy();
                copy.remove(ModDataComponents.ROM_DATA.get());
                copy.remove(ModDataComponents.SOC_DATA.get());
                copy.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                light.setStackInSlot(i, copy);
            }
        }
        return light.serializeNBT(registries);
    }

    static ListTag serializeBoundedTerminalOutput(List<String> output) {
        List<String> tail = new ArrayList<>();
        int totalChars = 0;
        if (output != null && !output.isEmpty()) {
            for (int i = output.size() - 1; i >= 0 && tail.size() < MAX_UPDATE_TERMINAL_LINES; i--) {
                String line = output.get(i);
                if (line == null) line = "";
                if (line.length() > MAX_UPDATE_TERMINAL_CHARS) {
                    line = line.substring(0, MAX_UPDATE_TERMINAL_CHARS);
                }
                if (totalChars + line.length() > MAX_UPDATE_TERMINAL_CHARS && !tail.isEmpty()) {
                    break;
                }
                tail.add(line);
                totalChars += line.length();
            }
        }
        ListTag terminalTag = new ListTag();
        for (int i = tail.size() - 1; i >= 0; i--) {
            terminalTag.add(StringTag.valueOf(tail.get(i)));
        }
        return terminalTag;
    }

    static String boundString(@Nullable String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    static CompoundTag buildUpdateTag(
            CompoundTag lightInventory,
            int displayMode,
            int graphicsHandlerOrdinal,
            int cpuStateOrdinal,
            @Nullable String errorMessage,
            @Nullable SocData socData,
            List<String> terminalOutput
    ) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", lightInventory);
        tag.putInt("DisplayMode", displayMode);
        tag.putInt("GraphicsHandler", graphicsHandlerOrdinal);
        tag.putInt("CpuState", cpuStateOrdinal);
        tag.putString("ErrorMessage", boundString(errorMessage, MAX_UPDATE_ERROR_CHARS));

        if (socData != null && !socData.instructions().isEmpty()) {
            tag.put("SocData", serializeSocTag(socData, false));
        }

        tag.put("TerminalOutput", serializeBoundedTerminalOutput(terminalOutput));
        return tag;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        synchronized (cpu.getExecutionLock()) {
            tag.put("Inventory", itemHandler.serializeNBT(registries));
            tag.putInt("DisplayMode", cpu.getDisplayMode());
            tag.putInt("GraphicsHandler", cpu.getGraphicsHandler().ordinal());

            byte[] compressedFb = FramebufferCodec.compress(cpu.getActiveFramebuffer());
            if (compressedFb != null && compressedFb.length > 0) {
                tag.putByteArray("FramebufferCompressed", compressedFb);
            }

            if (socData != null && !socData.instructions().isEmpty()) {
                tag.put("SocData", serializeSocTag(socData));
            }

            ListTag terminalTag = new ListTag();
            for (String line : cpu.getTerminalOutput()) {
                terminalTag.add(StringTag.valueOf(line));
            }
            tag.put("TerminalOutput", terminalTag);
            tag.putInt("CpuState", cpu.getState().ordinal());
            if (onboardDriveId != null) tag.putUUID("OnboardDriveId", onboardDriveId);
            if (onboardStorage.length > 0) {
                tag.putByteArray("OnboardStorage", onboardStorage);
            }

            CompoundTag cpuTag = new CompoundTag();
            saveCpuStateToNbt(cpuTag);
            tag.put("CpuData", cpuTag);
        }
    }

    private void loadInventory(CompoundTag inventory, HolderLookup.Provider registries) {
        CompoundTag upgraded = inventory.copy();
        upgraded.putInt("Size", HARDWARE_SLOTS);
        itemHandler.deserializeNBT(registries, upgraded);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        synchronized (cpu.getExecutionLock()) {
            if (tag.contains("Inventory")) {
                loadInventory(tag.getCompound("Inventory"), registries);
            }

            onboardDriveId = tag.hasUUID("OnboardDriveId") ? tag.getUUID("OnboardDriveId") : null;
            onboardStorage = new byte[0];
            if (tag.contains("OnboardStorage", Tag.TAG_BYTE_ARRAY)) {
                onboardStorage = tag.getByteArray("OnboardStorage");
            } else if (tag.contains("BinaryStorage", Tag.TAG_BYTE_ARRAY)) {
                onboardStorage = tag.getByteArray("BinaryStorage");
            }

            if (tag.contains("SocData")) {
                this.socData = deserializeSocTag(tag.getCompound("SocData"));
                this.cpu.setSupportedInstructions(this.socData.instructions());
            } else {
                this.socData = SocData.EMPTY;
                this.cpu.setSupportedInstructions(Collections.emptyList());
            }

            if (tag.contains("GraphicsHandler")) {
                int ord = tag.getInt("GraphicsHandler");
                VirtualCpu.GraphicsHandler[] handlers = VirtualCpu.GraphicsHandler.values();
                if (ord >= 0 && ord < handlers.length) {
                    cpu.setGraphicsHandler(handlers[ord]);
                }
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
                if (socData != null && !socData.instructions().isEmpty() && !cpu.hasProgram()) {
                    cpu.loadBinaryImage(socData.bios());
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
                int[] fb = FramebufferCodec.decompress(tag.getByteArray("FramebufferCompressed"));
                FramebufferCodec.restore(fb, cpu.getFramebuffer());
                if (cpu.getGpu().isInstalled()) {
                    FramebufferCodec.restore(fb, cpu.getGpu().getFrontBuffer());
                    FramebufferCodec.restore(fb, cpu.getGpu().getBackBuffer());
                    cpu.getGpu().setFbDirty(true);
                }
                cpu.setFbDirty(true);
            } else if (tag.contains("Framebuffer", Tag.TAG_INT_ARRAY)) {
                int[] fb = tag.getIntArray("Framebuffer");
                FramebufferCodec.restore(fb, cpu.getFramebuffer());
                if (cpu.getGpu().isInstalled()) {
                    FramebufferCodec.restore(fb, cpu.getGpu().getFrontBuffer());
                    FramebufferCodec.restore(fb, cpu.getGpu().getBackBuffer());
                    cpu.getGpu().setFbDirty(true);
                }
                cpu.setFbDirty(true);
            }

            displayStreamSession.resetBaseline();
            lastWorldTerminalVersion = -1L;
            worldTerminalDirty = false;

            if (level instanceof ServerLevel) {
                cpu.getNetworkPort().setPolicy(NetworkConfig.policy());
                if (cpu.getState() == VirtualCpu.State.RUNNING) {
                    ComputerThreadManager.register(this);
                } else if (hasActiveViewers()) {
                    ComputerThreadManager.unregisterExecution(this);
                    ComputerThreadManager.registerSync(this);
                } else {
                    ComputerThreadManager.unregister(this);
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return buildUpdateTag(
                serializeLightInventory(itemHandler, registries),
                cpu.getDisplayMode(),
                cpu.getGraphicsHandler().ordinal(),
                cpu.getState().ordinal(),
                cpu.getErrorMessage(),
                socData,
                cpu.getTerminalOutput()
        );
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
            loadInventory(tag.getCompound("Inventory"), lookupProvider);
        }
        if (tag.contains("SocData")) {
            this.socData = deserializeSocTag(tag.getCompound("SocData"));
        } else {
            this.socData = SocData.EMPTY;
        }
        if (tag.contains("GraphicsHandler")) {
            int ord = tag.getInt("GraphicsHandler");
            VirtualCpu.GraphicsHandler[] handlers = VirtualCpu.GraphicsHandler.values();
            if (ord >= 0 && ord < handlers.length) {
                cpu.setGraphicsHandler(handlers[ord]);
            }
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
            activeViewers.addIfAbsent(serverPlayer);
            if (level instanceof ServerLevel serverLevel) {
                refreshSyncSnapshots(serverLevel, worldPosition);
            }
            displayStreamSession.sendInitialSnapshot(
                    serverPlayer.getUUID(),
                    cpu,
                    (frame, broadcastToAll) -> emitFrameChunks(
                            broadcastToAll ? collectLiveViewers() : List.of(serverPlayer),
                            frame
                    ),
                    lines -> sendPayloadDirect(serverPlayer, new SyncTerminalPacket(worldPosition, lines))
            );
            ComputerThreadManager.registerSync(this);
        }
        return new ComputerMenu(containerId, playerInventory, this, this.dataAccess);
    }
}
