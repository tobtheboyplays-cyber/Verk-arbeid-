package com.hearthstead.event.worldevent;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Wolf pack at night: three wolves come for the livestock. Guards run out
 * (their ordinary combat engages the wolves, which are hostile monsters).
 * The pack kills at most {@link #MAX_KILLS} animal; after a kill, or once
 * two wolves are down, the rest slink off. By dawn (budget) they are gone.
 */
final class WolfPackEvent implements WorldEventHandler {
    static final String ROLE_WOLF = "pack_wolf";
    static final int PACK = 3;
    static final int MAX_KILLS = 1;
    static final int GUARDS_SENT = 2;

    @Override
    public WorldEventType type() {
        return WorldEventType.WOLF_PACK;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return WorldEventCreatures.livestock(level, settlement).size() >= 2;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<LivingEntity> herd = WorldEventCreatures.livestock(level, settlement);
        if (herd.size() < 2) return false;
        List<BlockPos> positions = new ArrayList<>();
        for (LivingEntity animal : herd) positions.add(animal.blockPosition());
        BlockPos pasture = WorldEventCreatures.centroid(positions);
        BlockPos den = WorldEventCreatures.ringSpot(level, settlement, pasture, 22, 30, 0.7F, 1.0F, level.random);
        if (den == null) den = WorldEventCreatures.ringSpot(level, null, pasture, 14, 20, 0.7F, 1.0F, level.random);
        if (den == null) return false;
        int spawned = 0;
        for (int i = 0; i < PACK; i++) {
            BlockPos feet = WorldEventCreatures.nearFeet(level, den.offset(i - 1, 0, i % 2), 0.7F, 1.0F);
            if (feet == null) continue;
            PackWolfEntity wolf = WorldEventEntities.PACK_WOLF.get().create(level);
            if (wolf == null) continue;
            wolf.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, level.random.nextFloat() * 360F, 0F);
            wolf.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
            wolf.setPersistenceRequired();
            wolf.setAnchor(pasture);
            WorldEventDirector.tag(wolf, settlement, active, ROLE_WOLF);
            if (level.addFreshEntity(wolf)) spawned++;
        }
        if (spawned == 0) return false;
        active.state.putInt("Pack", spawned);
        active.state.putBoolean("Seen", true);
        active.state.putLong("Pasture", pasture.asLong());
        level.playSound(null, den, com.hearthstead.registry.ModSounds.EVENT_WOLF_HOWL.get(), SoundSource.HOSTILE, 4.0F, 1.0F);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.wolves.arrive", "Wolves howl near the pastures!"),
            Component.translatableWithFallback("hearthstead.event.wolves.cta",
                "Guards, torches up and out to the livestock. Drive the pack off!"), pasture);
        return true;
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<PackWolfEntity> wolves = new ArrayList<>();
        for (Entity entity : WorldEventActors.actors(level, active)) {
            if (entity instanceof PackWolfEntity wolf) wolves.add(wolf);
        }
        int killed = active.state.getInt("WolvesKilled");
        if (wolves.isEmpty()) {
            if (WorldEventActors.presence(level, active) == WorldEventActors.Presence.ABSENT) return;
            if (active.state.getBoolean("Seen") || killed > 0) end(level, settlement, active);
            return;
        }
        active.state.putBoolean("Seen", true);
        boolean retreat = active.state.getInt("Kills") >= MAX_KILLS
            || killed >= Math.max(1, active.state.getInt("Pack") - 1);
        if (retreat) {
            // The survivors slink off into the dark: they walk out and vanish only unseen.
            for (PackWolfEntity wolf : wolves) wolf.setLeaving();
            level.playSound(null, wolves.get(0).blockPosition(), SoundEvents.WOLF_WHINE, SoundSource.HOSTILE, 2.0F, 0.9F);
            end(level, settlement, active);
            return;
        }
        if (!retreat) sendGuards(level, settlement, active, wolves);
    }

    private static void sendGuards(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                   List<PackWolfEntity> wolves) {
        List<SettlerEntity> guards = WorldEventActors.members(level, settlement,
            s -> WorldEventActors.isMartial(s) && s.getTarget() == null);
        int sent = 0;
        for (PackWolfEntity wolf : wolves) {
            if (sent >= GUARDS_SENT) break;
            SettlerEntity guard = WorldEventActors.nearest(guards, wolf, 64.0D);
            if (guard == null) break;
            guards.remove(guard);
            WorldEventDirector.assign(guard, new WorldEventDirector.Role(WorldEventDirector.RoleKind.CHASE,
                active.id, wolf.getUUID(), null, level.getGameTime() + 40, null, 1.1D));
            sent++;
        }
    }

    /** A livestock animal fell to a pack wolf. */
    static void livestockKilled(ServerLevel level, PackWolfEntity wolf) {
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, wolf);
        if (owner == null) return;
        owner.active().state.putInt("Kills", owner.active().state.getInt("Kills") + 1);
        WorldEventSavedData.get(level).markChanged();
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, DamageSource source) {
        active.state.putInt("WolvesKilled", active.state.getInt("WolvesKilled") + 1);
    }

    private static void end(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        int killed = active.state.getInt("WolvesKilled");
        int lost = active.state.getInt("Kills");
        Component notice = killed >= active.state.getInt("Pack")
            ? Component.translatableWithFallback("hearthstead.event.wolves.slain",
                "The whole pack lies dead. The herd is safe tonight.")
            : lost > 0
                ? Component.translatableWithFallback("hearthstead.event.wolves.took",
                    "The wolves dragged off %s animal and melted into the dark.", lost)
                : Component.translatableWithFallback("hearthstead.event.wolves.driven",
                    "The pack is driven off. %s wolves fell; not one animal lost.", killed);
        WorldEventDirector.finish(level, settlement, killed >= active.state.getInt("Pack") ? "slain"
            : lost > 0 ? "took_livestock" : "driven_off", notice);
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        end(level, settlement, active);
    }
}
