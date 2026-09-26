package com.hearthstead.conversation.parley;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): every raid parley branch through the real talk
 * path ({@link ConversationService#chooseForTest}), on a real recurring
 * raid, with the consequence asserted (raid over, or band released, enraged
 * or not). Batches {@code scenario_parley_*}.
 *
 * <p>The truce persuasion rolls for real: the level random is seeded so the
 * roll is 0 (below every clamped chance, a sure success) or 99 (above every
 * clamped chance, a sure failure).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioParleyGameTests {

    private record Parley(Settlement settlement, List<RaiderEntity> band, RaiderEntity captain, ServerPlayer player) {
    }

    private static Parley arena(GameTestHelper helper, String name) {
        ConversationConfig.overrideForTests(true, true);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        settlement.raidLifecycle = completedFirst(helper);
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        ServerPlayer player = player(helper, name.toLowerCase(), new BlockPos(8, 1, 8));
        RaidCaptain captainRecord = RaidDirector.pickCaptain(settlement, level.getRandom());
        RaidPlan plan = new RaidPlan(captainRecord.id(), RaidObjective.KORN, 0.0F, 14L);
        helper.assertTrue(settlement.recurringRaidRun.queue(plan), "fixture: queue one recurring raid");
        List<RaiderEntity> band = RaidDirector.startQueuedRecurringRaid(level, settlement);
        helper.assertTrue(!band.isEmpty() && settlement.recurringRaidRun.isActive(), "fixture: the band arrives");
        RaiderEntity captain = band.stream().filter(RaiderEntity::isCaptain).findFirst().orElse(null);
        helper.assertTrue(captain != null, "fixture: the band has a captain");
        helper.assertTrue(RaidParley.tryStart(level, settlement) || RaidParley.holding(settlement.id),
            "a fresh raid with a player near halts for a parley");
        helper.assertTrue(band.stream().allMatch(RaiderEntity::isNoAi), "the whole band holds");
        player.teleportTo(level, captain.getX() + 2.0D, captain.getY(), captain.getZ(), 0.0F, 0.0F);
        return new Parley(settlement, band, captain, player);
    }

    private static RaidLifecycle completedFirst(GameTestHelper helper) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(participant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(participant)
                && lifecycle.completeFirstRaid(false),
            "fixture: one cleanly completed first raid");
        return lifecycle;
    }

    /** A real logged-in survival player (vanilla login path). */
    private static ServerPlayer player(GameTestHelper helper, String name, BlockPos rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(helper.getLevel(), abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
        player.getInventory().clearContent();
        return player;
    }

    private static void cleanup(GameTestHelper helper, Parley p) {
        ConversationService.closeFor(p.player(), null);
        if (helper.getLevel().getServer().getPlayerList().getPlayer(p.player().getUUID()) != null) {
            helper.getLevel().getServer().getPlayerList().remove(p.player());
        }
        for (RaiderEntity raider : p.band()) if (!raider.isRemoved()) raider.discard();
        RaidParley.clearDuelForTests(p.settlement().id);
        SettlementSavedData.get(helper.getLevel()).settlements.remove(p.settlement().id);
        ConversationConfig.overrideForTests(null, null);
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static boolean released(List<RaiderEntity> band) {
        for (RaiderEntity raider : band) {
            if (raider.isAlive() && (raider.isNoAi() || raider.getPersistentData().getBoolean(RaidParley.HOLD_TAG))) {
                return false;
            }
        }
        return true;
    }

    private static boolean enraged(List<RaiderEntity> band) {
        return band.stream().filter(RaiderEntity::isAlive).anyMatch(r -> r.hasEffect(MobEffects.DAMAGE_BOOST));
    }

    private static long seedForRoll(int roll) {
        for (long seed = 1; seed < 100_000; seed++) {
            if (RandomSource.create(seed).nextInt(100) == roll) return seed;
        }
        throw new IllegalStateException("no seed rolls " + roll);
    }

    private static void talk(GameTestHelper helper, Parley p, String option) {
        helper.assertTrue(ConversationService.openBound(p.player(), p.captain(), false), "the captain talks");
        helper.assertTrue(ConversationService.chooseForTest(p.player(), option), option + " is offered");
    }

    /** The raid is over and the band has gone home. */
    private static void assertRaidLeft(GameTestHelper helper, Parley p, String why) {
        helper.assertTrue(!p.settlement().recurringRaidRun.isActive() && p.settlement().pendingRaid == null,
            why + ": the raid is over");
        helper.assertTrue(!RaidParley.holding(p.settlement().id), why + ": no parley holds any more");
    }

    /** The raid goes on: the band is released to fight, enraged or not. */
    private static void assertBandCharges(GameTestHelper helper, Parley p, boolean enraged, String why) {
        helper.assertTrue(p.settlement().recurringRaidRun.isActive(), why + ": the raid is still on");
        helper.assertTrue(!RaidParley.holding(p.settlement().id), why + ": the parley has ended");
        helper.assertTrue(released(p.band()), why + ": every raider is released (no NoAi, no hold tag)");
        helper.assertTrue(enraged(p.band()) == enraged, why + ": enraged=" + enraged);
    }

    // ------------------------------------------------------------- tribute ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_tribute_food")
    public void tributeInFoodIsPaidOnceAndTheRaidLeaves(GameTestHelper helper) {
        Parley p = arena(helper, "Breadholm");
        try {
            int food = RaidParley.tributeFood(p.band().size());
            p.player().getInventory().add(new ItemStack(Items.BREAD, food + 3));
            talk(helper, p, "tribute_food");
            helper.assertTrue(count(p.player(), Items.BREAD) == 3,
                "exactly the food tribute (" + food + ") was paid, left " + count(p.player(), Items.BREAD));
            assertRaidLeft(helper, p, "food tribute");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_tribute_short")
    public void tributeThePlayerCannotAffordChangesNothing(GameTestHelper helper) {
        Parley p = arena(helper, "Pennyless");
        try {
            int coins = RaidParley.tributeCoins(p.band().size());
            p.player().getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), Math.max(0, coins - 1)));
            helper.assertTrue(ConversationService.openBound(p.player(), p.captain(), false), "the captain talks");
            ConversationService.chooseForTest(p.player(), "tribute_coins");
            helper.assertTrue(count(p.player(), ModItems.GOLD_COIN.get()) == Math.max(0, coins - 1),
                "a tribute you cannot afford takes nothing");
            helper.assertTrue(p.settlement().recurringRaidRun.isActive() && RaidParley.holding(p.settlement().id),
                "and the parley still holds");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // --------------------------------------------------------------- truce ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_truce_success")
    public void truceTalkedIntoSuccessEndsTheRaid(GameTestHelper helper) {
        Parley p = arena(helper, "Truceholm");
        try {
            helper.assertTrue(ConversationService.openBound(p.player(), p.captain(), false), "the captain talks");
            helper.getLevel().getRandom().setSeed(seedForRoll(0));
            helper.assertTrue(ConversationService.chooseForTest(p.player(), "truce"), "truce is offered");
            assertRaidLeft(helper, p, "persuaded truce");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_truce_failure")
    public void failedTruceEnragesTheBand(GameTestHelper helper) {
        Parley p = arena(helper, "Angerholm");
        try {
            helper.assertTrue(ConversationService.openBound(p.player(), p.captain(), false), "the captain talks");
            helper.getLevel().getRandom().setSeed(seedForRoll(99));
            helper.assertTrue(ConversationService.chooseForTest(p.player(), "truce"), "truce is offered");
            assertBandCharges(helper, p, true, "failed truce");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // -------------------------------------------------------------- refuse ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_refuse")
    public void refusingReleasesTheBandUnenraged(GameTestHelper helper) {
        Parley p = arena(helper, "Nayholm");
        try {
            talk(helper, p, "refuse");
            assertBandCharges(helper, p, false, "refused");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------- timeout ---

    @GameTest(template = "empty16", timeoutTicks = 1400, batch = "scenario_parley_timeout")
    public void unansweredParleyGrowsImpatientAndCharges(GameTestHelper helper) {
        Parley p = arena(helper, "Waitholm");
        BlockPos home = p.settlement().center;
        p.player().teleportTo(helper.getLevel(), home.getX() + .5, home.getY(), home.getZ() + .5, 0F, 0F);
        long started = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            long waited = helper.getLevel().getGameTime() - started;
            helper.assertTrue(!RaidParley.holding(p.settlement().id), "still holding after " + waited + " ticks");
            helper.assertTrue(waited >= ConversationConfig.parleyTicks() - 5,
                "the band waited the whole parley (" + ConversationConfig.parleyTicks() + "), only " + waited);
            assertBandCharges(helper, p, false, "timed out");
            cleanup(helper, p);
        });
    }

    // ---------------------------------------------------------------- duel ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_duel_won")
    public void duelWonThroughTheTalkSendsTheRaidHome(GameTestHelper helper) {
        Parley p = arena(helper, "Duelwon");
        try {
            talk(helper, p, "duel");
            helper.assertTrue(RaidParley.duelActiveForTests(p.settlement().id), "the duel is on");
            RaiderEntity captain = p.captain();
            captain.setHealth(captain.getMaxHealth() * 0.5F);
            captain.invulnerableTime = 0;
            captain.hurt(captain.damageSources().playerAttack(p.player()), captain.getMaxHealth() * 0.4F);
            helper.assertTrue(List.of(true).equals(RaidParley.duelOutcomesForTests(p.settlement().id)),
                "the captain yields once: " + RaidParley.duelOutcomesForTests(p.settlement().id));
            helper.assertTrue(captain.isAlive() || captain.isRemoved(), "a yield is not a kill");
            assertRaidLeft(helper, p, "duel won");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_duel_lost")
    public void duelLostToTheCaptainsBlowReleasesTheBand(GameTestHelper helper) {
        Parley p = arena(helper, "Duellost");
        talk(helper, p, "duel");
        helper.assertTrue(RaidParley.duelActiveForTests(p.settlement().id), "the duel is on");
        // The band forms up outside the claim, beyond the test's entity-ticking area, so an
        // embedded player standing there is never ticked and its 60-tick spawn protection
        // never runs out (QA-TEST-06). Bring both duelists into the arena, 2 blocks apart
        // as before; the login protection itself is untouched.
        BlockPos ground = helper.absolutePos(new BlockPos(7, 1, 8));
        p.captain().teleportTo(ground.getX() + 0.5D, ground.getY(), ground.getZ() + 0.5D);
        p.player().teleportTo(helper.getLevel(), ground.getX() + 2.5D, ground.getY(), ground.getZ() + 0.5D, 0.0F, 0.0F);
        // Past the fresh player's 60-tick spawn protection, the captain lands a blow that
        // takes the player through the yield line (the real damage path, as in talk_parley_duel).
        // The move itself decides nothing: the duel is still on, with no outcome, before the blow.
        com.hearthstead.gametest.GameTestTicks.at(helper, 64, () -> helper.assertTrue(
            RaidParley.duelActiveForTests(p.settlement().id)
                && RaidParley.duelOutcomesForTests(p.settlement().id).isEmpty(),
            "the duel is still undecided just before the blow: " + RaidParley.duelOutcomesForTests(p.settlement().id)));
        com.hearthstead.gametest.GameTestTicks.at(helper, 65, () -> {
            helper.assertTrue(p.player().tickCount >= 60,
                "fixture: the duelist is ticked, so its spawn protection has run out; tickCount="
                    + p.player().tickCount);
            p.player().invulnerableTime = 0;
            p.player().hurt(p.player().damageSources().mobAttack(p.captain()), p.player().getHealth() - 2.0F);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(List.of(false).equals(RaidParley.duelOutcomesForTests(p.settlement().id)),
                "the captain's blow takes the player to the yield line: " + RaidParley.duelOutcomesForTests(p.settlement().id)
                    + " player hp " + p.player().getHealth());
            helper.assertTrue(p.player().isAlive(), "a yield is not a death");
            assertBandCharges(helper, p, false, "duel lost");
            cleanup(helper, p);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_duel_leash")
    public void walkingAwayFromTheDuelLosesIt(GameTestHelper helper) {
        Parley p = arena(helper, "Runholm");
        talk(helper, p, "duel");
        RaiderEntity captain = p.captain();
        p.player().teleportTo(helper.getLevel(), captain.getX() + RaidParley.DUEL_LEASH + 6, captain.getY(),
            captain.getZ(), 0F, 0F);
        helper.succeedWhen(() -> {
            helper.assertTrue(List.of(false).equals(RaidParley.duelOutcomesForTests(p.settlement().id)),
                "leaving the duel ground is a loss: " + RaidParley.duelOutcomesForTests(p.settlement().id));
            assertBandCharges(helper, p, false, "duel abandoned");
            cleanup(helper, p);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_duel_logout")
    public void duelistLoggingOutMidDuelReleasesTheBand(GameTestHelper helper) {
        Parley p = arena(helper, "Quitholm");
        talk(helper, p, "duel");
        ConversationService.closeFor(p.player(), null);
        helper.getLevel().getServer().getPlayerList().remove(p.player());
        helper.succeedWhen(() -> {
            helper.assertTrue(List.of(false).equals(RaidParley.duelOutcomesForTests(p.settlement().id)),
                "a duelist who leaves loses once: " + RaidParley.duelOutcomesForTests(p.settlement().id));
            assertBandCharges(helper, p, false, "duelist left");
            cleanup(helper, p);
        });
    }

    // -------------------------------------------------------- broken truce ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_strike")
    public void strikingAHoldingRaiderBreaksTheParley(GameTestHelper helper) {
        Parley p = arena(helper, "Sneakholm");
        try {
            RaiderEntity victim = p.band().stream().filter(r -> r != p.captain()).findFirst().orElse(p.captain());
            victim.invulnerableTime = 0;
            victim.hurt(victim.damageSources().playerAttack(p.player()), 1.0F);
            assertBandCharges(helper, p, false, "parley broken by a blow");
        } finally {
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------- restart ---

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_parley_restart")
    public void heldRaidersAreReleasedWhenTheyLoadAfterARestart(GameTestHelper helper) {
        Parley p = arena(helper, "Rebootholm");
        ServerLevel level = helper.getLevel();
        List<RaiderEntity> reloaded = new ArrayList<>();
        try {
            // The server stops: the in-memory parley is gone, the raiders were saved holding.
            RaidParley.clear();
            for (RaiderEntity raider : p.band()) {
                helper.assertTrue(raider.getPersistentData().getBoolean(RaidParley.HOLD_TAG), "saved while holding");
                CompoundTag tag = new CompoundTag();
                helper.assertTrue(raider.save(tag), "the raider saves");
                raider.discard();
                Entity copy = EntityType.loadEntityRecursive(tag, level, e -> e);
                helper.assertTrue(copy instanceof RaiderEntity, "the raider loads");
                helper.assertTrue(level.addFreshEntity(copy), "and joins the world again");
                reloaded.add((RaiderEntity) copy);
            }
            helper.assertTrue(released(reloaded), "every raider frozen by a parley that no longer runs is released on load");
        } finally {
            for (RaiderEntity raider : reloaded) raider.discard();
            cleanup(helper, p);
        }
        helper.succeed();
    }

    // ----------------------------------------------------- switched off --

    /** [features] conversations off on a fresh world: a raid never halts for a parley, the talk opens nothing. */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "scenario_parley_conversations_off")
    public void withConversationsOffARaidNeverHaltsForAParley(GameTestHelper helper) {
        ConversationConfig.overrideForTests(false, true);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Quietholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        settlement.raidLifecycle = completedFirst(helper);
        SettlementSavedData.get(level).settlements.put(settlement.id, settlement);
        ServerPlayer player = player(helper, "quiet-talker", new BlockPos(8, 1, 8));
        List<RaiderEntity> band = List.of();
        try {
            RaidCaptain captainRecord = RaidDirector.pickCaptain(settlement, level.getRandom());
            helper.assertTrue(settlement.recurringRaidRun.queue(new RaidPlan(captainRecord.id(), RaidObjective.KORN,
                0.0F, 14L)), "fixture: queue a recurring raid");
            band = RaidDirector.startQueuedRecurringRaid(level, settlement);
            helper.assertTrue(!band.isEmpty(), "the band arrives");
            helper.assertTrue(!RaidParley.tryStart(level, settlement) && !RaidParley.holding(settlement.id),
                "switched off: no parley starts");
            helper.assertTrue(band.stream().noneMatch(RaiderEntity::isNoAi), "and nobody holds: the raid simply comes");
            RaiderEntity captain = band.stream().filter(RaiderEntity::isCaptain).findFirst().orElse(band.get(0));
            player.teleportTo(level, captain.getX() + 2.0D, captain.getY(), captain.getZ(), 0.0F, 0.0F);
            helper.assertTrue(!ConversationService.openBound(player, captain, false), "no talk window opens");
        } finally {
            ConversationService.closeFor(player, null);
            helper.getLevel().getServer().getPlayerList().remove(player);
            for (RaiderEntity raider : band) raider.discard();
            SettlementSavedData.get(level).settlements.remove(settlement.id);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }
}
