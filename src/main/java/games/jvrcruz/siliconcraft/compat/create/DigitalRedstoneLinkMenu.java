package games.jvrcruz.siliconcraft.compat.create;

import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class DigitalRedstoneLinkMenu extends AbstractContainerMenu {
    private final DigitalRedstoneLinkEntity entity;
    private final String[] names = new String[DigitalRedstoneLinkEntity.CHANNELS];
    private final boolean[] powered = new boolean[DigitalRedstoneLinkEntity.CHANNELS];

    DigitalRedstoneLinkMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(id, inventory, null, new SimpleContainer(DigitalRedstoneLinkEntity.CHANNELS * 2));
        for (int i = 0; i < names.length; i++) names[i] = buf.readUtf(DigitalRedstoneLinkEntity.MAX_NAME);
    }

    DigitalRedstoneLinkMenu(int id, Inventory inventory, DigitalRedstoneLinkEntity entity) {
        this(id, inventory, entity, entity.frequencies());
        for (int i = 0; i < names.length; i++) names[i] = entity.channelName(i);
    }

    private DigitalRedstoneLinkMenu(int id, Inventory inventory, DigitalRedstoneLinkEntity entity, SimpleContainer frequencies) {
        super(CreatePeripherals.LINK_MENU.get(), id);
        this.entity = entity;
        for (int i = 0; i < names.length; i++) {
            int row = i;
            for (int column = 0; column < 2; column++) {
                addSlot(new Slot(frequencies, row * 2 + column, 109 + column * 21, 38 + row * 24) {
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                    @Override public boolean mayPickup(Player player) { return false; }
                });
            }
            addDataSlot(new DataSlot() {
                @Override public int get() { return isPowered(row) ? 1 : 0; }
                @Override public void set(int value) { powered[row] = value != 0; }
            });
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 145 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 203));
        }
    }

    String channelName(int row) {
        return names[row];
    }

    boolean isPowered(int row) {
        return entity != null ? entity.isChannelOn(row) : powered[row];
    }

    boolean renameChannel(int row, String nickname, Player player) {
        return entity != null && stillValid(player) && entity.rename(row, nickname);
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= 0 && slot < DigitalRedstoneLinkEntity.CHANNELS * 2) {
            if (entity != null && type == ClickType.PICKUP && stillValid(player)) {
                entity.setFrequency(slot, button == 1 ? ItemStack.EMPTY : getCarried());
            }
            return;
        }
        super.clicked(slot, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (entity == null) return true;
        if (entity.isRemoved() || !(player.level() instanceof ServerLevel level) || entity.getLevel() != level) return false;
        var pos = entity.getBlockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) == entity
                && SubLevelCoordinates.distanceSquared(level, player.position(), Vec3.atCenterOf(pos)) <= 64;
    }
}
