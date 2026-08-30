package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Physical-authority and negative-path contract shared by field gatherers. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GroundCollectionSessionGameTests {

    private static SettlerEntity worker(GameTestHelper helper) {
        return worker(helper, new BlockPos(4, 1, 4));
    }

    private static SettlerEntity worker(GameTestHelper helper, BlockPos relativePos) {
        // GameTest templates can be placed into generated stone at this depth.
        // Make the small contact corridor authoritative so a positive pickup
        // test never depends on whatever terrain happened to exist here. The
        // barrier tests add their own walls after this fixture is prepared.
        for (int x = relativePos.getX() - 2; x <= relativePos.getX() + 4; x++) {
            for (int y = 1; y <= 4; y++) {
                for (int z = relativePos.getZ() - 1;
                     z <= relativePos.getZ() + 1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        helper.setBlock(relativePos.below(), Blocks.STONE);
        return helper.spawn(ModEntities.SETTLER.get(), relativePos);
    }

    private static int worldLogs(ServerLevel level, BlockPos around) {
        int total = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
            new AABB(around).inflate(8.0))) {
            if (item.isAlive() && item.getItem().is(ItemTags.LOGS)) {
                total += item.getItem().getCount();
            }
        }
        return total;
    }

    private static int physicalLogEntities(ServerLevel level, BlockPos around) {
        return level.getEntitiesOfClass(ItemEntity.class,
            new AABB(around).inflate(8.0), item -> item.isAlive()
                && item.getItem().is(ItemTags.LOGS)).size();
    }

    private static boolean hasUnblockedRay(ServerLevel level,
                                           SettlerEntity worker,
                                           ItemEntity item) {
        return level.clip(new ClipContext(worker.getEyePosition(), item.position(),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker))
            .getType() == HitResult.Type.MISS;
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public void exactOneMovesWorldToOffhandToPersistentContainer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));

        // Source-transfer ordering regression: escrow ownership must exist
        // before a lumber log is removed, and a failed source mutation must
        // be able to cancel that still-unmaterialized row without minting an
        // item. This extends an existing native test so the frozen roster
        // count remains stable.
        int pendingBefore = DeferredItemMaterializationSavedData.get(level)
            .pendingRows();
        UUID staged = session.queuePhysical(level, source,
            new ItemStack(Items.BIRCH_LOG));
        helper.assertTrue(staged != null
                && DeferredItemMaterializationSavedData.get(level)
                    .pendingRows() == pendingBefore + 1
                && level.getEntity(staged) == null,
            "phase one must durably queue exactly one log without spawning it");
        helper.assertTrue(session.cancelQueued(level, staged)
                && DeferredItemMaterializationSavedData.get(level)
                    .pendingRows() == pendingBefore
                && level.getEntity(staged) == null,
            "a source-clear failure must roll back the queued copy exactly");

        helper.assertTrue(session.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "the produced log must become a real item entity");
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        helper.assertTrue(physical != null && worldLogs(level, source) == 1,
            "one physical world log must exist before pickup contact");
        session.select(physical);
        GroundCollectionSession.PickupResult pickup =
            session.takeOneToOffhand(level, 4.0);
        helper.assertTrue(pickup == GroundCollectionSession.PickupResult.PICKED,
            "contact must transfer exactly one selected item into offhand; got "
                + pickup);
        helper.assertTrue(worldLogs(level, source) == 0
                && worker.getOffhandItem().is(Items.OAK_LOG)
                && session.load() == 0,
            "pickup may not skip directly into the persistent container");
        helper.assertTrue(session.stowOne()
                == GroundCollectionSession.StowResult.STOWED,
            "container contact must accept the carried log");
        helper.assertTrue(worker.getOffhandItem().isEmpty()
                && session.load() == 1,
            "the same one item must finish in the persistent container");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public void interruptionRematerialisesTheOffhandItemWithoutLossOrDupe(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        session.spawnPhysical(level, source, new ItemStack(Items.SPRUCE_LOG));
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        helper.assertTrue(physical != null, "setup must expose the real log");
        session.select(physical);
        GroundCollectionSession.PickupResult pickup =
            session.takeOneToOffhand(level, 4.0);
        helper.assertTrue(pickup == GroundCollectionSession.PickupResult.PICKED,
            "setup pickup must reach the worker's offhand; got " + pickup);

        session.suspend(level);

        helper.assertTrue(worker.getOffhandItem().isEmpty(),
            "an interrupted worker may not keep hidden transient cargo");
        helper.assertTrue(session.load() == 0 && worldLogs(level, source) == 1,
            "interruption must leave exactly one real log, never zero or two");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public void fullContainerKeepsCarriedItemPhysicalAndTrackerOverflowNeverDeletes(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        worker.setCarryCapacity(1);
        worker.bag.addItem(new ItemStack(Items.COBBLESTONE));
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 2);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        session.spawnPhysical(level, source, new ItemStack(Items.OAK_LOG));
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        helper.assertTrue(physical != null, "setup must expose the real log");
        session.select(physical);
        session.takeOneToOffhand(level, 4.0);

        helper.assertTrue(session.stowOne() == GroundCollectionSession.StowResult.FULL,
            "capacity must reject stow before mutating either inventory");
        helper.assertTrue(worker.getOffhandItem().is(Items.OAK_LOG)
                && session.load() == 1,
            "full-container rejection must preserve both physical owners");
        session.suspend(level);
        session.spawnPhysical(level, source, new ItemStack(Items.BIRCH_LOG));
        session.spawnPhysical(level, source, new ItemStack(Items.JUNGLE_LOG));
        session.spawnPhysical(level, source, new ItemStack(Items.ACACIA_LOG));

        helper.assertTrue(session.trackedCount() == 2,
            "the ownership ledger must stay at its explicit hard cap");
        helper.assertTrue(worldLogs(level, source) == 4,
            "overflow must remain real world output rather than disappearing");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public void physicalOwnerCanRebuildTransientIndexAfterSessionReload(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity owner = worker(helper);
        GroundCollectionSession beforeReload = new GroundCollectionSession(owner,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(beforeReload.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "setup must create one real, persistently-owned item entity");
        beforeReload.forgetUnresolved();

        GroundCollectionSession afterReload = new GroundCollectionSession(owner,
            stack -> stack.is(ItemTags.LOGS), 4);
        helper.assertTrue(afterReload.recoverOwned(level,
                new AABB(source).inflate(4.0)) == 1,
            "a new server session must rebuild its transient index from the real item");
        ItemEntity recovered = afterReload.nearestLoaded(level, owner.blockPosition());
        helper.assertTrue(recovered != null,
            "the recovered row must still resolve to the same physical entity");
        afterReload.select(recovered);
        GroundCollectionSession.PickupResult pickup =
            afterReload.takeOneToOffhand(level, 4.0);
        helper.assertTrue(pickup == GroundCollectionSession.PickupResult.PICKED,
            "recovery must preserve the ordinary exact-one pickup transaction; got "
                + pickup);
        helper.assertTrue(afterReload.stowOne()
                == GroundCollectionSession.StowResult.STOWED
                && afterReload.load() == 1 && worldLogs(level, source) == 0,
            "reload recovery must end with exactly one item, never a copy");
        helper.succeed();
    }

    /**
     * A distance-only transaction must not let a worker reach through a
     * solid barrier. The item is deliberately inside the lumberer's outer
     * 2.5-block safety cap, so the wall/line-of-sight invariant -- not a
     * conveniently large separation -- is what must reject the contact.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "ground_collection_contact_safety")
    public void solidWallWithinOuterCapCannotTransferPhysicalLog(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(6, 1, 4));
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(5, 2, 4), Blocks.STONE_BRICKS);
        helper.assertTrue(session.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "fixture must create one physical log behind the wall");
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        helper.assertTrue(physical != null,
            "fixture must resolve the owned physical log");
        physical.setDeltaMovement(Vec3.ZERO);
        session.select(physical);
        helper.assertTrue(worker.distanceToSqr(physical) <= 6.25,
            "fixture log must remain within the outer contact cap");
        helper.assertTrue(!hasUnblockedRay(level, worker, physical),
            "fixture wall must actually occlude the physical log");

        GroundCollectionSession.PickupResult result =
            session.takeOneToOffhand(level, 6.25);
        helper.assertTrue(result != GroundCollectionSession.PickupResult.PICKED,
            "solid-wall/partial-path contact may not transfer a log merely "
                + "because origin distance is inside 2.5 blocks (result="
                + result + ")");
        helper.assertTrue(worker.getOffhandItem().isEmpty()
                && worldLogs(level, source) == 1,
            "rejected wall contact must conserve the one physical log");
        helper.succeed();
    }

    /**
     * Models the authored pickup wind-up: the target is valid when selected,
     * but leaves range before contact tick 12. The contact transaction must
     * revalidate the real entity and leave it physical.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "ground_collection_contact_safety")
    public void targetMovedOutOfReachDuringPickupWindupAbortsAtContact(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(session.spawnPhysical(level, source,
                new ItemStack(Items.SPRUCE_LOG)),
            "fixture must create the selected physical log");
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        helper.assertTrue(physical != null, "fixture must resolve the selected log");
        physical.setDeltaMovement(Vec3.ZERO);
        session.select(physical);

        helper.runAfterDelay(4, () -> {
            physical.setPos(source.getX() + 8.5, source.getY() + 0.25,
                source.getZ() + 0.5);
            physical.setDeltaMovement(Vec3.ZERO);
        });
        helper.runAfterDelay(11, () -> helper.assertTrue(
            worker.getOffhandItem().isEmpty() && worldLogs(level, source) == 1,
            "no world-to-offhand transfer may occur before contact tick 12"));
        helper.runAfterDelay(12, () -> {
            GroundCollectionSession.PickupResult result =
                session.takeOneToOffhand(level, 6.25);
            helper.assertTrue(result == GroundCollectionSession.PickupResult.TOO_FAR,
                "a target moved during wind-up must be rejected at contact, got "
                    + result);
            helper.assertTrue(worker.getOffhandItem().isEmpty()
                    && worldLogs(level, source) == 1,
                "aborted moving-target contact must conserve exactly one log");
            helper.succeed();
        });
    }

    /** Same contact-frame race as above, but the world inserts an occluder. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "ground_collection_contact_safety")
    public void lineOfSightBlockedDuringPickupWindupAbortsAtContact(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        // Keep the selected item suspended at chest height during the
        // authored wind-up. A ground-level endpoint can make the fixture's
        // own ray terminate in the template floor, which would mean the
        // target was already occluded before the wall is introduced.
        BlockPos source = helper.absolutePos(new BlockPos(6, 2, 4));
        helper.assertTrue(session.spawnPhysical(level, source,
                new ItemStack(Items.BIRCH_LOG)),
            "fixture must create the selected physical log");
        ItemEntity physical = session.nearestLoaded(level, worker.blockPosition());
        if (physical != null) {
            physical.setNoGravity(true);
            physical.setDeltaMovement(Vec3.ZERO);
        }
        helper.assertTrue(physical != null
                && hasUnblockedRay(level, worker, physical),
            "the pickup wind-up must begin with a visible real target");
        session.select(physical);

        helper.runAfterDelay(4, () -> {
            helper.setBlock(new BlockPos(5, 1, 4), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(5, 2, 4), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(5, 3, 4), Blocks.STONE_BRICKS);
        });
        helper.runAfterDelay(11, () -> helper.assertTrue(
            worker.getOffhandItem().isEmpty() && worldLogs(level, source) == 1,
            "the occluded log must remain in the world before contact tick 12"));
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(!hasUnblockedRay(level, worker, physical),
                "fixture wall must block line of sight by the contact frame");
            GroundCollectionSession.PickupResult result =
                session.takeOneToOffhand(level, 6.25);
            helper.assertTrue(result != GroundCollectionSession.PickupResult.PICKED,
                "contact must revalidate line of sight after wind-up (result="
                    + result + ")");
            helper.assertTrue(worker.getOffhandItem().isEmpty()
                    && worldLogs(level, source) == 1,
                "occlusion abort must conserve exactly one physical log");
            helper.succeed();
        });
    }

    /**
     * Vanilla merging must not collapse two workers' ownership domains into
     * one ItemEntity. This is intentionally fail-first while persistent
     * entity owner tags are ignored by ItemEntity's merge predicate.
     */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "ground_collection_merge_ownership")
    public void adjacentOwnedLogsFromDifferentSessionsKeepDistinctOwners(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity workerA = worker(helper, new BlockPos(3, 1, 4));
        SettlerEntity workerB = worker(helper, new BlockPos(7, 1, 4));
        GroundCollectionSession sessionA = new GroundCollectionSession(workerA,
            stack -> stack.is(ItemTags.LOGS), 4);
        GroundCollectionSession sessionB = new GroundCollectionSession(workerB,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(sessionA.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG))
                && sessionB.spawnPhysical(level, source,
                    new ItemStack(Items.OAK_LOG)),
            "fixture must create one equally placed owned log per worker");
        ItemEntity ownedA = sessionA.nearestLoaded(level, source);
        ItemEntity ownedB = sessionB.nearestLoaded(level, source);
        helper.assertTrue(ownedA != null && ownedB != null
                && !ownedA.getUUID().equals(ownedB.getUUID()),
            "fixture must begin with two distinct physical owner entities");
        ownedA.setDeltaMovement(Vec3.ZERO);
        ownedB.setDeltaMovement(Vec3.ZERO);

        // Vanilla only performs neighbour merging periodically. Re-pin both
        // entities immediately before that cadence so random spawn velocity
        // cannot turn this ownership contract into a probabilistic test.
        helper.runAtTickTime(39, () -> {
            ownedA.setPos(source.getX() + 0.5, source.getY() + 0.25,
                source.getZ() + 0.5);
            ownedB.setPos(source.getX() + 0.5, source.getY() + 0.25,
                source.getZ() + 0.5);
            ownedA.setDeltaMovement(Vec3.ZERO);
            ownedB.setDeltaMovement(Vec3.ZERO);
        });

        helper.runAfterDelay(60, () -> {
            int total = worldLogs(level, source);
            int entities = physicalLogEntities(level, source);
            sessionA.forgetUnresolved();
            sessionB.forgetUnresolved();
            int recoveredA = sessionA.recoverOwned(level,
                new AABB(source).inflate(4.0));
            int recoveredB = sessionB.recoverOwned(level,
                new AABB(source).inflate(4.0));
            helper.assertTrue(total == 2,
                "cross-session merge handling may never lose or duplicate logs, total="
                    + total);
            helper.assertTrue(entities == 2 && recoveredA == 1 && recoveredB == 1,
                "different workers' logs must not merge into one owner domain "
                    + "(entities=" + entities + " recoveredA=" + recoveredA
                    + " recoveredB=" + recoveredB + ")");
            helper.succeed();
        });
    }

    /** Owned production must likewise remain distinct from an ordinary drop. */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "ground_collection_merge_ownership")
    public void ownedAndUnownedAdjacentLogsDoNotMergeOwnership(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession session = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(session.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "fixture must create one owned physical log");
        ItemEntity owned = session.nearestLoaded(level, source);
        helper.assertTrue(owned != null, "fixture must resolve the owned log");
        owned.setDeltaMovement(Vec3.ZERO);
        ItemEntity ordinary = new ItemEntity(level, source.getX() + 0.5,
            source.getY() + 0.5, source.getZ() + 0.5,
            new ItemStack(Items.OAK_LOG));
        ordinary.setDeltaMovement(Vec3.ZERO);
        ordinary.setPickUpDelay(100);
        helper.assertTrue(level.addFreshEntity(ordinary),
            "fixture must add one ordinary unowned physical log");

        helper.runAtTickTime(39, () -> {
            owned.setPos(source.getX() + 0.5, source.getY() + 0.25,
                source.getZ() + 0.5);
            ordinary.setPos(source.getX() + 0.5, source.getY() + 0.25,
                source.getZ() + 0.5);
            owned.setDeltaMovement(Vec3.ZERO);
            ordinary.setDeltaMovement(Vec3.ZERO);
        });

        helper.runAfterDelay(60, () -> {
            int total = worldLogs(level, source);
            int entities = physicalLogEntities(level, source);
            session.forgetUnresolved();
            int recovered = session.recoverOwned(level,
                new AABB(source).inflate(4.0));
            helper.assertTrue(total == 2,
                "owned/unowned merge handling may never lose or duplicate logs, total="
                    + total);
            helper.assertTrue(entities == 2 && recovered == 1,
                "owned production must remain distinct from an ordinary drop "
                    + "(entities=" + entities + " recovered=" + recovered + ")");
            helper.succeed();
        });
    }

    /**
     * The ownership target is also vanilla's merge/pickup discriminator, so
     * it must be a finite lease. Once the transient session is abandoned
     * without an explicit cleanup callback, the live ItemEntity itself must
     * release target, pickup delay and recoverable owner authority.
     */
    @GameTest(template = "empty16", timeoutTicks = 180,
        batch = "ground_collection_lease_expiry")
    public void abandonedOwnershipLeaseExpiresOnThePhysicalItem(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession abandoned = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(abandoned.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "fixture must create one leased physical log");
        ItemEntity physical = abandoned.nearestLoaded(level,
            worker.blockPosition());
        helper.assertTrue(physical != null,
            "fixture must resolve the leased physical entity");
        physical.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(worker.getUUID().equals(physical.getTarget())
                && physical.hasPickUpDelay(),
            "active ownership must set both vanilla target and pickup delay");

        // Simulate losing the goal/session without calling abandon(). The
        // physical entity's own expiry hook must be sufficient recovery.
        abandoned.forgetUnresolved();
        helper.runAfterDelay(GroundCollectionSession.OWNERSHIP_LEASE_TICKS + 5,
            () -> {
                GroundCollectionSession recovered = new GroundCollectionSession(worker,
                    stack -> stack.is(ItemTags.LOGS), 4);
                int recoverable = recovered.recoverOwned(level,
                    new AABB(source).inflate(4.0));
                helper.assertTrue(physical.isAlive()
                        && worldLogs(level, source) == 1,
                    "lease expiry must preserve the real log in the world");
                helper.assertTrue(physical.getTarget() == null,
                    "expired ownership must clear vanilla's pickup/merge target");
                helper.assertTrue(!physical.hasPickUpDelay(),
                    "expired ownership must make the orphan normally pickupable");
                helper.assertTrue(recoverable == 0,
                    "expired persistent owner metadata must not resurrect a dead "
                        + "session (recovered=" + recoverable + ")");
                helper.succeed();
            });
    }

    /**
     * Recovery and the physical entity tick have no guaranteed ordering when
     * a chunk is loaded. An already-expired owner row must be rejected by the
     * synchronous rebuild itself, before its heartbeat can extend the lease.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "ground_collection_lease_expiry")
    public void recoveryCannotResurrectLeaseBeforeFirstItemTick(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlerEntity worker = worker(helper);
        GroundCollectionSession oldSession = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        BlockPos source = helper.absolutePos(new BlockPos(5, 1, 4));
        helper.assertTrue(oldSession.spawnPhysical(level, source,
                new ItemStack(Items.OAK_LOG)),
            "fixture must create one physically-owned log");
        ItemEntity physical = oldSession.nearestLoaded(level,
            worker.blockPosition());
        helper.assertTrue(physical != null,
            "fixture must resolve the exact leased ItemEntity");

        // Do not tick the entity. Put its existing lease exactly on the
        // deadline and immediately ask a brand-new session to recover it.
        physical.getPersistentData().putLong(
            "HearthsteadGroundCollectionLeaseExpiry", level.getGameTime());
        GroundCollectionSession recovering = new GroundCollectionSession(worker,
            stack -> stack.is(ItemTags.LOGS), 4);
        int recovered = recovering.recoverOwned(level,
            new AABB(source).inflate(4.0));

        helper.assertTrue(recovered == 0 && recovering.trackedCount() == 0,
            "recoverOwned must reject, not heartbeat, an owner whose lease is "
                + "already due (recovered=" + recovered + ")");
        helper.assertTrue(physical.isAlive() && worldLogs(level, source) == 1,
            "lease rejection must preserve the exact physical log");
        helper.assertTrue(physical.getTarget() == null
                && !physical.hasPickUpDelay(),
            "synchronous rejection must clear pickup/merge ownership before "
                + "the ItemEntity receives another tick");
        helper.succeed();
    }
}
