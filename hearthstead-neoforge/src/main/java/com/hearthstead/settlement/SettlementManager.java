package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** All server-side settlement operations. Everything goes through here. */
public final class SettlementManager {
    /** GameTests found settlements in cramped test structures; the spacing
     *  rule would make every test after the first fail. Never true in play. */
    public static boolean ignoreFoundingDistance = false;

    public static SettlementSavedData data(ServerLevel level) {
        return SettlementSavedData.get(level);
    }

    @Nullable
    public static Settlement byId(ServerLevel level, UUID id) {
        return id == null ? null : data(level).settlements.get(id);
    }

    /** The settlement whose radius contains {@code pos}, if any. */
    @Nullable
    public static Settlement at(ServerLevel level, BlockPos pos) {
        for (Settlement s : data(level).settlements.values()) {
            if (s.inside(pos)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Found a new settlement centered on a placed hearth. Returns null when
     * another settlement is too close. Idempotent per position: a hearth that
     * already sits inside its own settlement re-binds instead of re-founding.
     */
    @Nullable
    public static Settlement tryFound(ServerLevel level, BlockPos hearthPos) {
        SettlementSavedData data = data(level);
        for (Settlement other : data.settlements.values()) {
            if (other.center.equals(hearthPos)) {
                return other;
            }
            double minDist = other.radius + Settlement.DEFAULT_RADIUS;
            if (!ignoreFoundingDistance
                && other.center.distSqr(hearthPos) < minDist * minDist) {
                return null;
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(),
            SettlerNames.pickSettlementName(level.random), hearthPos);
        data.settlements.put(s.id, s);
        data.setDirty();

        for (int i = 0; i < 3; i++) {
            spawnSettler(level, s, false);
        }

        level.playSound(null, hearthPos, ModSounds.SETTLEMENT_FOUNDED.get(),
            SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            hearthPos.getX() + 0.5, hearthPos.getY() + 1.2, hearthPos.getZ() + 0.5,
            24, 1.2, 0.8, 1.2, 0.02);
        broadcast(level, s, Component.translatable("hearthstead.message.founded", s.name));
        return s;
    }

    /** Called when a hearth block is broken. The settlement dissolves. */
    public static void disbandAt(ServerLevel level, BlockPos hearthPos) {
        SettlementSavedData data = data(level);
        Settlement found = null;
        for (Settlement s : data.settlements.values()) {
            if (s.center.equals(hearthPos)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            return;
        }
        for (SettlerEntity settler : loadedMembers(level, found)) {
            settler.unbind();
        }
        data.settlements.remove(found.id);
        data.setDirty();
        broadcast(level, found, Component.translatable("hearthstead.message.disbanded", found.name));
    }

    @Nullable
    public static SettlerEntity spawnSettler(ServerLevel level, Settlement s, boolean traveler) {
        BlockPos spawnPos = traveler
            ? findEdgeSpawn(level, s)
            : findGround(level, s.center.offset(level.random.nextInt(7) - 3, 0,
                level.random.nextInt(7) - 3), s.center);
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        if (settler == null) {
            return null;
        }
        Set<String> taken = new HashSet<>();
        for (Settlement.SettlerRecord r : s.settlers) {
            taken.add(r.name);
        }
        String name = SettlerNames.pickSettlerName(level.random, taken);
        settler.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
            level.random.nextFloat() * 360.0F, 0.0F);
        settler.setSettlerName(name);
        settler.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
            MobSpawnType.MOB_SUMMONED, null);
        // Appearance seed is already rolled in the SettlerEntity constructor
        // for every creation path, not just this one.
        if (traveler) {
            settler.markTraveler(s.id, s.center);
            s.travelerId = settler.getUUID();
            s.travelerSinceGameTime = level.getGameTime();
        } else {
            settler.bindTo(s.id, s.center);
            s.putRecord(settler.getUUID(), name, Profession.NONE);
        }
        data(level).setDirty();
        level.addFreshEntity(settler);
        return settler;
    }

    /** A traveler reached the hearth and joins the settlement. */
    public static void convertTraveler(ServerLevel level, SettlerEntity settler) {
        Settlement s = byId(level, settler.getTargetSettlementId());
        if (s == null || s.population() >= s.capacity()) {
            settler.discard();
            return;
        }
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        s.travelerId = null;
        if (level.getBlockEntity(s.center) instanceof HearthBlockEntity hearth) {
            hearth.consumeFood(3);
        }
        settler.celebrate();
        level.playSound(null, s.center, ModSounds.SETTLER_RECRUITED.get(),
            SoundSource.NEUTRAL, 1.0F, 1.0F);
        broadcast(level, s, Component.translatable("hearthstead.message.recruited",
            settler.getSettlerName(), s.name));
        data(level).setDirty();
    }

    /** One-second cadence, driven by the hearth block entity. */
    public static void tickRecruitment(ServerLevel level, Settlement s) {
        List<SettlerEntity> members = loadedMembers(level, s);
        if (!members.isEmpty()) {
            int total = 0;
            for (SettlerEntity m : members) {
                total += (int) m.getMorale();
            }
            s.moraleCache = total / members.size();
        }

        // A traveler already walking in?
        if (s.travelerId != null) {
            Entity traveler = level.getEntity(s.travelerId);
            boolean expired = level.getGameTime() - s.travelerSinceGameTime > 6000;
            if (traveler == null && expired) {
                s.travelerId = null;
            }
            return;
        }

        boolean attractive = s.population() < s.capacity()
            && s.foodCache >= 8
            && s.moraleCache >= 60;
        if (attractive) {
            if (s.recruitTarget <= 0) {
                s.recruitTarget = 200 + level.random.nextInt(80);
            }
            s.recruitProgress += s.moraleCache >= 80 ? 2 : 1;
            if (s.recruitProgress >= s.recruitTarget) {
                s.recruitProgress = 0;
                s.recruitTarget = 200 + level.random.nextInt(80);
                SettlerEntity traveler = spawnSettler(level, s, true);
                if (traveler != null) {
                    broadcast(level, s,
                        Component.translatable("hearthstead.message.traveler_spotted"));
                }
            }
        } else if (s.recruitProgress > 0) {
            s.recruitProgress--;
        }
        data(level).setDirty();
    }

    public static void raiseAlert(ServerLevel level, Settlement s, BlockPos threatPos) {
        long now = level.getGameTime();
        boolean fresh = !s.alertActive(now);
        s.alertUntilGameTime = now + 400;
        s.alertPos = threatPos;
        data(level).setDirty();
        if (fresh) {
            level.playSound(null, s.center, ModSounds.GUARD_ALERT.get(),
                SoundSource.NEUTRAL, 1.2F, 1.0F);
            broadcast(level, s, Component.translatable("hearthstead.message.alert", s.name));
        }
    }

    public static void onSettlerDied(ServerLevel level, SettlerEntity settler) {
        // A dead mayor is the settlement's problem, not just this
        // settler's: morale for everyone and three days of mourning.
        Settlement mayorSeat = byId(level, settler.getSettlementId());
        if (mayorSeat != null) {
            Mayor.onDeath(level, mayorSeat, settler);
        }
        Settlement s = byId(level, settler.getSettlementId());
        if (s == null) {
            return;
        }
        s.removeRecord(settler.getUUID());
        for (SettlerEntity m : loadedMembers(level, s)) {
            m.addMorale(-15.0F);
        }
        broadcast(level, s, Component.translatable("hearthstead.message.settler_died",
            settler.getSettlerName()));
        data(level).setDirty();
    }

    public static void noteProfessionChange(ServerLevel level, SettlerEntity settler) {
        Settlement s = byId(level, settler.getSettlementId());
        if (s == null) {
            return;
        }
        s.putRecord(settler.getUUID(), settler.getSettlerName(), settler.getProfession());
        data(level).setDirty();
    }

    public static List<SettlerEntity> loadedMembers(ServerLevel level, Settlement s) {
        List<SettlerEntity> out = new ArrayList<>();
        for (Settlement.SettlerRecord r : s.settlers) {
            if (level.getEntity(r.entityId) instanceof SettlerEntity settler && settler.isAlive()) {
                out.add(settler);
            }
        }
        return out;
    }

    private static void broadcast(ServerLevel level, Settlement s, Component msg) {
        double range = s.radius + 32;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(s.center) <= range * range) {
                p.displayClientMessage(msg, false);
            }
        }
    }

    private static BlockPos findEdgeSpawn(ServerLevel level, Settlement s) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            int x = s.center.getX() + (int) (Math.cos(angle) * (s.radius - 4));
            int z = s.center.getZ() + (int) (Math.sin(angle) * (s.radius - 4));
            BlockPos edge = new BlockPos(x, s.center.getY(), z);
            if (level.isLoaded(edge)) {
                return findGround(level, edge, s.center);
            }
        }
        return findGround(level, s.center.offset(4, 0, 4), s.center);
    }

    /** Finds a standable Y near the candidate, scanning a short column. */
    private static BlockPos findGround(ServerLevel level, BlockPos candidate, BlockPos fallback) {
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos feet = candidate.atY(candidate.getY() + dy);
            BlockPos below = feet.below();
            BlockState floor = level.getBlockState(below);
            if (floor.isSolidRender(level, below)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return feet;
            }
        }
        return fallback.above();
    }

    private SettlementManager() {
    }
}
