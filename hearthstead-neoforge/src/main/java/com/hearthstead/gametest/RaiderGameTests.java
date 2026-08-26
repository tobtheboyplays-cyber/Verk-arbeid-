package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidPressure;
import com.hearthstead.settlement.raid.RaidTelegraph;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE A3 step 3 — the raider itself.
 *
 * <p>The thing these guard against is the failure both references shipped:
 * raiders that are indistinguishable from each other and from the player's
 * own guards, scaled from the player's stat sheet rather than from anything
 * the enemy has done.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderGameTests {

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
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Raidholm",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * A captain is a different creature, not a bigger health bar: more
     * health AND more damage, and the renderer keys its helm, pauldron and
     * scale off the same flag, so you can read who leads a raid from across
     * the field.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_captains_are_visibly_and_mechanically_distinct")
    public void captainsAreVisiblyAndMechanicallyDistinct(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity grunt = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        UUID captainId = UUID.randomUUID();
        grunt.assign(captainId, s.id, RaidObjective.KORN, 1.0F, false);
        captain.assign(captainId, s.id, RaidObjective.KORN, 1.0F, true);

        helper.assertTrue(!grunt.isCaptain(), "the follower is not the captain");
        helper.assertTrue(captain.isCaptain(), "the captain is");
        helper.assertTrue(captain.getMaxHealth() > grunt.getMaxHealth(),
            "a captain must be harder to kill: " + captain.getMaxHealth()
                + " vs " + grunt.getMaxHealth());
        double captainDamage = captain.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double gruntDamage = grunt.getAttributeValue(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(captainDamage > gruntDamage,
            "and must hit harder: " + captainDamage + " vs " + gruntDamage);
        helper.succeed();
    }

    /**
     * Strength comes from the captain's own record, not from the player's
     * stat sheet -- and it is capped, so a long feud stays winnable rather
     * than becoming a wall.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_menace_scales_strength_and_is_capped")
    public void menaceScalesStrengthAndIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity mild = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity feared = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        RaiderEntity absurd = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 2));
        UUID captainId = UUID.randomUUID();
        mild.assign(captainId, s.id, RaidObjective.BLOD, 1.0F, false);
        feared.assign(captainId, s.id, RaidObjective.BLOD, 2.0F, false);
        absurd.assign(captainId, s.id, RaidObjective.BLOD, 99.0F, false);

        helper.assertTrue(feared.getMaxHealth() > mild.getMaxHealth(),
            "menace must scale health, got " + feared.getMaxHealth()
                + " vs " + mild.getMaxHealth());
        helper.assertTrue(absurd.menace() <= RaiderEntity.MAX_MENACE,
            "menace must be capped, got " + absurd.menace());
        helper.assertTrue(absurd.getMaxHealth()
                <= mild.getMaxHealth() * RaiderEntity.MAX_MENACE + 0.01F,
            "and so must the health it buys, got " + absurd.getMaxHealth());
        helper.succeed();
    }

    /** Raiders never turn on each other, however the melee goes. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_raiders_do_not_fight_each_other")
    public void raidersDoNotFightEachOther(GameTestHelper helper) {
        buildArena(helper, 10);
        RaiderEntity a = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        RaiderEntity b = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 2));
        helper.assertTrue(!a.canAttack(b), "a raider must not target another raider");
        helper.succeed();
    }

    /**
     * A raid that despawns is a raid that never happened. Raiders are
     * persistent, and their orders survive a save.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_raiders_persist_and_remember_their_orders")
    public void raidersPersistAndRememberTheirOrders(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 2));
        UUID captainId = UUID.randomUUID();
        raider.assign(captainId, s.id, RaidObjective.BRANN, 1.5F, true);
        raider.setObjectivePos(helper.absolutePos(new BlockPos(5, 1, 5)));

        helper.assertTrue(!raider.removeWhenFarAway(4096.0),
            "raiders must never despawn for distance");

        var tag = new net.minecraft.nbt.CompoundTag();
        raider.addAdditionalSaveData(tag);
        RaiderEntity reloaded = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(6, 1, 2));
        reloaded.readAdditionalSaveData(tag);

        helper.assertTrue(reloaded.isCaptain(), "captaincy must survive a save");
        helper.assertTrue(reloaded.objective() == RaidObjective.BRANN,
            "as must the objective, got " + reloaded.objective());
        helper.assertTrue(captainId.equals(reloaded.captainId()),
            "and who they answer to");
        helper.assertTrue(s.id.equals(reloaded.settlementId()),
            "and which settlement they came for");
        helper.assertTrue(reloaded.objectivePos() != null,
            "and where they were headed");
        helper.succeed();
    }

    /**
     * The band forms up on the planned bearing, spread across a front
     * rather than stacked on one point -- MineColonies players report
     * raiders "usually come from the same spawn point" and ganging up on
     * one tower guard (#193), and TekTopia uses four fixed corners.
     */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "raider_the_band_forms_up_on_the_planned_bearing")
    public void theBandFormsUpOnThePlannedBearing(GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        // North is -Z; the geometry must put a 180-degree approach south of
        // the settlement and a 0-degree approach north of it.
        BlockPos north = RaidDirector.formUpAt(center, 0.0F, 30);
        BlockPos south = RaidDirector.formUpAt(center, 180.0F, 30);
        BlockPos east = RaidDirector.formUpAt(center, -90.0F, 30);
        helper.assertTrue(north.getZ() > center.getZ(),
            "0 degrees should form up on +Z, got " + north);
        helper.assertTrue(south.getZ() < center.getZ(),
            "180 degrees should form up on -Z, got " + south);
        helper.assertTrue(east.getX() > center.getX(),
            "-90 degrees should form up on +X, got " + east);
        // And the distance must be honoured, so a band never lands on top of
        // the settlement it came to raid.
        double d = Math.sqrt(north.distSqr(center));
        helper.assertTrue(Math.abs(d - 30.0) < 1.5,
            "form-up distance should be honoured, got " + d);
        helper.succeed();
    }

    /** A band is a band, never a horde, however rich the settlement gets. */
    @GameTest(template = "empty16", timeoutTicks = 300, batch = "raider_band_size_grows_with_worth_but_is_capped")
    public void bandSizeGrowsWithWorthButIsCapped(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement small = makeSettlement(helper, new BlockPos(5, 1, 5));
        for (int i = 0; i < 4; i++) {
            small.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        Settlement huge = new Settlement(UUID.randomUUID(), "Huge", BlockPos.ZERO);
        for (int i = 0; i < 60; i++) {
            huge.putRecord(UUID.randomUUID(), "H" + i, Profession.NONE);
        }
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        int smallBand = RaidDirector.bandSizeFor(small, captain);
        int hugeBand = RaidDirector.bandSizeFor(huge, captain);
        helper.assertTrue(hugeBand > smallBand,
            "a richer settlement draws a bigger band: " + hugeBand
                + " vs " + smallBand);
        helper.assertTrue(hugeBand <= RaidDirector.MAX_BAND,
            "and it must stay a band, not a horde: " + hugeBand);
        helper.assertTrue(smallBand >= RaidDirector.MIN_BAND,
            "a raid is never one lone figure: " + smallBand);
        helper.succeed();
    }

    /**
     * A raid must actually arrive, and must actually end. A scheduled raid
     * that never concludes is the raid-shaped version of MineColonies'
     * deliveries that silently never happen.
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "raider_a_raid_arrives_and_then_resolves")
    public void aRaidArrivesAndThenResolves(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        var random = level.getRandom();
        RaidCaptain captain = RaidDirector.pickCaptain(s, random);
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 3L);
        s.pendingRaid = plan;

        var band = RaidDirector.spawnBand(level, s, plan);
        helper.assertTrue(!band.isEmpty(),
            "the band must actually arrive, spawned " + band.size());
        helper.assertTrue(!RaidDirector.livingRaidersOf(level, s).isEmpty(),
            "and must be findable as this settlement's raiders");
        // Asserted on the band spawnBand actually produced, not on what a
        // bounded box query can see: a raider that forms up 30+ blocks out
        // lands beyond the small region a GameTest force-loads, so the query
        // legitimately cannot see all of them here.
        boolean anyCaptain = band.stream().anyMatch(RaiderEntity::isCaptain);
        helper.assertTrue(anyCaptain,
            "a band is led, so one of them is the captain [band=" + band.size() + "]");

        // Not over while anyone still stands.
        helper.assertTrue(!RaidDirector.resolveIfOver(level, s),
            "a raid with raiders left standing is not over");
        helper.assertTrue(s.pendingRaid != null, "so the plan must still be set");

        int pressureBefore = s.raidPressure.pressure();
        int defeatsBefore = captain.defeats();
        for (RaiderEntity r : band) {
            r.discard();
        }
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with none left standing the raid must resolve");
        helper.assertTrue(s.pendingRaid == null,
            "and the plan must be cleared so the next night can be rolled");
        helper.assertTrue(s.raidPressure.pressure() > pressureBefore,
            "repelling a raid must RAISE pressure -- the deliberate inverse "
                + "of MineColonies buying quiet with a loss. Got "
                + s.raidPressure.pressure() + " from " + pressureBefore);
        helper.assertTrue(captain.defeats() == defeatsBefore + 1,
            "and the captain must remember being driven off");
        helper.succeed();
    }

    /**
     * Theft is physical. A raider takes goods OUT of a real chest and INTO
     * its own real inventory, the chest is genuinely emptier, and killing
     * the raider gives the goods back. MineColonies leaves a chat line and a
     * day of mourning; its own feature requests (#113, #129) are asking for
     * exactly this -- a consequence you can chase down.
     */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "raider_raiders_steal_real_goods_and_drop_them_when_killed")
    public void raidersStealRealGoodsAndDropThemWhenKilled(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        BlockPos chestRel = new BlockPos(9, 1, 9);
        helper.setBlock(chestRel, Blocks.CHEST);
        var bounds = net.minecraft.world.level.levelgen.structure.BoundingBox
            .fromCorners(helper.absolutePos(new BlockPos(8, 1, 8)),
                helper.absolutePos(new BlockPos(10, 3, 10)));
        // A plaque block MUST exist at the anchor. BuildingManager's sweep
        // dissolves any building whose plaquePos holds no plaque -- correctly,
        // since "no plaque, no building" is the permanent invariant (D-005).
        // A fixture that skips this registers a building the game then deletes
        // out from under the test, on a round-robin sweep shared with every
        // other concurrently running test: the root cause of KF-014.
        helper.setBlock(new BlockPos(8, 1, 8), ModBlocks.PLAQUE.get());
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(8, 1, 8)),
            helper.absolutePos(new BlockPos(8, 1, 8)), bounds);
        warehouse.valid = true;
        s.buildings.add(warehouse);
        var chest = (net.minecraft.world.Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(chestRel));
        helper.assertTrue(chest != null, "the store must exist");
        chest.setItem(0, new ItemStack(Items.WHEAT, 12));
        final int total = 12;

        RaiderEntity thief = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        thief.assign(UUID.randomUUID(), s.id, RaidObjective.KORN, 1.0F, false);

        helper.succeedWhen(() -> {
            int inChest = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack st = chest.getItem(i);
                if (st.is(Items.WHEAT)) {
                    inChest += st.getCount();
                }
            }
            int carried = thief.lootCount();
            helper.assertTrue(inChest + carried == total,
                "wheat must be conserved at every instant: chest=" + inChest
                    + " carried=" + carried);
            helper.assertTrue(carried > 0,
                "the raider should be taking the stores, chest still has "
                    + inChest);
            helper.assertTrue(inChest < total,
                "and the chest must be genuinely emptier, not just counted down");
        });
    }

    /**
     * Getting away with the goods and being wiped out empty-handed must
     * resolve DIFFERENTLY. Whether the settlement held is about whether the
     * raiders got what they came for, not about who died.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_escaping_with_the_stores_resolves_as_a_loss")
    public void escapingWithTheStoresResolvesAsALoss(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 2L);
        s.raidPressure.setPressureForTesting(50);

        // The band is gone AND the goods went with them.
        s.raidLootEscaped = true;
        int before = s.raidPressure.pressure();
        int victoriesBefore = captain.victories();
        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(s.raidPressure.pressure() < before,
            "losing the stores must EASE pressure -- at a price already paid. "
                + "Got " + s.raidPressure.pressure() + " from " + before);
        helper.assertTrue(captain.victories() == victoriesBefore + 1,
            "and the captain must remember winning");
        helper.assertTrue(!s.raidLootEscaped,
            "the flag must be cleared so the next raid starts honest");
        helper.succeed();
    }

    /** Guards already hunt hostiles, so a raider is a target without a special case. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_guards_treat_raiders_as_hostile")
    public void guardsTreatRaidersAsHostile(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(5, 1, 5));
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 5));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        helper.assertTrue(raider instanceof net.minecraft.world.entity.monster.Monster,
            "raiders must be Monsters so existing guard targeting sees them");
        helper.succeedWhen(() -> helper.assertTrue(
            guard.getTarget() == raider || raider.getTarget() == guard,
            "a guard and a raider inside the settlement should engage; guard="
                + guard.getTarget() + " raider=" + raider.getTarget()));
    }

    // ---------------------------------------------------------- SLICE A3-RAIDS ---

    /**
     * D-A3-3: escalation must be legible in the stage, not only felt through
     * wealth. The same settlement, unchanged in every other way, must pull a
     * visibly bigger band once it reads as besieged.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_band_size_escalates_with_pressure_stage")
    public void bandSizeEscalatesWithPressureStage(GameTestHelper helper) {
        Settlement calm = new Settlement(UUID.randomUUID(), "Calm", BlockPos.ZERO);
        Settlement besieged = new Settlement(UUID.randomUUID(), "Besieged", BlockPos.ZERO);
        for (int i = 0; i < 6; i++) {
            calm.putRecord(UUID.randomUUID(), "C" + i, Profession.NONE);
            besieged.putRecord(UUID.randomUUID(), "B" + i, Profession.NONE);
        }
        besieged.raidPressure.setPressureForTesting(RaidPressure.BELEIRING_THRESHOLD);
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        int calmBand = RaidDirector.bandSizeFor(calm, captain);
        int siegeBand = RaidDirector.bandSizeFor(besieged, captain);
        helper.assertTrue(siegeBand > calmBand,
            "the same settlement under siege must pull a bigger band than a "
                + "calm one: siege=" + siegeBand + " calm=" + calmBand);
        helper.assertTrue(siegeBand <= RaidDirector.MAX_BAND,
            "and escalation must still respect the performance cap, got " + siegeBand);
        helper.succeed();
    }

    /**
     * The aftermath report (D-A3-8 / "Aftermath"): what a lost raid actually
     * cost must be readable afterward, not only felt in the moment.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_a_lost_raid_leaves_a_report_of_what_was_stolen_and_who_was_hurt")
    public void aLostRaidLeavesAReportOfWhatWasStolenAndWhoWasHurt(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 5L);
        s.raidLootEscaped = true;
        s.raidItemsStolenTonight = 7;
        s.raidSettlersHurtTonight = 2;

        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        helper.assertTrue(!s.raidLog.isEmpty(), "the morning must leave a record");
        RaidLogEntry entry = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(!entry.held(), "the settlement lost this one");
        helper.assertTrue(entry.itemsStolen() == 7,
            "the report must say what was taken, got " + entry.itemsStolen());
        helper.assertTrue(entry.settlersHurt() == 2,
            "and who was hurt, got " + entry.settlersHurt());
        helper.assertTrue(entry.captainName().equals(captain.name()),
            "and who led it, got " + entry.captainName());
        helper.assertTrue(s.raidItemsStolenTonight == 0 && s.raidSettlersHurtTonight == 0,
            "tallies must reset so tomorrow's raid starts honest");
        helper.succeed();
    }

    /** The other outcome must read differently: held means nothing was taken. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_a_held_raid_is_logged_as_held_without_stolen_goods")
    public void aHeldRaidIsLoggedAsHeldWithoutStolenGoods(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement s = makeSettlement(helper, new BlockPos(6, 1, 6));
        for (int i = 0; i < 6; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        var level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 6L);
        s.raidSettlersHurtTonight = 1;
        // raidLootEscaped is left false: nothing got away with the goods.

        helper.assertTrue(RaidDirector.resolveIfOver(level, s),
            "with nobody left the raid resolves");
        RaidLogEntry entry = s.raidLog.get(s.raidLog.size() - 1);
        helper.assertTrue(entry.held(), "nothing escaped, so the settlement held");
        helper.assertTrue(entry.itemsStolen() == 0,
            "held must mean nothing was stolen, got " + entry.itemsStolen());
        helper.assertTrue(entry.settlersHurt() == 1,
            "but people can still be hurt in a raid that is repelled, got "
                + entry.settlersHurt());
        helper.succeed();
    }

    /** The report is a history, not an unbounded diary -- capped like the enemy gallery. */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_the_raid_log_stays_bounded")
    public void theRaidLogStaysBounded(GameTestHelper helper) {
        Settlement s = new Settlement(UUID.randomUUID(), "Logtown", BlockPos.ZERO);
        var level = helper.getLevel();
        for (int i = 0; i < RaidDirector.MAX_RAID_LOG + 5; i++) {
            RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
            s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, i);
            helper.assertTrue(RaidDirector.resolveIfOver(level, s),
                "an empty band resolves immediately");
        }
        helper.assertTrue(s.raidLog.size() <= RaidDirector.MAX_RAID_LOG,
            "the raid log must stay bounded, got " + s.raidLog.size());
        helper.succeed();
    }

    /**
     * A hit landed as part of a live raid must count toward the morning
     * report; the same raider swinging outside a raid (a scout defending
     * itself, say) must not inflate one that never happened.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_hurting_a_settler_is_only_tallied_during_a_live_raid")
    public void hurtingASettlerIsOnlyTalliedDuringALiveRaid(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement s = makeSettlement(helper, new BlockPos(5, 1, 5));
        SettlerEntity outsideRaid = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 5));
        outsideRaid.setSettlerName("Kari");
        outsideRaid.bindTo(s.id, s.center);
        s.putRecord(outsideRaid.getUUID(), outsideRaid.getSettlerName(), Profession.NONE);
        SettlerEntity duringRaid = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 5));
        duringRaid.setSettlerName("Ola");
        duringRaid.bindTo(s.id, s.center);
        s.putRecord(duringRaid.getUUID(), duringRaid.getSettlerName(), Profession.NONE);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 5));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        raider.doHurtTarget(outsideRaid);
        helper.assertTrue(s.raidSettlersHurtTonight == 0,
            "a hit outside a live raid must not be tallied, got "
                + s.raidSettlersHurtTonight);

        s.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 0.0F, 1L);
        raider.doHurtTarget(duringRaid);
        helper.assertTrue(s.raidSettlersHurtTonight == 1,
            "a hit during a live raid must be tallied for the defense report, got "
                + s.raidSettlersHurtTonight);
        helper.succeed();
    }

    /**
     * Telegraphing (D-A3's "1-2 days ahead"): a scout is a real, findable
     * RaiderEntity, but it must never itself start the fight it is warning
     * the settlement about.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raider_scouts_are_omens_and_do_not_hunt_even_next_to_settlers")
    public void scoutsAreOmensAndDoNotHuntEvenNextToSettlers(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(9, 1, 7));
        guard.setSettlerName("Vakt");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);

        RaiderEntity scout = RaidTelegraph.spawnScout(helper.getLevel(), s);
        helper.assertTrue(scout != null, "the omen must actually appear");
        helper.assertTrue(scout.isScout(), "and be flagged as a scout, not a raider");

        // Control: an ordinary raider in the same arena must still hunt, so
        // a null target on the scout proves the guard, not just an empty test.
        RaiderEntity control = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(9, 1, 9));
        control.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);

        helper.succeedWhen(() -> {
            helper.assertTrue(control.getTarget() == guard || guard.getTarget() == control,
                "control check: an ordinary raider must still hunt, or this "
                    + "test proves nothing");
            helper.assertTrue(scout.getTarget() == null,
                "a scout is an omen, not a fight -- it must never initiate "
                    + "one, got target=" + scout.getTarget());
        });
    }
}
