package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Native regressions for the server-authored Mayor roster projection. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HearthMayorRosterGameTests {

    /**
     * A weaker first resident must remain first in the snapshot. Reintroducing
     * a hidden knack sort would reverse this deliberately adversarial fixture.
     */
    @GameTest(batch = "hearth_mayor_roster_resident_order",
        template = "empty16", timeoutTicks = 200)
    public void snapshotPreservesResidentOrderInsteadOfRankingByKnack(
            GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        SettlerEntity low = settler(helper, settlement, "Low First", 5, 5);
        SettlerEntity high = settler(helper, settlement, "High Second", 6, 5);

        Attribute lowKnack = low.attributes().knack();
        Attribute highKnack = high.attributes().knack();
        low.attributes().pinForTest(lowKnack, 5);
        high.attributes().pinForTest(highKnack, 90);

        HearthMayorSnapshot snapshot = HearthNetwork.snapshot(
            helper.getLevel(), settlement);
        List<HearthMayorSnapshot.Candidate> candidates = snapshot.candidates();

        helper.assertTrue(candidates.size() == 2,
            "fixture must project exactly its two eligible residents, got "
                + candidates.size());
        helper.assertTrue(candidates.get(0).id().equals(low.getUUID())
                && candidates.get(1).id().equals(high.getUUID()),
            "snapshot must retain settlement resident order even when the first "
                + "resident has lower knack");
        helper.assertTrue(candidates.get(0).knack() == 5
                && candidates.get(1).knack() == 90,
            "fixture must reach the production projection as low-then-high, got "
                + candidates.get(0).knack() + " then "
                + candidates.get(1).knack());
        helper.assertTrue(candidates.get(0).knack()
                < candidates.get(1).knack(),
            "fixture must be adversarial to descending knack ranking");
        List<HearthMayorSnapshot.Resident> residents = snapshot.residents();
        helper.assertTrue(residents.size() == 2
                && residents.get(0).id().equals(low.getUUID())
                && residents.get(1).id().equals(high.getUUID()),
            "People projection must preserve the exact persisted member order");
        helper.assertTrue(residents.stream().allMatch(HearthMayorSnapshot.Resident::loaded)
                && residents.stream().allMatch(row -> row.runtimeEntityId() >= 0)
                && residents.stream().allMatch(row -> "idle".equals(row.statusKey())),
            "loaded residents must expose their live activity and runtime id, not invented facts");
        UUID absent = UUID.randomUUID();
        settlement.putRecord(absent, "Away Record", Profession.FARMER);
        SettlerEntity foreign = settler(helper, settlement, "Foreign Binding", 7, 5);
        foreign.bindTo(UUID.randomUUID(), helper.absolutePos(new BlockPos(7, 1, 5)));
        HearthMayorSnapshot refreshed = HearthNetwork.snapshot(helper.getLevel(), settlement);
        HearthMayorSnapshot.Resident away = refreshed.residents().get(2);
        HearthMayorSnapshot.Resident foreignRow = refreshed.residents().get(3);
        helper.assertTrue(refreshed.residentTotal() == 4
                && away.id().equals(absent) && !away.loaded() && away.runtimeEntityId() == -1
                && "Away Record".equals(away.name())
                && "FARMER".equals(away.professionId())
                && "unloaded".equals(away.statusKey()),
            "unloaded records must be shown from persisted facts without loading an entity");
        helper.assertTrue(foreignRow.id().equals(foreign.getUUID()) && !foreignRow.loaded()
                && foreignRow.runtimeEntityId() == -1
                && "Foreign Binding".equals(foreignRow.name()),
            "a recorded UUID bound to another settlement must not leak live identity or activity");
        helper.succeed();
    }

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Roster Order", helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 6;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper,
                                         Settlement settlement,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }
}
