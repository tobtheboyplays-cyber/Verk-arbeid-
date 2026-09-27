package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.TraderWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TownChat;
import com.hearthstead.settlement.work.MerchantCounterCall;
import com.hearthstead.settlement.work.TraderCounter;
import com.hearthstead.settlement.work.TraderDealScene;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * TRADER lane (owner, 26 Sep: "the Trader can have a shop", "the chat should say whether the Trader
 * made a sale"). A real Trading Post with a counter: the Trader stands behind it, the visiting
 * merchant walks up to its front, the 10 s deal plays (TRADING), the sale commits, the goods on
 * the counter are cleared, the Coin reaches the post chest, and the settlement's member hears
 * exactly one sale line while a non-member hears none. A player trading with the merchant always
 * wins; a village without a counter still sells face to face.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TraderCounterGameTests {
    private static final BlockPos COUNTER = new BlockPos(9, 1, 7);
    private static final BlockPos TRADER_CELL = new BlockPos(9, 1, 8);
    private static final BlockPos MERCHANT_CELL = new BlockPos(9, 1, 6);
    private static final BlockPos POST_CHEST = new BlockPos(10, 1, 10);

    private record Heard(UUID player, Component line) {
    }

    private record Fixture(Settlement settlement, Building post, SettlerEntity trader, WanderingTrader merchant,
                           Container chest, ServerPlayer member, ServerPlayer stranger, List<Heard> heard,
                           BiConsumer<ServerPlayer, Component> tap) {
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(rel);
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    private static Fixture fixture(GameTestHelper helper, boolean counter, boolean merchantWalks) {
        var level = helper.getLevel();
        level.setDayTime(2000);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                if (x == 0 || z == 0 || x == 15 || z == 15) {
                    // a glass ring keeps the walking merchant on the test floor
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.GLASS);
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.GLASS);
                }
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Counterholm-" + UUID.randomUUID().toString().substring(0, 6),
            helper.absolutePos(new BlockPos(2, 1, 2)));
        s.radius = 20;
        SettlementSavedData.get(level).settlements.put(s.id, s);
        helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.HEARTH.get());
        ((HearthBlockEntity) level.getBlockEntity(s.center)).bindSettlement(s.id);
        Building post = GameTestFixtures.register(helper, s, BuildingType.TRADING_POST, 8, 8);
        helper.setBlock(POST_CHEST, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(POST_CHEST));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 64));
        if (counter) helper.setBlock(COUNTER, Blocks.CARTOGRAPHY_TABLE);
        TraderCounter.forget(post);

        SettlerEntity trader = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(10, 1, 9));
        trader.setSettlerName("Aldric");
        trader.bindTo(s.id, s.center);
        s.putRecord(trader.getUUID(), "Aldric", Profession.NONE);
        helper.assertTrue(Employment.hire(level, s, post, trader).ok(), "Trader hired normally");

        WanderingTrader merchant = helper.spawn(EntityType.WANDERING_TRADER,
            merchantWalks ? new BlockPos(9, 1, 2) : new BlockPos(11, 1, 6));
        if (!merchantWalks) merchant.setNoAi(true);
        merchant.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", s.id);
        merchant.getPersistentData().putLong("HearthsteadMerchantExpires", level.getGameTime() + 20000);
        merchant.getPersistentData().putBoolean("HearthsteadMerchantArrived", true);
        helper.assertTrue(com.hearthstead.event.GoldCoinTrades.clearOwnedMerchantForPublication(merchant, s.id),
            "visitor published through the real finite-market initializer");

        ServerPlayer member = player(helper, new BlockPos(4, 1, 12));
        ServerPlayer stranger = player(helper, new BlockPos(12, 1, 12));
        s.addMember(member.getUUID());
        List<Heard> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        String town = s.name;
        BiConsumer<ServerPlayer, Component> tap = (to, line) -> {
            if (!line.getSiblings().isEmpty()
                && line.getSiblings().get(0).getContents() instanceof TranslatableContents prefix
                && prefix.getArgs().length > 0 && town.equals(String.valueOf(prefix.getArgs()[0]))
                && (to == member || to == stranger)) {
                heard.add(new Heard(to.getUUID(), line));
            }
        };
        TownChat.addTestTap(tap);
        return new Fixture(s, post, trader, merchant, chest, member, stranger, heard, tap);
    }

    private static void cleanup(GameTestHelper helper, Fixture f) {
        TownChat.removeTestTap(f.tap());
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement().id);
        helper.getLevel().getServer().getPlayerList().remove(f.member());
        helper.getLevel().getServer().getPlayerList().remove(f.stranger());
    }

    /** Trade lines heard by this player whose body key starts with the prefix. */
    private static long lines(Fixture f, ServerPlayer who, String keyPrefix) {
        return f.heard().stream().filter(h -> h.player().equals(who.getUUID())).filter(h -> {
            for (Component part : h.line().getSiblings()) {
                if (part.getContents() instanceof TranslatableContents t && t.getKey().startsWith(keyPrefix)) {
                    return true;
                }
            }
            return false;
        }).count();
    }

    /** Committed sale rounds (TraderSaleService receipts on the Trader). */
    private static int rounds(SettlerEntity trader) {
        return trader.getPersistentData().getList("HearthsteadTraderSaleReceipts", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static List<Display.ItemDisplay> goodsDisplays(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(Display.ItemDisplay.class,
            new AABB(helper.absolutePos(COUNTER)).inflate(3), d -> d.getTags().contains(TraderDealScene.DISPLAY_TAG));
    }

    private static String state(Fixture f) {
        return "stage=" + f.trader().getPersistentData().getCompound(TraderWorkGoal.KEY)
            + " trader=" + f.trader().getActivity() + "@" + f.trader().blockPosition()
            + " merchant@" + f.merchant().blockPosition() + " call=" + f.merchant().getPersistentData().getCompound(MerchantCounterCall.TAG)
            + " postCoins=" + count(f.chest(), ModItems.GOLD_COIN.get()) + " bag=" + f.trader().bag.isEmpty()
            + " heard=" + f.heard().size();
    }

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "trader_counter")
    public void traderSellsAtTheCounterAndOnlyTheMemberHearsIt(GameTestHelper helper) {
        Fixture f = fixture(helper, true, true);
        var spot = TraderCounter.find(helper.getLevel(), f.post());
        helper.assertTrue(spot != null && spot.counter().equals(helper.absolutePos(COUNTER))
                && spot.traderCell().equals(helper.absolutePos(TRADER_CELL))
                && spot.merchantCell().equals(helper.absolutePos(MERCHANT_CELL)),
            "the counter, the Trader's side (storage side) and the merchant's side are found: " + spot);
        boolean[] seen = new boolean[3]; // TRADING, goods on the counter, merchant at the counter while dealing
        helper.onEachTick(() -> {
            if (f.trader().getActivity() == SettlerActivity.TRADING) {
                seen[0] = true;
                if (!goodsDisplays(helper).isEmpty()) seen[1] = true;
                if (TraderCounter.standingAt(f.merchant(), helper.absolutePos(MERCHANT_CELL))
                    && TraderCounter.standingAt(f.trader(), helper.absolutePos(TRADER_CELL))) seen[2] = true;
            }
        });
        helper.succeedWhen(() -> {
            String st = state(f);
            helper.assertTrue(count(f.chest(), ModItems.GOLD_COIN.get()) >= 1, "Coins banked in the post chest; " + st);
            // The Trader keeps trading while stock lasts: one sale line per committed round, never more.
            int rounds = rounds(f.trader());
            helper.assertTrue(rounds >= 1, "at least one round committed; " + st);
            helper.assertTrue(seen[0], "the deal played as TRADING; " + st);
            helper.assertTrue(seen[1], "the goods lay on the counter during the deal; " + st);
            helper.assertTrue(seen[2], "Trader behind, merchant in front of the counter during the deal; " + st);
            helper.assertTrue(f.trader().getActivity() == SettlerActivity.TRADING || goodsDisplays(helper).isEmpty(),
                "no goods display is left behind outside a deal; " + st);
            helper.assertTrue(lines(f, f.member(), "hearthstead.chat.trade.sold") == rounds,
                "the member hears exactly one sale line per round (" + rounds + "); " + st);
            helper.assertTrue(lines(f, f.member(), "hearthstead.chat.trade.") == rounds, "and nothing else; " + st);
            helper.assertTrue(lines(f, f.stranger(), "hearthstead.chat.trade.") == 0, "a non-member hears none; " + st);
            cleanup(helper, f);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "trader_counter")
    public void aPlayerTradingWithTheMerchantAlwaysWins(GameTestHelper helper) {
        Fixture f = fixture(helper, true, true);
        ServerPlayer buyer = player(helper, new BlockPos(9, 1, 3));
        long expires = f.merchant().getPersistentData().getLong("HearthsteadMerchantExpires");
        long[] blockedAt = {-1L};
        BlockPos[] heldAt = {null};
        boolean[] violated = {false};
        String[] why = {null};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            var st = f.trader().getPersistentData().getCompound(TraderWorkGoal.KEY);
            if (blockedAt[0] < 0 && st.hasUUID("Merchant") && "TO_MERCHANT".equals(st.getString("Stage"))) {
                // The Trader wants the counter now: a player steps up beside the merchant (trade reach,
                // as in real play: vanilla TradeWithPlayerGoal only holds him within 4 blocks) and opens
                // his trade screen.
                buyer.setPos(f.merchant().getX() + 1.5D, f.merchant().getY(), f.merchant().getZ());
                f.merchant().setTradingPlayer(buyer);
                blockedAt[0] = now;
                heldAt[0] = f.merchant().blockPosition();
            }
            // two ticks of grace: goals and the Trader re-evaluate on the ticks after the screen opens
            if (blockedAt[0] >= 0 && now - blockedAt[0] >= 2 && now - blockedAt[0] < 300) {
                if (f.trader().getActivity() == SettlerActivity.TRADING) why[0] = "a deal ran at t+" + (now - blockedAt[0]);
                if (f.merchant().blockPosition().distManhattan(heldAt[0]) > 1)
                    why[0] = "merchant moved " + heldAt[0] + " -> " + f.merchant().blockPosition() + " at t+" + (now - blockedAt[0]);
                if (f.merchant().getNavigation().isInProgress()) why[0] = "merchant navigating at t+" + (now - blockedAt[0]);
                violated[0] |= why[0] != null;
            }
            if (blockedAt[0] >= 0 && now - blockedAt[0] == 300) {
                helper.assertTrue(!violated[0], "while a player trades, the merchant is never pulled away and no deal runs: " + why[0]);
                helper.assertTrue(count(f.chest(), ModItems.GOLD_COIN.get()) == 0, "no sale while the player trades");
                helper.assertTrue(f.merchant().getPersistentData().getLong("HearthsteadMerchantExpires") == expires,
                    "the visit timer is untouched");
                f.merchant().setTradingPlayer(null);
            }
        });
        helper.succeedWhen(() -> {
            String st = state(f);
            helper.assertTrue(blockedAt[0] >= 0 && helper.getLevel().getGameTime() - blockedAt[0] > 300,
                "the player's trade came first; " + st);
            helper.assertTrue(count(f.chest(), ModItems.GOLD_COIN.get()) >= 1, "the Trader sells once the player is done; " + st);
            helper.assertTrue(lines(f, f.member(), "hearthstead.chat.trade.sold") == rounds(f.trader()),
                "one sale line per round; " + st);
            helper.assertTrue(f.merchant().getPersistentData().getLong("HearthsteadMerchantExpires") == expires,
                "the visit timer is untouched; " + st);
            helper.getLevel().getServer().getPlayerList().remove(buyer);
            cleanup(helper, f);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "trader_counter")
    public void withoutACounterTheTraderStillSellsFaceToFace(GameTestHelper helper) {
        Fixture f = fixture(helper, false, false);
        helper.assertTrue(TraderCounter.find(helper.getLevel(), f.post()) == null, "no counter in this post");
        boolean[] trading = {false};
        helper.onEachTick(() -> trading[0] |= f.trader().getActivity() == SettlerActivity.TRADING);
        helper.succeedWhen(() -> {
            String st = state(f);
            helper.assertTrue(count(f.chest(), ModItems.GOLD_COIN.get()) >= 1, "Coins banked; " + st);
            helper.assertTrue(trading[0], "the deal scene still plays face to face; " + st);
            helper.assertTrue(goodsDisplays(helper).isEmpty(), "no counter, no display; " + st);
            helper.assertTrue(lines(f, f.member(), "hearthstead.chat.trade.sold") == rounds(f.trader()), "one sale line per round; " + st);
            helper.assertTrue(lines(f, f.stranger(), "hearthstead.chat.trade.") == 0, "none for the stranger; " + st);
            cleanup(helper, f);
        });
    }
}
