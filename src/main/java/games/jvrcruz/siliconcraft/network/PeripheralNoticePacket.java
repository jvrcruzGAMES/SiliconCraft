package games.jvrcruz.siliconcraft.network;

import games.jvrcruz.siliconcraft.Siliconcraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PeripheralNoticePacket(boolean narration, String message) implements CustomPacketPayload {
    public static final Type<PeripheralNoticePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Siliconcraft.MODID, "peripheral_notice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PeripheralNoticePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PeripheralNoticePacket::narration, ByteBufCodecs.stringUtf8(256),
            PeripheralNoticePacket::message, PeripheralNoticePacket::new);

    @Override
    public Type<PeripheralNoticePacket> type() { return TYPE; }
}
