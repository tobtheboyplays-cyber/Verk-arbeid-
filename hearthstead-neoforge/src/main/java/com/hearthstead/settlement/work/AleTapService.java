package com.hearthstead.settlement.work;

import com.hearthstead.block.AleTapBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/** Physical one-barrel transactions. No balance, remote stock search, free pour or dropped output. */
public final class AleTapService {
    public static final int PRICE = 1;
    private static final int MAX_OWNERSHIP_ROWS = 2048;
    private static final double PLAYER_REACH_SQR = 4.5 * 4.5;
    private static final Map<ServerPlayer, Long> LAST_PURCHASE = new WeakHashMap<>();
    private AleTapService() {}

    public enum Result {
        SUCCESS("success"), INVALID_TAP("invalid_tap"), NO_BARREL("no_barrel"),
        OUT_OF_REACH("out_of_reach"), BLOCKED("blocked"), NOT_TAVERN("not_tavern"),
        NEEDS_BOTTLE("needs_bottle"), NEEDS_COIN("needs_coin"), EMPTY("empty"),
        NO_ROOM("no_room"), BARREL_FULL("barrel_full"), RETRY("retry"), NOT_ALLOWED("not_allowed");
        private final String key;
        Result(String key) { this.key = key; }
        public Component message() { return Component.translatable("hearthstead.ale_tap." + key); }
    }

    /** Exact adjacent vanilla barrel behind the current, real tap; never loads chunks. */
    @Nullable
    public static BarrelBlockEntity barrel(ServerLevel level, BlockPos tap) {
        if (level == null || tap == null || !level.hasChunkAt(tap)) return null;
        var state = level.getBlockState(tap);
        if (!(state.getBlock() instanceof AleTapBlock)) return null;
        Direction facing = state.getValue(AleTapBlock.FACING);
        if (!facing.getAxis().isHorizontal()) return null;
        BlockPos back = tap.relative(facing.getOpposite());
        if (!level.hasChunkAt(back) || !level.getBlockState(back).is(Blocks.BARREL)) return null;
        return level.getBlockEntity(back) instanceof BarrelBlockEntity barrel ? barrel : null;
    }

    /** Bounded four-neighbour lookup; multiple outlets are refused as ambiguous routing. */
    @Nullable
    public static BlockPos resolveTapForBarrel(ServerLevel level, Building tavern, BlockPos barrelPos) {
        if (level == null || tavern == null || !tavern.valid || tavern.type != BuildingType.TAVERN
                || barrelPos == null || !tavern.contains(barrelPos) || !level.hasChunkAt(barrelPos)) return null;
        BlockPos found = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos tap = barrelPos.relative(side);
            if (!tavern.contains(tap)) continue;
            BarrelBlockEntity candidate = barrel(level, tap);
            if (candidate != null && candidate.getBlockPos().equals(barrelPos) && ownsTap(level, tavern, tap)) {
                if (found != null) return null;
                found = tap.immutable();
            }
        }
        return found;
    }

    /** Exact shared registered-Tavern ownership check for refill and serving callers. */
    public static boolean ownsTap(ServerLevel level, Building tavern, BlockPos tap) {
        BarrelBlockEntity backing = barrel(level, tap);
        return backing != null && tavern != null && owningTavern(level, tap, backing.getBlockPos()) == tavern;
    }

    /** Actual collision-shape centre, not a guessed full-block contact. */
    @Nullable
    public static Vec3 interactionPoint(ServerLevel level, BlockPos tap) {
        if (level == null || tap == null || !level.hasChunkAt(tap)) return null;
        var state = level.getBlockState(tap);
        if (!(state.getBlock() instanceof AleTapBlock)) return null;
        var shape = state.getCollisionShape(level, tap);
        if (shape.isEmpty()) return null;
        return shape.bounds().getCenter().add(tap.getX(), tap.getY(), tap.getZ());
    }

    private static boolean visible(ServerLevel level, Entity actor, BlockPos tap, Vec3 contact) {
        var hit = level.clip(new ClipContext(actor.getEyePosition(), contact,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(tap);
    }

    /** Physical employer-bound access also permits returning existing cargo outside work hours. */
    public static boolean hostContact(SettlerEntity host, Building tavern, BlockPos tap) {
        if (host == null || tavern == null || !(host.level() instanceof ServerLevel level) || !host.isAlive()
                || host.getProfession() != Profession.INNKEEPER || host.settlement() == null
                || Employment.employerOf(host.settlement(), host.getUUID()) != tavern) return false;
        var barrel = barrel(level, tap);
        Vec3 contact = interactionPoint(level, tap);
        return barrel != null && contact != null && owningTavern(level, tap, barrel.getBlockPos()) == tavern
            && host.position().distanceToSqr(contact) <= ContainerApproach.CONTACT_DISTANCE_SQR
            && visible(level, host, tap, contact);
    }

    /** Tavern ownership is communal; there is no separate player-owner ACL in the current settlement model. */
    @Nullable
    private static Building owningTavern(ServerLevel level, BlockPos tap, BlockPos barrel) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) return null;
        Building found = null;
        int inspected = 0;
        for (Settlement settlement : data.settlements.values()) {
            if (++inspected > MAX_OWNERSHIP_ROWS || settlement == null) return null;
            for (Building candidate : settlement.buildings) {
                if (++inspected > MAX_OWNERSHIP_ROWS) return null;
                if (candidate == null || !candidate.valid || candidate.type != BuildingType.TAVERN
                        || !candidate.contains(tap) || !candidate.contains(barrel)
                        || candidate.plaquePos == null || !level.hasChunkAt(candidate.plaquePos)
                        || !(level.getBlockEntity(candidate.plaquePos) instanceof PlaqueBlockEntity plaque)
                        || plaque.type() != BuildingType.TAVERN || !candidate.id.equals(plaque.buildingId())
                        || plaque.settlementFor(level) != settlement) continue;
                if (found != null) return null;
                found = candidate;
            }
        }
        return found;
    }

    private static boolean boundServing(ServerLevel level, SettlerEntity host, Building tavern,
                                         TavernServingEntity serving, TavernServingEntity.Phase phase) {
        return serving != null && serving.level() == level && !serving.isRemoved()
            && level.getEntity(serving.getUUID()) == serving && serving.phase() == phase
            && host.getUUID().equals(serving.hostId()) && host.settlement() != null
            && host.settlement().id.equals(serving.settlementId()) && serving.site() != null
            && tavern.id.equals(serving.site().tavernId());
    }

    /** Moves one Tavern-owned Ale into the existing persisted serving, not yet a sale. */
    public static boolean takeAleAtContact(SettlerEntity host, Building tavern, BlockPos tap,
                                           TavernServingEntity serving) {
        if (!hostContact(host, tavern, tap) || !(host.level() instanceof ServerLevel level)
                || !TavernHostService.authorizedHost(host, tavern.id)
                || !boundServing(level, host, tavern, serving, TavernServingEntity.Phase.CARRYING)) return false;
        BarrelBlockEntity barrel = barrel(level, tap);
        int slot = firstPlain(barrel, ModItems.ALE.get());
        if (slot < 0) return false;
        ItemStack source = barrel.getItem(slot);
        if (!serving.acceptTappedAle(source.copyWithCount(1), tap, barrel.getBlockPos())) return false;
        source.shrink(1);
        if (source.isEmpty()) barrel.setItem(slot, ItemStack.EMPTY);
        barrel.setChanged();
        AleTapBlock.pulse(level, tap);
        return true;
    }

    /** Restores untasted Ale and deposits guest payment only at the original physical tap/barrel. */
    public static boolean depositServiceCargoAtContact(SettlerEntity host, Building tavern, BlockPos tap,
                                                       TavernServingEntity serving) {
        if (!hostContact(host, tavern, tap) || !(host.level() instanceof ServerLevel level)
                || !boundServing(level, host, tavern, serving, TavernServingEntity.Phase.RETURNING)
                || !tap.equals(serving.tapSource())) return false;
        BarrelBlockEntity barrel = barrel(level, tap);
        if (!barrel.getBlockPos().equals(serving.tapBarrel())) return false;
        ItemStack ale = serving.displayAle(), coins = serving.displayPayment();
        if ((!ale.isEmpty() && !plain(ale, ModItems.ALE.get()))
                || (!coins.isEmpty() && !plain(coins, ModItems.GOLD_COIN.get()))) return false;
        if (!ale.isEmpty() && !coins.isEmpty()) return false; // Never refund an already-paid drink.
        ItemStack[] before = snapshot(barrel), after = copies(before);
        if (!insert(barrel, after, ale, 0, after.length)
                || !insert(barrel, after, coins, 0, after.length)) return false;
        // The serving methods have exact RETURNING/custody guards. Under the single
        // server call no other actor can alter either side between plan and commit.
        ItemStack removedAle = ale.isEmpty() ? ItemStack.EMPTY : serving.removeUntastedTapAle(ale.getCount());
        ItemStack removedCoins = coins.isEmpty() ? ItemStack.EMPTY : serving.removeTapPayment(coins.getCount());
        if (!ItemStack.matches(removedAle, ale) || !ItemStack.matches(removedCoins, coins)) {
            throw new IllegalStateException("Tap serving debit violated its planned custody contract");
        }
        apply(barrel, before, after);
        return true;
    }

    /** Optional player self-pour: one plain bottle and Coin become one Ale, same price in Creative. */
    public static Result buy(ServerPlayer player, InteractionHand hand, BlockPos tap, Direction facing) {
        if (player == null || hand == null || player.isSpectator() || !player.isAlive()) return Result.NOT_ALLOWED;
        ServerLevel level = player.serverLevel();
        if (tap == null || facing == null || !level.hasChunkAt(tap)
                || !(level.getBlockState(tap).getBlock() instanceof AleTapBlock)
                || level.getBlockState(tap).getValue(AleTapBlock.FACING) != facing) return Result.INVALID_TAP;
        BarrelBlockEntity barrel = barrel(level, tap);
        if (barrel == null) return Result.NO_BARREL;
        Vec3 contact = interactionPoint(level, tap);
        if (contact == null) return Result.INVALID_TAP;
        if (player.getEyePosition().distanceToSqr(contact) > PLAYER_REACH_SQR) return Result.OUT_OF_REACH;
        if (!visible(level, player, tap, contact)) return Result.BLOCKED;
        if (owningTavern(level, tap, barrel.getBlockPos()) == null) return Result.NOT_TAVERN;
        if (LAST_PURCHASE.getOrDefault(player, Long.MIN_VALUE) == level.getGameTime()) return Result.RETRY;
        int glassSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
        var inventory = player.getInventory();
        if (!plain(inventory.getItem(glassSlot), Items.GLASS_BOTTLE)) return Result.NEEDS_BOTTLE;
        int coinSlot = firstPlain(inventory, ModItems.GOLD_COIN.get(), 0, 36);
        if (coinSlot < 0) return Result.NEEDS_COIN;
        int aleSlot = firstPlain(barrel, ModItems.ALE.get());
        if (aleSlot < 0) return Result.EMPTY;
        ItemStack[] walletBefore = snapshot(inventory), walletAfter = copies(walletBefore);
        ItemStack[] barrelBefore = snapshot(barrel), barrelAfter = copies(barrelBefore);
        shrink(walletAfter, glassSlot, 1); shrink(walletAfter, coinSlot, PRICE); shrink(barrelAfter, aleSlot, 1);
        ItemStack ale = new ItemStack(ModItems.ALE.get());
        if (walletAfter[glassSlot].isEmpty()) walletAfter[glassSlot] = ale;
        else if (!insert(inventory, walletAfter, ale, 0, 36)) return Result.NO_ROOM;
        if (!insert(barrel, barrelAfter, new ItemStack(ModItems.GOLD_COIN.get(), PRICE), 0, barrelAfter.length))
            return Result.BARREL_FULL;
        apply(inventory, walletBefore, walletAfter); apply(barrel, barrelBefore, barrelAfter);
        LAST_PURCHASE.put(player, level.getGameTime());
        player.containerMenu.broadcastChanges();
        AleTapBlock.pulse(level, tap);
        return Result.SUCCESS;
    }

    private static boolean plain(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty();
    }
    private static int firstPlain(Container container, Item item) {
        return firstPlain(container, item, 0, container.getContainerSize());
    }
    private static int firstPlain(Container container, Item item, int from, int to) {
        for (int slot = from; slot < to; slot++) if (plain(container.getItem(slot), item)) return slot;
        return -1;
    }
    private static ItemStack[] snapshot(Container container) {
        ItemStack[] stacks = new ItemStack[container.getContainerSize()];
        for (int slot = 0; slot < stacks.length; slot++) stacks[slot] = container.getItem(slot).copy();
        return stacks;
    }
    private static ItemStack[] copies(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int slot = 0; slot < source.length; slot++) result[slot] = source[slot].copy();
        return result;
    }
    private static void shrink(ItemStack[] stacks, int slot, int count) {
        stacks[slot].shrink(count);
        if (stacks[slot].isEmpty()) stacks[slot] = ItemStack.EMPTY;
    }
    private static boolean insert(Container container, ItemStack[] stacks, ItemStack offered, int from, int to) {
        if (offered.isEmpty()) return true;
        int remaining = offered.getCount();
        for (int pass = 0; pass < 2; pass++) for (int slot = from; slot < to && remaining > 0; slot++) {
            ItemStack current = stacks[slot];
            if (!container.canPlaceItem(slot, offered) || (pass == 0 ? current.isEmpty() : !current.isEmpty())) continue;
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, offered)) continue;
            int limit = Math.min(container.getMaxStackSize(), offered.getMaxStackSize());
            int accepted = Math.min(remaining, Math.max(0, limit - current.getCount()));
            if (accepted <= 0) continue;
            stacks[slot] = current.isEmpty() ? offered.copyWithCount(accepted) : current.copyWithCount(current.getCount() + accepted);
            remaining -= accepted;
        }
        return remaining == 0;
    }
    private static void apply(Container container, ItemStack[] before, ItemStack[] after) {
        for (int slot = 0; slot < after.length; slot++)
            if (!ItemStack.matches(before[slot], after[slot])) container.setItem(slot, after[slot].copy());
        container.setChanged();
    }
}