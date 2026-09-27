package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.entity.ai.SettlerPanicGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.defense.AlarmBell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The vanilla bell as the settlement's physical alarm. Own batch; never
 * touches world time. Each test shortens only the alarm deadline itself.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AlarmBellGameTests {
    private static final BlockPos BELL = new BlockPos(3, 1, 3);
    private static final BlockPos BED_HEAD = new BlockPos(12, 1, 8);

    @GameTest(template = "empty16", batch = "alarm_bell", timeoutTicks = 60)
    public static void ringingBellInsideSettlementSetsAlarm(GameTestHelper h) {
        room(h);
        Settlement s = settlement(h);
        long now = h.getLevel().getGameTime();
        BlockPos bell = h.absolutePos(BELL);
        BellBlock block = (BellBlock) Blocks.BELL;
        // A redstone/no-cause ring is not a player alarm.
        block.attemptToRing(h.getLevel(), bell, null);
        h.assertFalse(s.alertActive(now), "A bell rung without a player cause must not raise ALARM");
        Player ringer = h.makeMockPlayer(GameType.SURVIVAL);
        h.assertTrue(block.attemptToRing(ringer, h.getLevel(), bell, null), "fixture: the bell must ring");
        h.assertTrue(s.alertActive(now), "A player ringing a bell inside the settlement must raise ALARM");
        h.assertTrue(s.alertUntilGameTime >= now + AlarmBell.ALARM_TICKS,
            "ALARM lasts the bounded bell duration: " + (s.alertUntilGameTime - now));
        h.assertTrue(bell.equals(s.alertPos), "With no known threat, defenders rally at the bell");
        h.assertTrue(AlarmBell.findBell(h.getLevel(), s) != null, "The rung bell is the settlement's alarm bell");
        // Re-ringing extends; it never shortens.
        long before = s.alertUntilGameTime;
        h.runAfterDelay(5, () -> {
            block.attemptToRing(ringer, h.getLevel(), bell, null);
            h.assertTrue(s.alertUntilGameTime > before, "Re-ringing must extend ALARM");
            cleanup(h, s);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "alarm_bell", timeoutTicks = 400)
    public static void alarmSendsCivilianToShelterAndStopsWork(GameTestHelper h) {
        room(h);
        Settlement s = settlement(h);
        BlockPos bed = home(h, s);
        SettlerEntity civilian = civilian(h, s, bed);
        ringAsPlayer(h);
        h.assertTrue(s.alertActive(h.getLevel().getGameTime()), "fixture: ALARM raised");
        double startDist = civilian.blockPosition().distSqr(bed);
        h.succeedWhen(() -> {
            h.assertTrue(running(civilian, SettlerPanicGoal.class), "Civilian must take shelter during ALARM");
            h.assertTrue(civilian.getActivity() == SettlerActivity.FLEEING, "Civilian must be sheltering");
            h.assertTrue(civilian.getTarget() == null, "Civilian never fights");
            h.assertTrue(!runningWork(civilian), "No work goal may run during ALARM");
            h.assertTrue(civilian.blockPosition().distSqr(bed) < startDist
                    && civilian.blockPosition().distSqr(bed) <= 9.0,
                "Civilian must physically reach its home: at " + civilian.blockPosition().toShortString()
                    + " bed " + bed.toShortString() + " note=" + civilian.routeFailureNote());
            h.assertTrue(civilian.getNavigation().isDone(), "Sheltered civilian waits inside");
            cleanup(h, s);
        });
    }

    @GameTest(template = "empty16", batch = "alarm_bell", timeoutTicks = 200)
    public static void alarmRalliesGuardAlertGoal(GameTestHelper h) {
        room(h);
        Settlement s = settlement(h);
        Building barracks = GameTestFixtures.register(h, s, BuildingType.BARRACKS, 10, 10);
        SettlerEntity guard = h.spawn(ModEntities.SETTLER.get(), new BlockPos(12, 1, 12));
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), "Bell Guard", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), s, barracks, guard).ok(), "fixture: Guard hire");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.setHealth(guard.getMaxHealth());
        ringAsPlayer(h);
        BlockPos bell = h.absolutePos(BELL);
        boolean[] responded = {false};
        h.succeedWhen(() -> {
            responded[0] |= running(guard, GuardRespondToAlertGoal.class);
            h.assertTrue(responded[0], "The Guard's alert goal must activate on the bell ALARM");
            h.assertTrue(guard.blockPosition().distSqr(bell) <= 49.0,
                "The Guard must rally to the bell: at " + guard.blockPosition().toShortString());
            cleanup(h, s);
        });
    }

    @GameTest(template = "empty16", batch = "alarm_bell", timeoutTicks = 500)
    public static void alarmExpiryReleasesShelterAndWorkResumes(GameTestHelper h) {
        room(h);
        Settlement s = settlement(h);
        BlockPos bed = home(h, s);
        SettlerEntity civilian = civilian(h, s, bed);
        ringAsPlayer(h);
        long[] expireAt = {-1};
        // Bug hunter diagnostics for the order/RNG flake (W19a): say where it stuck.
        GameTestTicks.at(h, 480, () -> h.fail("stuck: expireAt=" + expireAt[0]
            + " alarm=" + s.alertActive(h.getLevel().getGameTime())
            + " panic=" + running(civilian, SettlerPanicGoal.class)
            + " act=" + civilian.getActivity() + " sleeping=" + civilian.isSleeping()
            + " pos=" + civilian.blockPosition().toShortString() + " bed=" + bed.toShortString()
            + " distSqr=" + civilian.blockPosition().distSqr(bed)
            + " navDone=" + civilian.getNavigation().isDone() + " route=" + civilian.routeFailureNote()
            + " running=" + civilian.goalSelector.getAvailableGoals().stream()
                .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                .map(g -> g.getGoal().getClass().getSimpleName()).toList()
            + " day=" + h.getLevel().getDayTime() % 24000L));
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            if (expireAt[0] < 0) {
                if (running(civilian, SettlerPanicGoal.class) && civilian.blockPosition().distSqr(bed) <= 9.0
                        && civilian.getNavigation().isDone()) {
                    // Sheltered: shorten only the alarm deadline (world time is untouched).
                    expireAt[0] = now + 20;
                    s.alertUntilGameTime = expireAt[0];
                }
                return;
            }
            if (now < expireAt[0]) {
                h.assertTrue(civilian.getActivity() == SettlerActivity.FLEEING,
                    "Civilian stays sheltered until ALARM ends");
                return;
            }
            if (now >= expireAt[0] + 40) {
                h.assertFalse(s.alertActive(now), "ALARM must have expired");
                h.assertFalse(running(civilian, SettlerPanicGoal.class),
                    "Shelter must release once a bell-only ALARM expires");
                h.assertTrue(civilian.getActivity() != SettlerActivity.FLEEING,
                    "Civilian resumes ordinary activity after ALARM");
                h.assertTrue(civilian.panicShelterUntil() <= now,
                    "A bell-only ALARM must not leave a persisted shelter timer behind");
                cleanup(h, s);
                h.succeed();
            }
        });
    }

    // ---- fixtures ----

    private static void ringAsPlayer(GameTestHelper h) {
        Player ringer = h.makeMockPlayer(GameType.SURVIVAL);
        h.assertTrue(((BellBlock) Blocks.BELL).attemptToRing(ringer, h.getLevel(), h.absolutePos(BELL), null),
            "fixture: the bell must ring");
    }

    private static boolean running(SettlerEntity settler, Class<?> goal) {
        return settler.goalSelector.getAvailableGoals().stream()
            .anyMatch(w -> w.isRunning() && goal.isInstance(w.getGoal()));
    }

    private static boolean runningWork(SettlerEntity settler) {
        return settler.goalSelector.getAvailableGoals().stream()
            .anyMatch(w -> w.isRunning() && w.getGoal().getClass().getSimpleName().endsWith("WorkGoal"));
    }

    private static void room(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            h.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
        }
        h.setBlock(BELL, Blocks.BELL);
    }

    private static Settlement settlement(GameTestHelper h) {
        Settlement s = new Settlement(UUID.randomUUID(), "Elmfield", h.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        return s;
    }

    private static BlockPos home(GameTestHelper h, Settlement s) {
        BlockPos bed = h.absolutePos(BED_HEAD);
        h.setBlock(BED_HEAD, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        h.setBlock(BED_HEAD.west(), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        BlockPos plaqueRel = new BlockPos(10, 2, 6);
        GameTestFixtures.placePlaque(h, plaqueRel);
        Building home = new Building(UUID.randomUUID(), BuildingType.HOUSE, h.absolutePos(plaqueRel), bed,
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(10, 0, 6)), h.absolutePos(new BlockPos(14, 3, 10))));
        home.valid = true;
        home.beds.add(bed);
        s.buildings.add(home);
        return bed;
    }

    private static SettlerEntity civilian(GameTestHelper h, Settlement s, BlockPos bed) {
        SettlerEntity civilian = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 12));
        civilian.bindTo(s.id, s.center);
        s.putRecord(civilian.getUUID(), "Carrier", Profession.NONE);
        civilian.claimBed(bed);
        return civilian;
    }

    private static void cleanup(GameTestHelper h, Settlement s) {
        s.alertUntilGameTime = 0;
        AlarmBell.forget(s.id);
    }
}
