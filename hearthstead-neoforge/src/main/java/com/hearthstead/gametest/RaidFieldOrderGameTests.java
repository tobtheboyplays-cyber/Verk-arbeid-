package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owner, 27 Sep: "do the commands work in a raid?". The in-raid field commands,
 * end to end on the server, with real raiders on the field and the settlement's
 * raid alarm ringing (the state {@code FieldOrders.raidActive} reads):
 * <ol>
 *   <li>HOLD_FIRE: a ready, armed Archer looses nothing at raiders in range;</li>
 *   <li>FIRE_AT_WILL: the same Archer engages and actually hits a raider;</li>
 *   <li>FOLLOW: both Guards escort the commander when the commander moves and
 *       keep about five blocks from him (owner, 27 Sep: "follow me at 5 blocks");</li>
 *   <li>focus (ATTACK on a marked raider): Guards take exactly the marked raider,
 *       not a nearer decoy;</li>
 *   <li>owner, 27 Sep: when the marked raider goes down they KEEP ATTACKING: the
 *       order stays an attack order and every Guard takes the next live raider,
 *       never reverting to FOLLOW and never idling, until a new order;</li>
 *   <li>a new FOLLOW order ends that attack mode at once;</li>
 *   <li>when the raid ends, an attack-mode order lapses after the grace period
 *       and the Guards go back to their posts.</li>
 * </ol>
 * Orders are sent as the R (MELEE) and G (RANGED) keys send them. A second test
 * checks that "back to posts" also ends attack mode.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RaidFieldOrderGameTests {

    private enum Phase { READY, LINE, HOLD, FIRE, FOLLOW, FOCUS, ENGAGE, FOLLOW2, FOCUS2, ENGAGE2, RAID_OVER, DONE }

    /** Horizontal distance: the escort ring is flat, the commander may stand a block higher. */
    private static double flat(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @GameTest(template = "empty32", timeoutTicks = 5500, batch = "raid_field_orders")
    public void fieldOrdersWorkDuringALiveRaid(GameTestHelper helper) {
        FieldOrders.resetForTests(helper.getLevel().getServer());
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        helper.getLevel().setDayTime(1000); // daytime: nobody is asleep on a rota
        Settlement s = new Settlement(UUID.randomUUID(), "Raidholm", helper.absolutePos(new BlockPos(16, 1, 16)));
        s.radius = 14;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        Building barracks = GameTestFixtures.register(helper, s, BuildingType.BARRACKS, 1, 1);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 22);
        helper.setBlock(new BlockPos(3, 1, 23), Blocks.CHEST);
        Container rack = (Container) helper.getBlockEntity(new BlockPos(3, 1, 23));
        rack.setItem(0, new ItemStack(Items.ARROW, 64));

        SettlerEntity guardA = soldier(helper, s, barracks, "Aldric", 14, 8);
        SettlerEntity guardB = soldier(helper, s, barracks, "Bryn", 17, 8);
        for (SettlerEntity g : List.of(guardA, guardB)) {
            g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            g.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
        SettlerEntity archer = soldier(helper, s, tower, "Skytte", 4, 20);
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        archer.bag.setItem(0, new ItemStack(Items.ARROW, 64));
        helper.assertTrue(guardA.getProfession() == Profession.GUARD && archer.getProfession() == Profession.ARCHER,
            "fixture: two Guards and one Archer are employed");
        ArcherAttackGoal bowGoal = archerGoal(archer);

        ServerPlayer commander = commander(helper, helper.absolutePos(new BlockPos(16, 1, 14)));
        s.alertUntilGameTime = helper.getLevel().getGameTime() + 100_000L; // the raid alarm is ringing
        List<RaiderEntity> raiders = new ArrayList<>();

        long[] mark = {0};
        int[] shotsAtHold = {0};
        Phase[] phase = {Phase.READY};
        RaiderEntity[] marked = {null};
        RaiderEntity[] decoy = {null};
        boolean[] engaged = {false, false};
        helper.onEachTick(() -> {
            if (phase[0] == Phase.DONE) return;
            long t = helper.getTick();
            var level = helper.getLevel();
            switch (phase[0]) {
                case READY -> {
                    boolean ready = EquipmentRequests.readyForProfession(level, archer, Profession.ARCHER)
                        && EquipmentRequests.readyForProfession(level, guardA, Profession.GUARD)
                        && EquipmentRequests.readyForProfession(level, guardB, Profession.GUARD);
                    if (!ready) {
                        helper.assertTrue(t < 200, "soldiers never became combat-ready");
                        return;
                    }
                    // The Guards hold a shield line by the barracks, away from the raiders,
                    // so every hit in the HOLD/FIRE phases is the Archer's own.
                    FieldOrders.Result line = FieldOrders.issue(commander, request(Group.MELEE, Kind.LINE,
                        helper.absolutePos(new BlockPos(15, 1, 5)), 4, 2, -1));
                    helper.assertTrue(line.accepted() && line.heard() == 2, "LINE heard by both Guards: "
                        + line.refusal() + " " + line.heard());
                    mark[0] = t;
                    phase[0] = Phase.LINE;
                }
                case LINE -> {
                    if (t - mark[0] < 6) return; // the per-player order rate limit is 4 ticks
                    // Raiders arrive in bow range; the order is given the same tick.
                    raiders.add(raider(helper, s, 9, 28));
                    raiders.add(raider(helper, s, 12, 29));
                    FieldOrders.Result hold = FieldOrders.issue(commander,
                        request(Group.RANGED, Kind.HOLD_FIRE, null, 0, 1, -1));
                    helper.assertTrue(hold.accepted() && hold.heard() == 1,
                        "HOLD_FIRE heard by the Archer: " + hold.refusal() + " " + hold.heard());
                    FieldOrders.Assignment a = FieldOrders.assignment(archer);
                    helper.assertTrue(a != null && a.holdFire(), "the Archer holds fire");
                    for (RaiderEntity r : raiders) {
                        helper.assertTrue(!FieldOrders.allowsTarget(archer, r), "a held Archer may not pick a raider in range");
                    }
                    shotsAtHold[0] = bowGoal.shotsFired();
                    mark[0] = t;
                    phase[0] = Phase.HOLD;
                }
                case HOLD -> {
                    helper.assertTrue(bowGoal.shotsFired() == shotsAtHold[0], "an Archer on HOLD_FIRE loosed an arrow");
                    for (RaiderEntity r : raiders) {
                        helper.assertTrue(r.getLastHurtByMob() != archer, "a raider was hurt by the Archer while it held fire");
                        helper.assertTrue(archer.getTarget() != r || archer.distanceToSqr(r) <= 9.0D,
                            "a held Archer took a raider beyond self-defence reach as its target");
                    }
                    if (t - mark[0] < 160) return;
                    FieldOrders.Result fire = FieldOrders.issue(commander,
                        request(Group.RANGED, Kind.FIRE_AT_WILL, null, 0, 1, -1));
                    helper.assertTrue(fire.accepted(), "FIRE_AT_WILL accepted: " + fire.refusal());
                    FieldOrders.Assignment a = FieldOrders.assignment(archer);
                    helper.assertTrue(a == null || !a.holdFire(), "fire at will lifts the hold");
                    mark[0] = t;
                    phase[0] = Phase.FIRE;
                }
                case FIRE -> {
                    boolean hit = false;
                    for (RaiderEntity r : raiders) hit |= r.getLastHurtByMob() == archer;
                    if (!(hit && bowGoal.shotsFired() > shotsAtHold[0])) {
                        helper.assertTrue(t - mark[0] < 600, "FIRE_AT_WILL: the Archer never hit a raider (shots "
                            + bowGoal.shotsFired() + ", target " + archer.getTarget() + ")");
                        return;
                    }
                    FieldOrders.Result follow = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.FOLLOW, commander.blockPosition(), 0, 1, -1));
                    helper.assertTrue(follow.accepted() && follow.heard() == 2,
                        "FOLLOW heard by both Guards: " + follow.refusal() + " " + follow.heard());
                    // The commander walks off east; the escort must come along. (x 24: the
                    // five-block ring must stay on the 32x32 floor.)
                    BlockPos east = helper.absolutePos(new BlockPos(24, 1, 12));
                    commander.setPos(east.getX() + 0.5D, east.getY(), east.getZ() + 0.5D);
                    mark[0] = t;
                    phase[0] = Phase.FOLLOW;
                }
                case FOLLOW -> {
                    boolean near = true;
                    for (SettlerEntity g : List.of(guardA, guardB)) {
                        FieldOrders.Assignment a = FieldOrders.assignment(g);
                        helper.assertTrue(a != null && a.order.kind == Kind.FOLLOW,
                            g.getSettlerName() + " dropped the FOLLOW order");
                        double slot = Math.sqrt(a.slot().distToCenterSqr(commander.getX(), a.slot().getY() + 0.5D,
                            commander.getZ()));
                        near &= slot >= 3.5D && slot <= 6.5D; // the ring slot itself is ~5 blocks out
                        double d = flat(g, commander);
                        near &= d >= 3.0D && d <= 7.0D; // and the Guard keeps about 5 blocks
                    }
                    if (!near) {
                        helper.assertTrue(t - mark[0] < 500, "FOLLOW: the Guards did not keep ~5 blocks from the "
                            + "commander: " + String.format("%.1f / %.1f", flat(guardA, commander),
                                flat(guardB, commander)) + " at " + guardA.blockPosition() + " / "
                            + guardB.blockPosition() + " vs " + commander.blockPosition());
                        return;
                    }
                    // A decoy close to the escort and a marked raider further off.
                    decoy[0] = raider(helper, s, 21, 17);
                    marked[0] = raider(helper, s, 27, 24);
                    FieldOrders.Result focus = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.ATTACK, null, 0, 1, marked[0].getId()));
                    helper.assertTrue(focus.accepted() && focus.heard() == 2, "focus accepted: " + focus.refusal());
                    for (SettlerEntity g : List.of(guardA, guardB)) {
                        FieldOrders.Assignment a = FieldOrders.assignment(g);
                        helper.assertTrue(a != null && a.order.kind == Kind.ATTACK, "focus assigned");
                        helper.assertTrue(g.getTarget() == marked[0], g.getSettlerName() + " targets the marked raider");
                        helper.assertTrue(FieldOrders.allowsTarget(g, marked[0]), "marked raider allowed");
                        helper.assertTrue(g.distanceToSqr(decoy[0]) <= 9.0D || !FieldOrders.allowsTarget(g, decoy[0]),
                            "the unmarked decoy is off limits beyond self-defence reach");
                    }
                    mark[0] = t;
                    phase[0] = Phase.FOCUS;
                }
                case FOCUS -> {
                    for (SettlerEntity g : List.of(guardA, guardB)) {
                        helper.assertTrue(g.getTarget() == null || g.getTarget() == marked[0]
                                || g.distanceToSqr(g.getTarget()) <= 9.0D,
                            g.getSettlerName() + " drifted to another target: " + g.getTarget());
                    }
                    if (t - mark[0] < 40) return;
                    marked[0].kill(); // the marked raider goes down
                    engaged[0] = false;
                    engaged[1] = false;
                    mark[0] = t;
                    phase[0] = Phase.ENGAGE;
                }
                case ENGAGE -> {
                    if (t - mark[0] < 12) return; // the order book is updated every 10 ticks
                    List<SettlerEntity> guards = List.of(guardA, guardB);
                    for (int i = 0; i < guards.size(); i++) {
                        SettlerEntity g = guards.get(i);
                        FieldOrders.Assignment a = FieldOrders.assignment(g);
                        helper.assertTrue(a != null && a.order.kind == Kind.ATTACK && a.order.engaging(),
                            g.getSettlerName() + " stopped attacking after the marked raider fell: "
                                + (a == null ? "none" : a.order.kind + (a.order.engaging() ? " (engaging)" : "")));
                        // Never idle: the order always points at a live raider while one is left.
                        net.minecraft.world.entity.LivingEntity next = FieldOrders.enemy(g, a);
                        helper.assertTrue(next instanceof RaiderEntity && next.isAlive() && next != marked[0],
                            g.getSettlerName() + " has no next raider to attack: " + next);
                        if (g.getTarget() instanceof RaiderEntity r && r.isAlive() && r != marked[0]
                            && FieldOrders.allowsTarget(g, r)) {
                            engaged[i] = true;
                        }
                    }
                    if (!(engaged[0] && engaged[1])) {
                        helper.assertTrue(t - mark[0] < 400, "the Guards never took the next raider: "
                            + guardA.getTarget() + " / " + guardB.getTarget());
                        return;
                    }
                    // A new order ends attack mode: follow the commander again.
                    FieldOrders.Result follow = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.FOLLOW, commander.blockPosition(), 0, 1, -1));
                    helper.assertTrue(follow.accepted() && follow.heard() == 2, "FOLLOW after attack mode: "
                        + follow.refusal());
                    mark[0] = t;
                    phase[0] = Phase.FOLLOW2;
                }
                case FOLLOW2 -> {
                    for (SettlerEntity g : List.of(guardA, guardB)) {
                        FieldOrders.Assignment a = FieldOrders.assignment(g);
                        helper.assertTrue(a != null && a.order.kind == Kind.FOLLOW && !FieldOrders.engaging(g),
                            "a new FOLLOW order ends attack mode: " + (a == null ? "none" : a.order.kind));
                    }
                    if (t - mark[0] < 40) return;
                    // Mark a fresh raider near the escort, then let it fall mid-raid.
                    marked[0] = raider(helper, s, 24, 16);
                    FieldOrders.Result focus = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.ATTACK, null, 0, 1, marked[0].getId()));
                    helper.assertTrue(focus.accepted(), "second focus accepted: " + focus.refusal());
                    mark[0] = t;
                    phase[0] = Phase.FOCUS2;
                }
                case FOCUS2 -> {
                    if (t - mark[0] < 20) return;
                    marked[0].kill();
                    mark[0] = t;
                    phase[0] = Phase.ENGAGE2;
                }
                case ENGAGE2 -> {
                    if (t - mark[0] < 12) return;
                    for (SettlerEntity g : List.of(guardA, guardB)) {
                        helper.assertTrue(FieldOrders.engaging(g), g.getSettlerName() + " did not keep attacking");
                    }
                    for (RaiderEntity r : raiders) if (r.isAlive()) r.kill();
                    decoy[0].kill();
                    s.alertUntilGameTime = helper.getLevel().getGameTime(); // the raid is over
                    mark[0] = t;
                    phase[0] = Phase.RAID_OVER;
                }
                case RAID_OVER -> {
                    boolean anyOrder = FieldOrders.assignment(guardA) != null
                        || FieldOrders.assignment(guardB) != null || FieldOrders.assignment(archer) != null;
                    if (t - mark[0] == 100) {
                        FieldOrders.Assignment held = FieldOrders.assignment(guardA);
                        helper.assertTrue(held != null && held.order.kind == Kind.ATTACK,
                            "attack mode holds through the grace period right after the raid: "
                                + (held == null ? "none" : held.order.kind));
                    }
                    if (anyOrder) {
                        helper.assertTrue(t - mark[0] < 1500, "orders never lapsed after the raid ended");
                        return;
                    }
                    helper.assertTrue(t - mark[0] > 100, "orders lapsed before the grace period");
                    phase[0] = Phase.DONE;
                    helper.getLevel().getServer().getPlayerList().remove(commander);
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    /**
     * Owner, 27 Sep: after the marked raider falls the Guards keep attacking
     * until "another order"; "back to posts" is such an order.
     */
    @GameTest(template = "empty32", timeoutTicks = 600, batch = "raid_field_orders_return")
    public void backToPostsEndsAttackMode(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        helper.getLevel().setDayTime(1000);
        Settlement s = new Settlement(UUID.randomUUID(), "Returnholm", helper.absolutePos(new BlockPos(16, 1, 16)));
        s.radius = 14;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        Building barracks = GameTestFixtures.register(helper, s, BuildingType.BARRACKS, 1, 1);
        SettlerEntity guard = soldier(helper, s, barracks, "Ragna", 14, 12);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        ServerPlayer commander = commander(helper, helper.absolutePos(new BlockPos(16, 1, 10)));
        s.alertUntilGameTime = helper.getLevel().getGameTime() + 100_000L;
        long[] mark = {0};
        int[] step = {0};
        RaiderEntity[] marked = {null};
        RaiderEntity[] next = {null};
        helper.onEachTick(() -> {
            long t = helper.getTick();
            switch (step[0]) {
                case 0 -> {
                    if (!EquipmentRequests.readyForProfession(helper.getLevel(), guard, Profession.GUARD)) {
                        helper.assertTrue(t < 200, "the Guard never became combat-ready");
                        return;
                    }
                    marked[0] = raider(helper, s, 18, 16);
                    next[0] = raider(helper, s, 22, 20);
                    FieldOrders.Result focus = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.ATTACK, null, 0, 1, marked[0].getId()));
                    helper.assertTrue(focus.accepted() && focus.heard() == 1, "focus: " + focus.refusal());
                    mark[0] = t;
                    step[0] = 1;
                }
                case 1 -> {
                    if (t - mark[0] < 10) return;
                    marked[0].kill();
                    mark[0] = t;
                    step[0] = 2;
                }
                case 2 -> {
                    if (t - mark[0] < 12) return;
                    helper.assertTrue(FieldOrders.engaging(guard), "keeps attacking after the mark fell");
                    helper.assertTrue(FieldOrders.allowsTarget(guard, next[0]), "the next raider is fair game");
                    FieldOrders.Result back = FieldOrders.issue(commander,
                        request(Group.MELEE, Kind.RETURN, null, 0, 1, -1));
                    helper.assertTrue(back.accepted(), "back to posts: " + back.refusal());
                    helper.assertTrue(FieldOrders.assignment(guard) == null, "back to posts ends attack mode");
                    mark[0] = t;
                    step[0] = 3;
                }
                case 3 -> {
                    helper.assertTrue(FieldOrders.assignment(guard) == null && !FieldOrders.engaging(guard),
                        "attack mode came back without an order");
                    if (t - mark[0] < 40) return;
                    step[0] = 4;
                    next[0].kill();
                    helper.getLevel().getServer().getPlayerList().remove(commander);
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    private static SettlerEntity soldier(GameTestHelper helper, Settlement s, Building employer,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, employer, settler);
        helper.assertTrue(hired.ok(), "fixture hires " + name + ": " + hired);
        return settler;
    }

    private static RaiderEntity raider(GameTestHelper helper, Settlement s, int x, int z) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(x, 1, z));
        // Bound to this settlement's raid, as a real band is (RaidDirector assigns every member).
        raider.assign(UUID.randomUUID(), s.id, com.hearthstead.settlement.raid.RaidObjective.BLOD, 1.0F, false);
        raider.setNoAi(true); // a standing target: the test is about the defenders' orders
        raider.setPersistenceRequired();
        return raider;
    }

    private static ArcherAttackGoal archerGoal(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) return existing;
        }
        throw new IllegalStateException("the Archer has no ArcherAttackGoal");
    }

    /** A real server player in the player list, so FOLLOW can find its leader. */
    private static ServerPlayer commander(GameTestHelper helper, BlockPos at) {
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "raid-commander");
        net.minecraft.server.network.CommonListenerCookie cookie =
            net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile,
            cookie.clientInformation());
        net.minecraft.network.Connection connection =
            new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    private static FieldOrderRequestPayload request(Group group, Kind kind, BlockPos pos, int octant, int width,
                                                    int enemyId) {
        return new FieldOrderRequestPayload(group.wireId(), kind.wireId(),
            pos == null ? FieldOrderRequestPayload.NO_POS : pos, octant, width, enemyId);
    }
}
