package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.GuardRaidEscortGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidThreatBoard;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Protect-civilians-first (DESIGN.md system 5 / R19), routed here whole
 * after the 2026-08-26 raid-night audit found it was not true:
 * {@code SettlerDefenseTargetGoal} used to be a plain nearest-hostile
 * search, so a guard already fighting a harmless raider at the wall would
 * never so much as glance at a second one mauling a settler three blocks
 * away. See that class's own doc for the mechanism; these pin the
 * observable result.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardDefenseGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Wardholm", helper.absolutePos(centerRel));
        s.radius = 8;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity spawnGuard(GameTestHelper helper, Settlement s, BlockPos rel) {
        return spawnDefender(helper, s, rel, Profession.GUARD);
    }

    private static SettlerEntity spawnDefender(GameTestHelper helper, Settlement s,
                                                BlockPos rel, Profession profession) {
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), rel);
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(profession);
        // This direct-profession fixture has no workplace/courier able to
        // satisfy an equipment request. Supply the real physical weapon that
        // production GuardMeleeGoal now requires; never bypass that gate.
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
            profession == Profession.ARCHER ? Items.BOW : Items.IRON_SWORD));
        return guard;
    }

    private static SettlerEntity spawnCivilian(GameTestHelper helper, Settlement s, BlockPos rel,
                                               String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static RaiderEntity spawnIdleRaider(GameTestHelper helper, Settlement s, BlockPos rel) {
        return spawnIdleRaider(helper, s, rel,
            RaiderEntity.Variant.SKIRMISHER);
    }

    private static RaiderEntity spawnIdleRaider(GameTestHelper helper,
                                                 Settlement s,
                                                 BlockPos rel,
                                                 RaiderEntity.Variant variant) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        // Variant must precede assign(): assign materialises the matching
        // health, speed and knockback profile as well as the visible build.
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        // These tests measure assignment, not time-to-kill. Absorption keeps
        // a valid, attackable target alive long enough for the 10-tick
        // coordination review without weakening real guard combat.
        raider.setAbsorptionAmount(100.0F);
        return raider;
    }

    private static ActiveRaid beginRaidCapture(GameTestHelper helper,
                                                Settlement settlement) {
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2),
            "fixture: authoritative first-raid schedule must initialize");
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "fixture: authoritative first raid did not activate");
        UUID participantId = UUID.randomUUID();
        helper.assertTrue(settlement.raidLifecycle.recordParticipant(participantId),
            "fixture: exact first-raid participant must enter the open capture");
        settlement.pendingRaid = plan;
        return new ActiveRaid(plan, participantId);
    }

    private static ActiveRaid activateRaid(GameTestHelper helper,
                                            Settlement settlement) {
        ActiveRaid raid = beginRaidCapture(helper, settlement);
        helper.assertTrue(settlement.raidLifecycle.sealParticipants(),
            "fixture: exact first-raid participant capture must seal");
        return raid;
    }

    private static void activateRaidFor(GameTestHelper helper,
                                        Settlement settlement,
                                        RaiderEntity... participants) {
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2),
            "fixture: authoritative first-raid schedule must initialize");
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "fixture: authoritative first raid did not activate");
        for (RaiderEntity participant : participants) {
            helper.assertTrue(settlement.raidLifecycle.recordParticipant(
                    participant.getUUID()),
                "fixture: physical raider must enter sealed roster");
        }
        helper.assertTrue(settlement.raidLifecycle.sealParticipants(),
            "fixture: physical raid roster must seal");
        settlement.pendingRaid = plan;
    }

    /**
     * The core claim: a raider actively targeting a settler outranks a
     * raider that is simply closer and doing nothing. Determinism over
     * relying on real AI acquisition: {@link RaiderEntity#setTarget} is
     * called directly, the same "seed the world, read the public API"
     * discipline {@code RaidDamageGameTests}' class doc names.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "guard_defense_prefers_a_raider_attacking_a_settler_over_a_nearer_idle_one")
    public void prefersARaiderAttackingASettlerOverANearerIdleOne(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        SettlerEntity victim = spawnCivilian(helper, s, new BlockPos(13, 1, 8), "Civilian");

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity attackingAndFar = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));
        // This fixture measures defender ordering, not RaiderEntity's own
        // combat loop. Without freezing both raiders they can kill the
        // civilian and guard before the shared ten-tick threat snapshot is
        // published, making the final observed target null for reasons
        // unrelated to the ordering contract. A frozen Mob still exposes
        // the live target set immediately below to production urgency logic.
        idleAndNear.setNoAi(true);
        attackingAndFar.setNoAi(true);
        attackingAndFar.setTarget(victim);

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == attackingAndFar,
            "a guard must prefer the raider actively attacking a settler over one merely "
                + "closer and idle; got " + guard.getTarget()));
    }

    /**
     * Second-tier preference: absent any raider attacking a settler, one
     * attacking the player still outranks a nearer, idle raider.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "guard_defense_prefers_a_raider_attacking_the_player_over_a_nearer_idle_one")
    public void prefersARaiderAttackingThePlayerOverANearerIdleOne(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos playerAbs = helper.absolutePos(new BlockPos(13, 1, 8));
        player.teleportTo(playerAbs.getX() + 0.5, playerAbs.getY(), playerAbs.getZ() + 0.5);

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity attackingAndFar = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));
        // Freeze autonomous retargeting: this fixture asserts the shared
        // defender selector's player-threat ordering, not RaiderEntity's own
        // higher-priority search for a nearby settler (the guard itself).
        idleAndNear.setNoAi(true);
        attackingAndFar.setNoAi(true);
        attackingAndFar.setTarget(player);

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == attackingAndFar,
            "absent a settler under attack, a guard must still prefer the raider actively "
                + "attacking the player over one merely closer and idle; got "
                + guard.getTarget()));
    }

    /**
     * The requirement the audit named explicitly: the preference must stay
     * visible mid-fight, not only at the moment a guard first picks a
     * target. A guard already engaged with the nearer, idle raider must
     * abandon it the instant a farther one starts mauling a settler.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "guard_defense_abandons_a_distant_fight_to_intercept_one_standing_over_a_civilian")
    public void abandonsADistantFightToInterceptOneStandingOverACivilian(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, s, new BlockPos(8, 1, 8));
        SettlerEntity victim = spawnCivilian(helper, s, new BlockPos(13, 1, 8), "Civilian");

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        RaiderEntity laterAttacker = spawnIdleRaider(helper, s, new BlockPos(12, 1, 8));
        // This test measures target reselection, not damage throughput. Keep
        // both server entities targetable but stop their autonomous combat AI
        // from killing the guard/civilian before the bounded 10-tick target
        // review. The threat is still armed below through the production
        // authority signal: the raider's own live Mob#getTarget().
        idleAndNear.setNoAi(true);
        laterAttacker.setNoAi(true);

        // Confirm the guard actually engages the near, idle raider FIRST --
        // otherwise a later switch to the far one would prove nothing about
        // abandoning an existing fight. Only once that is confirmed does the
        // second raider become a threat to a settler, one runnable so the
        // ordering (check, then arm the threat) is not left to chance.
        // WAIT for the first engagement rather than assuming a tick for it.
        // This used to be runAtTickTime(30, ...), which failed roughly one run
        // in three: vanilla's TargetGoal randomises its own first-check
        // interval, so "has the guard acquired anything by tick 30" is a race,
        // not a fact. The fix is not a bigger number -- that only moves the
        // race -- but polling for the condition the test actually depends on.
        //
        // Nothing is weakened. Both original assertions survive verbatim: the
        // guard must engage the near idle raider FIRST (otherwise a later
        // switch proves nothing about abandoning a fight), and only once that
        // is true is the far raider armed against a settler. If the first
        // engagement never happens, the setup assertion keeps failing until
        // the timeout and the test still fails with its own message.
        boolean[] armed = {false};
        helper.succeedWhen(() -> {
            if (!armed[0]) {
                helper.assertTrue(guard.getTarget() == idleAndNear,
                    "setup: the guard must start on the nearer idle raider, got "
                        + guard.getTarget());
                laterAttacker.setTarget(victim);
                armed[0] = true;
            }
            helper.assertTrue(armed[0] && guard.getTarget() == laterAttacker,
                "an already-engaged guard must switch to intercept a raider that starts "
                    + "attacking a settler, even though it is farther away; got "
                    + guard.getTarget());
        });
    }

    /**
     * One exact Stand issuer gets one bodyguard inside the authored leash.
     * A second Stand guard, a Patrol guard and every Archer remain on their
     * explicit orders. Moving the issuer outside the leash returns the elected
     * guard to the unchanged post.
     */
    @GameTest(template = "empty16", timeoutTicks = 500, batch = "guard_raid_elects_one_stand_bodyguard_without_overriding_patrol_or_archer")
    public void electsOneStandBodyguardWithoutOverridingPatrolOrArcher(
            GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        Building barracks = GameTestFixtures.register(helper, s,
            BuildingType.BARRACKS, 2, 2);
        ServerPlayer issuer = helper.makeMockServerPlayerInLevel();
        BlockPos playerAbs = helper.absolutePos(new BlockPos(8, 1, 8));
        issuer.teleportTo(playerAbs.getX() + 0.5D, playerAbs.getY(),
            playerAbs.getZ() + 0.5D);
        ServerPlayer intruder = helper.makeMockServerPlayerInLevel();
        BlockPos intruderAbs = helper.absolutePos(new BlockPos(7, 1, 8));
        intruder.teleportTo(intruderAbs.getX() + 0.5D, intruderAbs.getY(),
            intruderAbs.getZ() + 0.5D);

        SettlerEntity bodyguard = spawnCivilian(helper, s,
            new BlockPos(7, 1, 8), "Bodyguard");
        SettlerEntity reserve = spawnCivilian(helper, s,
            new BlockPos(3, 1, 8), "Reserve");
        SettlerEntity patrol = spawnCivilian(helper, s,
            new BlockPos(12, 1, 8), "Patrol");
        for (SettlerEntity guard : new SettlerEntity[] {
                bodyguard, reserve, patrol}) {
            helper.assertTrue(Employment.hire(helper.getLevel(), s,
                    barracks, guard).ok(),
                "fixture: Barracks must hire each Guard");
            guard.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(Items.IRON_SWORD));
        }

        BlockPos bodyPost = helper.absolutePos(new BlockPos(7, 1, 8));
        BlockPos reservePost = helper.absolutePos(new BlockPos(3, 1, 8));
        GuardOrder bodyOrder = s.guardOrders.orderForMutation(s.id,
            bodyguard.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        GuardOrder reserveOrder = s.guardOrders.orderForMutation(s.id,
            reserve.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        GuardOrder patrolOrder = s.guardOrders.orderForMutation(s.id,
            patrol.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        helper.assertTrue(bodyOrder.issueStand(bodyPost, Direction.NORTH, 4,
                issuer.getUUID(), barracks.id, helper.getLevel().getGameTime())
            && reserveOrder.issueStand(reservePost, Direction.NORTH, 8,
                issuer.getUUID(), barracks.id, helper.getLevel().getGameTime()),
            "fixture: two valid Stand posts must be authored by the player");
        UUID issuerId = issuer.getUUID();
        helper.assertTrue(patrolOrder.appendPatrolPoint(
                helper.absolutePos(new BlockPos(12, 1, 7)), issuerId,
                barracks.id, helper.getLevel().getGameTime())
            && patrolOrder.appendPatrolPoint(
                helper.absolutePos(new BlockPos(12, 1, 10)), issuerId,
                barracks.id, helper.getLevel().getGameTime())
            && patrolOrder.issuePatrol(GuardOrder.Traversal.LOOP, issuerId,
                barracks.id, helper.getLevel().getGameTime()),
            "fixture: valid Patrol order");

        // An Archer can never enter this movement goal, even if armed and a
        // raid is active; their Tower goal remains the sole movement owner.
        SettlerEntity archer = spawnDefender(helper, s,
            new BlockPos(14, 1, 14), Profession.ARCHER);

        // A compatibility mirror alone is never raid authority.  This used
        // to make a Stand Guard follow forever after a damaged load even
        // though RaidDirector correctly refused to process that mirror.
        s.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            0.0F, 4L);
        helper.assertTrue(!new GuardRaidEscortGoal(bodyguard).canUse(),
            "stale PendingRaid without an active lifecycle must not start escort");
        s.pendingRaid = null;
        ActiveRaid activeRaid = beginRaidCapture(helper, s);
        helper.assertTrue(!new GuardRaidEscortGoal(bodyguard).canUse(),
            "an authored lifecycle still capturing participants must not start escort");
        helper.assertTrue(s.raidLifecycle.sealParticipants(),
            "fixture: bodyguard raid must seal its exact participant ledger");
        helper.assertTrue(new GuardRaidEscortGoal(bodyguard).canUse(),
            "closest valid Stand guard must follow its exact issuer; a closer "
                + "non-issuer player may not steal it");
        helper.assertTrue(!new GuardRaidEscortGoal(reserve).canUse(),
            "a second Stand guard must not become another bodyguard");
        helper.assertTrue(!new GuardRaidEscortGoal(patrol).canUse(),
            "Patrol must never be overridden by raid bodyguard movement");
        helper.assertTrue(!new GuardRaidEscortGoal(archer).canUse(),
            "Archer Tower movement must never be overridden by bodyguard AI");

        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                helper.assertTrue(bodyguard.blockPosition().distSqr(bodyPost)
                        > 2.25D
                    && bodyguard.distanceToSqr(issuer) <= 16.0D,
                    "elected Stand guard must visibly screen its exact issuer");
                BlockPos outside = helper.absolutePos(new BlockPos(14, 1, 8));
                issuer.teleportTo(outside.getX() + 0.5D, outside.getY(),
                    outside.getZ() + 0.5D);
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                helper.assertTrue(bodyguard.blockPosition().distSqr(bodyPost)
                        <= 2.25D,
                    "outside the leash, the elected bodyguard must return");
                issuer.teleportTo(playerAbs.getX() + 0.5D, playerAbs.getY(),
                    playerAbs.getZ() + 0.5D);
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                helper.assertTrue(bodyguard.blockPosition().distSqr(bodyPost)
                        > 2.25D
                    && bodyguard.distanceToSqr(issuer) <= 16.0D,
                    "the unchanged Stand order must re-elect its issuer during the raid");
                helper.assertTrue(s.raidLifecycle.recordTerminalParticipant(
                        activeRaid.participantId())
                        && RaidDirector.resolveIfOver(helper.getLevel(), s)
                        && s.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                        && s.pendingRaid == null,
                    "the sealed authoritative raid must resolve through RaidDirector "
                        + "before escort authority disappears");
                phase[0] = 3;
                return;
            }
            helper.assertTrue(bodyguard.blockPosition().distSqr(bodyPost)
                    <= 2.25D
                && reserve.blockPosition().distSqr(reservePost) <= 2.25D
                && bodyOrder.mode() == GuardOrder.Mode.STAND_POST
                && reserveOrder.mode() == GuardOrder.Mode.STAND_POST
                && patrolOrder.mode() == GuardOrder.Mode.PATROL_ROUTE,
                "raid end must return the bodyguard while every explicit order "
                    + "remains unchanged");
        });
    }

    /** A neighbouring settlement's raider may not be chased or shot. */
    @GameTest(template = "empty16", timeoutTicks = 240, batch = "guard_raid_archer_coordinator_rejects_a_foreign_settlement_raider")
    public void archerCoordinatorRejectsAForeignSettlementRaider(
            GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement own = makeSettlement(helper, new BlockPos(8, 1, 8));
        Settlement foreign = makeSettlement(helper, new BlockPos(1, 1, 1));
        SettlerEntity archer = spawnDefender(helper, own,
            new BlockPos(8, 1, 8), Profession.ARCHER);
        RaiderEntity foreignNear = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(9, 1, 8));
        foreignNear.assign(UUID.randomUUID(), foreign.id, RaidObjective.BLOD,
            1.0F, false);
        RaiderEntity ownFar = spawnIdleRaider(helper, own,
            new BlockPos(12, 1, 8));
        foreignNear.setNoAi(true);
        ownFar.setNoAi(true);
        ArcherAttackGoal fallback = new ArcherAttackGoal(archer);
        helper.assertTrue(fallback.canUse()
                && archer.getTarget() == ownFar
                && archer.getTarget() != foreignNear,
            "shared Archer coordinator must reject a foreign raid even when "
                + "it is nearer; got " + archer.getTarget());
        helper.succeed();
    }

    /**
     * Two ordinary enemies and two defenders must produce two engagements,
     * not a dogpile. The role tie-break also pins the future counter seam:
     * the sword-bearing guard takes the brute while the archer covers the
     * skirmisher.  The encounter therefore proves the same readable roles
     * that the +25% class-counter damage uses in live combat.
     */
    @GameTest(template = "empty16", timeoutTicks = 240, batch = "guard_raid_distributes_roles_across_two_ordinary_raiders")
    public void distributesRolesAcrossTwoOrdinaryRaiders(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnDefender(helper, s,
            new BlockPos(7, 1, 8), Profession.GUARD);
        SettlerEntity archer = spawnDefender(helper, s,
            new BlockPos(9, 1, 8), Profession.ARCHER);
        RaiderEntity brute = spawnIdleRaider(helper, s,
            new BlockPos(8, 1, 11), RaiderEntity.Variant.BRUTE);
        RaiderEntity skirmisher = spawnIdleRaider(helper, s,
            new BlockPos(8, 1, 5), RaiderEntity.Variant.SKIRMISHER);
        activateRaidFor(helper, s, brute, skirmisher);
        brute.setNoAi(true);
        skirmisher.setNoAi(true);

        // A physically separate encounter creates the same roles in the
        // reverse entity-add order. Semantic assignments must match.
        Settlement reversed = new Settlement(UUID.randomUUID(), "Reverseholm",
            helper.absolutePos(new BlockPos(3, 1, 3)));
        reversed.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(reversed.id,
            reversed);
        SettlerEntity reversedArcher = spawnDefender(helper, reversed,
            new BlockPos(3, 1, 2), Profession.ARCHER);
        SettlerEntity reversedGuard = spawnDefender(helper, reversed,
            new BlockPos(2, 1, 3), Profession.GUARD);
        RaiderEntity reversedSkirmisher = spawnIdleRaider(helper, reversed,
            new BlockPos(3, 1, 5), RaiderEntity.Variant.SKIRMISHER);
        RaiderEntity reversedBrute = spawnIdleRaider(helper, reversed,
            new BlockPos(5, 1, 3), RaiderEntity.Variant.BRUTE);
        activateRaidFor(helper, reversed, reversedSkirmisher, reversedBrute);
        reversedBrute.setNoAi(true);
        reversedSkirmisher.setNoAi(true);

        // Allocation needs all four ordinary targets alive while independent
        // normal-AI acquisition converges. In 1.21.1 absorption is clamped to
        // MAX_ABSORPTION (zero by default), so set the capacity before filling
        // this local durability fixture. Damage and target authority stay real.
        for (RaiderEntity raider : new RaiderEntity[] {
                brute, skirmisher, reversedBrute, reversedSkirmisher }) {
            raider.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION)
                .setBaseValue(1_000.0D);
            raider.setAbsorptionAmount(1_000.0F);
            helper.assertTrue(raider.getAbsorptionAmount() == 1_000.0F,
                "fixture: allocation target must receive actual absorption");
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getTarget() == brute,
                "melee guard must screen the brute; got " + guard.getTarget());
            helper.assertTrue(archer.getTarget() == skirmisher,
                "archer must cover the skirmisher; got " + archer.getTarget());
            helper.assertTrue(guard.getTarget() != archer.getTarget(),
                "two defenders must cover two ordinary raiders instead of dogpiling");
            RaidThreatBoard.clear(helper.getLevel());
            Monster archerFirst = RaidThreatBoard.assignedTarget(
                helper.getLevel(), s, archer);
            Monster guardSecond = RaidThreatBoard.assignedTarget(
                helper.getLevel(), s, guard);
            RaidThreatBoard.clear(helper.getLevel());
            Monster guardFirst = RaidThreatBoard.assignedTarget(
                helper.getLevel(), s, guard);
            Monster archerSecond = RaidThreatBoard.assignedTarget(
                helper.getLevel(), s, archer);
            helper.assertTrue(guardFirst == guardSecond
                    && archerFirst == archerSecond
                    && guardFirst == brute && archerFirst == skirmisher,
                "atomic allocation must be caller/tick-order independent");
            helper.assertTrue(reversedGuard.getTarget() == reversedBrute
                    && reversedArcher.getTarget() == reversedSkirmisher,
                "physically reversed defender/raider add order must retain "
                    + "the same Guard-to-Brute and Archer-to-Skirmisher mapping");
        });
    }

    /**
     * Focus fire remains deliberate: a raider actively on the player earns
     * two defenders, but the third covers the other raider rather than joining
     * an unlimited pile. If there were only one enemy, fallback selection
     * would still send every available defender into the fight.
     */
    @GameTest(template = "empty16", timeoutTicks = 240, batch = "guard_raid_focuses_two_on_a_player_threat_then_covers_the_next_enemy")
    public void focusesTwoOnAPlayerThreatThenCoversTheNextEnemy(
            GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos playerAbs = helper.absolutePos(new BlockPos(8, 1, 8));
        player.teleportTo(playerAbs.getX() + 0.5D, playerAbs.getY(),
            playerAbs.getZ() + 0.5D);
        SettlerEntity first = spawnGuard(helper, s, new BlockPos(6, 1, 8));
        SettlerEntity second = spawnGuard(helper, s, new BlockPos(8, 1, 7));
        SettlerEntity third = spawnGuard(helper, s, new BlockPos(10, 1, 8));
        RaiderEntity playerThreat = spawnIdleRaider(helper, s,
            new BlockPos(8, 1, 11));
        RaiderEntity uncovered = spawnIdleRaider(helper, s,
            new BlockPos(8, 1, 4));
        activateRaidFor(helper, s, playerThreat, uncovered);
        // This is an allocation test, not a four-hit combat race. The live
        // sword contacts observed in QA can remove both 18-health targets
        // before the ten-tick board snapshot is read. Keep the same real,
        // attackable Raiders alive long enough to observe allocation.
        for (RaiderEntity raider : java.util.List.of(playerThreat, uncovered)) {
            raider.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(1_000.0D);
            raider.setHealth(1_000.0F);
        }
        playerThreat.setNoAi(true);
        uncovered.setNoAi(true);
        playerThreat.setTarget(player);

        helper.succeedWhen(() -> {
            int onThreat = (first.getTarget() == playerThreat ? 1 : 0)
                + (second.getTarget() == playerThreat ? 1 : 0)
                + (third.getTarget() == playerThreat ? 1 : 0);
            int onOther = (first.getTarget() == uncovered ? 1 : 0)
                + (second.getTarget() == uncovered ? 1 : 0)
                + (third.getTarget() == uncovered ? 1 : 0);
            helper.assertTrue(onThreat == 2,
                "the active player threat must draw exactly two defenders; got "
                    + onThreat + "; " + guardFocusDiagnostic(helper, first, second,
                        third, playerThreat, uncovered, player, s));
            helper.assertTrue(onOther == 1,
                "the third defender must cover the other enemy; got " + onOther
                    + "; " + guardFocusDiagnostic(helper, first, second, third,
                        playerThreat, uncovered, player, s));
        });
    }

    private static String guardFocusDiagnostic(GameTestHelper helper,
                                                SettlerEntity first,
                                                SettlerEntity second,
                                                SettlerEntity third,
                                                RaiderEntity playerThreat,
                                                RaiderEntity uncovered,
                                                ServerPlayer player,
                                                Settlement settlement) {
        return "guards=[" + guardFocusState(first) + ";" + guardFocusState(second)
            + ";" + guardFocusState(third) + "]"
            + ", raiders=[" + guardFocusRaiderState(playerThreat) + ";"
            + guardFocusRaiderState(uncovered) + "]"
            + ", player=[alive=" + player.isAlive() + ",removed=" + player.isRemoved()
            + ",pos=" + player.position() + "]"
            + ", candidates=" + RaidThreatBoard.candidates(helper.getLevel(), settlement);
    }

    private static String guardFocusState(SettlerEntity guard) {
        return "target=" + (guard.getTarget() == null ? "none"
            : guard.getTarget().getUUID()) + ",pos=" + guard.position()
            + ",alive=" + guard.isAlive() + ",profession=" + guard.getProfession();
    }

    private static String guardFocusRaiderState(RaiderEntity raider) {
        return "id=" + raider.getUUID() + ",target="
            + (raider.getTarget() == null ? "none" : raider.getTarget().getUUID())
            + ",pos=" + raider.position() + ",alive=" + raider.isAlive()
            + ",settlement=" + raider.settlementId();
    }

    /** Allocation regression, not a combat victory or damage simulation. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "guard_close_engagement_continuity")
    public void closeEngagementSurvivesNearestPeerButYieldsToUrgency(
            GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement settlement = makeSettlement(helper, new BlockPos(8, 1, 8));
        SettlerEntity guard = spawnGuard(helper, settlement, new BlockPos(8, 1, 8));
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        RaiderEntity incumbent = spawnIdleRaider(helper, settlement,
            new BlockPos(8, 1, 11));
        RaiderEntity nearer = spawnIdleRaider(helper, settlement,
            new BlockPos(9, 1, 8));
        guard.setNoAi(true);
        incumbent.setNoAi(true);
        nearer.setNoAi(true);
        guard.setTarget(incumbent);
        RaidThreatBoard.clear(helper.getLevel());
        helper.assertTrue(RaidThreatBoard.assignedTarget(helper.getLevel(),
                settlement, guard) == incumbent,
            "an eligible close incumbent must survive a nearer equal-tier peer");

        nearer.setTarget(guard);
        RaidThreatBoard.clear(helper.getLevel());
        helper.assertTrue(RaidThreatBoard.assignedTarget(helper.getLevel(),
                settlement, guard) == nearer,
            "a newly urgent threat must override close engagement continuity");

        nearer.setTarget(null);
        BlockPos far = helper.absolutePos(new BlockPos(8, 1, 15));
        incumbent.moveTo(far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D);
        RaidThreatBoard.clear(helper.getLevel());
        helper.assertTrue(RaidThreatBoard.assignedTarget(helper.getLevel(),
                settlement, guard) == nearer,
            "a distant incumbent must not prevent defending a nearby peer");

        incumbent.discard();
        RaidThreatBoard.clear(helper.getLevel());
        helper.assertTrue(RaidThreatBoard.assignedTarget(helper.getLevel(),
                settlement, guard) == nearer,
            "a removed incumbent must never retain an assignment");
        guard.discard();
        nearer.discard();
        SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        RaidThreatBoard.clear(helper.getLevel());
        helper.succeed();
    }

    private record ActiveRaid(RaidPlan plan, UUID participantId) {
    }
}
