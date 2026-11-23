package games.jvrcruz.aerosilicon;

import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.Sable;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

import static com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER;

final class AirshipControllerEntity extends BlockEntity implements ComputerPeripheral, MenuProvider {
    static final int CHANNELS = 4;
    static final int MAX_NAME = 32;
    static final int SET_CHANNEL = 0x310;
    static final int GET_CHANNEL = 0x311;

    private final SimpleContainer frequencies = new SimpleContainer(CHANNELS * 2);
    private final Channel[] channels = new Channel[CHANNELS];

    AirshipControllerEntity(BlockPos pos, BlockState state) {
        super(AeroSilicon.CONTROLLER_ENTITY.get(), pos, state);
        for (int i = 0; i < channels.length; i++) channels[i] = new Channel();
    }

    SimpleContainer frequencies() {
        return frequencies;
    }

    String channelName(int row) {
        return channels[row].name;
    }

    boolean isChannelOn(int row) {
        return channels[row].powered;
    }

    boolean rename(int row, String rawName) {
        if (row < 0 || row >= CHANNELS || !validName(rawName)) {
            return false;
        }
        String name = rawName.strip();
        Channel duplicate = findChannel(name);
        if (duplicate != null && duplicate != channels[row]) return false;

        Channel channel = channels[row];
        if (!channel.name.equals(name)) {
            setPowered(channel, false);
            channel.name = name;
            setChanged();
        }
        return true;
    }

    void setFrequency(int slot, ItemStack stack) {
        Channel channel = channels[slot / 2];
        setPowered(channel, false);
        frequencies.setItem(slot, stack.copyWithCount(1));
        updateKey(slot / 2);
        setChanged();
    }

    private void updateKey(int row) {
        channels[row].key = Couple.create(Frequency.of(frequencies.getItem(row * 2)),
                Frequency.of(frequencies.getItem(row * 2 + 1)));
    }

    private static boolean validName(String name) {
        return name.length() <= MAX_NAME && name.chars().noneMatch(Character::isISOControl);
    }

    private boolean configured(Channel channel) {
        return !channel.key.both(frequency -> frequency == Frequency.EMPTY);
    }

    private Channel findChannel(String nickname) {
        if (nickname.isBlank()) return null;
        String normalized = nickname.strip().toLowerCase(Locale.ROOT);
        for (Channel channel : channels) {
            if (channel.name.toLowerCase(Locale.ROOT).equals(normalized)) return channel;
        }
        return null;
    }

    private void setPowered(Channel channel, boolean powered) {
        if (!(level instanceof ServerLevel server) || channel.powered == powered) return;

        channel.powered = powered;
        if (powered) {
            REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(server, channel);
            refreshReceiverRange(server, channel);
        } else {
            REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(server, channel);
            refreshReceiverRange(server, channel);
        }
    }

    void refreshLinks() {
        if (level instanceof ServerLevel server && server.getGameTime() % 10 == 0) {
            for (Channel channel : channels) {
                if (channel.powered) {
                    REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(server, channel);
                    refreshReceiverRange(server, channel);
                }
            }
        }
    }

    private void refreshReceiverRange(ServerLevel level, Channel channel) {
        Set<IRedstoneLinkable> network = REDSTONE_LINK_NETWORK_HANDLER.networksIn(level)
                .getOrDefault(channel.key, Set.of());
        double range = AllConfigs.server().logistics.linkRange.get();

        // Create updates receivers in the transmitter's current range. Recompute
        // receivers left behind by a moving ship using their remaining transmitters.
        Set<IRedstoneLinkable> nearby = new HashSet<>();
        if (channel.powered) {
            for (IRedstoneLinkable link : network) {
                if (link.isAlive() && link.isListening() && inRange(level, channel, link, range)) nearby.add(link);
            }
        }

        for (IRedstoneLinkable receiver : channel.receivers) {
            if (nearby.contains(receiver) || !network.contains(receiver) || !receiver.isAlive()
                    || !receiver.isListening()) continue;
            int strength = 0;
            for (IRedstoneLinkable transmitter : network) {
                int signal = transmitter.getTransmittedStrength();
                if (signal > strength && !transmitter.isListening() && transmitter.isAlive()
                        && inRange(level, receiver, transmitter, range)) {
                    strength = signal;
                    if (strength >= 15) break;
                }
            }
            receiver.setReceivedStrength(strength);
        }
        channel.receivers = nearby;
    }

    private static boolean inRange(ServerLevel level, IRedstoneLinkable first, IRedstoneLinkable second, double range) {
        return Sable.HELPER.distanceSquaredWithSubLevels(level, Vec3.atCenterOf(first.getLocation()),
                Vec3.atCenterOf(second.getLocation())) < range * range;
    }

    private void stopLinks() {
        for (Channel channel : channels) setPowered(channel, false);
    }

    @Override
    public void setRemoved() {
        stopLinks();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        stopLinks();
        super.onChunkUnloaded();
    }

    @Override
    public OptionalInt call(Context context, int method, int arg0, int arg1) {
        return OptionalInt.empty();
    }

    @Override
    public OptionalInt callString(Context context, int method, String nickname, int value) {
        if (method != SET_CHANNEL && method != GET_CHANNEL) return OptionalInt.empty();

        Channel channel = findChannel(nickname);
        if (channel == null) return OptionalInt.of(-1);
        if (method == SET_CHANNEL && value != 0 && !configured(channel)) return OptionalInt.of(-2);
        if (method == SET_CHANNEL) setPowered(channel, value != 0);
        return OptionalInt.of(channel.powered ? 1 : 0);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag settings = new ListTag();
        for (int i = 0; i < CHANNELS; i++) {
            CompoundTag row = new CompoundTag();
            row.putString("Name", channelName(i));
            row.put("First", frequencies.getItem(i * 2).saveOptional(registries));
            row.put("Second", frequencies.getItem(i * 2 + 1).saveOptional(registries));
            settings.add(row);
        }
        tag.put("Channels", settings);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stopLinks();
        ListTag settings = tag.getList("Channels", Tag.TAG_COMPOUND);
        for (Channel channel : channels) channel.name = "";
        for (int i = 0; i < CHANNELS; i++) {
            CompoundTag row = settings.getCompound(i);
            String name = row.getString("Name").strip();
            if (validName(name) && findChannel(name) == null) channels[i].name = name;
            frequencies.setItem(i * 2, ItemStack.parseOptional(registries, row.getCompound("First")).copyWithCount(1));
            frequencies.setItem(i * 2 + 1, ItemStack.parseOptional(registries, row.getCompound("Second")).copyWithCount(1));
            updateKey(i);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.aerosilicon.airship_controller");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new AirshipControllerMenu(id, inventory, this);
    }

    void writeMenuData(FriendlyByteBuf buf) {
        for (Channel channel : channels) buf.writeUtf(channel.name, MAX_NAME);
    }

    private final class Channel implements IRedstoneLinkable {
        private String name = "";
        private Couple<Frequency> key = Couple.create(Frequency.EMPTY, Frequency.EMPTY);
        private boolean powered;
        private Set<IRedstoneLinkable> receivers = Set.of();

        @Override public int getTransmittedStrength() { return powered ? 15 : 0; }
        @Override public void setReceivedStrength(int strength) {}
        @Override public boolean isListening() { return false; }
        @Override public boolean isAlive() { return powered && !isRemoved(); }
        @Override public Couple<Frequency> getNetworkKey() { return key; }
        @Override public BlockPos getLocation() { return worldPosition; }
    }
}
