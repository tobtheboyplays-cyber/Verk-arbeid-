package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.state.RaidLifecycle;
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

    /**
     * How long a guest waits at the tavern (or the hearth, tavern-less)
     * before giving up on a settlement that cannot pay. 2.5 game days: long
     * enough that a settlement mid-harvest gets a real second chance, short
     * enough that an unpayable settlement is not haunted by the same guest
     * forever. Doubled while an innkeeper is on shift (see
     * {@link #tickWaitingTraveler}) — hospitality buys a guest more time to
     * wait.
     *
     * <p>That is a DIFFERENT hook from the innkeeper's price discount: what
     * joining costs (DESIGN.md system 8: "recruit by paying a price in
     * village-grown goods"), and the named discounts a settlement can earn
     * against it (an innkeeper on shift, a dining hall), live in
     * {@link Costs#recruit()} and {@link Costs#discountsFor} — this class
     * only ever asks, through {@link #recruitPrice} and
     * {@link #recruitDiscounts} below, so {@link #tickWaitingTraveler} and
     * the UI stay on the same one number, exactly what COSTS.md's
     * implementation map requires. One hook lets a slow settlement wait
     * longer, the other lowers what it pays once it can — the two stack
     * independently, and neither substitutes for the other.
     */
    private static final long GUEST_PATIENCE_TICKS = 60_000L;

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
        long foundedNight = Math.max(0L,
            Math.floorDiv(level.getDayTime(), RaidLifecycle.DAY_LENGTH));
        if (!s.raidLifecycle.initializeAtFounding(foundedNight, level.random,
            s.raidProfile)) {
            // A fresh instance has no competing lifecycle state, so failure
            // here would mean the founding record is unsafe to persist.
            return null;
        }
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
        if (!level.addFreshEntity(settler)) {
            // The target clock must only renew after an entity entered the
            // world. Roll back the bookkeeping performed above if the level
            // refused the spawn (another mod/event may cancel it).
            if (traveler) {
                if (settler.getUUID().equals(s.travelerId)) {
                    s.travelerId = null;
                    s.travelerSinceGameTime = 0L;
                }
            } else {
                s.removeRecord(settler.getUUID());
            }
            data(level).setDirty();
            return null;
        }
        data(level).setDirty();
        return settler;
    }

    /**
     * A waiting guest has been paid for and joins the settlement.
     *
     * <p>Payment itself is the caller's job ({@link #tickWaitingTraveler}
     * deducts the settlement's {@link #recruitPrice discounted recruit price}
     * before ever calling this) — by the time this runs the price is already
     * gone from the hearth, so this method only ever does the joining, the
     * same as it always has.
     */
    public static boolean convertTraveler(ServerLevel level, SettlerEntity settler) {
        Settlement s = byId(level, settler.getTargetSettlementId());
        if (s == null || s.population() >= s.capacity()) {
            return false;
        }
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        s.travelerId = null;
        settler.celebrate();
        level.playSound(null, s.center, ModSounds.SETTLER_RECRUITED.get(),
            SoundSource.NEUTRAL, 1.0F, 1.0F);
        broadcast(level, s, Component.translatable("hearthstead.message.recruited",
            settler.getSettlerName(), s.name));
        data(level).setDirty();
        return true;
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

        Building tavern = firstValidTavern(s);

        // A guest already waiting?
        if (s.travelerId != null) {
            tickWaitingTraveler(level, s, tavern);
            return;
        }

        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(
            level, s, RecruitmentPolicy.Stage.ATTRACTION);
        if (assessment.eligible()) {
            // One manager tick is one qualified real second. Hospitality can
            // lower the material price, never compress a 2-4 Minecraft-day
            // wall-clock into minutes.
            if (s.recruitProgress < s.recruitTarget) {
                s.recruitProgress++;
            }
            if (s.recruitQualifiedSeconds
                < RecruitmentPolicy.MIN_QUALIFIED_SECONDS) {
                s.recruitQualifiedSeconds++;
            }
            if (s.recruitProgress >= s.recruitTarget
                && s.recruitQualifiedSeconds >= RecruitmentPolicy.MIN_QUALIFIED_SECONDS) {
                SettlerEntity traveler = spawnSettler(level, s, true);
                if (traveler != null) {
                    // Only a real entity accepted by the level ends a cycle.
                    s.recruitProgress = 0;
                    s.recruitQualifiedSeconds = 0;
                    if (s.recruitCycle < Integer.MAX_VALUE) {
                        s.recruitCycle++;
                    }
                    s.recruitTarget = RecruitmentPolicy.targetFor(s.id, s.recruitCycle);
                    broadcast(level, s,
                        Component.translatable("hearthstead.message.traveler_spotted"));
                }
            }
        } else {
            // A blocked village cannot bank a hidden almost-complete guest.
            // Decay is controlled and symmetric across both required clocks.
            s.recruitProgress = Math.max(0, s.recruitProgress - 1);
            s.recruitQualifiedSeconds = Math.max(0, s.recruitQualifiedSeconds - 1);
        }
        data(level).setDirty();
    }

    /**
     * A guest is standing at the tavern (or the hearth, tavern-less), waiting
     * to be let in. Admits them the moment the settlement can pay its
     * {@link #recruitPrice discounted recruit price}; otherwise lets them
     * keep waiting up to their patience, doubled while an innkeeper is on
     * shift — checked straight off {@link Building#workers}, never a flag
     * kept in step by hand (Employment's own invariant: the worker list is
     * the only record of who is employed).
     *
     * <p>Payment is only ever attempted once the guest has actually reached
     * the waiting spot, so "joins only once they wait there" is a fact about
     * the world, not just a fact about the timer.
     */
    private static void tickWaitingTraveler(ServerLevel level, Settlement s,
                                            @Nullable Building tavern) {
        long waited = level.getGameTime() - s.travelerSinceGameTime;
        long patience = tavern != null && !tavern.workers.isEmpty()
            ? GUEST_PATIENCE_TICKS * 2 : GUEST_PATIENCE_TICKS;

        Entity entity = level.getEntity(s.travelerId);
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()) {
            // Chunk unloaded, or the guest is gone for some other reason.
            // Only give up tracking them once their patience would have run
            // out anyway, rather than holding the slot open forever.
            if (waited > patience) {
                s.travelerId = null;
                data(level).setDirty();
            }
            return;
        }

        BlockPos waitingSpot = tavern != null ? tavern.anchor : s.center;
        boolean arrived = waitingSpot != null
            && guest.blockPosition().distSqr(waitingSpot) <= 9;
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(
            level, s, RecruitmentPolicy.Stage.WAITING_ADMISSION);
        HearthBlockEntity hearth = RecruitmentPolicy.hearth(level, s);
        if (arrived && assessment.eligible() && hearth != null
            && s.id.equals(guest.getTargetSettlementId())
            // Same-tick defensive recheck: no live stack is touched unless
            // the exact discounted transaction remains payable.
            && Costs.canPay(hearth.getInventory(), assessment.price())) {
            Costs.pay(hearth.getInventory(), assessment.price());
            // Every conversion gate was approved above on this server tick;
            // convertTraveler has no remaining fallible inventory work.
            convertTraveler(level, guest);
            return;
        }

        if (waited > patience) {
            s.travelerId = null;
            String name = guest.getSettlerName();
            guest.discard();
            broadcast(level, s, Component.translatable("hearthstead.message.traveler_left", name));
            data(level).setDirty();
        }
    }

    /** The first valid TAVERN building in this settlement, or null. */
    @Nullable
    private static Building firstValidTavern(Settlement s) {
        for (Building b : s.buildings) {
            if (b.valid && b.type == BuildingType.TAVERN && b.anchor != null) {
                return b;
            }
        }
        return null;
    }

    /**
     * Whether {@code s} has a valid tavern right now -- the exact same
     * building-level test {@link #tickRecruitment}'s attractive-check
     * reads (D-TAVERN-1), exposed for the hearth's synced
     * {@code HearthMenu.DATA_TAVERN} slot and {@code /hearthstead recruit}'s
     * feedback, so neither call site re-derives its own copy of the gate.
     */
    public static boolean hasValidTavern(Settlement s) {
        return firstValidTavern(s) != null;
    }

    /**
     * Admin fast-forward used by {@code /hearthstead recruit}. It advances
     * both clocks to one qualified second before completion, but refuses the
     * same attraction blockers normal runtime does. Admission remains a
     * separate, fully assessed and paid transaction after the guest arrives.
     */
    public static boolean primeRecruitment(ServerLevel level, Settlement s) {
        if (s.travelerId != null) {
            return false;
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(
            level, s, RecruitmentPolicy.Stage.ATTRACTION);
        if (!assessment.eligible()) {
            return false;
        }
        s.recruitProgress = Math.max(0, s.recruitTarget - 1);
        s.recruitQualifiedSeconds = RecruitmentPolicy.MIN_QUALIFIED_SECONDS - 1;
        data(level).setDirty();
        return true;
    }

    // ------------------------------------------------------- the price ---

    /**
     * What recruiting would cost this settlement RIGHT NOW, every discount
     * it has earned already applied — the one call both
     * {@link #tickWaitingTraveler} and the hire/recruit UI are meant to make,
     * so the two never drift onto two different numbers. See
     * {@link #recruitDiscounts} for the itemization behind this total.
     */
    public static Costs.Price recruitPrice(ServerLevel level, Settlement s) {
        return Costs.afterDiscounts(Costs.recruit(), recruitDiscounts(level, s));
    }

    /**
     * The named discount lines making up {@link #recruitPrice}'s reduction —
     * e.g. an employed innkeeper, a valid dining hall — for the UI to render
     * per COSTS.md's "UI rule": full price, then discounted price with each
     * hook named ("Vertshusholderen -25%, Spisesalen -25%").
     */
    public static List<Costs.Discount> recruitDiscounts(ServerLevel level, Settlement s) {
        return Costs.discountsFor(level, s, Costs.PriceKey.RECRUIT);
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
