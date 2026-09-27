package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Natural rest -> alarm -> physical equipment -> combat, without forced targets or goals. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherNightRaidGameTests {
    private record Fixture(Settlement village, SettlerEntity archer, Container rack,
                           BlockPos bed, ArcherAttackGoal attack) {}

    @GameTest(batch = "archer_night_real", template = "empty16", timeoutTicks = 600)
    public void alarmWakesRealSleeperWhoFetchesBowAndLooses(GameTestHelper h) {
        Fixture f = fixture(h, 40);
        boolean[] alarm = {false}, enemy = {false};
        GameTestTicks.at(h, 180, () -> h.assertTrue(alarm[0],
            "The production rest goal never entered the real bed: " + state(f)));
        h.onEachTick(() -> {
            if (!alarm[0]) {
                if (!sleeping(f)) return;
                assertBeforeAlarm(h, f);
                alarm[0] = true;
                raiseAlarm(h, f);
                return;
            }
            if (!enemy[0]) {
                if (f.archer.isSleeping() || !f.archer.getMainHandItem().is(Items.BOW)) return;
                h.assertTrue(f.archer.getTarget() == null,
                    "Alarm alone must wake and equip the archer before any enemy exists");
                h.assertTrue(f.rack.getItem(0).isEmpty(), "The bow must leave the physical rack");
                h.assertTrue(bowsInBag(f.archer) == 0,
                    "Equipping must not duplicate the rack bow in the bag");
                // The normal target selector must discover this enemy. Never setTarget.
                var raider = h.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
                raider.setNoAi(true);
                // A real raider always belongs to the settlement it raids; the
                // threat board (RaidThreatBoard.validFor) ignores unassigned ones.
                raider.assign(UUID.randomUUID(), f.village.id,
                    com.hearthstead.settlement.raid.RaidObjective.BLOD, 1.0F, false);
                enemy[0] = true;
            }
            if (f.attack.shotsFired() > 0) {
                h.assertTrue(enemy[0] && !f.archer.isSleeping(), "A real volley must follow wake and target acquisition");
                h.assertTrue(f.rack.getItem(1).getCount() < 16,
                    "The volley must use arrows fetched from the rack");
                h.succeed();
            }
        });
        GameTestTicks.at(h, 580, () -> h.assertTrue(f.attack.shotsFired() > 0,
            "No natural wake/equip/volley before deadline: " + state(f)));
    }

    @GameTest(batch = "archer_night_real", template = "empty16", timeoutTicks = 300)
    public void exhaustedSleeperKeepsRestingThroughAlarmWithoutEnemy(GameTestHelper h) {
        Fixture f = fixture(h, 0);
        boolean[] alarm = {false};
        GameTestTicks.at(h, 180, () -> h.assertTrue(alarm[0],
            "The exhausted archer never entered the real bed: " + state(f)));
        h.onEachTick(() -> {
            if (!alarm[0]) {
                if (!sleeping(f)) return;
                assertBeforeAlarm(h, f);
                h.assertTrue(f.archer.getEnergy() < 12, "Fixture must still be critically exhausted");
                alarm[0] = true;
                raiseAlarm(h, f);
                GameTestTicks.at(h, 60, h::succeed);
            }
            h.assertTrue(f.archer.getEnergy() < 12, "Observation must stay below the critical-rest threshold");
            h.assertTrue(sleeping(f), "Alarm without an enemy must not wake a critically exhausted archer");
            h.assertTrue(f.archer.getTarget() == null, "No live target may bypass the alarm-only exception");
            h.assertTrue(f.archer.getMainHandItem().isEmpty() && f.rack.getItem(0).is(Items.BOW),
                "Rest must keep ownership of movement; the bow stays in its rack");
            h.assertTrue(f.attack.shotsFired() == 0, "Alarm alone cannot produce a volley");
        });
    }

    private static Fixture fixture(GameTestHelper h, float energy) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(18000);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        h.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = new Settlement(UUID.randomUUID(), "Night alarm", h.absolutePos(hearthRel));
        s.radius = 6;
        var data = SettlementSavedData.get(h.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        ((HearthBlockEntity) h.getBlockEntity(hearthRel)).bindSettlement(s.id);
        var tower = GameTestFixtures.register(h, s, BuildingType.WATCHTOWER, 2, 2);
        BlockPos rackRel = new BlockPos(3, 1, 3);
        h.setBlock(rackRel, Blocks.CHEST);
        Container rack = (Container) h.getBlockEntity(rackRel);
        rack.setItem(0, new ItemStack(Items.BOW));
        rack.setItem(1, new ItemStack(Items.ARROW, 16));
        rack.setChanged();
        BlockPos headRel = new BlockPos(6, 1, 6);
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        h.setBlock(headRel.south(), bed.setValue(BedBlock.PART, BedPart.FOOT));
        h.setBlock(headRel, bed.setValue(BedBlock.PART, BedPart.HEAD));
        SettlerEntity archer = h.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 6));
        archer.bindTo(s.id, s.center);
        s.putRecord(archer.getUUID(), "Night archer", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), s, tower, archer).ok(), "Watchtower must hire the fixture archer");
        archer.claimBed(h.absolutePos(headRel));
        archer.setHunger(100);
        archer.setEnergy(energy);
        h.assertTrue(Schedule.shouldSleep(s, archer, archer.dayPhase()), "Archer must be off watch at midnight");
        ArcherAttackGoal attack = archer.goalSelector.getAvailableGoals().stream()
            .map(g -> g.getGoal()).filter(ArcherAttackGoal.class::isInstance)
            .map(ArcherAttackGoal.class::cast).findFirst().orElseThrow();
        return new Fixture(s, archer, rack, h.absolutePos(headRel), attack);
    }

    private static boolean sleeping(Fixture f) {
        return f.archer.isSleeping() && f.archer.getActivity() == SettlerActivity.SLEEPING
            && f.archer.getSleepingPos().filter(f.bed::equals).isPresent();
    }

    private static void assertBeforeAlarm(GameTestHelper h, Fixture f) {
        h.assertTrue(sleeping(f), "Actual sleep in the claimed bed must precede the alarm");
        h.assertTrue(f.archer.getTarget() == null, "Sleeper must start with no target");
        h.assertTrue(f.archer.getMainHandItem().isEmpty() && f.rack.getItem(0).is(Items.BOW),
            "The sleeping archer's bow must still be in the rack");
    }

    private static void raiseAlarm(GameTestHelper h, Fixture f) {
        f.village.alertPos = h.absolutePos(new BlockPos(12, 1, 4));
        f.village.alertUntilGameTime = h.getLevel().getGameTime() + 600;
        SettlementSavedData.get(h.getLevel()).setDirty();
    }

    private static int bowsInBag(SettlerEntity archer) {
        int count = 0;
        for (int i = 0; i < archer.bag.getContainerSize(); i++) {
            if (archer.bag.getItem(i).is(Items.BOW)) count += archer.bag.getItem(i).getCount();
        }
        return count;
    }

    private static String state(Fixture f) {
        return "sleep=" + f.archer.isSleeping() + ", activity=" + f.archer.getActivity()
            + ", energy=" + f.archer.getEnergy() + ", hand=" + f.archer.getMainHandItem()
            + ", target=" + f.archer.getTarget() + ", volleys=" + f.attack.shotsFired()
            // bug hunter diagnostics: who holds MOVE/LOOK, readiness, arrows, alarm
            + ", running=" + f.archer.goalSelector.getAvailableGoals().stream()
                .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                .map(g -> g.getGoal().getClass().getSimpleName()).toList()
            + ", targeting=" + f.archer.targetSelector.getAvailableGoals().stream()
                .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                .map(g -> g.getGoal().getClass().getSimpleName()).toList()
            + ", ready=" + com.hearthstead.settlement.equipment.EquipmentRequests.readyForProfession(
                (net.minecraft.server.level.ServerLevel) f.archer.level(), f.archer, Profession.ARCHER)
            + ", quiver=" + f.attack.quiverCount() + ", rackArrows=" + f.rack.getItem(1).getCount()
            + ", alarm=" + f.village.alertActive(f.archer.level().getGameTime())
            + ", health=" + f.archer.getHealth() + ", route=" + f.archer.routeFailureNote()
            + ", pos=" + f.archer.blockPosition();
    }
}
