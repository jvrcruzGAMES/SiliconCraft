package games.jvrcruz.aerosilicon;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

record RenameChannelPayload(int menuId, int row, String name) implements CustomPacketPayload {
    static final Type<RenameChannelPayload> TYPE = new Type<>(AeroSilicon.id("rename_channel"));
    static final StreamCodec<ByteBuf, RenameChannelPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RenameChannelPayload::menuId,
            ByteBufCodecs.VAR_INT, RenameChannelPayload::row,
            ByteBufCodecs.stringUtf8(AirshipControllerEntity.MAX_NAME), RenameChannelPayload::name,
            RenameChannelPayload::new);

    @Override
    public Type<RenameChannelPayload> type() {
        return TYPE;
    }

    static void handle(RenameChannelPayload payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof AirshipControllerMenu menu && menu.containerId == payload.menuId) {
            if (menu.renameChannel(payload.row, payload.name, context.player())) {
                context.player().displayClientMessage(Component.translatable("screen.aerosilicon.saved"), true);
            } else {
                context.player().displayClientMessage(Component.translatable("screen.aerosilicon.invalid_name"), true);
            }
        }
    }
}
