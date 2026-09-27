package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuilderUnlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Codex native save (26 Sep): a Development written before tech tree Option 2
 * held QuestBaselines for the Courier-delivery gates Option 2 removed from
 * Fields and Fishery. On load the whole state went into quarantine, the Tech
 * Tree said "records need repair" and the Builder refused its Builder's Hut
 * work. This loads such a save into a real level and checks it is valid and
 * the Builder's unlock goes through. Batch "techtree_save_compat".
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class Option2SaveCompatGameTests {

    @SuppressWarnings("unchecked")
    private static Map<UUID, DevelopmentState> states(GameTestHelper helper) throws ReflectiveOperationException {
        var field = Development.class.getDeclaredField("settlements");
        field.setAccessible(true);
        return (Map<UUID, DevelopmentState>) field.get(Development.get(helper.getLevel()));
    }

    @GameTest(template = "empty16", batch = "techtree_save_compat", timeoutTicks = 100)
    public void aPreOption2SaveWithRetiredGateBaselinesLoadsAndTheBuilderWorks(GameTestHelper helper)
            throws ReflectiveOperationException {
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Stonebridge", hearthAbs);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        ((HearthBlockEntity) helper.getLevel().getBlockEntity(hearthAbs)).bindSettlement(settlement.id);

        // The native save's shape: research learned, Builder's Hut owned,
        // plus the two baselines for gates that no longer exist.
        DevelopmentState old = new DevelopmentState();
        old.unlock(DevelopmentNode.TIMBER_RIGHTS);
        old.unlock(DevelopmentNode.STORES_AND_ROADS);
        old.unlock(DevelopmentNode.CULTIVATED_GROUND);
        old.unlock(DevelopmentNode.SHORE_PROVISIONS);
        old.unlock(DevelopmentNode.FIRST_WATCH);
        old.learnTech(BuilderUnlocks.BUILDERS_HUT);
        old.markInitialized();
        CompoundTag tag = old.writeNbt();
        tag.getCompound("QuestCounters").putInt("CourierDeliveries", 6);
        for (String key : new String[] {"cultivated_ground/courier_deliveries",
                                        "shore_provisions/courier_deliveries"}) {
            CompoundTag row = new CompoundTag();
            row.putString("Key", key);
            row.putInt("Value", 0);
            tag.getList("QuestBaselines", Tag.TAG_COMPOUND).add(row);
        }

        DevelopmentState loaded = DevelopmentState.readNbt(tag);
        helper.assertFalse(loaded.quarantined(), "retired gate baselines must not quarantine the save");
        states(helper).put(settlement.id, loaded);

        DevelopmentState live = Development.of(helper.getLevel(), settlement);
        helper.assertFalse(live.quarantined(), "the live Development is valid");
        helper.assertTrue(live.unlocked(DevelopmentNode.CULTIVATED_GROUND)
                && live.unlocked(DevelopmentNode.SHORE_PROVISIONS)
                && live.unlocked(DevelopmentNode.FIRST_WATCH),
            "earned research is kept");
        helper.assertTrue(BuilderUnlocks.owns(helper.getLevel(), settlement, BuilderUnlocks.BUILDERS_HUT),
            "the Builder's Hut unlock goes through, so Builder upgrade orders are accepted");
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), settlement, BuildingType.BUILDERS_HUT),
            "Builder's Hut plan unlocked");

        // And a reload of what the level would now save stays valid.
        DevelopmentState again = DevelopmentState.readNbt(live.writeNbt());
        helper.assertFalse(again.quarantined(), "stays valid after save and load");
        helper.succeed();
    }
}
