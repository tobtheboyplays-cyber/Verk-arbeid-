package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * captain1 soak (2026-09-26): the Scholar was 0% working, IDLE_IN_WORK at his
 * own lectern. With no Research project affordable he had nothing to do, even
 * while Town+ tech nodes were being studied, and nothing told the player why.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScholarTechStudyGameTests {

    private static SettlerEntity scholarAtStudy(GameTestHelper helper, Settlement[] out) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Lesevik", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 8;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building study = GameTestFixtures.register(helper, s, BuildingType.ARCHITECTS_STUDY, 6, 6);
        SettlerEntity sage = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 7));
        sage.setSettlerName("Sage");
        sage.bindTo(s.id, s.center);
        s.putRecord(sage.getUUID(), "Sage", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, study, sage).ok(), "hire scholar");
        sage.setHunger(100.0F);
        sage.setEnergy(100.0F);
        helper.getLevel().setDayTime(2000);
        out[0] = s;
        return sage;
    }

    /** Nothing to study: the sheet says so instead of silent idling. */
    @GameTest(batch = "scholar_study", template = "empty16", timeoutTicks = 400)
    public void aScholarWithNothingToStudySaysSo(GameTestHelper helper) {
        Settlement[] s = new Settlement[1];
        SettlerEntity sage = scholarAtStudy(helper, s);
        helper.succeedWhen(() -> helper.assertTrue(
            sage.logisticsStopReason() == StopReason.NOTHING_TO_STUDY,
            "idle scholar must show NOTHING_TO_STUDY, stop=" + sage.logisticsStopReason()
                + " route=" + sage.routeFailureNote()));
    }

    /** A tech node under study: the Scholar works it and the study clock moves. */
    @GameTest(batch = "scholar_study", template = "empty16", timeoutTicks = 1200)
    public void aScholarAdvancesATechNodeBeingStudied(GameTestHelper helper) {
        Settlement[] s = new Settlement[1];
        SettlerEntity sage = scholarAtStudy(helper, s);
        DevelopmentState state = Development.of(helper.getLevel(), s[0]);
        String id = "scholar_soak_probe";
        state.startStudy(id, 1_000_000L); // never finishes inside the test
        Development.get(helper.getLevel()).setDirty();
        helper.succeedWhen(() -> {
            long[] progress = Development.of(helper.getLevel(), s[0]).study(id);
            helper.assertTrue(progress != null && progress[1] > 0,
                "a working scholar must advance the study clock, progress="
                    + (progress == null ? "none" : progress[1]) + " act=" + sage.getActivity()
                    + " stop=" + sage.logisticsStopReason());
        });
    }
}
