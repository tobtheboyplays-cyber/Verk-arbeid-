package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.registry.ModAttachments;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.StarterHandbookDelivery;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): the very first minutes of a fresh survival world
 * (batches {@code scenario_founding_*}).
 *
 * <ul>
 *   <li>The starter handbook: a real login delivers exactly one, a relog
 *   never delivers a second, a full inventory waits and then delivers once
 *   through the tick retry, and a player already holding one is not given
 *   another.</li>
 *   <li>The first Coins: a really founded village's merchant buys the
 *   player's logs, and those Coins (plus logs and cobble in the pack) buy
 *   Timber Rights from the acting player, exactly once.</li>
 *   <li>A second Banner too close to a village founds nothing.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioFoundingGameTests {

    private static ServerPlayer login(GameTestHelper h, GameProfile profile, int filledSlots) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        for (int i = 0; i < filledSlots; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void logout(GameTestHelper h, ServerPlayer player) {
        var list = h.getLevel().getServer().getPlayerList();
        if (list.getPlayer(player.getUUID()) == player) list.remove(player);
    }

    private static int handbooks(ServerPlayer player) {
        return player.getInventory().countItem(ModItems.HANDBOOK.get());
    }

    // ----------------------------------------------------------- handbook --

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "scenario_founding_handbook")
    public void aFreshPlayerGetsExactlyOneHandbookAndARelogNeverASecond(GameTestHelper h) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "scen-newcomer");
        ServerPlayer first = login(h, profile, 0);
        h.assertTrue(handbooks(first) == 1, "a fresh player's first login delivers one handbook, got " + handbooks(first));
        h.assertTrue(first.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED), "and remembers it");
        logout(h, first);
        ServerPlayer again = login(h, profile, 0);
        h.assertTrue(again.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED),
            "the delivery flag survives the save and relog");
        h.assertTrue(handbooks(again) == 1, "a relog never delivers a second handbook, got " + handbooks(again));
        again.getInventory().clearContent();
        h.assertTrue(StarterHandbookDelivery.deliverIfNeeded(again) == StarterHandbookDelivery.Outcome.ALREADY_DELIVERED
                && handbooks(again) == 0, "a lost handbook is not re-granted (one-time gift)");
        logout(h, again);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "scenario_founding_handbook_full")
    public void aFullInventoryWaitsThenGetsTheHandbookOnce(GameTestHelper h) {
        ServerPlayer player = login(h, new GameProfile(UUID.randomUUID(), "scen-packrat"), 0);
        // A player whose gift is still owed arrives with a full pack.
        player.getInventory().clearContent();
        player.setData(ModAttachments.STARTER_HANDBOOK_DELIVERED, false);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        h.assertTrue(StarterHandbookDelivery.deliverIfNeeded(player) == StarterHandbookDelivery.Outcome.WAITING_FOR_SPACE,
            "a full inventory waits");
        int dropped = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            player.getBoundingBox().inflate(8), e -> e.getItem().is(ModItems.HANDBOOK.get())).size();
        h.assertTrue(handbooks(player) == 0 && dropped == 0 && !player.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED),
            "nothing dropped on the ground, and the flag stays open for the retry");
        player.getInventory().setItem(20, ItemStack.EMPTY);
        for (int i = 0; i < 21; i++) player.doTick(); // the bounded retry runs every 20 ticks
        h.assertTrue(handbooks(player) == 1 && player.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED),
            "once a slot is free the tick retry delivers exactly one, got " + handbooks(player));
        for (int i = 0; i < 41; i++) player.doTick();
        h.assertTrue(handbooks(player) == 1, "and never a second");
        logout(h, player);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "scenario_founding_handbook_existing")
    public void aPlayerWhoAlreadyHasOneIsNotGivenAnother(GameTestHelper h) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "scen-reader"), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        player.getInventory().setItem(3, new ItemStack(ModItems.HANDBOOK.get()));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        h.assertTrue(handbooks(player) == 1 && player.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED),
            "an existing handbook counts as delivered, no copy, got " + handbooks(player));
        logout(h, player);
        h.succeed();
    }

    // -------------------------------------------------------- first Coins --

    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 100, batch = "scenario_founding_first_coins")
    public void theFirstMerchantsCoinsBuyTimberRights(GameTestHelper h) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearthPos = h.absolutePos(new BlockPos(32, 1, 32));
        h.getLevel().setBlockAndUpdate(hearthPos, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(h.getLevel(), hearthPos);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(settlement != null && settlement.mayorId != null, "the Banner founds a village with a Mayor");
        settlement.radius = 8;
        HearthBlockEntity hearth = (HearthBlockEntity) h.getLevel().getBlockEntity(hearthPos);
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(h.getLevel(), settlement);
        h.assertTrue(EarlyCoinMerchant.visit(h.getLevel(), settlement), "the first merchant comes to the new village");
        List<WanderingTrader> traders = h.getLevel().getEntitiesOfClass(WanderingTrader.class,
            new AABB(hearthPos).inflate(40), e -> e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement"));
        h.assertTrue(traders.size() == 1, "exactly one merchant");
        WanderingTrader merchant = traders.get(0);

        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(merchant.getX() + 1, merchant.getY(), merchant.getZ());
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
        GoldCoinTrades.onInteract(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
            player, net.minecraft.world.InteractionHand.MAIN_HAND, merchant));
        MerchantOffer logs = null;
        StringBuilder rows = new StringBuilder();
        for (MerchantOffer offer : merchant.getOffers()) {
            rows.append(offer.getCostA()).append(offer.getItemCostA().components()).append("->")
                .append(offer.getResult()).append(offer.isOutOfStock() ? "(out)" : "").append("; ");
            // The Basic row: the one the player's plain, freshly chopped logs satisfy.
            if (logs == null && offer.getResult().is(ModItems.GOLD_COIN.get()) && !offer.isOutOfStock()
                && offer.satisfiedBy(player.getInventory().getItem(0), ItemStack.EMPTY)) {
                logs = offer;
            }
        }
        h.assertTrue(logs != null, "the merchant buys the plain oak logs a new player chops: " + rows);
        int coinPrice = DevelopmentNode.TIMBER_RIGHTS.costs().get(0).count();
        merchant.setTradingPlayer(player);
        int coins = 0;
        while (coins < coinPrice) {
            h.assertTrue(!logs.isOutOfStock() && logs.take(player.getInventory().getItem(0), ItemStack.EMPTY),
                "a shipment of logs sells (" + coins + " of " + coinPrice + " Coins so far; purse "
                    + GoldCoinTrades.remainingOwnedPurse(merchant) + "; rows " + rows + ")");
            ItemStack paid = logs.assemble();
            int got = paid.getCount(); // Inventory.add empties the stack it is given
            player.getInventory().add(paid);
            coins += got;
            merchant.notifyTrade(logs);
            GoldCoinTrades.finishOwnedPurchase(h.getLevel(), merchant, merchant.getOffers().indexOf(logs), logs);
        }
        merchant.setTradingPlayer(null);
        h.assertTrue(player.getInventory().countItem(ModItems.GOLD_COIN.get()) == coins,
            "the Coins are in the pack: " + coins);
        // The rest of the price from the pack: logs are left over, cobble is mined.
        player.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        int logsBefore = player.getInventory().countItem(Items.OAK_LOG);
        int revision = Development.revisionOf(h.getLevel(), settlement);
        Development.Result result = Development.purchaseNode(h.getLevel(), settlement, hearth,
            DevelopmentNode.TIMBER_RIGHTS, revision, player);
        h.assertTrue(result == Development.Result.APPLIED, "Timber Rights is bought with the merchant's Coins: " + result);
        h.assertTrue(player.getInventory().countItem(ModItems.GOLD_COIN.get()) == coins - coinPrice
                && player.getInventory().countItem(Items.COBBLESTONE) == 0
                && player.getInventory().countItem(Items.OAK_LOG) == logsBefore - 8,
            "exactly the price left the pack");
        h.assertTrue(Development.of(h.getLevel(), settlement).unlocked(DevelopmentNode.TIMBER_RIGHTS)
                && Development.isBuildingUnlocked(h.getLevel(), settlement, com.hearthstead.building.BuildingType.LUMBER_CAMP)
                // Tech tree v3 (techtree-craft): the Builder's Hut is its own node now.
                && !Development.isBuildingUnlocked(h.getLevel(), settlement, com.hearthstead.building.BuildingType.BUILDERS_HUT),
            "Timber Rights grants the Lumber Camp; the Builder's Hut is learned on its own node (builders_hut)");
        merchant.discard();
        for (var member : SettlementManager.loadedMembers(h.getLevel(), settlement)) member.discard();
        SettlementSavedData.get(h.getLevel()).settlements.remove(settlement.id);
        h.getLevel().setBlockAndUpdate(hearthPos, Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    // ------------------------------------------------------------ too close --

    @GameTest(template = "empty32", timeoutTicks = 40, batch = "scenario_founding_too_close")
    public void aSecondBannerBesideAVillageFoundsNothing(GameTestHelper h) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement existing = new Settlement(UUID.randomUUID(), "Firstholm", h.absolutePos(new BlockPos(8, 1, 8)));
        SettlementSavedData.get(h.getLevel()).settlements.put(existing.id, existing);
        BlockPos second = h.absolutePos(new BlockPos(20, 1, 20));
        h.getLevel().setBlockAndUpdate(second, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement founded;
        try {
            SettlementManager.ignoreFoundingDistance = false;
            founded = SettlementManager.tryFound(h.getLevel(), second);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(founded == null, "a Banner inside another village's reach founds nothing");
        h.assertTrue(SettlementManager.loadedMembers(h.getLevel(), existing).isEmpty()
                && SettlementSavedData.get(h.getLevel()).settlements.values().stream()
                    .noneMatch(s -> s.center.equals(second)),
            "and spawns no founders");
        SettlementSavedData.get(h.getLevel()).settlements.remove(existing.id);
        h.getLevel().setBlockAndUpdate(second, Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    // ------------------------------------------------- merchant arrives --

    /** The first merchant comes by itself (the server tick), once a player is near the new village. */
    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 400, batch = "scenario_founding_merchant_arrives")
    public void theFirstMerchantComesByItselfWhenAPlayerIsNear(GameTestHelper h) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearthPos = h.absolutePos(new BlockPos(32, 1, 32));
        h.getLevel().setBlockAndUpdate(hearthPos, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement founded;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            founded = SettlementManager.tryFound(h.getLevel(), hearthPos);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        final Settlement settlement = founded;
        h.assertTrue(settlement != null && settlement.mayorId != null, "a founded village with a Mayor");
        settlement.radius = 8;
        ((HearthBlockEntity) h.getLevel().getBlockEntity(hearthPos)).bindSettlement(settlement.id);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(hearthPos.getX() + 3.5, hearthPos.getY(), hearthPos.getZ() + 0.5);
        h.succeedWhen(() -> {
            List<WanderingTrader> traders = h.getLevel().getEntitiesOfClass(WanderingTrader.class,
                new AABB(hearthPos).inflate(48), e -> e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")
                    && settlement.id.equals(e.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement")));
            h.assertTrue(traders.size() == 1, "exactly one merchant arrives on its own, got " + traders.size());
            traders.get(0).discard();
            for (var member : SettlementManager.loadedMembers(h.getLevel(), settlement)) member.discard();
            SettlementSavedData.get(h.getLevel()).settlements.remove(settlement.id);
            h.getLevel().setBlockAndUpdate(hearthPos, Blocks.AIR.defaultBlockState());
        });
    }
}
