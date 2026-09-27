package com.hearthstead.settlement.guard.drill;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.GuardDrillScript;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.guard.patrol.PatrolService;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.summon.PlayerSummons;
import com.hearthstead.entity.LifeNeed;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The drill yard in front of one Barracks: who is in today's morning drill, where each stands,
 * who spars with whom, and the one synced cue per guard (GuardDrillGoal owns the movement).
 *
 * <p>Server thread only; in memory (a restart simply starts the next morning fresh). All the
 * work is lazy and bounded: the roster is re-read at most every {@value #ROSTER_INTERVAL} ticks
 * (a Barracks has at most four guards), the yard geometry is found once per day, and each guard
 * walks with one {@code moveTo} plus throttled repaths. Nothing here ticks on its own.
 *
 * <p>Safety: {@link #threat} is checked by every member every tick; the first alarm, raid or
 * target closes the yard for the rest of the day ({@link #cancel}). The drill never hurts
 * anyone, never sets a target and never touches equipment: it is a render cue, a few quiet
 * sounds and the Strength/XP bookkeeping in GuardDrillGoal.
 */
public final class GuardDrillYard {
    public static final int ROSTER_INTERVAL = 100;
    /** Cue modes (synced, see SettlerEntity#guardDrillCue). */
    public static final int MODE_NONE = 0;
    public static final int MODE_PAIR = 1;
    public static final int MODE_SOLO = 2;
    public static final int MODE_READY = 3;
    /** A member not seen by its goal for this long counts as away (its partner drills solo). */
    private static final int AWAY_TICKS = 40;

    private static final Map<UUID, Yard> YARDS = new HashMap<>();

    private GuardDrillYard() {
    }

    /** One member of today's drill. */
    static final class Member {
        final UUID id;
        int entityId = -1;
        long joinedAt;
        long endAt;
        long lastSeen = Long.MIN_VALUE;
        long arrivedAt = Long.MIN_VALUE;
        boolean drafted;

        Member(UUID id) {
            this.id = id;
        }

        boolean present(long now) {
            return now - lastSeen <= AWAY_TICKS && arrivedAt != Long.MIN_VALUE;
        }
    }

    /** Today's yard for one Barracks. */
    static final class Yard {
        final UUID barracksId;
        long day = Long.MIN_VALUE;
        boolean cancelled;
        String cancelReason = "";
        long nextRosterAt;
        final List<Member> members = new ArrayList<>();
        final Set<UUID> done = new HashSet<>();
        /** Why each finished member left (GameTest triage). */
        final Map<UUID, String> endReasons = new HashMap<>();
        final Map<Integer, BlockPos> slots = new HashMap<>();
        final Map<Integer, Long> pairStart = new HashMap<>();
        BlockPos origin;
        int axis;
        int sign;
        boolean noGround;

        Yard(UUID barracksId) {
            this.barracksId = barracksId;
        }

        void reset(long newDay) {
            day = newDay;
            cancelled = false;
            cancelReason = "";
            nextRosterAt = 0L;
            members.clear();
            done.clear();
            endReasons.clear();
            slots.clear();
            pairStart.clear();
            origin = null;
            noGround = false;
        }

        int indexOf(UUID id) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).id.equals(id)) return i;
            }
            return -1;
        }
    }

    /** What a member should show right now. */
    public record Assignment(int mode, long start, int seed, int slot, int length,
                             @Nullable SettlerEntity partner, @Nullable BlockPos faceTowards) {
        public CompoundTag cue() {
            CompoundTag tag = new CompoundTag();
            if (mode == MODE_NONE) return tag;
            tag.putInt("Mode", mode);
            tag.putLong("Start", start);
            tag.putInt("Seed", seed);
            tag.putInt("Slot", slot);
            tag.putInt("Len", length);
            return tag;
        }
    }

    // ------------------------------------------------------------------ eligibility

    /** The Barracks this guard belongs to, or null. */
    @Nullable
    public static Building barracksOf(Settlement settlement, SettlerEntity guard) {
        Building b = Employment.employerOf(settlement, guard.getUUID());
        // Not "valid": a Barracks whose walls failed the last survey still houses its guards.
        return b != null && b.type == BuildingType.BARRACKS && b.anchor != null ? b : null;
    }

    /** Anything that must end a drill at once: an alarm, a raid (pending or running), bad weather. */
    public static boolean threat(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        return LifeNeed.threatActive(settlement, now) || BlessingEffects.raidActive(settlement);
    }

    /** A player order, route, field order, banner team or summons owns this guard. */
    public static boolean hasStandingOrder(ServerLevel level, Settlement settlement, SettlerEntity guard) {
        if (PatrolService.assigned(guard) || FieldOrders.controls(guard) || FieldOrders.assignment(guard) != null
            || BannerTeams.active(guard) != null || PlayerSummons.active(guard) != null
            || Summons.active(guard)) {
            return true;
        }
        GuardAssignmentService.Validation v = GuardAssignmentService.validate(level, settlement, guard, false);
        return v.valid() && v.order().map(o -> o.modeAt(level.getGameTime()) != GuardOrder.Mode.NONE)
            .orElse(false);
    }

    /**
     * Whether this guard is fit to spar right now, whatever its watch: the node is owned, a
     * sword-family blade in hand, fed, rested enough, whole, free of orders, in fair weather, no
     * threat. {@code keep} applies the looser in-yard thresholds. A guard asleep after the night
     * watch is fit if it is not exhausted: the drill wakes it, and it sleeps after.
     */
    public static boolean fit(ServerLevel level, Settlement settlement, SettlerEntity guard, boolean keep) {
        return why(level, settlement, guard, keep) == null;
    }

    /** The first reason this guard cannot drill now, or null (GameTest triage and fit). */
    @Nullable
    public static String why(ServerLevel level, Settlement settlement, SettlerEntity guard, boolean keep) {
        if (guard.getProfession() != Profession.GUARD) return "not a guard";
        if (!guard.isAlive() || !guard.isBound() || guard.isTraveler()) return "not bound";
        if (guard.isPassenger()) return "riding";
        if (guard.getTarget() != null) return "has a target";
        if (guard.hurtTime > 0 || guard.isOnFire()) return "hurt";
        if (guard.hasMeal()) return "eating";
        if (level.isRaining() || level.isThundering()) return "rain";
        if (guard.getHunger() < (keep ? GuardDrillRules.KEEP_HUNGER : GuardDrillRules.MIN_HUNGER)) return "hungry";
        if (guard.getEnergy() < (keep ? GuardDrillRules.KEEP_ENERGY : GuardDrillRules.MIN_ENERGY)) return "exhausted";
        if (guard.getHealth() < GuardDrillRules.minHealth(guard.getMaxHealth())) return "wounded";
        if (!guard.hasGuardMeleeWeapon() || !"guard".equals(guard.guardWeaponClass().clipSet())) return "no sword";
        if (threat(level, settlement)) return "threat";
        if (!Development.hasUpgrade(level, settlement, PostRaidUpgrade.GUARD_DRILL)) return "no Guard Drill";
        if (barracksOf(settlement, guard) == null) return "no barracks";
        if (hasStandingOrder(level, settlement, guard)) return "has an order";
        return null;
    }

    // ------------------------------------------------------------------ membership

    private static Yard yard(ServerLevel level, Building barracks) {
        if (YARDS.size() > 64) YARDS.clear();
        Yard yard = YARDS.computeIfAbsent(barracks.id, Yard::new);
        long day = GuardDrillRules.day(level.getDayTime());
        if (yard.day != day) yard.reset(day);
        return yard;
    }

    /**
     * The member index of this guard in today's yard, admitting it if the roster says so;
     * -1 when it does not drill now. Cheap for a member; the roster re-read is throttled.
     */
    public static int admit(ServerLevel level, Settlement settlement, SettlerEntity guard) {
        Building barracks = barracksOf(settlement, guard);
        if (barracks == null) return -1;
        Yard yard = yard(level, barracks);
        long dayTime = level.getDayTime();
        if (yard.cancelled || yard.noGround || !GuardDrillRules.open(dayTime)
            || yard.done.contains(guard.getUUID())) {
            return -1;
        }
        int index = yard.indexOf(guard.getUUID());
        if (index >= 0) return index;
        long now = level.getGameTime();
        if (!GuardDrillRules.joinable(dayTime) || now < yard.nextRosterAt) return -1;
        yard.nextRosterAt = now + ROSTER_INTERVAL;
        List<SettlerEntity> offWatch = new ArrayList<>();
        List<SettlerEntity> draftable = new ArrayList<>();
        int onWatch = 0;
        for (UUID id : barracks.workers) {
            if (yard.indexOf(id) >= 0) continue;
            Entity e = level.getEntity(id);
            if (!(e instanceof SettlerEntity other) || other.getProfession() != Profession.GUARD
                || !other.isAlive()) {
                continue;
            }
            boolean watching = Schedule.onWatch(settlement, other, other.dayPhase());
            if (watching) onWatch++;
            if (yard.done.contains(id) || !fit(level, settlement, other, false)) continue;
            (watching ? draftable : offWatch).add(other);
        }
        List<SettlerEntity> admit = GuardDrillRules.roster(offWatch, draftable, onWatch, yard.members.size());
        long end = now + (GuardDrillRules.WINDOW_END - GuardDrillRules.timeOfDay(dayTime));
        for (SettlerEntity s : admit) {
            Member m = new Member(s.getUUID());
            m.entityId = s.getId();
            m.joinedAt = now;
            m.endAt = Math.min(end, now + GuardDrillRules.SESSION_MAX_TICKS);
            m.drafted = draftable.contains(s);
            yard.members.add(m);
        }
        return yard.indexOf(guard.getUUID());
    }

    /** Whether this guard is a member of today's drill (for other goals that must not interrupt). */
    public static boolean member(SettlerEntity guard) {
        if (!(guard.level() instanceof ServerLevel level)) return false;
        Settlement settlement = guard.settlement();
        Building barracks = settlement == null ? null : barracksOf(settlement, guard);
        Yard yard = barracks == null ? null : YARDS.get(barracks.id);
        return yard != null && yard.day == GuardDrillRules.day(level.getDayTime()) && !yard.cancelled
            && yard.indexOf(guard.getUUID()) >= 0;
    }

    /** Last tick of this member's session (game time), or -1. */
    public static long endAt(ServerLevel level, Settlement settlement, SettlerEntity guard) {
        Member m = memberOf(level, settlement, guard);
        return m == null ? -1L : m.endAt;
    }

    @Nullable
    private static Member memberOf(ServerLevel level, Settlement settlement, SettlerEntity guard) {
        Building barracks = barracksOf(settlement, guard);
        Yard yard = barracks == null ? null : YARDS.get(barracks.id);
        if (yard == null || yard.day != GuardDrillRules.day(level.getDayTime())) return null;
        int i = yard.indexOf(guard.getUUID());
        return i < 0 ? null : yard.members.get(i);
    }

    @Nullable
    private static Yard yardOf(Settlement settlement, SettlerEntity guard) {
        Building barracks = barracksOf(settlement, guard);
        return barracks == null ? null : YARDS.get(barracks.id);
    }

    /** Whether today's yard was closed by a threat. */
    public static boolean cancelled(Settlement settlement, SettlerEntity guard) {
        Yard yard = yardOf(settlement, guard);
        return yard != null && yard.cancelled;
    }

    /** Closes the yard for the rest of the day (the first alarm, raid or target). */
    public static void cancel(Settlement settlement, SettlerEntity guard, String reason) {
        Yard yard = yardOf(settlement, guard);
        if (yard != null && !yard.cancelled) {
            yard.cancelled = true;
            yard.cancelReason = reason;
        }
    }

    /** This member is done for today (session over, gave up walking, or left). */
    public static void finish(Settlement settlement, SettlerEntity guard) {
        finish(settlement, guard, "done");
    }

    public static void finish(Settlement settlement, SettlerEntity guard, String reason) {
        Yard yard = yardOf(settlement, guard);
        if (yard == null) return;
        yard.endReasons.put(guard.getUUID(), reason + "@" + guard.level().getGameTime());
        int i = yard.indexOf(guard.getUUID());
        if (i >= 0) {
            // Keep the index (partners stay partners); the member is simply never present again.
            Member m = yard.members.get(i);
            m.lastSeen = Long.MIN_VALUE;
            m.arrivedAt = Long.MIN_VALUE;
        }
        yard.done.add(guard.getUUID());
    }

    /** Called every tick by the member's goal. */
    public static void heartbeat(ServerLevel level, Settlement settlement, SettlerEntity guard, boolean arrived) {
        Member m = memberOf(level, settlement, guard);
        if (m == null) return;
        long now = level.getGameTime();
        m.lastSeen = now;
        m.entityId = guard.getId();
        if (arrived && m.arrivedAt == Long.MIN_VALUE) {
            m.arrivedAt = now;
        } else if (!arrived) {
            m.arrivedAt = Long.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------ geometry

    /** The block member {@code index} drills on, or null when the yard has no ground. */
    @Nullable
    public static BlockPos slotPos(ServerLevel level, Settlement settlement, SettlerEntity guard, int index) {
        Building barracks = barracksOf(settlement, guard);
        if (barracks == null) return null;
        Yard yard = yard(level, barracks);
        BlockPos cached = yard.slots.get(index);
        if (cached != null) return cached;
        if (yard.origin == null) {
            placeOrigin(yard, barracks, settlement);
        }
        int[] off = GuardDrillRules.slot(index);
        int lat = off[0];
        int out = off[1];
        BlockPos base = yard.axis == 0
            ? yard.origin.offset(yard.sign * out, 0, lat)
            : yard.origin.offset(lat, 0, yard.sign * out);
        BlockPos found = ground(level, base, barracks.anchor.getY());
        if (found == null) {
            // Only a loaded yard with no floor closes it for the day; an unloaded chunk retries.
            if (index == 0 && level.hasChunkAt(base)) yard.noGround = true;
            return null;
        }
        yard.slots.put(index, found);
        return found;
    }

    private static void placeOrigin(Yard yard, Building barracks, Settlement settlement) {
        BoundingBox box = barracks.bounds;
        double bx = box != null ? (box.minX() + box.maxX()) / 2.0 : barracks.anchor.getX();
        double bz = box != null ? (box.minZ() + box.maxZ()) / 2.0 : barracks.anchor.getZ();
        BlockPos centre = settlement.center;
        int[] face = GuardDrillRules.face(bx, bz, centre.getX(), centre.getZ());
        yard.axis = face[0];
        yard.sign = face[1];
        int y = barracks.anchor.getY();
        if (box == null) {
            yard.origin = new BlockPos(barracks.anchor.getX(), y, barracks.anchor.getZ());
        } else if (yard.axis == 0) {
            int x = yard.sign > 0 ? box.maxX() : box.minX();
            yard.origin = new BlockPos(x, y, (int) Math.floor(bz));
        } else {
            int z = yard.sign > 0 ? box.maxZ() : box.minZ();
            yard.origin = new BlockPos((int) Math.floor(bx), y, z);
        }
    }

    /**
     * A standable block near {@code base}: solid floor, two free blocks, dry, within 3 blocks of
     * the Barracks' own floor height. Scans the column top-down around that height rather than
     * trusting the heightmap, which a roof overhang, a tree or a GameTest frame can put far above.
     */
    @Nullable
    static BlockPos ground(ServerLevel level, BlockPos base, int anchorY) {
        for (int ring = 0; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = base.getX() + dx;
                    int z = base.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, anchorY, z))) return null;
                    for (int y = anchorY + 3; y >= anchorY - 3; y--) {
                        BlockPos feet = new BlockPos(x, y, z);
                        if (standable(level, feet)) return feet;
                    }
                }
            }
        }
        return null;
    }

    public static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && level.getFluidState(feet).isEmpty() && level.getFluidState(below).isEmpty();
    }

    // ------------------------------------------------------------------ pairing

    /** What member {@code index} shows now: waiting, sparring with its partner, or shadow drill. */
    public static Assignment assignment(ServerLevel level, Settlement settlement, SettlerEntity guard, int index) {
        Building barracks = barracksOf(settlement, guard);
        Yard yard = barracks == null ? null : YARDS.get(barracks.id);
        if (yard == null || index < 0 || index >= yard.members.size()) {
            return new Assignment(MODE_NONE, 0L, 0, 0, 0, null, null);
        }
        long now = level.getGameTime();
        Member self = yard.members.get(index);
        int partnerIndex = GuardDrillRules.partnerIndex(index);
        Member other = partnerIndex < yard.members.size() ? yard.members.get(partnerIndex) : null;
        BlockPos partnerSlot = yard.slots.get(partnerIndex);
        if (partnerSlot == null && yard.origin != null) {
            partnerSlot = slotPos(level, settlement, guard, partnerIndex);
        }
        if (self.arrivedAt == Long.MIN_VALUE) {
            return new Assignment(MODE_NONE, 0L, 0, 0, 0, null, partnerSlot);
        }
        int pair = index / 2;
        int seedBase = (int) (barracks.id.getLeastSignificantBits() ^ (yard.day * 0x2545F491L) ^ (pair * 7919L));
        SettlerEntity partner = null;
        if (other != null && other.present(now) && level.getEntity(other.entityId) instanceof SettlerEntity p
            && p.isAlive()) {
            partner = p;
        }
        if (partner != null) {
            Long start = yard.pairStart.get(pair);
            long both = Math.max(self.arrivedAt, other.arrivedAt);
            if (start == null || start < both) {
                start = both + 10L;
                yard.pairStart.put(pair, start);
            }
            long end = Math.min(self.endAt, other.endAt);
            int len = (int) Math.max(0L, end - start);
            int seed = seedBase ^ (int) (start * 31L);
            return new Assignment(MODE_PAIR, start, seed, index & 1, len, partner,
                partner.blockPosition());
        }
        yard.pairStart.remove(pair);
        if (other != null && !yard.done.contains(other.id) && now - self.arrivedAt < 60L
            && other.lastSeen != Long.MIN_VALUE) {
            // The partner is on its way: wait in the stance a little before drilling alone.
            return new Assignment(MODE_READY, self.arrivedAt, seedBase ^ index, index & 1, 0, null, partnerSlot);
        }
        long start = self.arrivedAt + 10L;
        int len = (int) Math.max(0L, self.endAt - start);
        return new Assignment(MODE_SOLO, start, seedBase ^ (int) (start * 17L) ^ index, GuardDrillScript.SOLO,
            len, null, partnerSlot);
    }

    /** Test/diagnostic view: members of this guard's yard, in join order. */
    public static List<UUID> members(Settlement settlement, SettlerEntity guard) {
        Yard yard = yardOf(settlement, guard);
        List<UUID> out = new ArrayList<>();
        if (yard != null) {
            for (Member m : yard.members) out.add(m.id);
        }
        return out;
    }

    /** Test/diagnostic: why the yard closed today ("" if it did not). */
    public static String cancelReason(Settlement settlement, SettlerEntity guard) {
        Yard yard = yardOf(settlement, guard);
        return yard == null ? "" : yard.cancelReason;
    }

    /** GameTest triage: the yard's state as one line. */
    public static String debug(ServerLevel level, Settlement settlement, SettlerEntity guard) {
        Building barracks = barracksOf(settlement, guard);
        if (barracks == null) return "yard: no barracks";
        Yard yard = YARDS.get(barracks.id);
        if (yard == null) return "yard: never asked";
        return "yard: day=" + yard.day + "/" + GuardDrillRules.day(level.getDayTime()) + " cancelled=" + yard.cancelled
            + (yard.cancelled ? "(" + yard.cancelReason + ")" : "") + " noGround=" + yard.noGround
            + " members=" + yard.members.size() + " done=" + yard.done.size() + " ended=" + yard.endReasons.values()
            + " origin=" + yard.origin
            + " slots=" + yard.slots.values();
    }

    /** GameTests reset a Barracks' yard between cases. */
    public static void forget(UUID barracksId) {
        YARDS.remove(barracksId);
    }
}
