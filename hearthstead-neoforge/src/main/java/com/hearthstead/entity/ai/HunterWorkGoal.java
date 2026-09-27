package com.hearthstead.entity.ai;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.ButcheringTableBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.event.HunterShotEvents;
import com.hearthstead.item.CarcassData;
import com.hearthstead.item.CarcassItem;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.HunterButchery;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hunter's expedition: range a bounded radius out from the lodge, take
 * one wild animal at a time, carry its CARCASS home on his shoulders,
 * butcher it at the Lodge and store the meat and hide in the Lodge chest --
 * or, if nothing is safe to take, forage a mushroom instead.
 *
 * <h2>What stops this from stripping the world</h2>
 *
 * <p>"a hunter that clears every animal in the chunk permanently is a
 * strictly worse pasture" — so four separate things bound it, all real
 * every time, none cached:
 *
 * <ol>
 *   <li><b>A hard range cap.</b> {@link #findHuntable} only ever looks
 *       inside {@value #HUNT_RADIUS} blocks of the lodge's own anchor — an
 *       expedition, not a search of the whole loaded world.
 *   <li><b>The population floor.</b> A species is huntable only when MORE
 *       than {@value #MIN_SPECIES_POPULATION} of it are alive inside that
 *       same radius <i>right now</i> — counted fresh on every scan, the same
 *       "read live, never a cached headcount" rule {@link HerderWorkGoal}'s
 *       cull floor follows.
 *   <li><b>The player's own stock is off limits.</b> Any animal standing
 *       inside a REAL building's bounds — a player's pasture chief among
 *       them — is invisible to this goal ({@link #insideAnyBuilding}).
 *   <li><b>No pointless kills.</b> Babies drop no vanilla loot, so they are
 *       never targets (they still count toward the floor).
 * </ol>
 *
 * <h2>The carcass (one physical authority, start to finish)</h2>
 *
 * <p>{@link HunterShotEvents} turns the kill's vanilla drops into ONE
 * {@code hearthstead:carcass} item at the body (the drops leave the world in
 * the same event). From there the carcass is always exactly one thing: a
 * leased ground item, the Hunter's session-owned OFFHAND (drawn across his
 * shoulders), the stack on a Butchering Table, or a Lodge chest slot. Every
 * move between those owners happens in one server tick. The Hunter's death
 * drops a carried carcass through the settler's durable death-drop path; a
 * preempted haul (raid alarm, hunger, night) sets it down as a real item
 * that he -- or any player -- can pick up again, and a dropped carcass never
 * despawns ({@link CarcassItem#getEntityLifespan}).
 *
 * <p>Butchering converts the carcass into its recorded vanilla loot (plus a
 * rare trade-level bonus, see {@link HunterButchery}) straight into the bag
 * in the same tick the carcass is removed; the bag is then emptied into the
 * Lodge chest one unit at a time with the shared {@link GroundedBagUnload}
 * cycle. A Lodge without a Butchering Table still works: the Hunter dresses
 * the carcass on the floor by the chest, more slowly and without the bonus.
 *
 * <h2>Foraging</h2>
 *
 * <p>When nothing huntable is in range, the same expedition also checks for
 * a real {@code BROWN_MUSHROOM} within reach — {@code KITCHEN}'s stew input.
 */
public class HunterWorkGoal extends Goal {

    private enum Action { HUNT, FORAGE }

    private enum Mode {
        TO_TARGET, DRAWING, RECOVERY, TO_LOOT, PICKING_LOOT,
        /** Walking to the butcher spot, with a carcass or to one on a table. */
        HAUL,
        /** Skinning then jointing, on a table or on the Lodge floor. */
        BUTCHER,
        /** Walking to a Lodge chest that holds a carcass, to carry it out. */
        FETCH_CARCASS,
        /** Walking to / unloading into a Lodge chest; arrows restock here. */
        TO_LODGE
    }

    /** THE RANGE CAP: see the class doc, point 1. */
    public static final int HUNT_RADIUS = 28;
    public static final int VERTICAL_BAND = 10;
    /** THE POPULATION FLOOR: see the class doc, point 2. */
    public static final int MIN_SPECIES_POPULATION = 4;

    private static final int LOOK_INTERVAL = 50;
    private static final double HUNT_REACH = 6.0;
    private static final double FORAGE_REACH = 2.5;
    /** HUNTER_LOOSE is one non-looping 1.20s/24-tick physical shot. */
    public static final int HUNT_ANIMATION_TICKS = 24;
    public static final int HUNT_RELEASE_TICK = 14;
    /** HUNTER_DRAW: the pull starts at 0.28 s; the creak lands as the string moves. */
    public static final int HUNT_DRAW_CREAK_TICK = 6;
    /** HUNTER_DRAW: the right hand closes on the next arrow at the belt, 0.99 s. */
    public static final int HUNT_ARROW_FETCH_TICK = 20;
    private static final int REPATH_INTERVAL = 40;
    private static final int PATIENCE = 6;
    private static final int FORAGE_SCAN_BUDGET = 400;
    /** Empty Lodge stock is re-read twice per five seconds, never per tick. */
    private static final int AMMO_STOCK_RETRY_TICKS = 50;
    private static final int PICKUP_CONTACT_TICK = 11;
    private static final int PICKUP_DURATION_TICKS = 28;
    private static final double PICKUP_CONTACT_DISTANCE_SQR = 2.25D;
    /** The laden walk: a carcass on the shoulders slows the Hunter visibly. */
    public static final double HAUL_SPEED = 0.7D;
    /** Same reach as a container contact (ContainerApproach.CONTACT_DISTANCE_SQR). */
    private static final double TABLE_CONTACT_SQR = 6.25D;
    private static final int HAUL_PATIENCE = 12;
    private static final int BLOCKED_RETRY_TICKS = 100;
    /** Floor-dressing spot when the Lodge has no chest either. */
    private static final double ANCHOR_CONTACT = 3.0D;
    /** Throttle for the Lodge-table / loose-carcass / chest scans in canUse. */
    private static final int CARCASS_SCAN_TICKS = 40;
    /**
     * Soak finding (watchdog, 26 Sep): a drop the path reaches but the hand
     * cannot (one-node path beside it, blocked pickup ray) was released and
     * then re-claimed by the loose-carcass scan forever. One drop gets this
     * many ticks of real route time; then it is set aside for
     * {@link #UNREACHABLE_FORGET_TICKS} (never deleted -- it stays a real
     * item any player can carry to the Lodge).
     */
    private static final int LOOT_TARGET_TIMEOUT = 300;
    private static final long UNREACHABLE_FORGET_TICKS = 2400L;
    /** A settled path end may stand up to two blocks from the drop. */
    private static final double PICKUP_SETTLED_DISTANCE_SQR = 4.0D;

    private final SettlerEntity settler;
    private final WorkScanner mushroomScanner = new WorkScanner();
    private final GroundCollectionSession loot;
    private final GroundedBagUnload chestBag = new GroundedBagUnload();
    private Mode mode;
    private Action action;
    private Building lodge;
    private Animal target;
    private BlockPos mushroomTarget;
    /** Butchering Table in use (null = dress on the floor by {@link #chestTarget}). */
    private BlockPos table;
    private BlockPos chestTarget;
    private int workTicks;
    private int lookCooldown;
    private int mushroomCooldown;
    private long nextAmmoStockCheck;
    private long retryAt;
    private long nextCarcassScan;
    private final Map<java.util.UUID, Long> unreachableUntil = new HashMap<>();
    private java.util.UUID lootTargetId;
    private int lootTargetTicks;
    private double pickupReachSqr = PICKUP_CONTACT_DISTANCE_SQR;
    private int haulFailures;
    private boolean lodgeArrowStock;
    private int repathTimer;
    private int stuckChecks;
    private boolean pickupTransferred;
    private boolean arrowsHandled;
    private boolean butcherOnTable;
    private int butcherTotal;
    private int skinTotal;
    private boolean done;

    public HunterWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        this.loot = new GroundCollectionSession(settler,
            stack -> !stack.isEmpty(), 32);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.HUNTER
            && settler.isBound()
            && settler.dayPhase().work();
    }

    private int bagCount() {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private boolean carryingCarcass() {
        return loot.ownsOffhandItem() && CarcassItem.isCarcass(settler.getOffhandItem());
    }

    @Override
    public boolean canUse() {
        if (!workConditions()
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        Building building = Employment.employerOf(s, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null
            || building.type != BuildingType.HUNTERS_LODGE) {
            return false;
        }
        lodge = building;
        if (level.getGameTime() < retryAt) {
            return false;
        }
        loot.restorePersistedIndex(level);
        loot.adoptEligibleOffhand();
        loot.recoverOwned(level, huntBounds(building.anchor));

        // A saved (reloaded) chest unload resumes on its own contract clock;
        // a vanished chest releases only the visual claim (cargo stays bagged).
        var pending = settler.bagTransferPresentation();
        if (GroundedBagUnload.presentationTargetGone(level, settler, pending)) {
            GroundedBagUnload.clearPresentation(settler, pending);
            pending = settler.bagTransferPresentation();
        }
        if (pending.active() && !pending.sourcePickup()) {
            if (building.contains(pending.containerPos())) {
                clearAmmoStop();
                chestTarget = pending.containerPos();
                mode = Mode.TO_LODGE;
                return true;
            }
            GroundedBagUnload.clearPresentation(settler, pending);
        }
        // 1. A carcass already on the shoulders goes home first.
        if (carryingCarcass()) {
            clearAmmoStop();
            mode = Mode.HAUL;
            return true;
        }
        if (loot.ownsOffhandItem() || loot.hasTrackedDrops()) {
            clearAmmoStop();
            mode = Mode.TO_LOOT;
            return true;
        }
        // 2. A reconstructed goal must deliver even one real carried item
        // before a broken bow or new-work gate can stop it.
        if (bagCount() > 0) {
            clearAmmoStop();
            mode = Mode.TO_LODGE;
            return true;
        }
        // 3-5. Carcass work waiting at the Lodge or in the grounds. These
        // are block/entity scans, so they run at most every
        // CARCASS_SCAN_TICKS rather than on every goal poll.
        if (level.getGameTime() >= nextCarcassScan) {
            nextCarcassScan = level.getGameTime() + CARCASS_SCAN_TICKS;
            // 3. A carcass lying on this Lodge's table (laid by this Hunter
            // before an interruption, or by a player) is butchered next.
            BlockPos loadedTable = findTable(level, true);
            if (loadedTable != null) {
                clearAmmoStop();
                table = loadedTable;
                mode = Mode.HAUL;
                return true;
            }
            // 4. A loose carcass in the hunting grounds (dropped by a raid
            // alarm, a death, or a player) is picked up again.
            if (claimLooseCarcass(level, s)) {
                clearAmmoStop();
                mode = Mode.TO_LOOT;
                return true;
            }
            // 5. A carcass a player stored in the Lodge chest is carried out.
            BlockPos stored = chestHoldingCarcass(level);
            if (stored != null) {
                clearAmmoStop();
                chestTarget = stored;
                mode = Mode.FETCH_CARCASS;
                return true;
            }
        }
        if (settler.carriedArrowCount() > 0
            && !settler.carriedArrowsOwnedBy(building.id)
            && !settler.releaseCarriedArrows(level, s)) {
            return false;
        }

        boolean bowReady = EquipmentRequests.readyForProfession(level, settler,
            Profession.HUNTER);
        boolean hasArrows = settler.carriedArrowCount() > 0;
        if (!hasArrows && refreshLodgeArrowStock(level)) {
            clearAmmoStop();
            mode = Mode.TO_LODGE;
            return true;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        // Trade skill (primary, Perception): shorter pause between hunts.
        lookCooldown = SkillLevels.shortenWait(settler,
            LOOK_INTERVAL + settler.getRandom().nextInt(LOOK_INTERVAL));

        Animal found = bowReady && hasArrows
            ? findHuntable(level, s, building.anchor) : null;
        if (found != null) {
            clearAmmoStop();
            target = found;
            mushroomTarget = null;
            action = Action.HUNT;
            mode = Mode.TO_TARGET;
            return true;
        }

        // Nothing safe to hunt this cycle: forage instead. Own cooldown/
        // cursor, budgeted like every other block scan in the mod.
        if (mushroomCooldown > 0) {
            mushroomCooldown--;
        } else {
            List<BlockPos> found2 = mushroomScanner.scan(building.anchor, HUNT_RADIUS,
                FORAGE_SCAN_BUDGET, 4, this::isForageable);
            if (!found2.isEmpty()) {
                clearAmmoStop();
                mushroomTarget = found2.get(0);
                target = null;
                action = Action.FORAGE;
                mode = Mode.TO_TARGET;
                return true;
            }
            mushroomCooldown = 100 + settler.getRandom().nextInt(60);
        }
        if (!hasArrows) {
            publishAmmoWait(level);
        } else if (bowReady && settler.getActivity() != SettlerActivity.GAME_SCARCE) {
            // Armed and stocked but the floor protects every species in
            // range: say so on the sheet instead of idling silently.
            settler.setActivity(SettlerActivity.GAME_SCARCE);
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (done) {
            return false;
        }
        // An already-started physical return (haul, butcher, unload) may
        // finish after the work bell, but never against a cached, dismantled
        // or superseded employer.
        return mode == Mode.TO_LODGE || mode == Mode.HAUL
            || mode == Mode.BUTCHER || mode == Mode.FETCH_CARCASS
            ? refreshExactEmployer()
            : workConditions();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        workTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        pickupTransferred = false;
        arrowsHandled = false;
        settler.setActivity(SettlerActivity.TRAVELING);
        ServerLevel level = (ServerLevel) settler.level();
        if (mode == Mode.TO_LODGE) {
            beginDeposit(level);
        } else if (mode == Mode.HAUL) {
            beginHaul(level);
        } else if (mode == Mode.FETCH_CARCASS) {
            ContainerApproach.moveToContact(level, settler, chestTarget, 1.0D);
        } else if (mode == Mode.TO_LOOT) {
            beginLootRoute(level);
        } else if (action == Action.HUNT && target != null) {
            settler.setActivity(SettlerActivity.TRACKING_GAME);
            settler.getNavigation().moveTo(target, 1.0);
        } else if (mushroomTarget != null) {
            settler.getNavigation().moveTo(mushroomTarget.getX() + 0.5, mushroomTarget.getY(),
                mushroomTarget.getZ() + 0.5, 0.9);
        }
    }

    @Override
    public void tick() {
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if (done) return;
        if (mode == Mode.TO_LODGE) {
            tickDeposit();
        } else if (mode == Mode.HAUL) {
            tickHaul();
        } else if (mode == Mode.BUTCHER) {
            tickButcher();
        } else if (mode == Mode.FETCH_CARCASS) {
            tickFetch();
        } else if (mode == Mode.TO_LOOT) {
            tickLootRoute();
        } else if (mode == Mode.PICKING_LOOT) {
            tickLootPickup();
        } else if (mode == Mode.TO_TARGET) {
            if (action == Action.FORAGE) tickForage(); else tickTravelToTarget();
        } else if (mode == Mode.DRAWING) {
            tickDraw();
        } else if (mode == Mode.RECOVERY) {
            tickRecovery();
        }
    }

    // --------------------------------------------------------------- hunt ---

    private void tickTravelToTarget() {
        if (target == null || !mayHarvest(settler, target)
            || !settler.getMainHandItem().is(Items.BOW)) {
            settler.stopUsingItem();
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
        if (settler.blockPosition().closerThan(target.blockPosition(), HUNT_REACH)
            && settler.hasLineOfSight(target)) {
            mode = Mode.DRAWING;
            workTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_HUNT);
            settler.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > PATIENCE) {
                settler.recordRouteFailure("hunt_unreachable");
                done = true;
            } else {
                settler.setActivity(SettlerActivity.TRACKING_GAME);
                settler.getNavigation().moveTo(target, 1.0);
            }
        }
    }

    private void tickDraw() {
        if (!settler.getMainHandItem().is(Items.BOW)) {
            settler.stopUsingItem();
            done = true;
            return;
        }
        if (target == null || !mayHarvest(settler, target)
            || !settler.blockPosition().closerThan(target.blockPosition(), HUNT_REACH)
            || !settler.hasLineOfSight(target)) {
            settler.stopUsingItem();
            mode = Mode.TO_TARGET;
            settler.setActivity(SettlerActivity.TRACKING_GAME);
            return;
        }
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        workTicks++;
        if (workTicks == HUNT_RELEASE_TICK) {
            loose((ServerLevel) settler.level(), target);
            settler.stopUsingItem();
            mode = Mode.RECOVERY;
        }
        if (workTicks == HUNT_DRAW_CREAK_TICK) {
            // Sound only: the string creaks as HUNTER_DRAW starts the pull (pitch varies per shot).
            settler.level().playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.WORK_BOW_DRAW.get(), net.minecraft.sounds.SoundSource.NEUTRAL,
                0.4F, 0.95F + settler.getRandom().nextFloat() * 0.2F);
        }
    }

    private void tickRecovery() {
        // WORK_HUNT owns the full non-looping clip, even when contact kills
        // early. Physical collection starts only after recovery tick 24.
        if (workTicks + 1 == HUNT_ARROW_FETCH_TICK) {
            // Sound only: HUNTER_DRAW's right hand takes the next arrow from the belt (0.99 s).
            settler.level().playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.WORK_QUIVER_RUSTLE.get(), net.minecraft.sounds.SoundSource.NEUTRAL,
                0.3F, 1.05F + settler.getRandom().nextFloat() * 0.15F);
        }
        workTicks++;
        if (workTicks < HUNT_ANIMATION_TICKS) {
            return;
        }
        ServerLevel level = (ServerLevel) settler.level();
        loot.recoverOwned(level, huntBounds(lodge.anchor));
        if (loot.hasTrackedDrops()) {
            mode = Mode.TO_LOOT;
            beginLootRoute(level);
        } else if (bagCount() > 0 || settler.carriedArrowCount() <= 0) {
            mode = Mode.TO_LODGE;
            beginDeposit(level);
        } else if (!settler.getMainHandItem().is(Items.BOW)) {
            // The shot may have consumed the bow's last durability. Existing
            // drops and bag cargo were handled above; end new hunting now so
            // the ordinary equipment-request goal can obtain a replacement.
            done = true;
        } else if (target != null && target.isAlive()) {
            mode = Mode.TO_TARGET;
            settler.setActivity(SettlerActivity.TRACKING_GAME);
        } else {
            done = true;
        }
    }

    private boolean loose(ServerLevel level, Animal animal) {
        ItemStack bow = settler.getMainHandItem();
        if (!mayHarvest(settler, animal) || !bow.is(Items.BOW)) {
            return false;
        }
        java.util.UUID source = settler.carriedArrowSourceBuildingId();
        if (source == null || settler.takeCarriedArrows(1) != 1) {
            return false;
        }
        Arrow arrow = new Arrow(level, settler, new ItemStack(Items.ARROW), bow);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = animal.getX() - settler.getX();
        double dy = animal.getY(0.3333D) - arrow.getY();
        double dz = animal.getZ() - settler.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * 0.2D, dz, 1.8F, 4.0F);
        if (!HunterShotEvents.issue(arrow, settler, animal, lodge)
            || !level.addFreshEntity(arrow)) {
            settler.storeCarriedArrows(source, 1);
            return false;
        }
        settler.triggerBowLoose();
        level.playSound(null, settler.blockPosition(), com.hearthstead.registry.ModSounds.WORK_BOW_LOOSE.get(),
            SoundSource.NEUTRAL, 1.0F,
            1.0F / (settler.getRandom().nextFloat() * 0.4F + 0.8F));
        // Trade skill (secondary, Dexterity, level 5+): a clean loose now
        // and then spares the bow its wear. Never below level 5.
        if (!SkillLevels.rollSide(settler)) {
            bow.hurtAndBreak(1, settler, EquipmentSlot.MAINHAND);
        }
        settler.spendEffort(1);
        return true;
    }

    // --------------------------------------------------------------- loot ---

    private void beginLootRoute(ServerLevel level) {
        if (carryingCarcass()) {
            mode = Mode.HAUL;
            beginHaul(level);
            return;
        }
        if (loot.ownsOffhandItem()) {
            // A non-carcass item adopted after a reload goes into the bag.
            if (loot.stowOne() == GroundCollectionSession.StowResult.FULL) {
                loot.returnCarriedToWorld(level, settler.blockPosition(), false);
                mode = Mode.TO_LODGE;
                beginDeposit(level);
                return;
            }
        }
        loot.heartbeat(level);
        ItemEntity item = loot.nearestLoaded(level, settler.blockPosition());
        if (item == null) {
            if (bagCount() > 0) {
                mode = Mode.TO_LODGE;
                beginDeposit(level);
            } else {
                done = true;
            }
            return;
        }
        loot.select(item);
        settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
        settler.getNavigation().moveTo(item, 1.0D);
        repathTimer = 0;
        stuckChecks = 0;
    }

    private void tickLootRoute() {
        ServerLevel level = (ServerLevel) settler.level();
        loot.heartbeat(level);
        ItemEntity item = loot.selected(level);
        if (item == null) {
            beginLootRoute(level);
            return;
        }
        settler.getLookControl().setLookAt(item, 30.0F, 30.0F);
        if (!item.getUUID().equals(lootTargetId)) {
            lootTargetId = item.getUUID();
            lootTargetTicks = 0;
        }
        double distance = settler.distanceToSqr(item);
        boolean settled = settler.getNavigation().isDone()
            && distance <= PICKUP_SETTLED_DISTANCE_SQR;
        if ((distance <= PICKUP_CONTACT_DISTANCE_SQR || settled)
            && loot.hasClearPickupLine(level, item)) {
            pickupReachSqr = Math.max(PICKUP_CONTACT_DISTANCE_SQR, distance + 0.25D);
            mode = Mode.PICKING_LOOT;
            workTicks = 0;
            pickupTransferred = false;
            settler.getNavigation().stop();
            settler.triggerPickup();
        } else if (++lootTargetTicks > LOOT_TARGET_TIMEOUT) {
            giveUpOn(level, item);
        } else if (--repathTimer <= 0) {
            repathTimer = 10;
            if (++stuckChecks > PATIENCE) {
                giveUpOn(level, item);
            } else {
                settler.getNavigation().moveTo(item, 1.0D);
            }
        }
    }

    /** Sets one unreachable drop aside (still a real, unowned item) and moves on. */
    private void giveUpOn(ServerLevel level, ItemEntity item) {
        unreachableUntil.put(item.getUUID(), level.getGameTime() + UNREACHABLE_FORGET_TICKS);
        settler.recordRouteFailure("hunter_drop_unreachable");
        lootTargetId = null;
        loot.releaseSelected(level);
        beginLootRoute(level);
    }

    private boolean setAside(ServerLevel level, java.util.UUID id) {
        long now = level.getGameTime();
        unreachableUntil.values().removeIf(until -> until <= now);
        return unreachableUntil.containsKey(id);
    }

    private void tickLootPickup() {
        ServerLevel level = (ServerLevel) settler.level();
        workTicks++;
        if (!pickupTransferred && workTicks == PICKUP_CONTACT_TICK) {
            pickupTransferred = loot.takeOneToOffhand(level, pickupReachSqr)
                == GroundCollectionSession.PickupResult.PICKED;
        }
        if (workTicks >= PICKUP_DURATION_TICKS) {
            if (pickupTransferred && carryingCarcass()) {
                // The carcass stays on the shoulders: it never enters the bag.
                mode = Mode.HAUL;
                beginHaul(level);
                return;
            }
            if (pickupTransferred) {
                GroundCollectionSession.StowResult result = loot.stowOne();
                if (result == GroundCollectionSession.StowResult.FULL) {
                    loot.returnCarriedToWorld(level, settler.blockPosition(), false);
                    mode = Mode.TO_LODGE;
                    beginDeposit(level);
                    return;
                }
            }
            mode = Mode.TO_LOOT;
            beginLootRoute(level);
        }
    }

    // ------------------------------------------------------ haul & butcher ---

    /** Chooses the butcher spot: a free table, else the floor by a chest. */
    private void beginHaul(ServerLevel level) {
        stuckChecks = 0;
        repathTimer = 0;
        if (!refreshExactEmployer()) {
            done = true;
            return;
        }
        boolean carrying = carryingCarcass();
        if (table == null || !tableUsable(level, table, carrying)) {
            table = carrying ? findTable(level, false) : findTable(level, true);
        }
        if (table == null) {
            if (!carrying) {
                done = true;
                return;
            }
            chestTarget = pickChest(level, false);
        }
        settler.setActivity(carrying ? SettlerActivity.HAULING_CARCASS
            : SettlerActivity.TRAVELING);
        pathToButcherSpot(level, carrying);
    }

    private void pathToButcherSpot(ServerLevel level, boolean carrying) {
        double speed = carrying ? HAUL_SPEED : 1.0D;
        if (table != null) {
            settler.getNavigation().moveTo(table.getX() + 0.5D, table.getY(),
                table.getZ() + 0.5D, speed);
        } else if (chestTarget != null) {
            ContainerApproach.moveToContact(level, settler, chestTarget, speed);
        } else {
            settler.getNavigation().moveTo(lodge.anchor.getX() + 0.5,
                lodge.anchor.getY() + 1, lodge.anchor.getZ() + 0.5, speed);
        }
    }

    private boolean atButcherSpot(ServerLevel level) {
        if (table != null) {
            return settler.distanceToSqr(Vec3.atCenterOf(table)) <= TABLE_CONTACT_SQR
                && seesBlock(level, table);
        }
        if (chestTarget != null) {
            return ContainerApproach.inspect(level, settler, chestTarget).canInteract();
        }
        return settler.blockPosition().closerThan(lodge.anchor, ANCHOR_CONTACT);
    }

    private void tickHaul() {
        if (!refreshExactEmployer()) {
            done = true;
            return;
        }
        ServerLevel level = (ServerLevel) settler.level();
        boolean carrying = carryingCarcass();
        if (table != null && !tableUsable(level, table, carrying)) {
            // Broken, moved, or another Hunter's carcass got there first.
            table = null;
            beginHaul(level);
            return;
        }
        if (table == null && !carrying) {
            done = true;
            return;
        }
        settler.setActivity(carrying ? SettlerActivity.HAULING_CARCASS
            : SettlerActivity.TRAVELING);
        BlockPos look = table != null ? table : chestTarget != null ? chestTarget : lodge.anchor;
        settler.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
        if (atButcherSpot(level)) {
            settler.getNavigation().stop();
            if (table != null && carrying) {
                if (!(level.getBlockEntity(table) instanceof ButcheringTableBlockEntity bench)
                    || bench.hasCarcass()) {
                    table = null;
                    beginHaul(level);
                    return;
                }
                // Shoulders -> table in one tick: the table accepts first,
                // then the hand is cleared; never both, never neither.
                ItemStack carried = settler.getOffhandItem().copy();
                if (!bench.place(carried)) {
                    table = null;
                    beginHaul(level);
                    return;
                }
                loot.releaseCarriedForHandoff();
                settler.triggerPickup();
                level.playSound(null, table, SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL,
                    0.9F, 0.6F);
            }
            beginButcher(level, table != null);
        } else if (--repathTimer <= 0) {
            repathTimer = 20;
            if (++stuckChecks > HAUL_PATIENCE) {
                // Never delete: a carried carcass stays in the hand; stop()
                // sets it down as a real, leased item the Hunter retrieves.
                settler.recordRouteFailure("hunter_butcher_spot_unreachable");
                settler.setLogisticsStop(StopReason.NO_PATH, table != null ? table : lodge.plaquePos,
                    BLOCKED_RETRY_TICKS / 20);
                // Repeated failures back off (100, 200, ... 1600 ticks) so an
                // unreachable spot cannot become a drop/pick-up/haul loop.
                haulFailures = Math.min(haulFailures + 1, 5);
                retryAt = level.getGameTime() + ((long) BLOCKED_RETRY_TICKS << (haulFailures - 1));
                done = true;
                return;
            }
            pathToButcherSpot(level, carrying);
        }
    }

    private void beginButcher(ServerLevel level, boolean onTable) {
        CarcassData data = butcherSource(level, onTable);
        if (data == null) {
            done = true;
            return;
        }
        mode = Mode.BUTCHER;
        haulFailures = 0;
        butcherOnTable = onTable;
        workTicks = 0;
        int base = onTable ? HearthsteadServerConfig.butcherTableTicks()
            : HearthsteadServerConfig.fieldDressingTicks();
        int primary = SkillLevels.primaryOf(Profession.HUNTER).map(settler::attribute).orElse(0);
        butcherTotal = onTable
            ? HunterButchery.workTicks(base, SkillLevels.levelOf(settler), primary)
            : Math.max(HunterButchery.CLEAVE_PERIOD, base);
        skinTotal = HunterButchery.skinTicks(butcherTotal, HunterButchery.hasPelt(data));
    }

    @Nullable
    private CarcassData butcherSource(ServerLevel level, boolean onTable) {
        if (onTable) {
            return table != null && level.getBlockEntity(table) instanceof ButcheringTableBlockEntity bench
                ? CarcassItem.data(bench.carcass()) : null;
        }
        return carryingCarcass() ? CarcassItem.data(settler.getOffhandItem()) : null;
    }

    private void tickButcher() {
        if (!refreshExactEmployer()) {
            done = true;
            return;
        }
        ServerLevel level = (ServerLevel) settler.level();
        CarcassData data = butcherSource(level, butcherOnTable);
        if (data == null) {
            // A player took it off the table: nothing to commit, nothing lost.
            done = true;
            return;
        }
        if (!atButcherSpot(level)) {
            mode = Mode.HAUL;
            beginHaul(level);
            return;
        }
        BlockPos spot = butcherOnTable ? table : chestTarget != null ? chestTarget : settler.blockPosition();
        settler.getNavigation().stop();
        settler.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + (butcherOnTable ? 0.9 : 0.1),
            spot.getZ() + 0.5);
        workTicks++;
        boolean skinning = workTicks <= skinTotal;
        settler.setActivity(skinning ? SettlerActivity.WORK_SKIN : SettlerActivity.WORK_BUTCHER);
        int phaseTick = skinning ? workTicks : workTicks - skinTotal;
        if (HunterButchery.soundTick(phaseTick, skinning)) {
            WorkSoundSync.play(level, butcherOnTable ? table : settler.blockPosition(),
                skinning ? ModSounds.HIDE_SCRAPE.get() : ModSounds.CLEAVER_CHOP.get(), 0.6F, 1.0F);
        }
        if (workTicks >= butcherTotal) {
            commitButcher(level, data);
        }
    }

    /**
     * The one conversion point: the whole yield enters the bag (atomically,
     * or not at all) in the same tick the carcass leaves its single owner.
     */
    private void commitButcher(ServerLevel level, CarcassData data) {
        boolean bonus = butcherOnTable && SkillLevels.rollSide(settler);
        List<ItemStack> out = HunterButchery.yield(data, bonus);
        if (!WorkerStorageAuthority.storeAllInBag(settler.bag, out)) {
            // Bag too full to take the cuts: unload first, the carcass waits.
            settler.recordRouteFailure("hunter_bag_full_for_butchery");
            mode = Mode.TO_LODGE;
            beginDeposit(level);
            return;
        }
        ItemStack removed = butcherOnTable
            && level.getBlockEntity(table) instanceof ButcheringTableBlockEntity bench
            ? bench.take() : loot.releaseCarriedForHandoff();
        if (removed.isEmpty()) {
            // Unreachable in one server tick; fail loudly rather than mint.
            com.hearthstead.Hearthstead.LOGGER.error(
                "Hunter {} butchery source vanished at commit; reverting bag", settler.getUUID());
            removeFromBag(out);
            done = true;
            return;
        }
        settler.train(Employment.trainedBy(BuildingType.HUNTERS_LODGE), 1.0F);
        SkillLevels.completeUnit(settler, 1, Employment.trainedBy(BuildingType.HUNTERS_LODGE));
        settler.spendEffort(1);
        level.playSound(null, butcherOnTable ? table : settler.blockPosition(),
            SoundEvents.WOOL_BREAK, SoundSource.NEUTRAL, 0.7F, 0.8F);
        mode = Mode.TO_LODGE;
        beginDeposit(level);
    }

    private void removeFromBag(List<ItemStack> added) {
        for (ItemStack unit : added) {
            int left = unit.getCount();
            for (int i = 0; i < settler.bag.getContainerSize() && left > 0; i++) {
                ItemStack live = settler.bag.getItem(i);
                if (ItemStack.isSameItemSameComponents(live, unit)) {
                    int take = Math.min(left, live.getCount());
                    live.shrink(take);
                    left -= take;
                }
            }
        }
    }

    // ---------------------------------------------------------- chest trips ---

    private void tickFetch() {
        if (!refreshExactEmployer()) {
            done = true;
            return;
        }
        ServerLevel level = (ServerLevel) settler.level();
        if (chestTarget == null || !lodge.contains(chestTarget)) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(chestTarget.getX() + 0.5, chestTarget.getY() + 0.6,
            chestTarget.getZ() + 0.5);
        ContainerApproach.Result contact = ContainerApproach.inspect(level, settler, chestTarget);
        if (contact.canInteract()) {
            settler.getNavigation().stop();
            if (level.getBlockEntity(chestTarget) instanceof Container chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack there = chest.getItem(slot);
                    if (CarcassItem.isCarcass(there) && loot.acceptHandoff(there.copyWithCount(1))) {
                        chest.removeItem(slot, 1);
                        chest.setChanged();
                        level.blockEvent(chestTarget, level.getBlockState(chestTarget).getBlock(), 1, 1);
                        settler.triggerPickup();
                        break;
                    }
                }
            }
            if (carryingCarcass()) {
                mode = Mode.HAUL;
                beginHaul(level);
            } else {
                done = true;
            }
        } else if (--repathTimer <= 0) {
            repathTimer = 20;
            if (contact.state() == ContainerApproach.State.INVALID_TARGET || ++stuckChecks > HAUL_PATIENCE) {
                settler.recordRouteFailure("hunter_carcass_chest_unreachable");
                retryAt = level.getGameTime() + BLOCKED_RETRY_TICKS;
                done = true;
                return;
            }
            ContainerApproach.moveToContact(level, settler, chestTarget, 1.0D);
        }
    }

    private void beginDeposit(ServerLevel level) {
        settler.setActivity(SettlerActivity.TRAVELING);
        stuckChecks = 0;
        repathTimer = 0;
        arrowsHandled = false;
        if (!refreshExactEmployer()) {
            done = true;
            return;
        }
        if (chestTarget == null || !lodge.contains(chestTarget)
            || !(level.getBlockEntity(chestTarget) instanceof Container)) {
            chestTarget = pickChest(level, bagCount() > 0);
        }
        if (chestTarget != null) {
            ContainerApproach.moveToContact(level, settler, chestTarget, 1.0D);
        }
    }

    private void tickDeposit() {
        // Re-resolve the roster authority on the exact contact path. A cached
        // old Lodge may still be loaded and contain valid chests after a
        // reassignment; neither cargo nor ammunition may cross that boundary.
        if (!refreshExactEmployer()) {
            releaseUnload();
            done = true;
            return;
        }
        ServerLevel level = (ServerLevel) settler.level();
        if (chestTarget == null || !lodge.contains(chestTarget)
            || !(level.getBlockEntity(chestTarget) instanceof Container)) {
            releaseUnload();
            chestTarget = pickChest(level, bagCount() > 0);
            if (chestTarget == null) {
                settler.recordRouteFailure("hunter_lodge_storage_missing");
                settler.setLogisticsStop(StopReason.CHEST_FULL, lodge.plaquePos,
                    BLOCKED_RETRY_TICKS / 20);
                retryAt = level.getGameTime() + BLOCKED_RETRY_TICKS;
                done = true;
                return;
            }
        }
        settler.getLookControl().setLookAt(chestTarget.getX() + 0.5, chestTarget.getY() + 0.6,
            chestTarget.getZ() + 0.5);
        ContainerApproach.Result contact = ContainerApproach.inspect(level, settler, chestTarget);
        if (contact.canInteract()) {
            settler.getNavigation().stop();
            if (!arrowsHandled) {
                arrowsHandled = true;
                List<Container> containers = lodgeContainers(level);
                if (settler.carriedArrowCount() == 0
                    || settler.carriedArrowsOwnedBy(lodge.id)
                    || settler.releaseCarriedArrows(level, settler.settlement())) {
                    restockArrows(containers);
                }
            }
            if (bagCount() == 0 && !settler.bagTransferPresentation().active()) {
                finishDeposit(level);
                return;
            }
            Container chest = (Container) level.getBlockEntity(chestTarget);
            GroundedBagUnload.Result result = chestBag.tick(level, settler, chestTarget,
                stack -> !stack.isEmpty(), unit -> insert(chest, unit).isEmpty());
            if (result == GroundedBagUnload.Result.WAITING) {
                return;
            }
            if (result == GroundedBagUnload.Result.BLOCKED) {
                releaseUnload();
                BlockPos other = pickChest(level, true);
                if (other != null && !other.equals(chestTarget)) {
                    chestTarget = other;
                    ContainerApproach.moveToContact(level, settler, chestTarget, 1.0D);
                    return;
                }
                // A full Lodge: the cargo stays safely bagged; say why.
                settler.recordRouteFailure("hunter_lodge_chest_full");
                settler.setLogisticsStop(StopReason.CHEST_FULL, chestTarget,
                    BLOCKED_RETRY_TICKS / 20);
                retryAt = level.getGameTime() + BLOCKED_RETRY_TICKS;
                done = true;
                return;
            }
            finishDeposit(level);
        } else if (--repathTimer <= 0) {
            repathTimer = 20;
            if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
                chestTarget = null;
                return;
            }
            if (++stuckChecks > HAUL_PATIENCE) {
                settler.recordRouteFailure("hunter_lodge_chest_unreachable");
                settler.setLogisticsStop(StopReason.NO_PATH, chestTarget,
                    BLOCKED_RETRY_TICKS / 20);
                retryAt = level.getGameTime() + BLOCKED_RETRY_TICKS;
                done = true;
                return;
            }
            ContainerApproach.moveToContact(level, settler, chestTarget, 1.0D);
        }
    }

    private void finishDeposit(ServerLevel level) {
        if (carryingCarcass()) {
            // The bag was emptied to make room for this carcass's cuts:
            // go straight back to butchering instead of setting it down.
            mode = Mode.HAUL;
            beginHaul(level);
            return;
        }
        List<Container> containers = lodgeContainers(level);
        lodgeArrowStock = settler.carriedArrowCount() > 0 || containsArrows(containers);
        if (settler.carriedArrowCount() > 0) {
            clearAmmoStop();
        } else {
            nextAmmoStockCheck = level.getGameTime() + AMMO_STOCK_RETRY_TICKS;
            publishAmmoWait(level);
        }
        if (settler.logisticsStopReason() == StopReason.CHEST_FULL
            || settler.logisticsStopReason() == StopReason.NO_PATH) {
            settler.clearLogisticsStop();
        }
        done = true;
    }

    // ---------------------------------------------------------------- forage ---

    private void tickForage() {
        if (mushroomTarget == null || !isForageable(mushroomTarget)) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(mushroomTarget.getX() + 0.5,
            mushroomTarget.getY() + 0.3, mushroomTarget.getZ() + 0.5);
        if (!settler.blockPosition().closerThan(mushroomTarget, FORAGE_REACH)) {
            if (--repathTimer <= 0) {
                repathTimer = REPATH_INTERVAL;
                if (++stuckChecks > PATIENCE) {
                    done = true;
                    return;
                }
                settler.getNavigation().moveTo(mushroomTarget.getX() + 0.5, mushroomTarget.getY(),
                    mushroomTarget.getZ() + 0.5, 0.9);
            }
            return;
        }
        if (settler.level() instanceof ServerLevel level) {
            BlockState state = level.getBlockState(mushroomTarget);
            if (state.is(Blocks.BROWN_MUSHROOM)) {
                List<ItemStack> drops = Block.getDrops(state, level, mushroomTarget, null);
                level.destroyBlock(mushroomTarget, false);
                for (ItemStack drop : drops) {
                    ItemStack leftover = settler.bag.addItem(drop);
                    if (!leftover.isEmpty()) {
                        com.hearthstead.util.ItemSpill.conserve(level, mushroomTarget, leftover);
                    }
                }
                settler.triggerPickup();
                settler.train(Employment.trainedBy(BuildingType.HUNTERS_LODGE), 1.0F);
                SkillLevels.completeUnit(settler, 1,
                    Employment.trainedBy(BuildingType.HUNTERS_LODGE));
            }
        }
        // Carry a single forage home. A reconstructed goal likewise treats
        // any non-empty bag as existing cargo before deciding new work.
        if (bagCount() > 0) {
            mode = Mode.TO_LODGE;
            beginDeposit((ServerLevel) settler.level());
        } else {
            done = true;
        }
    }

    @Override
    public void stop() {
        settler.stopUsingItem();
        settler.getNavigation().stop();
        releaseUnload();
        if (settler.level() instanceof ServerLevel level) {
            // J-03: the Hunter's claimed game must outlast the night.
            loot.persistOwnedDrops(level);
            if (workConditions()) {
                loot.suspend(level);
            } else {
                loot.abandon(level);
            }
        }
        settler.setActivity(SettlerActivity.IDLE);
        target = null;
        mushroomTarget = null;
    }

    // ------------------------------------------------------------ helpers ---

    /** Visual claim only: an uncommitted unit is still bagged; a committed one left once. */
    private void releaseUnload() {
        var view = settler.bagTransferPresentation();
        if (view.active() && !view.sourcePickup()) {
            GroundedBagUnload.clearPresentation(settler, view);
        }
    }

    /**
     * Rebinds the cached object only when the persisted building identity is
     * still this Hunter's one valid Lodge employer. Employment.employerOf
     * already fails closed for duplicate roster/identity authority.
     */
    private boolean refreshExactEmployer() {
        Settlement settlement = settler.settlement();
        Building current = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (lodge == null || current == null || current.id == null
            || !current.id.equals(lodge.id) || !current.valid
            || current.anchor == null
            || current.type != BuildingType.HUNTERS_LODGE) {
            return false;
        }
        lodge = current;
        return true;
    }

    /** Wild game only — the classic overworld passives whose real loot
     *  tables already back Ring-2 (BUTCHER's meat, TANNERY's rabbit hide,
     *  FLETCHER's future feather input, and sheared-in-the-wild wool). */
    public static boolean isWildGame(Animal a) {
        return a instanceof Cow || a instanceof Pig || a instanceof Sheep
            || a instanceof Chicken || a instanceof Rabbit;
    }

    /** The same five species, by type (the hunting-grounds spawner's set). */
    public static boolean isWildGameType(EntityType<?> type) {
        return type == EntityType.COW || type == EntityType.PIG || type == EntityType.SHEEP
            || type == EntityType.CHICKEN || type == EntityType.RABBIT;
    }

    public static boolean insideAnyBuilding(Settlement s, BlockPos pos) {
        for (Building b : s.buildings) {
            if (b.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Live wild game per species inside a Lodge's hunting box, outside every
     * building -- the exact population the floor reads (babies included).
     */
    public static Map<EntityType<?>, Integer> wildGameCounts(ServerLevel level, Settlement s,
                                                             BlockPos anchor) {
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Animal a : level.getEntitiesOfClass(Animal.class, huntBounds(anchor),
                a -> a.isAlive() && isWildGame(a) && !insideAnyBuilding(s, a.blockPosition()))) {
            counts.merge(a.getType(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The nearest wild animal this hunter is actually allowed to take, or
     * null. Every guard here is re-evaluated from the live world on every
     * call — see the class doc's "none cached" promise.
     */
    private Animal findHuntable(ServerLevel level, Settlement s, BlockPos anchor) {
        AABB box = huntBounds(anchor, settler);
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, box,
            a -> a.isAlive() && isWildGame(a) && !insideAnyBuilding(s, a.blockPosition()));
        if (animals.isEmpty()) {
            return null;
        }
        Map<Class<?>, Integer> counts = new HashMap<>();
        for (Animal a : animals) {
            counts.merge(a.getClass(), 1, Integer::sum);
        }
        Animal best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Animal a : animals) {
            if (a.isBaby() || counts.getOrDefault(a.getClass(), 0) <= MIN_SPECIES_POPULATION) {
                continue; // the population floor: leave this species alone
            }
            double d = a.distanceToSqr(settler);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                best = a;
            }
        }
        return best;
    }

    /** Revalidation shared by release and the pre-damage event. */
    public static boolean mayHarvest(SettlerEntity hunter, Animal candidate) {
        if (hunter == null || candidate == null || !candidate.isAlive()
            || hunter.getProfession() != Profession.HUNTER
            || !(hunter.level() instanceof ServerLevel level)
            || candidate.level() != level || !isWildGame(candidate)
            || candidate.isBaby()) {
            return false;
        }
        Settlement settlement = hunter.settlement();
        Building lodge = settlement == null ? null
            : Employment.employerOf(settlement, hunter.getUUID());
        if (lodge == null || lodge.type != BuildingType.HUNTERS_LODGE
            || !lodge.valid || lodge.anchor == null
            || !huntBounds(lodge.anchor, hunter).contains(candidate.position())
            || insideAnyBuilding(settlement, candidate.blockPosition())) {
            return false;
        }
        int liveSpecies = level.getEntitiesOfClass(Animal.class,
            huntBounds(lodge.anchor, hunter), animal -> animal.isAlive()
                && animal.getClass() == candidate.getClass()
                && isWildGame(animal)
                && !insideAnyBuilding(settlement, animal.blockPosition())).size();
        return liveSpecies > MIN_SPECIES_POPULATION;
    }

    public static AABB huntBounds(BlockPos anchor) {
        return new AABB(anchor).inflate(HUNT_RADIUS, VERTICAL_BAND, HUNT_RADIUS);
    }

    /**
     * This hunter's search box: Perception widens the radius by 0..25%
     * (AttributeRuntime.range, plan/ATTRIBUTES.md). Finding and the harvest
     * revalidation both use it, so a far find is never rejected.
     */
    public static AABB huntBounds(BlockPos anchor, SettlerEntity hunter) {
        double radius = com.hearthstead.entity.AttributeRuntime.range(hunter, HUNT_RADIUS);
        return new AABB(anchor).inflate(radius, VERTICAL_BAND, radius);
    }

    /**
     * Loose carcasses in the hunting grounds that nobody else holds a live
     * lease on: re-leased to this Hunter so the ordinary pickup route
     * collects them. Items inside other buildings (a player's storeroom
     * floor) are left alone.
     */
    private boolean claimLooseCarcass(ServerLevel level, Settlement s) {
        List<ItemEntity> loose = level.getEntitiesOfClass(ItemEntity.class,
            huntBounds(lodge.anchor), item -> item.isAlive()
                && CarcassItem.isCarcass(item.getItem())
                && !insideForeignBuilding(s, item.blockPosition()));
        if (loose.isEmpty()) {
            return false;
        }
        loose.sort(Comparator.comparingDouble(settler::distanceToSqr));
        for (ItemEntity item : loose) {
            if (setAside(level, item.getUUID())) {
                continue;
            }
            if (GroundCollectionSession.leaseExisting(settler, item)) {
                loot.recoverOwned(level, item.getBoundingBox().inflate(1.0D));
                if (loot.hasTrackedDrops()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean insideForeignBuilding(Settlement s, BlockPos pos) {
        for (Building b : s.buildings) {
            if (b != lodge && b.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A Butchering Table inside this Lodge's bounds (loaded chunks only):
     * with a carcass waiting on it ({@code loaded}) or free to take one.
     */
    @Nullable
    private BlockPos findTable(ServerLevel level, boolean loaded) {
        BoundingBox b = lodge == null ? null : lodge.bounds;
        if (b == null || (long) b.getXSpan() * b.getYSpan() * b.getZSpan() > 32768L) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)
                        || !(level.getBlockEntity(cursor) instanceof ButcheringTableBlockEntity bench)
                        || bench.hasCarcass() != loaded) {
                        continue;
                    }
                    double d = settler.distanceToSqr(Vec3.atCenterOf(cursor));
                    if (d < bestDist) {
                        bestDist = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean tableUsable(ServerLevel level, BlockPos pos, boolean carrying) {
        return lodge.contains(pos) && level.hasChunkAt(pos)
            && level.getBlockEntity(pos) instanceof ButcheringTableBlockEntity bench
            && bench.hasCarcass() != carrying;
    }

    private boolean seesBlock(ServerLevel level, BlockPos pos) {
        Vec3 eye = settler.getEyePosition();
        Vec3 top = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D);
        BlockHitResult hit = level.clip(new ClipContext(eye, top, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS || pos.equals(hit.getBlockPos());
    }

    private List<Container> lodgeContainers(ServerLevel level) {
        List<Container> containers = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, lodge)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                containers.add(chest);
            }
        }
        return containers;
    }

    /**
     * The Lodge chest to walk to: with {@code needRoom}, the nearest one with
     * room for the next bag unit; otherwise the nearest one holding arrows,
     * else the nearest one at all.
     */
    @Nullable
    private BlockPos pickChest(ServerLevel level, boolean needRoom) {
        ItemStack next = ItemStack.EMPTY;
        for (int i = 0; i < settler.bag.getContainerSize() && next.isEmpty(); i++) {
            next = settler.bag.getItem(i);
        }
        BlockPos best = null;
        BlockPos arrowChest = null;
        double bestDist = Double.MAX_VALUE;
        double arrowDist = Double.MAX_VALUE;
        for (BlockPos pos : WarehouseIndex.containers(level, lodge)) {
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            if (needRoom && !next.isEmpty() && !hasRoom(chest, next)) {
                continue;
            }
            double d = settler.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
            if (!needRoom && d < arrowDist && containsArrows(List.of(chest))) {
                arrowDist = d;
                arrowChest = pos;
            }
        }
        return arrowChest != null && settler.carriedArrowCount() == 0 ? arrowChest : best;
    }

    @Nullable
    private BlockPos chestHoldingCarcass(ServerLevel level) {
        if (!settler.getOffhandItem().isEmpty()) {
            return null;
        }
        for (BlockPos pos : WarehouseIndex.containers(level, lodge)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (CarcassItem.isCarcass(chest.getItem(slot))) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasRoom(Container chest, ItemStack unit) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack there = chest.getItem(slot);
            if (there.isEmpty() && chest.canPlaceItem(slot, unit)) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(there, unit)
                && there.getCount() < Math.min(there.getMaxStackSize(), chest.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private void restockArrows(List<Container> containers) {
        int needed = SettlerEntity.ARCHER_QUIVER_CAPACITY
            - settler.carriedArrowCount();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize()
                    && needed > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.is(Items.ARROW)) {
                    continue;
                }
                int accepted = settler.storeCarriedArrows(lodge.id,
                    Math.min(needed, stack.getCount()));
                if (accepted > 0) {
                    stack.shrink(accepted);
                    container.setChanged();
                    needed -= accepted;
                }
            }
        }
    }

    private boolean refreshLodgeArrowStock(ServerLevel level) {
        long now = level.getGameTime();
        if (now < nextAmmoStockCheck) {
            return lodgeArrowStock;
        }
        lodgeArrowStock = containsArrows(lodgeContainers(level));
        nextAmmoStockCheck = now + AMMO_STOCK_RETRY_TICKS;
        return lodgeArrowStock;
    }

    private static boolean containsArrows(List<Container> containers) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.ARROW)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void publishAmmoWait(ServerLevel level) {
        int retry = (int) Math.max(0L,
            Math.min(Integer.MAX_VALUE, nextAmmoStockCheck - level.getGameTime()));
        settler.setLogisticsStop(StopReason.WAITING_INPUT,
            lodge == null ? null : lodge.plaquePos, retry);
    }

    private void clearAmmoStop() {
        if (settler.logisticsStopReason() == StopReason.WAITING_INPUT) {
            settler.clearLogisticsStop();
        }
    }

    private boolean isForageable(BlockPos pos) {
        return settler.level().getBlockState(pos).is(Blocks.BROWN_MUSHROOM);
    }

    /** Fisher-style exact insert honouring canPlaceItem and stack limits. */
    private static ItemStack insert(Container into, ItemStack source) {
        ItemStack left = source.copy();
        for (int slot = 0; slot < into.getContainerSize() && !left.isEmpty(); slot++) {
            if (!into.canPlaceItem(slot, left)) continue;
            ItemStack there = into.getItem(slot);
            int capacity = Math.min(into.getMaxStackSize(), left.getMaxStackSize());
            if (!there.isEmpty() && !ItemStack.isSameItemSameComponents(there, left)) continue;
            int amount = Math.min(left.getCount(), capacity - there.getCount());
            if (amount <= 0) continue;
            if (there.isEmpty()) into.setItem(slot, left.copyWithCount(amount));
            else { there.grow(amount); into.setChanged(); }
            left.shrink(amount);
        }
        return left;
    }
}
