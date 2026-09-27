package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.HomeTier;
import com.hearthstead.network.PlaqueNetwork;
import com.hearthstead.network.PlaqueSnapshot;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.techtree.effects.CommonsEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Home tiers on the House plaque (batch {@code techtree_commons}): the plaque's tier
 * line follows BOTH the House's checklist level and the learned Commons nodes
 * through the real tech-tree state, and the Manors morale reads the Manor
 * tier. Synchronous; never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HomeTierGameTests {

    private static final String BATCH = "techtree_commons";

    public HomeTierGameTests() {
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void housePlaqueNamesItsTierFromLevelAndLearnedNodes(GameTestHelper helper) {
        Fixture f = fixture(helper, "Tiers");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.HOME);
        Building house = home(helper, f.settlement, BuildingType.HOUSE, 1);
        try {
            expect(helper, f, house, "a bare House, nothing learned",
                "home_tier.hut", "home_tier_next_learn.cottage");
            house.level = 2;
            expect(helper, f, house, "a level-2 room ahead of the tree",
                "home_tier.hut", "home_tier_learn.cottage");
            state.unlockUpgrade(PostRaidUpgrade.STURDY_BEDS);
            expect(helper, f, house, "Cottages learned",
                "home_tier.cottage", "home_tier_next_learn.townhouse");
            state.learnTech("two_storey_houses");
            expect(helper, f, house, "Townhouses learned, room still level 2",
                "home_tier.cottage", "home_tier_next.townhouse");
            house.level = 3;
            expect(helper, f, house, "a level-3 House with Townhouses",
                "home_tier.townhouse", "home_tier_next_learn.manor");
            house.level = 4;
            expect(helper, f, house, "a level-4 room before Manors",
                "home_tier.townhouse", "home_tier_learn.manor");
            state.learnTech("manors");
            expect(helper, f, house, "the top tier", "home_tier.manor");
            Building lodging = home(helper, f.settlement, BuildingType.LODGING, 3);
            helper.assertTrue(PlaqueNetwork.homeTierLines(helper.getLevel(), f.settlement, lodging).isEmpty(),
                "a Lodging has no home tier");
        } finally {
            f.settlement.buildings.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void manorsMoraleNeedsARealManor(GameTestHelper helper) {
        Fixture f = fixture(helper, "Manor");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.HOME);
        state.unlockUpgrade(PostRaidUpgrade.STURDY_BEDS);
        state.learnTech("two_storey_houses");
        state.learnTech("manors");
        Building house = home(helper, f.settlement, BuildingType.HOUSE, 3);
        Building lodging = home(helper, f.settlement, BuildingType.LODGING, 3);
        BlockPos houseBed = house.beds.iterator().next();
        BlockPos lodgingBed = lodging.beds.iterator().next();
        try {
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, houseBed)
                    == CommonsEffects.COTTAGE_MORALE,
                "a Townhouse (House level 3) gets the Cottage lift only");
            house.level = HomeTier.MANOR.level();
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, houseBed)
                    == CommonsEffects.COTTAGE_MORALE + CommonsEffects.MANOR_MORALE,
                "a Manor adds the Manors lift");
            helper.assertTrue(CommonsEffects.homeMorale(helper.getLevel(), f.settlement, lodgingBed)
                    == CommonsEffects.COTTAGE_MORALE + CommonsEffects.MANOR_MORALE,
                "a level-3 Lodging (its top level) keeps the Manors lift");
        } finally {
            f.settlement.buildings.clear();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    private static void expect(GameTestHelper helper, Fixture f, Building house, String when, String... ids) {
        List<String> got = PlaqueNetwork.homeTierLines(helper.getLevel(), f.settlement, house).stream()
            .map(PlaqueSnapshot.RequirementLine::id).toList();
        helper.assertTrue(got.equals(List.of(ids)), when + ": expected " + List.of(ids) + " but plaque says " + got);
    }

    private static Building home(GameTestHelper helper, Settlement settlement, BuildingType type, int level) {
        int offset = settlement.buildings.size() * 5;
        BlockPos anchor = helper.absolutePos(new BlockPos(6 + offset, 1, 8));
        Building building = new Building(UUID.randomUUID(), type, anchor.above(), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        building.level = level;
        building.beds.add(anchor.offset(1, 0, 1));
        settlement.buildings.add(building);
        return building;
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Home tiers " + name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
