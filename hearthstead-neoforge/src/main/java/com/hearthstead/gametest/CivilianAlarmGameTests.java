package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CivilianAlarmGameTests {
    @GameTest(template = "empty16", timeoutTicks = 1150)
    public static void civilianReportsAtGuardThenCompletesAndLeavesShelter(GameTestHelper h) {
        room(h);
        Settlement s = new Settlement(UUID.randomUUID(), "Alarm test", h.absolutePos(new BlockPos(13,1,12)));
        s.radius = 16; SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        BlockPos bed = bedAndHome(h, s);
        SettlerEntity civilian = h.spawn(ModEntities.SETTLER.get(), new BlockPos(9,1,8));
        civilian.bindTo(s.id, s.center); s.putRecord(civilian.getUUID(), "Carrier", Profession.NONE);
        civilian.claimBed(bed); civilian.bag.setItem(0, new ItemStack(Items.BREAD, 14));
        // Keep Guard contact (radius 3) separate from home arrival (radius 2).
        // Overlapping destinations made a legitimate report also look like home arrival.
        SettlerEntity guard = h.spawn(ModEntities.SETTLER.get(), new BlockPos(7,1,12));
        guard.bindTo(s.id, s.center); guard.setProfessionProjection(Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        s.putRecord(guard.getUUID(), "Guard", Profession.GUARD);
        guard.setNoAi(true); // Isolate the civilian's real report route from the Guard's independent combat acquisition.
        var enemy = h.spawn(EntityType.ZOMBIE, new BlockPos(5,1,8)); enemy.setNoAi(true);
        int[] tick = {0}; int[] homeAt = {-1}; boolean[] reported = {false};
        String[] lastHomeAttempt = {"none"};
        // ALERT_GUARD and HOME each have a bounded 240-tick route window. Keep
        // the complete 600-tick shelter proof, but fail near the two-leg bound
        // with the causal transition that did not happen instead of timing out.
        GameTestTicks.at(h, 520, () -> {
            h.assertTrue(reported[0],
                "Civilian must report at a reachable own Guard within the bounded alert route: "
                    + homeRouteWitness(civilian, bed, guard));
            h.assertTrue(homeAt[0] >= 0,
                "After reporting, civilian must physically reach its owned home within the bounded home route: "
                    + homeRouteWitness(civilian, bed, guard) + "; lastActiveHome=" + lastHomeAttempt[0]);
        });
        h.onEachTick(() -> {
            int now = ++tick[0];
            h.assertTrue(civilian.bag.countItem(Items.BREAD) == 14, "Panic must preserve carried cargo");
            h.assertTrue(civilian.getTarget() == null, "Civilian must never fight the hostile");
            if (!reported[0] && s.alertActive(h.getLevel().getGameTime())) {
                h.assertTrue(civilian.distanceToSqr(guard) <= 9.1,
                    "The first alert transition must occur at close own-Guard contact, before home");
                h.assertTrue(civilian.blockPosition().distSqr(bed) > 4.0,
                    "Guard report must happen before civilian reaches the home interior");
                reported[0] = true;
                enemy.discard();
                // Test control removes the completed alert so only the persisted 600-tick
                // home timer, not a stale global alert, holds the civilian afterwards.
                s.alertUntilGameTime = h.getLevel().getGameTime();
            }
            if (reported[0] && homeAt[0] < 0 && civilian.panicShelterUntil() > h.getLevel().getGameTime()) {
                homeAt[0] = now;
                h.assertTrue(civilian.blockPosition().distSqr(bed) <= 4.0,
                    "The shelter deadline cannot start before physical home arrival");
                h.assertTrue(civilian.panicShelterUntil() >= h.getLevel().getGameTime() + 590,
                    "Shelter deadline starts only after physical interior arrival");
            }
            if (reported[0] && homeAt[0] < 0 && civilian.goalSelector.getAvailableGoals().stream()
                    .anyMatch(wrapped -> wrapped.isRunning()
                        && wrapped.getGoal() instanceof com.hearthstead.entity.ai.SettlerPanicGoal)) {
                lastHomeAttempt[0] = "tick=" + now + "," + homeRouteWitness(civilian, bed, guard);
            }
            if (homeAt[0] >= 0 && now == homeAt[0] + 590) {
                h.assertTrue(civilian.getActivity() == SettlerActivity.FLEEING,
                    "Civilian must remain sheltered for the full 600-tick minimum after the threat ends");
            }
            if (homeAt[0] >= 0 && now == homeAt[0] + 620) {
                h.assertTrue(civilian.getActivity() != SettlerActivity.FLEEING,
                    "With threat gone, civilian leaves shelter only after its persisted minimum elapsed");
                h.succeed();
            }
        });
    }

    private static String homeRouteWitness(SettlerEntity civilian, BlockPos bed, SettlerEntity guard) {
        var path = civilian.getNavigation().getPath();
        String route;
        if (path == null) {
            route = "null";
        } else {
            int next = path.getNextNodeIndex();
            String nextNode = next >= 0 && next < path.getNodeCount()
                ? path.getNode(next).asBlockPos().toShortString() : "none";
            route = "target=" + path.getTarget().toShortString() + ",reachable=" + path.canReach()
                + ",done=" + path.isDone() + ",next=" + next + "/" + path.getNodeCount()
                + ",node=" + nextNode + ",end="
                + (path.getEndNode() == null ? "none" : path.getEndNode().asBlockPos().toShortString());
        }
        StringBuilder running = new StringBuilder();
        civilian.goalSelector.getAvailableGoals().forEach(wrapped -> {
            if (!wrapped.isRunning()) return;
            if (running.length() > 0) running.append(',');
            running.append(wrapped.getGoal().getClass().getSimpleName());
        });
        return "civilian=" + civilian.position() + ",cell=" + civilian.blockPosition().toShortString()
            + ",bed=" + bed.toShortString() + ",guard=" + guard.blockPosition().toShortString()
            + ",activity=" + civilian.getActivity().key()
            + ",shelterUntil=" + civilian.panicShelterUntil()
            + ",routeFailure=" + civilian.routeFailureNote()
            + ",running=" + (running.isEmpty() ? "none" : running)
            + ",path=" + route;
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void missingHomeDoesNotUseHearthOrHoldMoveForever(GameTestHelper h) {
        room(h);
        Settlement s = new Settlement(UUID.randomUUID(), "No home", h.absolutePos(new BlockPos(3,1,8)));
        s.radius = 16; SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlerEntity civilian = h.spawn(ModEntities.SETTLER.get(), new BlockPos(9,1,8));
        civilian.bindTo(s.id, s.center); s.putRecord(civilian.getUUID(), "Carrier", Profession.NONE);
        civilian.bag.setItem(0, new ItemStack(Items.BREAD, 14));
        var enemy = h.spawn(EntityType.ZOMBIE, new BlockPos(7,1,8)); enemy.setNoAi(true);
        boolean[] observedMissingHome = {false};
        h.onEachTick(() -> observedMissingHome[0] |= civilian.routeFailureNote().contains("panic_home_unavailable"));
        GameTestTicks.at(h, 80, () -> {
            h.assertTrue(civilian.bag.countItem(Items.BREAD) == 14, "Missing home must not lose cargo");
            h.assertTrue(civilian.getClaimedBed() == null, "Panic must not create or claim a free home");
            h.assertTrue(observedMissingHome[0],
                "Missing owned safe room must expose a readable blocked state");
            h.assertTrue(civilian.blockPosition().distSqr(s.center) > 4.0,
                "Civilian must never use the Hearth as a panic destination");
            h.assertTrue(civilian.getActivity() != SettlerActivity.FLEEING,
                "Missing-home route must yield MOVE and retry later, not hold panic forever");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 20)
    public static void arrivedShelterDeadlinePersistsAcrossEntitySave(GameTestHelper h) {
        SettlerEntity original = h.spawn(ModEntities.SETTLER.get(), new BlockPos(2,1,2));
        long deadline = h.getLevel().getGameTime() + 600;
        original.extendPanicShelterUntil(deadline);
        CompoundTag saved = new CompoundTag(); original.addAdditionalSaveData(saved);
        SettlerEntity restored = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(restored != null, "Settler type must recreate a saved civilian");
        restored.readAdditionalSaveData(saved);
        h.assertTrue(restored.panicShelterUntil() == deadline,
            "Reload must retain the remaining arrived-shelter deadline");
        h.succeed();
    }

    private static void room(GameTestHelper h) {
        for (int x=0; x<16; x++) for (int z=0; z<16; z++) {
            h.setBlock(new BlockPos(x,0,z), Blocks.STONE);
            for (int y=1; y<5; y++) h.setBlock(new BlockPos(x,y,z), Blocks.AIR);
            h.setBlock(new BlockPos(x,5,z), Blocks.STONE);
        }
    }

    private static BlockPos bedAndHome(GameTestHelper h, Settlement s) {
        BlockPos bed = h.absolutePos(new BlockPos(12,1,8));
        h.setBlock(new BlockPos(12,1,8), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        h.setBlock(new BlockPos(11,1,8), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        BlockPos plaqueRel = new BlockPos(10,2,6); GameTestFixtures.placePlaque(h, plaqueRel);
        Building home = new Building(UUID.randomUUID(), BuildingType.HOUSE, h.absolutePos(plaqueRel), bed,
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(10,0,6)), h.absolutePos(new BlockPos(14,3,10))));
        home.valid = true; home.beds.add(bed); s.buildings.add(home);
        return bed;
    }
}
