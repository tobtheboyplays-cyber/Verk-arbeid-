package com.hearthstead.entity.combat.role;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.role.BandageItem;
import com.hearthstead.registry.RoleItems;
import com.hearthstead.revive.ReviveRules;
import com.hearthstead.revive.ReviveService;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The battlefield Healer (plan/BATTLE-ROLES.md §3), in priority order:
 * flee when targeted, revive a downed player, evacuate the badly wounded to
 * the Infirmary, bandage the wounded, restock supplies. Supplies are real
 * items carried in the offhand (bandages, or healing herbs) and restocked
 * from the Infirmary's chests, then the Warehouse's.
 */
public class HealerMedicGoal extends Goal {
    public static final String EVACUATE_UNTIL = "HearthsteadEvacuateUntil";
    public static final String EVACUATE_TO = "HearthsteadEvacuateTo";
    public static final int CHANNEL_TICKS = 30;
    public static final double CARE_RADIUS = 24.0D;
    public static final double REVIVE_RADIUS = 32.0D;
    public static final double THREAT_RADIUS = 4.0D;
    public static final int FLEE_TICKS = 60;
    public static final int RESTOCK_TARGET = 16;
    public static final int RESTOCK_BELOW = 4;
    private static final double TOUCH = 2.0D;

    /** Live healers, for the revive rescuer hook. */
    private static final Map<SettlerEntity, HealerMedicGoal> LIVE = new WeakHashMap<>();

    public enum Mode { NONE, FLEE, REVIVE, EVACUATE, BANDAGE, RESTOCK }

    private final SettlerEntity healer;
    private Mode mode = Mode.NONE;
    @Nullable private UUID patient;
    @Nullable private UUID reviveTarget;
    private long reviveOfferedUntil = Long.MIN_VALUE;
    private long fleeUntil = Long.MIN_VALUE;
    @Nullable private Vec3 fleeTo;
    private long channelStart = Long.MIN_VALUE;
    private long nextThink = Long.MIN_VALUE;
    private long nextRepath = Long.MIN_VALUE;
    private long nextRestockCheck = Long.MIN_VALUE;
    /** Every mode ends by this tick, so an unreachable target never strands a Healer. */
    private long modeUntil = Long.MIN_VALUE;
    public static final int MODE_TIMEOUT_TICKS = 400;

    // ----- evidence --------------------------------------------------------
    private int bandagesApplied;
    private int revivePings;
    private int evacuations;
    private int flights;

    public HealerMedicGoal(SettlerEntity healer) {
        this.healer = healer;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        LIVE.put(healer, this);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean isHealer() {
        return RoleCombat.enabled() && healer.getProfession() == Profession.HEALER && healer.isAlive()
            && healer.level() instanceof ServerLevel && healer.settlement() != null;
    }

    @Override
    public boolean canUse() {
        if (!isHealer()) {
            return false;
        }
        ServerLevel level = (ServerLevel) healer.level();
        long now = level.getGameTime();
        if (now < nextThink) {
            return false;
        }
        nextThink = now + 10;
        mode = pickMode(level, now);
        modeUntil = now + MODE_TIMEOUT_TICKS;
        return mode != Mode.NONE;
    }

    @Override
    public boolean canContinueToUse() {
        return isHealer() && mode != Mode.NONE;
    }

    @Override
    public void stop() {
        mode = Mode.NONE;
        patient = null;
        channelStart = Long.MIN_VALUE;
        healer.getNavigation().stop();
        healer.setActivity(SettlerActivity.IDLE);
    }

    // ----------------------------------------------------------- decide

    private Mode pickMode(ServerLevel level, long now) {
        LivingEntity threat = threat(level, now);
        if (threat != null) {
            startFlee(threat, now);
            return Mode.FLEE;
        }
        if (reviveTarget != null && now <= reviveOfferedUntil) {
            return Mode.REVIVE;
        }
        reviveTarget = null;
        Settlement settlement = healer.settlement();
        SettlerEntity evac = evacuee(level, settlement);
        if (evac != null && supplies() > 0) {
            patient = evac.getUUID();
            return Mode.EVACUATE;
        }
        if (supplies() > 0) {
            LivingEntity p = triage(level, settlement);
            if (p != null) {
                patient = p.getUUID();
                return Mode.BANDAGE;
            }
        }
        // The bandage errand is peacetime work: never from bed, on the sleep
        // shift or exhausted (this goal outranks RestAtNightGoal). Tending a
        // wounded ally above still wakes a medic, as it should.
        boolean mayErrand = !healer.isSleeping() && healer.getEnergy() >= 20.0F
            && !com.hearthstead.settlement.Schedule.shouldSleep(settlement, healer, healer.dayPhase());
        if (mayErrand && supplies() < RESTOCK_BELOW && now >= nextRestockCheck) {
            nextRestockCheck = now + 100;
            if (restockSource(level, settlement) != null && enemiesNear(level, 12.0D).isEmpty()) {
                return Mode.RESTOCK;
            }
        }
        return Mode.NONE;
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) healer.level();
        long now = level.getGameTime();
        if (now > modeUntil) {
            if (mode == Mode.RESTOCK) {
                nextRestockCheck = now + 1200;
            }
            mode = Mode.NONE;
            return;
        }
        if (mode == Mode.RESTOCK && now % 40 == 0 && supplies() > 0
            && triage(level, healer.settlement()) != null) {
            mode = Mode.NONE;
            return;
        }
        if (mode != Mode.FLEE && now % 10 == 0) {
            LivingEntity threat = threat(level, now);
            if (threat != null) {
                startFlee(threat, now);
                mode = Mode.FLEE;
            }
        }
        switch (mode) {
            case FLEE -> tickFlee(now);
            case REVIVE -> tickRevive(level, now);
            case EVACUATE -> tickEvacuate(level, now);
            case BANDAGE -> tickBandage(level, now);
            case RESTOCK -> tickRestock(level);
            default -> { }
        }
    }

    // ------------------------------------------------------------- flee

    @Nullable
    private LivingEntity threat(ServerLevel level, long now) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                healer.getBoundingBox().inflate(16.0D, 4.0D, 16.0D))) {
            if (!RoleCombat.hostileTo(healer.settlement(), e)) {
                continue;
            }
            boolean hunting = e instanceof Mob mob && mob.getTarget() == healer;
            if (hunting || healer.distanceToSqr(e) <= THREAT_RADIUS * THREAT_RADIUS) {
                return e;
            }
        }
        if (RoleWorld.lastHurt(level, healer.getUUID()) > now - 10) {
            UUID attacker = RoleWorld.lastAttacker(level, healer.getUUID());
            if (attacker != null && level.getEntity(attacker) instanceof LivingEntity a && a.isAlive()) {
                return a;
            }
        }
        return null;
    }

    private void startFlee(LivingEntity threat, long now) {
        Vec3 away = healer.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-4D) {
            away = new Vec3(1, 0, 0);
        }
        away = new Vec3(away.x, 0, away.z).normalize().scale(10.0D);
        fleeTo = healer.position().add(away);
        fleeUntil = now + FLEE_TICKS;
        modeUntil = Math.max(modeUntil, fleeUntil);
        flights++;
        channelStart = Long.MIN_VALUE;
    }

    private void tickFlee(long now) {
        if (now >= fleeUntil || fleeTo == null) {
            mode = Mode.NONE;
            return;
        }
        healer.setActivity(SettlerActivity.IDLE);
        if (now >= nextRepath) {
            healer.getNavigation().moveTo(fleeTo.x, fleeTo.y, fleeTo.z, 1.35D);
            nextRepath = now + 10;
        }
    }

    // ----------------------------------------------------------- revive

    /** Offered by {@link HealerRescuer}; true if this healer took the job. */
    boolean offerRevive(ServerLevel level, ServerPlayer downed) {
        if (!isHealer() || mode == Mode.FLEE
            || healer.distanceToSqr(downed) > REVIVE_RADIUS * REVIVE_RADIUS) {
            return false;
        }
        reviveTarget = downed.getUUID();
        reviveOfferedUntil = level.getGameTime() + 40;
        return true;
    }

    private void tickRevive(ServerLevel level, long now) {
        ServerPlayer downed = reviveTarget == null ? null
            : level.getServer().getPlayerList().getPlayer(reviveTarget);
        if (downed == null || downed.level() != level || !ReviveService.isDowned(downed)
            || now > reviveOfferedUntil) {
            reviveTarget = null;
            mode = Mode.NONE;
            return;
        }
        // Never walk into a fight: wait at a distance while a hostile stands over them.
        boolean hot = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                downed.getBoundingBox().inflate(5.0D, 3.0D, 5.0D))) {
            if (RoleCombat.hostileTo(healer.settlement(), e)) {
                hot = true;
                break;
            }
        }
        healer.getLookControl().setLookAt(downed, 30.0F, 30.0F);
        if (hot) {
            if (healer.distanceToSqr(downed) < 64.0D) {
                healer.getNavigation().stop();
            } else if (now >= nextRepath) {
                healer.getNavigation().moveTo(downed, 1.1D);
                nextRepath = now + 10;
            }
            return;
        }
        if (healer.distanceToSqr(downed) > ReviveRules.REVIVE_REACH_SQR * 0.8D) {
            if (now >= nextRepath) {
                healer.getNavigation().moveTo(downed, 1.25D);
                nextRepath = now + 10;
            }
            return;
        }
        healer.getNavigation().stop();
        healer.setActivity(SettlerActivity.WORK_REVIVE);
        if (ReviveService.pingRevive(healer, downed)) {
            revivePings++;
        }
    }

    // --------------------------------------------------------- evacuate

    @Nullable
    private SettlerEntity evacuee(ServerLevel level, @Nullable Settlement settlement) {
        Building infirmary = nearestOfType(settlement, BuildingType.INFIRMARY);
        if (infirmary == null) {
            return null;
        }
        for (SettlerEntity s : level.getEntitiesOfClass(SettlerEntity.class,
                healer.getBoundingBox().inflate(CARE_RADIUS, 4.0D, CARE_RADIUS))) {
            if (s == healer || !RoleCombat.isAlly(settlement, s) || !s.getProfession().battlefield()) {
                continue;
            }
            float f = s.getHealth() / Math.max(1.0F, s.getMaxHealth());
            boolean danger = !enemiesAround(level, s, 12.0D).isEmpty();
            if (HealLedger.shouldEvacuate(f, danger)
                && s.getPersistentData().getLong(EVACUATE_UNTIL) <= level.getGameTime()) {
                s.getPersistentData().putLong(EVACUATE_UNTIL, level.getGameTime() + 800);
                s.getPersistentData().putLong(EVACUATE_TO, infirmary.anchor.asLong());
                evacuations++;
                return s;
            }
        }
        return null;
    }

    private void tickEvacuate(ServerLevel level, long now) {
        LivingEntity p = patient == null ? null : level.getEntity(patient) instanceof LivingEntity l ? l : null;
        if (p == null || !p.isAlive() || p.getPersistentData().getLong(EVACUATE_UNTIL) <= now) {
            // Arrived (or the escort lapsed): patch them up now.
            mode = p != null && p.isAlive() ? Mode.BANDAGE : Mode.NONE;
            return;
        }
        if (now >= nextRepath) {
            healer.getNavigation().moveTo(p, 1.2D);
            nextRepath = now + 10;
        }
    }

    // ---------------------------------------------------------- bandage

    @Nullable
    private LivingEntity triage(ServerLevel level, @Nullable Settlement settlement) {
        List<HealLedger.Patient<LivingEntity>> list = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                healer.getBoundingBox().inflate(CARE_RADIUS, 4.0D, CARE_RADIUS))) {
            if (e == healer || !RoleCombat.isAlly(settlement, e)
                || e instanceof Player player && ReviveService.isDowned(player)) {
                continue;
            }
            list.add(new HealLedger.Patient<>(e, e.getHealth() / Math.max(1.0F, e.getMaxHealth()),
                healer.distanceToSqr(e), !enemiesAround(level, e, THREAT_RADIUS).isEmpty(),
                RoleWorld.healing(level, e)));
        }
        return HealLedger.triage(list);
    }

    private void tickBandage(ServerLevel level, long now) {
        LivingEntity p = patient == null ? null : level.getEntity(patient) instanceof LivingEntity l ? l : null;
        if (p == null || !p.isAlive() || supplies() <= 0
            || p.getHealth() >= p.getMaxHealth() || RoleWorld.healing(level, p)) {
            mode = Mode.NONE;
            return;
        }
        healer.getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (healer.distanceToSqr(p) > TOUCH * TOUCH) {
            channelStart = Long.MIN_VALUE;
            if (now >= nextRepath) {
                healer.getNavigation().moveTo(p, 1.15D);
                nextRepath = now + 10;
            }
            return;
        }
        healer.getNavigation().stop();
        healer.setActivity(SettlerActivity.WORK_BANDAGE);
        if (channelStart == Long.MIN_VALUE) {
            channelStart = now;
            return;
        }
        // Job fit (Spirit + Dexterity): a steadier healer bandages sooner.
        if (now - channelStart < com.hearthstead.entity.AttributeRuntime.shortenWork(healer, CHANNEL_TICKS)) {
            return;
        }
        channelStart = Long.MIN_VALUE;
        ItemStack supply = healer.getOffhandItem();
        // Spirit: +0..25% heal (plan/ATTRIBUTES.md).
        float heal = com.hearthstead.entity.AttributeRuntime.heal(healer, BandageItem.supplyHeal(supply));
        if (heal > 0.0F && RoleWorld.startHeal(level, p, heal)) {
            supply.shrink(1);
            bandagesApplied++;
            healer.train(Attribute.SPIRIT, 2.0F);
            level.playSound(null, p.blockPosition(), RoleItems.BANDAGE_SOUND.get(),
                SoundSource.NEUTRAL, 0.8F, 1.0F);
            p.getPersistentData().remove(EVACUATE_UNTIL);
        }
        mode = Mode.NONE;
    }

    // ---------------------------------------------------------- restock

    public int supplies() {
        ItemStack off = healer.getOffhandItem();
        return BandageItem.supplyHeal(off) > 0.0F ? off.getCount() : 0;
    }

    @Nullable
    private Building restockSource(ServerLevel level, @Nullable Settlement settlement) {
        for (BuildingType type : new BuildingType[] {BuildingType.INFIRMARY, BuildingType.WAREHOUSE}) {
            Building b = nearestOfType(settlement, type);
            if (b != null && countSupplies(level, b) > 0) {
                return b;
            }
        }
        return null;
    }

    private void tickRestock(ServerLevel level) {
        Building source = restockSource(level, healer.settlement());
        if (source == null || supplies() >= RESTOCK_TARGET) {
            mode = Mode.NONE;
            return;
        }
        long now = level.getGameTime();
        if (!healer.blockPosition().closerThan(source.anchor, 3.0D)) {
            if (now >= nextRepath) {
                healer.getNavigation().moveTo(source.anchor.getX() + 0.5D, source.anchor.getY(),
                    source.anchor.getZ() + 0.5D, 1.0D);
                nextRepath = now + 20;
            }
            return;
        }
        healer.getNavigation().stop();
        withdraw(level, source);
        mode = Mode.NONE;
    }

    private int countSupplies(ServerLevel level, Building b) {
        int n = 0;
        for (BlockPos pos : WarehouseIndex.containers(level, b)) {
            if (level.getBlockEntity(pos) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (BandageItem.supplyHeal(c.getItem(i)) > 0.0F) {
                        n += c.getItem(i).getCount();
                    }
                }
            }
        }
        return n;
    }

    /** Chest-true transfer into the offhand: same item as already carried, or anything if empty. */
    private void withdraw(ServerLevel level, Building b) {
        // J-06: something else in the offhand used to block every restock, so
        // the healer idled silently. Stow it in the bag first (never deleted;
        // with a full bag the hand simply stays as it is).
        ItemStack held = healer.getOffhandItem();
        if (!held.isEmpty() && BandageItem.supplyHeal(held) <= 0.0F) {
            ItemStack rest = healer.bag.addItem(held.copy());
            if (!rest.isEmpty()) {
                // Undo the partial move: the bag gives back exactly what it took.
                int moved = held.getCount() - rest.getCount();
                for (int i = 0; i < healer.bag.getContainerSize() && moved > 0; i++) {
                    ItemStack in = healer.bag.getItem(i);
                    if (ItemStack.isSameItemSameComponents(in, held)) {
                        int take = Math.min(moved, in.getCount());
                        in.shrink(take);
                        moved -= take;
                    }
                }
                return;
            }
            healer.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        for (BlockPos pos : WarehouseIndex.containers(level, b)) {
            if (!(level.getBlockEntity(pos) instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack in = c.getItem(i);
                if (BandageItem.supplyHeal(in) <= 0.0F) {
                    continue;
                }
                ItemStack off = healer.getOffhandItem();
                if (!off.isEmpty() && !ItemStack.isSameItemSameComponents(off, in)) {
                    continue;
                }
                int room = Math.min(RESTOCK_TARGET, in.getMaxStackSize()) - (off.isEmpty() ? 0 : off.getCount());
                if (room <= 0) {
                    return;
                }
                ItemStack taken = c.removeItem(i, Math.min(room, in.getCount()));
                if (taken.isEmpty()) {
                    continue;
                }
                c.setChanged();
                if (off.isEmpty()) {
                    healer.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, taken);
                } else {
                    off.grow(taken.getCount());
                }
            }
        }
    }

    // ----------------------------------------------------------- helpers

    private List<LivingEntity> enemiesNear(ServerLevel level, double r) {
        return enemiesAround(level, healer, r);
    }

    private List<LivingEntity> enemiesAround(ServerLevel level, LivingEntity centre, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                centre.getBoundingBox().inflate(r, 3.0D, r))) {
            if (RoleCombat.hostileTo(healer.settlement(), e) && centre.distanceToSqr(e) <= r * r) {
                out.add(e);
            }
        }
        return out;
    }

    @Nullable
    static Building nearestOfType(@Nullable Settlement settlement, BuildingType type) {
        if (settlement == null) {
            return null;
        }
        for (Building b : settlement.buildings) {
            if (b.valid && b.type == type) {
                return b;
            }
        }
        return null;
    }

    @Nullable
    static HealerMedicGoal of(SettlerEntity healer) {
        return LIVE.get(healer);
    }

    // --------------------------------------------- test seams / evidence ---

    public Mode mode() {
        return mode;
    }

    public int bandagesApplied() {
        return bandagesApplied;
    }

    public int revivePings() {
        return revivePings;
    }

    public int evacuations() {
        return evacuations;
    }

    public int flights() {
        return flights;
    }
}
