package games.jvrcruz.siliconcraft.blockentity;

import com.google.gson.Gson;
import games.jvrcruz.siliconcraft.api.peripheral.ComputerPeripheral;
import games.jvrcruz.siliconcraft.block.PeripheralDeviceBlock;
import games.jvrcruz.siliconcraft.block.PeripheralDeviceBlock.Kind;
import games.jvrcruz.siliconcraft.init.ModBlockEntities;
import games.jvrcruz.siliconcraft.compat.sable.SubLevelCoordinates;
import games.jvrcruz.siliconcraft.network.PeripheralNoticePacket;
import net.neoforged.neoforge.network.PacketDistributor;
import games.jvrcruz.siliconcraft.item.MemoryCardItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class PeripheralDeviceBlockEntity extends BlockEntity implements ComputerPeripheral {
    private static final Gson JSON = new Gson();
    private static final int MAX_RANGE = 64;
    private final int[] outputs = new int[6];
    private final PeripheralFlow flow = new PeripheralFlow(this);
    private ItemStack card = ItemStack.EMPTY;
    private int range = MAX_RANGE;
    private int mode;
    private boolean ignoreTransparent;
    private boolean laser;
    private int period;
    private int lastDistance = -1;
    private long lastMessage = Long.MIN_VALUE;
    private long eventCursor;
    private final ArrayDeque<String> clicks = new ArrayDeque<>();
    private String placedBy = "";

    public void placedBy(Player player) {
        if (kind() == Kind.PLAYER) {
            placedBy = player.getGameProfile().getName();
            setChanged();
        }
    }

    public PeripheralDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PERIPHERAL_DEVICE.get(), pos, state);
    }

    Kind kind() { return ((PeripheralDeviceBlock) getBlockState().getBlock()).kind(); }
    Direction front() { return getBlockState().getValue(BlockStateProperties.FACING); }
    public PeripheralFlow flow() { return flow; }
    public int output(Direction side) { return outputs[side.get3DDataValue()]; }

    public void insertCard(Player player, ItemStack stack) {
        if (!card.isEmpty() || !player.getUUID().equals(MemoryCardItem.owner(stack))) return;
        card = stack.copyWithCount(1);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        setChanged();
    }

    public void interact(Player player) {
        if (kind() == Kind.INVENTORY && player.getUUID().equals(MemoryCardItem.owner(card))) {
            ItemStack removed = card;
            card = ItemStack.EMPTY;
            if (!player.addItem(removed)) player.drop(removed, false);
            setChanged();
        } else if (kind() == Kind.PLAYER || kind() == Kind.BLOCK) {
            if (clicks.size() == 32) clicks.removeFirst();
            clicks.addLast(JSON.toJson(Map.of("event", "click", "player", player.getGameProfile().getName(),
                    "uuid", player.getStringUUID(), "item", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString())));
        }
    }

    public void dropCard() {
        if (!card.isEmpty() && level != null) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), card);
            card = ItemStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putIntArray("Outputs", outputs);
        tag.putInt("Range", range);
        tag.putInt("Mode", mode);
        tag.putBoolean("Transparent", ignoreTransparent);
        tag.putBoolean("Laser", laser);
        tag.putInt("Period", period);
        tag.putInt("FlowLimit", flow.limit());
        if (!placedBy.isEmpty()) tag.putString("PlacedBy", placedBy);
        if (!card.isEmpty()) tag.put("Card", card.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int[] storedOutputs = tag.getIntArray("Outputs");
        Arrays.fill(outputs, 0);
        for (int i = 0; i < Math.min(outputs.length, storedOutputs.length); i++) outputs[i] = clamp(storedOutputs[i], 0, 15);
        range = tag.contains("Range") ? clamp(tag.getInt("Range"), 1, MAX_RANGE) : MAX_RANGE;
        mode = clamp(tag.getInt("Mode"), 0, 2);
        ignoreTransparent = tag.getBoolean("Transparent");
        laser = tag.getBoolean("Laser");
        period = clamp(tag.getInt("Period"), 0, 1200);
        if (tag.contains("FlowLimit")) flow.limit(Math.max(0, tag.getInt("FlowLimit")));
        placedBy = tag.getString("PlacedBy");
        card = ItemStack.parseOptional(registries, tag.getCompound("Card"));
    }

    @Override
    public OptionalInt call(Context context, int method, int first, int second) {
        return invoke(context, method, first, second).map(result -> OptionalInt.of(result.value())).orElseGet(OptionalInt::empty);
    }

    @Override
    public Optional<Result> invoke(Context context, int method, int first, int second) {
        ServerLevel world = context.level();
        return switch (kind()) {
            case CHAT -> method == 0x402 ? poll(world, true) : Optional.empty();
            case DISTANCE -> distanceCall(world, method, first);
            case ENVIRONMENT -> environment(world, method, first);
            case PLAYER -> players(world, method, first, second);
            case BLOCK -> readBlock(world, method);
            case INVENTORY -> inventory(world, method, first);
            case REDSTONE -> redstone(world, method, first, second);
            case ENERGY, FLUID, GAS -> flow.call(method, first);
        };
    }

    @Override
    public Optional<Result> invokeString(Context context, int method, String input, int argument) {
        ServerLevel world = context.level();
        return switch (kind()) {
            case CHAT -> chat(world, method, input);
            case PLAYER -> playerQuery(world, method, input);
            case INVENTORY -> inventoryQuery(world, method, input, argument);
            default -> Optional.empty();
        };
    }

    private Optional<Result> chat(ServerLevel world, int method, String input) {
        if (method != 0x400 && method != 0x401 && method != 0x403 && method != 0x404 && method != 0x405) return Optional.empty();
        if (input.isBlank() || lastMessage != Long.MIN_VALUE && world.getGameTime() - lastMessage < 20) return number(0);
        List<ServerPlayer> targets = world.getServer().getPlayerList().getPlayers();
        if (method == 0x401 || method == 0x404 || method == 0x405) {
            int split = input.indexOf('|');
            if (split < 1 || split == input.length() - 1) return number(0);
            ServerPlayer target = world.getServer().getPlayerList().getPlayerByName(input.substring(0, split));
            if (target == null) return number(0);
            targets = List.of(target);
            input = input.substring(split + 1);
        }
        Component body = Component.literal(input);
        if (method == 0x403) {
            try {
                // Serialize only the visible text and legacy colors, never run player-supplied click actions.
                var parsed = Component.Serializer.fromJson(input, world.registryAccess());
                if (parsed == null) return number(0);
                body = Component.literal(parsed.getString()).withStyle(Style.EMPTY.withColor(parsed.getStyle().getColor()));
            } catch (RuntimeException invalidComponent) {
                return number(0);
            }
        }
        Component message = Component.literal("[Chat Box] ").append(body);
        for (ServerPlayer player : targets) {
            if (method == 0x404 || method == 0x405) {
                PacketDistributor.sendToPlayer(player, new PeripheralNoticePacket(method == 0x405, input));
            } else {
                player.sendSystemMessage(message);
            }
        }
        lastMessage = world.getGameTime();
        return number(targets.size());
    }

    private Optional<Result> poll(ServerLevel world, boolean chat) {
        if (!chat && !clicks.isEmpty()) return takeClick();
        var event = PeripheralDeviceEvents.poll(world, eventCursor, chat, worldCenter(world));
        if (event == null) return text("");
        eventCursor = event.sequence();
        return text(event.payload());
    }

    private Optional<Result> takeClick() {
        return text(clicks.isEmpty() ? "" : clicks.removeFirst());
    }

    private Optional<Result> distanceCall(ServerLevel world, int method, int arg) {
        switch (method) {
            case 0x410: return number(measure(world));
            case 0x411: return number(mode);
            case 0x412: mode = clamp(arg, 0, 2); break;
            case 0x413: return number(range);
            case 0x414: range = clamp(arg, 1, MAX_RANGE); break;
            case 0x415: return number(ignoreTransparent ? 1 : 0);
            case 0x416: ignoreTransparent = arg != 0; break;
            case 0x417: return number(laser ? 1 : 0);
            case 0x418: laser = arg != 0; break;
            case 0x419: return number(period);
            case 0x41a: period = arg == 0 ? 0 : clamp(arg, 5, 1200); break;
            case 0x41b: return number(lastDistance);
            default: return Optional.empty();
        }
        setChanged();
        return number(1);
    }

    public static void tickDistance(Level level, BlockPos pos, BlockState state, PeripheralDeviceBlockEntity device) {
        if (level instanceof ServerLevel world && device.period > 0 && world.getGameTime() % device.period == 0) {
            device.lastDistance = device.measure(world);
        }
    }

    private int measure(ServerLevel world) {
        Direction facing = front();
        Vec3 step = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 start = Vec3.atCenterOf(worldPosition).add(step.scale(0.5001));
        Vec3 end = start.add(step.scale(range));
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= range + 1; i++) {
            BlockPos pos = worldPosition.relative(facing, i);
            if (!world.hasChunkAt(pos)) {
                end = start.add(step.scale(i - 1));
                break;
            }
            if (mode == 1) continue;
            BlockState state = world.getBlockState(pos);
            if (ignoreTransparent && !state.canOcclude()) continue;
            var hit = state.getShape(world, pos).clip(start, end, pos);
            if (hit != null) {
                distance = start.distanceTo(hit.getLocation());
                end = hit.getLocation();
                break;
            }
        }
        if (mode != 0) {
            Vec3 entityStart = SubLevelCoordinates.worldPosition(world, start);
            Vec3 entityEnd = SubLevelCoordinates.worldPosition(world, end);
            for (var entity : world.getEntities((Entity) null, new AABB(entityStart, entityEnd).inflate(0.01),
                    candidate -> candidate.isPickable() && !candidate.isSpectator())) {
                if (entity.getBoundingBox().contains(entityStart)) distance = 0;
                else {
                    var hit = entity.getBoundingBox().clip(entityStart, entityEnd);
                    if (hit.isPresent()) distance = Math.min(distance, entityStart.distanceTo(hit.get()));
                }
            }
        }
        if (laser) {
            double length = Math.min(start.distanceTo(end), distance);
            for (double offset = 0.5; offset <= length; offset += 2) {
                Vec3 point = SubLevelCoordinates.worldPosition(world, start.add(step.scale(offset)));
                world.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0, 0, 0, 0);
            }
        }
        return Double.isFinite(distance) ? (int) Math.round(distance * 1000) : -1;
    }

    private Optional<Result> environment(ServerLevel world, int method, int arg) {
        return switch (method) {
            case 0x420 -> text(world.getBiome(worldPosition).unwrapKey().orElseThrow().location().toString());
            case 0x421 -> text(world.dimension().location().toString());
            case 0x422 -> number((int) Math.floorMod(world.getDayTime(), 24000));
            case 0x423 -> number(world.getMoonPhase());
            case 0x424 -> number(world.getBrightness(LightLayer.BLOCK, worldPosition));
            case 0x425 -> number(world.getBrightness(LightLayer.SKY, worldPosition));
            case 0x426 -> number(world.getMaxLocalRawBrightness(worldPosition));
            case 0x427 -> number(world.isThundering() ? 2 : world.isRaining() ? 1 : 0);
            case 0x428 -> number(WorldgenRandom.seedSlimeChunk(worldPosition.getX() >> 4, worldPosition.getZ() >> 4,
                    world.getSeed(), 987234911L).nextInt(10) == 0 ? 1 : 0);
            case 0x42b -> ModList.get().isLoaded("mekanism")
                    ? text(Double.toString(PeripheralGas.radiation(world, BlockPos.containing(worldCenter(world)))))
                    : text("");
            case 0x429 -> text(JSON.toJson(world.getServer().levelKeys().stream().map(key -> key.location().toString())
                    .sorted().skip(Math.max(0, arg)).limit(32).toList()));
            case 0x42c -> text(JSON.toJson(SubLevelCoordinates.nearbyStructures(world, scanBox(world, arg))));
            case 0x42a -> text(JSON.toJson(world.getEntities(null, scanBox(world, arg))
                    .stream().limit(24).map(entity -> Map.of("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                            "uuid", entity.getStringUUID(), "x", entity.getX(), "y", entity.getY(), "z", entity.getZ())).toList()));
            default -> Optional.empty();
        };
    }

    private Optional<Result> players(ServerLevel world, int method, int first, int second) {
        var players = world.getServer().getPlayerList().getPlayers();
        Vec3 center = worldCenter(world);
        int radius = clamp(first, 0, 256);
        return switch (method) {
            case 0x430 -> text(JSON.toJson(players.stream().skip(Math.max(0, first)).limit(64).map(player -> player.getGameProfile().getName()).toList()));
            case 0x431 -> text(playerNames(players.stream().filter(player -> player.level() == world
                    && player.distanceToSqr(center) <= (double) radius * radius)
                    .skip(Math.max(0, second)).limit(64).toList()));
            case 0x434 -> poll(world, false);
            case 0x436 -> text(placedBy);
            case 0x437 -> {
                AABB box = new AABB(center, center).inflate(radius);
                yield text(playerNames(world.players().stream().filter(player -> box.contains(player.position()))
                        .skip(Math.max(0, second)).limit(64).toList()));
            }
            default -> Optional.empty();
        };
    }

    private String playerNames(List<ServerPlayer> players) {
        return JSON.toJson(players.stream().map(player -> player.getGameProfile().getName()).toList());
    }

    private Optional<Result> playerQuery(ServerLevel world, int method, String input) {
        if (method == 0x433) {
            int[] coordinates = parse(input, 6);
            if (coordinates == null) return Optional.empty();
            var area = new AABB(Math.min(coordinates[0], coordinates[3]), Math.min(coordinates[1], coordinates[4]),
                    Math.min(coordinates[2], coordinates[5]), Math.max(coordinates[0], coordinates[3]) + 1.0,
                    Math.max(coordinates[1], coordinates[4]) + 1.0, Math.max(coordinates[2], coordinates[5]) + 1.0);
            return text(playerNames(world.players().stream().filter(player -> area.contains(player.position())).limit(64).toList()));
        }
        if (method == 0x435) {
            String[] parts = input.split("\\|", 2);
            if (parts.length != 2) return Optional.empty();
            ServerPlayer player = world.getServer().getPlayerList().getPlayerByName(parts[0]);
            var id = ResourceLocation.tryParse(parts[1]);
            if (player == null || id == null || !BuiltInRegistries.CUSTOM_STAT.containsKey(id)) return number(-1);
            return number(player.getStats().getValue(Stats.CUSTOM.get(id)));
        }
        if (method != 0x432) return Optional.empty();
        ServerPlayer player = world.getServer().getPlayerList().getPlayerByName(input);
        if (player == null) return text("");
        return text(JSON.toJson(Map.of("name", player.getGameProfile().getName(), "uuid", player.getStringUUID(),
                "dimension", player.level().dimension().location().toString(), "x", player.getX(), "y", player.getY(),
                "z", player.getZ(), "yaw", player.getYRot(), "pitch", player.getXRot(), "health", player.getHealth(),
                "food", player.getFoodData().getFoodLevel())));
    }

    private Optional<Result> readBlock(ServerLevel world, int method) {
        if (method == 0x444) return takeClick();
        BlockPos target = worldPosition.relative(front());
        if (!accessible(world, target)) return Optional.empty();
        BlockState state = world.getBlockState(target);
        return switch (method) {
            case 0x440 -> text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            case 0x441 -> text(JSON.toJson(state.getValues().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> entry.getKey().getName(), entry -> entry.getValue().toString()))));
            case 0x442 -> {
                var entity = world.getBlockEntity(target);
                if (entity == null || entity instanceof ComputerBlockEntity || entity instanceof PeripheralDeviceBlockEntity) yield text("");
                yield text(entity.getUpdateTag(world.registryAccess()).toString());
            }
            case 0x443 -> number(world.getBlockEntity(target) == null ? 0 : 1);
            default -> Optional.empty();
        };
    }

    private ServerPlayer cardOwner(ServerLevel world) {
        var owner = MemoryCardItem.owner(card);
        return owner == null ? null : world.getServer().getPlayerList().getPlayer(owner);
    }

    private Optional<Result> inventory(ServerLevel world, int method, int arg) {
        ServerPlayer owner = cardOwner(world);
        if (method == 0x450) return text(owner == null ? "" : JSON.toJson(Map.of(
                "name", owner.getGameProfile().getName(), "uuid", owner.getStringUUID())));
        if (owner == null) return Optional.empty();
        var inventory = owner.getInventory();
        return switch (method) {
            case 0x451 -> number(inventory.getContainerSize());
            case 0x452 -> arg >= 0 && arg < inventory.getContainerSize() ? text(JSON.toJson(stackInfo(inventory.getItem(arg)))) : Optional.empty();
            case 0x453 -> number(inventory.getFreeSlot());
            case 0x454 -> number(inventory.isEmpty() ? 1 : 0);
            case 0x455 -> text(JSON.toJson(stackInfo(owner.getMainHandItem())));
            case 0x456 -> text(JSON.toJson(stackInfo(owner.getOffhandItem())));
            case 0x45b -> number(inventory.selected);
            case 0x45c -> number((int) inventory.items.stream().filter(ItemStack::isEmpty).count());
            case 0x45d -> arg >= 0 && arg < 4 ? number(inventory.armor.get(arg).isEmpty() ? 0 : 1) : Optional.empty();
            case 0x45e -> text(ModList.get().isLoaded("curios") ? JSON.toJson(PeripheralCurios.sizes(owner)) : "{}");
            case 0x45a -> text(JSON.toJson(java.util.stream.IntStream.range(36, 40)
                    .mapToObj(slot -> stackInfo(inventory.getItem(slot))).toList()));
            case 0x459 -> text(JSON.toJson(java.util.stream.IntStream.range(0, inventory.getContainerSize())
                    .mapToObj(slot -> stackInfo(inventory.getItem(slot))).toList()));
            default -> Optional.empty();
        };
    }

    private static Map<String, Object> stackInfo(ItemStack stack) {
        return stack.isEmpty() ? Map.of() : Map.of("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                "count", stack.getCount(), "maxCount", stack.getMaxStackSize(), "name", stack.getHoverName().getString());
    }

    private Optional<Result> inventoryQuery(ServerLevel world, int method, String input, int value) {
        var owner = cardOwner(world);
        if (owner == null) return Optional.empty();
        boolean curios = ModList.get().isLoaded("curios");
        if (method == 0x45f) {
            var slots = curios ? PeripheralCurios.slots(owner, input) : null;
            return slots == null ? text("[]") : text(JSON.toJson(java.util.stream.IntStream.range(0, slots.getSlots())
                    .skip(Math.max(0, value)).limit(32).mapToObj(slot -> stackInfo(slots.getStackInSlot(slot))).toList()));
        }
        if (method == 0x457 || method == 0x458) {
            return transfer(world, owner, new PlayerInvWrapper(owner.getInventory()), input, value, method == 0x457);
        }
        if (method == 0x480 || method == 0x481) {
            int split = input.indexOf('|');
            if (!curios || split < 1) return number(0);
            var slots = PeripheralCurios.slots(owner, input.substring(0, split));
            return slots == null ? number(0) : transfer(world, owner, slots, input.substring(split + 1), value, method == 0x480);
        }

        if (method >= 0x482 && method <= 0x487) {
            String[] selector = input.split("\\|", 2);
            IItemHandlerModifiable slots = selector.length == 1 ? new PlayerInvWrapper(owner.getInventory())
                    : curios ? PeripheralCurios.slots(owner, selector[0]) : null;
            int argumentCount = switch (method) {
                case 0x482, 0x487 -> 1;
                case 0x485, 0x486 -> 2;
                default -> 4;
            };
            int[] arguments = parse(selector[selector.length - 1], argumentCount);
            if (slots == null || arguments == null || arguments[0] < 0 || arguments[0] >= slots.getSlots()) return Optional.empty();
            int containerSlot = arguments[0];
            ItemStack container = slots.getStackInSlot(containerSlot);
            if (method == 0x487) {
                return text(container.isEmpty() ? "" : container.save(world.registryAccess()).toString());
            }
            if (container.getCount() != 1) return Optional.empty();
            // Use a copy so inspecting an item capability cannot modify the equipped stack.
            ItemStack working = container.copy();
            if (method == 0x485 || method == 0x486) {
                var fluids = working.getCapability(Capabilities.FluidHandler.ITEM);
                int sideNumber = arguments[1];
                if (fluids == null || sideNumber < 0 || sideNumber >= 6 || value <= 0) return number(0);
                Direction side = Direction.from3DDataValue(sideNumber);
                BlockPos target = worldPosition.relative(side);
                if (!accessible(world, target)) return number(0);
                var adjacent = world.getCapability(Capabilities.FluidHandler.BLOCK, target, side.getOpposite());
                if (adjacent == null) return number(0);
                var moved = method == 0x485 ? FluidUtil.tryFluidTransfer(fluids, adjacent, value, true)
                        : FluidUtil.tryFluidTransfer(adjacent, fluids, value, true);
                if (!moved.isEmpty()) slots.setStackInSlot(containerSlot, fluids.getContainer());
                return number(moved.getAmount());
            }
            var handler = working.getCapability(Capabilities.ItemHandler.ITEM);
            if (method == 0x482) {
                var fluids = working.getCapability(Capabilities.FluidHandler.ITEM);
                return text(JSON.toJson(Map.of("itemSlots", handler == null ? 0 : handler.getSlots(),
                        "items", handler == null ? List.of() : java.util.stream.IntStream.range(0, handler.getSlots())
                                .skip(Math.max(0, value)).limit(32).mapToObj(slot -> stackInfo(handler.getStackInSlot(slot))).toList(),
                        "tanks", fluids == null ? List.of() : java.util.stream.IntStream.range(0, fluids.getTanks()).limit(24)
                                .mapToObj(tank -> {
                                    var fluid = fluids.getFluidInTank(tank);
                                    return Map.of("id", BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(),
                                            "amount", fluid.getAmount(), "capacity", fluids.getTankCapacity(tank));
                                }).toList())));
            }
            if (handler == null) return number(0);
            int moved = transferItems(world, owner, handler, arguments[1], arguments[2], arguments[3], value, method == 0x483);
            if (moved > 0 || !ItemStack.isSameItemSameComponents(container, working)) {
                slots.setStackInSlot(containerSlot, working);
            }
            return number(moved);
        }
        return Optional.empty();
    }

    private Optional<Result> transfer(ServerLevel world, ServerPlayer owner, IItemHandler player,
                                      String input, int count, boolean importing) {
        int[] arguments = parse(input, 3);
        return arguments == null ? number(0) : number(transferItems(world, owner, player,
                arguments[0], arguments[1], arguments[2], count, importing));
    }

    private int transferItems(ServerLevel world, ServerPlayer owner, IItemHandler player, int sideNumber, int storageSlot,
                              int playerSlot, int count, boolean importing) {
        if (sideNumber < 0 || sideNumber >= 6 || count <= 0) return 0;
        Direction side = Direction.from3DDataValue(sideNumber);
        BlockPos target = worldPosition.relative(side);
        if (!accessible(world, target)) return 0;
        var external = world.getCapability(Capabilities.ItemHandler.BLOCK, target, side.getOpposite());
        if (external == null) return 0;
        var from = importing ? external : player;
        var to = importing ? player : external;
        int fromSlot = importing ? storageSlot : playerSlot, toSlot = importing ? playerSlot : storageSlot;
        if (fromSlot < 0 || fromSlot >= from.getSlots() || toSlot < 0 || toSlot >= to.getSlots()) return 0;
        ItemStack offered = from.extractItem(fromSlot, Math.min(count, 64), true);
        if (offered.isEmpty()) return 0;
        int accepted = offered.getCount() - to.insertItem(toSlot, offered.copy(), true).getCount();
        if (accepted <= 0) return 0;
        ItemStack extracted = from.extractItem(fromSlot, accepted, false);
        if (extracted.isEmpty()) return 0;
        int extractedCount = extracted.getCount();
        ItemStack remaining = ItemStack.isSameItemSameComponents(extracted, offered) ? to.insertItem(toSlot, extracted, false) : extracted;
        int moved = extractedCount - remaining.getCount();
        if (!remaining.isEmpty()) {
            remaining = from.insertItem(fromSlot, remaining, false);
            if (!remaining.isEmpty()) remaining = ItemHandlerHelper.insertItemStacked(from, remaining, false);
            if (!remaining.isEmpty()) {
                if (importing) Containers.dropItemStack(world, target.getX(), target.getY(), target.getZ(), remaining);
                else owner.drop(remaining, false);
            }
        }
        return moved;
    }

    private Optional<Result> redstone(ServerLevel world, int method, int side, int value) {
        if (side < 0 || side >= 6) return Optional.empty();
        Direction direction = Direction.from3DDataValue(side);
        return switch (method) {
            case 0x460 -> number(accessible(world, worldPosition.relative(direction))
                    ? world.getSignal(worldPosition.relative(direction), direction) : 0);
            case 0x461 -> number(outputs[side]);
            case 0x462 -> {
                int power = clamp(value, 0, 15);
                if (outputs[side] != power) {
                    outputs[side] = power;
                    setChanged();
                    BlockPos neighbor = worldPosition.relative(direction);
                    if (accessible(world, neighbor)) {
                        var block = getBlockState().getBlock();
                        world.neighborChanged(neighbor, block, worldPosition);
                        for (Direction adjacentSide : Direction.values()) {
                            if (adjacentSide == direction.getOpposite()) continue;
                            BlockPos adjacent = neighbor.relative(adjacentSide);
                            if (accessible(world, adjacent)) world.neighborChanged(adjacent, block, neighbor);
                        }
                    }
                }
                yield number(power);
            }
            default -> Optional.empty();
        };
    }

    boolean accessible(ServerLevel world, BlockPos target) {
        return world.hasChunkAt(target) && SubLevelCoordinates.sameSubLevel(world, worldPosition, target);
    }

    private AABB scanBox(ServerLevel world, int range) {
        Vec3 center = worldCenter(world);
        return new AABB(center, center).inflate(clamp(range, 1, 32));
    }

    private Vec3 worldCenter(ServerLevel world) {
        return SubLevelCoordinates.worldPosition(world, Vec3.atCenterOf(worldPosition));
    }

    private static int[] parse(String input, int size) {
        String[] parts = input.split(",", -1);
        if (parts.length != size) return null;
        int[] values = new int[size];
        try {
            for (int i = 0; i < size; i++) values[i] = Integer.parseInt(parts[i].strip());
        } catch (NumberFormatException invalid) {
            return null;
        }
        return values;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    private static Optional<Result> number(int value) { return Optional.of(Result.number(value)); }
    private static Optional<Result> text(String value) { return Optional.of(Result.text(value)); }
}
