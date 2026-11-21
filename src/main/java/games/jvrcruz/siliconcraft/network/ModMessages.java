package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.blockentity.*;
import games.jvrcruz.siliconcraft.client.ClientProgramManager;
import games.jvrcruz.siliconcraft.data.ProgramSavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModMessages {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Siliconcraft.MODID).versioned("1.0.0");

        registrar.playToServer(ChipForgePacket.TYPE, ChipForgePacket.STREAM_CODEC, ModMessages::handleChipForge);
        registrar.playToServer(ArchitectureWriterPacket.TYPE, ArchitectureWriterPacket.STREAM_CODEC, ModMessages::handleArchitectureWriter);
        registrar.playToServer(MemoryForgePacket.TYPE, MemoryForgePacket.STREAM_CODEC, ModMessages::handleMemoryForge);
        registrar.playToServer(ProgrammerPacket.TYPE, ProgrammerPacket.STREAM_CODEC, ModMessages::handleProgrammer);
        registrar.playToServer(SocManufacturerPacket.TYPE, SocManufacturerPacket.STREAM_CODEC, ModMessages::handleSocManufacturer);
        registrar.playToServer(ComputerActionPacket.TYPE, ComputerActionPacket.STREAM_CODEC, ModMessages::handleComputerAction);

        // Program library packets
        registrar.playToServer(AddAssemblyProgramPacket.TYPE, AddAssemblyProgramPacket.STREAM_CODEC, ModMessages::handleAddAssemblyProgram);
        registrar.playToServer(CompileProgramPacket.TYPE, CompileProgramPacket.STREAM_CODEC, ModMessages::handleCompileProgram);
        registrar.playToServer(RequestProgramsPacket.TYPE, RequestProgramsPacket.STREAM_CODEC, ModMessages::handleRequestPrograms);
        registrar.playToClient(SyncProgramsPacket.TYPE, SyncProgramsPacket.STREAM_CODEC, ModMessages::handleSyncPrograms);
    }

    private static void handleArchitectureWriter(ArchitectureWriterPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null && player.level().isLoaded(packet.pos())) {
                BlockEntity be = player.level().getBlockEntity(packet.pos());
                if (be instanceof ArchitectureWriterBlockEntity writerBe) {
                    writerBe.writeArchitecture(packet.archName(), packet.instructions());
                }
            }
        });
    }

    private static void handleAddAssemblyProgram(AddAssemblyProgramPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.level().isLoaded(packet.pos())) {
                BlockEntity be = serverPlayer.level().getBlockEntity(packet.pos());
                if (be instanceof AssemblyWriterBlockEntity writerBe) {
                    writerBe.addAssemblyProgram(serverPlayer, packet.name(), packet.lines());
                }
            }
        });
    }

    private static void handleCompileProgram(CompileProgramPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.level().isLoaded(packet.pos())) {
                BlockEntity be = serverPlayer.level().getBlockEntity(packet.pos());
                if (be instanceof CodeCompilerBlockEntity compilerBe) {
                    compilerBe.compileAndAddProgram(serverPlayer, packet.name(), packet.sourceLines());
                }
            }
        });
    }

    private static void handleRequestPrograms(RequestProgramsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                ProgramSavedData data = ProgramSavedData.get(serverPlayer.serverLevel());
                PacketDistributor.sendToPlayer(serverPlayer, new SyncProgramsPacket(data.getPrograms()));
            }
        });
    }

    private static void handleSyncPrograms(SyncProgramsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientProgramManager.setPrograms(packet.programs());
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
                    }
                }
            }
        });
    }
}
