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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * Brute toll (owner-approved 26 Sep): three brutes walk up to the Banner and
 * demand food. While they parley they are passive and cannot be targeted,
 * so Guards hold (the brutes are invulnerable until someone chooses a
 * fight). Answers: pay the food toll (scaled with the village), pay 10
 * Coins, try to talk them down, refuse (a warning, then they attack) or
 * attack first. Nobody answers within about two in-game hours: they help
 * themselves to the toll from the Warehouse and leave, or attack if the
 * stores hold nothing. Hostile brutes use the real Raider brute melee (club
 * and heavy slam) and the raid hunt goal.
 */
@EventBusSubscriber(modid = com.hearthstead.Hearthstead.MODID)
final class BruteTollEvent implements WorldEventHandler {
    static final String ROLE_CHIEF = "brute_chief";
    static final String ROLE_BRUTE = "brute";
    static final int BAND = 3;
    static final int COIN_TOLL = 10;
    static final int FAVOUR_PERCENT = 30;
    static final int REFUSE_WARNING_TICKS = 60;

    static final int MIN_FOOD_TOLL = 4;
    static final int MIN_COIN_TOLL = 2;

    /** Village-size demand (8 + 2 per settler, max 32)... */
    static int foodToll(Settlement settlement) {
        return Math.max(8, Math.min(32, 8 + 2 * settlement.population()));
    }

    /**
     * ...but never more than about half of what the stores actually hold, so
     * paying is a real option (survival QA: a toll nobody can pay is just a
     * fight). {@code stored} is the food in the Warehouse stores.
     */
    /** Same fact the talk UI uses ("town.defended"), so both answer paths make only true claims. */
    static boolean defended(Settlement settlement) {
        return com.hearthstead.conversation.TownFactsLive.of(settlement).defended();
    }

    static int coinTollOf(WorldEventSavedData.Active active) {
        return active.state.contains("CoinToll") ? active.state.getInt("CoinToll") : COIN_TOLL;
    }

    static int foodToll(Settlement settlement, int stored) {
        return Math.max(MIN_FOOD_TOLL, Math.min(foodToll(settlement), stored / 2));
    }

    /** Coin alternative: up to {@link #COIN_TOLL}, capped at half the stored Coins. */
    static int coinToll(int storedCoins) {
        return Math.max(MIN_COIN_TOLL, Math.min(COIN_TOLL, storedCoins / 2));
    }

    @Override
    public WorldEventType type() {
        return WorldEventType.BRUTE_TOLL;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return settlement.population() >= 4 && lateEnough(level, settlement);
    }

    /** Raid curve (balance lane, owner request): brutes come late. */
    static final int MIN_RAID_NUMBER = 3;
    static final int MIN_DAY = 6;

    /**
     * Not before the settlement's 3rd raid AND not before day 6, whichever is
     * later. Raid number = 1 before the first raid is done, then 2 + the last
     * resolved recurring raid serial. An unknown founding day counts as old.
     */
    static boolean lateEnough(ServerLevel level, Settlement settlement) {
        var raid = settlement.raidLifecycle;
        // One source of truth for "which raid is next" (balance lane's RaidEscalation).
        long raidNumber = com.hearthstead.settlement.raid.RaidEscalation.raidNumber(settlement);
        if (raidNumber < MIN_RAID_NUMBER) return false;
        long founded = raid.foundedNight();
        return founded < 0L || WorldEventSchedule.dayOf(level.getDayTime()) - founded >= MIN_DAY;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos edge = WorldEventCreatures.ringSpot(level, settlement, settlement.center,
            Math.max(16, settlement.radius - 4), Math.max(20, settlement.radius + 6), 1.6F, 2.4F, level.random);
        if (edge == null) return false;
        BlockPos stop = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 5, 8, 1.6F, 2.4F, level.random);
        if (stop == null) stop = settlement.center;
        int toll = foodToll(settlement, WorldEventVisitors.storeFood(level, settlement));
        int coins = coinToll(WorldEventVisitors.storeCoins(level, settlement));
        List<RaiderEntity> band = new ArrayList<>();
        // Story lane (T3): Gorm brings a fourth brute after his band was killed last time.
        int bandSize = StoryHooks.gormBand(level, settlement, BAND);
        for (int i = 0; i < bandSize; i++) {
            BlockPos feet = WorldEventCreatures.nearFeet(level, edge.offset(i * 2 - 2, 0, i % 2), 1.4F, 2.4F);
            if (feet == null) feet = edge;
            RaiderEntity brute = ModEntities.RAIDER.get().create(level);
            if (brute == null) return false;
            brute.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0F, 0F);
            brute.setVariant(RaiderEntity.Variant.BRUTE);
            brute.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
            brute.setPersistenceRequired();
            brute.setCanPickUpLoot(false);
            WorldEventDirector.tag(brute, settlement, active, i == 0 ? ROLE_CHIEF : ROLE_BRUTE);
            String name = i == 0 ? StoryCharacter.GORM : "Brute";
            brute.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", name);
            passive(brute, stop.offset(i * 2 - 2, 0, 0));
            if (i == 0) {
                WorldEventActors.markWaiting(brute, Component.translatableWithFallback(
                    "hearthstead.event.brute_toll.who", "demands food"), true);
            }
            if (!level.addFreshEntity(brute)) return false;
            band.add(brute);
        }
        active.state.putInt("Toll", toll);
        active.state.putInt("CoinToll", coins);
        active.state.putBoolean("Seen", true);
        active.state.putLong("Stop", stop.asLong());
        WorldEventConversations.bind(band.get(0), settlement, WorldEventConversations.BRUTE_TOLL, "brute",
            "conversation.hearthstead.title.brute", Map.of("toll", toll, "coins", coins));
        level.playSound(null, edge, com.hearthstead.registry.ModSounds.EVENT_BRUTE_GRUNT.get(), SoundSource.HOSTILE, 1.5F, 1.0F);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback(bandSize > BAND ? "hearthstead.story.gorm.arrive_more"
                : "hearthstead.story.gorm.arrive", "%s and his brutes approach the Banner. They look hungry.",
                StoryCharacter.GORM),
            Component.translatableWithFallback("hearthstead.event.brute_toll.cta",
                "Talk to their chief: pay %s food or %s Coins, or fight. Guards hold until you choose.",
                toll, coins), stop);
        StoryHooks.gormArrives(level, settlement);
        return true;
    }

    /** Parley state: invulnerable (so Guards and turrets hold), no attack goals, walk to the Banner. */
    static void passive(RaiderEntity brute, BlockPos stop) {
        brute.getPersistentData().getCompound(WorldEventDirector.TAG).putLong("Stop", stop.asLong());
        brute.setInvulnerable(true);
        for (var goal : List.copyOf(brute.goalSelector.getAvailableGoals())) brute.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(brute.targetSelector.getAvailableGoals())) brute.targetSelector.removeGoal(goal.getGoal());
        brute.setTarget(null);
        brute.goalSelector.addGoal(0, new FloatGoal(brute));
        brute.goalSelector.addGoal(2, new WalkToGoal(brute));
        brute.goalSelector.addGoal(8, new LookAtPlayerGoal(brute, Player.class, 10.0F));
        brute.goalSelector.addGoal(9, new RandomLookAroundGoal(brute));
    }

    /** Fight state: the real brute melee (club, heavy slam) and the raid hunt; vulnerable again. */
    static void hostile(RaiderEntity brute) {
        CompoundTagView.of(brute).putBoolean("Hostile", true);
        brute.setInvulnerable(false);
        for (var goal : List.copyOf(brute.goalSelector.getAvailableGoals())) brute.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(brute.targetSelector.getAvailableGoals())) brute.targetSelector.removeGoal(goal.getGoal());
        brute.goalSelector.addGoal(0, new FloatGoal(brute));
        brute.goalSelector.addGoal(2, new com.hearthstead.entity.ai.RaiderHuntGoal(brute));
        brute.goalSelector.addGoal(3, new com.hearthstead.entity.ai.RaiderMeleeGoal(brute, 1.0D));
        brute.goalSelector.addGoal(8, new LookAtPlayerGoal(brute, Player.class, 8.0F));
        brute.goalSelector.addGoal(9, new RandomLookAroundGoal(brute));
        brute.targetSelector.addGoal(1, new HurtByTargetGoal(brute));
        brute.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(brute, SettlerEntity.class, true,
            target -> brute.isMyWar(target)));
        brute.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(brute, Player.class, true));
    }

    /** Tiny helper so the entity tag reads clearly. */
    private record CompoundTagView(net.minecraft.nbt.CompoundTag tag) {
        static net.minecraft.nbt.CompoundTag of(Entity entity) {
            return entity.getPersistentData().getCompound(WorldEventDirector.TAG);
        }
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (!(actor instanceof RaiderEntity brute)) return;
        if (active.state.getBoolean("Hostile")) {
            hostile(brute);
        } else {
            passive(brute, BlockPos.of(CompoundTagView.of(brute).getLong("Stop")));
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<RaiderEntity> band = band(level, active);
        if (band.isEmpty()) {
            var presence = WorldEventActors.presence(level, active);
            if (presence == WorldEventActors.Presence.ABSENT) return; // unloaded, not beaten: wait
            if (active.state.getBoolean("Seen")) {
                String outcome = active.state.getBoolean("Hostile") && presence == WorldEventActors.Presence.DEAD
                    ? "defeated" : "left";
                WorldEventDirector.finish(level, settlement, outcome, "defeated".equals(outcome)
                    ? Component.translatableWithFallback("hearthstead.event.brute_toll.defeated",
                        "The last brute falls. Nobody takes a toll from this village.")
                    : null);
            }
            return;
        }
        active.state.putBoolean("Seen", true);
        if (active.state.getBoolean("Leaving")) {
            leaveTick(level, settlement, active, band);
            return;
        }
        if (active.state.contains("HostileAt") && !active.state.getBoolean("Hostile")
            && active.eligibleTicks >= active.state.getInt("HostileAt")) {
            turnHostile(level, settlement, active, null);
        }
        if (active.state.getBoolean("Hostile")) return;
        // Arrival clock: the answer window starts once the chief is at the Banner.
        RaiderEntity chief = chief(band);
        if (!active.state.contains("ArrivedAt") && chief != null
            && chief.blockPosition().distSqr(BlockPos.of(active.state.getLong("Stop"))) <= 16) {
            active.state.putInt("ArrivedAt", active.eligibleTicks);
        }
        int arrived = active.state.contains("ArrivedAt") ? active.state.getInt("ArrivedAt") : 400;
        if (!WorldEventVisitors.answered(active)
            && active.eligibleTicks - arrived >= WorldEventType.BRUTE_TOLL_ANSWER_TICKS) {
            defaultOutcome(level, settlement, active);
        }
    }

    private static List<RaiderEntity> band(ServerLevel level, WorldEventSavedData.Active active) {
        List<RaiderEntity> out = new ArrayList<>();
        for (Entity entity : WorldEventActors.actors(level, active)) {
            if (entity instanceof RaiderEntity brute) out.add(brute);
        }
        return out;
    }

    private static RaiderEntity chief(List<RaiderEntity> band) {
        for (RaiderEntity brute : band) if (ROLE_CHIEF.equals(WorldEventDirector.tagRole(brute))) return brute;
        return band.isEmpty() ? null : band.get(0);
    }

    /** No answer: take the toll from the stores and go, or fight if there is nothing to take. */
    static void defaultOutcome(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventVisitors.markAnswered(active, "timeout");
        int took = WorldEventVisitors.takeFoodFromStores(level, settlement, active.state.getInt("Toll"));
        for (RaiderEntity brute : band(level, active)) WorldEventConversations.unbind(brute, null);
        if (took > 0) {
            active.state.putString("Outcome", "took_food");
            leave(level, active);
            WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
                "hearthstead.event.brute_toll.took", "The brutes lose patience, help themselves to %s food from your stores and stomp off.",
                took).withStyle(ChatFormatting.RED));
        } else {
            turnHostile(level, settlement, active, Component.translatableWithFallback(
                "hearthstead.event.brute_toll.no_food", "Nobody answered and the stores are bare. The brutes attack!"));
        }
    }

    static void turnHostile(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, Component notice) {
        if (active.state.getBoolean("Hostile")) return;
        active.state.putBoolean("Hostile", true);
        active.state.putInt("HostileSince", active.eligibleTicks);
        WorldEventVisitors.markAnswered(active, active.state.contains("Answered") ? active.state.getString("Answered") : "fight");
        for (RaiderEntity brute : band(level, active)) {
            WorldEventConversations.unbind(brute, null);
            WorldEventActors.markWaiting(brute, Component.translatableWithFallback(
                "hearthstead.event.brute_toll.who_angry", "attacking"), false);
            hostile(brute);
        }
        if (notice != null) WorldEventDirector.notice(level, settlement, notice.copy().withStyle(ChatFormatting.RED));
        level.playSound(null, settlement.center, com.hearthstead.registry.ModSounds.RAIDER_BRUTE_ROAR.get(), SoundSource.HOSTILE, 2.0F, 0.9F);
        WorldEventSavedData.get(level).markChanged();
    }

    private static void leave(ServerLevel level, WorldEventSavedData.Active active) {
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        for (RaiderEntity brute : band(level, active)) {
            brute.setInvulnerable(true);
            WorldEventActors.markWaiting(brute, Component.translatableWithFallback(
                "hearthstead.event.brute_toll.who_leaving", "leaving"), false);
        }
        // They walk off together and despawn only out of sight (never a puff).
        WorldEventDirector.Owner owner = WorldEventDirector.ownerOfEvent(level, active.id);
        if (owner != null) {
            WorldEventDirector.finish(level, owner.settlement(),
                active.state.contains("Outcome") ? active.state.getString("Outcome") : "left", null);
        }
    }

    private static void leaveTick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                  List<RaiderEntity> band) {
        if (active.eligibleTicks - active.state.getInt("LeaveAt") > 600 || band(level, active).isEmpty()) {
            WorldEventDirector.finish(level, settlement, active.state.contains("Outcome")
                ? active.state.getString("Outcome") : "left", null);
        }
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventDirector.finish(level, settlement, active.state.getBoolean("Hostile") ? "retreated" : "left",
            active.state.getBoolean("Hostile") ? Component.translatableWithFallback("hearthstead.event.brute_toll.retreat",
                "The surviving brutes retreat into the woods, nursing their bruises.") : null);
    }

    /** Story lane (T3): Gorm remembers how the toll ended (a neutral record; cues come with the choice). */
    @Override
    public void cleanup(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        if (!java.util.Set.of("test_teardown", "stopped", "disabled").contains(outcome)) {
            StoryHooks.remember(level, settlement, StoryHooks.GORM, StoryCharacter.GORM, outcome,
                VisitorMemory.NEUTRAL);
        }
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, DamageSource source) {
        if (ROLE_CHIEF.equals(WorldEventDirector.tagRole(actor))) {
            actor.spawnAtLocation(new ItemStack(ModItems.GOLD_COIN.get(), 4));
        }
    }

    // ------------------------------------------------------------ answers --

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Hostile") && !active.state.getBoolean("Leaving")
            && !active.state.contains("HostileAt") && ROLE_CHIEF.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        int toll = active.state.getInt("Toll");
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("brute", settlement.id), "brute",
            Component.translatableWithFallback("hearthstead.event.brute_toll.title", "Brutes at the Banner"),
            Component.literal(WorldEventActors.plainName(actor)),
            List.of(Component.translatableWithFallback("conversation.hearthstead.brute_toll.demand",
                    "\"Long road. Empty bellies. You have food.\""),
                Component.translatableWithFallback("hearthstead.event.brute_toll.demand2",
                    "\"%s food, and we go. Or we take it from your bones.\"", toll)),
            List.of(), "timeout", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("pay_food", Component.translatableWithFallback(
                "conversation.hearthstead.brute_toll.pay_food", "Give them the food"),
                WorldEventVisitors.Cost.food(active.state.getInt("Toll")), WorldEventVisitors.OptionStyle.PRIMARY)
                .withRelation(5),
            WorldEventVisitors.Option.of("pay_coins", Component.translatableWithFallback(
                "conversation.hearthstead.brute_toll.pay_coins", "Pay them in Coins instead"),
                WorldEventVisitors.Cost.coins(coinTollOf(active)), WorldEventVisitors.OptionStyle.SECONDARY).withRelation(3),
            defended(settlement)
                ? WorldEventVisitors.Option.of("talk_walls", Component.translatableWithFallback(
                    "conversation.hearthstead.brute_toll.talk_walls", "\"Look at our walls and our guards. Move along.\""),
                    WorldEventVisitors.Cost.FREE, WorldEventVisitors.OptionStyle.SECONDARY)
                    .persuade(new WorldEventVisitors.Persuasion(35, "talked_down", "insulted"))
                : WorldEventVisitors.Option.of("talk_honest", Component.translatableWithFallback(
                    "conversation.hearthstead.brute_toll.talk_honest",
                    "\"We have little. The road ahead has richer towns, and every village will hear if you harm us.\""),
                    WorldEventVisitors.Cost.FREE, WorldEventVisitors.OptionStyle.SECONDARY)
                    .persuade(new WorldEventVisitors.Persuasion(30, "talked_down", "insulted")),
            WorldEventVisitors.Option.of("refuse", Component.translatableWithFallback(
                "conversation.hearthstead.brute_toll.refuse", "Refuse"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-10),
            WorldEventVisitors.Option.of("attack", Component.translatableWithFallback(
                "conversation.hearthstead.brute_toll.attack", "Attack first"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-20));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        for (RaiderEntity brute : band(level, active)) WorldEventConversations.unbind(brute, null);
        if (optionId.equals("refuse") || optionId.equals("insulted") || optionId.equals("attack")) {
            StoryHooks.remember(level, settlement, StoryHooks.GORM, StoryCharacter.GORM, optionId,
                VisitorMemory.DISPLEASED);
        }
        switch (optionId) {
            case "pay_food", "pay_coins" -> {
                active.state.putString("Outcome", optionId.equals("pay_food") ? "paid_food" : "paid_coins");
                leave(level, active);
                boolean favour = Math.floorMod(active.id.hashCode(), 100) < FAVOUR_PERCENT;
                if (favour) {
                    WorldEventSavedData data = WorldEventSavedData.get(level);
                    WorldEventSavedData.Row row = data.row(settlement.id);
                    if (row != null) row.bruteFavour = true;
                    data.markChanged();
                }
                return Component.translatableWithFallback("hearthstead.event.brute_toll.paid",
                    "The chief grunts, shoulders the toll and leads his brutes away.");
            }
            case "talked_down" -> {
                active.state.putString("Outcome", "talked_down");
                leave(level, active);
                // Survival QA #4: an undefended village was talked out of it with
                // an honest appeal, not with walls it does not have.
                return defended(settlement)
                    ? Component.translatableWithFallback("hearthstead.event.brute_toll.talked_down",
                        "The chief eyes the walls, spits, and leads his brutes away empty-handed.")
                    : Component.translatableWithFallback("hearthstead.event.brute_toll.talked_down_honest",
                        "The chief weighs your words, grunts, and leads his brutes on down the road.");
            }
            case "refuse", "insulted" -> {
                active.state.putInt("HostileAt", active.eligibleTicks + REFUSE_WARNING_TICKS);
                level.playSound(null, actor.blockPosition(), com.hearthstead.registry.ModSounds.EVENT_BRUTE_DEMAND.get(), SoundSource.HOSTILE, 1.5F, 0.95F);
                return Component.translatableWithFallback("hearthstead.event.brute_toll.warning",
                    "\"Then we take it from your bones.\" The brutes raise their clubs!").withStyle(ChatFormatting.RED);
            }
            default -> {
                turnHostile(level, settlement, active, null);
                return Component.translatableWithFallback("hearthstead.event.brute_toll.attack_first",
                    "%s strikes first! To arms!", player.getDisplayName()).withStyle(ChatFormatting.RED);
            }
        }
    }

    // ---------------------------------------------------- struck in parley --

    /** A player hitting a parleying brute starts the fight (the hit lands). */
    @SubscribeEvent
    public static void attacked(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        strike(player.serverLevel(), event.getTarget(), player);
    }

    @SubscribeEvent
    public static void shot(ProjectileImpactEvent event) {
        if (!(event.getProjectile().level() instanceof ServerLevel level)
            || !(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit)
            || !(event.getProjectile().getOwner() instanceof ServerPlayer player)) {
            return;
        }
        strike(level, hit.getEntity(), player);
    }

    private static void strike(ServerLevel level, Entity target, ServerPlayer player) {
        if (!(target instanceof RaiderEntity) || !target.getPersistentData().contains(WorldEventDirector.TAG)) return;
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, target);
        if (owner == null || owner.active().type != WorldEventType.BRUTE_TOLL
            || owner.active().state.getBoolean("Hostile") || owner.active().state.getBoolean("Leaving")) {
            return;
        }
        turnHostile(level, owner.settlement(), owner.active(), Component.translatableWithFallback(
            "hearthstead.event.brute_toll.attack_first", "%s strikes first! To arms!", player.getDisplayName()));
    }

    // -------------------------------------------------------------- favour --

    /** A fed brute returns the favour: one shouted warning when the next raid is announced. */
    static void maybeWarn(ServerLevel level, Settlement settlement, WorldEventSavedData.Row row, WorldEventSavedData data) {
        var plan = settlement.raidLifecycle.recurringWarnedPlan();
        if (plan.isEmpty()) plan = settlement.raidLifecycle.queuedPlan();
        if (plan.isEmpty()) return;
        row.bruteFavour = false;
        data.markChanged();
        double radians = Math.toRadians(plan.get().approachDegrees());
        double dx = -Math.sin(radians), dz = Math.cos(radians);
        String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
            "hearthstead.event.brute_toll.favour." + dir,
            "A brute you once fed bellows from the treeline: \"Raiders come from the " + dir + "! We are even.\"")
            .withStyle(ChatFormatting.GOLD));
    }

    /** Parley movement: walk to the stop point (Banner, or away when leaving) and stand. */
    static final class WalkToGoal extends Goal {
        private final RaiderEntity brute;
        private int repath;
        private BlockPos lastPos;
        private int stillSince;

        WalkToGoal(RaiderEntity brute) {
            this.brute = brute;
            setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() { return CompoundTagView.of(brute).contains("Stop"); }

        @Override
        public void tick() {
            BlockPos stop = BlockPos.of(CompoundTagView.of(brute).getLong("Stop"));
            if (brute.distanceToSqr(stop.getX() + .5, stop.getY(), stop.getZ() + .5) <= 4.0D) {
                brute.getNavigation().stop();
                return;
            }
            // Stuck recovery (QA #5): no progress for 10 s means this is as close as
            // the terrain allows; stand here and parley (the chief is talkable anywhere).
            if (lastPos == null || brute.blockPosition().distSqr(lastPos) >= 2) {
                lastPos = brute.blockPosition();
                stillSince = brute.tickCount;
            } else if (brute.tickCount - stillSince > 200) {
                CompoundTagView.of(brute).putLong("Stop", brute.blockPosition().asLong());
                brute.getNavigation().stop();
                return;
            }
            if (--repath <= 0) {
                repath = 30;
                brute.getNavigation().moveTo(stop.getX() + .5, stop.getY(), stop.getZ() + .5, 0.8D);
            }
        }
    }
}
