package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.vm.GamepadState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One complete sample prevents mixing buttons and axes from different client updates. */
public record GamepadInputPacket(BlockPos pos, GamepadState input) implements CustomPacketPayload {
    public static final Type<GamepadInputPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "gamepad_input"));
    public static final StreamCodec<ByteBuf, GamepadInputPacket> STREAM_CODEC = StreamCodec.of(
            GamepadInputPacket::encode, GamepadInputPacket::decode);

    private static void encode(ByteBuf buffer, GamepadInputPacket packet) {
        BlockPos.STREAM_CODEC.encode(buffer, packet.pos());
        ByteBufCodecs.VAR_INT.encode(buffer, packet.input().buttons());
        for (int axis = 0; axis < 6; axis++) {
            ByteBufCodecs.VAR_INT.encode(buffer, packet.input().axis(axis));
        }
    }

    private static GamepadInputPacket decode(ByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        return new GamepadInputPacket(pos, new GamepadState(
                ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
