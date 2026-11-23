package games.jvrcruz.siliconcraft.init;

import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.Locale;
import java.util.OptionalInt;

final class BuiltinPeripherals {
    private static final int SAMPLE_RATE = 8000;

    private static final ComputerPeripheral SPEAKER = new ComputerPeripheral() {
        @Override
        public boolean autoWrap() {
            return true;
        }

        @Override
        public OptionalInt call(Context context, int method, int arg0, int arg1) {
            return switch (method) {
                case 0x100 -> OptionalInt.of(SAMPLE_RATE);
                case 0x101 -> OptionalInt.of(playTone(context, arg0, arg1));
                default -> OptionalInt.empty();
            };
        }
    };

    private static final ComputerPeripheral NOTE_BLOCK = new ComputerPeripheral() {
        @Override
        public OptionalInt call(Context context, int method, int arg0, int arg1) {
            var state = context.level().getBlockState(context.peripheralPos());
            return switch (method) {
                case 0x200 -> OptionalInt.of(state.getValue(BlockStateProperties.NOTE));
                case 0x201 -> {
                    var updated = state.setValue(BlockStateProperties.NOTE, arg0);
                    context.level().setBlock(context.peripheralPos(), updated, Block.UPDATE_ALL);
                    yield OptionalInt.of(arg0);
                }
                case 0x202 -> OptionalInt.of(playNote(context, state, arg0,
                        state.getValue(NoteBlock.INSTRUMENT)));
                default -> OptionalInt.empty();
            };
        }

        @Override
        public OptionalInt callString(Context context, int method, String text, int argument) {
            if (method != 0x203) return OptionalInt.empty();
            var state = context.level().getBlockState(context.peripheralPos());
            String instrumentName = text.strip().toLowerCase(Locale.ROOT);
            for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
                if (instrument.getSerializedName().equals(instrumentName)) {
                    return OptionalInt.of(playNote(context, state, argument, instrument));
                }
            }
            throw new IllegalArgumentException("Unknown note block instrument: " + text);
        }
    };

    private static int playNote(ComputerPeripheral.Context context, BlockState state, int note, NoteBlockInstrument instrument) {
        if (note == -1) note = state.getValue(BlockStateProperties.NOTE);
        BlockState playback = state.setValue(BlockStateProperties.NOTE, note)
                .setValue(NoteBlock.INSTRUMENT, instrument);
        var level = context.level();
        var pos = context.peripheralPos();
        if (!instrument.worksAboveNoteBlock() && !level.getBlockState(pos.above()).isAir()) return 0;

        // Run vanilla's note event with a temporary state. Queuing a block event would
        // read the stored instrument again when Minecraft processes the event.
        if (!playback.triggerEvent(level, pos, 0, 0)) return 0;
        level.gameEvent(null, GameEvent.NOTE_BLOCK_PLAY, pos);
        return 1;
    }

    private BuiltinPeripherals() {}

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(ComputerPeripheral.CAPABILITY, (level, pos, state, entity, side) -> SPEAKER,
                ModBlocks.SPEAKER.get());
        event.registerBlock(ComputerPeripheral.CAPABILITY, (level, pos, state, entity, side) -> NOTE_BLOCK,
                Blocks.NOTE_BLOCK);
    }

    private static int playTone(ComputerPeripheral.Context context, int frequency, int durationMs) {
        if (frequency < 1 || frequency > SAMPLE_RATE / 2 || durationMs < 1 || durationMs > 1000) {
            throw new IllegalArgumentException("Speaker tone requires 1–4000 Hz and 1–1000 ms");
        }
        var computerPos = context.computerPos();
        LevelChunk chunk = context.level().getChunkSource().getChunkNow(computerPos.getX() >> 4, computerPos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(computerPos) instanceof ComputerBlockEntity computer)) {
            throw new IllegalStateException("Computer is no longer loaded");
        }

        byte[] samples = new byte[durationMs * (SAMPLE_RATE / 1000)];
        int period = SAMPLE_RATE / frequency;
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (byte) (i % period < period / 2 ? 200 : 50);
        }
        computer.broadcastSpeakerAudio(context.level(), context.peripheralPos(), samples);
        return samples.length;
    }
}
