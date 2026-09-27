package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.ResidentMeal;
import com.hearthstead.entity.Trait;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.entity.ai.HearthApproach;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Real entity NBT, goal and deferred-drop edges; no meal-policy mocks. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ResidentMealGameTests {
    private static final BlockPos HEARTH = new BlockPos(4, 1, 4);

    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 40)
    public void communalFoodKeepsExactSlotsAcrossRepeatedBlockReload(GameTestHelper helper) {
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        BlockPos position = helper.absolutePos(HEARTH);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(position);
        UUID settlement = UUID.randomUUID();
        hearth.bindSettlement(settlement);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.COOKED_CHICKEN, 4));
        hearth.getInventory().setStackInSlot(7, namedBread(13));
        hearth.getInventory().setStackInSlot(23, new ItemStack(Items.GLISTERING_MELON_SLICE, 4));
        var registries = helper.getLevel().registryAccess();
        CompoundTag original = hearth.saveWithoutMetadata(registries);
        for (int pass = 0; pass < 3; pass++) {
            var restored = new HearthBlockEntity(position, hearth.getBlockState());
            restored.loadWithComponents(original.copy(), registries);
            helper.assertTrue(settlement.equals(restored.getSettlementId())
                    && restored.countFoodUnits() == 17
                    && restored.getInventory().getStackInSlot(0).getCount() == 4
                    && restored.getInventory().getStackInSlot(7).getCount() == 13
                    && ItemStack.isSameItemSameComponents(restored.getInventory().getStackInSlot(7), namedBread(1))
                    && restored.getInventory().getStackInSlot(23).getCount() == 4
                    && original.equals(restored.saveWithoutMetadata(registries)),
                "reloading a Hearth must retain every exact item, slot, component and settlement");
            hearth = restored;
            original = hearth.saveWithoutMetadata(registries);
        }
        helper.succeed();
    }

    private static final class DeathProbe extends SettlerEntity {
        DeathProbe(ServerLevel level) { super(ModEntities.SETTLER.get(), level); }
        void retryDeath() { super.tickDeath(); }
    }

    private static DeathProbe resident(GameTestHelper helper) {
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        DeathProbe resident = new DeathProbe(helper.getLevel());
        var feet = helper.absolutePos(new BlockPos(3, 1, 4));
        resident.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        resident.setNoAi(true); // This fixture drives the real goal/session explicitly.
        resident.bindTo(UUID.randomUUID(), helper.absolutePos(HEARTH));
        resident.setHunger(10);
        resident.addMorale(-30);
        helper.assertTrue(helper.getLevel().addFreshEntity(resident), "resident must join");
        return resident;
    }

    private static ItemStack namedBread(int count) {
        ItemStack bread = new ItemStack(Items.BREAD, count);
        bread.set(DataComponents.CUSTOM_NAME, Component.literal("Resident meal proof"));
        return bread;
    }

    private static boolean proofItem(ItemEntity entity) {
        return entity.getItem().is(Items.BREAD)
            && Component.literal("Resident meal proof").equals(
                entity.getItem().get(DataComponents.CUSTOM_NAME));
    }

    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 160)
    public void interruptedSavedMealResumesWithoutHearthAndPaysOnce(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        ItemStack source = namedBread(2);
        ItemStack identity = source.copyWithCount(1);
        helper.assertTrue(resident.beginMeal(source) && source.getCount() == 1,
            "begin must remove exactly one real source item");
        helper.assertTrue(!resident.beginMeal(source) && source.getCount() == 1,
            "second claim must not take another item");
        resident.mealDisplayCopy().shrink(1);
        helper.assertTrue(ItemStack.isSameItemSameComponents(resident.mealDisplayCopy(), identity),
            "display copies cannot mutate owned food or components");
        EatFromHearthGoal goal = new EatFromHearthGoal(resident);
        helper.assertTrue(goal.canUse(), "served meal must run with no Hearth block or stock");
        goal.start();
        int[] phase = {0};
        int[] pauseTicks = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 1) {
                helper.assertTrue(!resident.tickMeal() && resident.mealRemainingTicks() == 30,
                    "interrupted non-EATING ticks must retain the exact remaining meal");
                if (++pauseTicks[0] < 6) return;
                CompoundTag saved = new CompoundTag();
                resident.addAdditionalSaveData(saved);
                resident.readAdditionalSaveData(saved);
                helper.assertTrue(resident.mealRemainingTicks() == 30
                    && ItemStack.isSameItemSameComponents(resident.mealDisplayCopy(), identity),
                    "entity save/load must retain phase and exact components");
                helper.assertTrue(goal.canUse(), "retained meal bypasses cooldown and empty Hearth");
                goal.start();
                phase[0] = 2;
            }
            int beforeTicks = resident.mealRemainingTicks();
            float beforeHunger = resident.getHunger();
            float beforeMorale = resident.getMorale();
            goal.tick();
            int afterTicks = resident.mealRemainingTicks();
            helper.assertTrue(!resident.tickMeal() && resident.mealRemainingTicks() == afterTicks,
                "a second caller in the same game tick cannot advance or pay again");
            if (phase[0] == 0 && afterTicks == 30) {
                goal.stop();
                phase[0] = 1;
            }
            if (beforeTicks == 1 && !resident.hasMeal()) {
                helper.assertTrue(resident.getHunger() == beforeHunger + 40
                    && resident.getMorale() == beforeMorale + 2 * Trait.moraleGain(resident.traits()),
                    "one completed bread must pay exactly its existing nutrition and morale");
                CompoundTag completed = new CompoundTag();
                resident.addAdditionalSaveData(completed);
                resident.readAdditionalSaveData(completed);
                helper.assertTrue(!resident.hasMeal() && !resident.tickMeal()
                    && resident.getHunger() == beforeHunger + 40
                    && source.getCount() == 1,
                    "completed save cannot replay nutrition or withdraw a second source item");
                helper.succeed();
            }
        });
    }

    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 80)
    public void hearthPickupRequiresVisibleContactAndOwnsOnlyOneMeal(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(HEARTH));
        hearth.getInventory().setStackInSlot(0, namedBread(2));
        // Same range through a full two-high wall; no pickup is permitted.
        var blocked = helper.absolutePos(new BlockPos(2, 1, 4));
        resident.setPos(blocked.getX() + 0.5, blocked.getY(), blocked.getZ() + 0.5);
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 2, 4), Blocks.STONE);
        EatFromHearthGoal goal = new EatFromHearthGoal(resident);
        helper.assertTrue(goal.canUse(), "hungry resident sees real stocked Hearth");
        goal.start();
        goal.tick();
        helper.assertTrue(!resident.hasMeal() && hearth.countFoodUnits() == 2,
            "in-range wall must prevent inventory withdrawal");
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.AIR);
        helper.setBlock(new BlockPos(3, 2, 4), Blocks.AIR);
        goal.tick();
        helper.assertTrue(resident.hasMeal() && resident.mealRemainingTicks() == 40
            && hearth.countFoodUnits() == 1,
            "clear contact must transfer exactly one meal, not instant nutrition");
        goal.stop();
        helper.assertTrue(resident.hasMeal() && hearth.countFoodUnits() == 1,
            "ordinary self-fetch interruption retains extracted food");
        helper.succeed();
    }

    /**
     * The authored grounded village puts the Hearth in a perimeter corner:
     * walls/gate remove three cardinal feet and the outside shoulder has no
     * support. A resident must walk to a real visible contact cell before one
     * physical bread changes ownership.
     */
    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 180)
    public void cornerHearthUsesPhysicalContactRouteBeforeWithdrawingMeal(
            GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(HEARTH);
        helper.assertTrue(hearth != null, "fixture: corner Hearth exists");
        hearth.getInventory().setStackInSlot(0, namedBread(1));

        helper.setBlock(HEARTH.east(), Blocks.COBBLESTONE_WALL);
        helper.setBlock(HEARTH.north(), Blocks.COBBLESTONE_WALL);
        helper.setBlock(HEARTH.south(), Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.OPEN, false));
        helper.setBlock(HEARTH.west().below(), Blocks.AIR);
        BlockPos start = helper.absolutePos(new BlockPos(7, 1, 6));
        resident.setPos(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);

        // This fixture uses the real AI/physics, unlike the explicit session
        // probes above. Wait for observed support instead of assuming that a
        // disabled-AI actor has grounded exactly two ticks after placement.
        resident.setNoAi(false);
        boolean[] groundedRouteChecked = {false};
        helper.onEachTick(() -> {
            if (groundedRouteChecked[0] || !resident.onGround()) return;
            helper.assertTrue(HearthApproach.findReachableContactPath(resident,
                    helper.getLevel(), helper.absolutePos(HEARTH)) != null,
                "corner Hearth must expose one reachable, ray-clear contact route");
            groundedRouteChecked[0] = true;
        });

        boolean[] physicallyOwned = {false};
        helper.succeedWhen(() -> {
            physicallyOwned[0] |= resident.hasMeal();
            helper.assertTrue(groundedRouteChecked[0] && physicallyOwned[0] && hearth.countFoodUnits() == 0
                    && resident.blockPosition().distSqr(helper.absolutePos(HEARTH)) <= 6.25D,
                "resident must reach a real Hearth contact and own exactly its one bread"
                    + " [pos=" + resident.position() + ", grounded=" + resident.onGround()
                    + ", routeChecked=" + groundedRouteChecked[0] + ", meal=" + resident.hasMeal() + "]");
        });
    }

    @GameTest(batch = "resident_meal_death", template = "empty16", timeoutTicks = 100)
    public void rejectedDeathMaterializationPreservesMealAcrossEscrowReload(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        helper.assertTrue(resident.beginMeal(namedBread(1)), "meal fixture must transfer");
        var escrow = DeferredItemMaterializationSavedData.get(helper.getLevel());
        int before = escrow.pendingItems(helper.getLevel().registryAccess(), Items.BREAD);
        Consumer<EntityJoinLevelEvent> reject = event -> {
            if (event.getLevel() == helper.getLevel()
                && event.getEntity() instanceof ItemEntity item && proofItem(item)) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EntityJoinLevelEvent.class, reject);
        try {
            resident.hurt(helper.getLevel().damageSources().genericKill(), 1000);
            resident.retryDeath();
        } finally {
            NeoForge.EVENT_BUS.unregister(reject);
        }
        helper.assertTrue(!resident.isAlive() && !resident.hasMeal()
            && escrow.pendingItems(helper.getLevel().registryAccess(), Items.BREAD) == before + 1,
            "rejected world insertion must leave exactly one meal in deferred ownership");
        var reloaded = DeferredItemMaterializationSavedData.load(
            escrow.save(new CompoundTag(), helper.getLevel().registryAccess()),
            helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.pendingItems(helper.getLevel().registryAccess(), Items.BREAD) == before + 1,
            "the rejected physical meal must survive escrow save/load");
        DeferredItemMaterializationSavedData.retryLoaded(helper.getLevel());
        resident.retryDeath();
        int count = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            helper.getBounds(), ResidentMealGameTests::proofItem).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(count == 1
            && escrow.pendingItems(helper.getLevel().registryAccess(), Items.BREAD) == before,
            "one retry materializes one component-preserving bread, never two");
        helper.succeed();
    }

    @GameTest(batch = "resident_meal_capacity", template = "empty16", timeoutTicks = 100)
    public void fullDeathEscrowRetainsCorpseMealUntilCapacityReturns(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        helper.assertTrue(resident.beginMeal(namedBread(1)), "meal fixture must transfer");
        var escrow = DeferredItemMaterializationSavedData.get(helper.getLevel());
        var fixtureRows = new ArrayList<UUID>();
        try {
            while (escrow.pendingRows() < DeferredItemMaterializationSavedData.MAX_PENDING_ROWS) {
                UUID row = escrow.queue(helper.getLevel(), resident.getX(), resident.getY(),
                    resident.getZ(), new ItemStack(Items.COBBLESTONE));
                helper.assertTrue(row != null, "fixture must fill the ordinary bounded queue");
                fixtureRows.add(row);
            }
            resident.hurt(helper.getLevel().damageSources().genericKill(), 1000);
            for (int i = 0; i < 25; i++) resident.retryDeath();
            CompoundTag corpse = new CompoundTag();
            resident.addAdditionalSaveData(corpse);
            ItemStack retained = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                corpse.getCompound(ResidentMeal.NBT_KEY).getCompound("Item"));
            helper.assertTrue(!resident.isAlive() && !resident.isRemoved() && resident.hasMeal()
                && retained.getCount() == 1
                && ItemStack.isSameItemSameComponents(retained, namedBread(1)),
                "a full ledger must retain the exact saved meal and prevent corpse removal");
        } finally {
            for (UUID row : fixtureRows) escrow.cancel(row);
        }
        resident.retryDeath();
        resident.retryDeath();
        int count = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            helper.getBounds(), ResidentMealGameTests::proofItem).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(!resident.hasMeal() && count == 1,
            "returning capacity releases the saved meal exactly once");
        helper.succeed();
    }

    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 100)
    public void completedStewPreservesItsBowlAndCannotEatItAgain(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        ItemStack stew = new ItemStack(Items.MUSHROOM_STEW);
        helper.assertTrue(resident.beginMeal(stew) && stew.isEmpty(), "one actual stew transfers");
        resident.setActivity(SettlerActivity.EATING);
        helper.onEachTick(() -> {
            float before = resident.getHunger();
            if (!resident.tickMeal()) return;
            helper.assertTrue(!resident.hasMeal() && resident.getHunger() == before + 48,
                "stew grants one nutrition result");
            CompoundTag saved = new CompoundTag();
            resident.addAdditionalSaveData(saved);
            ItemStack remainder = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                saved.getCompound(ResidentMeal.NBT_KEY).getCompound("Item"));
            helper.assertTrue(remainder.is(Items.BOWL) && remainder.getCount() == 1,
                "completed meal retains its one real conversion remainder");
            resident.readAdditionalSaveData(saved);
            helper.assertTrue(!resident.tickMeal() && resident.getHunger() == before + 48
                && !resident.beginMeal(namedBread(1)),
                "saved bowl cannot replay nutrition or be overwritten by a new meal");
            helper.succeed();
        });
    }

    @GameTest(batch = "resident_meal", template = "empty16", timeoutTicks = 100)
    public void savedMealLosingFoodComponentReturnsItemWithoutEatingForever(GameTestHelper helper) {
        DeathProbe resident = resident(helper);
        helper.assertTrue(resident.beginMeal(namedBread(1)), "one real bread enters saved meal ownership");
        CompoundTag saved = new CompoundTag();
        resident.addAdditionalSaveData(saved);
        CompoundTag session = saved.getCompound(ResidentMeal.NBT_KEY);
        ItemStack changed = ItemStack.parseOptional(helper.getLevel().registryAccess(), session.getCompound("Item"));
        // Reproduce a formerly edible saved stack after a component change.
        // Keep the exact item, count/name and original positive remaining time.
        changed.remove(DataComponents.FOOD);
        helper.assertTrue(changed.getFoodProperties(resident) == null, "fixture must really remove edibility");
        session.put("Item", changed.save(helper.getLevel().registryAccess()));
        resident.readAdditionalSaveData(saved);
        EatFromHearthGoal goal = new EatFromHearthGoal(resident);
        helper.assertTrue(resident.hasMeal() && goal.canUse(), "saved positive-time session reaches real resume branch");
        goal.start();
        helper.runAfterDelay(1, () -> {
            float hunger = resident.getHunger();
            float morale = resident.getMorale();
            helper.assertTrue(!resident.tickMeal() && !resident.hasMeal()
                && resident.getHunger() == hunger && resident.getMorale() == morale,
                "non-edible session must release EATING without nutrition or completion");
            CompoundTag cancelled = new CompoundTag();
            resident.addAdditionalSaveData(cancelled);
            ItemStack retained = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                cancelled.getCompound(ResidentMeal.NBT_KEY).getCompound("Item"));
            helper.assertTrue(retained.getCount() == 1 && ItemStack.isSameItemSameComponents(retained, changed),
                "cancelled session keeps the exact non-edible component-bearing stack until return");
            goal.stop();
            helper.assertTrue(!goal.canUse(), "no active meal can bypass cooldown and monopolize MOVE again");
            helper.runAfterDelay(25, () -> {
                var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds(), ResidentMealGameTests::proofItem);
                int count = items.stream().mapToInt(item -> item.getItem().getCount()).sum();
                helper.assertTrue(count == 1 && items.stream().allMatch(item ->
                    ItemStack.isSameItemSameComponents(item.getItem(), changed)) && !resident.hasMeal(),
                    "ordinary periodic deferred return must materialize exactly one unchanged item");
                CompoundTag returned = new CompoundTag();
                resident.addAdditionalSaveData(returned);
                helper.assertTrue(!returned.getCompound(ResidentMeal.NBT_KEY).contains("Item"),
                    "resident and physical world cannot both retain the returned item");
                helper.succeed();
            });
        });
    }

    /**
     * The Road probe must visibly stop short at this live follow-range contact,
     * while vanilla finds the exact same physical target. Once that premise is
     * recorded, normal goal arbitration must walk, claim one live bread, and
     * complete its nutrition without accepting the partial Road prefix.
     */
    @GameTest(batch = "resident_meal_route_ab", template = "empty32", timeoutTicks = 450)
    public void hearthRouteAbAtFollowRangeKeepsVanillaAndRoadEvidence(
            GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        var hearth = (HearthBlockEntity) helper.getBlockEntity(HEARTH);
        helper.assertTrue(hearth != null, "HSQA_HEARTH_ROUTE_AB hearth must exist");
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 1));

        BlockPos hearthPos = helper.absolutePos(HEARTH);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "RouteAB", hearthPos);
        settlement.radius = 31;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(29, 1, 20));
        resident.setSettlerName("Route AB resident");
        resident.bindTo(settlement.id, settlement.center);
        settlement.putRecord(resident.getUUID(), resident.getSettlerName(), Profession.NONE);
        resident.setHunger(20);
        helper.assertTrue(resident.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE) >= 30.0D,
            "HSQA_HEARTH_ROUTE_AB follow range must cover the nearest contact");
        HoldMoveGoal hold = new HoldMoveGoal();
        resident.goalSelector.addGoal(0, hold);

        boolean[] probesRecorded = {false};
        boolean[] contactBeforeClaim = {false};
        boolean[] mealOwned = {false};

        helper.onEachTick(() -> {
            if (!probesRecorded[0]) {
                if (!resident.onGround() || resident.getNavigation().getPath() != null) return;
                Set<BlockPos> targets = legalOpenFieldTargets(hearthPos);
                // Keep the historical A/B premise on the original road budget,
                // and require the new navigator to recover it before movement.
                GroundPathNavigation road = new IndoorRouteGameTests.OrdinaryNavigation(resident);
                Path recovered = new RoadNavigation(resident, helper.getLevel()).createPath(targets, 0);
                helper.assertTrue(recovered != null && recovered.canReach()
                        && targets.contains(recovered.getTarget()),
                    "current road navigation must recover full legal Hearth contact");
                GroundPathNavigation vanilla = new GroundPathNavigation(resident, helper.getLevel());
                vanilla.setCanOpenDoors(true);
                vanilla.setCanPassDoors(true);
                Path roadPath = road.createPath(targets, 0);
                Path vanillaPath = vanilla.createPath(targets, 0);
                Hearthstead.LOGGER.info("HSQA_HEARTH_ROUTE_AB tick={} pos={} grounded={} targets={} road={} vanilla={}",
                    helper.getTick(), resident.position(), resident.onGround(), targets.size(),
                    pathEvidence(roadPath), pathEvidence(vanillaPath));
                helper.assertTrue(roadPath != null && !roadPath.canReach(),
                    "HSQA_HEARTH_ROUTE_AB Road premise must be a real partial path: "
                        + pathEvidence(roadPath));
                helper.assertTrue(vanillaPath != null && vanillaPath.canReach()
                        && targets.contains(vanillaPath.getTarget()),
                    "HSQA_HEARTH_ROUTE_AB vanilla path must reach one legal contact target: "
                        + pathEvidence(vanillaPath));
                helper.assertTrue(!resident.hasMeal() && hearth.countFoodUnits() == 1,
                    "isolated path probes must not withdraw the physical food");
                resident.goalSelector.removeGoal(hold);
                probesRecorded[0] = true;
                return;
            }

            if (!mealOwned[0] && resident.blockPosition().distSqr(hearthPos) <= 6.25D) {
                contactBeforeClaim[0] = true;
            }
            if (!mealOwned[0] && resident.hasMeal()) {
                helper.assertTrue(contactBeforeClaim[0] && hearth.countFoodUnits() == 0,
                    "one bread may leave the live Hearth only after real close contact"
                        + " [pos=" + resident.blockPosition().toShortString()
                        + ", food=" + hearth.countFoodUnits() + "]");
                mealOwned[0] = true;
                return;
            }
            if (mealOwned[0] && !resident.hasMeal()) {
                helper.assertTrue(hearth.countFoodUnits() == 0 && resident.mealDisplayCopy().isEmpty()
                        && resident.getHunger() > 40.0F,
                    "the one owned bread must complete real nutrition with no duplicate meal"
                        + " [hunger=" + resident.getHunger() + ", food="
                        + hearth.countFoodUnits() + "]");
                Hearthstead.LOGGER.info("HSQA_HEARTH_ROUTE_PHYSICAL_SUCCESS tick={} pos={} hunger={} food={}",
                    helper.getTick(), resident.position(), resident.getHunger(), hearth.countFoodUnits());
                helper.succeed();
            }
        });
    }

    /**
     * A hungry civilian starts farther from the Hearth than its follow range.
     * Road must expose a strictly nearer prefix while the strict contact API
     * cannot yet qualify a full route. Normal goal arbitration must then stage
     * movement, claim one live bread only at contact, and complete nutrition.
     */
    @GameTest(batch = "resident_meal_route_staged", template = "empty32", timeoutTicks = 450)
    public void hungryCivilianStagesCloserRoadPrefixesBeforePhysicalHearthMeal(
            GameTestHelper helper) {
        BlockPos hearthLocal = new BlockPos(2, 1, 2);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        helper.setBlock(hearthLocal, ModBlocks.HEARTH.get());
        var hearth = (HearthBlockEntity) helper.getBlockEntity(hearthLocal);
        helper.assertTrue(hearth != null, "HSQA_HEARTH_STAGED_ROUTE hearth must exist");
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 1));

        BlockPos hearthPos = helper.absolutePos(hearthLocal);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "StagedRoute", hearthPos);
        settlement.radius = 48;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        // Civilian NONE keeps GuardOrder's persisted-order yield policy out
        // of this meal-recovery proof.
        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(29, 1, 29));
        resident.setSettlerName("Staged route resident");
        resident.bindTo(settlement.id, settlement.center);
        settlement.putRecord(resident.getUUID(), resident.getSettlerName(), Profession.NONE);
        resident.setHunger(20);
        helper.assertTrue(resident.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE) >= 30.0D,
            "HSQA_HEARTH_STAGED_ROUTE fixture must retain ordinary follow range");
        HoldMoveGoal hold = new HoldMoveGoal();
        resident.goalSelector.addGoal(0, hold);

        boolean[] probesRecorded = {false};
        boolean[] contactBeforeClaim = {false};
        boolean[] mealOwned = {false};

        helper.onEachTick(() -> {
            if (!probesRecorded[0]) {
                if (!resident.onGround() || resident.getNavigation().getPath() != null) return;
                Set<BlockPos> targets = legalOpenFieldTargets(hearthPos);
                BlockPos start = resident.blockPosition();
                GroundPathNavigation road = new IndoorRouteGameTests.OrdinaryNavigation(resident);
                Path roadPath = road.createPath(targets, 0);
                Path recoveredPath = new RoadNavigation(resident, helper.getLevel()).createPath(targets, 0);
                Path strictPath = HearthApproach.findReachableContactPath(
                    resident, helper.getLevel(), hearthPos);
                Path progressingPath = HearthApproach.findProgressingContactPath(
                    resident, helper.getLevel(), hearthPos);
                BlockPos roadEnd = roadPath == null || roadPath.getNodeCount() == 0 ? null
                    : roadPath.getNodePos(roadPath.getNodeCount() - 1);
                Hearthstead.LOGGER.info("HSQA_HEARTH_STAGED_ROUTE tick={} pos={} grounded={} targets={} road={} strict={} progressing={}",
                    helper.getTick(), resident.position(), resident.onGround(), targets.size(),
                    pathEvidence(roadPath), pathEvidence(strictPath), pathEvidence(progressingPath));
                helper.assertTrue(roadPath != null && !roadPath.canReach() && roadEnd != null
                        && !roadEnd.equals(start) && roadEnd.distSqr(hearthPos) < start.distSqr(hearthPos),
                    "HSQA_HEARTH_STAGED_ROUTE Road premise must be a genuinely closer partial prefix: "
                        + pathEvidence(roadPath));
                helper.assertTrue(recoveredPath != null && recoveredPath.canReach()
                        && targets.contains(recoveredPath.getTarget()),
                    "HSQA_HEARTH_STAGED_ROUTE current navigation must recover full contact: "
                        + pathEvidence(recoveredPath));
                helper.assertTrue(strictPath != null && strictPath.canReach(),
                    "HSQA_HEARTH_STAGED_ROUTE full contact must now be available: "
                        + pathEvidence(strictPath));
                helper.assertTrue(progressingPath != null && progressingPath.canReach()
                        && targets.contains(progressingPath.getTarget()),
                    "HSQA_HEARTH_STAGED_ROUTE meal helper must retain complete physical contact: "
                        + pathEvidence(progressingPath));
                helper.assertTrue(!resident.hasMeal() && hearth.countFoodUnits() == 1,
                    "isolated route probes must not withdraw the physical food");
                resident.goalSelector.removeGoal(hold);
                probesRecorded[0] = true;
                return;
            }

            if (!mealOwned[0] && resident.blockPosition().distSqr(hearthPos) <= 6.25D) {
                contactBeforeClaim[0] = true;
            }
            if (!mealOwned[0] && resident.hasMeal()) {
                helper.assertTrue(contactBeforeClaim[0] && hearth.countFoodUnits() == 0,
                    "one bread may leave the live Hearth only after real close contact"
                        + " [pos=" + resident.blockPosition().toShortString()
                        + ", food=" + hearth.countFoodUnits() + "]");
                mealOwned[0] = true;
                return;
            }
            if (mealOwned[0] && !resident.hasMeal()) {
                helper.assertTrue(hearth.countFoodUnits() == 0 && resident.mealDisplayCopy().isEmpty()
                        && resident.getHunger() > 40.0F,
                    "the one owned bread must complete real nutrition with no duplicate meal"
                        + " [hunger=" + resident.getHunger() + ", food="
                        + hearth.countFoodUnits() + "]");
                Hearthstead.LOGGER.info("HSQA_HEARTH_STAGED_ROUTE_SUCCESS tick={} pos={} hunger={} food={}",
                    helper.getTick(), resident.position(), resident.getHunger(), hearth.countFoodUnits());
                // This arena sits beside the real-founding merchant test.
                // Retire only our owned settlement so its radius cannot block
                // the next independent village from being founded.
                resident.discard();
                data.settlements.remove(settlement.id);
                data.setDirty();
                helper.setBlock(hearthLocal, Blocks.AIR);
                helper.succeed();
            }
        });
    }


    /**
     * A hungry civilian may be more than its ordinary 32-block follow range
     * from food. The meal-only probe must still plan one complete legal route
     * without changing the combat-follow attribute or moving any food.
     */
    @GameTest(batch = "resident_meal_long_route", template = "empty64", timeoutTicks = 40)
    public void civilianMealProbeFindsCompleteRouteBeyondFollowRange(GameTestHelper helper) {
        BlockPos hearthLocal = new BlockPos(4, 1, 4);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        helper.setBlock(hearthLocal, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthLocal);
        helper.assertTrue(hearth != null, "HSQA_LONG_HEARTH_ROUTE hearth must exist");
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 1));

        BlockPos hearthPos = helper.absolutePos(hearthLocal);
        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(55, 1, 55));
        // Keep real gravity/collision ticks; suppress decisions rather than physics.
        resident.goalSelector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal())
            .toList().forEach(resident.goalSelector::removeGoal);
        resident.targetSelector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal())
            .toList().forEach(resident.targetSelector::removeGoal);
        double followRange = resident.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        helper.assertTrue(followRange >= 30.0D && followRange <= 40.0D
                && resident.blockPosition().distSqr(hearthPos) > followRange * followRange,
            "HSQA_LONG_HEARTH_ROUTE fixture must start beyond ordinary follow range"
                + " [range=" + followRange + ", start=" + resident.blockPosition()
                + ", hearth=" + hearthPos + "]");

        // A newly spawned mob has not had its first collision/grounding tick.
        // GroundPathNavigation correctly refuses to plan while it is airborne;
        // observe real grounding rather than making the route API ignore it.
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(resident.onGround(),
                "HSQA_LONG_HEARTH_ROUTE resident must physically settle on the fixture floor");
            Path route = HearthApproach.findCivilianMealContactPath(resident, helper.getLevel(), hearthPos);
            helper.assertTrue(route != null && route.canReach()
                    && route.getTarget() != null && route.getTarget().distSqr(hearthPos) <= 6.25D,
                "HSQA_LONG_HEARTH_ROUTE must return a complete close-contact route beyond 32 blocks: "
                    + pathEvidence(route));
            helper.assertTrue(hearth.countFoodUnits() == 1 && !resident.hasMeal(),
                "HSQA_LONG_HEARTH_ROUTE planning must not withdraw Hearth food");
            helper.succeed();
        });
    }

    /** Real FloatGoal, movement and physical meal ownership from a two-deep pool. */
    @GameTest(batch = "resident_meal_water_recovery", template = "empty16", timeoutTicks = 600)
    public void hungryResidentLeavesDeepWaterBeforePhysicalHearthMeal(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = 0; y <= 2; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            for (int y = 3; y <= 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        for (int x = 2; x <= 6; x++) for (int z = 4; z <= 8; z++) {
            if (x < 6) helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
        }
        BlockPos hearthLocal = new BlockPos(12, 3, 6);
        helper.setBlock(hearthLocal, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthLocal);
        helper.assertTrue(hearth != null, "water recovery fixture needs its real Hearth");
        hearth.getInventory().setStackInSlot(0, namedBread(1));
        BlockPos hearthPos = helper.absolutePos(hearthLocal);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "WaterRecovery", hearthPos);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        BlockPos startLocal = new BlockPos(3, 1, 6);
        BlockPos start = helper.absolutePos(startLocal);
        helper.assertTrue(helper.getLevel().getFluidState(start).is(net.minecraft.tags.FluidTags.WATER)
                && helper.getLevel().getFluidState(start.above()).is(net.minecraft.tags.FluidTags.WATER),
            "fixture must begin in a real two-block source-water column");
        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(), startLocal);
        resident.setSettlerName("Deep water meal resident");
        resident.bindTo(settlement.id, settlement.center);
        settlement.putRecord(resident.getUUID(), resident.getSettlerName(), Profession.NONE);
        resident.setHunger(20);
        resident.setEnergy(80);
        boolean[] sawFloat = {false};
        boolean[] sawDryBeforeMeal = {false};
        boolean[] sawMeal = {false};
        long[] dryTick = {-1};
        net.minecraft.world.phys.Vec3[] previous = {resident.position()};
        helper.onEachTick(() -> {
            helper.assertTrue(resident.isAlive(), "water recovery actor must remain alive");
            helper.assertTrue(resident.position().distanceToSqr(previous[0]) < 4.0D,
                "recovery must use continuous movement, not a relocation between samples");
            previous[0] = resident.position();
            sawFloat[0] |= resident.isInWater() && resident.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.isRunning()
                    && wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.FloatGoal);
            if (!sawMeal[0] && !resident.hasMeal() && !resident.isInWater() && resident.onGround()) {
                if (!sawDryBeforeMeal[0]) dryTick[0] = helper.getTick();
                sawDryBeforeMeal[0] = true;
            }
            if (!sawMeal[0] && !resident.hasMeal()) {
                helper.assertTrue(hearth.countFoodUnits() == 1 && resident.getHunger() <= 20.0F,
                    "before ownership the sole bread must remain in Hearth and grant no nutrition");
            }
            if (!sawMeal[0] && resident.hasMeal()) {
                helper.assertTrue(sawFloat[0] && sawDryBeforeMeal[0] && dryTick[0] < helper.getTick()
                        && !resident.isInWater() && resident.blockPosition().distSqr(hearthPos) <= 6.25D
                        && hearth.countFoodUnits() == 0
                        && ItemStack.isSameItemSameComponents(resident.mealDisplayCopy(), namedBread(1)),
                    "ordinary FloatGoal must precede dry movement, then exact physical bread transfer");
                sawMeal[0] = true;
                return;
            }
            if (sawMeal[0] && !resident.hasMeal()) {
                int looseBread = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds(), ResidentMealGameTests::proofItem).stream()
                    .mapToInt(item -> item.getItem().getCount()).sum();
                helper.assertTrue(resident.getHunger() > 40.0F && hearth.countFoodUnits() == 0
                        && resident.mealDisplayCopy().isEmpty() && looseBread == 0,
                    "one real bread must complete nutrition without retaining or dropping a duplicate");
                resident.discard();
                data.settlements.remove(settlement.id);
                data.setDirty();
                helper.setBlock(hearthLocal, Blocks.AIR);
                helper.succeed();
            }
        });
    }

    private static Set<BlockPos> legalOpenFieldTargets(BlockPos hearth) {
        Set<BlockPos> targets = new LinkedHashSet<>();
        targets.add(hearth.above());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if ((x != 0 || z != 0) && x * x + z * z <= 6.25D)
                targets.add(hearth.offset(x, 0, z).immutable());
        }
        return targets;
    }

    private static String pathEvidence(Path path) {
        if (path == null) return "null";
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) {
            if (i > 0) nodes.append('|');
            nodes.append(path.getNodePos(i).toShortString());
        }
        return "target=" + path.getTarget() + ",canReach=" + path.canReach()
            + ",done=" + path.isDone() + ",next=" + path.getNextNodeIndex()
            + ",count=" + path.getNodeCount() + ",end="
            + (path.getNodeCount() == 0 ? "none" : path.getNodePos(path.getNodeCount() - 1))
            + ",nodes=" + nodes;
    }

    private static final class HoldMoveGoal extends Goal {
        private HoldMoveGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return true; }
        @Override public boolean canContinueToUse() { return true; }
    }
}
