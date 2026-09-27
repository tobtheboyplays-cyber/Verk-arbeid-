package com.hearthstead.event.worldevent;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Passing caravan (owner-approved): a trade caravan stops on the road near
 * town. Talk to the caravan master to barter in bulk, or offer an escort
 * past the woods: stay with the caravan to its far waypoint (sometimes two
 * bandits jump it on the way) for Coins and goods. Or wave them on. Either
 * way they roll out of sight and every actor is cleaned up.
 */
final class CaravanEvent implements WorldEventHandler {
    static final String ROLE_MASTER = "caravan_master";
    static final String ROLE_CART = "caravan_cart";
    static final String ROLE_BANDIT = "caravan_bandit";
    static final int ESCORT_COINS = 8;
    static final int AMBUSH_PERCENT = 50;
    static final double ESCORT_RANGE = 32.0D;
    /** The escort must be this close to be paid on arrival. */
    static final double PAYOUT_RANGE = 24.0D;
    static final int ABANDON_TICKS = 600;

    /** Bulk goods the caravan carries (item, count) and their value in copper per item. */
    record Goods(Item item, int count, int copper) {}

    static final List<Goods> GOODS = List.of(
        new Goods(Items.IRON_INGOT, 16, 50), new Goods(Items.LEATHER, 16, 30), new Goods(Items.WHITE_WOOL, 24, 15),
        new Goods(Items.GLASS, 16, 20), new Goods(Items.BRICKS, 32, 10), new Goods(Items.COOKED_SALMON, 12, 25),
        new Goods(Items.SUGAR, 16, 10), new Goods(Items.GOLD_INGOT, 4, 120));

    @Override
    public WorldEventType type() {
        return WorldEventType.CARAVAN;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return settlement.population() >= 3;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos stop = WorldEventCreatures.ringSpot(level, settlement, settlement.center,
            settlement.radius + 6, settlement.radius + 14, 1.0F, 2.0F, level.random);
        if (stop == null) stop = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 10, 16, 1.0F, 2.0F, level.random);
        if (stop == null) return false;
        // The far waypoint: along the road (tangent to the town), not through it.
        double dx = stop.getX() - settlement.center.getX(), dz = stop.getZ() - settlement.center.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        BlockPos far = BlockPos.containing(stop.getX() - dz / len * 48.0D, stop.getY(), stop.getZ() + dx / len * 48.0D);
        WanderingTrader master = EntityType.WANDERING_TRADER.create(level);
        if (master == null) return false;
        master.moveTo(stop.getX() + .5, stop.getY(), stop.getZ() + .5, 0F, 0F);
        master.finalizeSpawn(level, level.getCurrentDifficultyAt(stop), MobSpawnType.EVENT, null);
        // Vanilla's countdown would poof him mid-visit while the event is paused (players
        // 80+ blocks away but still simulating); the event and Departure end him instead.
        // Not 0: a leashed trader llama copies the trader's delay minus one and would
        // vanish at once (bug hunt, 26 Sep).
        master.setDespawnDelay(WorldEventActors.NEVER_DESPAWN);
        master.setPersistenceRequired();
        master.getOffers().clear();
        if (master.getAttribute(Attributes.FOLLOW_RANGE) != null) master.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64.0D);
        master.restrictTo(stop, 6);
        WorldEventDirector.tag(master, settlement, active, ROLE_MASTER);
        master.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", "Caravan master");
        WorldEventActors.markWaiting(master, Component.translatableWithFallback("hearthstead.event.caravan.who",
            "caravan master"), true);
        if (!level.addFreshEntity(master)) return false;
        int[][] offsets = {{2, 2}, {-2, 2}, {2, -2}, {-2, -2}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};
        int placed = 0;
        for (int[] offset : offsets) {
            if (placed >= 2) break;
            BlockPos at = WorldEventCreatures.nearFeet(level, stop.offset(offset[0], 0, offset[1]), 0.9F, 1.9F);
            TraderLlama cart = at == null ? null : EntityType.TRADER_LLAMA.create(level);
            if (cart == null) continue;
            cart.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0F, 0F);
            cart.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
            cart.setPersistenceRequired();
            WorldEventDirector.tag(cart, settlement, active, ROLE_CART);
            if (level.addFreshEntity(cart)) {
                cart.setLeashedTo(master, true);
                placed++;
            }
        }
        CompoundTag stock = new CompoundTag();
        for (Goods goods : GOODS) stock.putInt(BuiltInRegistries.ITEM.getKey(goods.item()).toString(), goods.count());
        active.state.put("Stock", stock);
        active.state.putLong("Stop", stop.asLong());
        active.state.putLong("Far", far.asLong());
        // Rolling out: along a real road if one leaves from here, else on along the waypoint line.
        BlockPos road = WorldEventDeparture.roadExit(level, stop, settlement.center);
        active.state.putLong("RoadExit", (road != null ? road : beyond(stop, far, 24.0D)).asLong());
        active.state.putBoolean("Seen", true);
        WorldEventConversations.bind(master, settlement, WorldEventConversations.CARAVAN, "caravan",
            "conversation.hearthstead.title.caravan", Map.of("coins", ESCORT_COINS));
        level.playSound(null, stop, com.hearthstead.registry.ModSounds.EVENT_CARAVAN_ARRIVE.get(), SoundSource.NEUTRAL, 1.5F, 1.0F);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.caravan.arrive",
                "A trade caravan has stopped on the road outside town."),
            Component.translatableWithFallback("hearthstead.event.caravan.cta",
                "Talk to the caravan master: trade in bulk, or escort them past the woods for pay."), stop);
        return true;
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (actor instanceof RaiderEntity bandit && ROLE_BANDIT.equals(WorldEventDirector.tagRole(actor))) hostile(bandit);
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity entity = WorldEventActors.actor(level, active, ROLE_MASTER);
        if (!(entity instanceof WanderingTrader master)) {
            var presence = WorldEventActors.presence(level, active, ROLE_MASTER);
            // Unloaded (e.g. the Banner chunk loaded first after a restart) is not dead: wait.
            if (presence == WorldEventActors.Presence.ABSENT) return;
            if (presence == WorldEventActors.Presence.DEAD && active.state.getBoolean("Escort")) {
                WorldEventDirector.finish(level, settlement, "caravan_lost", Component.translatableWithFallback(
                    "hearthstead.event.caravan.lost", "The caravan master has fallen. The carts scatter into the woods.")
                    .withStyle(ChatFormatting.RED));
            } else {
                WorldEventDirector.finish(level, settlement, "gone", null);
            }
            return;
        }
        String mode = active.state.getString("Mode");
        if (mode.isEmpty()) return; // waiting at the roadside for an answer
        BlockPos far = BlockPos.of(active.state.getLong("Far"));
        master.restrictTo(far, 80);
        if (master.getNavigation().isDone() || level.getGameTime() % 60 == 0) {
            // Walk in short legs on the real ground height, so hills or an unloaded
            // far end never leave one impossible path request stalling the caravan.
            double dx = far.getX() - master.getX(), dz = far.getZ() - master.getZ();
            double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            double leg = Math.min(len, 14.0D);
            int lx = (int) Math.round(master.getX() + dx / len * leg), lz = (int) Math.round(master.getZ() + dz / len * leg);
            int ly = level.hasChunkAt(new BlockPos(lx, far.getY(), lz))
                ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz)
                : (int) master.getY();
            master.getNavigation().moveTo(lx + .5, ly, lz + .5, "escort".equals(mode) ? 0.55D : 0.7D);
        }
        double left = Math.sqrt(master.distanceToSqr(far.getX() + .5, master.getY(), far.getZ() + .5));
        if (!active.state.contains("BestLeft") || left < active.state.getDouble("BestLeft") - 1.0D) {
            active.state.putDouble("BestLeft", left);
            active.state.putInt("BestAt", active.eligibleTicks);
        }
        BlockPos route = BlockPos.of(active.state.getLong("Stop"));
        double routeLength = Math.max(1.0D, Math.sqrt(route.distSqr(far)));
        // Arrived at the far waypoint, or at the end of the usable road: most of the
        // way there and no progress for half a minute (a river or cliff at the end).
        boolean arrived = left <= 4.0D || left <= routeLength * 0.4D
            && active.eligibleTicks - active.state.getInt("BestAt") > 600;
        if (!"escort".equals(mode)) {
            if (arrived || active.eligibleTicks - active.state.getInt("ModeAt") > 1200) {
                WorldEventDirector.finish(level, settlement, "passed", null);
            }
            return;
        }
        // Escort: stay with the caravan; maybe an ambush halfway.
        ServerPlayer escort = nearestPlayer(level, master, ESCORT_RANGE);
        if (escort == null) {
            if (!active.state.contains("AloneSince")) active.state.putInt("AloneSince", active.eligibleTicks);
            if (active.eligibleTicks - active.state.getInt("AloneSince") > ABANDON_TICKS) {
                WorldEventDirector.finish(level, settlement, "abandoned", Component.translatableWithFallback(
                    "hearthstead.event.caravan.abandoned", "The escort fell behind. The caravan rolls on alone, and pays nothing."));
                return;
            }
        } else {
            active.state.remove("AloneSince");
        }
        BlockPos stop = BlockPos.of(active.state.getLong("Stop"));
        double total = Math.sqrt(stop.distSqr(far));
        double done = Math.sqrt(master.blockPosition().distSqr(stop));
        if (!active.state.getBoolean("Ambushed") && ambushed(active.id) && done >= total * 0.4D) {
            active.state.putBoolean("Ambushed", true);
            spawnBandits(level, settlement, active, master, far);
        }
        if (arrived) {
            boolean banditsLeft = WorldEventActors.actor(level, active, ROLE_BANDIT) != null;
            if (banditsLeft) return; // finish the fight first
            // "Stay close": the master pays only an escort who is actually at his side.
            ServerPlayer atHand = nearestPlayer(level, master, PAYOUT_RANGE);
            if (atHand == null) return; // waits; the abandon clock still runs
            reward(level, active, atHand, master);
            active.state.putLong("RoadExit", beyond(stop, far, 40.0D).asLong());
            WorldEventDirector.finish(level, settlement, "escorted", Component.translatableWithFallback(
                "hearthstead.event.caravan.escorted",
                "The caravan reaches the far road safely. The master pays %s Coins and a crate of goods.", ESCORT_COINS)
                .withStyle(ChatFormatting.GREEN));
        }
    }

    /** A point {@code extra} blocks past {@code to} on the line from {@code from}. */
    static BlockPos beyond(BlockPos from, BlockPos to, double extra) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        return BlockPos.containing(to.getX() + dx / len * extra, to.getY(), to.getZ() + dz / len * extra);
    }

    static boolean ambushed(UUID eventId) {
        return Math.floorMod(eventId.getLeastSignificantBits(), 100L) < AMBUSH_PERCENT;
    }

    private static ServerPlayer nearestPlayer(ServerLevel level, Entity near, double range) {
        ServerPlayer best = null;
        double bestDistance = range * range;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double d = player.distanceToSqr(near);
            if (d <= bestDistance) { best = player; bestDistance = d; }
        }
        return best;
    }

    private static void reward(ServerLevel level, WorldEventSavedData.Active active, ServerPlayer escort, Entity master) {
        Entity at = escort != null ? escort : master;
        List<ItemStack> pay = new ArrayList<>();
        pay.add(new ItemStack(ModItems.GOLD_COIN.get(), ESCORT_COINS));
        pay.add(new ItemStack(Items.IRON_INGOT, 4));
        pay.add(new ItemStack(Items.LEATHER, 4));
        for (ItemStack stack : pay) {
            ItemEntity drop = new ItemEntity(level, at.getX(), at.getY() + 0.5D, at.getZ(), stack);
            drop.setPickUpDelay(10);
            level.addFreshEntity(drop);
        }
        active.state.putBoolean("Rewarded", true);
    }

    static void spawnBandits(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                     WanderingTrader master, BlockPos far) {
        double dx = far.getX() - master.getX(), dz = far.getZ() - master.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        BlockPos ahead = BlockPos.containing(master.getX() + dx / len * 10.0D, master.getY(), master.getZ() + dz / len * 10.0D);
        int spawned = 0;
        for (int i = 0; i < 2; i++) {
            BlockPos feet = WorldEventCreatures.nearFeet(level, ahead.offset(i * 3 - 1, 0, i * 2 - 1), 0.6F, 1.9F);
            RaiderEntity bandit = feet == null ? null : ModEntities.RAIDER.get().create(level);
            if (bandit == null) continue;
            bandit.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0F, 0F);
            bandit.setVariant(RaiderEntity.Variant.SKIRMISHER);
            bandit.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
            bandit.setCustomName(Component.translatableWithFallback("hearthstead.event.caravan.bandit", "Road bandit"));
            bandit.setPersistenceRequired();
            bandit.setCanPickUpLoot(false);
            WorldEventDirector.tag(bandit, settlement, active, ROLE_BANDIT);
            hostile(bandit);
            if (level.addFreshEntity(bandit)) spawned++;
        }
        if (spawned > 0) {
            level.playSound(null, ahead, SoundEvents.PILLAGER_CELEBRATE, SoundSource.HOSTILE, 1.5F, 0.9F);
            WorldEventDirector.notice(level, settlement, Component.translatableWithFallback("hearthstead.event.caravan.ambush",
                "Ambush! Bandits leap from the trees at the caravan!").withStyle(ChatFormatting.RED));
        }
    }

    /** Road bandits: the raider melee/skirmish moves, aimed at the caravan and its escort. */
    static void hostile(RaiderEntity bandit) {
        for (var goal : List.copyOf(bandit.goalSelector.getAvailableGoals())) bandit.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(bandit.targetSelector.getAvailableGoals())) bandit.targetSelector.removeGoal(goal.getGoal());
        bandit.goalSelector.addGoal(0, new FloatGoal(bandit));
        bandit.goalSelector.addGoal(3, new com.hearthstead.entity.ai.RaiderMeleeGoal(bandit, 1.1D));
        bandit.goalSelector.addGoal(3, new com.hearthstead.entity.ai.RaiderSkirmishGoal(bandit));
        bandit.targetSelector.addGoal(1, new HurtByTargetGoal(bandit));
        bandit.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(bandit, WanderingTrader.class, true,
            target -> ROLE_MASTER.equals(WorldEventDirector.tagRole(target))));
        bandit.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(bandit, Player.class, true));
        bandit.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(bandit, SettlerEntity.class, true,
            target -> bandit.isMyWar(target)));
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, DamageSource source) {
        if (ROLE_BANDIT.equals(WorldEventDirector.tagRole(actor))) {
            actor.spawnAtLocation(new ItemStack(ModItems.GOLD_COIN.get(), 1));
        }
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventDirector.finish(level, settlement, active.state.getString("Mode").isEmpty() ? "moved_on"
            : active.state.getString("Mode"), Component.translatableWithFallback("hearthstead.event.caravan.gone",
            "The caravan's bells fade down the road."));
    }

    // ------------------------------------------------------------ answers --

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return active.state.getString("Mode").isEmpty() && ROLE_MASTER.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("caravan", settlement.id), "caravan",
            Component.translatableWithFallback("hearthstead.event.caravan.title", "A passing caravan"),
            Component.literal(WorldEventActors.plainName(actor)),
            List.of(Component.translatableWithFallback("conversation.hearthstead.caravan.line1",
                    "\"Iron, leather, glass and wool, friend, by the cartload.\""),
                Component.translatableWithFallback("hearthstead.event.caravan.line2",
                    "\"The woods ahead are thick with bandits. Ride with us and I'll pay %s Coins.\"", ESCORT_COINS)),
            List.of(), "pass", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("escort", Component.translatableWithFallback(
                "conversation.hearthstead.caravan.escort", "Escort the caravan past the woods"),
                WorldEventVisitors.Cost.FREE, WorldEventVisitors.OptionStyle.PRIMARY).withRelation(10),
            WorldEventVisitors.Option.of("pass", Component.translatableWithFallback(
                "conversation.hearthstead.caravan.pass", "Safe travels"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.SECONDARY));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        WorldEventConversations.unbind(actor, null);
        WorldEventActors.markWaiting(actor, Component.translatableWithFallback("hearthstead.event.caravan.who",
            "caravan master"), false);
        active.state.putInt("ModeAt", active.eligibleTicks);
        if ("escort".equals(optionId)) {
            active.state.putString("Mode", "escort");
            active.state.putBoolean("Escort", true);
            return Component.translatableWithFallback("hearthstead.event.caravan.escort_start",
                "%s rides with the caravan. Stay close until the far road!", player.getDisplayName());
        }
        active.state.putString("Mode", "pass");
        WorldEventDirector.finish(level, settlement, "passed", null); // the carts roll out down the road
        return Component.translatableWithFallback("hearthstead.event.caravan.pass_start",
            "The caravan master waves and the carts roll on.");
    }

    // -------------------------------------------------------------- barter --

    /** Copper value per item for the caravan's goods; -1 for anything else. */
    static int unitValue(ItemStack stack) {
        for (Goods goods : GOODS) if (stack.is(goods.item())) return goods.copper();
        return -1;
    }
}
