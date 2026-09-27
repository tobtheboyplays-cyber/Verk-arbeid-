package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.request.CraftingOrderBook;
import com.hearthstead.settlement.request.CraftingOrderService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Supply-chain audit (batch {@code builder_supply_orders}): a Builder-supply
 * recipe is order-only. A staffed Carpenter with planks never turns them into
 * stairs on its own, and makes oak stairs as soon as a crafting order asks
 * for them (the path the Builder's shortfall now takes).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderSupplyOrderGameTests {

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "builder_supply_orders")
    public void carpenterMakesStairsOnlyWhenAnOrderAsks(GameTestHelper helper) {
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Supplyton",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building carpenter = GameTestFixtures.register(helper, settlement, BuildingType.CARPENTER, 2, 2);
        // B4 (27 Sep): only an unlocked workshop takes crafting orders, so the fixture learns
        // the Carpenter's real tech claimant, exactly as a player must.
        com.hearthstead.settlement.development.TechTreeTestGrants.grantClaimants(
            com.hearthstead.settlement.development.Development.of(helper.getLevel(), settlement), null, BuildingType.CARPENTER);
        Building hut = GameTestFixtures.register(helper, settlement, BuildingType.BUILDERS_HUT, 9, 2);
        BlockPos chestRel = new BlockPos(3, 1, 3);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 12));
        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        worker.setSettlerName("Joiner");
        worker.bindTo(settlement.id, settlement.center);
        settlement.putRecord(worker.getUUID(), "Joiner", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, carpenter, worker).ok(), "hire the Carpenter");

        Production.Recipe idle = Production.ready(helper.getLevel(), carpenter,
            CraftingOrderService.preferredOutput(helper.getLevel(), settlement, carpenter));
        helper.assertTrue(idle == null || !idle.id().startsWith(Production.BUILD_SUPPLY_PREFIX),
            "no order: the bench never picks a Builder-supply recipe (" + (idle == null ? "none" : idle.id()) + ")");

        CraftingOrderBook.OpenDecision order = CraftingOrderService.requestCraft(helper.getLevel(), settlement,
            hut, Items.OAK_STAIRS, 4, CraftingOrderBook.Source.MATERIAL);
        helper.assertTrue(order.result() == CraftingOrderBook.OpenResult.CREATED, "the Builder's order opens: " + order.result());
        helper.assertTrue(CraftingOrderService.preferredOutput(helper.getLevel(), settlement, carpenter) == Items.OAK_STAIRS,
            "the order is routed to the Carpenter");
        Production.Recipe ordered = Production.ready(helper.getLevel(), carpenter, Items.OAK_STAIRS);
        helper.assertTrue(ordered != null && "bp_oak_stairs".equals(ordered.id()),
            "with the order the Carpenter makes oak stairs: " + (ordered == null ? "none" : ordered.id()));
        helper.succeed();
    }
}
