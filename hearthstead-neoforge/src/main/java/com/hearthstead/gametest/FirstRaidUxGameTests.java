package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.network.HearthNetwork;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidPresentation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Native contracts for the first-warning compass and bounded aftermath. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FirstRaidUxGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "first_raid_warning_compass_matches_actual_form_up")
    public void warningCompassMatchesActualFormUp(GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        BlockPos south = RaidDirector.formUpAt(center, 0.0F, 30);
        BlockPos east = RaidDirector.formUpAt(center, -90.0F, 30);
        BlockPos north = RaidDirector.formUpAt(center, -180.0F, 30);
        helper.assertTrue(south.getZ() > center.getZ()
                && RaidPresentation.Compass.fromApproachDegrees(0.0F)
                    == RaidPresentation.Compass.SOUTH
                && east.getX() > center.getX()
                && RaidPresentation.Compass.fromApproachDegrees(-90.0F)
                    == RaidPresentation.Compass.EAST
                && north.getZ() < center.getZ()
                && RaidPresentation.Compass.fromApproachDegrees(-180.0F)
                    == RaidPresentation.Compass.NORTH,
            "warning compass labels must match the band's actual world bearing");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "first_raid_aftermath_latest_exact_or_closed")
    public void aftermathUsesNewestExactEntryOrCloses(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Ashford",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.raidLog.add(new RaidLogEntry(4L, "Old Captain", "blod",
            true, 0, 1, "uro"));
        settlement.raidLog.add(new RaidLogEntry(7L, "Grimr the Torch",
            "brann", true, 2, 1, "varsel"));
        settlement.blessingState.grantOffer();
        HearthMayorSnapshot.AftermathView exact =
            HearthNetwork.aftermathView(settlement);
        helper.assertTrue(exact.present() && exact.night() == 7L
                && exact.captainName().equals("Grimr the Torch")
                && exact.offerSerial() == 1,
            "the Hearth must project only the newest exact persisted report");

        settlement.raidLog.add(new RaidLogEntry(8L, "Forged", "unknown",
            true, 0, 0, "rolig"));
        helper.assertTrue(!HearthNetwork.aftermathView(settlement).present(),
            "a malformed newest report must close instead of exposing old history");
        helper.succeed();
    }
}
