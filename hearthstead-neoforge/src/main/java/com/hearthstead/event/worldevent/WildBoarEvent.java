package com.hearthstead.event.worldevent;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

/**
 * A wild boar in the fields: heavy, bad-tempered, roots up a few crops
 * (capped, growth only). Guards fight it with their normal combat; a
 * Hunter can shoot it and a Hunter's kill gives extra meat.
 */
final class WildBoarEvent implements WorldEventHandler {
    static final String ROLE_BOAR = "wild_boar";

    @Override
    public WorldEventType type() {
        return WorldEventType.WILD_BOAR;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return WorldEventCreatures.hasFields(level, settlement);
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<BlockPos> crops = WorldEventCreatures.crops(level, settlement, false, 32);
        BlockPos fields = WorldEventCreatures.centroid(crops);
        if (fields == null) return false;
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, fields, 20, 28, 1.2F, 1.2F, level.random);
        if (spot == null) spot = WorldEventCreatures.ringSpot(level, null, fields, 12, 18, 1.2F, 1.2F, level.random);
        if (spot == null) return false;
        WildBoarEntity boar = WorldEventEntities.WILD_BOAR.get().create(level);
        if (boar == null) return false;
        boar.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, level.random.nextFloat() * 360F, 0F);
        boar.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        boar.setPersistenceRequired();
        boar.setFields(fields);
        WorldEventDirector.tag(boar, settlement, active, ROLE_BOAR);
        if (!level.addFreshEntity(boar)) return false;
        active.state.putLong("Fields", fields.asLong());
        active.state.putBoolean("Seen", true);
        level.playSound(null, spot, com.hearthstead.registry.ModSounds.EVENT_BOAR_CHARGE.get(), SoundSource.HOSTILE, 2.0F, 0.85F);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.boar.arrive", "A wild boar is rooting up the fields!"),
            Component.translatableWithFallback("hearthstead.event.boar.cta",
                "It is heavy and angry: mind its charge. Guards and hunters, bring it down for meat."), fields);
        return true;
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity entity = WorldEventActors.actor(level, active, ROLE_BOAR);
        if (!(entity instanceof WildBoarEntity boar)) {
            if (WorldEventActors.presence(level, active, ROLE_BOAR) == WorldEventActors.Presence.ABSENT) return;
            if (active.state.getBoolean("Seen")) end(level, settlement, active);
            return;
        }
        active.state.putBoolean("Seen", true);
        if (boar.leaving() || boar.rooted() >= WildBoarEntity.MAX_ROOTED) {
            if (!active.state.contains("LeaveAt")) {
                active.state.putInt("LeaveAt", active.eligibleTicks);
                boar.setLeaving();
            }
            BlockPos fields = BlockPos.of(active.state.getLong("Fields"));
            double out = settlement.radius + 24.0D;
            if (boar.blockPosition().distSqr(fields) > out * out
                || active.eligibleTicks - active.state.getInt("LeaveAt") > 600) {
                active.state.putString("Outcome", "wandered_off");
                end(level, settlement, active);
            }
            return;
        }
        if (active.eligibleTicks % 40 == 0) WorldEventActors.hunterShot(level, settlement, boar, 8, active);
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, DamageSource source) {
        boolean hunter = source.getEntity() instanceof SettlerEntity settler
            && settler.getProfession() == Profession.HUNTER;
        active.state.putString("Outcome", hunter ? "hunter_kill" : "killed");
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        if (!active.state.contains("Outcome")) active.state.putString("Outcome", "wandered_off");
        end(level, settlement, active);
    }

    private static void end(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        String outcome = active.state.contains("Outcome") ? active.state.getString("Outcome") : "gone";
        int lost = active.state.getInt("CropDamage");
        Component notice = switch (outcome) {
            case "hunter_kill" -> Component.translatableWithFallback("hearthstead.event.boar.hunter",
                "The hunter brings down the boar. Extra meat for the larder! Crops rooted up: %s.", lost);
            case "killed" -> Component.translatableWithFallback("hearthstead.event.boar.killed",
                "The boar is down. Good meat on the ground. Crops rooted up: %s.", lost);
            default -> Component.translatableWithFallback("hearthstead.event.boar.left",
                "The boar trots back into the woods. Crops rooted up: %s.", lost);
        };
        WorldEventDirector.finish(level, settlement, outcome, notice);
    }
}
