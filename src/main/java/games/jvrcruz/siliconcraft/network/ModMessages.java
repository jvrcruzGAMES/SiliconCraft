package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.compat.create.CreatePeripherals;
import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.*;
import games.jvrcruz.siliconcraft.client.ClientProgramManager;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import games.jvrcruz.siliconcraft.menu.ComputerMenu;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModMessages {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Siliconcraft.MODID).versioned("1.3.0");
        CreatePeripherals.registerPayloads(registrar);

        registrar.playToServer(ChipForgePacket.TYPE, ChipForgePacket.STREAM_CODEC, ModMessages::handleChipForge);
        registrar.playToServer(ArchitectureWriterPacket.TYPE, ArchitectureWriterPacket.STREAM_CODEC, ModMessages::handleArchitectureWriter);
        registrar.playToServer(MemoryForgePacket.TYPE, MemoryForgePacket.STREAM_CODEC, ModMessages::handleMemoryForge);
        registrar.playToServer(GpuAssemblerPacket.TYPE, GpuAssemblerPacket.STREAM_CODEC, ModMessages::handleGpuAssembler);
        registrar.playToServer(ProgrammerPacket.TYPE, ProgrammerPacket.STREAM_CODEC, ModMessages::handleProgrammer);
        registrar.playToServer(SocManufacturerPacket.TYPE, SocManufacturerPacket.STREAM_CODEC, ModMessages::handleSocManufacturer);
        registrar.playToServer(ComputerActionPacket.TYPE, ComputerActionPacket.STREAM_CODEC, ModMessages::handleComputerAction);
        registrar.playToServer(ComputerInputPacket.TYPE, ComputerInputPacket.STREAM_CODEC, ModMessages::handleComputerInput);
        registrar.playToServer(GamepadInputPacket.TYPE, GamepadInputPacket.STREAM_CODEC, ModMessages::handleGamepadInput);

        // Program library packets (.sc Project Manager + Programmer)
        registrar.playToServer(UploadProjectStartPacket.TYPE, UploadProjectStartPacket.STREAM_CODEC, ModMessages::handleUploadProjectStart);
        registrar.playToServer(UploadProjectChunkPacket.TYPE, UploadProjectChunkPacket.STREAM_CODEC, ModMessages::handleUploadProjectChunk);
        registrar.playToServer(RenameProgramPacket.TYPE, RenameProgramPacket.STREAM_CODEC, ModMessages::handleRenameProgram);
        registrar.playToServer(DeleteProgramPacket.TYPE, DeleteProgramPacket.STREAM_CODEC, ModMessages::handleDeleteProgram);
        registrar.playToServer(RequestProgramsPacket.TYPE, RequestProgramsPacket.STREAM_CODEC, ModMessages::handleRequestPrograms);
        registrar.playToClient(SyncProgramsPacket.TYPE, SyncProgramsPacket.STREAM_CODEC, ModMessages::handleSyncPrograms);
        registrar.playToClient(SyncTerminalPacket.TYPE, SyncTerminalPacket.STREAM_CODEC, ModMessages::handleSyncTerminal);
        registrar.playToClient(SyncFramebufferPacket.TYPE, SyncFramebufferPacket.STREAM_CODEC, (pkt, ctx) -> games.jvrcruz.siliconcraft.client.ClientPayloadHandler.handleSyncFramebuffer(pkt, ctx));
        registrar.playToClient(PlayAudioPacket.TYPE, PlayAudioPacket.STREAM_CODEC, (pkt, ctx) -> games.jvrcruz.siliconcraft.client.ClientPayloadHandler.handlePlayAudio(pkt, ctx));
        registrar.playToClient(PeripheralNoticePacket.TYPE, PeripheralNoticePacket.STREAM_CODEC,
                (packet, context) -> games.jvrcruz.siliconcraft.client.ClientPayloadHandler.handlePeripheralNotice(packet, context));
        registrar.playToClient(StopAudioPacket.TYPE, StopAudioPacket.STREAM_CODEC, (pkt, ctx) -> games.jvrcruz.siliconcraft.client.ClientPayloadHandler.handleStopAudio(pkt, ctx));
    }

    private static void handleArchitectureWriter(ArchitectureWriterPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ArchitectureWriterBlockEntity writerBe) {
                    writerBe.writeArchitecture(packet.archName(), packet.instructions(), packet.gpu());
                }
            }
        });
    }

    private static void handleUploadProjectStart(UploadProjectStartPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ChunkedProjectUploadManager.handleStart(serverPlayer, packet);
            }
        });
    }

    private static void handleUploadProjectChunk(UploadProjectChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ChunkedProjectUploadManager.handleChunk(serverPlayer, packet);
            }
        });
    }

    private static void handleRenameProgram(RenameProgramPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.level().isLoaded(packet.pos())) {
                if (serverPlayer.distanceToSqr(packet.pos().getCenter()) > 100.0) {
                    return;
                }
                BlockEntity be = serverPlayer.level().getBlockEntity(packet.pos());
                if (!(be instanceof ProjectManagerBlockEntity)) {
                    return;
                }
                ProgramSavedData savedData = ProgramSavedData.get(serverPlayer.serverLevel());
                ProgramSavedData.RenameResult result = savedData.renameProgram(packet.id(), packet.newName());
                if (result.success()) {
                    syncProgramsToAll(savedData.getPrograms());
                    serverPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("Renamed project to: " + result.finalName())
                                    .withStyle(net.minecraft.ChatFormatting.GREEN),
                            false
                    );
                } else {
                    serverPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("Rename failed: " + result.error())
                                    .withStyle(net.minecraft.ChatFormatting.RED),
                            false
                    );
                }
            }
        });
    }

    private static void handleDeleteProgram(DeleteProgramPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                ProgramSavedData savedData = ProgramSavedData.get(serverPlayer.serverLevel());
                boolean removed = savedData.removeProgram(packet.id());
                if (removed) {
                    syncProgramsToAll(savedData.getPrograms());
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Deleted program from library.").withStyle(net.minecraft.ChatFormatting.YELLOW), false);
                }
            }
        });
    }

    private static void handleRequestPrograms(RequestProgramsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                ProgramSavedData data = ProgramSavedData.get(serverPlayer.serverLevel());
                syncProgramsToPlayer(serverPlayer, data.getPrograms());
            }
        });
    }

    public static void syncProgramsToAll(java.util.List<games.jvrcruz.siliconcraft.data.ProgramEntry> programs) {
        for (SyncProgramsPacket batch : SyncProgramsPacket.buildBatches(programs)) {
            PacketDistributor.sendToAllPlayers(batch);
        }
    }

    public static void syncProgramsToPlayer(ServerPlayer player, java.util.List<games.jvrcruz.siliconcraft.data.ProgramEntry> programs) {
        for (SyncProgramsPacket batch : SyncProgramsPacket.buildBatches(programs)) {
            PacketDistributor.sendToPlayer(player, batch);
        }
    }

    private static void handleSyncPrograms(SyncProgramsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.append()) {
                ClientProgramManager.addPrograms(packet.programs());
            } else {
                ClientProgramManager.setPrograms(packet.programs());
            }
        });
    }

    private static void handleChipForge(ChipForgePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ChipForgeBlockEntity forgeBe) {
                    if (packet.startForge()) {
                        forgeBe.startForging();
                    }
                }
            }
        });
    }

    private static void handleMemoryForge(MemoryForgePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof MemoryForgeBlockEntity forgeBe) {
                    forgeBe.setMode(packet.mode());
                    forgeBe.setNvmeEnabled(packet.nvme());
                    if (packet.startForge()) {
                        forgeBe.startForging();
                    }
                }
            }
        });
    }

    private static void handleGpuAssembler(GpuAssemblerPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof GpuAssemblerBlockEntity gpuBe) {
                    if (packet.startAssemble()) {
                        gpuBe.startAssembling();
                    }
                }
            }
        });
    }

    private static void handleProgrammer(ProgrammerPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ProgrammerBlockEntity progBe) {
                    progBe.burnProgram(packet.lines());
                }
            }
        });
    }

    private static void handleSocManufacturer(SocManufacturerPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof SocManufacturerBlockEntity socBe) {
                    socBe.startManufacture();
                }
            }
        });
    }

    private static void handleComputerAction(ComputerActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ComputerBlockEntity compBe) {
                    if (packet.action() == 0) {
                        compBe.togglePower();
                    } else if (packet.action() == 1) {
                        compBe.resetCpu();
                    } else if (packet.action() == 2) {
                        compBe.stepCpu();
                    } else if (packet.action() == 3) {
                        compBe.cycleGraphicsHandler();
                    }
                }
            }
        });
    }

    private static void handleGamepadInput(GamepadInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.containerMenu instanceof ComputerMenu menu
                    && menu.getBlockEntity().getBlockPos().equals(packet.pos())
                    && menu.stillValid(player) && !menu.getBlockEntity().isRemoved()) {
                var cpu = menu.getBlockEntity().getCpu();
                cpu.setGamepadState(0, cpu.getState() == VirtualCpu.State.RUNNING
                        ? packet.input() : GamepadState.NEUTRAL);
            }
        });
    }

    private static void handleComputerInput(ComputerInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ComputerBlockEntity compBe) {
                    if (packet.inputType() == 0) {
                        compBe.getCpu().queueKeyboardInterrupt(packet.arg1());
                    } else if (packet.inputType() == 1) {
                        compBe.getCpu().queueMouseMoveInterrupt(packet.arg1(), packet.arg2());
                    } else if (packet.inputType() == 2) {
                        compBe.getCpu().queueMouseClickInterrupt(packet.arg1(), packet.arg2(), packet.arg3());
                    } else if (packet.inputType() == 3) {
                        compBe.getCpu().setPadState(packet.arg1(), packet.arg2());
                    }
                }
            }
        });
    }

    private static void handleSyncTerminal(SyncTerminalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level() != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ComputerBlockEntity compBe) {
                    compBe.getCpu().setTerminalOutput(packet.lines());
                }
            }
        });
    }
}
