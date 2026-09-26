package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentNetwork;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Network contract for buying a {@link PostRaidUpgrade} through the Hearth
 * Development action packet ({@code Kind.BUY_UPGRADE}): the server handler
 * is invoked directly with a mock player, and only server truth (Hearth
 * Coins, DevelopmentState revision, TECH snapshot upgrade rows) is asserted.
 * World time is never changed.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PostRaidUpgradeNetworkGameTests {

    /** NeoForge creates holder instances for non-static {@link GameTest}s. */
    public PostRaidUpgradeNetworkGameTests() {
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 100)
    public void satchelBuyActionValidatesActorRevisionAndPaysOnce(
            GameTestHelper helper) {
        BlockPos hearthRelative = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Satchel Network", absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        DevelopmentState state = Development.of(helper.getLevel(), settlement);
        prime(state);
        put(hearth, ModItems.GOLD_COIN.get(), 7);

        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
            player.connection.getConnection());
        player.getInventory().clearContent();
        placeNear(player, absolute);
        PostRaidUpgrade satchel = PostRaidUpgrade.COURIER_SATCHEL;

        DevelopmentSnapshotPayload.UpgradeView before = row(
            DevelopmentNetwork.techSnapshot(player, settlement, hearth), satchel);
        helper.assertTrue(before != null
                && before.statusWireId() == Development.NodeStatus.LOCKED.wireId()
                && Development.Result.FIRST_RAID_REQUIRED.translationKey()
                    .equals(before.reasonKey())
                && before.coinCost() == satchel.coinCost(),
            "before the aftermath the snapshot must show a locked Satchel with its cost: "
                + before);
        int lockedRevision = state.revision();
        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), lockedRevision));
        helper.assertTrue(!state.hasUpgrade(satchel) && coins(hearth) == 7
                && state.revision() == lockedRevision,
            "a pre-aftermath buy action must pay nothing");

        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        int revision = state.revision();
        DevelopmentSnapshotPayload.UpgradeView open = row(
            DevelopmentNetwork.techSnapshot(player, settlement, hearth), satchel);
        helper.assertTrue(open != null
                && open.statusWireId() == Development.NodeStatus.AVAILABLE.wireId()
                && open.reasonKey().isEmpty(),
            "after the aftermath the funded Satchel must be available: " + open);

        // Out of Hearth reach: ignored before any assessment.
        player.setPos(absolute.getX() + 20.5D, absolute.getY() + 1.0D,
            absolute.getZ() + 0.5D);
        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision));
        helper.assertTrue(!state.hasUpgrade(satchel) && coins(hearth) == 7,
            "a distant player must not buy the Satchel");
        placeNear(player, absolute);

        // A player who may not build is read-only.
        player.getAbilities().mayBuild = false;
        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision));
        player.getAbilities().mayBuild = true;
        helper.assertTrue(!state.hasUpgrade(satchel) && coins(hearth) == 7
                && state.revision() == revision,
            "a player without build rights must not buy the Satchel");

        // Stale revision, unknown upgrade id and wrong view all pay nothing.
        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision - 1));
        DevelopmentNetwork.handle(player, buy(settlement, 999, revision));
        DevelopmentNetwork.handle(player, new DevelopmentActionPayload(
            settlement.center, settlement.id, HearthMayorAction.NO_ID,
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.BUY_UPGRADE, satchel.wireId(), revision));
        helper.assertTrue(!state.hasUpgrade(satchel) && coins(hearth) == 7
                && state.revision() == revision,
            "stale, unknown or wrong-view buy actions must pay nothing");

        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision));
        helper.assertTrue(state.hasUpgrade(satchel)
                && Development.hasUpgrade(helper.getLevel(), settlement, satchel)
                && coins(hearth) == 7 - satchel.coinCost()
                && state.revision() == revision + 1,
            "the exact buy action must pay the Satchel once and commit one revision");

        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision));
        DevelopmentNetwork.handle(player, buy(settlement, satchel.wireId(), revision + 1));
        helper.assertTrue(coins(hearth) == 7 - satchel.coinCost()
                && state.revision() == revision + 1,
            "replayed buy actions must never pay twice");

        DevelopmentSnapshotPayload.UpgradeView owned = row(
            DevelopmentNetwork.techSnapshot(player, settlement, hearth), satchel);
        helper.assertTrue(owned != null
                && owned.statusWireId() == Development.NodeStatus.OWNED.wireId(),
            "the snapshot must report the Satchel as owned: " + owned);
        helper.succeed();
    }

    private static DevelopmentActionPayload buy(Settlement settlement, int wireId,
                                                int revision) {
        return new DevelopmentActionPayload(settlement.center, settlement.id,
            HearthMayorAction.NO_ID, DevelopmentActionPayload.View.TECH,
            DevelopmentActionPayload.Kind.BUY_UPGRADE, wireId, revision);
    }

    private static DevelopmentSnapshotPayload.UpgradeView row(
            DevelopmentSnapshotPayload snapshot, PostRaidUpgrade upgrade) {
        for (DevelopmentSnapshotPayload.UpgradeView view : snapshot.upgrades()) {
            if (view.upgradeWireId() == upgrade.wireId()) {
                return view;
            }
        }
        return null;
    }

    private static void placeNear(ServerPlayer player, BlockPos hearth) {
        player.setPos(hearth.getX() + 1.5D, hearth.getY(), hearth.getZ() + 0.5D);
    }

    private static void prime(DevelopmentState state) {
        DevelopmentNode[] trunk = {
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.SHORE_PROVISIONS,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH
        };
        for (DevelopmentNode node : trunk) {
            state.unlock(node);
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            if (hearth.getInventory().getStackInSlot(slot).isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int coins(HearthBlockEntity hearth) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
