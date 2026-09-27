package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.ambient.AmbientCue;
import com.hearthstead.ambient.BarkLimiter;
import com.hearthstead.ambient.LivingVillage;
import com.hearthstead.ambient.ShelterFromRainGoal;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Living village (ambient life), batch {@code alive_ambient}. The clock is
 * never moved: moments are driven through their public entry points and the
 * rate limiter is exercised with explicit timestamps.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class LivingVillageGameTests {
    private static Settlement settlement(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Lindmere",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.bindTo(settlement.id, settlement.center);
        settler.setNoAi(true);
        return settler;
    }

    @GameTest(batch = "alive_ambient", template = "empty16", timeoutTicks = 40)
    public void cheerAtTheBannerAfterAWonRaid(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        List<SettlerEntity> crowd = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            crowd.add(settler(helper, settlement, 4 + i * 2, 6));
        }
        SettlerEntity sleeper = settler(helper, settlement, 8, 12);
        sleeper.setActivity(SettlerActivity.SLEEPING);

        int cheered = LivingVillage.onRaidHeld(helper.getLevel(), settlement);
        helper.assertTrue(cheered == 5, "every awake settler near the Banner cheers, got " + cheered);
        int lines = 0;
        for (SettlerEntity settler : crowd) {
            helper.assertTrue(LivingVillage.lastCue(settler) == AmbientCue.CHEER, "cheer cue");
            lines += LivingVillage.barksSaid(settler);
        }
        helper.assertTrue(lines >= 1 && lines <= 3, "a few call out, not everyone: " + lines);
        helper.assertTrue(LivingVillage.cuesPlayed(sleeper) == 0, "a sleeper is not woken to cheer");
        helper.succeed();
    }

    @GameTest(batch = "alive_ambient", template = "empty16", timeoutTicks = 40)
    public void barksDoNotSpam(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        List<SettlerEntity> crowd = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SettlerEntity settler = settler(helper, settlement, 4 + i, 8);
            settler.assignProfession(Profession.FARMER);
            settler.setActivity(SettlerActivity.WORK_PLANT); // JOB flavour context
            crowd.add(settler);
        }
        // Eight workers in one spot all trying to talk every second for a minute.
        // The limit is per BarkLimiter.CELL-block area. The test structure can
        // straddle a cell edge (W20: 4 + 4 across two cells), so count per cell.
        long start = helper.getLevel().getGameTime() + 1_000_000L;
        java.util.Map<Long, Integer> saidByCell = new java.util.HashMap<>();
        int said = 0;
        for (long t = start; t < start + 1200L; t += 20L) {
            for (SettlerEntity settler : crowd) {
                if (LivingVillage.everydayLineForTest(helper.getLevel(), settler, t)) {
                    said++;
                    saidByCell.merge(BarkLimiter.cell(settler.getX(), settler.getZ()), 1, Integer::sum);
                }
            }
        }
        long cap = 1200L / BarkLimiter.AREA_GAP + 1;
        helper.assertTrue(said >= 1, "the crowd says something");
        for (var entry : saidByCell.entrySet()) {
            helper.assertTrue(entry.getValue() <= cap, "at most one everyday line per area gap: "
                + entry.getValue() + " > " + cap + " in cell " + entry.getKey());
        }
        helper.assertTrue(said < crowd.size() || saidByCell.size() > 1,
            "in one area the limiter must hold some of the eight back");
        for (SettlerEntity settler : crowd) {
            helper.assertTrue(LivingVillage.barksSaid(settler) <= 1,
                "no settler repeats its job line within a minute");
        }
        helper.succeed();
    }

    @GameTest(batch = "alive_ambient", template = "empty16", timeoutTicks = 60)
    public void workTimeIsNotTouched(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        SettlerEntity worker = settler(helper, settlement, 8, 8);
        worker.assignProfession(Profession.FARMER);
        worker.setActivity(SettlerActivity.WORK_PLANT);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absolutePos(new BlockPos(10, 1, 8)).getCenter());
        Vec3 before = worker.position();
        boolean navDone = worker.getNavigation().isDone();

        long now = helper.getLevel().getGameTime();
        for (int i = 0; i < 30; i++) {
            LivingVillage.passForTest(helper.getLevel(), player, now + i * 20L);
        }
        helper.assertTrue(worker.getActivity() == SettlerActivity.WORK_PLANT,
            "ambient life never changes a worker's activity");
        helper.assertTrue(worker.getNavigation().isDone() == navDone, "nor its navigation");
        helper.assertTrue(worker.position().distanceToSqr(before) < 1.0E-6D, "nor moves it");
        helper.assertTrue(LivingVillage.cuesPlayed(worker) >= 1, "the player was greeted");
        helper.assertTrue(LivingVillage.lastCue(worker) != AmbientCue.WAVE,
            "busy hands greet with a nod, never a wave");
        helper.assertFalse(ShelterFromRainGoal.eligible(worker, now),
            "the rain shelter never takes a working settler");
        worker.setActivity(SettlerActivity.IDLE);
        helper.assertTrue(ShelterFromRainGoal.eligible(worker, now),
            "control: the same settler idle may step out of the rain");
        helper.succeed();
    }

    @GameTest(batch = "alive_ambient", template = "empty16", timeoutTicks = 40)
    public void theFallenAreMournedBriefly(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        SettlerEntity first = settler(helper, settlement, 6, 8);
        SettlerEntity second = settler(helper, settlement, 10, 8);
        int mourners = LivingVillage.mourn(helper.getLevel(), settlement, "Wilmot",
            helper.absolutePos(new BlockPos(8, 1, 8)).getCenter(), helper.getLevel().getGameTime());
        helper.assertTrue(mourners == 2, "both nearby settlers bow their heads: " + mourners);
        helper.assertTrue(LivingVillage.lastCue(first) == AmbientCue.MOURN
            && LivingVillage.lastCue(second) == AmbientCue.MOURN, "mourn cue");
        helper.assertTrue(LivingVillage.barksSaid(first) + LivingVillage.barksSaid(second) <= 1,
            "at most one quiet line");
        helper.succeed();
    }
}
