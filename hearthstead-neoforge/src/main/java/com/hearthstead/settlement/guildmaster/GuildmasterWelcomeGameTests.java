package com.hearthstead.settlement.guildmaster;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.entity.GuildmasterEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.heraldry.BannerDesignNetwork;
import com.hearthstead.heraldry.VillageDesign;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.StarterKitConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The Guildmaster's welcome (owner, 27 Sep): found, raise the banner, and the
 * Guildmaster gives 4 Coins once per kingdom (into the Banner stores) and an
 * iron tool set to every player once. Batch {@code gm_welcome}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuildmasterWelcomeGameTests {
    private static final String BATCH = "gm_welcome";
    private static final BlockPos LOCAL = new BlockPos(8, 1, 8);
    private static final List<Item> TOOLS = List.of(Items.IRON_PICKAXE, Items.IRON_AXE,
        Items.IRON_SHOVEL, Items.IRON_HOE);

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void raiseWelcomesWithFourCoinsOnceAndToolsForEveryPlayerAcrossReload(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StarterKitConfig.overrideStartCoinsForTests(0);
        com.hearthstead.conversation.ConversationConfig.overrideForTests(true, null);
        HearthBlockEntity banner = floorAndBanner(h);
        Settlement s = null;
        ServerPlayer a = null;
        ServerPlayer b = null;
        try {
            s = found(h, banner.getBlockPos());
            h.assertTrue(s != null, "founding succeeds");
            banner.bindSettlement(s.id);
            h.assertTrue(coins(banner) == 0, "with [start] startCoins = 0 founding itself gives no Coins, got "
                + coins(banner));

            a = h.makeMockServerPlayerInLevel();
            a.getInventory().clearContent();
            a.teleportTo(s.center.getX() + 0.5D, s.center.getY(), s.center.getZ() + 3.5D);
            s.addMember(a.getUUID());
            BannerDesignNetwork.resetForTest(a.getUUID());
            BannerDesignNetwork.Result raised = BannerDesignNetwork.confirm(a, s.center, VillageDesign.FOUNDING);
            h.assertTrue(raised == BannerDesignNetwork.Result.SAVED, "the banner is raised, got " + raised);

            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS,
                "the welcome puts exactly 4 Coins in the Banner (startCoins 0 -> total 4), got " + coins(banner));
            assertTools(h, a, 1, "player A");
            GuildmasterWelcomeData data = GuildmasterWelcomeData.get(level);
            h.assertTrue(data.coinsGiven(s.id) && data.toolsGiven(s.id, a.getUUID()), "the welcome is recorded");
            GuildmasterEntity g = GuildmasterService.live(level, s);
            h.assertTrue(g != null, "the Guildmaster is seated for the welcome");
            h.assertTrue(ConversationService.isTalking(a) && ConversationService.talkerOf(g) != null
                && a.getUUID().equals(ConversationService.talkerOf(g)), "the welcome talk opens with the Guildmaster");
            h.assertTrue(ConversationService.visibleOptionTextsForTest(a).size() == 2,
                "two replies: thanks / show me the emblems, got " + ConversationService.visibleOptionTextsForTest(a));
            h.assertTrue(ConversationService.chooseForTest(a, "thanks"), "A thanks him");
            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "the reply's gift action never repeats the Coins");
            assertTools(h, a, 1, "player A after the reply");
            ConversationService.closeFor(a, null);

            // A raise again (a new design) never repeats anything.
            BannerDesignNetwork.resetForTest(a.getUUID());
            BannerDesignNetwork.confirm(a, s.center, VillageDesign.FOUNDING);
            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "a second raise gives no Coins");
            assertTools(h, a, 1, "player A after a second raise");
            h.assertTrue(!GuildmasterWelcome.onInteract(a, g), "A's next click opens the emblems, not a welcome");

            // Player B (the co-op partner): tools on his first talk, no new Coins.
            b = h.makeMockServerPlayerInLevel();
            b.getInventory().clearContent();
            b.teleportTo(g.getX() + 1.5D, g.getY(), g.getZ() + 1.5D);
            h.assertTrue(GuildmasterWelcome.onInteract(b, g), "B's first talk is his welcome");
            assertTools(h, b, 1, "player B");
            h.assertTrue(ConversationService.isTalking(b), "B's own (tools-only) welcome talk opens");
            h.assertTrue(ConversationService.chooseForTest(b, "emblems"), "B asks for the emblems");
            h.assertTrue(!ConversationService.isTalking(b), "the talk closes before the emblem shop opens");
            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "B's welcome never repeats the Coins");
            h.assertTrue(!GuildmasterWelcome.onInteract(b, g), "B's second click opens the emblems");
            assertTools(h, b, 1, "player B after a second click");
            ConversationService.closeFor(b, null);

            // Save and reload the record: nothing is handed out again.
            CompoundTag saved = data.save(new CompoundTag(), level.registryAccess());
            GuildmasterWelcomeData reloaded = GuildmasterWelcomeData.load(saved, level.registryAccess());
            GuildmasterWelcomeData.replaceForTest(level, reloaded);
            h.assertTrue(GuildmasterWelcomeData.get(level) == reloaded, "the reloaded record is live");
            GuildmasterWelcome.Grant again = GuildmasterWelcome.grant(level, s, a);
            GuildmasterWelcome.Grant againB = GuildmasterWelcome.grant(level, s, b);
            h.assertTrue(!again.any() && !againB.any(), "after a reload nothing is granted again");
            h.assertTrue(!GuildmasterWelcome.onInteract(a, g) && !GuildmasterWelcome.onInteract(b, g),
                "after a reload both players go straight to the emblems");
            BannerDesignNetwork.resetForTest(a.getUUID());
            BannerDesignNetwork.confirm(a, s.center, VillageDesign.FOUNDING);
            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "still exactly 4 Coins after reload + raise");
            assertTools(h, a, 1, "player A after reload");
            assertTools(h, b, 1, "player B after reload");
        } finally {
            cleanup(h, banner, s, a, b);
        }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void fullPackAndFullBannerDropTheGiftInsteadOfDeletingIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StarterKitConfig.overrideStartCoinsForTests(0);
        HearthBlockEntity banner = floorAndBanner(h);
        Settlement s = null;
        ServerPlayer a = null;
        ServerPlayer b = null;
        try {
            s = found(h, banner.getBlockPos());
            h.assertTrue(s != null, "founding succeeds");
            banner.bindSettlement(s.id);
            for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
                banner.getInventory().setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            a = h.makeMockServerPlayerInLevel();
            a.teleportTo(s.center.getX() + 4.5D, s.center.getY(), s.center.getZ() + 4.5D);
            for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
                a.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            }
            b = h.makeMockServerPlayerInLevel();
            b.getInventory().clearContent();
            b.teleportTo(s.center.getX() - 3.5D, s.center.getY(), s.center.getZ() - 3.5D);

            // Both players in the same tick: Coins exactly once, tools to each.
            GuildmasterWelcome.Grant ga = GuildmasterWelcome.grant(level, s, a);
            GuildmasterWelcome.Grant gb = GuildmasterWelcome.grant(level, s, b);
            h.assertTrue(ga.coins() && ga.tools() && !gb.coins() && gb.tools(),
                "first grant gives Coins + tools, the second player only tools: " + ga + " / " + gb);
            h.assertTrue(droppedCoins(level, s.center) == GuildmasterWelcome.COINS && coins(banner) == 0,
                "a full Banner drops the 4 Coins beside it, got " + droppedCoins(level, s.center));
            for (Item tool : TOOLS) {
                h.assertTrue(dropped(level, a, tool) == 1, "a full pack drops " + tool + " at A's feet");
            }
            h.assertTrue(dropped(level, a, Items.BREAD) == GuildmasterWelcome.BREAD,
                "a full pack drops the 16 bread at A's feet, got " + dropped(level, a, Items.BREAD));
            assertTools(h, b, 1, "player B");
            for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
                h.assertTrue(banner.getInventory().getStackInSlot(slot).is(Items.COBBLESTONE)
                    && banner.getInventory().getStackInSlot(slot).getCount() == 64, "Banner stock untouched");
            }
            h.assertTrue(!GuildmasterWelcome.grant(level, s, a).any() && !GuildmasterWelcome.grant(level, s, b).any(),
                "never twice");
        } finally {
            cleanup(h, banner, s, a, b);
        }
        h.succeed();
    }

    /**
     * Co-op shared welcome (shared-conversations lane, 27 Sep): A raises the
     * banner while B is online, so the welcome waits for B; B walks up and
     * joins the same talk and gets his own tools at once; A's reply never
     * repeats anything. Coins once, tools once per player.
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "gm_welcome_shared")
    public void sharedWelcomeGivesEachPlayerToolsAndTheKingdomCoinsOnce(GameTestHelper h) {
        StarterKitConfig.overrideStartCoinsForTests(0);
        com.hearthstead.conversation.ConversationConfig.overrideForTests(true, null);
        com.hearthstead.conversation.ConversationConfig.overrideSharedForTests(true);
        HearthBlockEntity banner = floorAndBanner(h);
        Settlement s = found(h, banner.getBlockPos());
        ServerPlayer a = h.makeMockServerPlayerInLevel();
        ServerPlayer b = h.makeMockServerPlayerInLevel();
        Runnable undo = () -> {
            ConversationService.markClientForTests(b, false);
            com.hearthstead.conversation.ConversationConfig.overrideSharedForTests(null);
            cleanup(h, banner, s, a, b);
        };
        try {
            h.assertTrue(s != null, "founding succeeds");
            banner.bindSettlement(s.id);
            a.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            b.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            a.getInventory().clearContent();
            b.getInventory().clearContent();
            a.teleportTo(s.center.getX() + 0.5D, s.center.getY(), s.center.getZ() + 3.5D);
            b.teleportTo(s.center.getX() + 0.5D, s.center.getY(), s.center.getZ() + 40.5D);
            s.addMember(a.getUUID());
            s.addMember(b.getUUID());
            ConversationService.markClientForTests(b, true);
            BannerDesignNetwork.resetForTest(a.getUUID());
            h.assertTrue(BannerDesignNetwork.confirm(a, s.center, VillageDesign.FOUNDING)
                == BannerDesignNetwork.Result.SAVED, "the banner is raised");
            h.assertTrue(ConversationService.isWaiting(a), "the welcome waits for the online partner");
            h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "4 Coins at the raise");
            assertTools(h, a, 1, "player A");
            assertTools(h, b, 0, "player B before joining");
            GuildmasterEntity g = GuildmasterService.live(h.getLevel(), s);
            h.assertTrue(g != null, "the Guildmaster is seated");
            b.teleportTo(g.getX() + 6.5D, g.getY(), g.getZ() + 0.5D);
        } catch (RuntimeException | AssertionError failure) {
            undo.run();
            throw failure;
        }
        com.hearthstead.gametest.GameTestTicks.at(h, 4, () -> {
            try {
                h.assertTrue(ConversationService.participantIdsOf(a).contains(b.getUUID()), "B joined A's welcome");
                assertTools(h, b, 1, "player B on joining");
                h.assertTrue(ConversationService.chooseForTest(b, "thanks"), "B answers for both");
                h.assertTrue(coins(banner) == GuildmasterWelcome.COINS, "the Coins are never repeated");
                assertTools(h, a, 1, "player A after the shared reply");
                assertTools(h, b, 1, "player B after the shared reply");
            } finally {
                undo.run();
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------ helpers ---

    private static HearthBlockEntity floorAndBanner(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        h.setBlock(LOCAL, ModBlocks.HEARTH.get());
        return (HearthBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(LOCAL));
    }

    private static Settlement found(GameTestHelper h, BlockPos center) {
        boolean previous = SettlementManager.ignoreFoundingDistance;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            return SettlementManager.tryFound(h.getLevel(), center);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
    }

    private static void assertTools(GameTestHelper h, ServerPlayer player, int each, String who) {
        for (Item tool : TOOLS) {
            int n = count(player, tool);
            h.assertTrue(n == each, who + " has " + n + " x " + tool + ", expected " + each);
        }
        int bread = count(player, Items.BREAD);
        h.assertTrue(bread == each * GuildmasterWelcome.BREAD, who + " has " + bread + " bread, expected "
            + each * GuildmasterWelcome.BREAD);
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static int coins(HearthBlockEntity banner) {
        int total = 0;
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
            ItemStack stack = banner.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int droppedCoins(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
            e -> e.getItem().is(ModItems.GOLD_COIN.get())).stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static int dropped(ServerLevel level, ServerPlayer player, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3),
            e -> e.getItem().is(item)).stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static void cleanup(GameTestHelper h, HearthBlockEntity banner, Settlement s,
                                ServerPlayer a, ServerPlayer b) {
        ServerLevel level = h.getLevel();
        StarterKitConfig.overrideStartCoinsForTests(null);
        com.hearthstead.conversation.ConversationConfig.overrideForTests(null, null);
        GuildmasterWelcome.resetTransientForTest();
        for (ServerPlayer p : new ServerPlayer[] {a, b}) {
            if (p != null) {
                ConversationService.closeFor(p, null);
                BannerDesignNetwork.resetForTest(p.getUUID());
            }
        }
        BlockPos center = banner.getBlockPos();
        for (GuildmasterEntity g : level.getEntitiesOfClass(GuildmasterEntity.class, new AABB(center).inflate(12))) {
            g.discard();
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(8))) {
            item.discard();
        }
        if (s != null) {
            for (SettlerEntity founder : SettlementManager.loadedMembers(level, s)) {
                founder.discard();
            }
            GuildmasterRegistry.get(level).unlink(s.id);
            SettlementSavedData.get(level).settlements.remove(s.id);
            SettlementSavedData.get(level).setDirty();
        }
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
            banner.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        level.setBlock(center, Blocks.AIR.defaultBlockState(), 2);
    }
}
