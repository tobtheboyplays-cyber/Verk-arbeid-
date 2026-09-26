package com.hearthstead.event.worldevent;

import com.hearthstead.settlement.Settlement;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Wolf;

/**
 * Stray dog (owner-approved): a scruffy dog hangs about the Banner. Feed it
 * and it moves in for good as the settlement's one {@link VillageDog}; shoo
 * it and it trots off. Nobody bothers: it wanders away at the end of the
 * window. Only planned while the settlement has no dog.
 */
final class StrayDogEvent implements WorldEventHandler {
    static final String ROLE_DOG = "stray_dog";
    static final int FOOD = 2;

    @Override
    public WorldEventType type() {
        return WorldEventType.STRAY_DOG;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        WorldEventSavedData.Row row = WorldEventSavedData.get(level).row(settlement.id);
        return settlement.population() >= 2 && (row == null || row.villageDog == null);
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 4, 9, 0.7F, 0.9F, level.random);
        if (spot == null) return false;
        Wolf dog = EntityType.WOLF.create(level);
        if (dog == null) return false;
        dog.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, level.random.nextFloat() * 360F, 0F);
        dog.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        dog.setAge(0);
        dog.setPersistenceRequired();
        WorldEventDirector.tag(dog, settlement, active, ROLE_DOG);
        dog.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", "Stray dog");
        install(dog, spot);
        WorldEventActors.markWaiting(dog, Component.translatableWithFallback("hearthstead.event.stray_dog.who",
            "hungry"), true);
        if (!level.addFreshEntity(dog)) return false;
        active.state.putLong("Spot", spot.asLong());
        active.state.putBoolean("Seen", true);
        WorldEventConversations.bind(dog, settlement, WorldEventConversations.STRAY_DOG, "dog",
            "conversation.hearthstead.title.stray_dog", Map.of("food", FOOD));
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.stray_dog.arrive",
                "A scruffy stray dog is sniffing around the Banner."),
            Component.translatableWithFallback("hearthstead.event.stray_dog.cta",
                "Feed it and it may stay (right-click it)."), spot);
        return true;
    }

    /** A stray: no vanilla hunting, just hangs about its spot, tail low. */
    static void install(Wolf dog, BlockPos spot) {
        dog.goalSelector.removeAllGoals(goal -> true);
        dog.targetSelector.removeAllGoals(goal -> true);
        dog.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(dog));
        dog.goalSelector.addGoal(8, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(dog,
            net.minecraft.world.entity.player.Player.class, 8.0F));
        dog.goalSelector.addGoal(9, new net.minecraft.world.entity.ai.goal.RandomLookAroundGoal(dog));
        dog.restrictTo(spot, 6);
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (actor instanceof Wolf dog && active.state.contains("Spot")) install(dog, BlockPos.of(active.state.getLong("Spot")));
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity dog = WorldEventActors.actor(level, active, ROLE_DOG);
        if (dog == null) {
            if (WorldEventActors.presence(level, active, ROLE_DOG) == WorldEventActors.Presence.ABSENT) return;
            WorldEventDirector.finish(level, settlement, "gone", null);
            return;
        }
        if (active.state.getBoolean("Leaving")) {
            BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
            if (dog instanceof Wolf wolf && wolf.getNavigation().isDone()) {
                double dx = dog.getX() - settlement.center.getX(), dz = dog.getZ() - settlement.center.getZ();
                double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
                wolf.restrictTo(BlockPos.containing(dog.getX() + dx / len * 40, dog.getY(), dog.getZ() + dz / len * 40), 64);
                wolf.getNavigation().moveTo(dog.getX() + dx / len * 20, dog.getY(), dog.getZ() + dz / len * 20, 1.1D);
            }
            if (active.eligibleTicks - active.state.getInt("LeaveAt") > 300
                || dog.blockPosition().distSqr(spot) > 40 * 40) {
                WorldEventDirector.finish(level, settlement, active.state.getString("Outcome"), null);
            }
        } else if (level.random.nextInt(6) == 0) {
            dog.playSound(com.hearthstead.registry.ModSounds.EVENT_DOG_WHINE.get(), 0.8F, 1.0F);
        }
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventDirector.finish(level, settlement, "wandered_off", Component.translatableWithFallback(
            "hearthstead.event.stray_dog.wandered", "Nobody fed the stray. It trots off down the road."));
    }

    @Override
    public boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return !active.state.getBoolean("Leaving") && ROLE_DOG.equals(WorldEventDirector.tagRole(actor));
    }

    @Override
    public WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                          ServerPlayer player, Entity actor) {
        return new WorldEventVisitors.Topic(active.id, actor.getId(), actor.getUUID(),
            WorldEventActors.stableId("dog", settlement.id), "dog",
            Component.translatableWithFallback("hearthstead.event.stray_dog.title", "A stray dog"),
            Component.translatableWithFallback("hearthstead.event.stray_dog.who", "hungry"),
            List.of(Component.translatableWithFallback("conversation.hearthstead.stray_dog.line1",
                "The dog sits, thumps its tail and stares at your pack with enormous eyes.")),
            List.of(), "wandered_off", -1L);
    }

    @Override
    public List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                   WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of(
            WorldEventVisitors.Option.of("feed_dog", Component.translatableWithFallback(
                "conversation.hearthstead.stray_dog.feed", "Toss it some food"), WorldEventVisitors.Cost.food(FOOD),
                WorldEventVisitors.OptionStyle.PRIMARY).withRelation(20),
            WorldEventVisitors.Option.of("shoo", Component.translatableWithFallback(
                "conversation.hearthstead.stray_dog.shoo", "Shoo it away"), WorldEventVisitors.Cost.FREE,
                WorldEventVisitors.OptionStyle.DANGER).withRelation(-10));
    }

    @Override
    public Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor, String optionId) {
        WorldEventConversations.unbind(actor, null);
        if ("feed_dog".equals(optionId) && actor instanceof Wolf dog) {
            WorldEventSavedData.Row row = WorldEventSavedData.get(level).rowOrCreate(settlement.id);
            if (row != null && row.villageDog == null) {
                WorldEventDirector.release(dog, active);
                dog.setCustomName(Component.translatableWithFallback("hearthstead.event.stray_dog.name", "Village dog"));
                dog.setCustomNameVisible(false);
                dog.clearRestriction();
                VillageDog.adopt(level, settlement, dog, row);
                dog.playSound(com.hearthstead.registry.ModSounds.EVENT_DOG_BARK.get(), 1.0F, 1.0F);
                WorldEventDirector.finish(level, settlement, "adopted", null);
                return Component.translatableWithFallback("hearthstead.event.stray_dog.adopted",
                    "The dog wolfs it down, wags from nose to tail and follows you home. %s has a dog!", settlement.name);
            }
        }
        active.state.putBoolean("Leaving", true);
        active.state.putInt("LeaveAt", active.eligibleTicks);
        active.state.putString("Outcome", "shooed");
        WorldEventActors.markWaiting(actor, Component.translatableWithFallback("hearthstead.event.stray_dog.who",
            "hungry"), false);
        WorldEventDirector.finish(level, settlement, "shooed", null); // it trots off; never a puff
        return Component.translatableWithFallback("hearthstead.event.stray_dog.shooed",
            "The stray flattens its ears and slinks away.");
    }
}
