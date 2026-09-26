package com.hearthstead.menu;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Exact physical before/after evidence for the settler's real bag menu. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class SettlerInventoryAuthorityGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "authority_settler_inventory_terminal_only")
    public void committedDirectionsEmitAndNoOpClicksRemainSilent(
            GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Bagproof",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(8, 1, 8));
        settler.setSettlerName("Ledger carrier");
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), settler.getSettlerName(),
            Profession.NONE);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(settler.getX(), settler.getY(), settler.getZ());
        player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        SettlerInventoryMenu menu = new SettlerInventoryMenu(1,
            player.getInventory(), settler);
        int playerHotbarZero = SettlerInventoryMenu.SETTLER_SLOTS + 27;

        menu.clicked(playerHotbarZero, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(player.getInventory().getItem(0).isEmpty()
                && settler.bag.getItem(0).is(Items.COBBLESTONE)
                && settler.bag.getItem(0).getCount() == 5,
            "the server transaction must move the exact physical stack into slot 0");
        helper.assertTrue(menu.transferTelemetryCountForTest() == 1,
            "player-to-settler must emit one terminal fingerprint delta");

        int evidenceAfterDeposit = menu.transferTelemetryCountForTest();
        menu.clicked(playerHotbarZero, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(menu.transferTelemetryCountForTest()
                == evidenceAfterDeposit,
            "shift-clicking the now-empty source must emit no evidence");

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(settler.bag.getItem(0).isEmpty()
                && player.getInventory().countItem(Items.COBBLESTONE) == 5,
            "the reverse server transaction must remove the exact bag stack");
        helper.assertTrue(menu.transferTelemetryCountForTest()
                == evidenceAfterDeposit + 1,
            "settler-to-player must emit one terminal fingerprint delta");

        int evidenceAfterWithdrawal = menu.transferTelemetryCountForTest();
        Settlement.SettlerRecord duplicate = new Settlement.SettlerRecord(
            settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settlement.settlers.add(duplicate);
        player.getInventory().setItem(0, new ItemStack(Items.STONE));
        menu.clicked(playerHotbarZero, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(settler.bag.getItem(0).is(Items.STONE)
                && menu.transferTelemetryCountForTest()
                    == evidenceAfterWithdrawal,
            "a corrupt duplicate roster may move vanilla inventory but may not "
                + "mint authoritative transfer evidence");
        settlement.settlers.remove(duplicate);
        settler.bag.setItem(0, ItemStack.EMPTY);

        for (int slot = 0; slot < SettlerInventoryMenu.SETTLER_SLOTS; slot++) {
            settler.bag.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Items.STONE));
        menu.clicked(playerHotbarZero, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(player.getInventory().getItem(0).is(Items.STONE)
                && menu.transferTelemetryCountForTest()
                    == evidenceAfterWithdrawal,
            "a full-target rollback/no-op must preserve the stack and emit nothing");
        helper.succeed();
    }

    public SettlerInventoryAuthorityGameTests() {
    }
}
