package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime proofs for executions. Every batch starts with {@code finisher_}
 * ({@code -Dhearthstead.gametest.batchPrefix=finisher_}).
 *
 * <p>Players are real survival ServerPlayers on an embedded connection;
 * raiders are no-AI dummies so nothing depends on pathing. Requests go
 * through {@link FinisherService#request}, the same path the network
 * handler uses.</p>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = Hearthstead.MODID)
public class FinisherGameTests {
    private static final Map<UUID, Integer> DEATHS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        DEATHS.computeIfPresent(event.getEntity().getUUID(), (k, v) -> v + 1);
    }

    private record Fixture(Settlement settlement, RaiderEntity raider, List<ServerPlayer> players) {
        void cleanup(GameTestHelper h) {
            var list = h.getLevel().getServer().getPlayerList();
            for (ServerPlayer p : players) {
                if (list.getPlayer(p.getUUID()) == p) {
                    list.remove(p);
                }
            }
            SettlementSavedData data = SettlementSavedData.get(h.getLevel());
            data.settlements.remove(settlement.id);
            data.setDirty();
            DEATHS.remove(raider.getUUID());
        }

        ServerPlayer player(int i) {
            return players.get(i);
        }
    }

    /** Raider at (8,1,8); players at the given relative x/z, all looking at it. */
    private static Fixture fixture(GameTestHelper h, RaiderEntity.Variant variant, boolean captain,
                                   double[][] playerSpots) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(h.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Finishholm",
            h.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        RaiderEntity raider = h.spawn(ModEntities.RAIDER.get(), new BlockPos(8, 1, 8));
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, captain);
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0D);
        // Owner rule: finishable only BELOW 10% health (and off balance). 5/60 = 8.3%.
        raider.setHealth(5.0F);
        raider.setNoAi(true);
        DEATHS.put(raider.getUUID(), 0);

        List<ServerPlayer> players = new java.util.ArrayList<>();
        int n = 0;
        for (double[] spot : playerSpots) {
            ServerPlayer p = survivalPlayer(h, "finisher-" + (n++));
            BlockPos abs = h.absolutePos(BlockPos.ZERO);
            double px = abs.getX() + spot[0];
            double pz = abs.getZ() + spot[1];
            double rx = raider.getX();
            double rz = raider.getZ();
            float yaw = FinisherService.yawToward(new net.minecraft.world.phys.Vec3(rx - px, 0, rz - pz));
            p.moveTo(px, abs.getY() + 1, pz, yaw, 10.0F);
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            players.add(p);
        }
        return new Fixture(settlement, raider, players);
    }

    private static ServerPlayer survivalPlayer(GameTestHelper h, String name) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    // ------------------------------------------------------------------ tests

    @GameTest(template = "empty16", timeoutTicks = 120, batch = "finisher_exactly_one_kill")
    public static void executionKillsExactlyOnceOnItsImpactTick(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            h.assertTrue(FinisherService.forceOpenWindow(f.raider()), "fixture: window opens on stagger");
            FinisherService.Result r = FinisherService.request(f.player(0), f.raider());
            h.assertTrue(r == FinisherService.Result.STARTED, "solo request starts: " + r);
            FinisherService.Execution e = FinisherService.executionOf(f.raider());
            h.assertTrue(e != null && e.variant() != null && !e.variant().isDouble(), "a solo move runs");
            h.assertTrue(FinisherService.isExecuting(f.player(0)), "the executor is locked");
            h.assertTrue(FinisherService.request(f.player(0), f.raider()) == FinisherService.Result.BUSY,
                "no second request while locked");
            int impact = e.variant().impactTick();
            int lock = e.variant().lockTicks();
            h.runAfterDelay(impact - 2, () -> {
                h.assertTrue(f.raider().isAlive(), "the victim lives until the impact frame");
                h.assertTrue(DEATHS.get(f.raider().getUUID()) == 0, "no early death");
            });
            h.runAfterDelay(impact + 2, () -> {
                h.assertFalse(f.raider().isAlive(), "the victim dies at the impact frame");
                h.assertTrue(DEATHS.get(f.raider().getUUID()) == 1, "exactly one death at impact");
                h.assertTrue(FinisherService.windowState(f.raider()) == FinishWindowTracker.State.EXECUTED,
                    "each enemy can be finished once");
            });
            h.runAfterDelay(lock + 16, () -> {
                h.assertTrue(DEATHS.getOrDefault(f.raider().getUUID(), 1) == 1,
                    "still exactly one death after the move, the fall and the cheer");
                h.assertFalse(FinisherService.isExecuting(f.player(0)), "the lock ends with the move");
                h.assertTrue(FinisherStats.total(f.player(0)) == 1, "one finisher counted");
                h.assertTrue(FinisherStats.raidCount(f.player(0)) == 1, "raid counter too");
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160, batch = "finisher_executor_untouchable")
    public static void executorAndVictimTakeNoOutsideDamageDuringTheMove(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        // A freshly joined ServerPlayer ignores damage for 60 ticks (vanilla spawn
        // protection); start only after it lapses so both damage checks mean something.
        h.runAfterDelay(65, () -> {
            FinisherService.forceOpenWindow(f.raider());
            h.assertTrue(FinisherService.request(f.player(0), f.raider()).accepted(), "starts");
            h.runAfterDelay(3, () -> {
                ServerPlayer p = f.player(0);
                float before = p.getHealth();
                p.hurt(p.damageSources().mobAttack(f.raider()), 8.0F);
                p.hurt(p.damageSources().generic(), 5.0F);
                h.assertTrue(p.getHealth() == before, "executor is invulnerable during the move: "
                    + before + " -> " + p.getHealth());
                float victimBefore = f.raider().getHealth();
                f.raider().hurt(f.raider().damageSources().generic(), 10.0F);
                f.raider().hurt(f.raider().damageSources().playerAttack(p), 10.0F);
                h.assertTrue(f.raider().getHealth() == victimBefore,
                    "other attackers cannot interrupt or steal the kill");
                h.assertTrue(DEATHS.get(f.raider().getUUID()) == 0, "not dead yet");
            });
            h.runAfterDelay(40, () -> {
                h.assertTrue(DEATHS.getOrDefault(f.raider().getUUID(), 0) == 1, "the execution itself kills once");
                ServerPlayer p = f.player(0);
                float before = p.getHealth();
                p.hurt(p.damageSources().generic(), 2.0F);
                h.assertTrue(p.getHealth() < before, "invulnerability ends with the lock");
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "finisher_window_expiry")
    public static void expiredWindowRefusesTheFinisher(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            h.assertTrue(FinisherService.request(f.player(0), f.raider()) == FinisherService.Result.NO_WINDOW,
                "a healthy, unstaggered raider cannot be finished");
            FinisherService.forceOpenWindow(f.raider());
            h.assertTrue(FinisherService.isFinishable(f.raider()), "open");
            h.runAfterDelay(FinishWindowTracker.WINDOW_TICKS + 3, () -> {
                h.assertFalse(FinisherService.isFinishable(f.raider()), "the window lapsed");
                FinisherService.Result r = FinisherService.request(f.player(0), f.raider());
                h.assertTrue(r == FinisherService.Result.NO_WINDOW, "late press refused: " + r);
                h.assertTrue(f.raider().isAlive(), "nothing died");
                h.assertFalse(FinisherService.isExecuting(f.player(0)), "nobody locked");
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "finisher_reach_los")
    public static void reachAndLineOfSightAreServerValidated(GameTestHelper h) {
        // p0 is 6.5 blocks away; p1 is close but behind a wall.
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false,
            new double[][]{{2.0, 8.5}, {8.5, 5.5}});
        for (int x = 6; x <= 10; x++) {
            for (int y = 1; y <= 3; y++) {
                h.setBlock(new BlockPos(x, y, 7), Blocks.STONE_BRICKS);
            }
        }
        h.runAfterDelay(5, () -> {
            FinisherService.forceOpenWindow(f.raider());
            FinisherService.Result far = FinisherService.request(f.player(0), f.raider());
            h.assertTrue(far == FinisherService.Result.OUT_OF_REACH, "6.5 blocks is out of reach: " + far);
            FinisherService.Result walled = FinisherService.request(f.player(1), f.raider());
            h.assertTrue(walled == FinisherService.Result.NO_LINE_OF_SIGHT, "a wall blocks it: " + walled);
            h.assertTrue(FinisherService.isFinishable(f.raider()), "rejected presses do not use up the window");
            h.assertTrue(f.raider().isAlive(), "and kill nothing");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 140, batch = "finisher_double")
    public static void twoPlayersWithinHalfASecondDoTheDoubleExecution(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.BRUTE, true,
            new double[][]{{6.3, 8.5}, {8.5, 6.3}});
        h.runAfterDelay(5, () -> {
            FinisherService.forceOpenWindow(f.raider());
            FinisherService.Result first = FinisherService.request(f.player(0), f.raider());
            h.assertTrue(first == FinisherService.Result.PENDING_DOUBLE,
                "a second player in reach of a brute captain opens the co-op latch: " + first);
            h.assertTrue(FinisherService.isExecuting(f.player(0)), "the lead steadies (locked)");
            h.runAfterDelay(DoubleFinisherLatch.PAIR_TICKS / 2, () -> {
                FinisherService.Result second = FinisherService.request(f.player(1), f.raider());
                h.assertTrue(second == FinisherService.Result.JOINED_DOUBLE, "partner joins: " + second);
                FinisherService.Execution e = FinisherService.executionOf(f.raider());
                h.assertTrue(e != null && e.variant() == FinisherVariant.DOUBLE_PIN_EXECUTION, "double runs");
                h.assertTrue(e.partner() == f.player(1) && e.lead() == f.player(0), "lead + partner");
                int impact = FinisherVariant.DOUBLE_PIN_EXECUTION.impactTick();
                h.runAfterDelay(impact + 2, () -> {
                    h.assertFalse(f.raider().isAlive(), "the brute captain falls at the impact");
                    h.assertTrue(DEATHS.get(f.raider().getUUID()) == 1, "exactly one death");
                });
                h.runAfterDelay(FinisherVariant.DOUBLE_PIN_EXECUTION.lockTicks() + 16, () -> {
                    h.assertTrue(FinisherStats.doubles(f.player(0)) == 1, "lead counts a double");
                    h.assertTrue(FinisherStats.doubles(f.player(1)) == 1, "partner counts a double");
                    long trophies = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        f.raider().getBoundingBox().inflate(4.0D),
                        i -> i.getItem().is(Items.BLACK_BANNER)).size();
                    h.assertTrue(trophies == 1, "the captain drops its war banner trophy: " + trophies);
                    f.cleanup(h);
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "finisher_double_lapses")
    public static void unansweredDoubleLapsesIntoTheLeadsSoloMove(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.BRUTE, false,
            new double[][]{{6.3, 8.5}, {8.5, 6.3}});
        h.runAfterDelay(5, () -> {
            FinisherService.forceOpenWindow(f.raider());
            h.assertTrue(FinisherService.request(f.player(0), f.raider())
                == FinisherService.Result.PENDING_DOUBLE, "latch opens");
            h.runAfterDelay(DoubleFinisherLatch.PAIR_TICKS + 2, () -> {
                FinisherService.Execution e = FinisherService.executionOf(f.raider());
                h.assertTrue(e != null && e.variant() != null && !e.variant().isDouble()
                    && e.variant().enemies().contains(EnemyClass.BRUTE), "the lead's solo brute move runs");
                h.assertTrue(e.partner() == null, "no partner");
                h.assertFalse(FinisherService.isExecuting(f.player(1)), "the other player stays free");
                FinisherService.Result late = FinisherService.request(f.player(1), f.raider());
                h.assertTrue(late == FinisherService.Result.BUSY, "too late to join: " + late);
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "finisher_rule_low_hp_needs_stagger")
    public static void ninePercentWithoutStaggerCannotBeFinished(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            f.raider().setHealth(60.0F * 0.09F);
            h.runAfterDelay(2, () -> {
                h.assertFalse(FinisherService.isFinishable(f.raider()), "9% HP, balanced: no window");
                FinisherService.Result r = FinisherService.request(f.player(0), f.raider());
                h.assertTrue(r == FinisherService.Result.NO_WINDOW, "9% without a stagger refused: " + r);
                h.assertTrue(f.raider().isAlive(), "nothing died");
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "finisher_rule_stagger_needs_low_hp")
    public static void staggeredAtFifteenPercentCannotBeFinished(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            f.raider().setHealth(60.0F * 0.15F);
            f.raider().stagger(20);
            h.runAfterDelay(2, () -> {
                h.assertTrue(FinisherService.isOffBalance(f.raider()), "the stagger knocked it off balance");
                h.assertFalse(FinisherService.isFinishable(f.raider()), "15% HP: no window");
                FinisherService.Result r = FinisherService.request(f.player(0), f.raider());
                h.assertTrue(r == FinisherService.Result.NO_WINDOW, "staggered at 15% refused: " + r);
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "finisher_rule_both_hold")
    public static void staggeredAtEightPercentCanBeFinished(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            f.raider().setHealth(60.0F * 0.08F);
            f.raider().stagger(20);          // a real stagger, picked up by the server tick
            h.runAfterDelay(2, () -> {
                h.assertTrue(FinisherService.isFinishable(f.raider()), "off balance + 8%: window open");
                FinisherService.Result r = FinisherService.request(f.player(0), f.raider());
                h.assertTrue(r == FinisherService.Result.STARTED, "8% while staggered finishes: " + r);
                f.cleanup(h);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "finisher_guard_gate")
    public static void guardsNeverClaimARaiderAPlayerIsExecuting(GameTestHelper h) {
        Fixture f = fixture(h, RaiderEntity.Variant.SKIRMISHER, false, new double[][]{{6.5, 8.5}});
        h.runAfterDelay(5, () -> {
            FinisherService.forceOpenWindow(f.raider());
            FinisherService.forceGuardRoll(f.raider(), true);
            h.assertTrue(FinisherService.request(f.player(0), f.raider()).accepted(), "player starts");
            h.assertFalse(FinisherService.guardMayClaim(null, f.raider()),
                "a guard may not claim a raider a player is executing");
            h.assertTrue(f.raider().cinematicOpportunityState() == null
                || f.raider().cinematicOpportunityState()
                    == com.hearthstead.entity.combat.CinematicOpportunity.State.CLEARED,
                "any guard cinematic was cleared");
            f.cleanup(h);
            h.succeed();
        });
    }
}
