package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The archer trade, end to end (owner's ask, 2026-08-25: an archer whose
 * abilities — Power Shot, Triple Shot — arrive over time).
 *
 * <p>The load-bearing rules pinned here, each of which would fail its named
 * test if broken in code:
 *
 * <ul>
 *   <li><b>Chest truth.</b> Every arrow an archer looses left the
 *       WATCHTOWER's own chest, exactly counted — and an empty tower means
 *       an archer who cannot shoot. This is the consumer end of FLOWS.md's
 *       fletcher → watchtower edge, and the first test asserts the full
 *       conservation identity, not just "the chest went down".
 *   <li><b>Doing the job makes you better at it</b> (job standard point 8):
 *       loosing arrows trains DEXTERITY, the number {@link ArcherRank#of}
 *       reads — without it, a career archer could never leave RECRUIT, the
 *       exact defect the guard progression audit found on STRENGTH.
 *   <li><b>The Power Shot cadence</b>: a SHARPSHOOTER's every-4th-shot
 *       ability actually fires on its cadence, observed through the goal's
 *       own counters (designed-for-testability seams, not reflection).
 * </ul>
 *
 * <p>Helpers mirror {@link GuardTrainingGameTests} exactly (a registered
 * settlement small enough that neighbouring arenas cannot answer for each
 * other, a valid building the hire API accepts).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See {@link GuardTrainingGameTests#settlement}: registered, and small,
     *  for exactly the same reasons. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Skytterholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** A valid WATCHTOWER whose bounds contain the chest the tests stock —
     *  bounds are what {@code WarehouseIndex.containers} walks. */
    private static Building tower(GameTestHelper helper, Settlement s,
                                  int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, x, z);
    }

    private static Container chestAt(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        var be = helper.getBlockEntity(rel);
        if (!(be instanceof Container c)) {
            throw new IllegalStateException("fixture: no container at " + rel);
        }
        return c;
    }

    private static int countOf(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** A physical bow is employment equipment; arrows remain separate stock. */
    private static void stockArcherRack(Container rack, int arrows) {
        rack.setItem(0, new ItemStack(Items.BOW));
        if (arrows > 0) {
            rack.setItem(1, new ItemStack(Items.ARROW, arrows));
        }
    }

    /** See {@link GuardTrainingGameTests#trainStrengthTo}: repeated small
     *  calls, so the result lands close to the target. */
    private static void trainDexterityTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.DEXTERITY) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.DEXTERITY, 5.0F, 1.0F);
        }
    }

    /**
     * The goal under test, from the entity's own selector.
     *
     * <p>{@code SettlerEntity.registerGoals} is the model-wiring worker's
     * file this cycle, so until the registration line lands there the
     * fixture arms the goal itself, at the same slot {@code GuardMeleeGoal}
     * holds (2). The lookup-first shape means these tests keep measuring the
     * one real instance — never a duplicate that would double-shoot — both
     * before and after that wiring lands.
     */
    private static ArcherAttackGoal arm(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) {
                return existing;
            }
        }
        ArcherAttackGoal goal = new ArcherAttackGoal(archer);
        archer.goalSelector.addGoal(2, goal);
        return goal;
    }

    // --------------------------------------------------- chest-true ammo ---

    /**
     * The whole trade in one arena: a hired archer, a stocked tower, a
     * raider — the raider gets hurt AND the tower's arrow count goes down,
     * and the conservation identity holds at every observed instant:
     * chest + quiver + loosed == what the chest started with. (A MARKSMAN
     * fixture, so the spread is tight, every volley is exactly one arrow,
     * and the assertion is arithmetic rather than luck.)
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerLoosesChestTrueArrowsAtARaider(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Skytte", 4, 4);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, tower, archer);
        helper.assertTrue(hired.ok(), "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getProfession() == Profession.ARCHER,
            "the watchtower's trade is ARCHER now, was " + archer.getProfession());
        trainDexterityTo(archer, ArcherRank.MARKSMAN.threshold());

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            helper.assertTrue(inChest + goal.quiverCount() + goal.shotsFired() == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " != the 16 the tower started with");
            helper.assertTrue(inChest < 16,
                "the tower's own chest must be what the quiver drains");
            helper.assertTrue(pell.getHealth() < pellMax,
                "an archer with arrows and a clear shot must hurt the raider"
                    + " (still " + pell.getHealth() + "/" + pellMax
                    + " after " + goal.shotsFired() + " volleys)");
        });
    }

    /**
     * Regression for the old transient goal field: a chunk unload or restart
     * after restock erased every shaft already removed from the tower. The
     * borrowed count now lives on the entity, survives NBT reload exactly and
     * remains bounded when malformed external data is presented.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 200)
    public void borrowedQuiverSurvivesReloadWithoutBreakingConservation(
            GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building watchtower = tower(helper, settlement, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, ArcherAttackGoal.QUIVER_SIZE);

        SettlerEntity archer = settler(helper, settlement, "Lagerfast", 4, 4);
        helper.assertTrue(
            Employment.hire(helper.getLevel(), settlement, watchtower, archer).ok(),
            "fixture: the watchtower must hire the reload archer");
        // This test is about persisted borrowed-arrow ownership. Give it the
        // already-physical rack bow synchronously; the separate equipment
        // acquisition tests own the animated chest-to-hand journey.
        ItemStack physicalBow = rack.removeItem(0, 1);
        helper.assertTrue(physicalBow.is(Items.BOW),
            "fixture: the exact Watchtower rack must supply the bow");
        archer.setItemSlot(EquipmentSlot.MAINHAND, physicalBow);
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(13, 1, 4));
        target.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(target);

        // The persistence boundary is the first real combat tick: it pulls
        // the physical rack arrows, then begins the ordinary 20-tick draw.
        // Drive that single authoritative goal tick directly so the save
        // cannot race the selector past this deliberately narrow boundary.
        helper.assertTrue(goal.canUse(),
            "fixture: the physically armed Archer must enter its real combat goal");
        goal.start();
        goal.tick();
        int borrowed = goal.quiverCount();
        helper.assertTrue(borrowed > 0 && goal.shotsFired() == 0,
            "fixture must save after rack withdrawal and before release");
        int inRack = countOf(rack, Items.ARROW);

        CompoundTag save = new CompoundTag();
        archer.addAdditionalSaveData(save);
        helper.assertTrue(save.getInt(SettlerEntity.ARCHER_QUIVER_NBT_KEY)
                == borrowed,
            "entity save must own the exact borrowed arrow count");
        helper.assertTrue(save.hasUUID(
                SettlerEntity.ARCHER_QUIVER_SOURCE_NBT_KEY)
                && save.getUUID(SettlerEntity.ARCHER_QUIVER_SOURCE_NBT_KEY)
                    .equals(watchtower.id),
            "entity save must bind borrowed arrows to the exact source tower");

            SettlerEntity loaded = ModEntities.SETTLER.get().create(
                helper.getLevel());
            helper.assertTrue(loaded != null,
                "fixture: replacement settler entity must construct");
            loaded.readAdditionalSaveData(save);
            ArcherAttackGoal loadedGoal = arm(loaded);
            helper.assertTrue(loadedGoal.quiverCount() == borrowed,
                "reload changed quiver ownership from " + borrowed + " to "
                    + loadedGoal.quiverCount());
            helper.assertTrue(loaded.archerQuiverOwnedBy(watchtower.id),
                "reload must retain exact source-tower ownership");
            helper.assertTrue(inRack + loadedGoal.quiverCount()
                    + goal.shotsFired() == ArcherAttackGoal.QUIVER_SIZE,
                "reload broke conservation: rack " + inRack + " + quiver "
                    + loadedGoal.quiverCount() + " + loosed "
                    + goal.shotsFired());

            CompoundTag overflow = save.copy();
            overflow.putInt(SettlerEntity.ARCHER_QUIVER_NBT_KEY,
                ArcherAttackGoal.QUIVER_SIZE + 99);
            loaded.readAdditionalSaveData(overflow);
            helper.assertTrue(loaded.archerQuiverCount()
                    == ArcherAttackGoal.QUIVER_SIZE,
                "malformed reload must clamp quiver ownership to its bound");

            Building towerB = tower(helper, settlement, 9, 2);
            chestAt(helper, new BlockPos(10, 1, 3));
            helper.assertFalse(loaded.archerQuiverOwnedBy(towerB.id),
                "Tower A's restarted quiver must never authorize Tower B");

            archer.setTarget(null);
            goal.stop();
            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    towerB, archer).ok(),
                "a normal reassignment must settle Tower A's quiver first");
            helper.assertTrue(archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null
                    && countOf(rack, Items.ARROW) == ArcherAttackGoal.QUIVER_SIZE,
                "A->B reassignment must return every borrowed shaft to A and clear provenance");

            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    watchtower, archer).ok(),
                "fixture: Archer must return to Tower A for full-rack dismissal");
            int arrowSlot = -1;
            for (int slot = 0; slot < rack.getContainerSize(); slot++) {
                if (rack.getItem(slot).is(Items.ARROW)) {
                    arrowSlot = slot;
                    break;
                }
            }
            ItemStack rackArrows = arrowSlot < 0 ? ItemStack.EMPTY
                : rack.getItem(arrowSlot);
            helper.assertTrue(rackArrows.is(Items.ARROW)
                    && rackArrows.getCount() >= 4,
                "fixture: Tower A rack must contain the returned arrows");
            rackArrows.shrink(4);
            helper.assertTrue(archer.storeArcherQuiverArrows(watchtower.id, 4)
                    == 4,
                "fixture: four physical rack arrows must enter the sourced quiver");
            Container holding = chestAt(helper, new BlockPos(15, 1, 15));
            for (int slot = 0; slot < rack.getContainerSize(); slot++) {
                holding.setItem(slot, rack.removeItemNoUpdate(slot));
                rack.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            AABB nearby = archer.getBoundingBox().inflate(3.0D);
            int droppedBefore = helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, nearby, item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(Employment.dismiss(helper.getLevel(), settlement,
                    archer) == watchtower,
                "full-rack dismissal must complete through visible fallback");
            int droppedAfter = helper.getLevel().getEntitiesOfClass(
                ItemEntity.class, nearby, item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(droppedAfter - droppedBefore == 4
                    && archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null,
                "full rack must materialize exactly four arrows once, then clear count+source");

            helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                    watchtower, archer).ok(),
                "fixture: terminal Archer must re-enter the exact Watchtower");
            UUID terminalSale = UUID.randomUUID();
            helper.assertTrue(settlement.employmentAuthorizations.authorize(
                    settlement.id, archer.getUUID(), watchtower.id,
                    Profession.ARCHER, terminalSale),
                "fixture: terminal cleanup needs one exact current paid receipt");
            UUID orderAuthor = UUID.randomUUID();
            GuardOrder terminalOrder = settlement.guardOrders.orderForMutation(
                settlement.id, archer.getUUID(),
                helper.getLevel().dimension().location()).orElseThrow();
            helper.assertTrue(terminalOrder.issueTower(watchtower.anchor,
                    Direction.NORTH, GuardOrder.DEFAULT_FACING_ARC,
                    orderAuthor, watchtower.id, helper.getLevel().getGameTime()),
                "fixture: terminal Archer needs a persisted Tower Post");
            int holdingArrowSlot = -1;
            for (int slot = 0; slot < holding.getContainerSize(); slot++) {
                if (holding.getItem(slot).is(Items.ARROW)
                    && holding.getItem(slot).getCount() >= 3) {
                    holdingArrowSlot = slot;
                    break;
                }
            }
            helper.assertTrue(holdingArrowSlot >= 0,
                "fixture: conserved rack stock must contain three death-test arrows");
            holding.getItem(holdingArrowSlot).shrink(3);
            holding.setChanged();
            helper.assertTrue(archer.storeArcherQuiverArrows(watchtower.id, 3)
                    == 3,
                "fixture: death-test quiver must own three exact Tower A arrows");
            archer.bag.setItem(0, new ItemStack(Items.BREAD, 2));

            int arrowsBeforeCancelledDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            Consumer<LivingDeathEvent> cancelArcherDeath = event -> {
                if (event.getEntity() == archer) {
                    event.getEntity().setHealth(1.0F);
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class,
                cancelArcherDeath);
            try {
                helper.assertTrue(archer.hurt(
                        helper.getLevel().damageSources().genericKill(),
                        archer.getMaxHealth() + 100.0F),
                    "fixture: the lethal hit must land before cancellation");
            } finally {
                NeoForge.EVENT_BUS.unregister(cancelArcherDeath);
            }
            int arrowsAfterCancelledDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(archer.isAlive() && !archer.isRemoved()
                    && settlement.record(archer.getUUID()) != null
                    && watchtower.workers.contains(archer.getUUID())
                    && settlement.employmentAuthorizations.matches(
                        settlement.id, archer.getUUID(), watchtower.id,
                        Profession.ARCHER)
                    && settlement.guardOrders.order(archer.getUUID()).isPresent()
                    && archer.bag.getItem(0).is(Items.BREAD)
                    && archer.bag.getItem(0).getCount() == 2
                    && archer.archerQuiverCount() == 3
                    && archer.archerQuiverOwnedBy(watchtower.id)
                    && arrowsAfterCancelledDeath == arrowsBeforeCancelledDeath,
                "a cancelled LivingDeathEvent must preserve the living roster, "
                    + "job receipt, order, bag, quiver and physical item count");

            archer.setHealth(1.0F);
            // Both death edges are compressed into one GameTest tick. The
            // cancelled lethal hit correctly leaves vanilla's hurt cooldown
            // behind, so clear only that fixture clock before exercising the
            // distinct accepted terminal edge.
            archer.invulnerableTime = 0;
            int breadBeforeAcceptedDeath = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            Consumer<EntityJoinLevelEvent> rejectTerminalItems = event -> {
                if (event.getLevel() == helper.getLevel()
                    && event.getEntity() instanceof ItemEntity item
                    && (item.getItem().is(Items.ARROW)
                        || item.getItem().is(Items.BREAD))) {
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                EntityJoinLevelEvent.class, rejectTerminalItems);
            try {
                helper.assertTrue(archer.hurt(
                        helper.getLevel().damageSources().genericKill(), 20.0F),
                    "fixture: the accepted terminal death must land");
            } finally {
                NeoForge.EVENT_BUS.unregister(rejectTerminalItems);
            }
            int arrowsAfterRejectedSpawn = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            int breadAfterRejectedSpawn = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            DeferredItemMaterializationSavedData escrow =
                DeferredItemMaterializationSavedData.get(helper.getLevel());
            helper.assertTrue(!archer.isAlive()
                    && settlement.record(archer.getUUID()) == null
                    && !watchtower.workers.contains(archer.getUUID())
                    && settlement.employmentAuthorizations.receipt(
                        archer.getUUID()) == null
                    && settlement.guardOrders.order(archer.getUUID()).isEmpty()
                    && watchtower.equipmentRequests.stream().noneMatch(
                        request -> request.requesterId().equals(
                            archer.getUUID()))
                    && archer.archerQuiverCount() == 0
                    && archer.archerQuiverSourceBuildingId() == null
                    && archer.bag.isEmpty()
                    && arrowsAfterRejectedSpawn == arrowsAfterCancelledDeath
                    && breadAfterRejectedSpawn == breadBeforeAcceptedDeath
                    && escrow.pendingRows() == 2
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.ARROW) == 3
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.BREAD) == 2,
                "a rejected terminal ItemEntity spawn must transfer exact arrow "
                    + "and bag ownership into durable escrow while clearing worker, "
                    + "request, order and current authorization once");

            CompoundTag escrowSave = escrow.save(new CompoundTag(),
                helper.getLevel().registryAccess());
            DeferredItemMaterializationSavedData escrowRestart =
                DeferredItemMaterializationSavedData.load(escrowSave,
                    helper.getLevel().registryAccess());
            helper.assertTrue(!escrowRestart.quarantined()
                    && escrowRestart.pendingRows() == 2
                    && escrowRestart.pendingItems(
                        helper.getLevel().registryAccess(), Items.ARROW) == 3
                    && escrowRestart.pendingItems(
                        helper.getLevel().registryAccess(), Items.BREAD) == 2,
                "rejected death drops must retain exact counts through restart");

            // Simulate the opposite save-tear edge: the physical arrow entity
            // exists with the stable row UUID/proof, but SavedData still owns
            // the pending row. Retry must consume escrow, not spawn a second
            // three-arrow stack.
            ListTag pendingRows = escrowSave.getList("Pending",
                Tag.TAG_COMPOUND);
            CompoundTag arrowRow = null;
            ItemStack arrowStack = ItemStack.EMPTY;
            for (int rowIndex = 0; rowIndex < pendingRows.size(); rowIndex++) {
                CompoundTag candidate = pendingRows.getCompound(rowIndex);
                ItemStack decoded = ItemStack.parseOptional(
                    helper.getLevel().registryAccess(),
                    candidate.getCompound("Stack"));
                if (decoded.is(Items.ARROW)) {
                    arrowRow = candidate;
                    arrowStack = decoded;
                    break;
                }
            }
            helper.assertTrue(arrowRow != null && arrowStack.getCount() == 3,
                "fixture: restart escrow must expose the exact arrow row");
            CompoundTag proof = new CompoundTag();
            proof.putUUID("Id", arrowRow.getUUID("Id"));
            CompoundTag options = new CompoundTag();
            options.putInt("PickupDelay", -1);
            options.putBoolean("ExtendedLifetime", false);
            proof.put("Options", options);

            ItemEntity wrongPosition = new ItemEntity(helper.getLevel(),
                arrowRow.getDouble("X"), arrowRow.getDouble("Y") + 65.0D,
                arrowRow.getDouble("Z"), arrowStack.copy());
            wrongPosition.setUUID(arrowRow.getUUID("Id"));
            wrongPosition.getPersistentData().put(
                "HearthsteadDeferredMaterializationProof", proof.copy());
            helper.assertTrue(helper.getLevel().addFreshEntity(wrongPosition),
                "fixture: a same-UUID wrong-position collision must join first");
            DeferredItemMaterializationSavedData.retryLoaded(
                helper.getLevel());
            helper.assertTrue(escrow.pendingRows() == 1
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.ARROW) == 3
                    && escrow.pendingItems(helper.getLevel().registryAccess(),
                        Items.BREAD) == 0,
                "an out-of-bounds UUID collision must retain the exact arrow "
                    + "escrow while an independent bread row remains free to materialize");
            wrongPosition.discard();

            ItemEntity alreadyPhysical = new ItemEntity(helper.getLevel(),
                arrowRow.getDouble("X") + 1.25D, arrowRow.getDouble("Y"),
                arrowRow.getDouble("Z"), arrowStack);
            alreadyPhysical.setUUID(arrowRow.getUUID("Id"));
            alreadyPhysical.getPersistentData().put(
                "HearthsteadDeferredMaterializationProof", proof);
            helper.assertTrue(helper.getLevel().addFreshEntity(alreadyPhysical),
                "fixture: the exact already-materialized replay entity must join");

            DeferredItemMaterializationSavedData.retryLoaded(
                helper.getLevel());
            int arrowsAfterRetry = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.ARROW))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            int breadAfterRetry = helper.getLevel()
                .getEntitiesOfClass(ItemEntity.class, nearby,
                    item -> item.getItem().is(Items.BREAD))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(escrow.pendingRows() == 0
                    && arrowsAfterRetry - arrowsAfterRejectedSpawn == 3
                    && breadAfterRetry - breadAfterRejectedSpawn == 2,
                "retry must acknowledge one exact pre-existing arrow entity, "
                    + "materialize one bread stack and never duplicate either row");
            helper.assertTrue(escrow.materializedExact(helper.getLevel(),
                    arrowRow.getUUID("Id"), arrowRow.getDouble("X"),
                    arrowRow.getDouble("Y"), arrowRow.getDouble("Z"),
                    arrowStack,
                    DeferredItemMaterializationSavedData.ItemEntityOptions.NONE),
                "source-ledger replay must prove the exact moved physical item "
                    + "even after its deferred row was consumed");

            UUID deterministicHandoff = UUID.randomUUID();
            ItemStack stagedBread = new ItemStack(Items.BREAD);
            helper.assertTrue(escrow.queue(helper.getLevel(),
                    deterministicHandoff, archer.getX(), archer.getY(),
                    archer.getZ(), stagedBread,
                    DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.INSERTED
                    && escrow.queue(helper.getLevel(), deterministicHandoff,
                        archer.getX(), archer.getY(), archer.getZ(), stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.IDEMPOTENT
                    && escrow.containsExact(helper.getLevel(),
                        deterministicHandoff, archer.getX(), archer.getY(),
                        archer.getZ(), stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    && escrow.queue(helper.getLevel(), deterministicHandoff,
                        archer.getX() + 1.0D, archer.getY(), archer.getZ(),
                        stagedBread,
                        DeferredItemMaterializationSavedData.ItemEntityOptions.NONE)
                    == DeferredItemMaterializationSavedData.QueueResult.COLLISION
                    && escrow.cancel(deterministicHandoff),
                "deterministic source handoff must insert once, replay exactly, "
                    + "reject changed coordinates and roll back before source clear");

            SettlerEntity replacement = settler(helper, settlement,
                "Etterfølger", 5, 4);
            UUID replacementSale = UUID.randomUUID();
            ItemStack replacementEmblem = JobEmblemItem.stackFor(
                Profession.ARCHER);
            helper.assertTrue(JourneyEmblemProvenance.stamp(replacementEmblem,
                    settlement.id, replacementSale, Profession.ARCHER),
                "fixture: replacement Archer needs a new physical sale stamp");
            ServerPlayer replacementAuthor =
                helper.makeMockServerPlayerInLevel();
            replacementAuthor.setPos(replacement.getX(), replacement.getY(),
                replacement.getZ());
            replacementAuthor.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                replacementEmblem);
            helper.assertTrue(Employment.hireWithHeldEmblem(helper.getLevel(),
                    settlement, watchtower, replacement,
                    replacementAuthor).ok()
                    && settlement.employmentAuthorizations.matches(
                        settlement.id, replacement.getUUID(), watchtower.id,
                        Profession.ARCHER),
                "the vacated Watchtower must accept one newly paid replacement");
            Settlement terminalRestart = Settlement.readNbt(
                settlement.writeNbt());
            helper.assertTrue(terminalRestart.employmentAuthorizations.matches(
                    settlement.id, replacement.getUUID(), watchtower.id,
                    Profession.ARCHER),
                "replacement authority must survive restart after terminal cleanup");
        helper.succeed();
    }

    /**
     * A volley is not allowed to spawn from an unchanged stance. The real
     * MAINHAND bow must enter vanilla's synced use-item state for at least one
     * observable pre-contact tick, then leave it on the same tick the first
     * arrow is loosed. SettlerModel consumes this exact state for draw pose and
     * bow pull; EV_ARCHER_LOOSE owns only the bounded recovery presentation.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerPublishesPhysicalDrawBeforeRelease(GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building watchtower = tower(helper, settlement, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, settlement, "Buestreng", 4, 4);
        helper.assertTrue(
            Employment.hire(helper.getLevel(), settlement, watchtower, archer).ok(),
            "fixture: the watchtower must hire the archer");
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(13, 1, 4));
        target.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(target);

        boolean[] sawPhysicalDraw = {false};
        helper.onEachTick(() -> {
            if (goal.shotsFired() == 0 && archer.isUsingItem()) {
                helper.assertTrue(archer.getUseItem().is(Items.BOW),
                    "the draw state must belong to the real MAINHAND bow");
                sawPhysicalDraw[0] = true;
            }
            if (goal.shotsFired() > 0) {
                helper.assertTrue(sawPhysicalDraw[0],
                    "an arrow spawned without an observable physical bow draw");
                helper.assertTrue(!archer.isUsingItem(),
                    "the bow-use state must stop on the exact release tick");
                helper.succeed();
            }
        });
    }

    /**
     * No arrows in the tower = no shooting. The mirror image of the test
     * above, and the pressure that makes the fletcher worth hiring: the
     * archer stands the post empty-handed rather than conjuring ammunition.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void anEmptyTowerMeansNoShots(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));  // present, empty
        stockArcherRack(rack, 0);

        SettlerEntity archer = settler(helper, s, "Tomhendt", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.runAfterDelay(250, () -> {
            helper.assertTrue(goal.shotsFired() == 0,
                "no arrows in the tower must mean no shots, yet "
                    + goal.shotsFired() + " were loosed");
            helper.assertTrue(rack.isEmpty(),
                "an empty rack must stay empty -- nothing may mint arrows");
            helper.assertTrue(pell.isAlive() && pell.getHealth() >= pellMax,
                "the raider must be untouched, at " + pell.getHealth()
                    + "/" + pellMax);
            helper.succeed();
        });
    }

    // ------------------------------------------------- doing trains rank ---

    /**
     * Loosing arrows trains DEXTERITY — the number {@link ArcherRank#of}
     * reads. Counted at the moment of release (and again per arrow that
     * strikes, from the arrow's own hit hook), never on a timer: without
     * this, a career archer could never leave RECRUIT, the exact defect the
     * guard progression audit found on the STRENGTH ladder.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void loosingArrowsTrainsDexterity(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, s, "Laerling", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        int before = archer.attribute(Attribute.DEXTERITY);

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> helper.assertTrue(
            archer.attribute(Attribute.DEXTERITY) > before,
            "loosing arrows must train Dexterity -- the rank ladder reads it"
                + " (started " + before + ", still "
                + archer.attribute(Attribute.DEXTERITY) + ")"));
    }

    // ---------------------------------------------------- the power shot ---

    /**
     * A SHARPSHOOTER's Power Shot fires on its cadence: every 4th volley,
     * no more and no fewer — {@code shotsFired / 4 == powerShotsFired} is an
     * invariant at any instant, because both counters move in the same
     * release. Observed through the goal's own seams; the fixture reaches
     * DEX 35 the same way {@link GuardTrainingGameTests} reaches VETERAN.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void aSharpshooterFiresThePowerShotOnItsCadence(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        stockArcherRack(chestAt(helper, new BlockPos(3, 1, 3)), 16);

        SettlerEntity archer = settler(helper, s, "Skarpskytter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        trainDexterityTo(archer, ArcherRank.SHARPSHOOTER.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.SHARPSHOOTER),
            "fixture sanity: the Power Shot needs a Sharpshooter, DEX="
                + archer.attribute(Attribute.DEXTERITY));

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            helper.assertTrue(
                goal.shotsFired() / ArcherRank.POWER_SHOT_EVERY == goal.powerShotsFired(),
                "the cadence broke: " + goal.powerShotsFired() + " power shots in "
                    + goal.shotsFired() + " volleys is not every "
                    + ArcherRank.POWER_SHOT_EVERY + "th");
            helper.assertTrue(goal.powerShotsFired() >= 1,
                "a Sharpshooter's 4th volley must be a Power Shot ("
                    + goal.shotsFired() + " volleys so far)");
        });
    }

    // --------------------------------------------------- the triple shot ---

    /**
     * ACCEPT-JOBS audit (2026-08-26): Triple Shot -- the owner's other named
     * ability, MASTER's every-5th-volley fan of three arrows -- had NO
     * coverage anywhere: only {@link ArcherAttackGoal#powerShotsFired()}
     * existed as a test seam, {@code tripleShotsFired()} did not exist at
     * all. Added one line for line (see that goal's own class doc for why
     * it is duplicated rather than shared) so this is observable the same
     * way Power Shot already was. The conservation identity accounts for
     * the fan explicitly: a Triple Shot spends THREE arrows for one volley,
     * not one, so {@code shotsFired} alone would silently under-count ammo
     * the moment a Master archer's cadence lands on its 5th shot -- exactly
     * the kind of chest-truth gap this audit exists to catch.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void aMasterArcherFansTheTripleShotOnItsCadence(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Mesterskytter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        trainDexterityTo(archer, ArcherRank.MASTER.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.MASTER),
            "fixture sanity: the Triple Shot needs a Master, DEX="
                + archer.attribute(Attribute.DEXTERITY));

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        // The fourth cadence slot is a Power Shot. Keep the fixture target
        // alive through the following fifth slot so Triple Shot cadence,
        // rather than a premature target death, owns this assertion.
        pell.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        pell.setHealth(pell.getMaxHealth());
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            // A Triple Shot spends 3 arrows for 1 counted volley -- the 2
            // extra per triple shot have to be added back in, or the
            // identity below would look broken even though nothing leaked.
            int accounted = inChest + goal.quiverCount() + goal.shotsFired()
                + 2 * goal.tripleShotsFired();
            helper.assertTrue(accounted == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " + 2*triples " + goal.tripleShotsFired() + " != 16");
            helper.assertTrue(goal.tripleShotsFired() >= 1,
                "a Master's 5th volley must be a Triple Shot ("
                    + goal.shotsFired() + " volleys so far, "
                    + goal.powerShotsFired() + " power)");
            helper.assertTrue(pell.getHealth() < pellMax,
                "a Master archer firing its real cadence must still hurt the "
                    + "raider (still " + pell.getHealth() + "/" + pellMax + ")");
        });
    }

    // ------------------------------------------------- self-acquisition ---

    /**
     * ACCEPT-JOBS audit (2026-08-26): every test above calls {@code
     * archer.setTarget(pell)} before waiting -- real fixtures for the
     * shooting mechanics, but every one of them skips straight past {@link
     * ArcherAttackGoal#canUse()}'s own {@code acquire()} call, the archer's
     * DUPLICATED copy of {@code SettlerDefenseTargetGoal}'s targeting logic
     * (the class doc's own "What is deliberately mirrored" section explains
     * why it is duplicated rather than shared). That method had zero
     * coverage: nothing ever left an archer's target null and simply waited
     * to see whether the trade notices a raider on its own. This is the
     * full chain the owner is judging at 18:00 -- hired, posted, watching,
     * and finding its own target -- not a raider handed to it by the test.
     * The raider is spawned real (never {@code setTarget} on either side)
     * and given no AI so it cannot wander out of the settlement ring before
     * the archer's own {@code RETARGET_INTERVAL} scan finds it.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerFindsAndLoosesAtARaiderWithNoHelp(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        stockArcherRack(rack, 16);

        SettlerEntity archer = settler(helper, s, "Speider", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getTarget() == null,
            "fixture sanity: nothing may hand the archer a target");

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        // Armed only so the fixture can read shotsFired()/quiverCount() --
        // the SAME lookup-first helper every other test in this file uses,
        // never a second goal instance. Never armed with a target.
        ArcherAttackGoal goal = arm(archer);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            helper.assertTrue(inChest + goal.quiverCount() + goal.shotsFired() == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " != the 16 the tower started with");
            helper.assertTrue(archer.getTarget() == pell,
                "the archer must find the raider through its OWN goal (never "
                    + "setTarget from the test), got " + archer.getTarget());
            helper.assertTrue(pell.getHealth() < pellMax,
                "an archer that finds its own target must still hurt the raider"
                    + " (still " + pell.getHealth() + "/" + pellMax
                    + " after " + goal.shotsFired() + " volleys)");
        });
    }
}
